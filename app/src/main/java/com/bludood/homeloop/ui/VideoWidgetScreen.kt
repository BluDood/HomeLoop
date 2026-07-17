package com.bludood.homeloop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bludood.homeloop.MainUiState
import com.bludood.homeloop.data.AdvancedSection
import com.bludood.homeloop.data.AppUiPreferences
import com.bludood.homeloop.data.AppViewMode
import com.bludood.homeloop.data.FrameRateOption
import com.bludood.homeloop.data.CropEdge
import com.bludood.homeloop.data.ScalingDirection
import com.bludood.homeloop.data.ScalingFilter
import com.bludood.homeloop.data.PlaybackOptions
import com.bludood.homeloop.data.PlaybackStatus
import com.bludood.homeloop.data.ResolutionOption
import com.bludood.homeloop.data.VirtualTimeline
import com.bludood.homeloop.data.WidgetTapAction
import com.bludood.homeloop.data.HiddenAudioPolicy
import com.bludood.homeloop.data.RenderingPolicy
import com.bludood.homeloop.data.OptimizationStatus
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoWidgetScreen(
    state: MainUiState,
    onChooseVideo: () -> Unit,
    onAddWidget: () -> Unit,
    onFpsSelected: (FrameRateOption) -> Unit,
    onResolutionSelected: (ResolutionOption) -> Unit,
    onCropChanged: (CropEdge, Int) -> Unit,
    onTrimChanged: (Long, Long) -> Unit,
    onScalingFilterChanged: (ScalingDirection, ScalingFilter) -> Unit,
    onAudioChanged: (Boolean) -> Unit,
    onRenderingPolicySelected: (RenderingPolicy) -> Unit,
    onHiddenAudioPolicySelected: (HiddenAudioPolicy) -> Unit,
    onOpenUsageAccess: () -> Unit,
    onWidgetTapActionSelected: (WidgetTapAction) -> Unit,
    onRandomize: () -> Unit,
    onSeek: (Long) -> Unit,
    onOptimize: () -> Unit,
    onCancelOptimization: () -> Unit,
    onDeleteOptimizedCopy: () -> Unit,
    onTogglePlayback: () -> Unit,
    onMessageShown: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val appContext = LocalContext.current.applicationContext
    val uiPreferences = remember(appContext) { AppUiPreferences(appContext) }
    var viewMode by rememberSaveable {
        mutableStateOf(uiPreferences.viewMode())
    }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            onMessageShown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("HomeLoop")
                        Text(
                            text = "Video widgets for your home screen",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            val selectedMode = if (viewMode == AppViewMode.BASIC) {
                                AppViewMode.ADVANCED
                            } else {
                                AppViewMode.BASIC
                            }
                            viewMode = selectedMode
                            uiPreferences.setViewMode(selectedMode)
                        },
                    ) {
                        Text(if (viewMode == AppViewMode.BASIC) "Advanced" else "Basic")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(contentPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (viewMode == AppViewMode.BASIC) {
                BasicView(
                    state = state,
                    onChooseVideo = onChooseVideo,
                    onAddWidget = onAddWidget,
                    onFpsSelected = onFpsSelected,
                    onResolutionSelected = onResolutionSelected,
                    onAudioChanged = onAudioChanged,
                    onRenderingPolicySelected = onRenderingPolicySelected,
                    onHiddenAudioPolicySelected = onHiddenAudioPolicySelected,
                    onOpenUsageAccess = onOpenUsageAccess,
                    onWidgetTapActionSelected = onWidgetTapActionSelected,
                    onOptimize = onOptimize,
                    onCancelOptimization = onCancelOptimization,
                    onTogglePlayback = onTogglePlayback,
                )
            } else {
                SetupCard(
                    state = state,
                    onChooseVideo = onChooseVideo,
                    onAddWidget = onAddWidget,
                )
                SettingsCard(
                    state = state,
                    onFpsSelected = onFpsSelected,
                    onResolutionSelected = onResolutionSelected,
                    onCropChanged = onCropChanged,
                    onTrimChanged = onTrimChanged,
                    onScalingFilterChanged = onScalingFilterChanged,
                    onAudioChanged = onAudioChanged,
                    onRenderingPolicySelected = onRenderingPolicySelected,
                    onHiddenAudioPolicySelected = onHiddenAudioPolicySelected,
                    onOpenUsageAccess = onOpenUsageAccess,
                    onWidgetTapActionSelected = onWidgetTapActionSelected,
                )
                OptimizationCard(
                    state = state,
                    onOptimize = onOptimize,
                    onCancel = onCancelOptimization,
                    onDelete = onDeleteOptimizedCopy,
                )
                InputDiagnosticsCard(state)
                RuntimeDiagnosticsCard(state)
                TimelineCard(state = state, onRandomize = onRandomize, onSeek = onSeek)
                PlaybackActions(state = state, onTogglePlayback = onTogglePlayback)
                Text(
                    text = "Pause freezes both decoding and the virtual playhead. When only the screen " +
                        "turns off, decoding sleeps while the timeline continues.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 28.dp),
                )
            }
        }
    }
}

