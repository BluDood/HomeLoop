package com.bludood.homeloop.playback

import android.content.Context
import com.bludood.homeloop.data.CropInsets
import com.bludood.homeloop.data.OptimizationProfile
import com.bludood.homeloop.data.OptimizationRecommendation
import com.bludood.homeloop.data.PlaybackSnapshot
import java.security.MessageDigest
import kotlin.math.roundToInt

object OptimizationPlanner {
    fun profile(context: Context, snapshot: PlaybackSnapshot): OptimizationProfile? {
        if (!snapshot.hasVideo || snapshot.videoWidth <= 0 || snapshot.videoHeight <= 0) return null
        val output = AutoResolutionResolver.resolve(context, snapshot)
        val targetFps = effectiveFps(snapshot)
        val bitrate = recommendedBitrate(output.width, output.height, targetFps)
        val signatureSource = listOf(
            PROFILE_VERSION,
            snapshot.videoUri.orEmpty(),
            snapshot.inputDiagnostics.fileSizeBytes,
            snapshot.inputDiagnostics.lastModifiedMs,
            snapshot.videoWidth,
            snapshot.videoHeight,
            snapshot.sourceFrameRate,
            snapshot.trimStartMs,
            snapshot.trimEndMs,
            snapshot.cropInsets.top,
            snapshot.cropInsets.right,
            snapshot.cropInsets.bottom,
            snapshot.cropInsets.left,
            output.width,
            output.height,
            (targetFps * 1_000f).roundToInt(),
            bitrate,
        ).joinToString("|")
        val signature = MessageDigest.getInstance("SHA-256")
            .digest(signatureSource.toByteArray())
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        val estimatedBytes = if (snapshot.durationMs > 0L) {
            ((bitrate + AUDIO_BITRATE).toLong() * snapshot.durationMs / 8_000L)
        } else {
            0L
        }
        return OptimizationProfile(
            signature = signature,
            outputWidth = output.width,
            outputHeight = output.height,
            targetFps = targetFps,
            videoBitrate = bitrate,
            estimatedBytes = estimatedBytes,
        )
    }

    fun recommendation(
        snapshot: PlaybackSnapshot,
        profile: OptimizationProfile?,
    ): OptimizationRecommendation {
        if (profile == null) return OptimizationRecommendation(false, emptyList())
        val diagnostics = snapshot.inputDiagnostics
        val visiblePixels = snapshot.cropInsets.remainingWidth(snapshot.videoWidth).toLong() *
                snapshot.cropInsets.remainingHeight(snapshot.videoHeight)
        val outputPixels = profile.outputWidth.toLong() * profile.outputHeight
        val reasons = buildList {
            if (visiblePixels > outputPixels * 2L) add("Source resolution is much larger than the widget")
            if (snapshot.sourceFrameRate > profile.targetFps * 1.2f) {
                add("Source frame rate is higher than the selected cadence")
            }
            if (diagnostics.videoBitrate > profile.videoBitrate * 2) add("Source bitrate is unusually high")
            if (diagnostics.hdr) add("HDR needs conversion for predictable widget color")
            if (!diagnostics.seekable) add("The selected provider may seek slowly")
            if (diagnostics.hardwareDecoder.isBlank()) add("No confirmed hardware decoder")
        }
        return OptimizationRecommendation(reasons.isNotEmpty(), reasons)
    }

    fun effectiveFps(snapshot: PlaybackSnapshot): Float =
        if (snapshot.sourceFrameRate > 0f) {
            snapshot.sourceFrameRate / snapshot.frameRate.sourceDivisor
        } else {
            FALLBACK_FPS
        }

    fun combinedCrop(snapshot: PlaybackSnapshot, profile: OptimizationProfile): CropInsets {
        val safe = snapshot.cropInsets.clamped(snapshot.videoWidth, snapshot.videoHeight)
        val visibleWidth = safe.remainingWidth(snapshot.videoWidth)
        val visibleHeight = safe.remainingHeight(snapshot.videoHeight)
        val sourceAspect = visibleWidth.toDouble() / visibleHeight
        val targetAspect = profile.outputWidth.toDouble() / profile.outputHeight
        return if (sourceAspect > targetAspect) {
            val extra = (visibleWidth - visibleHeight * targetAspect).roundToInt().coerceAtLeast(0)
            safe.copy(left = safe.left + extra / 2, right = safe.right + extra - extra / 2)
        } else {
            val extra = (visibleHeight - visibleWidth / targetAspect).roundToInt().coerceAtLeast(0)
            safe.copy(top = safe.top + extra / 2, bottom = safe.bottom + extra - extra / 2)
        }.clamped(snapshot.videoWidth, snapshot.videoHeight)
    }

    private fun recommendedBitrate(width: Int, height: Int, fps: Float): Int =
        (width.toDouble() * height * fps * BITS_PER_PIXEL_FRAME)
            .roundToInt()
            .coerceIn(MIN_VIDEO_BITRATE, MAX_VIDEO_BITRATE)

    const val AUDIO_BITRATE = 128_000
    private const val PROFILE_VERSION = 2
    private const val FALLBACK_FPS = 30f
    private const val BITS_PER_PIXEL_FRAME = 0.11
    private const val MIN_VIDEO_BITRATE = 500_000
    private const val MAX_VIDEO_BITRATE = 6_000_000
}
