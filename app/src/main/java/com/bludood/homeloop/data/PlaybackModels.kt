package com.bludood.homeloop.data

data class ResolutionOption(val autoScalePercent: Int, val description: String) {
    val label: String get() = "$autoScalePercent%"
}

data class FrameRateOption(val id: String, val label: String, val sourceDivisor: Int) {
    fun displayLabel(sourceFps: Float): String {
        if (sourceFps <= 0f) return label
        return "$label · ${"%.3f".format(sourceFps / sourceDivisor)} FPS"
    }
}

object PlaybackOptions {
    val frameRates = listOf(
        FrameRateOption("source_100", "Match video", sourceDivisor = 1),
        FrameRateOption("source_50", "1/2", sourceDivisor = 2),
        FrameRateOption("source_25", "1/4", sourceDivisor = 4),
        FrameRateOption("source_12_5", "1/8", sourceDivisor = 8),
    )
    val resolutions = listOf(
        ResolutionOption(100, "Pixel-correct Auto size"),
        ResolutionOption(75, "75% of Auto dimensions"),
        ResolutionOption(50, "50% of Auto dimensions"),
        ResolutionOption(25, "25% of Auto dimensions"),
    )
    val defaultFrameRate = frameRates.first()
    val defaultResolution = resolutions.first()
}

enum class WidgetTapAction(val label: String, val description: String) {
    SHOW_CONTROLS("Show controls", "Tap the widget to show play, mute and full screen controls"),
    TOGGLE_PLAYBACK("Pause / resume", "Tap the widget to pause or resume playback"),
    TOGGLE_MUTE("Mute / unmute", "Tap the widget to toggle audio"),
    OPEN_APP("Open the app", "Tap the widget to open this screen"),
    OPEN_PLAYER("Play full screen", "Tap the widget to watch from the same moment in full screen"),
}

enum class RenderingPolicy(val label: String, val description: String) {
    SCREEN_ON(
        "Whenever the screen is on",
        "Most compatible; renders even when another app or Home page is visible",
    ),
    HOME_SCREEN_ONLY(
        "Only while Home is open",
        "Uses Usage Access to stop video rendering while another app is foreground",
    ),
}

enum class HiddenAudioPolicy(val label: String, val description: String) {
    CONTINUE(
        "Continue audio",
        "Stops video rendering but keeps audio playing when Home is not open",
    ),
    SUSPEND(
        "Suspend audio",
        "Stops the complete media pipeline; the virtual timeline still advances",
    ),
}

enum class ScalingFilter(val label: String, val description: String) {
    BICUBIC("Bicubic", "Smoother detail · 16 texture samples"),
    BILINEAR("Bilinear", "Balanced and fast · 1 texture lookup"),
    LANCZOS2("Lanczos-2", "Sharpest detail · 16 samples plus extra GPU math"),
    NEAREST("Nearest neighbor", "Fastest · intentionally pixelated"),
}

enum class ScalingDirection { UPSCALE, DOWNSCALE }

data class InputVideoDiagnostics(
    val fileSizeBytes: Long = -1L,
    val lastModifiedMs: Long = 0L,
    val containerMime: String = "",
    val videoMime: String = "",
    val videoBitrate: Int = 0,
    val rotationDegrees: Int = 0,
    val audioTrackCount: Int = 0,
    val subtitleTrackCount: Int = 0,
    val hdr: Boolean = false,
    val seekable: Boolean = false,
    val provider: String = "",
    val hardwareDecoder: String = "",
) {
    val hasData: Boolean get() = videoMime.isNotBlank() || fileSizeBytes >= 0L
}

enum class AudioPipelineState(val label: String) {
    MUTED("Muted"),
    STARTING("Starting"),
    PLAYING("Playing"),
    SLEEPING("Sleeping"),
    UNAVAILABLE("Unavailable"),
    STOPPED("Stopped"),
}

data class RuntimeDiagnostics(
    val decodedFrames: Long = 0L,
    val selectedFrames: Long = 0L,
    val publishedFrames: Long = 0L,
    val cadenceSkippedFrames: Long = 0L,
    val lateDroppedFrames: Long = 0L,
    val decodeFps: Float = 0f,
    val publishFps: Float = 0f,
    val appCpuPercent: Float = 0f,
    val appRamMb: Float = 0f,
    val audioDriftMs: Long = 0L,
    val audioRestartCount: Int = 0,
    val audioError: String = "",
    val audioState: AudioPipelineState = AudioPipelineState.STOPPED,
    val sampleElapsedMs: Long = 0L,
    val active: Boolean = false,
) {
    val missedPercent: Float
        get() = if (selectedFrames <= 0L) 0f else {
            lateDroppedFrames * 100f / selectedFrames
        }
}

data class OptimizationProfile(
    val signature: String,
    val outputWidth: Int,
    val outputHeight: Int,
    val targetFps: Float,
    val videoBitrate: Int,
    val estimatedBytes: Long,
) {
    val label: String get() = "$outputWidth × $outputHeight · ${"%.2f".format(targetFps)} FPS"
}

enum class OptimizationStatus {
    NONE,
    RUNNING,
    READY,
    STALE,
    FAILED,
    CANCELLED,
}

