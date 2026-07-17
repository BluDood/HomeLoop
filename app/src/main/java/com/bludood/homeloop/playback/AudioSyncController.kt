package com.bludood.homeloop.playback

import kotlin.math.abs

internal object AudioSyncController {
    data class Decision(
        val playbackSpeed: Float,
        val seekPositionMs: Long? = null,
    )

    fun decide(driftMs: Long, targetPositionMs: Long): Decision {
        val absoluteDrift = abs(driftMs)
        if (absoluteDrift >= HARD_SEEK_THRESHOLD_MS) {
            return Decision(playbackSpeed = 1f, seekPositionMs = targetPositionMs)
        }
        if (absoluteDrift <= DEAD_ZONE_MS) return Decision(playbackSpeed = 1f)

        val correction = (absoluteDrift / CORRECTION_WINDOW_MS)
            .toFloat()
            .coerceIn(MIN_SPEED_CORRECTION, MAX_SPEED_CORRECTION)
        return Decision(
            playbackSpeed = if (driftMs > 0L) 1f - correction else 1f + correction,
        )
    }

    private const val DEAD_ZONE_MS = 20L
    private const val HARD_SEEK_THRESHOLD_MS = 200L
    private const val CORRECTION_WINDOW_MS = 4_000L
    private const val MIN_SPEED_CORRECTION = 0.005f
    private const val MAX_SPEED_CORRECTION = 0.02f
}
