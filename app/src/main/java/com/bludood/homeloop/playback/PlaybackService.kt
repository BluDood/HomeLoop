package com.bludood.homeloop.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Debug
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.bludood.homeloop.MainActivity
import com.bludood.homeloop.R
import com.bludood.homeloop.data.PlaybackRepository
import com.bludood.homeloop.data.PlaybackSnapshot
import com.bludood.homeloop.data.PlaybackStatus
import com.bludood.homeloop.data.PlaybackOptions
import com.bludood.homeloop.data.CropInsets
import com.bludood.homeloop.data.AudioPipelineState
import com.bludood.homeloop.data.HiddenAudioPolicy
import com.bludood.homeloop.data.RenderingPolicy
import com.bludood.homeloop.data.RuntimeDiagnostics
import com.bludood.homeloop.data.ScalingFilter
import com.bludood.homeloop.data.VirtualTimeline
import com.bludood.homeloop.widget.VideoWidgetProvider
import com.bludood.homeloop.widget.WidgetFramePublisher
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

class PlaybackService : Service() {
    private lateinit var repository: PlaybackRepository
    private lateinit var publisher: WidgetFramePublisher
    private val decoderExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "WidgetVideoDecoder")
    }
    private val generation = AtomicInteger(0)
    private val audioGeneration = AtomicInteger(0)
    private var decoderJob: Future<*>? = null
    private var audioPlayer: MediaPlayer? = null
    private var foregroundStarted = false
    private var totalDecodedFrames = 0L
    private var totalSelectedFrames = 0L
    private var totalPublishedFrames = 0L
    private var totalCadenceSkippedFrames = 0L
    private var totalLateDroppedFrames = 0L
    private var diagnosticsStartedMs = 0L
    private var lastDiagnosticsWallMs = 0L
    private var lastDiagnosticsCpuMs = 0L
    private var latestAudioDriftMs = 0L
    private var audioPlaybackSpeed = 1f
    private var audioStartCount = 0
    private var audioRetryAttempts = 0
    private var latestAudioError = ""
    private var audioPipelineState = AudioPipelineState.STOPPED
    private var audioRangeStartMs = 0L
    private var audioRangeEndMs = 0L
    private lateinit var launcherVisibilityMonitor: LauncherVisibilityMonitor
    private var suspendedForLauncher = false
    private var launcherHiddenSinceMs = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var audioManager: AudioManager
    private lateinit var audioFocusRequest: AudioFocusRequest
    private var ownsAudioFocus = false
    private var audioSuppressedForFocus = false
    private var metadataRefreshAttempted = false
    private val metadataRefreshInFlight = AtomicBoolean(false)
    private var pendingMetadataAction: (() -> Unit)? = null
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        mainHandler.post { handleAudioFocusChange(change) }
    }
    private val audioRetry = Runnable { retryAudioAfterError() }
    private val audioSync = object : Runnable {
        override fun run() {
            val player = audioPlayer ?: return
            val snapshot = repository.snapshot()
            if (
                snapshot.status in ACTIVE_STATUSES &&
                snapshot.timelineRunning &&
                snapshot.durationMs > 0L
            ) {
                val actual = preciseAudioPositionMs(player)
                var nextSyncDelayMs = AUDIO_SYNC_INTERVAL_MS
                if (actual >= 0L) {
                    val target = audioTargetPositionMs(snapshot)
                    val outsideRange = actual < audioRangeStartMs - AUDIO_RANGE_TOLERANCE_MS ||
                        actual >= audioRangeEndMs
                    if (outsideRange) {
                        applyAudioSpeed(player, 1f)
                        runCatching {
                            player.seekTo(target, MediaPlayer.SEEK_CLOSEST)
                        }
                        latestAudioDriftMs = 0L
                        nextSyncDelayMs = AUDIO_BOUNDARY_RECHECK_MS
                    } else {
                        val drift = VirtualTimeline.circularDrift(
                            actualMs = actual,
                            targetMs = target,
                            durationMs = snapshot.durationMs,
                        )
                        latestAudioDriftMs = drift
                        val decision = AudioSyncController.decide(drift, target)
                        if (decision.seekPositionMs != null) {
                            applyAudioSpeed(player, 1f)
                            runCatching {
                                player.seekTo(decision.seekPositionMs, MediaPlayer.SEEK_CLOSEST)
                            }
                        } else {
                            applyAudioSpeed(player, decision.playbackSpeed)
                        }
                        nextSyncDelayMs = minOf(
                            AUDIO_SYNC_INTERVAL_MS,
                            (audioRangeEndMs - actual).coerceAtLeast(AUDIO_BOUNDARY_RECHECK_MS),
                        )
                    }
                }
                mainHandler.postDelayed(this, nextSyncDelayMs)
            }
        }
    }

    private val resourcePolicyPoll = object : Runnable {
        override fun run() {
            if (::repository.isInitialized && foregroundStarted) {
                repository.touchPlaybackHeartbeat()
                applyResourcePolicyFromForegroundState()
            }
            mainHandler.postDelayed(this, RESOURCE_POLICY_POLL_MS)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    stopRenderer(AudioPipelineState.SLEEPING)
                    if (repository.snapshot().timelineRunning) {
                        repository.setStatus(
                            PlaybackStatus.SUSPENDED,
                            "Screen off; timeline still advances",
                        )
                        updateNotification("Timeline active; decoder waits for the screen")
                    } else {
                        repository.setStatus(PlaybackStatus.PAUSED, "Playback paused")
                        VideoWidgetProvider.updateStatus(
                            this@PlaybackService,
                            "Paused — tap to resume",
                        )
                        updateNotification("Playback paused")
                    }
                }

                Intent.ACTION_SCREEN_ON -> startRendererIfPossible()

                AudioManager.ACTION_AUDIO_BECOMING_NOISY -> {
                    repository.setAudioEnabled(false)
                    stopAudio(AudioPipelineState.MUTED)
                    updateNotification("Audio muted after the output device disconnected")
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        repository = PlaybackRepository(this)
        publisher = WidgetFramePublisher(this)
        launcherVisibilityMonitor = LauncherVisibilityMonitor(this)
        audioManager = getSystemService(AudioManager::class.java)
        audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build(),
            )
            .setOnAudioFocusChangeListener(audioFocusChangeListener, mainHandler)
            .build()
        createNotificationChannel()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(screenReceiver, filter)
        }
        mainHandler.post(resourcePolicyPoll)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.getStringExtra(EXTRA_ACTION)
            ?.let { runCatching { Action.valueOf(it) }.getOrNull() }
            ?: Action.START
        if (action == Action.STOP) {
            stopRenderer()
            repository.setStatus(PlaybackStatus.IDLE, "Decoder stopped; timeline still advances")
            stopForegroundAndSelf()
            return START_NOT_STICKY
        }

        if (action == Action.PAUSE) {
            repository.pauseTimeline()
            stopRenderer()
            repository.setStatus(PlaybackStatus.PAUSED, "Playback paused")
            stopForegroundAndSelf()
            return START_NOT_STICKY
        }

        if (action == Action.APPLY_AUDIO) {
            if (!foregroundStarted) {
                stopSelf()
                return START_NOT_STICKY
            }
            applyAudioSetting()
            return START_NOT_STICKY
        }

        if (action == Action.APPLY_RESOURCE_POLICY) {
            if (!foregroundStarted) {
                stopSelf()
                return START_NOT_STICKY
            }
            launcherVisibilityMonitor.reset()
            launcherHiddenSinceMs = 0L
            applyResourcePolicyFromForegroundState(force = true)
            return START_NOT_STICKY
        }

        if (action in OPTIMIZATION_BLOCKED_ACTIONS && repository.isOptimizationRunning()) {
            repository.pauseTimeline()
            stopRenderer()
            repository.setStatus(PlaybackStatus.PAUSED, "Playback paused while optimizing")
            stopForegroundAndSelf()
            return START_NOT_STICKY
        }

        return try {
            ensureForeground("Preparing video…")
            if (action == Action.RENDER_PAUSED_FRAME) {
                renderPausedFrame()
                return START_NOT_STICKY
            }
            if (action == Action.START || action == Action.RESUME) repository.resumeTimeline()
            if (action == Action.RESTART) stopRenderer()
            if (action == Action.RESTART_VIDEO) stopVideoDecoder()
            startRendererIfPossible()
            START_NOT_STICKY
        } catch (exception: RuntimeException) {
            Log.e(TAG, "Could not start foreground playback", exception)
            repository.setStatus(
                PlaybackStatus.ERROR,
                exception.message ?: "Could not start foreground playback",
            )
            VideoWidgetProvider.updateStatus(this, "Playback error — open the app")
            if (foregroundStarted) {
                updateNotification("Playback error")
            } else {
                stopSelf()
            }
            START_NOT_STICKY
        }
    }

    private fun startRendererIfPossible() {
        var snapshot = repository.snapshot()
        val metadataNeeded =
            (snapshot.resolution.isAuto && (snapshot.videoWidth <= 0 || snapshot.videoHeight <= 0)) ||
            (snapshot.frameRate.isSourceBased && snapshot.sourceFrameRate <= 0f) ||
            !snapshot.inputDiagnostics.hasData
        if (metadataNeeded && !metadataRefreshAttempted) {
            refreshMetadataThen(::startRendererIfPossible)
            return
        }
        if (!snapshot.hasVideo) {
            repository.setStatus(PlaybackStatus.ERROR, "Choose a video first")
            VideoWidgetProvider.updateStatus(this, "Choose a video in the app")
            stopForegroundAndSelf()
            return
        }
        if (!snapshot.timelineRunning) {
            repository.setStatus(PlaybackStatus.PAUSED, "Playback paused")
            stopForegroundAndSelf()
            return
        }
        if (!VideoWidgetProvider.hasWidgets(this)) {
            repository.setStatus(PlaybackStatus.SUSPENDED, "Add the widget, then start again")
            stopForegroundAndSelf()
            return
        }
        val powerManager = getSystemService(PowerManager::class.java)
        if (powerManager != null && !powerManager.isInteractive) {
            repository.setStatus(PlaybackStatus.SUSPENDED, "Screen off; timeline still advances")
            updateNotification("Timeline active; decoder waits for the screen")
            return
        }
        if (
            snapshot.renderingPolicy == RenderingPolicy.HOME_SCREEN_ONLY &&
            LauncherVisibilityMonitor.hasUsageAccess(this) &&
            launcherVisibilityMonitor.isLauncherForeground() == false
        ) {
            suspendForLauncher(snapshot)
            return
        }
        suspendedForLauncher = false
        launcherHiddenSinceMs = 0L
        if (decoderJob?.isDone == false) return

        val profile = OptimizationPlanner.profile(this, snapshot)
        val optimizedUri = repository.optimizedUri(profile)
        val uri = optimizedUri ?: repository.videoUri() ?: run {
            repository.setStatus(PlaybackStatus.ERROR, "Saved video access is invalid")
            return
        }
        val outputSize = AutoResolutionResolver.resolve(this, snapshot)
        val frameRateLabel = snapshot.frameRate.displayLabel(snapshot.sourceFrameRate)
        val decoderFrameRate = if (optimizedUri != null) {
            PlaybackOptions.optimizedFrameRate
        } else {
            snapshot.frameRate
        }
        val decoderSourceFrameRate = if (optimizedUri != null) {
            profile?.targetFps ?: snapshot.sourceFrameRate
        } else {
            snapshot.sourceFrameRate
        }
        val decoderCrop = if (optimizedUri != null) CropInsets() else snapshot.cropInsets
        val decoderUpscaleFilter = if (optimizedUri != null) ScalingFilter.BILINEAR
        else snapshot.upscaleFilter
        val decoderDownscaleFilter = if (optimizedUri != null) ScalingFilter.BILINEAR
        else snapshot.downscaleFilter
        val decoderEndPositionMs = if (optimizedUri != null) {
            snapshot.durationMs
        } else {
            snapshot.trimEndMs
        }
        val workerGeneration = generation.incrementAndGet()
        val shouldStop = {
            generation.get() != workerGeneration || Thread.currentThread().isInterrupted
        }
        repository.setStatus(
            PlaybackStatus.RENDERING,
            "$frameRateLabel · ${outputSize.label}" +
                if (optimizedUri != null) " · Optimized"
                else if (snapshot.resolution.isAuto) " · Auto" else "",
        )
        updateNotification("Rendering $frameRateLabel at ${outputSize.label}")
        resetRuntimeDiagnostics()
        if (snapshot.audioEnabled && audioPlayer == null) {
            startAudio(uri, optimizedCopy = optimizedUri != null)
        }
        if (!snapshot.audioEnabled) stopAudio(AudioPipelineState.MUTED)

        decoderJob = decoderExecutor.submit {
            while (!shouldStop()) {
                val clockReadStartNs = SystemClock.elapsedRealtimeNanos()
                val currentSnapshot = repository.snapshot()
                val decoderStartPositionMs = if (optimizedUri != null) {
                    currentSnapshot.currentPositionMs
                } else {
                    currentSnapshot.sourcePositionMs
                }
                val clockReadEndNs = SystemClock.elapsedRealtimeNanos()
                val decoder = VideoFrameDecoder(
                    context = this,
                    uri = uri,
                    startPositionMs = decoderStartPositionMs,
                    endPositionMs = decoderEndPositionMs,
                    playbackAnchorRealtimeNs = clockReadStartNs +
                        (clockReadEndNs - clockReadStartNs) / 2L,
                    frameRate = decoderFrameRate,
                    knownSourceFrameRate = decoderSourceFrameRate,
                    outputWidth = outputSize.width,
                    outputHeight = outputSize.height,
                    cropInsets = decoderCrop,
                    upscaleFilter = decoderUpscaleFilter,
                    downscaleFilter = decoderDownscaleFilter,
                    onFrame = { bitmap, _ ->
                        if (shouldStop()) {
                            bitmap.recycle()
                            null
                        } else {
                            publisher.publish(bitmap)
                        }
                    },
                    onInfo = { Log.d(TAG, it) },
                    onDiagnostics = ::recordDiagnostics,
                    shouldStop = shouldStop,
                )
                try {
                    decoder.decodeUntilEndOrStopped()
                } catch (exception: Exception) {
                    Log.e(TAG, "Widget decoder failed", exception)
                    if (!shouldStop()) {
                        val detail = exception.message ?: exception.javaClass.simpleName
                        repository.setStatus(PlaybackStatus.ERROR, "Decoder error: $detail")
                        VideoWidgetProvider.updateStatus(this, "Decoder error — open the app")
                        updateNotification("Decoder error: $detail")
                        stopAudio()
                        markRuntimeInactive()
                        mainHandler.post(::stopForegroundAndSelf)
                    }
                    break
                }
            }
        }
    }

    @Synchronized
    private fun startAudio(
        uri: Uri,
        optimizedCopy: Boolean,
        retrying: Boolean = false,
    ) {
        mainHandler.removeCallbacks(audioRetry)
        if (!retrying) {
            audioRetryAttempts = 0
            latestAudioError = ""
        }
        releaseAudioPlayer(cancelRetry = false)
        if (!requestAudioFocus()) {
            latestAudioError = "Audio focus is currently unavailable"
            audioPipelineState = AudioPipelineState.UNAVAILABLE
            updateAudioDiagnostics()
            return
        }
        audioPipelineState = AudioPipelineState.STARTING
        updateAudioDiagnostics()
        val initialSnapshot = repository.snapshot()
        audioRangeStartMs = if (optimizedCopy) 0L else initialSnapshot.trimStartMs
        audioRangeEndMs = if (optimizedCopy) {
            initialSnapshot.durationMs
        } else {
            initialSnapshot.trimEndMs
        }
        audioStartCount++
        updateAudioDiagnostics()
        val workerAudioGeneration = audioGeneration.get()
        val shouldStop = {
            audioGeneration.get() != workerAudioGeneration ||
                !repository.snapshot().timelineRunning
        }
        val player = MediaPlayer()
        audioPlayer = player
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .build(),
        )
        player.isLooping = false
        var initialCorrections = 0
        player.setOnSeekCompleteListener { prepared ->
            synchronized(this) {
                if (audioPlayer !== prepared || shouldStop()) return@setOnSeekCompleteListener
                val snapshot = repository.snapshot()
                val actual = preciseAudioPositionMs(prepared)
                val drift = VirtualTimeline.circularDrift(
                    actualMs = actual,
                    targetMs = audioTargetPositionMs(snapshot),
                    durationMs = snapshot.durationMs,
                )
                runCatching {
                    if (
                        !prepared.isPlaying &&
                        abs(drift) > AUDIO_START_TOLERANCE_MS &&
                        initialCorrections < MAX_INITIAL_AUDIO_CORRECTIONS
                    ) {
                        initialCorrections++
                        prepared.seekTo(audioTargetPositionMs(snapshot), MediaPlayer.SEEK_CLOSEST)
                    } else {
                        if (!prepared.isPlaying) prepared.start()
                        audioPipelineState = AudioPipelineState.PLAYING
                        updateAudioDiagnostics()
                        audioRetryAttempts = 0
                        applyAudioSpeed(prepared, 1f)
                        mainHandler.removeCallbacks(audioSync)
                        mainHandler.postDelayed(audioSync, AUDIO_SYNC_INTERVAL_MS)
                    }
                }.onFailure { error ->
                    handleAudioFailure(
                        prepared,
                        "Audio seek/start failed: ${error.message ?: error.javaClass.simpleName}",
                    )
                }
            }
        }
        player.setOnPreparedListener { prepared ->
            synchronized(this) {
                if (audioPlayer !== prepared || shouldStop()) {
                    prepared.release()
                    if (audioPlayer === prepared) audioPlayer = null
                } else {
                    // MediaPlayer preparation is asynchronous. Seek to the current virtual clock
                    // now, not to the timestamp captured before preparation began.
                    runCatching {
                        prepared.seekTo(
                            audioTargetPositionMs(repository.snapshot()),
                            MediaPlayer.SEEK_CLOSEST,
                        )
                    }.onFailure { error ->
                        handleAudioFailure(
                            prepared,
                            "Audio initial seek failed: " +
                                (error.message ?: error.javaClass.simpleName),
                        )
                    }
                }
            }
        }
        player.setOnErrorListener { failed, what, extra ->
            val detail = "MediaPlayer error $what/$extra"
            Log.w(TAG, detail)
            handleAudioFailure(failed, detail)
            true
        }
        player.setOnCompletionListener { completed ->
            synchronized(this) {
                if (audioPlayer !== completed || shouldStop()) return@setOnCompletionListener
                runCatching {
                    completed.seekTo(
                        audioTargetPositionMs(repository.snapshot()),
                        MediaPlayer.SEEK_CLOSEST,
                    )
                }.onFailure { error ->
                    handleAudioFailure(
                        completed,
                        "Audio loop failed: ${error.message ?: error.javaClass.simpleName}",
                    )
                }
            }
        }
        try {
            player.setDataSource(this, uri)
            player.prepareAsync()
        } catch (exception: Exception) {
            Log.w(TAG, "Could not start audio", exception)
            handleAudioFailure(
                player,
                "Could not start audio: ${exception.message ?: exception.javaClass.simpleName}",
            )
        }
    }

    @Synchronized
    private fun stopAudio(state: AudioPipelineState = AudioPipelineState.STOPPED) {
        audioSuppressedForFocus = false
        audioPipelineState = state
        releaseAudioPlayer(cancelRetry = true)
        updateAudioDiagnostics()
    }

    @Synchronized
    private fun releaseAudioPlayer(cancelRetry: Boolean, abandonFocus: Boolean = true) {
        if (cancelRetry) {
            mainHandler.removeCallbacks(audioRetry)
            audioRetryAttempts = 0
        }
        audioGeneration.incrementAndGet()
        mainHandler.removeCallbacks(audioSync)
        audioPlayer?.let { player ->
            runCatching { player.stop() }
            runCatching { player.release() }
        }
        audioPlayer = null
        audioPlaybackSpeed = 1f
        latestAudioDriftMs = 0L
        if (abandonFocus) abandonAudioFocus()
    }

    private fun requestAudioFocus(): Boolean {
        if (ownsAudioFocus) return true
        ownsAudioFocus = audioManager.requestAudioFocus(audioFocusRequest) ==
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return ownsAudioFocus
    }

    private fun abandonAudioFocus() {
        if (!ownsAudioFocus) return
        audioManager.abandonAudioFocusRequest(audioFocusRequest)
        ownsAudioFocus = false
    }

    @Synchronized
    private fun handleAudioFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                ownsAudioFocus = true
                audioPlayer?.setVolume(1f, 1f)
                if (audioSuppressedForFocus) {
                    audioSuppressedForFocus = false
                    applyAudioSetting()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                audioPlayer?.setVolume(AUDIO_DUCK_VOLUME, AUDIO_DUCK_VOLUME)
            }
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            -> {
                ownsAudioFocus = false
                audioSuppressedForFocus = true
                audioPipelineState = AudioPipelineState.SLEEPING
                releaseAudioPlayer(cancelRetry = true, abandonFocus = false)
                updateAudioDiagnostics()
            }
        }
    }

    @Synchronized
    private fun handleAudioFailure(player: MediaPlayer, detail: String) {
        if (audioPlayer !== player) {
            runCatching { player.release() }
            return
        }
        latestAudioError = detail
        audioPipelineState = AudioPipelineState.UNAVAILABLE
        releaseAudioPlayer(cancelRetry = false)
        updateAudioDiagnostics()
        val snapshot = repository.snapshot()
        if (!shouldPlayAudio(snapshot) || audioRetryAttempts >= MAX_AUDIO_RETRIES) return
        audioRetryAttempts++
        mainHandler.removeCallbacks(audioRetry)
        mainHandler.postDelayed(
            audioRetry,
            AUDIO_RETRY_BASE_DELAY_MS * audioRetryAttempts,
        )
    }

    private fun retryAudioAfterError() {
        val snapshot = repository.snapshot()
        if (!shouldPlayAudio(snapshot)) return
        val profile = OptimizationPlanner.profile(this, snapshot)
        val optimizedUri = repository.optimizedUri(profile)
        val uri = optimizedUri ?: repository.videoUri() ?: return
        startAudio(uri, optimizedCopy = optimizedUri != null, retrying = true)
    }

    private fun shouldPlayAudio(snapshot: PlaybackSnapshot): Boolean =
        snapshot.audioEnabled &&
            snapshot.timelineRunning &&
            snapshot.status in ACTIVE_STATUSES &&
            !(suspendedForLauncher && snapshot.hiddenAudioPolicy == HiddenAudioPolicy.SUSPEND)

    private fun audioTargetPositionMs(snapshot: PlaybackSnapshot): Long =
        (audioRangeStartMs + snapshot.currentPositionMs).coerceIn(
            audioRangeStartMs,
            (audioRangeEndMs - 1L).coerceAtLeast(audioRangeStartMs),
        )

    private fun updateAudioDiagnostics() {
        if (!::repository.isInitialized) return
        val diagnostics = repository.snapshot().runtimeDiagnostics
        repository.setRuntimeDiagnostics(
            diagnostics.copy(
                audioRestartCount = (audioStartCount - 1).coerceAtLeast(0),
                audioError = latestAudioError,
                audioState = audioPipelineState,
            ),
        )
    }

    private fun preciseAudioPositionMs(player: MediaPlayer): Long {
        val timestamp = runCatching { player.timestamp }.getOrNull()
        if (timestamp != null && timestamp.mediaClockRate > 0f) {
            val elapsedNs = System.nanoTime() - timestamp.anchorSystemNanoTime
            if (elapsedNs >= 0L) {
                val elapsedMediaUs = elapsedNs / 1_000.0 * timestamp.mediaClockRate
                return ((timestamp.anchorMediaTimeUs + elapsedMediaUs) / 1_000.0).toLong()
            }
        }
        return runCatching { player.currentPosition.toLong() }.getOrDefault(0L)
    }

    private fun applyAudioSpeed(player: MediaPlayer, speed: Float) {
        if (abs(audioPlaybackSpeed - speed) < 0.0005f) return
        runCatching {
            player.playbackParams = player.playbackParams
                .setPitch(1f)
                .setSpeed(speed)
            audioPlaybackSpeed = speed
        }.onFailure {
            Log.d(TAG, "Audio speed correction is unavailable", it)
        }
    }

    private fun applyAudioSetting() {
        val snapshot = repository.snapshot()
        if (!snapshot.audioEnabled) {
            stopAudio(AudioPipelineState.MUTED)
            return
        }
        audioSuppressedForFocus = false
        if (suspendedForLauncher && snapshot.hiddenAudioPolicy == HiddenAudioPolicy.SUSPEND) {
            stopAudio(AudioPipelineState.SLEEPING)
            return
        }
        if (snapshot.status in ACTIVE_STATUSES && snapshot.timelineRunning) {
            val profile = OptimizationPlanner.profile(this, snapshot)
            val optimizedUri = repository.optimizedUri(profile)
            val uri = optimizedUri ?: repository.videoUri() ?: return
            startAudio(uri, optimizedCopy = optimizedUri != null)
        }
    }

    private fun applyResourcePolicyFromForegroundState(force: Boolean = false) {
        val snapshot = repository.snapshot()
        if (!snapshot.timelineRunning || snapshot.status !in ACTIVE_STATUSES) return
        if (
            snapshot.renderingPolicy != RenderingPolicy.HOME_SCREEN_ONLY ||
            !LauncherVisibilityMonitor.hasUsageAccess(this)
        ) {
            launcherHiddenSinceMs = 0L
            if (suspendedForLauncher) {
                suspendedForLauncher = false
                startRendererIfPossible()
            }
            return
        }
        when (launcherVisibilityMonitor.isLauncherForeground()) {
            true -> {
                launcherHiddenSinceMs = 0L
                if (suspendedForLauncher) {
                    suspendedForLauncher = false
                    startRendererIfPossible()
                }
            }
            false -> {
                val now = SystemClock.elapsedRealtime()
                if (launcherHiddenSinceMs == 0L) launcherHiddenSinceMs = now
                if (
                    !suspendedForLauncher &&
                    (force || now - launcherHiddenSinceMs >= LAUNCHER_EXIT_GRACE_MS)
                ) {
                    suspendForLauncher(snapshot)
                } else if (suspendedForLauncher && force) {
                    suspendForLauncher(snapshot)
                }
            }
            null -> {
                // Detection intentionally fails open so an OEM/API quirk cannot freeze playback.
                launcherHiddenSinceMs = 0L
                if (suspendedForLauncher) {
                    suspendedForLauncher = false
                    startRendererIfPossible()
                }
            }
        }
    }

    private fun suspendForLauncher(snapshot: PlaybackSnapshot) {
        suspendedForLauncher = true
        stopVideoDecoder()
        val continueAudio = snapshot.audioEnabled &&
            snapshot.hiddenAudioPolicy == HiddenAudioPolicy.CONTINUE
        if (continueAudio) {
            if (audioPlayer == null) {
                val profile = OptimizationPlanner.profile(this, snapshot)
                val optimizedUri = repository.optimizedUri(profile)
                val uri = optimizedUri ?: repository.videoUri()
                if (uri != null) startAudio(uri, optimizedCopy = optimizedUri != null)
            }
        } else {
            stopAudio(AudioPipelineState.SLEEPING)
        }
        val detail = if (continueAudio) {
            "Home is not open; video sleeps and audio continues"
        } else {
            "Home is not open; media pipeline sleeps while the timeline advances"
        }
        repository.setStatus(PlaybackStatus.SUSPENDED, detail)
        updateNotification(detail)
    }

    private fun renderPausedFrame() {
        stopRenderer()
        var snapshot = repository.snapshot()
        if (!snapshot.hasVideo || !VideoWidgetProvider.hasWidgets(this)) {
            stopForegroundAndSelf()
            return
        }
        if ((snapshot.videoWidth <= 0 || snapshot.videoHeight <= 0) && !metadataRefreshAttempted) {
            refreshMetadataThen(::renderPausedFrame)
            return
        }
        val profile = OptimizationPlanner.profile(this, snapshot)
        val optimizedUri = repository.optimizedUri(profile)
        val uri = optimizedUri ?: repository.videoUri() ?: return
        val outputSize = AutoResolutionResolver.resolve(this, snapshot)
        val workerGeneration = generation.incrementAndGet()
        val delivered = AtomicBoolean(false)
        val shouldStop = {
            delivered.get() || generation.get() != workerGeneration ||
                Thread.currentThread().isInterrupted
        }
        decoderJob = decoderExecutor.submit {
            try {
                VideoFrameDecoder(
                    context = this,
                    uri = uri,
                    startPositionMs = if (optimizedUri != null) {
                        snapshot.currentPositionMs
                    } else {
                        snapshot.sourcePositionMs
                    },
                    endPositionMs = if (optimizedUri != null) {
                        snapshot.durationMs
                    } else {
                        snapshot.trimEndMs
                    },
                    frameRate = if (optimizedUri != null) {
                        PlaybackOptions.optimizedFrameRate
                    } else {
                        PlaybackOptions.defaultFrameRate
                    },
                    knownSourceFrameRate = if (optimizedUri != null) {
                        profile?.targetFps ?: snapshot.sourceFrameRate
                    } else {
                        snapshot.sourceFrameRate
                    },
                    outputWidth = outputSize.width,
                    outputHeight = outputSize.height,
                    cropInsets = if (optimizedUri != null) CropInsets() else snapshot.cropInsets,
                    upscaleFilter = if (optimizedUri != null) ScalingFilter.BILINEAR
                    else snapshot.upscaleFilter,
                    downscaleFilter = if (optimizedUri != null) ScalingFilter.BILINEAR
                    else snapshot.downscaleFilter,
                    onFrame = { bitmap, _ ->
                        if (generation.get() != workerGeneration) {
                            bitmap.recycle()
                            null
                        } else {
                            val reusable = publisher.publish(bitmap)
                            delivered.set(true)
                            reusable
                        }
                    },
                    onInfo = { Log.d(TAG, it) },
                    onDiagnostics = {},
                    shouldStop = shouldStop,
                    realtime = false,
                ).decodeUntilEndOrStopped()
            } catch (exception: Exception) {
                Log.w(TAG, "Could not render paused seek frame", exception)
            } finally {
                repository.setStatus(PlaybackStatus.PAUSED, "Playback paused")
                mainHandler.post {
                    if (!repository.snapshot().timelineRunning) stopForegroundAndSelf()
                }
            }
        }
    }

    private fun refreshMetadataThen(action: () -> Unit) {
        pendingMetadataAction = action
        if (!metadataRefreshInFlight.compareAndSet(false, true)) return
        metadataRefreshAttempted = true
        repository.setStatus(PlaybackStatus.STARTING, "Reading video metadata")
        updateNotification("Reading video metadata…")
        decoderExecutor.submit {
            repository.ensureVideoMetadata()
            mainHandler.post {
                metadataRefreshInFlight.set(false)
                val continuation = pendingMetadataAction
                pendingMetadataAction = null
                if (foregroundStarted) continuation?.invoke()
            }
        }
    }

    @Synchronized
    private fun resetRuntimeDiagnostics() {
        totalDecodedFrames = 0L
        totalSelectedFrames = 0L
        totalPublishedFrames = 0L
        totalCadenceSkippedFrames = 0L
        totalLateDroppedFrames = 0L
        latestAudioDriftMs = 0L
        audioStartCount = if (audioPlayer == null) 0 else 1
        diagnosticsStartedMs = SystemClock.elapsedRealtime()
        lastDiagnosticsWallMs = diagnosticsStartedMs
        lastDiagnosticsCpuMs = Process.getElapsedCpuTime()
        repository.resetRuntimeDiagnostics(active = true)
        updateAudioDiagnostics()
    }

    @Synchronized
    private fun recordDiagnostics(delta: DecoderDiagnosticsDelta) {
        totalDecodedFrames += delta.decodedFrames
        totalSelectedFrames += delta.selectedFrames
        totalPublishedFrames += delta.publishedFrames
        totalCadenceSkippedFrames += delta.cadenceSkippedFrames
        totalLateDroppedFrames += delta.lateDroppedFrames
        val now = SystemClock.elapsedRealtime()
        val cpuNow = Process.getElapsedCpuTime()
        val wallDelta = (now - lastDiagnosticsWallMs).coerceAtLeast(1L)
        val cpuDelta = (cpuNow - lastDiagnosticsCpuMs).coerceAtLeast(0L)
        val cpuPercent = (
            cpuDelta * 100f / wallDelta / Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        ).coerceIn(0f, 100f)
        val memory = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        repository.setRuntimeDiagnostics(
            RuntimeDiagnostics(
                decodedFrames = totalDecodedFrames,
                selectedFrames = totalSelectedFrames,
                publishedFrames = totalPublishedFrames,
                cadenceSkippedFrames = totalCadenceSkippedFrames,
                lateDroppedFrames = totalLateDroppedFrames,
                decodeFps = if (delta.elapsedMs > 0L) {
                    delta.decodedFrames * 1_000f / delta.elapsedMs
                } else {
                    0f
                },
                publishFps = if (delta.elapsedMs > 0L) {
                    delta.publishedFrames * 1_000f / delta.elapsedMs
                } else {
                    0f
                },
                appCpuPercent = cpuPercent,
                appRamMb = memory.totalPss / 1_024f,
                audioDriftMs = latestAudioDriftMs,
                audioRestartCount = (audioStartCount - 1).coerceAtLeast(0),
                audioError = latestAudioError,
                audioState = audioPipelineState,
                sampleElapsedMs = now - diagnosticsStartedMs,
                active = true,
            ),
        )
        lastDiagnosticsWallMs = now
        lastDiagnosticsCpuMs = cpuNow
    }

    private fun markRuntimeInactive() {
        if (!::repository.isInitialized) return
        val current = repository.snapshot().runtimeDiagnostics
        repository.setRuntimeDiagnostics(
            current.copy(active = false, decodeFps = 0f, publishFps = 0f, appCpuPercent = 0f),
        )
    }

    private fun stopVideoDecoder() {
        generation.incrementAndGet()
        decoderJob?.cancel(true)
        decoderJob = null
        markRuntimeInactive()
    }

    private fun stopRenderer(audioState: AudioPipelineState = AudioPipelineState.STOPPED) {
        stopVideoDecoder()
        stopAudio(audioState)
    }

    private fun stopForegroundAndSelf() {
        if (foregroundStarted) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
        stopSelf()
    }

    private fun ensureForeground(text: String) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(text),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
        foregroundStarted = true
        repository.touchPlaybackHeartbeat()
    }

    private fun updateNotification(text: String) {
        if (!foregroundStarted) return
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_widget_play)
        .setContentTitle(getString(R.string.app_name))
        .setContentText(text)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                10,
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setOnlyAlertOnce(true)
        .addAction(
            R.drawable.ic_widget_stop,
            getString(R.string.stop_decoder),
            PendingIntent.getService(
                this,
                11,
                intent(this, Action.STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .build()

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.channel_description)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        stopRenderer()
        mainHandler.removeCallbacks(resourcePolicyPoll)
        publisher.close()
        decoderExecutor.shutdownNow()
        runCatching { unregisterReceiver(screenReceiver) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    enum class Action {
        START,
        STOP,
        RESTART,
        PAUSE,
        RESUME,
        APPLY_AUDIO,
        APPLY_RESOURCE_POLICY,
        RESTART_VIDEO,
        RENDER_PAUSED_FRAME,
    }

    companion object {
        private const val TAG = "HomeLoopPlayback"
        private const val CHANNEL_ID = "widget_playback"
        private const val NOTIFICATION_ID = 3401
        private const val EXTRA_ACTION = "playback_action"
        private const val AUDIO_SYNC_INTERVAL_MS = 500L
        private const val AUDIO_BOUNDARY_RECHECK_MS = 30L
        private const val AUDIO_RANGE_TOLERANCE_MS = 100L
        private const val RESOURCE_POLICY_POLL_MS = 750L
        private const val LAUNCHER_EXIT_GRACE_MS = 1_500L
        private const val AUDIO_START_TOLERANCE_MS = 40L
        private const val MAX_INITIAL_AUDIO_CORRECTIONS = 3
        private const val MAX_AUDIO_RETRIES = 3
        private const val AUDIO_RETRY_BASE_DELAY_MS = 300L
        private const val AUDIO_DUCK_VOLUME = 0.2f
        private val ACTIVE_STATUSES = setOf(
            PlaybackStatus.STARTING,
            PlaybackStatus.RENDERING,
            PlaybackStatus.SUSPENDED,
        )
        private val OPTIMIZATION_BLOCKED_ACTIONS = setOf(
            Action.START,
            Action.RESUME,
            Action.RESTART,
            Action.RESTART_VIDEO,
            Action.RENDER_PAUSED_FRAME,
        )

        fun intent(context: Context, action: Action) =
            Intent(context, PlaybackService::class.java).putExtra(EXTRA_ACTION, action.name)
    }
}
