package com.bludood.homeloop.ui

import java.util.Locale

internal fun formatClock(durationMs: Long): String {
    val totalSeconds = (durationMs / 1_000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3_600L
    val minutes = totalSeconds % 3_600L / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

internal fun formatPreciseClock(durationMs: Long): String {
    val safe = durationMs.coerceAtLeast(0L)
    return "${formatClock(safe)}.${String.format(Locale.US, "%02d", safe % 1_000L / 10L)}"
}

internal fun formatTimeLeft(remainingMs: Long): String {
    val totalMinutes = (remainingMs + 30_000L) / 60_000L
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return when {
        totalMinutes <= 0L -> "Less than a minute left"
        hours <= 0L -> "About $minutes min left"
        minutes <= 0L -> "About $hours h left"
        else -> "About $hours h $minutes min left"
    }
}

internal fun formatDecimal(value: Float): String = String.format(Locale.US, "%.2f", value)

internal fun formatFrameRate(value: Float): String = String.format(Locale.US, "%.3f", value)

internal fun formatBitrate(bitsPerSecond: Int): String = when {
    bitsPerSecond <= 0 -> "Unknown"
    bitsPerSecond >= 1_000_000 -> "${formatDecimal(bitsPerSecond / 1_000_000f)} Mbps"
    else -> "${(bitsPerSecond / 1_000f).toInt()} kbps"
}

internal fun formatBytes(bytes: Long): String = when {
    bytes < 0L -> "Unknown"
    bytes >= 1_000_000_000L -> "${formatDecimal(bytes / 1_000_000_000f)} GB"
    bytes >= 1_000_000L -> "${formatDecimal(bytes / 1_000_000f)} MB"
    bytes >= 1_000L -> "${formatDecimal(bytes / 1_000f)} KB"
    else -> "$bytes B"
}