@Composable
private fun BasicView(
    state: MainUiState,
    onChooseVideo: () -> Unit,
    onAddWidget: () -> Unit,
    onFpsSelected: (FrameRateOption) -> Unit,
    onResolutionSelected: (ResolutionOption) -> Unit,
    onAudioChanged: (Boolean) -> Unit,
    onRenderingPolicySelected: (RenderingPolicy) -> Unit,
    onHiddenAudioPolicySelected: (HiddenAudioPolicy) -> Unit,
    onOpenUsageAccess: () -> Unit,
    onWidgetTapActionSelected: (WidgetTapAction) -> Unit,
    onOptimize: () -> Unit,
    onCancelOptimization: () -> Unit,
    onTogglePlayback: () -> Unit,
) {
    LaunchedEffect(state.playback.renderingPolicy) {
        if (state.playback.renderingPolicy != RenderingPolicy.HOME_SCREEN_ONLY) {
            onRenderingPolicySelected(RenderingPolicy.HOME_SCREEN_ONLY)
        }
    }
    BasicPlaybackStatusCard(state = state, onTogglePlayback = onTogglePlayback)
    SetupCard(state = state, onChooseVideo = onChooseVideo, onAddWidget = onAddWidget)
    BasicQualityCard(
        state = state,
        onFpsSelected = onFpsSelected,
        onResolutionSelected = onResolutionSelected,
    )
    BasicBehaviorCard(
        state = state,
        onAudioChanged = onAudioChanged,
        onHiddenAudioPolicySelected = onHiddenAudioPolicySelected,
        onOpenUsageAccess = onOpenUsageAccess,
        onWidgetTapActionSelected = onWidgetTapActionSelected,
    )
    BasicOptimizationCard(
        state = state,
        onOptimize = onOptimize,
        onCancel = onCancelOptimization,
    )
    Spacer(Modifier.height(14.dp))
}

@Composable
private fun BasicPlaybackStatusCard(
    state: MainUiState,
    onTogglePlayback: () -> Unit,
) {
    val playback = state.playback
    val paused = playback.status == PlaybackStatus.PAUSED || !playback.timelineRunning
    val active = playback.status in setOf(
        PlaybackStatus.STARTING,
        PlaybackStatus.RENDERING,
        PlaybackStatus.SUSPENDED,
    )
    val statusTitle = when {
        playback.status == PlaybackStatus.ERROR -> "Needs attention"
        paused -> "Paused"
        playback.status == PlaybackStatus.STARTING -> "Starting"
        playback.status == PlaybackStatus.RENDERING -> "Playing"
        playback.status == PlaybackStatus.SUSPENDED && state.hasWidget -> "Sleeping"
        else -> "Stopped"
    }
    val statusColor = when {
        playback.status == PlaybackStatus.ERROR -> MaterialTheme.colorScheme.error
        playback.status == PlaybackStatus.RENDERING -> MaterialTheme.colorScheme.primary
        playback.status == PlaybackStatus.SUSPENDED -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val buttonLabel = when {
        paused -> "Resume"
        active -> "Pause"
        else -> "Start"
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (playback.status == PlaybackStatus.ERROR) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "●",
                color = statusColor,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = statusTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = playback.statusDetail.ifBlank { "Ready" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(12.dp))
            Button(
                onClick = onTogglePlayback,
                enabled = playback.hasVideo && state.hasWidget,
            ) {
                Text(buttonLabel)
            }
        }
    }
}

