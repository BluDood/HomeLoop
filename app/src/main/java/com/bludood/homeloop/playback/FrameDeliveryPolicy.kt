package com.bludood.homeloop.playback

object FrameDeliveryPolicy {
    const val OUTPUT_TIMEOUT_MS = 15_000L
    const val MAX_FRAME_SILENCE_NS = 1_000_000_000L

    fun shouldDrop(
        latenessNs: Long, toleranceNs: Long, nowNs: Long,
        lastPublishedNs: Long?
    ): Boolean =
        latenessNs > toleranceNs && lastPublishedNs != null &&
                nowNs - lastPublishedNs < MAX_FRAME_SILENCE_NS

    fun outputStalled(nowMs: Long, lastOutputMs: Long): Boolean =
        nowMs - lastOutputMs >= OUTPUT_TIMEOUT_MS
}
