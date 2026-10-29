package com.bludood.homeloop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.bludood.homeloop.MainUiState
import com.bludood.homeloop.data.PlaybackStatus
import com.bludood.homeloop.data.VirtualTimeline

@Composable
internal fun DiagnosticsPage(
    state: MainUiState,
    snackbarHostState: SnackbarHostState,
    onUp: () -> Unit,
) {
    val playback = state.playback
    val input = playback.inputDiagnostics
    val runtime = playback.runtimeDiagnostics
    SubPage(title = "Diagnostics", snackbarHostState = snackbarHostState, onUp = onUp) {
        DiagnosticsSection(
            "Playback",
            listOf(
                "Status" to playback.statusDetail.ifBlank { playback.status.name.lowercase() },
                "Playback service" to if (playback.status.isActive) "Started" else "Stopped",
                "Renderer" to when (playback.status) {
                    PlaybackStatus.STARTING -> "Starting"
                    PlaybackStatus.RENDERING -> "Rendering"
                    PlaybackStatus.SUSPENDED -> "Sleeping"
                    PlaybackStatus.ERROR -> "Error"
                    PlaybackStatus.IDLE, PlaybackStatus.PAUSED -> "Stopped"
                },
                "Audio" to when {
                    !playback.audioEnabled -> "Muted"
                    input.audioTrackCount <= 0 -> "Unavailable"
                    else -> runtime.audioState.label
                },
                "Virtual timeline" to if (playback.timelineRunning) "Advancing" else "Paused",
                "Position" to "${VirtualTimeline.format(playback.currentPositionMs)} of " +
                        VirtualTimeline.format(playback.durationMs),
                "After a restart" to if (state.backgroundStartAllowed) "Resumes automatically"
                else "Tap the widget to resume",
            ),
        )
        DiagnosticsSection(
            if (runtime.active) "Decoder · live" else "Decoder · last sample",
            listOf(
                "Decoded frames" to runtime.decodedFrames.toString(),
                "Cadence-selected" to runtime.selectedFrames.toString(),
                "Published to widget" to runtime.publishedFrames.toString(),
                "Late / missed" to "${runtime.lateDroppedFrames} · ${formatDecimal(runtime.missedPercent)}%",
                "Intentionally skipped" to runtime.cadenceSkippedFrames.toString(),
                "Decoder throughput" to "${formatDecimal(runtime.decodeFps)} FPS",
                "Actual widget FPS" to "${formatDecimal(runtime.publishFps)} FPS",
                "Audio drift (+ = ahead)" to
                        "${if (runtime.audioDriftMs > 0L) "+" else ""}${runtime.audioDriftMs} ms",
                "Audio restarts" to runtime.audioRestartCount.toString(),
                "Latest audio error" to runtime.audioError.ifBlank { "None" },
                "App CPU" to "${formatDecimal(runtime.appCpuPercent)}% of device",
                "App RAM" to "${formatDecimal(runtime.appRamMb)} MB PSS",
                "Measured time" to VirtualTimeline.format(runtime.sampleElapsedMs),
            ),
        )
        if (input.hasData) {
            DiagnosticsSection(
                "Video file",
                listOf(
                    "Video" to "${playback.videoWidth} × ${playback.videoHeight} · " +
                            "${formatFrameRate(playback.sourceFrameRate)} FPS",
                    "Duration" to VirtualTimeline.format(playback.sourceDurationMs),
                    "File size" to formatBytes(input.fileSizeBytes),
                    "Container" to input.containerMime.ifBlank { "Unknown" },
                    "Video codec" to input.videoMime.ifBlank { "Unknown" },
                    "Source bitrate" to formatBitrate(input.videoBitrate),
                    "Dynamic range" to if (input.hdr) "HDR" else "SDR / unspecified",
                    "Tracks" to "${input.audioTrackCount} audio · ${input.subtitleTrackCount} other",
                    "Storage access" to if (input.seekable) "Seekable" else "Potentially slow / non-seekable",
                    "Provider" to input.provider.ifBlank { "Local or direct file" },
                    "Hardware decoder" to input.hardwareDecoder.ifBlank { "Not confirmed" },
                ),
            )
        }
        Text(
            "Cadence-skipped frames are intentional. Late or missed frames were already obsolete " +
                    "before GPU readback. Pause freezes decoding and the virtual playhead; when only the " +
                    "screen turns off, decoding sleeps while the timeline continues.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DiagnosticsSection(title: String, rows: List<Pair<String, String>>) {
    SettingsSection(title) {
        rows.forEachIndexed { index, (label, value) ->
            InfoRow(label, value, index = index, count = rows.size)
        }
    }
}
