package com.bludood.homeloop.playback

import com.bludood.homeloop.data.CropInsets
import com.bludood.homeloop.data.InputVideoDiagnostics
import com.bludood.homeloop.data.HiddenAudioPolicy
import com.bludood.homeloop.data.OptimizationProfile
import com.bludood.homeloop.data.PlaybackOptions
import com.bludood.homeloop.data.PlaybackSnapshot
import com.bludood.homeloop.data.PlaybackStatus
import com.bludood.homeloop.data.RuntimeDiagnostics
import com.bludood.homeloop.data.RenderingPolicy
import com.bludood.homeloop.data.ScalingFilter
import com.bludood.homeloop.data.WidgetTapAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OptimizationPlannerTest {
    @Test
    fun sourceRelativeCadenceKeepsFractionalFrameRate() {
        val snapshot = snapshot().copy(
            sourceFrameRate = 23.976f,
            frameRate = PlaybackOptions.frameRates.first { it.sourceDivisor == 2 },
        )

        assertEquals(11.988f, OptimizationPlanner.effectiveFps(snapshot), 0.0001f)
    }

    @Test
    fun optimizationNeverInventsFramesAboveTheSourceCadence() {
        val snapshot = snapshot().copy(
            sourceFrameRate = 23.976f,
            frameRate = PlaybackOptions.defaultFrameRate,
        )

        assertEquals(23.976f, OptimizationPlanner.effectiveFps(snapshot), 0.0001f)
    }

    @Test
    fun combinedCropMatchesSquareOutputWithoutStretching() {
        val snapshot = snapshot()
        val profile = profile(width = 500, height = 500)

        val crop = OptimizationPlanner.combinedCrop(snapshot, profile)

        assertEquals(420, crop.left)
        assertEquals(420, crop.right)
        assertEquals(0, crop.top)
        assertEquals(0, crop.bottom)
        assertEquals(1_080, crop.remainingWidth(snapshot.videoWidth))
    }

    @Test
    fun largeSourceProducesOptimizationRecommendation() {
        val recommendation = OptimizationPlanner.recommendation(
            snapshot(),
            profile(width = 426, height = 240),
        )

        assertTrue(recommendation.recommended)
        assertTrue(recommendation.reasons.any { "resolution" in it.lowercase() })
    }

    private fun snapshot() = PlaybackSnapshot(
        videoUri = "content://test/video",
        videoName = "movie.mp4",
        videoWidth = 1_920,
        videoHeight = 1_080,
        sourceDurationMs = 7_200_000L,
        trimStartMs = 0L,
        trimEndMs = 7_200_000L,
        durationMs = 7_200_000L,
        currentPositionMs = 0L,
        timelineRunning = true,
        frameRate = PlaybackOptions.defaultFrameRate,
        sourceFrameRate = 23.976f,
        resolution = PlaybackOptions.defaultResolution,
        cropInsets = CropInsets(),
        upscaleFilter = ScalingFilter.BICUBIC,
        downscaleFilter = ScalingFilter.BICUBIC,
        inputDiagnostics = InputVideoDiagnostics(
            fileSizeBytes = 4_000_000_000L,
            videoMime = "video/avc",
            seekable = true,
            hardwareDecoder = "hardware.avc.decoder",
        ),
        runtimeDiagnostics = RuntimeDiagnostics(),
        audioEnabled = true,
        renderingPolicy = RenderingPolicy.SCREEN_ON,
        hiddenAudioPolicy = HiddenAudioPolicy.SUSPEND,
        widgetTapAction = WidgetTapAction.OPEN_APP,
        status = PlaybackStatus.IDLE,
        statusDetail = "Ready",
    )

    private fun profile(width: Int, height: Int) = OptimizationProfile(
        signature = "test",
        outputWidth = width,
        outputHeight = height,
        targetFps = 23.976f,
        videoBitrate = 1_000_000,
        estimatedBytes = 1_000_000L,
    )
}