@Composable
private fun BasicQualityCard(
    state: MainUiState,
    onFpsSelected: (FrameRateOption) -> Unit,
    onResolutionSelected: (ResolutionOption) -> Unit,
) {
    val resolutionOptions = remember { PlaybackOptions.resolutions.reversed() }
    val frameRateOptions = remember { PlaybackOptions.frameRates.reversed() }
    val controlsEnabled = state.optimization.status != OptimizationStatus.RUNNING

    SectionCard(title = "Quality") {
        Text("Resolution", style = MaterialTheme.typography.labelLarge)
        Text(
            "100% matches the useful widget, display, and video resolution.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        DiscreteOptionSlider(
            selected = state.playback.resolution,
            options = resolutionOptions,
            optionLabel = { it.label },
            onSelected = onResolutionSelected,
            enabled = controlsEnabled,
        )
        Text(
            text = if (state.outputSize.width > 0 && state.outputSize.height > 0) {
                "Output: ${state.outputSize.label}"
            } else {
                "Add the widget to calculate its output size"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )

        Spacer(Modifier.height(18.dp))
        Text("Frame rate", style = MaterialTheme.typography.labelLarge)
        Text(
            "Uses the video's detected frame rate or an even fraction of it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        DiscreteOptionSlider(
            selected = state.playback.frameRate,
            options = frameRateOptions,
            optionLabel = { it.label },
            onSelected = onFpsSelected,
            enabled = controlsEnabled,
        )
        val sourceDivisor = state.playback.frameRate.sourceDivisor
        Text(
            text = if (state.playback.sourceFrameRate > 0f && sourceDivisor != null) {
                "Output: ${formatFrameRate(state.playback.sourceFrameRate / sourceDivisor)} FPS"
            } else {
                "Choose a video to calculate the output frame rate"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        if (!controlsEnabled) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Quality is locked while optimization is running.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun <T> DiscreteOptionSlider(
    selected: T,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelected: (T) -> Unit,
    enabled: Boolean,
) {
    val selectedIndex = options.indexOf(selected).coerceAtLeast(0)
    var sliderValue by remember(selectedIndex) { mutableFloatStateOf(selectedIndex.toFloat()) }
    val highlightedIndex = sliderValue.roundToInt().coerceIn(options.indices)

    Slider(
        value = sliderValue,
        onValueChange = { sliderValue = it },
        onValueChangeFinished = {
            val chosen = options[sliderValue.roundToInt().coerceIn(options.indices)]
            if (chosen != selected) onSelected(chosen)
        },
        enabled = enabled && options.size > 1,
        valueRange = 0f..options.lastIndex.toFloat(),
        steps = (options.size - 2).coerceAtLeast(0),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            Text(
                text = optionLabel(option),
                style = MaterialTheme.typography.labelSmall,
                color = if (index == highlightedIndex) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                fontWeight = if (index == highlightedIndex) FontWeight.SemiBold else FontWeight.Normal,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun BasicBehaviorCard(
    state: MainUiState,
    onAudioChanged: (Boolean) -> Unit,
    onHiddenAudioPolicySelected: (HiddenAudioPolicy) -> Unit,
    onOpenUsageAccess: () -> Unit,
    onWidgetTapActionSelected: (WidgetTapAction) -> Unit,
) {
    val playback = state.playback
    val knownMissingAudio = playback.inputDiagnostics.hasData &&
        playback.inputDiagnostics.audioTrackCount <= 0

    SectionCard(title = "Playback behavior") {
        ToggleSetting(
            title = "Audio",
            description = when {
                knownMissingAudio -> "The selected video has no detected audio track"
                playback.audioEnabled -> "Plays while the launcher is open"
                else -> "Audio decoding is completely off"
            },
            checked = playback.audioEnabled,
            enabled = playback.hasVideo && !knownMissingAudio,
            onCheckedChange = onAudioChanged,
        )
        AnimatedVisibility(visible = playback.audioEnabled && !knownMissingAudio) {
            Column {
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                ToggleSetting(
                    title = "Continue audio in other apps",
                    description = "Uses more battery and keeps the audio pipeline active",
                    checked = playback.hiddenAudioPolicy == HiddenAudioPolicy.CONTINUE,
                    enabled = true,
                    onCheckedChange = { continueAudio ->
                        onHiddenAudioPolicySelected(
                            if (continueAudio) HiddenAudioPolicy.CONTINUE
                            else HiddenAudioPolicy.SUSPEND,
                        )
                    },
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
        Text("Widget tap", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(6.dp))
        Selector(
            selected = playback.widgetTapAction.label,
            options = WidgetTapAction.entries,
            optionLabel = { it.label },
            onSelected = onWidgetTapActionSelected,
        )

        if (
            playback.renderingPolicy == RenderingPolicy.HOME_SCREEN_ONLY &&
            !state.usageAccessGranted
        ) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            Text(
                "Allow Home detection",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "Usage Access lets the app stop video rendering while another app is open. " +
                    "Without it, playback safely continues while the screen is on.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onOpenUsageAccess,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Open Usage Access settings")
            }
        }
    }
}

@Composable
private fun ToggleSetting(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun BasicOptimizationCard(
    state: MainUiState,
    onOptimize: () -> Unit,
    onCancel: () -> Unit,
) {
    val optimization = state.optimization

    SectionCard(title = "Optimization") {
        when {
            optimization.status == OptimizationStatus.RUNNING -> {
                Text(
                    "Optimizing video",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    optimization.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { optimization.progressPercent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Cancel optimization")
                }
            }
            optimization.isUsable -> {
                Text(
                    "✓  Video is optimized",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                optimization.profile?.let { profile ->
                    Text(
                        profile.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            else -> {
                val explanation = when (optimization.status) {
                    OptimizationStatus.STALE ->
                        "The video needs to be optimized again for the current widget settings."
                    OptimizationStatus.FAILED -> optimization.detail
                    OptimizationStatus.CANCELLED -> "The previous optimization was cancelled."
                    else -> "Create a smaller copy tailored to this widget for smoother playback."
                }
                Text(
                    explanation,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (optimization.status == OptimizationStatus.FAILED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = onOptimize,
                    enabled = state.playback.hasVideo && state.hasWidget &&
                        optimization.profile != null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (optimization.status == OptimizationStatus.FAILED) {
                            "Try optimization again"
                        } else {
                            "Optimize for this widget"
                        },
                    )
                }
                if (!state.playback.hasVideo || !state.hasWidget) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Choose a video and add the widget before optimizing.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SetupCard(
    state: MainUiState,
    onChooseVideo: () -> Unit,
    onAddWidget: () -> Unit,
) {
    SectionCard(title = "Set up") {
        StepRow(
            number = "1",
            title = if (state.playback.hasVideo) state.playback.videoName else "Choose a video",
            detail = if (state.playback.hasVideo) "Video access is saved across restarts" else "Pick a local video file",
            actionLabel = if (state.isImporting) "Reading…" else "Choose",
            enabled = !state.isImporting,
            onAction = onChooseVideo,
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
        StepRow(
            number = "2",
            title = if (state.hasWidget) "Widget added" else "Add the home-screen widget",
            detail = if (state.hasWidget) "${state.widgetCount} active widget${if (state.widgetCount == 1) "" else "s"}" else "Your launcher will ask for placement",
            actionLabel = if (state.hasWidget) "Add another" else "Add widget",
            enabled = true,
            onAction = onAddWidget,
        )
    }
}

@Composable
private fun StepRow(
    number: String,
    title: String,
    detail: String,
    actionLabel: String,
    enabled: Boolean,
    onAction: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = number,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(30.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(10.dp))
        TextButton(enabled = enabled, onClick = onAction) { Text(actionLabel) }
    }
}

@Composable
private fun SettingsCard(
    state: MainUiState,
    onFpsSelected: (FrameRateOption) -> Unit,
    onResolutionSelected: (ResolutionOption) -> Unit,
    onCropChanged: (CropEdge, Int) -> Unit,
    onTrimChanged: (Long, Long) -> Unit,
    onScalingFilterChanged: (ScalingDirection, ScalingFilter) -> Unit,
    onAudioChanged: (Boolean) -> Unit,
    onRenderingPolicySelected: (RenderingPolicy) -> Unit,
    onHiddenAudioPolicySelected: (HiddenAudioPolicy) -> Unit,
    onOpenUsageAccess: () -> Unit,
    onWidgetTapActionSelected: (WidgetTapAction) -> Unit,
) {
    SectionCard(
        title = "Rendering options",
        section = AdvancedSection.RENDERING_OPTIONS,
    ) {
        Text("Frame rate", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(6.dp))
        Selector(
            selected = state.playback.frameRate.label,
            options = PlaybackOptions.frameRates,
            optionLabel = { it.label },
            onSelected = onFpsSelected,
        )
        Text(
            if (state.playback.sourceFrameRate > 0f) {
                "Target: ${formatFrameRate(state.playback.sourceFrameRate / requireNotNull(state.playback.frameRate.sourceDivisor))} FPS " +
                    "from the detected ${formatFrameRate(state.playback.sourceFrameRate)} FPS video rate"
            } else {
                "Uses the detected video rate, or an exact whole-frame fraction of it"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))
        Text("Resolution", style = MaterialTheme.typography.labelLarge)
        Text(
            "100% is pixel-correct for what the widget and display can show, capped at the " +
                "video's native resolution. Lower percentages scale both dimensions down to " +
                "reduce decoder, GPU, and launcher load.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Selector(
            selected = state.playback.resolution.label,
            options = PlaybackOptions.resolutions,
            optionLabel = { it.label },
            onSelected = onResolutionSelected,
        )
        Spacer(Modifier.height(14.dp))
        Text("Scaling filters", style = MaterialTheme.typography.labelLarge)
        Text(
            "Higher-quality filters can substantially increase GPU load",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text("Upscaling", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        Selector(
            selected = state.playback.upscaleFilter.label,
            options = ScalingFilter.entries,
            optionLabel = { it.label },
            onSelected = { onScalingFilterChanged(ScalingDirection.UPSCALE, it) },
        )
        Text(
            state.playback.upscaleFilter.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text("Downscaling", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        Selector(
            selected = state.playback.downscaleFilter.label,
            options = ScalingFilter.entries,
            optionLabel = { it.label },
            onSelected = { onScalingFilterChanged(ScalingDirection.DOWNSCALE, it) },
        )
        Text(
            state.playback.downscaleFilter.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))
        Text("Crop", style = MaterialTheme.typography.labelLarge)
        Text(
            "Pixels removed from each edge of the displayed source video",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CropInput(
                label = "Top",
                value = state.playback.cropInsets.top,
                onCommit = { onCropChanged(CropEdge.TOP, it) },
                modifier = Modifier.weight(1f),
            )
            CropInput(
                label = "Right",
                value = state.playback.cropInsets.right,
                onCommit = { onCropChanged(CropEdge.RIGHT, it) },
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CropInput(
                label = "Bottom",
                value = state.playback.cropInsets.bottom,
                onCommit = { onCropChanged(CropEdge.BOTTOM, it) },
                modifier = Modifier.weight(1f),
            )
            CropInput(
                label = "Left",
                value = state.playback.cropInsets.left,
                onCommit = { onCropChanged(CropEdge.LEFT, it) },
                modifier = Modifier.weight(1f),
            )
        }
        if (state.playback.videoWidth > 0 && state.playback.videoHeight > 0) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Visible source: " +
                    "${state.playback.cropInsets.remainingWidth(state.playback.videoWidth)} × " +
                    "${state.playback.cropInsets.remainingHeight(state.playback.videoHeight)} px",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(14.dp))
        Text("Trim and loop", style = MaterialTheme.typography.labelLarge)
        Text(
            "Set source timestamps as HH:MM:SS or raw seconds. Playback loops inside this range.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TrimTimeInput(
                label = "Start",
                valueMs = state.playback.trimStartMs,
                onCommit = { onTrimChanged(it, state.playback.trimEndMs) },
                modifier = Modifier.weight(1f),
            )
            TrimTimeInput(
                label = "End",
                valueMs = state.playback.trimEndMs,
                onCommit = { onTrimChanged(state.playback.trimStartMs, it) },
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Loop duration: ${VirtualTimeline.format(state.playback.durationMs)} · " +
                "Source duration: ${VirtualTimeline.format(state.playback.sourceDurationMs)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Play audio", style = MaterialTheme.typography.labelLarge)
                Text(
                    "Audio plays through the foreground service",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = state.playback.audioEnabled, onCheckedChange = onAudioChanged)
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 14.dp))
        Text("Resource use", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(6.dp))
        Selector(
            selected = state.playback.renderingPolicy.label,
            options = RenderingPolicy.entries,
            optionLabel = { it.label },
            onSelected = onRenderingPolicySelected,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            state.playback.renderingPolicy.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.playback.renderingPolicy == RenderingPolicy.HOME_SCREEN_ONLY) {
            if (!state.usageAccessGranted) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Usage Access is needed to identify when the launcher is foreground. " +
                        "Until it is granted, rendering remains enabled while the screen is on.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = onOpenUsageAccess,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Grant Usage Access")
                }
            } else {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Usage Access granted · launcher detection active",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(12.dp))
            Text("When Home is not open", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            Selector(
                selected = state.playback.hiddenAudioPolicy.label,
                options = HiddenAudioPolicy.entries,
                optionLabel = { it.label },
                onSelected = onHiddenAudioPolicySelected,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (!state.playback.audioEnabled) {
                    "Audio is disabled, so the complete pipeline sleeps while hidden."
                } else {
                    state.playback.hiddenAudioPolicy.description
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 14.dp))
        Text("Widget tap", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(6.dp))
        Selector(
            selected = state.playback.widgetTapAction.label,
            options = WidgetTapAction.entries,
            optionLabel = { it.label },
            onSelected = onWidgetTapActionSelected,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            state.playback.widgetTapAction.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun OptimizationCard(
    state: MainUiState,
    onOptimize: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    val optimization = state.optimization
    val profile = optimization.profile
    SectionCard(
        title = "Optimize for this widget",
        section = AdvancedSection.OPTIMIZATION,
    ) {
        Text(
            "Finish resizing the widget and adjusting crop, size, and FPS first. The original " +
                "is always retained.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(10.dp))
        DiagnosticRow(
            "Current target",
            profile?.label ?: if (state.outputSize.width > 0) state.outputSize.label else "Unavailable",
        )
        profile?.let {
            DiagnosticRow("Estimated output", formatBytes(it.estimatedBytes))
            DiagnosticRow("Video bitrate", formatBitrate(it.videoBitrate))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            if (state.optimizationRecommendation.recommended) {
                "Recommended: ${state.optimizationRecommendation.summary}"
            } else {
                state.optimizationRecommendation.summary
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (state.optimizationRecommendation.recommended) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Spacer(Modifier.height(10.dp))
        Text(
            optimization.detail,
            style = MaterialTheme.typography.labelLarge,
            color = when (optimization.status) {
                OptimizationStatus.FAILED -> MaterialTheme.colorScheme.error
                OptimizationStatus.READY -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurface
            },
        )
        if (optimization.status == OptimizationStatus.RUNNING) {
            Spacer(Modifier.height(8.dp))
            Text(
                "You can leave the app and use your phone while optimization continues in the " +
                    "background. Keep its progress notification active and do not force-stop the " +
                    "app. Android may stop background media processing after 6 hours.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { optimization.progressPercent / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text("Cancel optimization")
            }
        } else {
            optimization.outputPath?.let {
                if (optimization.outputSizeBytes > 0L) {
                    Text(
                        "Stored copy: ${formatBytes(optimization.outputSizeBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onOptimize,
                enabled = state.playback.hasVideo && state.hasWidget && profile != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (optimization.status == OptimizationStatus.NONE) "Optimize current setup"
                    else "Re-optimize current setup",
                )
            }
            if (optimization.outputPath != null) {
                TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                    Text("Delete optimized copy")
                }
            }
        }
    }
}

@Composable
private fun InputDiagnosticsCard(state: MainUiState) {
    val playback = state.playback
    val diagnostics = playback.inputDiagnostics
    SectionCard(
        title = "Input diagnostics",
        section = AdvancedSection.INPUT_DIAGNOSTICS,
    ) {
        if (!diagnostics.hasData) {
            Text(
                if (playback.hasVideo) "Diagnostics will be refreshed when playback starts"
                else "Choose a video to inspect its format",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }
        DiagnosticRow(
            "Video",
            "${playback.videoWidth} × ${playback.videoHeight} · " +
                "${formatFrameRate(playback.sourceFrameRate)} FPS",
        )
        DiagnosticRow("Duration", VirtualTimeline.format(playback.sourceDurationMs))
        DiagnosticRow("File size", formatBytes(diagnostics.fileSizeBytes))
        DiagnosticRow("Container", diagnostics.containerMime.ifBlank { "Unknown" })
        DiagnosticRow("Video codec", diagnostics.videoMime.ifBlank { "Unknown" })
        DiagnosticRow("Source bitrate", formatBitrate(diagnostics.videoBitrate))
        DiagnosticRow("Dynamic range", if (diagnostics.hdr) "HDR" else "SDR / unspecified")
        DiagnosticRow(
            "Tracks",
            "${diagnostics.audioTrackCount} audio · ${diagnostics.subtitleTrackCount} other",
        )
        DiagnosticRow(
            "Storage access",
            if (diagnostics.seekable) "Seekable" else "Potentially slow / non-seekable",
        )
        DiagnosticRow("Provider", diagnostics.provider.ifBlank { "Local or direct file" })
        DiagnosticRow(
            "Hardware decoder",
            diagnostics.hardwareDecoder.ifBlank { "Not confirmed" },
        )
    }
}

@Composable
private fun RuntimeDiagnosticsCard(state: MainUiState) {
    val diagnostics = state.playback.runtimeDiagnostics
    SectionCard(
        title = "Runtime diagnostics",
        section = AdvancedSection.RUNTIME_DIAGNOSTICS,
    ) {
        Text(
            if (diagnostics.active) "Live decoder sample" else "Last decoder sample",
            style = MaterialTheme.typography.labelLarge,
            color = if (diagnostics.active) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        DiagnosticRow("Decoded frames", diagnostics.decodedFrames.toString())
        DiagnosticRow("Cadence-selected", diagnostics.selectedFrames.toString())
        DiagnosticRow("Published to widget", diagnostics.publishedFrames.toString())
        DiagnosticRow(
            "Late / missed",
            "${diagnostics.lateDroppedFrames} · ${formatDecimal(diagnostics.missedPercent)}%",
        )
        DiagnosticRow("Intentionally skipped", diagnostics.cadenceSkippedFrames.toString())
        DiagnosticRow("Decoder throughput", "${formatDecimal(diagnostics.decodeFps)} FPS")
        DiagnosticRow("Actual widget FPS", "${formatDecimal(diagnostics.publishFps)} FPS")
        DiagnosticRow(
            "Audio drift (+ = ahead)",
            "${if (diagnostics.audioDriftMs > 0L) "+" else ""}${diagnostics.audioDriftMs} ms",
        )
        DiagnosticRow("Audio restarts", diagnostics.audioRestartCount.toString())
        DiagnosticRow(
            "Latest audio error",
            diagnostics.audioError.ifBlank { "None" },
        )
        DiagnosticRow("App CPU", "${formatDecimal(diagnostics.appCpuPercent)}% of device")
        DiagnosticRow("App RAM", "${formatDecimal(diagnostics.appRamMb)} MB PSS")
        DiagnosticRow("Measured time", VirtualTimeline.format(diagnostics.sampleElapsedMs))
        Text(
            "Cadence-skipped frames are intentional. Late/missed frames indicate that a selected " +
                "frame was already obsolete before GPU readback.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            modifier = Modifier.weight(1.35f),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun CropInput(
    label: String,
    value: Int,
    onCommit: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    var text by remember(value) { mutableStateOf(value.toString()) }
    var wasFocused by remember { mutableStateOf(false) }
    fun commit() {
        val parsed = text.toIntOrNull()?.coerceIn(0, MAX_CROP_INPUT) ?: value
        text = parsed.toString()
        if (parsed != value) onCommit(parsed)
    }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            if (input.length <= MAX_CROP_DIGITS && input.all(Char::isDigit)) text = input
        },
        label = { Text(label) },
        suffix = { Text("px") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(
            onDone = { focusManager.clearFocus() },
        ),
        modifier = modifier.onFocusChanged { focusState ->
            if (wasFocused && !focusState.isFocused) commit()
            wasFocused = focusState.isFocused
        },
    )
}

@Composable
private fun TrimTimeInput(
    label: String,
    valueMs: Long,
    onCommit: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    var text by remember(valueMs) { mutableStateOf(VirtualTimeline.format(valueMs)) }
    var wasFocused by remember { mutableStateOf(false) }
    fun commit() {
        val parsed = parseTrimTime(text)
        if (parsed == null) {
            text = VirtualTimeline.format(valueMs)
        } else {
            text = VirtualTimeline.format(parsed)
            if (parsed != valueMs) onCommit(parsed)
        }
    }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            if (input.length <= MAX_TRIM_TIME_LENGTH && input.all { it.isDigit() || it == ':' }) {
                text = input
            }
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Ascii,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        modifier = modifier.onFocusChanged { focusState ->
            if (wasFocused && !focusState.isFocused) commit()
            wasFocused = focusState.isFocused
        },
    )
}

private fun parseTrimTime(value: String): Long? {
    val parts = value.trim().split(':')
    if (parts.isEmpty() || parts.size > 3 || parts.any(String::isBlank)) return null
    val numbers = parts.map { it.toLongOrNull() ?: return null }
    val totalSeconds = when (numbers.size) {
        1 -> numbers[0]
        2 -> {
            if (numbers[1] >= 60L) return null
            numbers[0] * 60L + numbers[1]
        }
        3 -> {
            if (numbers[1] >= 60L || numbers[2] >= 60L) return null
            numbers[0] * 3_600L + numbers[1] * 60L + numbers[2]
        }
        else -> return null
    }
    if (totalSeconds < 0L || totalSeconds > Long.MAX_VALUE / 1_000L) return null
    return totalSeconds * 1_000L
}

@Composable
private fun <T> Selector(
    selected: String,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelected: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Text(selected, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("▾")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        expanded = false
                        onSelected(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun TimelineCard(
    state: MainUiState,
    onRandomize: () -> Unit,
    onSeek: (Long) -> Unit,
) {
    val duration = state.playback.durationMs
    val position = state.playback.currentPositionMs
    var isSeeking by remember { mutableStateOf(false) }
    var sliderValue by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(position, duration, isSeeking) {
        if (!isSeeking) sliderValue = if (duration > 0L) position.toFloat() / duration else 0f
    }
    val displayedPosition = if (isSeeking) (sliderValue * duration).toLong() else position
    SectionCard(
        title = "Virtual timeline",
        section = AdvancedSection.VIRTUAL_TIMELINE,
    ) {
        Text(
            text = if (duration > 0L) {
                "${VirtualTimeline.format(displayedPosition)} / ${VirtualTimeline.format(duration)}"
            } else {
                "--:--:-- / --:--:--"
            },
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(10.dp))
        Slider(
            value = sliderValue,
            onValueChange = {
                isSeeking = true
                sliderValue = it
            },
            onValueChangeFinished = {
                if (duration > 0L) {
                    onSeek((sliderValue * duration).toLong().coerceIn(0L, duration - 1L))
                }
                isSeeking = false
            },
            enabled = duration > 0L,
            valueRange = 0f..1f,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = onRandomize,
            enabled = state.playback.hasVideo,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Jump to a random point")
        }
    }
}

@Composable
private fun PlaybackActions(
    state: MainUiState,
    onTogglePlayback: () -> Unit,
) {
    val active = state.playback.status in setOf(
        PlaybackStatus.STARTING,
        PlaybackStatus.RENDERING,
        PlaybackStatus.SUSPENDED,
    )
    val paused = state.playback.status == PlaybackStatus.PAUSED ||
        !state.playback.timelineRunning
    val serviceLabel = if (active) "Started" else "Stopped"
    val rendererLabel = when (state.playback.status) {
        PlaybackStatus.STARTING -> "Starting"
        PlaybackStatus.RENDERING -> "Rendering"
        PlaybackStatus.SUSPENDED -> "Sleeping"
        PlaybackStatus.ERROR -> "Error"
        PlaybackStatus.IDLE,
        PlaybackStatus.PAUSED,
        -> "Stopped"
    }
    val audioLabel = when {
        !state.playback.audioEnabled -> "Muted"
        state.playback.inputDiagnostics.audioTrackCount <= 0 -> "Unavailable"
        else -> state.playback.runtimeDiagnostics.audioState.label
    }
    val timelineLabel = if (state.playback.timelineRunning) "Advancing" else "Paused"
    val buttonLabel = when {
        paused ->
            "Resume playback"
        active -> "Pause playback"
        else -> "Start playback"
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = when (state.playback.status) {
                PlaybackStatus.ERROR -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.secondaryContainer
            },
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Playback status",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(10.dp))
            DiagnosticRow("Playback service", serviceLabel)
            DiagnosticRow("Video renderer / decoder", rendererLabel)
            DiagnosticRow("Audio", audioLabel)
            DiagnosticRow("Virtual timeline", timelineLabel)
            Spacer(Modifier.height(8.dp))
            Text(
                text = state.playback.statusDetail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onTogglePlayback,
                enabled = state.playback.hasVideo && state.hasWidget,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(buttonLabel) }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    section: AdvancedSection? = null,
    content: @Composable () -> Unit,
) {
    val collapsible = section != null
    val appContext = LocalContext.current.applicationContext
    val uiPreferences = remember(appContext) { AppUiPreferences(appContext) }
    var expanded by rememberSaveable(section?.preferenceKey, title) {
        mutableStateOf(section?.let(uiPreferences::isSectionExpanded) ?: true)
    }
    val headerModifier = if (collapsible) {
        Modifier
            .clickable(role = Role.Button) {
                val newExpanded = !expanded
                expanded = newExpanded
                section?.let { uiPreferences.setSectionExpanded(it, newExpanded) }
            }
            .semantics {
                stateDescription = if (expanded) "Expanded" else "Collapsed"
            }
    } else {
        Modifier
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(headerModifier)
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (collapsible) {
                    ExpandChevron(expanded = expanded)
                }
            }
            if (collapsible) {
                AnimatedVisibility(
                    visible = expanded,
                    enter = expandVertically(
                        animationSpec = tween(durationMillis = SECTION_EXPAND_DURATION_MS),
                        expandFrom = Alignment.Top,
                    ) + fadeIn(animationSpec = tween(durationMillis = SECTION_FADE_DURATION_MS)),
                    exit = shrinkVertically(
                        animationSpec = tween(durationMillis = SECTION_COLLAPSE_DURATION_MS),
                        shrinkTowards = Alignment.Top,
                    ) + fadeOut(animationSpec = tween(durationMillis = SECTION_FADE_DURATION_MS)),
                ) {
                    Column(
                        modifier = Modifier.padding(
                            start = 16.dp,
                            end = 16.dp,
                            bottom = 16.dp,
                        ),
                    ) {
                        content()
                    }
                }
            } else {
                Column(
                    modifier = Modifier.padding(
                        start = 16.dp,
                        end = 16.dp,
                        bottom = 16.dp,
                    ),
                ) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun ExpandChevron(expanded: Boolean) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(durationMillis = SECTION_CHEVRON_DURATION_MS),
        label = "Section chevron rotation",
    )
    val color = MaterialTheme.colorScheme.onSurfaceVariant

    Canvas(
        modifier = Modifier
            .size(24.dp)
            .rotate(rotation),
    ) {
        val strokeWidth = 2.dp.toPx()
        val center = Offset(size.width / 2f, size.height * 0.62f)
        drawLine(
            color = color,
            start = Offset(size.width * 0.24f, size.height * 0.38f),
            end = center,
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = center,
            end = Offset(size.width * 0.76f, size.height * 0.38f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
    }
}

private const val SECTION_EXPAND_DURATION_MS = 260
private const val SECTION_COLLAPSE_DURATION_MS = 220
private const val SECTION_FADE_DURATION_MS = 160
private const val SECTION_CHEVRON_DURATION_MS = 220

private const val MAX_CROP_INPUT = 99_999
private const val MAX_CROP_DIGITS = 5
private const val MAX_TRIM_TIME_LENGTH = 12

private fun formatDecimal(value: Float): String = String.format(Locale.US, "%.2f", value)

private fun formatFrameRate(value: Float): String = String.format(Locale.US, "%.3f", value)

private fun formatBitrate(bitsPerSecond: Int): String = when {
    bitsPerSecond <= 0 -> "Unknown"
    bitsPerSecond >= 1_000_000 -> "${formatDecimal(bitsPerSecond / 1_000_000f)} Mbps"
    else -> "${(bitsPerSecond / 1_000f).toInt()} kbps"
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 0L -> "Unknown"
    bytes >= 1_000_000_000L -> "${formatDecimal(bytes / 1_000_000_000f)} GB"
    bytes >= 1_000_000L -> "${formatDecimal(bytes / 1_000_000f)} MB"
    bytes >= 1_000L -> "${formatDecimal(bytes / 1_000f)} KB"
    else -> "$bytes B"
}
