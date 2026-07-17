package com.bludood.homeloop.playback

data class DecoderDiagnosticsDelta(
    val decodedFrames: Long,
    val selectedFrames: Long,
    val publishedFrames: Long,
    val cadenceSkippedFrames: Long,
    val lateDroppedFrames: Long,
    val elapsedMs: Long,
)
