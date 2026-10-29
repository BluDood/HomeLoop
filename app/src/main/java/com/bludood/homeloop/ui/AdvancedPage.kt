package com.bludood.homeloop.ui

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.bludood.homeloop.MainUiState
import com.bludood.homeloop.R
import com.bludood.homeloop.data.AppSettings
import com.bludood.homeloop.data.FrameRateOption
import com.bludood.homeloop.data.HiddenAudioPolicy
import com.bludood.homeloop.data.OptimizationStatus
import com.bludood.homeloop.data.PlaybackOptions
import com.bludood.homeloop.data.RenderingPolicy
import com.bludood.homeloop.data.ResolutionOption
import com.bludood.homeloop.data.ScalingDirection
import com.bludood.homeloop.data.ScalingFilter
import com.bludood.homeloop.data.ThemeChoice
import com.bludood.homeloop.ui.theme.Space

@Composable
internal fun AdvancedPage(
    state: MainUiState,
    settings: AppSettings,
    snackbarHostState: SnackbarHostState,
    onUp: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onSettingsChanged: (AppSettings) -> Unit,
    onAddWidget: () -> Unit,
    onFpsSelected: (FrameRateOption) -> Unit,
    onResolutionSelected: (ResolutionOption) -> Unit,
    onScalingFilterChanged: (ScalingDirection, ScalingFilter) -> Unit,
    onRenderingPolicySelected: (RenderingPolicy) -> Unit,
    onHiddenAudioPolicySelected: (HiddenAudioPolicy) -> Unit,
    onOpenUsageAccess: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onRandomize: () -> Unit,
    onOptimize: () -> Unit,
    onCancelOptimization: () -> Unit,
    onDeleteOptimizedCopy: () -> Unit,
) {
    SubPage(title = "Advanced", snackbarHostState = snackbarHostState, onUp = onUp) {
        QualitySection(state, onFpsSelected, onResolutionSelected, onScalingFilterChanged)
        BackgroundSection(
            state = state,
            onRenderingPolicySelected = onRenderingPolicySelected,
            onHiddenAudioPolicySelected = onHiddenAudioPolicySelected,
            onOpenUsageAccess = onOpenUsageAccess,
            onOpenBatterySettings = onOpenBatterySettings,
        )
        OptimizedCopySection(state, onOptimize, onCancelOptimization, onDeleteOptimizedCopy)
        MoreSection(state, onAddWidget, onRandomize, onOpenDiagnostics)
        SettingsSection("Appearance") {
            val dynamicSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            ChoiceSetting(
                icon = R.drawable.ms_brightness_6_filled_24,
                title = "Theme",
                labels = ThemeChoice.entries.map { it.label },
                selectedIndex = settings.theme.ordinal,
                onSelected = { onSettingsChanged(settings.copy(theme = ThemeChoice.entries[it])) },
                index = 0,
                count = 2,
            )
            SettingsSwitchRow(
                icon = R.drawable.ms_palette_filled_24,
                title = "Dynamic colors",
                supporting = if (dynamicSupported) "Use colors from your wallpaper"
                else "Requires Android 12 or later",
                checked = settings.dynamicColor && dynamicSupported,
                onChecked = { onSettingsChanged(settings.copy(dynamicColor = it)) },
                index = 1,
                count = 2,
                enabled = dynamicSupported,
            )
        }
        SettingsSection("Preferences") {
            SettingsSwitchRow(
                icon = R.drawable.ms_touch_app_filled_24,
                title = "Haptic feedback",
                supporting = "Vibrate for touches and selections",
                checked = settings.hapticFeedback,
                onChecked = { onSettingsChanged(settings.copy(hapticFeedback = it)) },
                toggleFeedback = false,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SubPage(
    title: String,
    snackbarHostState: SnackbarHostState,
    onUp: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { NavigateUpButton(onUp) })
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { contentPadding -> PageColumn(contentPadding, content) }
}

@Composable
internal fun PageColumn(contentPadding: PaddingValues, content: @Composable ColumnScope.() -> Unit) {
    val pageScroll = rememberScrollState()
    Box(
        Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .consumeWindowInsets(contentPadding),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = Space.contentMaxWidth)
                .fillMaxWidth()
                .verticalScroll(pageScroll, enabled = pageScroll.maxValue > 0)
                .padding(horizontal = Space.screen, vertical = Space.lg),
            verticalArrangement = Arrangement.spacedBy(Space.xxl),
            content = content,
        )
    }
}

@Composable
private fun QualitySection(
    state: MainUiState,
    onFpsSelected: (FrameRateOption) -> Unit,
    onResolutionSelected: (ResolutionOption) -> Unit,
    onScalingFilterChanged: (ScalingDirection, ScalingFilter) -> Unit,
) {
    val playback = state.playback
    val frameRates = PlaybackOptions.frameRates
    val resolutions = PlaybackOptions.resolutions
    val outputFps = if (playback.sourceFrameRate > 0f) {
        "${formatFrameRate(playback.sourceFrameRate / playback.frameRate.sourceDivisor)} FPS"
    } else {
        null
    }
    val outputSize = state.outputSize.takeIf { it.width > 0 && it.height > 0 }?.label
    SettingsSection("Quality") {
        ChoiceSetting(
            icon = R.drawable.ms_high_quality_filled_24,
            title = "Resolution",
            labels = resolutions.map { it.label },
            selectedIndex = resolutions.indexOf(playback.resolution).coerceAtLeast(0),
            onSelected = { onResolutionSelected(resolutions[it]) },
            index = 0,
            count = 5,
        )
        ChoiceSetting(
            icon = R.drawable.ms_slow_motion_video_24,
            title = "Frame rate",
            labels = frameRates.map { if (it.sourceDivisor == 1) "Video" else it.label },
            selectedIndex = frameRates.indexOf(playback.frameRate).coerceAtLeast(0),
            onSelected = { onFpsSelected(frameRates[it]) },
            index = 1,
            count = 5,
        )
        InfoRow(
            title = "Widget output",
            value = listOfNotNull(outputSize, outputFps).joinToString(" · ")
                .ifBlank { "Add the widget to calculate its size" },
            icon = R.drawable.ms_info_filled_24,
            index = 2,
            count = 5,
        )
        ChoiceDialogRow(
            icon = R.drawable.ms_aspect_ratio_filled_24,
            title = "Upscaling filter",
            options = ScalingFilter.entries,
            selected = playback.upscaleFilter,
            optionLabel = { it.label },
            optionDescription = { it.description },
            onSelected = { onScalingFilterChanged(ScalingDirection.UPSCALE, it) },
            index = 3,
            count = 5,
        )
        ChoiceDialogRow(
            icon = R.drawable.ms_aspect_ratio_filled_24,
            title = "Downscaling filter",
            options = ScalingFilter.entries,
            selected = playback.downscaleFilter,
            optionLabel = { it.label },
            optionDescription = { it.description },
            onSelected = { onScalingFilterChanged(ScalingDirection.DOWNSCALE, it) },
            index = 4,
            count = 5,
        )
    }
}

@Composable
private fun BackgroundSection(
    state: MainUiState,
    onRenderingPolicySelected: (RenderingPolicy) -> Unit,
    onHiddenAudioPolicySelected: (HiddenAudioPolicy) -> Unit,
    onOpenUsageAccess: () -> Unit,
    onOpenBatterySettings: () -> Unit,
) {
    val playback = state.playback
    val homeOnly = playback.renderingPolicy == RenderingPolicy.HOME_SCREEN_ONLY
    val needsUsageAccess = homeOnly && !state.usageAccessGranted
    val count = if (needsUsageAccess) 4 else 3
    var index = 0
    SettingsSection("Background") {
        SettingsSwitchRow(
            icon = R.drawable.ms_home_filled_24,
            title = "Only play on Home",
            supporting = if (homeOnly) "Video rests while other apps are open"
            else "Video plays whenever the screen is on",
            checked = homeOnly,
            onChecked = {
                onRenderingPolicySelected(if (it) RenderingPolicy.HOME_SCREEN_ONLY else RenderingPolicy.SCREEN_ON)
            },
            index = index++,
            count = count,
        )
        if (needsUsageAccess) {
            SettingsActionRow(
                icon = R.drawable.ms_info_filled_24,
                title = "Allow usage access",
                supporting = "Needed to tell when Home is open",
                onClick = onOpenUsageAccess,
                index = index++,
                count = count,
            )
        }
        SettingsSwitchRow(
            icon = R.drawable.ms_volume_up_filled_24,
            title = "Sound in other apps",
            supporting = when {
                !playback.audioEnabled -> "Turn on Sound on Home first"
                !homeOnly -> "Sound always follows the video"
                playback.hiddenAudioPolicy == HiddenAudioPolicy.CONTINUE -> "Keeps playing; uses more battery"
                else -> "Pauses with the video"
            },
            checked = playback.hiddenAudioPolicy == HiddenAudioPolicy.CONTINUE,
            onChecked = {
                onHiddenAudioPolicySelected(if (it) HiddenAudioPolicy.CONTINUE else HiddenAudioPolicy.SUSPEND)
            },
            enabled = playback.audioEnabled && homeOnly,
            index = index++,
            count = count,
        )
        SettingsActionRow(
            icon = R.drawable.ms_battery_android_full_filled_24,
            title = "Resume after restart",
            supporting = if (state.backgroundStartAllowed) "Allowed · resumes on its own"
            else "Not allowed · tap the widget to resume",
            onClick = onOpenBatterySettings,
            index = index,
            count = count,
        )
    }
}

@Composable
private fun OptimizedCopySection(
    state: MainUiState,
    onOptimize: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    val optimization = state.optimization
    val profile = optimization.profile
    val canOptimize = state.playback.hasVideo && state.hasWidget && profile != null
    val hasCopy =
        optimization.outputPath != null && optimization.status != OptimizationStatus.RUNNING
    val count = (if (profile != null) 2 else 0) + 1 + (if (hasCopy) 1 else 0)
    var index = 0
    SettingsSection("Optimized copy") {
        if (profile != null) {
            InfoRow("Target", profile.label, index = index++, count = count)
            InfoRow(
                "Estimated size",
                "${formatBytes(profile.estimatedBytes)} · ${formatBitrate(profile.videoBitrate)}",
                index = index++,
                count = count,
            )
        }
        if (optimization.status == OptimizationStatus.RUNNING || optimization.isUsable) {
            OptimizationRow(state, onOptimize, onCancel, index = index++, count = count)
        } else {
            SettingsActionRow(
                icon = R.drawable.ms_speed_filled_24,
                title = if (optimization.outputPath == null) "Optimize" else "Optimize again",
                supporting = if (canOptimize) state.optimizationRecommendation.summary
                else "Choose a video and add the widget first",
                onClick = onOptimize,
                enabled = canOptimize,
                index = index++,
                count = count,
            )
        }
        if (hasCopy) {
            SettingsActionRow(
                icon = R.drawable.ms_delete_24,
                title = "Delete optimized copy",
                supporting = "Frees ${formatBytes(optimization.outputSizeBytes)}; the original stays",
                onClick = onDelete,
                index = index,
                count = count,
                trailing = null,
            )
        }
    }
}

@Composable
private fun MoreSection(
    state: MainUiState,
    onAddWidget: () -> Unit,
    onRandomize: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    SettingsSection("More") {
        SettingsActionRow(
            icon = R.drawable.ms_widgets_filled_24,
            title = "Add another widget",
            supporting = when (state.widgetCount) {
                0 -> "No widgets on your home screen"
                1 -> "1 widget on your home screen"
                else -> "${state.widgetCount} widgets on your home screen"
            },
            onClick = onAddWidget,
            index = 0,
            count = 3,
            trailing = null,
        )
        SettingsActionRow(
            icon = R.drawable.ms_shuffle_24,
            title = "Jump to a random point",
            supporting = "Start the loop somewhere new",
            onClick = onRandomize,
            enabled = state.playback.hasVideo,
            index = 1,
            count = 3,
            trailing = null,
        )
        SettingsActionRow(
            icon = R.drawable.ms_monitoring_24,
            title = "Diagnostics",
            supporting = "Playback, decoder and video file details",
            onClick = onOpenDiagnostics,
            index = 2,
            count = 3,
        )
    }
}