data class OptimizationSnapshot(
    val status: OptimizationStatus = OptimizationStatus.NONE,
    val progressPercent: Int = 0,
    val detail: String = "Not optimized",
    val outputPath: String? = null,
    val outputSizeBytes: Long = 0L,
    val profile: OptimizationProfile? = null,
    val remainingMs: Long? = null,
) {
    val isUsable: Boolean get() = status == OptimizationStatus.READY && outputPath != null
}

data class SavedOptimization(
    val status: OptimizationStatus,
    val outputPath: String,
    val outputSizeBytes: Long,
    val profile: OptimizationProfile,
)

data class OptimizationRecommendation(
    val recommended: Boolean,
    val reasons: List<String>,
) {
    val summary: String
        get() = when {
            reasons.isEmpty() -> "The original already suits this widget setup"
            recommended -> reasons.joinToString(separator = " · ")
            else -> reasons.first()
        }
}

enum class PlaybackStatus {
    IDLE,
    STARTING,
    RENDERING,
    SUSPENDED,
    PAUSED,
    ERROR;
    val isActive: Boolean get() = this == STARTING || this == RENDERING || this == SUSPENDED
}

data class PlaybackSnapshot(
    val videoUri: String?,
    val videoName: String,
    val videoWidth: Int,
    val videoHeight: Int,
    val sourceDurationMs: Long,
    val trimStartMs: Long,
    val trimEndMs: Long,
    val durationMs: Long,
    val currentPositionMs: Long,
    val timelineRunning: Boolean,
    val frameRate: FrameRateOption,
    val sourceFrameRate: Float,
    val resolution: ResolutionOption,
    val cropInsets: CropInsets,
    val upscaleFilter: ScalingFilter,
    val downscaleFilter: ScalingFilter,
    val inputDiagnostics: InputVideoDiagnostics,
    val runtimeDiagnostics: RuntimeDiagnostics,
    val audioEnabled: Boolean,
    val renderingPolicy: RenderingPolicy,
    val hiddenAudioPolicy: HiddenAudioPolicy,
    val widgetTapAction: WidgetTapAction,
    val status: PlaybackStatus,
    val statusDetail: String,
) {
    val hasVideo: Boolean get() = videoUri != null && durationMs > 0L
    val sourcePositionMs: Long
        get() =
            (trimStartMs + currentPositionMs).coerceIn(
                trimStartMs,
                (trimEndMs - 1L).coerceAtLeast(0L)
            )
}

object VirtualTimeline {
    fun position(
        durationMs: Long,
        anchorPositionMs: Long,
        anchorElapsedMs: Long,
        anchorWallMs: Long,
        anchorBootCount: Int,
        elapsedNowMs: Long,
        wallNowMs: Long,
        bootCountNow: Int,
        isRunning: Boolean = true,
    ): Long {
        if (durationMs <= 0L) return 0L
        if (!isRunning) return Math.floorMod(anchorPositionMs, durationMs)
        val elapsed = if (anchorBootCount >= 0 && anchorBootCount == bootCountNow) {
            (elapsedNowMs - anchorElapsedMs).coerceAtLeast(0L)
        } else {
            (wallNowMs - anchorWallMs).coerceAtLeast(0L)
        }
        return Math.floorMod(anchorPositionMs + elapsed, durationMs)
    }

    fun format(durationMs: Long): String {
        val totalSeconds = (durationMs / 1_000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3_600L
        val minutes = totalSeconds % 3_600L / 60L
        val seconds = totalSeconds % 60L
        return "%02d:%02d:%02d".format(hours, minutes, seconds)
    }

    fun circularDrift(actualMs: Long, targetMs: Long, durationMs: Long): Long {
        if (durationMs <= 0L) return actualMs - targetMs
        var drift = actualMs - targetMs
        if (drift > durationMs / 2L) drift -= durationMs
        if (drift < -durationMs / 2L) drift += durationMs
        return drift
    }
}

data class OutputSize(val width: Int, val height: Int) {
    val label: String get() = "$width × $height"
}

data class CropInsets(
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
    val left: Int = 0,
) {
    fun clamped(frameWidth: Int, frameHeight: Int): CropInsets {
        val safeTop = top.coerceAtLeast(0)
        val safeRight = right.coerceAtLeast(0)
        val safeBottom = bottom.coerceAtLeast(0)
        val safeLeft = left.coerceAtLeast(0)
        if (frameWidth <= MIN_REMAINING_PIXELS || frameHeight <= MIN_REMAINING_PIXELS) {
            return CropInsets(safeTop, safeRight, safeBottom, safeLeft)
        }
        val clampedLeft = safeLeft.coerceAtMost(frameWidth - MIN_REMAINING_PIXELS)
        val clampedRight = safeRight.coerceAtMost(
            frameWidth - clampedLeft - MIN_REMAINING_PIXELS,
        )
        val clampedTop = safeTop.coerceAtMost(frameHeight - MIN_REMAINING_PIXELS)
        val clampedBottom = safeBottom.coerceAtMost(
            frameHeight - clampedTop - MIN_REMAINING_PIXELS,
        )
        return CropInsets(clampedTop, clampedRight, clampedBottom, clampedLeft)
    }

    fun remainingWidth(frameWidth: Int): Int =
        (frameWidth - left - right).coerceAtLeast(MIN_REMAINING_PIXELS)

    fun remainingHeight(frameHeight: Int): Int =
        (frameHeight - top - bottom).coerceAtLeast(MIN_REMAINING_PIXELS)

    companion object {
        private const val MIN_REMAINING_PIXELS = 2
    }
}
