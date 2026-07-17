package com.bludood.homeloop.playback

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.storage.StorageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Crop
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.LanczosResample
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.InAppFragmentedMp4Muxer
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.bludood.homeloop.MainActivity
import com.bludood.homeloop.R
import com.bludood.homeloop.data.OptimizationProfile
import com.bludood.homeloop.data.PlaybackRepository
import com.bludood.homeloop.data.PlaybackSnapshot
import com.bludood.homeloop.data.PlaybackStatus
import com.bludood.homeloop.data.SavedOptimization
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors
import java.util.concurrent.Future

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class OptimizationService : Service() {
    private lateinit var repository: PlaybackRepository
    private val handler = Handler(Looper.getMainLooper())
    private val preparationExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "VideoOptimizationPreparation")
    }
    private val progressHolder = ProgressHolder()
    private var preparationJob: Future<*>? = null
    private var transformer: Transformer? = null
    private var exportComposition: Composition? = null
    private var exportEncoderFactory: DefaultEncoderFactory? = null
    private var profile: OptimizationProfile? = null
    private var temporaryFile: File? = null
    private var outputFile: File? = null
    private var savedOptimization: SavedOptimization? = null
    private var fragmentedMuxerRetryAttempted = false
    private var resumePlaybackAfterOptimization = false
    private var running = false
    private val timeEstimator = EncodingTimeEstimator()

    private val progressPoll = object : Runnable {
        override fun run() {
            val activeTransformer = transformer ?: return
            repository.touchOptimizationHeartbeat()
            val progressState = activeTransformer.getProgress(progressHolder)
            if (progressState == Transformer.PROGRESS_STATE_AVAILABLE) {
                val progress = progressHolder.progress.coerceIn(0, 99)
                val remainingMs = timeEstimator.estimateRemainingMs(
                    progress,
                    SystemClock.elapsedRealtime(),
                )
                val estimate = remainingMs?.let {
                    "About ${formatRemainingTime(it)} remaining"
                } ?: "Estimating time remaining…"
                repository.updateOptimizationProgress(
                    progress,
                    "Encoding optimized copy · $progress% · $estimate",
                )
                updateNotification(progress, "$progress% · $estimate")
            }
            if (running) handler.postDelayed(this, PROGRESS_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        repository = PlaybackRepository(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.getStringExtra(EXTRA_ACTION)) {
            ACTION_CANCEL -> {
                cancelOptimization("Optimization cancelled")
                START_NOT_STICKY
            }
            else -> {
                if (!running) {
                    runCatching(::prepareOptimization).onFailure { error ->
                        failOptimization(
                            "Could not start optimization: " +
                                (error.message ?: error.javaClass.simpleName),
                        )
                    }
                }
                START_NOT_STICKY
            }
        }
    }

    private fun prepareOptimization() {
        running = true
        startForeground(0, "Preparing optimized copy")
        preparationJob = preparationExecutor.submit {
            val snapshot = repository.snapshot()
            if (snapshot.videoWidth <= 0 || snapshot.videoHeight <= 0) {
                repository.ensureVideoMetadata()
            }
            handler.post {
                if (!running) return@post
                runCatching(::startOptimization).onFailure { error ->
                    failOptimization(
                        "Could not start optimization: " +
                            (error.message ?: error.javaClass.simpleName),
                    )
                }
            }
        }
    }

    private fun startOptimization() {
        val snapshot = repository.snapshot()
        if (!snapshot.hasVideo) {
            failOptimization("Choose a video first")
            return
        }
        val sourceUri = repository.videoUri()
        val targetProfile = OptimizationPlanner.profile(this, snapshot)
        if (sourceUri == null || targetProfile == null) {
            failOptimization("The current video or widget size could not be resolved")
            return
        }
        val directory = File(filesDir, PlaybackRepository.OPTIMIZED_DIRECTORY).apply { mkdirs() }
        require(directory.isDirectory) { "The optimization folder could not be created" }
        savedOptimization = repository.savedOptimization(targetProfile)
        val requiredBytes = OptimizationStoragePolicy.requiredFreeBytes(targetProfile.estimatedBytes)
        if (!OptimizationStoragePolicy.hasEnoughSpace(
                targetProfile.estimatedBytes,
                allocatableBytes(directory),
            )
        ) {
            failOptimization(
                "Not enough free storage. Keep at least ${formatBytes(requiredBytes)} available " +
                    "for this optimization.",
            )
            return
        }
        val finalFile = File(directory, "optimized_${targetProfile.signature.take(16)}.mp4")
        val partialFile = File(directory, "${finalFile.name}.partial")
        runCatching { partialFile.delete() }
        profile = targetProfile
        outputFile = finalFile
        temporaryFile = partialFile
        repository.markOptimizationRunning(targetProfile)
        resumePlaybackAfterOptimization = shouldResumePlayback(snapshot)
        suspendPlaybackForOptimization()
        fragmentedMuxerRetryAttempted = false
        timeEstimator.reset(SystemClock.elapsedRealtime())

        val videoEffects = buildVideoEffects(snapshot, targetProfile)
        val mediaItem = MediaItem.Builder()
            .setUri(sourceUri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(snapshot.trimStartMs)
                    .setEndPositionMs(snapshot.trimEndMs)
                    .build(),
            )
            .build()
        val editedItem = EditedMediaItem.Builder(mediaItem)
            .setEffects(Effects(emptyList(), videoEffects))
            .build()
        val sequence = if (snapshot.inputDiagnostics.audioTrackCount > 0) {
            EditedMediaItemSequence.withAudioAndVideoFrom(listOf(editedItem))
        } else {
            EditedMediaItemSequence.withVideoFrom(listOf(editedItem))
        }
        exportComposition = Composition.Builder(sequence)
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            .build()
        exportEncoderFactory = DefaultEncoderFactory.Builder(this)
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder()
                    .setBitrate(targetProfile.videoBitrate)
                    .setiFrameIntervalSeconds(1f)
                    .build(),
            )
            .build()
        startExport(useFragmentedMuxer = false)
    }

    private fun startExport(useFragmentedMuxer: Boolean) {
        val composition = exportComposition
            ?: return failOptimization("Optimization composition was lost")
        val encoderFactory = exportEncoderFactory
            ?: return failOptimization("Optimization encoder settings were lost")
        val partialFile = temporaryFile
            ?: return failOptimization("Temporary output location was lost")
        val listener = object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                finishOptimization()
            }

            override fun onError(
                composition: Composition,
                exportResult: ExportResult,
                exportException: ExportException,
            ) {
                handleExportError(exportException)
            }
        }
        val builder = Transformer.Builder(this)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(encoderFactory)
            .setMaxDelayBetweenMuxerSamplesMs(MUXER_SAMPLE_TIMEOUT_MS)
            .addListener(listener)
        if (useFragmentedMuxer) {
            builder.setMuxerFactory(InAppFragmentedMp4Muxer.Factory())
        }
        val activeTransformer = builder.build()
        transformer = activeTransformer
        handler.removeCallbacks(progressPoll)
        runCatching {
            activeTransformer.start(composition, partialFile.absolutePath)
            handler.post(progressPoll)
        }.onFailure { error ->
            Log.e(TAG, "Could not start video export", error)
            failOptimization(
                "Could not start video export: ${error.message ?: error.javaClass.simpleName}",
            )
        }
    }

    private fun handleExportError(exportException: ExportException) {
        val detail = describeExportError(exportException)
        Log.e(TAG, detail, exportException)
        if (
            exportException.errorCode == ExportException.ERROR_CODE_MUXING_FAILED &&
            !fragmentedMuxerRetryAttempted
        ) {
            fragmentedMuxerRetryAttempted = true
            transformer = null
            handler.removeCallbacks(progressPoll)
            runCatching { temporaryFile?.delete() }
            repository.updateOptimizationProgress(
                0,
                "Standard MP4 muxer failed; retrying with resilient fragmented MP4",
            )
            updateNotification(0, "Retrying with resilient MP4 muxer")
            timeEstimator.reset(SystemClock.elapsedRealtime())
            handler.post { startExport(useFragmentedMuxer = true) }
            return
        }
        failOptimization(detail)
    }

    private fun describeExportError(exportException: ExportException): String {
        val errorCodeName = ExportException.getErrorCodeName(exportException.errorCode)
        val causeDetail = generateSequence(exportException.cause) { it.cause }
            .take(MAX_REPORTED_CAUSE_DEPTH)
            .map { cause ->
                val message = cause.message.orEmpty().trim()
                if (message.isBlank()) cause.javaClass.simpleName
                else "${cause.javaClass.simpleName}: $message"
            }
            .lastOrNull()
            ?: exportException.message
            ?: "Video export failed"
        val codecDetail = exportException.codecInfo?.let { " · codec: $it" }.orEmpty()
        return "$errorCodeName (${exportException.errorCode}) at " +
            "${progressHolder.progress.coerceIn(0, 99)}%: $causeDetail$codecDetail"
    }

    private fun shouldResumePlayback(snapshot: PlaybackSnapshot): Boolean =
        snapshot.timelineRunning && snapshot.status in PLAYBACK_STATUSES_TO_RESTORE

    private fun suspendPlaybackForOptimization() {
        if (!resumePlaybackAfterOptimization) return
        repository.pauseTimeline()
        repository.setStatus(PlaybackStatus.PAUSED, "Playback paused while optimizing")
        runCatching {
            startService(PlaybackService.intent(this, PlaybackService.Action.PAUSE))
        }.onFailure { error ->
            Log.w(TAG, "Could not suspend widget playback before optimization", error)
        }
    }

    private fun buildVideoEffects(
        snapshot: PlaybackSnapshot,
        target: OptimizationProfile,
    ): List<Effect> = buildList {
        if (target.targetFps + FPS_EPSILON < snapshot.sourceFrameRate) {
            add(FrameDropEffect.createDefaultFrameDropEffect(target.targetFps))
        }
        val crop = OptimizationPlanner.combinedCrop(snapshot, target)
        if (crop != com.bludood.homeloop.data.CropInsets()) {
            add(
                Crop(
                    -1f + 2f * crop.left / snapshot.videoWidth,
                    1f - 2f * crop.right / snapshot.videoWidth,
                    -1f + 2f * crop.bottom / snapshot.videoHeight,
                    1f - 2f * crop.top / snapshot.videoHeight,
                ),
            )
        }
        add(LanczosResample.scaleToFit(target.outputWidth, target.outputHeight))
    }

    private fun finishOptimization() {
        val activeProfile = profile ?: return failOptimization("Optimization profile was lost")
        val partial = temporaryFile ?: return failOptimization("Temporary output was lost")
        val output = outputFile ?: return failOptimization("Output location was lost")
        runCatching {
            require(partial.isFile && partial.length() > 0L) { "The encoder produced an empty file" }
            validateOutput(partial)
            runCatching {
                Files.move(
                    partial.toPath(),
                    output.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            }.getOrElse {
                Files.move(
                    partial.toPath(),
                    output.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            repository.completeOptimization(activeProfile, output)
            savedOptimization?.outputPath?.takeIf { it != output.absolutePath }?.let { oldPath ->
                val old = File(oldPath)
                val root = File(filesDir, PlaybackRepository.OPTIMIZED_DIRECTORY).canonicalPath
                if (old.canonicalPath.startsWith(root)) old.delete()
            }
        }.onFailure { error ->
            failOptimization(error.message ?: "Could not finalize optimized copy")
            return
        }
        running = false
        handler.removeCallbacks(progressPoll)
        clearExportState()
        updateNotification(100, "Optimized copy ready")
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        restorePlaybackIfNeeded()
        stopSelf()
    }

    private fun validateOutput(file: File) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            require(duration > 0L) { "The optimized file has no readable duration" }
        } finally {
            retriever.release()
        }
    }

    @Suppress("UsableSpace")
    private fun allocatableBytes(directory: File): Long = runCatching {
        val storageManager = getSystemService(StorageManager::class.java)
        storageManager.getAllocatableBytes(storageManager.getUuidForPath(directory))
    }.getOrElse { directory.usableSpace }

    private fun cancelOptimization(detail: String) {
        transformer?.cancel()
        persistOptimizationFailure(detail, cancelled = true)
        cleanupAndStop()
    }

    private fun failOptimization(detail: String) {
        persistOptimizationFailure(detail)
        cleanupAndStop()
    }

    private fun persistOptimizationFailure(detail: String, cancelled: Boolean = false) {
        val previous = savedOptimization
        if (previous != null) {
            repository.restoreSavedOptimization(previous, detail)
        } else {
            repository.failOptimization(detail, cancelled)
        }
    }

    private fun cleanupAndStop() {
        running = false
        preparationJob?.cancel(true)
        handler.removeCallbacks(progressPoll)
        runCatching { temporaryFile?.delete() }
        clearExportState()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        restorePlaybackIfNeeded()
        stopSelf()
    }

    private fun clearExportState() {
        preparationJob = null
        transformer = null
        exportComposition = null
        exportEncoderFactory = null
    }

    private fun restorePlaybackIfNeeded() {
        if (!resumePlaybackAfterOptimization) return
        resumePlaybackAfterOptimization = false
        repository.resumeTimeline()
        repository.setStatus(PlaybackStatus.STARTING, "Resuming after optimization")
        runCatching {
            ContextCompat.startForegroundService(
                this,
                PlaybackService.intent(this, PlaybackService.Action.RESUME),
            )
        }.onFailure { error ->
            Log.e(TAG, "Could not restore widget playback after optimization", error)
            repository.pauseTimeline()
            repository.setStatus(
                PlaybackStatus.ERROR,
                "Could not resume after optimization: ${error.message ?: error.javaClass.simpleName}",
            )
        }
    }

    @SuppressLint("InlinedApi")
    private fun startForeground(progress: Int, text: String) {
        // ServiceCompat 1.17 masks out the API 35 mediaProcessing bit on API 34+, which
        // turns it into FOREGROUND_SERVICE_TYPE_NONE and crashes target-SDK 34+ apps.
        // The three-argument platform API is available from our minSdk (29), and Android
        // explicitly supports using newer foreground-service type constants on older releases.
        startForeground(
            NOTIFICATION_ID,
            buildNotification(progress, text),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING,
        )
    }

    private fun updateNotification(progress: Int, text: String) {
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, buildNotification(progress, text))
    }

    private fun buildNotification(progress: Int, text: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_widget_sync)
            .setContentTitle("Optimizing video for the widget")
            .setContentText(text)
            .setProgress(100, progress.coerceIn(0, 100), progress <= 0)
            .setOngoing(progress < 100)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    31,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .addAction(
                R.drawable.ic_widget_stop,
                "Cancel",
                PendingIntent.getService(
                    this,
                    32,
                    intent(this, cancel = true),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Video optimization",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun formatRemainingTime(milliseconds: Long): String {
        val totalMinutes = (milliseconds + 30_000L) / 60_000L
        if (totalMinutes <= 0L) return "less than a minute"
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        return when {
            hours <= 0L -> "${minutes}m"
            minutes <= 0L -> "${hours}h"
            else -> "${hours}h ${minutes}m"
        }
    }

    private fun formatBytes(bytes: Long): String {
        val mebibytes = bytes / (1024.0 * 1024.0)
        return if (mebibytes >= 1024.0) {
            "${"%.2f".format(mebibytes / 1024.0)} GB"
        } else {
            "${"%.0f".format(mebibytes)} MB"
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        failOptimization("Android stopped the optimization after its processing time limit")
    }

    override fun onDestroy() {
        if (running) {
            running = false
            preparationJob?.cancel(true)
            transformer?.cancel()
            persistOptimizationFailure("Optimization was interrupted")
            runCatching { temporaryFile?.delete() }
            clearExportState()
            restorePlaybackIfNeeded()
        }
        handler.removeCallbacks(progressPoll)
        preparationExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "VideoOptimization"
        private const val CHANNEL_ID = "video_optimization"
        private const val NOTIFICATION_ID = 3402
        private const val EXTRA_ACTION = "optimization_action"
        private const val ACTION_START = "start"
        private const val ACTION_CANCEL = "cancel"
        private const val PROGRESS_INTERVAL_MS = 500L
        private const val FPS_EPSILON = 0.01f
        private const val MUXER_SAMPLE_TIMEOUT_MS = 60_000L
        private const val MAX_REPORTED_CAUSE_DEPTH = 6
        private val PLAYBACK_STATUSES_TO_RESTORE = setOf(
            PlaybackStatus.STARTING,
            PlaybackStatus.RENDERING,
            PlaybackStatus.SUSPENDED,
        )

        fun intent(context: Context, cancel: Boolean = false): Intent =
            Intent(context, OptimizationService::class.java).putExtra(
                EXTRA_ACTION,
                if (cancel) ACTION_CANCEL else ACTION_START,
            )
    }
}
