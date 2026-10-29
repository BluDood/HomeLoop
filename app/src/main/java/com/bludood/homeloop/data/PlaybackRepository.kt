package com.bludood.homeloop.data

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.Settings
import android.system.Os
import android.system.OsConstants
import androidx.core.content.edit
import androidx.core.net.toUri
import com.bludood.homeloop.playback.BlackBarDetector
import com.bludood.homeloop.playback.OptimizationService
import com.bludood.homeloop.playback.PlaybackController
import java.io.File
import java.util.concurrent.ThreadLocalRandom

class PlaybackRepository(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun importVideo(uri: Uri): Result<String> = runCatching {
        try {
            appContext.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
        }

        val metadata = readMetadata(uri)
        require(metadata.durationMs > 0L) { "This file does not contain a readable video duration." }
        val displayName = readDisplayName(uri)
        deleteOptimizedFile()

        preferences.edit {
            putString(KEY_URI, uri.toString())
            putString(KEY_NAME, displayName)
            putLong(KEY_DURATION, metadata.durationMs)
            putLong(KEY_TRIM_START, 0L)
            putLong(KEY_TRIM_END, metadata.durationMs)
            putInt(KEY_VIDEO_WIDTH, metadata.width)
            putInt(KEY_VIDEO_HEIGHT, metadata.height)
            putFloat(KEY_SOURCE_FRAME_RATE, metadata.frameRate)
            putInputDiagnostics(this, metadata.diagnostics)
            putInt(KEY_CROP_TOP, 0)
            putInt(KEY_CROP_RIGHT, 0)
            putInt(KEY_CROP_BOTTOM, 0)
            putInt(KEY_CROP_LEFT, 0)
            putLong(KEY_ANCHOR_POSITION, 0L)
            putLong(KEY_ANCHOR_ELAPSED, SystemClock.elapsedRealtime())
            putLong(KEY_ANCHOR_WALL, System.currentTimeMillis())
            putInt(KEY_ANCHOR_BOOT, bootCount())
            putBoolean(KEY_TIMELINE_RUNNING, true)
            putString(KEY_STATUS, PlaybackStatus.IDLE.name)
            putString(KEY_STATUS_DETAIL, "Video ready")
            clearOptimization(this)
            clearRuntime(this)
        }
        displayName
    }

    fun snapshot(): PlaybackSnapshot {
        val sourceDurationMs = preferences.getLong(KEY_DURATION, 0L).coerceAtLeast(0L)
        val trimRange = trimRange(sourceDurationMs)
        val durationMs = (trimRange.endMs - trimRange.startMs).coerceAtLeast(0L)
        val frameRateId = preferences.getString(KEY_FRAME_RATE_MODE, null)
        val frameRate = PlaybackOptions.frameRates.firstOrNull { it.id == frameRateId }
            ?: PlaybackOptions.defaultFrameRate
        val autoScale = preferences.getInt(KEY_AUTO_SCALE, 100)
        val resolution = PlaybackOptions.resolutions.firstOrNull { it.autoScalePercent == autoScale }
            ?: PlaybackOptions.defaultResolution
        val timelineRunning = preferences.getBoolean(KEY_TIMELINE_RUNNING, true)
        val videoWidth = preferences.getInt(KEY_VIDEO_WIDTH, 0).coerceAtLeast(0)
        val videoHeight = preferences.getInt(KEY_VIDEO_HEIGHT, 0).coerceAtLeast(0)
        val cropInsets = CropInsets(
            top = preferences.getInt(KEY_CROP_TOP, 0),
            right = preferences.getInt(KEY_CROP_RIGHT, 0),
            bottom = preferences.getInt(KEY_CROP_BOTTOM, 0),
            left = preferences.getInt(KEY_CROP_LEFT, 0),
        ).clamped(videoWidth, videoHeight)
        val inputDiagnostics = InputVideoDiagnostics(
            fileSizeBytes = preferences.getLong(KEY_SOURCE_FILE_SIZE, -1L),
            lastModifiedMs = preferences.getLong(KEY_SOURCE_LAST_MODIFIED, 0L),
            containerMime = preferences.getString(KEY_CONTAINER_MIME, null).orEmpty(),
            videoMime = preferences.getString(KEY_VIDEO_MIME, null).orEmpty(),
            videoBitrate = preferences.getInt(KEY_VIDEO_BITRATE, 0),
            rotationDegrees = preferences.getInt(KEY_VIDEO_ROTATION, 0),
            audioTrackCount = preferences.getInt(KEY_AUDIO_TRACK_COUNT, 0),
            subtitleTrackCount = preferences.getInt(KEY_SUBTITLE_TRACK_COUNT, 0),
            hdr = preferences.getBoolean(KEY_HDR, false),
            seekable = preferences.getBoolean(KEY_SEEKABLE, false),
            provider = preferences.getString(KEY_PROVIDER, null).orEmpty(),
            hardwareDecoder = preferences.getString(KEY_HARDWARE_DECODER, null).orEmpty(),
        )
        val runtimeDiagnostics = RuntimeDiagnostics(
            decodedFrames = preferences.getLong(KEY_RUNTIME_DECODED, 0L),
            selectedFrames = preferences.getLong(KEY_RUNTIME_SELECTED, 0L),
            publishedFrames = preferences.getLong(KEY_RUNTIME_PUBLISHED, 0L),
            cadenceSkippedFrames = preferences.getLong(KEY_RUNTIME_CADENCE_SKIPPED, 0L),
            lateDroppedFrames = preferences.getLong(KEY_RUNTIME_LATE_DROPPED, 0L),
            decodeFps = preferences.getFloat(KEY_RUNTIME_DECODE_FPS, 0f),
            publishFps = preferences.getFloat(KEY_RUNTIME_PUBLISH_FPS, 0f),
            appCpuPercent = preferences.getFloat(KEY_RUNTIME_CPU_PERCENT, 0f),
            appRamMb = preferences.getFloat(KEY_RUNTIME_RAM_MB, 0f),
            audioDriftMs = preferences.getLong(KEY_RUNTIME_AUDIO_DRIFT, 0L),
            audioRestartCount = preferences.getInt(KEY_RUNTIME_AUDIO_RESTARTS, 0),
            audioError = preferences.getString(KEY_RUNTIME_AUDIO_ERROR, null).orEmpty(),
            audioState = storedEnum(KEY_RUNTIME_AUDIO_STATE, AudioPipelineState.STOPPED),
            sampleElapsedMs = preferences.getLong(KEY_RUNTIME_SAMPLE_ELAPSED, 0L),
            active = preferences.getBoolean(KEY_RUNTIME_ACTIVE, false),
        )

        return PlaybackSnapshot(
            videoUri = preferences.getString(KEY_URI, null),
            videoName = preferences.getString(KEY_NAME, null).orEmpty()
                .ifBlank { "No video selected" },
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            sourceDurationMs = sourceDurationMs,
            trimStartMs = trimRange.startMs,
            trimEndMs = trimRange.endMs,
            durationMs = durationMs,
            currentPositionMs = currentPosition(durationMs, timelineRunning),
            timelineRunning = timelineRunning,
            frameRate = frameRate,
            sourceFrameRate = preferences.getFloat(KEY_SOURCE_FRAME_RATE, 0f).coerceAtLeast(0f),
            resolution = resolution,
            cropInsets = cropInsets,
            upscaleFilter = storedEnum(KEY_UPSCALE_FILTER, ScalingFilter.BILINEAR),
            downscaleFilter = storedEnum(KEY_DOWNSCALE_FILTER, ScalingFilter.BILINEAR),
            inputDiagnostics = inputDiagnostics,
            runtimeDiagnostics = runtimeDiagnostics,
            audioEnabled = preferences.getBoolean(KEY_AUDIO, false),
            renderingPolicy = storedEnum(KEY_RENDERING_POLICY, RenderingPolicy.HOME_SCREEN_ONLY),
            hiddenAudioPolicy = storedEnum(KEY_HIDDEN_AUDIO_POLICY, HiddenAudioPolicy.SUSPEND),
            widgetTapAction = storedEnum(KEY_WIDGET_TAP_ACTION, WidgetTapAction.SHOW_CONTROLS),
            status = storedEnum(KEY_STATUS, PlaybackStatus.IDLE),
            statusDetail = preferences.getString(KEY_STATUS_DETAIL, null).orEmpty()
                .ifBlank { "Ready" },
        )
    }

    fun videoUri(): Uri? = preferences.getString(KEY_URI, null)
        ?.let { runCatching { it.toUri() }.getOrNull() }

    fun optimizedUri(profile: OptimizationProfile?): Uri? {
        if (profile == null || preferences.getString("${SAVED_PROFILE}signature", null) != profile.signature) {
            return null
        }
        val path = preferences.getString(KEY_OPTIMIZED_PATH, null) ?: return null
        val file = File(path)
        return file.takeIf { it.isFile && it.length() > 0L }?.let(Uri::fromFile)
    }

    fun optimizationSnapshot(currentProfile: OptimizationProfile?): OptimizationSnapshot {
        reconcileInterruptedOptimization()
        val storedStatus = storedEnum(KEY_OPTIMIZATION_STATUS, OptimizationStatus.NONE)
        val path = preferences.getString(KEY_OPTIMIZED_PATH, null)
        val storedProfile = storedProfile(SAVED_PROFILE)
        val pendingProfile = storedProfile(PENDING_PROFILE)
        val fileReady = path?.let { File(it).isFile && File(it).length() > 0L } == true
        val status = when {
            storedStatus == OptimizationStatus.RUNNING -> OptimizationStatus.RUNNING
            storedStatus == OptimizationStatus.FAILED -> OptimizationStatus.FAILED
            storedStatus == OptimizationStatus.CANCELLED -> OptimizationStatus.CANCELLED
            fileReady && currentProfile != null && storedProfile?.signature == currentProfile.signature ->
                OptimizationStatus.READY

            fileReady -> OptimizationStatus.STALE
            else -> OptimizationStatus.NONE
        }
        val storedDetail = preferences.getString(KEY_OPTIMIZATION_DETAIL, null)
            .orEmpty().trim()
        val retainedDetail = storedDetail.takeIf {
            it.contains("retained", ignoreCase = true)
        }
        val detail = when (status) {
            OptimizationStatus.NONE -> "No optimized copy for this video"
            OptimizationStatus.RUNNING -> storedDetail.ifBlank { "Optimizing video" }
            OptimizationStatus.READY -> retainedDetail ?: "Optimized copy is active"
            OptimizationStatus.STALE -> retainedDetail
                ?: "Optimized copy is out of date for the current settings"

            OptimizationStatus.FAILED -> storedDetail.ifBlank { "Optimization failed" }
            OptimizationStatus.CANCELLED -> "Optimization cancelled"
        }
        return OptimizationSnapshot(
            status = status,
            progressPercent = preferences.getInt(KEY_OPTIMIZATION_PROGRESS, 0).coerceIn(0, 100),
            detail = detail,
            outputPath = path,
            outputSizeBytes = path?.let { File(it).takeIf(File::isFile)?.length() }
                ?: preferences.getLong(KEY_OPTIMIZED_SIZE, 0L),
            profile = if (status == OptimizationStatus.RUNNING) {
                pendingProfile ?: currentProfile
            } else {
                currentProfile
            },
            remainingMs = preferences.getLong(KEY_OPTIMIZATION_REMAINING, -1L)
                .takeIf { status == OptimizationStatus.RUNNING && it >= 0L },
        )
    }

    fun markOptimizationRunning(profile: OptimizationProfile) {
        preferences.edit {
            putString(KEY_OPTIMIZATION_STATUS, OptimizationStatus.RUNNING.name)
            putInt(KEY_OPTIMIZATION_PROGRESS, 0)
            putString(KEY_OPTIMIZATION_DETAIL, "Preparing optimized copy")
            remove(KEY_OPTIMIZATION_REMAINING)
            putProfile(this, PENDING_PROFILE, profile)
        }
    }

    fun updateOptimizationProgress(
        progressPercent: Int,
        detail: String,
        remainingMs: Long? = null
    ) {
        preferences.edit {
            putString(KEY_OPTIMIZATION_STATUS, OptimizationStatus.RUNNING.name)
            putInt(KEY_OPTIMIZATION_PROGRESS, progressPercent.coerceIn(0, 100))
            putString(KEY_OPTIMIZATION_DETAIL, detail)
            if (remainingMs != null) putLong(KEY_OPTIMIZATION_REMAINING, remainingMs)
            else remove(KEY_OPTIMIZATION_REMAINING)
        }
    }

    fun reconcileInterruptedOptimization(): Boolean {
        if (preferences.getString(
                KEY_OPTIMIZATION_STATUS,
                null
            ) != OptimizationStatus.RUNNING.name
        ) {
            return false
        }
        if (OptimizationService.serviceAlive) return false
        val savedFileReady = preferences.getString(KEY_OPTIMIZED_PATH, null)
            ?.let { File(it).isFile && File(it).length() > 0L } == true &&
                storedProfile(SAVED_PROFILE) != null
        preferences.edit {
            putString(
                KEY_OPTIMIZATION_STATUS,
                if (savedFileReady) OptimizationStatus.STALE.name else OptimizationStatus.FAILED.name,
            )
            putString(
                KEY_OPTIMIZATION_DETAIL,
                if (savedFileReady) {
                    "Optimization was interrupted; previous optimized copy was retained"
                } else {
                    "Optimization was interrupted; try again"
                },
            )
            clearProfile(this, PENDING_PROFILE)
        }
        File(appContext.filesDir, OPTIMIZED_DIRECTORY)
            .listFiles { file -> file.name.endsWith(".partial") }
            ?.forEach { file -> runCatching { file.delete() } }
        return true
    }

    fun isOptimizationRunning(): Boolean {
        reconcileInterruptedOptimization()
        return preferences.getString(
            KEY_OPTIMIZATION_STATUS,
            null
        ) == OptimizationStatus.RUNNING.name
    }

    fun savedOptimization(currentProfile: OptimizationProfile?): SavedOptimization? {
        val path = preferences.getString(KEY_OPTIMIZED_PATH, null) ?: return null
        val file = File(path).takeIf { it.isFile && it.length() > 0L } ?: return null
        val savedProfile = storedProfile(SAVED_PROFILE) ?: return null
        return SavedOptimization(
            status = if (currentProfile?.signature == savedProfile.signature) {
                OptimizationStatus.READY
            } else {
                OptimizationStatus.STALE
            },
            outputPath = path,
            outputSizeBytes = file.length(),
            profile = savedProfile,
        )
    }

    fun restoreSavedOptimization(saved: SavedOptimization, failureDetail: String) {
        val file = File(saved.outputPath)
        if (!file.isFile || file.length() <= 0L) {
            failOptimization(failureDetail)
            return
        }
        preferences.edit {
            putString(KEY_OPTIMIZATION_STATUS, saved.status.name)
            putInt(KEY_OPTIMIZATION_PROGRESS, 0)
            putString(
                KEY_OPTIMIZATION_DETAIL,
                "$failureDetail Previous optimized copy was retained.",
            )
            putString(KEY_OPTIMIZED_PATH, saved.outputPath)
            putLong(KEY_OPTIMIZED_SIZE, file.length())
            clearProfile(this, PENDING_PROFILE)
            putProfile(this, SAVED_PROFILE, saved.profile)
        }
    }

    fun completeOptimization(profile: OptimizationProfile, output: File) {
        preferences.edit {
            putString(KEY_OPTIMIZATION_STATUS, OptimizationStatus.READY.name)
            putInt(KEY_OPTIMIZATION_PROGRESS, 100)
            putString(KEY_OPTIMIZATION_DETAIL, "Optimized copy is active")
            putString(KEY_OPTIMIZED_PATH, output.absolutePath)
            putLong(KEY_OPTIMIZED_SIZE, output.length())
            clearProfile(this, PENDING_PROFILE)
            putProfile(this, SAVED_PROFILE, profile)
        }
    }

    fun failOptimization(detail: String, cancelled: Boolean = false) {
        preferences.edit {
            putString(
                KEY_OPTIMIZATION_STATUS,
                if (cancelled) OptimizationStatus.CANCELLED.name else OptimizationStatus.FAILED.name,
            )
            putString(KEY_OPTIMIZATION_DETAIL, detail)
            clearProfile(this, PENDING_PROFILE)
        }
    }

    fun deleteOptimizedFile() {
        preferences.getString(KEY_OPTIMIZED_PATH, null)?.let { path ->
            val file = File(path)
            val optimizedRoot = File(appContext.filesDir, OPTIMIZED_DIRECTORY)
            if (runCatching { file.canonicalPath.startsWith(optimizedRoot.canonicalPath) }.getOrDefault(
                    false
                )
            ) {
                runCatching { file.delete() }
            }
        }
        preferences.edit { clearOptimization(this) }
    }

    fun setRuntimeDiagnostics(diagnostics: RuntimeDiagnostics) {
        preferences.edit {
            putLong(KEY_RUNTIME_DECODED, diagnostics.decodedFrames)
            putLong(KEY_RUNTIME_SELECTED, diagnostics.selectedFrames)
            putLong(KEY_RUNTIME_PUBLISHED, diagnostics.publishedFrames)
            putLong(KEY_RUNTIME_CADENCE_SKIPPED, diagnostics.cadenceSkippedFrames)
            putLong(KEY_RUNTIME_LATE_DROPPED, diagnostics.lateDroppedFrames)
            putFloat(KEY_RUNTIME_DECODE_FPS, diagnostics.decodeFps)
            putFloat(KEY_RUNTIME_PUBLISH_FPS, diagnostics.publishFps)
            putFloat(KEY_RUNTIME_CPU_PERCENT, diagnostics.appCpuPercent)
            putFloat(KEY_RUNTIME_RAM_MB, diagnostics.appRamMb)
            putLong(KEY_RUNTIME_AUDIO_DRIFT, diagnostics.audioDriftMs)
            putInt(KEY_RUNTIME_AUDIO_RESTARTS, diagnostics.audioRestartCount)
            putString(KEY_RUNTIME_AUDIO_ERROR, diagnostics.audioError)
            putString(KEY_RUNTIME_AUDIO_STATE, diagnostics.audioState.name)
            putLong(KEY_RUNTIME_SAMPLE_ELAPSED, diagnostics.sampleElapsedMs)
            putBoolean(KEY_RUNTIME_ACTIVE, diagnostics.active)
        }
    }

    fun resetRuntimeDiagnostics(active: Boolean = false) {
        preferences.edit {
            clearRuntime(this)
            putBoolean(KEY_RUNTIME_ACTIVE, active)
        }
    }

    fun ensureVideoMetadata() {
        if (
            preferences.getInt(KEY_METADATA_VERSION, 0) >= CURRENT_METADATA_VERSION &&
            preferences.getInt(KEY_VIDEO_WIDTH, 0) > 0 &&
            preferences.getInt(KEY_VIDEO_HEIGHT, 0) > 0 &&
            preferences.getFloat(KEY_SOURCE_FRAME_RATE, 0f) > 0f &&
            !preferences.getString(KEY_VIDEO_MIME, null).isNullOrBlank()
        ) return
        val uri = videoUri() ?: return
        runCatching { readMetadata(uri) }.getOrNull()?.let { metadata ->
            if (videoUri() != uri) return
            preferences.edit {
                if (metadata.width > 0 && metadata.height > 0) {
                    putInt(KEY_VIDEO_WIDTH, metadata.width)
                    putInt(KEY_VIDEO_HEIGHT, metadata.height)
                }
                if (metadata.frameRate > 0f) putFloat(KEY_SOURCE_FRAME_RATE, metadata.frameRate)
                putInputDiagnostics(this, metadata.diagnostics)
            }
        }
    }

    fun setFrameRate(frameRate: FrameRateOption) {
        if (frameRate in PlaybackOptions.frameRates) {
            preferences.edit { putString(KEY_FRAME_RATE_MODE, frameRate.id) }
        }
    }

    fun setResolution(resolution: ResolutionOption) {
        if (resolution in PlaybackOptions.resolutions) {
            preferences.edit { putInt(KEY_AUTO_SCALE, resolution.autoScalePercent) }
        }
    }

    fun setScalingFilter(direction: ScalingDirection, filter: ScalingFilter) {
        preferences.edit {
            putString(
                if (direction == ScalingDirection.UPSCALE) KEY_UPSCALE_FILTER
                else KEY_DOWNSCALE_FILTER,
                filter.name,
            )
        }
    }

    @Synchronized
    fun setCropInsets(insets: CropInsets) {
        val updated = insets.clamped(
            preferences.getInt(KEY_VIDEO_WIDTH, 0),
            preferences.getInt(KEY_VIDEO_HEIGHT, 0),
        )
        preferences.edit {
            putInt(KEY_CROP_TOP, updated.top)
            putInt(KEY_CROP_RIGHT, updated.right)
            putInt(KEY_CROP_BOTTOM, updated.bottom)
            putInt(KEY_CROP_LEFT, updated.left)
        }
    }

    fun previewFrame(positionMs: Long, maxSize: Int, exact: Boolean = false): Bitmap? =
        withRetriever { retriever ->
            retriever.frame(positionMs, maxSize, exact)
        }

    fun detectBlackBars(): CropInsets? {
        val snapshot = snapshot()
        if (snapshot.videoWidth <= 0 || snapshot.videoHeight <= 0) return null
        val bars = withRetriever { retriever ->
            (1..BAR_SAMPLE_COUNT).mapNotNull { sample ->
                val positionMs = snapshot.trimStartMs +
                        (snapshot.trimEndMs - snapshot.trimStartMs) * sample / (BAR_SAMPLE_COUNT + 1)
                val frame = retriever.frame(positionMs, BAR_SAMPLE_SIZE_PX, exact = false)
                    ?: return@mapNotNull null
                val pixels = IntArray(frame.width * frame.height)
                frame.getPixels(pixels, 0, frame.width, 0, 0, frame.width, frame.height)
                BlackBarDetector.detect(pixels, frame.width, frame.height)?.let {
                    BlackBarDetector.scale(
                        it,
                        frame.width,
                        frame.height,
                        snapshot.videoWidth,
                        snapshot.videoHeight
                    )
                }.also { frame.recycle() }
            }
        } ?: return null
        return BlackBarDetector.combine(bars)?.clamped(snapshot.videoWidth, snapshot.videoHeight)
    }

    private fun <T> withRetriever(block: (MediaMetadataRetriever) -> T): T? {
        val uri = videoUri() ?: return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(appContext, uri)
            block(retriever)
        } catch (_: RuntimeException) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun MediaMetadataRetriever.frame(
        positionMs: Long,
        maxSize: Int,
        exact: Boolean
    ): Bitmap? {
        val frame = getScaledFrameAtTime(
            positionMs.coerceAtLeast(0L) * 1_000L,
            if (exact) MediaMetadataRetriever.OPTION_CLOSEST else MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
            maxSize,
            maxSize,
        ) ?: return null
        val videoLandscape =
            preferences.getInt(KEY_VIDEO_WIDTH, 0) > preferences.getInt(KEY_VIDEO_HEIGHT, 0)
        val rotation = preferences.getInt(KEY_VIDEO_ROTATION, 0)
        if (frame.width == frame.height || (frame.width > frame.height) == videoLandscape || rotation == 0) {
            return frame
        }
        return Bitmap.createBitmap(
            frame, 0, 0, frame.width, frame.height,
            Matrix().apply { postRotate(rotation.toFloat()) }, true
        ).also { frame.recycle() }
    }

    fun setAudioEnabled(enabled: Boolean) {
        preferences.edit { putBoolean(KEY_AUDIO, enabled) }
    }

    @Synchronized
    fun setTrimRange(startMs: Long, endMs: Long) {
        val sourceDurationMs = preferences.getLong(KEY_DURATION, 0L).coerceAtLeast(0L)
        if (sourceDurationMs <= 0L) return
        val minimumDurationMs = minOf(MIN_TRIM_DURATION_MS, sourceDurationMs)
        val safeStart = startMs.coerceIn(0L, sourceDurationMs - minimumDurationMs)
        val safeEnd = endMs.coerceIn(safeStart + minimumDurationMs, sourceDurationMs)
        preferences.edit {
            putLong(KEY_TRIM_START, safeStart)
            putLong(KEY_TRIM_END, safeEnd)
        }
        saveAnchor(0L, preferences.getBoolean(KEY_TIMELINE_RUNNING, true))
    }

    fun setRenderingPolicy(policy: RenderingPolicy) {
        preferences.edit { putString(KEY_RENDERING_POLICY, policy.name) }
    }

    fun setHiddenAudioPolicy(policy: HiddenAudioPolicy) {
        preferences.edit { putString(KEY_HIDDEN_AUDIO_POLICY, policy.name) }
    }

    fun setWidgetTapAction(action: WidgetTapAction) {
        preferences.edit { putString(KEY_WIDGET_TAP_ACTION, action.name) }
    }

    fun randomizePosition() {
        val duration = trimmedDurationMs()
        if (duration <= 0L) return
        saveAnchor(ThreadLocalRandom.current().nextLong(duration))
    }

    @Synchronized
    fun setPosition(positionMs: Long) {
        val duration = trimmedDurationMs()
        if (duration <= 0L) return
        saveAnchor(Math.floorMod(positionMs, duration))
    }

    @Synchronized
    fun pauseTimeline() {
        val duration = trimmedDurationMs()
        val running = preferences.getBoolean(KEY_TIMELINE_RUNNING, true)
        if (duration <= 0L || !running) return
        saveAnchor(currentPosition(duration, true), running = false)
    }

    @Synchronized
    fun resumeTimeline() {
        val duration = trimmedDurationMs()
        val running = preferences.getBoolean(KEY_TIMELINE_RUNNING, true)
        if (duration <= 0L || running) return
        val pausedPosition = preferences.getLong(KEY_ANCHOR_POSITION, 0L)
        saveAnchor(pausedPosition, running = true)
    }

    fun setStatus(status: PlaybackStatus, detail: String) {
        preferences.edit {
            putString(KEY_STATUS, status.name)
            putString(KEY_STATUS_DETAIL, detail)
        }
    }

    fun reconcileInterruptedPlayback(): Boolean {
        if (
            !storedEnum(KEY_STATUS, PlaybackStatus.IDLE).isActive ||
            !preferences.getBoolean(KEY_TIMELINE_RUNNING, true)
        ) {
            return false
        }
        if (PlaybackController.serviceAlive || OptimizationService.serviceAlive) return false
        preferences.edit {
            putString(KEY_STATUS, PlaybackStatus.IDLE.name)
            putString(KEY_STATUS_DETAIL, "Playback interrupted; waiting to reconnect")
        }
        return true
    }

    private fun currentPosition(
        durationMs: Long,
        timelineRunning: Boolean = preferences.getBoolean(KEY_TIMELINE_RUNNING, true),
    ): Long = VirtualTimeline.position(
        durationMs = durationMs,
        anchorPositionMs = preferences.getLong(KEY_ANCHOR_POSITION, 0L),
        anchorElapsedMs = preferences.getLong(KEY_ANCHOR_ELAPSED, 0L),
        anchorWallMs = preferences.getLong(KEY_ANCHOR_WALL, System.currentTimeMillis()),
        anchorBootCount = preferences.getInt(KEY_ANCHOR_BOOT, -1),
        elapsedNowMs = SystemClock.elapsedRealtime(),
        wallNowMs = System.currentTimeMillis(),
        bootCountNow = bootCount(),
        isRunning = timelineRunning,
    )

    private fun trimmedDurationMs(): Long {
        val sourceDurationMs = preferences.getLong(KEY_DURATION, 0L).coerceAtLeast(0L)
        val range = trimRange(sourceDurationMs)
        return (range.endMs - range.startMs).coerceAtLeast(0L)
    }

    private fun trimRange(sourceDurationMs: Long): TrimRange {
        if (sourceDurationMs <= 0L) return TrimRange(0L, 0L)
        val minimumDurationMs = minOf(MIN_TRIM_DURATION_MS, sourceDurationMs)
        val start = preferences.getLong(KEY_TRIM_START, 0L)
            .coerceIn(0L, sourceDurationMs - minimumDurationMs)
        val end = preferences.getLong(KEY_TRIM_END, sourceDurationMs)
            .coerceIn(start + minimumDurationMs, sourceDurationMs)
        return TrimRange(start, end)
    }

    private fun saveAnchor(
        positionMs: Long,
        running: Boolean = preferences.getBoolean(KEY_TIMELINE_RUNNING, true),
    ) {
        preferences.edit {
            putLong(KEY_ANCHOR_POSITION, positionMs)
            putLong(KEY_ANCHOR_ELAPSED, SystemClock.elapsedRealtime())
            putLong(KEY_ANCHOR_WALL, System.currentTimeMillis())
            putInt(KEY_ANCHOR_BOOT, bootCount())
            putBoolean(KEY_TIMELINE_RUNNING, running)
        }
    }

    private fun readMetadata(uri: Uri): VideoMetadata {
        val retriever = MediaMetadataRetriever()
        val basic = try {
            retriever.setDataSource(appContext, uri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val rawWidth =
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull() ?: 0
            val rawHeight =
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull() ?: 0
            val rotation =
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    ?.toIntOrNull() ?: 0
            val rotated = rotation == 90 || rotation == 270
            VideoMetadata(
                durationMs = duration,
                width = if (rotated) rawHeight else rawWidth,
                height = if (rotated) rawWidth else rawHeight,
                frameRate = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                    ?.toFloatOrNull() ?: 0f,
                rotationDegrees = rotation,
            )
        } finally {
            runCatching { retriever.release() }
        }
        val tracks = readTrackDiagnostics(uri)
        val source = readSourceDiagnostics(uri)
        val frameRate = tracks.frameRate.takeIf { it > 0f } ?: basic.frameRate
        return basic.copy(
            frameRate = frameRate,
            diagnostics = InputVideoDiagnostics(
                fileSizeBytes = source.fileSizeBytes,
                lastModifiedMs = source.lastModifiedMs,
                containerMime = source.containerMime,
                videoMime = tracks.videoMime,
                videoBitrate = tracks.videoBitrate,
                rotationDegrees = tracks.rotationDegrees.takeIf { it != 0 }
                    ?: basic.rotationDegrees,
                audioTrackCount = tracks.audioTrackCount,
                subtitleTrackCount = tracks.subtitleTrackCount,
                hdr = tracks.hdr,
                seekable = source.seekable,
                provider = uri.authority.orEmpty(),
                hardwareDecoder = hardwareDecoderFor(tracks.videoMime),
            ),
        )
    }

    private fun readTrackDiagnostics(uri: Uri): TrackDiagnostics {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(appContext, uri, null)
            var videoMime = ""
            var declaredFrameRate = 0f
            var videoTrackIndex = -1
            var videoBitrate = 0
            var rotation = 0
            var audioTracks = 0
            var subtitleTracks = 0
            var hdr = false
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                when {
                    mime.startsWith("video/") && videoMime.isBlank() -> {
                        videoMime = mime
                        videoTrackIndex = index
                        declaredFrameRate = format.floatOr(MediaFormat.KEY_FRAME_RATE, 0f)
                        videoBitrate = format.intOr(MediaFormat.KEY_BIT_RATE, 0)
                        rotation = format.intOr(MediaFormat.KEY_ROTATION, 0)
                        val transfer = format.intOr(MediaFormat.KEY_COLOR_TRANSFER, 0)
                        hdr = transfer == MediaFormat.COLOR_TRANSFER_ST2084 ||
                                transfer == MediaFormat.COLOR_TRANSFER_HLG
                    }

                    mime.startsWith("audio/") -> audioTracks++
                    mime.isNotBlank() -> subtitleTracks++
                }
            }
            val sampledFrameRate = sampleFrameRate(extractor, videoTrackIndex)
            TrackDiagnostics(
                videoMime = videoMime,
                frameRate = sampledFrameRate.takeIf { it > 0f } ?: declaredFrameRate,
                videoBitrate = videoBitrate,
                rotationDegrees = rotation,
                audioTrackCount = audioTracks,
                subtitleTrackCount = subtitleTracks,
                hdr = hdr,
            )
        } catch (_: Exception) {
            TrackDiagnostics()
        } finally {
            extractor.release()
        }
    }

    private fun sampleFrameRate(extractor: MediaExtractor, videoTrackIndex: Int): Float {
        if (videoTrackIndex < 0) return 0f
        extractor.selectTrack(videoTrackIndex)
        val presentationTimesUs = ArrayList<Long>(FRAME_RATE_SAMPLE_LIMIT)
        var firstTimeUs = Long.MAX_VALUE
        var lastTimeUs = Long.MIN_VALUE
        while (presentationTimesUs.size < FRAME_RATE_SAMPLE_LIMIT) {
            val sampleTimeUs = extractor.sampleTime
            if (sampleTimeUs < 0L) break
            presentationTimesUs += sampleTimeUs
            firstTimeUs = minOf(firstTimeUs, sampleTimeUs)
            lastTimeUs = maxOf(lastTimeUs, sampleTimeUs)
            if (
                presentationTimesUs.size >= MIN_FRAME_RATE_SAMPLES &&
                lastTimeUs - firstTimeUs >= FRAME_RATE_SAMPLE_SPAN_US
            ) break
            if (!extractor.advance()) break
        }
        extractor.unselectTrack(videoTrackIndex)
        return FrameRateEstimator.estimate(presentationTimesUs)
    }

    private fun readSourceDiagnostics(uri: Uri): SourceDiagnostics {
        var fileSize = -1L
        var lastModified = 0L
        runCatching {
            appContext.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { column ->
                        if (!cursor.isNull(column)) fileSize = cursor.getLong(column)
                    }
                    cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                        .takeIf { it >= 0 }?.let { column ->
                            if (!cursor.isNull(column)) lastModified = cursor.getLong(column)
                        }
                }
            }
        }
        if (fileSize < 0L) {
            runCatching {
                appContext.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.SIZE),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val column = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (column >= 0 && !cursor.isNull(column)) fileSize = cursor.getLong(column)
                    }
                }
            }
        }
        val seekable = runCatching {
            appContext.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                Os.lseek(descriptor.fileDescriptor, 0L, OsConstants.SEEK_CUR)
                if (fileSize < 0L && descriptor.length >= 0L) fileSize = descriptor.length
                true
            } ?: false
        }.getOrDefault(false)
        return SourceDiagnostics(
            fileSizeBytes = fileSize,
            lastModifiedMs = lastModified,
            containerMime = appContext.contentResolver.getType(uri).orEmpty(),
            seekable = seekable,
        )
    }

    private fun hardwareDecoderFor(mime: String): String {
        if (mime.isBlank()) return ""
        return runCatching {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.firstOrNull { info ->
                !info.isEncoder && info.isHardwareAccelerated &&
                        info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
            }?.name.orEmpty()
        }.getOrDefault("")
    }

    private fun MediaFormat.intOr(key: String, fallback: Int): Int = runCatching {
        if (containsKey(key)) getInteger(key) else fallback
    }.getOrDefault(fallback)

    private fun MediaFormat.floatOr(key: String, fallback: Float): Float {
        if (!containsKey(key)) return fallback
        return runCatching { getFloat(key) }.getOrNull()
            ?: runCatching { getInteger(key).toFloat() }.getOrDefault(fallback)
    }

    private fun readDisplayName(uri: Uri): String = runCatching {
        appContext.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
        }
    }.getOrNull().orEmpty().ifBlank { "Selected video" }

    private fun bootCount(): Int = try {
        Settings.Global.getInt(appContext.contentResolver, Settings.Global.BOOT_COUNT)
    } catch (_: Settings.SettingNotFoundException) {
        -1
    } catch (_: SecurityException) {
        -1
    }

    private inline fun <reified T : Enum<T>> storedEnum(key: String, fallback: T): T =
        preferences.getString(key, null)
            ?.let { runCatching { enumValueOf<T>(it) }.getOrNull() }
            ?: fallback

    private fun putInputDiagnostics(editor: SharedPreferences.Editor, diagnostics: InputVideoDiagnostics) {
        editor.putInt(KEY_METADATA_VERSION, CURRENT_METADATA_VERSION)
        editor.putLong(KEY_SOURCE_FILE_SIZE, diagnostics.fileSizeBytes)
        editor.putLong(KEY_SOURCE_LAST_MODIFIED, diagnostics.lastModifiedMs)
        editor.putString(KEY_CONTAINER_MIME, diagnostics.containerMime)
        editor.putString(KEY_VIDEO_MIME, diagnostics.videoMime)
        editor.putInt(KEY_VIDEO_BITRATE, diagnostics.videoBitrate)
        editor.putInt(KEY_VIDEO_ROTATION, diagnostics.rotationDegrees)
        editor.putInt(KEY_AUDIO_TRACK_COUNT, diagnostics.audioTrackCount)
        editor.putInt(KEY_SUBTITLE_TRACK_COUNT, diagnostics.subtitleTrackCount)
        editor.putBoolean(KEY_HDR, diagnostics.hdr)
        editor.putBoolean(KEY_SEEKABLE, diagnostics.seekable)
        editor.putString(KEY_PROVIDER, diagnostics.provider)
        editor.putString(KEY_HARDWARE_DECODER, diagnostics.hardwareDecoder)
    }
    private fun storedProfile(prefix: String): OptimizationProfile? {
        val signature = preferences.getString("${prefix}signature", null) ?: return null
        val width = preferences.getInt("${prefix}width", 0)
        val height = preferences.getInt("${prefix}height", 0)
        val fps = preferences.getFloat("${prefix}fps", 0f)
        if (width <= 0 || height <= 0 || fps <= 0f) return null
        return OptimizationProfile(
            signature = signature,
            outputWidth = width,
            outputHeight = height,
            targetFps = fps,
            videoBitrate = preferences.getInt("${prefix}bitrate", 0),
            estimatedBytes = preferences.getLong("${prefix}estimated_size", 0L),
        )
    }

    private fun putProfile(editor: SharedPreferences.Editor, prefix: String, profile: OptimizationProfile) {
        editor.putString("${prefix}signature", profile.signature)
        editor.putInt("${prefix}width", profile.outputWidth)
        editor.putInt("${prefix}height", profile.outputHeight)
        editor.putFloat("${prefix}fps", profile.targetFps)
        editor.putInt("${prefix}bitrate", profile.videoBitrate)
        editor.putLong("${prefix}estimated_size", profile.estimatedBytes)
    }

    private fun clearProfile(editor: SharedPreferences.Editor, prefix: String) {
        PROFILE_FIELDS.forEach { editor.remove("$prefix$it") }
    }

    private fun clearOptimization(editor: SharedPreferences.Editor) {
        editor.remove(KEY_OPTIMIZATION_STATUS)
        editor.remove(KEY_OPTIMIZATION_PROGRESS)
        editor.remove(KEY_OPTIMIZATION_DETAIL)
        editor.remove(KEY_OPTIMIZED_PATH)
        editor.remove(KEY_OPTIMIZED_SIZE)
        clearProfile(editor, SAVED_PROFILE)
        clearProfile(editor, PENDING_PROFILE)
    }

    private fun clearRuntime(editor: SharedPreferences.Editor) {
        editor.remove(KEY_RUNTIME_DECODED)
        editor.remove(KEY_RUNTIME_SELECTED)
        editor.remove(KEY_RUNTIME_PUBLISHED)
        editor.remove(KEY_RUNTIME_CADENCE_SKIPPED)
        editor.remove(KEY_RUNTIME_LATE_DROPPED)
        editor.remove(KEY_RUNTIME_DECODE_FPS)
        editor.remove(KEY_RUNTIME_PUBLISH_FPS)
        editor.remove(KEY_RUNTIME_CPU_PERCENT)
        editor.remove(KEY_RUNTIME_RAM_MB)
        editor.remove(KEY_RUNTIME_AUDIO_DRIFT)
        editor.remove(KEY_RUNTIME_AUDIO_RESTARTS)
        editor.remove(KEY_RUNTIME_AUDIO_ERROR)
        editor.remove(KEY_RUNTIME_AUDIO_STATE)
        editor.remove(KEY_RUNTIME_SAMPLE_ELAPSED)
        editor.remove(KEY_RUNTIME_ACTIVE)
    }

    companion object {
        private const val PREFERENCES = "video_widget_playback"
        private const val KEY_URI = "uri"
        private const val KEY_NAME = "name"
        private const val KEY_DURATION = "duration_ms"
        private const val KEY_TRIM_START = "trim_start_ms"
        private const val KEY_TRIM_END = "trim_end_ms"
        private const val KEY_VIDEO_WIDTH = "video_width"
        private const val KEY_VIDEO_HEIGHT = "video_height"
        private const val KEY_SOURCE_FRAME_RATE = "source_frame_rate"
        private const val KEY_METADATA_VERSION = "metadata_version"
        private const val CURRENT_METADATA_VERSION = 2
        private const val FRAME_RATE_SAMPLE_LIMIT = 360
        private const val MIN_FRAME_RATE_SAMPLES = 48
        private const val FRAME_RATE_SAMPLE_SPAN_US = 12_000_000L
        private const val MIN_TRIM_DURATION_MS = 1_000L
        private const val BAR_SAMPLE_COUNT = 9
        private const val BAR_SAMPLE_SIZE_PX = 640
        private const val KEY_SOURCE_FILE_SIZE = "source_file_size"
        private const val KEY_SOURCE_LAST_MODIFIED = "source_last_modified"
        private const val KEY_CONTAINER_MIME = "container_mime"
        private const val KEY_VIDEO_MIME = "video_mime"
        private const val KEY_VIDEO_BITRATE = "video_bitrate"
        private const val KEY_VIDEO_ROTATION = "video_rotation"
        private const val KEY_AUDIO_TRACK_COUNT = "audio_track_count"
        private const val KEY_SUBTITLE_TRACK_COUNT = "subtitle_track_count"
        private const val KEY_HDR = "hdr"
        private const val KEY_SEEKABLE = "seekable"
        private const val KEY_PROVIDER = "provider"
        private const val KEY_HARDWARE_DECODER = "hardware_decoder"
        private const val KEY_CROP_TOP = "crop_top"
        private const val KEY_CROP_RIGHT = "crop_right"
        private const val KEY_CROP_BOTTOM = "crop_bottom"
        private const val KEY_CROP_LEFT = "crop_left"
        private const val KEY_ANCHOR_POSITION = "anchor_position_ms"
        private const val KEY_ANCHOR_ELAPSED = "anchor_elapsed_ms"
        private const val KEY_ANCHOR_WALL = "anchor_wall_ms"
        private const val KEY_ANCHOR_BOOT = "anchor_boot_count"
        private const val KEY_TIMELINE_RUNNING = "timeline_running"
        private const val KEY_FRAME_RATE_MODE = "frame_rate_mode"
        private const val KEY_AUTO_SCALE = "auto_scale"
        private const val KEY_UPSCALE_FILTER = "upscale_filter"
        private const val KEY_DOWNSCALE_FILTER = "downscale_filter"
        private const val KEY_AUDIO = "audio"
        private const val KEY_RENDERING_POLICY = "rendering_policy"
        private const val KEY_HIDDEN_AUDIO_POLICY = "hidden_audio_policy"
        private const val KEY_WIDGET_TAP_ACTION = "widget_tap_action"
        private const val KEY_STATUS = "status"
        private const val KEY_STATUS_DETAIL = "status_detail"
        private const val KEY_OPTIMIZATION_STATUS = "optimization_status"
        private const val KEY_OPTIMIZATION_PROGRESS = "optimization_progress"
        private const val KEY_OPTIMIZATION_REMAINING = "optimization_remaining_ms"
        private const val KEY_OPTIMIZATION_DETAIL = "optimization_detail"
        private const val KEY_OPTIMIZED_PATH = "optimized_path"
        private const val KEY_OPTIMIZED_SIZE = "optimized_size"
        private const val KEY_RUNTIME_DECODED = "runtime_decoded"
        private const val KEY_RUNTIME_SELECTED = "runtime_selected"
        private const val KEY_RUNTIME_PUBLISHED = "runtime_published"
        private const val KEY_RUNTIME_CADENCE_SKIPPED = "runtime_cadence_skipped"
        private const val KEY_RUNTIME_LATE_DROPPED = "runtime_late_dropped"
        private const val KEY_RUNTIME_DECODE_FPS = "runtime_decode_fps"
        private const val KEY_RUNTIME_PUBLISH_FPS = "runtime_publish_fps"
        private const val KEY_RUNTIME_CPU_PERCENT = "runtime_cpu_percent"
        private const val KEY_RUNTIME_RAM_MB = "runtime_ram_mb"
        private const val KEY_RUNTIME_AUDIO_DRIFT = "runtime_audio_drift"
        private const val KEY_RUNTIME_AUDIO_RESTARTS = "runtime_audio_restarts"
        private const val KEY_RUNTIME_AUDIO_ERROR = "runtime_audio_error"
        private const val KEY_RUNTIME_AUDIO_STATE = "runtime_audio_state"
        private const val KEY_RUNTIME_SAMPLE_ELAPSED = "runtime_sample_elapsed"
        private const val KEY_RUNTIME_ACTIVE = "runtime_active"
        private const val SAVED_PROFILE = "optimized_"
        private const val PENDING_PROFILE = "pending_optimized_"
        private val PROFILE_FIELDS = listOf("signature", "width", "height", "fps", "bitrate", "estimated_size")
        const val OPTIMIZED_DIRECTORY = "optimized"
    }

    private data class VideoMetadata(
        val durationMs: Long,
        val width: Int,
        val height: Int,
        val frameRate: Float,
        val rotationDegrees: Int,
        val diagnostics: InputVideoDiagnostics = InputVideoDiagnostics(),
    )

    private data class TrackDiagnostics(
        val videoMime: String = "",
        val frameRate: Float = 0f,
        val videoBitrate: Int = 0,
        val rotationDegrees: Int = 0,
        val audioTrackCount: Int = 0,
        val subtitleTrackCount: Int = 0,
        val hdr: Boolean = false,
    )

    private data class SourceDiagnostics(
        val fileSizeBytes: Long,
        val lastModifiedMs: Long,
        val containerMime: String,
        val seekable: Boolean,
    )

    private data class TrimRange(
        val startMs: Long,
        val endMs: Long,
    )
}
