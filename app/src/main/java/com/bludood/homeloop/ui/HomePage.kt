package com.bludood.homeloop.ui

import android.graphics.Bitmap
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bludood.homeloop.MainUiState
import com.bludood.homeloop.R
import com.bludood.homeloop.data.CropInsets
import com.bludood.homeloop.data.HiddenAudioPolicy
import com.bludood.homeloop.data.OptimizationStatus
import com.bludood.homeloop.data.PlaybackStatus
import com.bludood.homeloop.data.RenderingPolicy
import com.bludood.homeloop.data.WidgetTapAction
import com.bludood.homeloop.ui.theme.Space

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun HomePage(
    state: MainUiState,
    inAppPlayer: InAppPlayer?,
    onOpenPlayer: () -> Unit,
    snackbarHostState: SnackbarHostState,
    onOpenAdvanced: () -> Unit,
    onChooseVideo: () -> Unit,
    onAddWidget: () -> Unit,
    onAudioChanged: (Boolean) -> Unit,
    onWidgetTapActionSelected: (WidgetTapAction) -> Unit,
    onOpenBatterySettings: () -> Unit,
    onOpenUsageAccess: () -> Unit,
    onOptimize: () -> Unit,
    onCancelOptimization: () -> Unit,
    onTogglePlayback: () -> Unit,
    onSeek: (Long) -> Unit,
    onTrimChanged: (Long, Long) -> Unit,
    onCropChanged: (CropInsets) -> Unit,
    loadPreviewFrame: suspend (Int) -> Bitmap?,
    loadFrameAt: suspend (Long, Int) -> Bitmap?,
    detectBlackBars: suspend () -> CropInsets?,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text("HomeLoop") },
                actions = {
                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
                            TooltipAnchorPosition.Below
                        ),
                        tooltip = { PlainTooltip { Text("Advanced") } },
                        state = rememberTooltipState(),
                    ) {
                        IconButton(onClick = onOpenAdvanced, shapes = IconButtonDefaults.shapes()) {
                            Icon(painterResource(R.drawable.ms_tune_filled_24), "Advanced")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { contentPadding ->
        PageColumn(contentPadding) {
            NowPlaying(
                state = state,
                inAppPlayer = inAppPlayer,
                onOpenPlayer = onOpenPlayer,
                onChooseVideo = onChooseVideo,
                onAddWidget = onAddWidget,
                onTogglePlayback = onTogglePlayback,
                onSeek = onSeek,
            )
            SetupSection(
                state = state,
                onOpenBatterySettings = onOpenBatterySettings,
                onOpenUsageAccess = onOpenUsageAccess,
            )
            if (state.playback.hasVideo) {
                VideoSection(
                    state = state,
                    onTrimChanged = onTrimChanged,
                    onCropChanged = onCropChanged,
                    loadPreviewFrame = loadPreviewFrame,
                    loadFrameAt = loadFrameAt,
                    detectBlackBars = detectBlackBars,
                )
                PlaybackSection(
                    state = state,
                    onAudioChanged = onAudioChanged,
                    onWidgetTapActionSelected = onWidgetTapActionSelected,
                    onOptimize = onOptimize,
                    onCancelOptimization = onCancelOptimization,
                )
            }
            if (state.optimization.status == OptimizationStatus.RUNNING) {
                Text(
                    "You can leave the app while the copy is made. Keep its notification; Android " +
                            "may stop the work after 6 hours.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun NowPlaying(
    state: MainUiState,
    inAppPlayer: InAppPlayer?,
    onOpenPlayer: () -> Unit,
    onChooseVideo: () -> Unit,
    onAddWidget: () -> Unit,
    onTogglePlayback: () -> Unit,
    onSeek: (Long) -> Unit,
) {
    val playback = state.playback
    val content = nowPlayingContent(state)
    val showVideo = playback.hasVideo && inAppPlayer != null
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Box {
            if (showVideo) {
                CroppedVideo(
                    inAppPlayer = inAppPlayer,
                    videoWidth = playback.videoWidth,
                    videoHeight = playback.videoHeight,
                    crop = playback.cropInsets,
                    fill = true,
                    modifier = Modifier.matchParentSize(),
                )
                Box(
                    Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Black.copy(alpha = 0.1f),
                                0.25f to Color.Black.copy(alpha = 0.35f),
                                0.45f to Color.Black.copy(alpha = 0.7f),
                                1f to Color.Black.copy(alpha = 0.85f),
                            ),
                        ),
                )
            }
            val onVideo = Color.White
            CompositionLocalProvider(
                LocalContentColor provides if (showVideo) onVideo else LocalContentColor.current,
            ) {
                Column(
                    Modifier.padding(Space.xl),
                    verticalArrangement = Arrangement.spacedBy(Space.xs)
                ) {
                    if (showVideo) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(bottom = VIDEO_HEADROOM),
                            horizontalArrangement = Arrangement.End
                        ) {
                            FilledTonalIconButton(
                                onClick = onOpenPlayer,
                                shapes = IconButtonDefaults.shapes()
                            ) {
                                Icon(
                                    painterResource(R.drawable.ms_fullscreen_24),
                                    "Play full screen"
                                )
                            }
                        }
                    }
                    Text(
                        content.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = when {
                            playback.status == PlaybackStatus.ERROR && !showVideo -> MaterialTheme.colorScheme.error
                            showVideo -> onVideo.copy(alpha = 0.85f)
                            else -> MaterialTheme.colorScheme.primary
                        },
                    )
                    Text(
                        if (playback.hasVideo) playback.videoName else "Choose a video",
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        content.detail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (showVideo) onVideo.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (playback.hasVideo && playback.durationMs > 0L) {
                        Timeline(
                            positionMs = playback.currentPositionMs,
                            durationMs = playback.durationMs,
                            enabled = state.optimization.status != OptimizationStatus.RUNNING,
                            labelColor = if (showVideo) onVideo.copy(alpha = 0.85f)
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            onSeek = onSeek,
                        )
                    }
                    FlowRow(
                        modifier = Modifier.padding(top = Space.md),
                        horizontalArrangement = Arrangement.spacedBy(Space.sm),
                        verticalArrangement = Arrangement.spacedBy(Space.sm),
                    ) {
                        when {
                            !playback.hasVideo -> PrimaryAction(
                                R.drawable.ms_video_library_24,
                                if (state.isImporting) "Reading…" else "Choose video",
                                enabled = !state.isImporting,
                                onClick = onChooseVideo,
                            )

                            !state.hasWidget -> {
                                PrimaryAction(
                                    R.drawable.ms_add_24,
                                    "Add widget",
                                    onClick = onAddWidget
                                )
                                ChangeVideoAction(state, onChooseVideo)
                            }

                            else -> {
                                PrimaryAction(
                                    content.actionIcon,
                                    content.actionLabel,
                                    enabled = state.optimization.status != OptimizationStatus.RUNNING,
                                    onClick = onTogglePlayback,
                                )
                                ChangeVideoAction(state, onChooseVideo)
                            }
                        }
                    }
                }
            }
        }
    }
}

private val VIDEO_HEADROOM = 96.dp

@Composable
private fun Timeline(
    positionMs: Long,
    durationMs: Long,
    enabled: Boolean,
    labelColor: Color,
    onSeek: (Long) -> Unit,
) {
    var seekingTo by remember { mutableStateOf<Float?>(null) }
    val fraction = seekingTo ?: (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    Column(Modifier.padding(top = Space.sm)) {
        Slider(
            value = fraction,
            onValueChange = { seekingTo = it },
            onValueChangeFinished = {
                seekingTo?.let { onSeek((it * durationMs).toLong().coerceIn(0L, durationMs - 1L)) }
                seekingTo = null
            },
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Loop position" },
        )
        Row {
            Text(
                formatClock((fraction * durationMs).toLong()),
                style = MaterialTheme.typography.labelMedium,
                color = labelColor,
                modifier = Modifier.weight(1f),
            )
            Text(
                formatClock(durationMs),
                style = MaterialTheme.typography.labelMedium,
                color = labelColor,
            )
        }
    }
}

private class NowPlayingContent(
    val label: String,
    val detail: String,
    @param:DrawableRes val actionIcon: Int = R.drawable.ms_play_arrow_24,
    val actionLabel: String = "Play",
)

private fun nowPlayingContent(state: MainUiState): NowPlayingContent {
    val playback = state.playback
    val paused = playback.status == PlaybackStatus.PAUSED || !playback.timelineRunning
    val pause = R.drawable.ms_pause_24
    return when {
        !playback.hasVideo -> NowPlayingContent(
            "Get started",
            "Pick a video from your phone to loop on your home screen.",
        )

        !state.hasWidget -> NowPlayingContent(
            "Almost there",
            "Add the HomeLoop widget to your home screen to start playing.",
        )

        state.optimization.status == OptimizationStatus.RUNNING -> NowPlayingContent(
            "Optimizing",
            "Playback continues when the smoother copy is ready.",
            pause, "Pause",
        )

        playback.status == PlaybackStatus.ERROR -> NowPlayingContent(
            "Needs attention",
            playback.statusDetail.ifBlank { "Playback stopped unexpectedly." },
            actionLabel = "Try again",
        )

        paused -> NowPlayingContent(
            "Paused",
            "Resume to continue where the loop left off.",
            actionLabel = "Resume",
        )

        playback.status == PlaybackStatus.RENDERING -> NowPlayingContent(
            "Playing",
            "Looping on your home screen.",
            pause, "Pause",
        )

        playback.status == PlaybackStatus.SUSPENDED -> NowPlayingContent(
            "Playing",
            "Resting while your home screen is hidden. The loop keeps its place.",
            pause, "Pause",
        )

        playback.status == PlaybackStatus.STARTING -> NowPlayingContent(
            "Starting",
            "Preparing the video…",
            pause, "Pause",
        )

        else -> NowPlayingContent(
            "Ready",
            "Playback starts automatically when Android allows it.",
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PrimaryAction(
    @DrawableRes icon: Int,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shapes = ButtonDefaults.shapes(),
        contentPadding = ButtonDefaults.contentPaddingFor(
            ButtonDefaults.MinHeight,
            hasStartIcon = true
        ),
    ) { ButtonLabel(icon, label) }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ChangeVideoAction(state: MainUiState, onChooseVideo: () -> Unit) {
    FilledTonalButton(
        onClick = onChooseVideo,
        enabled = !state.isImporting && state.optimization.status != OptimizationStatus.RUNNING,
        shapes = ButtonDefaults.shapes(),
        contentPadding = ButtonDefaults.contentPaddingFor(
            ButtonDefaults.MinHeight,
            hasStartIcon = true
        ),
    ) {
        ButtonLabel(
            R.drawable.ms_video_library_24,
            if (state.isImporting) "Reading…" else "Change video"
        )
    }
}

@Composable
private fun SetupSection(
    state: MainUiState,
    onOpenBatterySettings: () -> Unit,
    onOpenUsageAccess: () -> Unit,
) {
    val playback = state.playback
    val needsBackgroundStart = playback.hasVideo && state.hasWidget && !state.backgroundStartAllowed
    val needsUsageAccess = playback.hasVideo && state.hasWidget &&
            playback.renderingPolicy == RenderingPolicy.HOME_SCREEN_ONLY && !state.usageAccessGranted
    val count = listOf(needsBackgroundStart, needsUsageAccess).count { it }
    if (count == 0) return
    SettingsSection("Finish setup") {
        var index = 0
        if (needsBackgroundStart) {
            SettingsActionRow(
                icon = R.drawable.ms_restart_alt_24,
                title = "Resume after restart",
                supporting = "Allow HomeLoop to start again on its own after a restart or crash",
                onClick = onOpenBatterySettings,
                index = index++,
                count = count,
            )
        }
        if (needsUsageAccess) {
            SettingsActionRow(
                icon = R.drawable.ms_home_filled_24,
                title = "Only play on Home",
                supporting = "Allow usage access so the video rests while other apps are open",
                onClick = onOpenUsageAccess,
                index = index,
                count = count,
            )
        }
    }
}

private enum class VideoEditor { TRIM, CROP }

@Composable
private fun VideoSection(
    state: MainUiState,
    onTrimChanged: (Long, Long) -> Unit,
    onCropChanged: (CropInsets) -> Unit,
    loadPreviewFrame: suspend (Int) -> Bitmap?,
    loadFrameAt: suspend (Long, Int) -> Bitmap?,
    detectBlackBars: suspend () -> CropInsets?,
) {
    val playback = state.playback
    val editable = state.optimization.status != OptimizationStatus.RUNNING
    var editor by rememberSaveable { mutableStateOf<VideoEditor?>(null) }
    val fullLength = playback.trimStartMs <= 0L && playback.trimEndMs >= playback.sourceDurationMs
    val crop = playback.cropInsets
    val sizeKnown = playback.videoWidth > 0 && playback.videoHeight > 0
    SettingsSection("Video") {
        SettingsActionRow(
            icon = R.drawable.ms_content_cut_24,
            title = "Trim",
            supporting = if (fullLength) {
                "Full length · ${formatClock(playback.sourceDurationMs)}"
            } else {
                "${formatClock(playback.trimStartMs)} – ${formatClock(playback.trimEndMs)} · " +
                        "${formatClock(playback.durationMs)} loop"
            },
            onClick = { editor = VideoEditor.TRIM },
            enabled = editable && playback.sourceDurationMs > 0L,
            index = 0,
            count = 2,
        )
        SettingsActionRow(
            icon = R.drawable.ms_crop_filled_24,
            title = "Crop",
            supporting = when {
                !sizeKnown -> "Reading the video size…"
                crop == CropInsets() -> "Full frame · ${playback.videoWidth} × ${playback.videoHeight}"
                else -> "Showing ${crop.remainingWidth(playback.videoWidth)} × " +
                        "${crop.remainingHeight(playback.videoHeight)} of " +
                        "${playback.videoWidth} × ${playback.videoHeight}"
            },
            onClick = { editor = VideoEditor.CROP },
            enabled = editable && sizeKnown,
            index = 1,
            count = 2,
        )
    }
    when (editor) {
        VideoEditor.TRIM -> TrimSheet(
            sourceDurationMs = playback.sourceDurationMs,
            startMs = playback.trimStartMs,
            endMs = playback.trimEndMs,
            sourceFrameRate = playback.sourceFrameRate,
            videoWidth = playback.videoWidth,
            videoHeight = playback.videoHeight,
            crop = crop,
            loadFrameAt = loadFrameAt,
            onApply = onTrimChanged,
            onDismiss = { editor = null },
        )

        VideoEditor.CROP -> CropSheet(
            videoWidth = playback.videoWidth,
            videoHeight = playback.videoHeight,
            crop = crop,
            loadPreviewFrame = loadPreviewFrame,
            detectBlackBars = detectBlackBars,
            onApply = onCropChanged,
            onDismiss = { editor = null },
        )

        null -> Unit
    }
}

@Composable
private fun PlaybackSection(
    state: MainUiState,
    onAudioChanged: (Boolean) -> Unit,
    onWidgetTapActionSelected: (WidgetTapAction) -> Unit,
    onOptimize: () -> Unit,
    onCancelOptimization: () -> Unit,
) {
    val playback = state.playback
    val knownMissingAudio = playback.inputDiagnostics.hasData &&
            playback.inputDiagnostics.audioTrackCount <= 0
    val showOptimization = state.hasWidget && (
            state.optimization.profile != null || state.optimization.status == OptimizationStatus.RUNNING
            )
    val count = if (showOptimization) 3 else 2
    SettingsSection("Playback") {
        SettingsSwitchRow(
            icon = R.drawable.ms_volume_up_filled_24,
            title = "Sound",
            supporting = when {
                knownMissingAudio -> "This video has no sound"
                !playback.audioEnabled -> "Off"
                playback.hiddenAudioPolicy == HiddenAudioPolicy.CONTINUE -> "Keeps playing in other apps"
                else -> "Plays while your home screen is open"
            },
            checked = playback.audioEnabled && !knownMissingAudio,
            onChecked = onAudioChanged,
            enabled = !knownMissingAudio,
            index = 0,
            count = count,
        )
        ChoiceDialogRow(
            icon = R.drawable.ms_touch_app_filled_24,
            title = "Tapping the widget",
            options = WidgetTapAction.entries,
            selected = playback.widgetTapAction,
            optionLabel = { it.label },
            optionDescription = { it.description },
            onSelected = onWidgetTapActionSelected,
            index = 1,
            count = count,
        )
        if (showOptimization) {
            OptimizationRow(state, onOptimize, onCancelOptimization, index = 2, count = count)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun OptimizationRow(
    state: MainUiState,
    onOptimize: () -> Unit,
    onCancel: () -> Unit,
    index: Int,
    count: Int,
) {
    val optimization = state.optimization
    val icon = R.drawable.ms_speed_filled_24
    when {
        optimization.status == OptimizationStatus.RUNNING -> SegmentedListItem(
            shapes = ListItemDefaults.segmentedShapes(index, count),
            colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            verticalAlignment = Alignment.CenterVertically,
            leadingContent = { Icon(painterResource(icon), null) },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                    Text(
                        "${optimization.progressPercent}% · " +
                                (optimization.remainingMs?.let(::formatTimeLeft)
                                    ?: "Estimating time left…"),
                    )
                    WavyProgress(optimization.progressPercent / 100f)
                }
            },
            trailingContent = {
                OutlinedButton(
                    onClick = onCancel,
                    shapes = ButtonDefaults.shapes()
                ) { Text("Cancel") }
            },
            content = { Text("Optimizing for this widget") },
        )

        optimization.isUsable -> SegmentedListItem(
            shapes = ListItemDefaults.segmentedShapes(index, count),
            colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            verticalAlignment = Alignment.CenterVertically,
            leadingContent = { Icon(painterResource(icon), null) },
            supportingContent = { Text("Using a lighter copy made for this widget") },
            content = { Text("Optimized") },
        )

        else -> SettingsActionRow(
            icon = icon,
            title = "Smoother playback",
            supporting = when (optimization.status) {
                OptimizationStatus.STALE -> "The widget changed. Optimize again to keep playback smooth"
                OptimizationStatus.FAILED -> "Optimization failed. Tap to try again"
                else -> if (state.optimizationRecommendation.recommended) {
                    "Recommended: make a lighter copy sized for this widget"
                } else {
                    "Make a lighter copy sized for this widget"
                }
            },
            onClick = onOptimize,
            index = index,
            count = count,
        )
    }
}
