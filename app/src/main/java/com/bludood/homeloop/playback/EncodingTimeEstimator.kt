package com.bludood.homeloop.playback

import kotlin.math.roundToLong

class EncodingTimeEstimator {
    private var startedMs = 0L
    private var lastSampleMs = 0L
    private var lastProgress = 0
    private var smoothedPercentPerMs: Double? = null

    fun reset(nowMs: Long) {
        startedMs = nowMs
        lastSampleMs = nowMs
        lastProgress = 0
        smoothedPercentPerMs = null
    }

    fun estimateRemainingMs(progressPercent: Int, nowMs: Long): Long? {
        val progress = progressPercent.coerceIn(0, 99)
        if (startedMs == 0L) reset(nowMs)
        if (progress > lastProgress && nowMs > lastSampleMs) {
            val sampleRate = (progress - lastProgress).toDouble() / (nowMs - lastSampleMs)
            smoothedPercentPerMs = smoothedPercentPerMs?.let { previous ->
                previous * (1.0 - SMOOTHING_WEIGHT) + sampleRate * SMOOTHING_WEIGHT
            } ?: sampleRate
            lastProgress = progress
            lastSampleMs = nowMs
        }
        val elapsedMs = (nowMs - startedMs).coerceAtLeast(0L)
        if (elapsedMs < MIN_SAMPLE_TIME_MS || progress < MIN_PROGRESS_PERCENT) return null
        val overallRate = progress.toDouble() / elapsedMs
        val recentRate = smoothedPercentPerMs ?: overallRate
        val rate = overallRate * OVERALL_RATE_WEIGHT + recentRate * (1.0 - OVERALL_RATE_WEIGHT)
        if (!rate.isFinite() || rate <= 0.0) return null
        return ((100 - progress) / rate).roundToLong().coerceIn(0L, MAX_ESTIMATE_MS)
    }

    companion object {
        private const val MIN_SAMPLE_TIME_MS = 10_000L
        private const val MIN_PROGRESS_PERCENT = 2
        private const val SMOOTHING_WEIGHT = 0.25
        private const val OVERALL_RATE_WEIGHT = 0.35
        private const val MAX_ESTIMATE_MS = 30L * 24L * 60L * 60L * 1_000L
    }
}
