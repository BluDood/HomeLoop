package com.bludood.homeloop.playback

object OptimizationStoragePolicy {
    private const val MINIMUM_SAFETY_BYTES = 64L * 1024L * 1024L

    fun requiredFreeBytes(estimatedOutputBytes: Long): Long {
        val estimate = estimatedOutputBytes.coerceAtLeast(0L)
        val safety = maxOf(estimate / 4L, MINIMUM_SAFETY_BYTES)
        return (estimate + safety).coerceAtLeast(MINIMUM_SAFETY_BYTES)
    }

    fun hasEnoughSpace(estimatedOutputBytes: Long, usableBytes: Long): Boolean =
        usableBytes >= requiredFreeBytes(estimatedOutputBytes)
}
