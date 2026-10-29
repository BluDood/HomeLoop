package com.bludood.homeloop.ui

import android.graphics.Bitmap
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import com.bludood.homeloop.MainUiState
import com.bludood.homeloop.data.AppSettings
import com.bludood.homeloop.data.CropInsets
import com.bludood.homeloop.data.FrameRateOption
import com.bludood.homeloop.data.HiddenAudioPolicy
import com.bludood.homeloop.data.RenderingPolicy
import com.bludood.homeloop.data.ResolutionOption
import com.bludood.homeloop.data.ScalingDirection
import com.bludood.homeloop.data.ScalingFilter
import com.bludood.homeloop.data.WidgetTapAction

private enum class Page { Home, Advanced, Diagnostics, Player }

@Composable
fun VideoWidgetScreen(
    state: MainUiState,
    onChooseVideo: () -> Unit,
    onAddWidget: () -> Unit,
    onFpsSelected: (FrameRateOption) -> Unit,
    onResolutionSelected: (ResolutionOption) -> Unit,
    onCropChanged: (CropInsets) -> Unit,
    loadPreviewFrame: suspend (Int) -> Bitmap?,
    loadFrameAt: suspend (Long, Int) -> Bitmap?,
    detectBlackBars: suspend () -> CropInsets?,
    settings: AppSettings,
    onSettingsChanged: (AppSettings) -> Unit,
    onTrimChanged: (Long, Long) -> Unit,
    onScalingFilterChanged: (ScalingDirection, ScalingFilter) -> Unit,
    onAudioChanged: (Boolean) -> Unit,
    onRenderingPolicySelected: (RenderingPolicy) -> Unit,
    onHiddenAudioPolicySelected: (HiddenAudioPolicy) -> Unit,
    onOpenUsageAccess: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onWidgetTapActionSelected: (WidgetTapAction) -> Unit,
    onRandomize: () -> Unit,
    onSeek: (Long) -> Unit,
    onOptimize: () -> Unit,
    onCancelOptimization: () -> Unit,
    onDeleteOptimizedCopy: () -> Unit,
    onTogglePlayback: () -> Unit,
    onMessageShown: () -> Unit,
    openPlayerRequests: Int = 0,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val currentState by rememberUpdatedState(state)
    val currentSettings by rememberUpdatedState(settings)
    val inAppPlayer = rememberInAppPlayer()
    LaunchedEffect(inAppPlayer, state.playback) { inAppPlayer?.follow(state.playback) }
    val currentPlayer by rememberUpdatedState(inAppPlayer)
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            onMessageShown()
        }
    }
    val stack = rememberSaveable(
        saver = listSaver(save = { pages -> pages.map { it.name } }, restore = { names ->
            names.map(Page::valueOf).toMutableStateList()
        }),
    ) { mutableStateListOf(Page.Home) }
    var handledPlayerRequests by remember { mutableIntStateOf(0) }
    LaunchedEffect(openPlayerRequests, state.playback.hasVideo) {
        if (openPlayerRequests > handledPlayerRequests && state.playback.hasVideo) {
            handledPlayerRequests = openPlayerRequests
            if (stack.lastOrNull() != Page.Player) {
                stack.retainAll { it == Page.Home }
                stack.add(Page.Player)
            }
        }
    }
    val savedPages = rememberSaveableStateHolder()
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Ltr) 1 else -1
    val axisDistance = with(LocalDensity.current) { 30.dp.roundToPx() }

    NavDisplay(
        backStack = stack,
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .clipToBounds(),
        entryDecorators = emptyList(),
        onBack = { if (stack.size > 1) stack.removeLastOrNull() },
        transitionSpec = { sharedAxis(forward = true, direction, axisDistance) },
        popTransitionSpec = { sharedAxis(forward = false, direction, axisDistance) },
        predictivePopTransitionSpec = { sharedAxis(forward = false, direction, axisDistance) },
        entryProvider = { page ->
            NavEntry(page) {
                savedPages.SaveableStateProvider(page.name) {
                    when (page) {
                        Page.Home -> HomePage(
                            state = currentState,
                            inAppPlayer = currentPlayer,
                            onOpenPlayer = { if (stack.lastOrNull() == Page.Home) stack.add(Page.Player) },
                            snackbarHostState = snackbarHostState,
                            onOpenAdvanced = { if (stack.lastOrNull() == Page.Home) stack.add(Page.Advanced) },
                            onChooseVideo = onChooseVideo,
                            onAddWidget = onAddWidget,
                            onAudioChanged = onAudioChanged,
                            onWidgetTapActionSelected = onWidgetTapActionSelected,
                            onOpenBatterySettings = onOpenBatterySettings,
                            onOpenUsageAccess = onOpenUsageAccess,
                            onOptimize = onOptimize,
                            onCancelOptimization = onCancelOptimization,
                            onTogglePlayback = onTogglePlayback,
                            onSeek = onSeek,
                            onTrimChanged = onTrimChanged,
                            onCropChanged = onCropChanged,
                            loadPreviewFrame = loadPreviewFrame,
                            loadFrameAt = loadFrameAt,
                            detectBlackBars = detectBlackBars,
                        )

                        Page.Advanced -> AdvancedPage(
                            state = currentState,
                            settings = currentSettings,
                            snackbarHostState = snackbarHostState,
                            onUp = { if (stack.lastOrNull() == Page.Advanced) stack.removeLastOrNull() },
                            onOpenDiagnostics = {
                                if (stack.lastOrNull() == Page.Advanced) stack.add(Page.Diagnostics)
                            },
                            onSettingsChanged = onSettingsChanged,
                            onAddWidget = onAddWidget,
                            onFpsSelected = onFpsSelected,
                            onResolutionSelected = onResolutionSelected,
                            onScalingFilterChanged = onScalingFilterChanged,
                            onRenderingPolicySelected = onRenderingPolicySelected,
                            onHiddenAudioPolicySelected = onHiddenAudioPolicySelected,
                            onOpenUsageAccess = onOpenUsageAccess,
                            onOpenBatterySettings = onOpenBatterySettings,
                            onRandomize = onRandomize,
                            onOptimize = onOptimize,
                            onCancelOptimization = onCancelOptimization,
                            onDeleteOptimizedCopy = onDeleteOptimizedCopy,
                        )

                        Page.Player -> PlayerPage(
                            state = currentState,
                            inAppPlayer = currentPlayer,
                            onUp = { if (stack.lastOrNull() == Page.Player) stack.removeLastOrNull() },
                            onTogglePlayback = onTogglePlayback,
                            onSeek = onSeek,
                        )

                        Page.Diagnostics -> DiagnosticsPage(
                            state = currentState,
                            snackbarHostState = snackbarHostState,
                            onUp = { if (stack.lastOrNull() == Page.Diagnostics) stack.removeLastOrNull() },
                        )
                    }
                }
            }
        },
    )
}

private fun sharedAxis(forward: Boolean, direction: Int, axisDistance: Int): ContentTransform {
    val travel = if (forward) direction else -direction
    val motion = tween<IntOffset>(300, easing = FastOutSlowInEasing)
    return (slideInHorizontally(motion) { axisDistance * travel } + fadeIn(
        tween(
            300,
            easing = stagedInEasing
        )
    )) togetherWith
            (slideOutHorizontally(motion) { -axisDistance * travel } + fadeOut(
                tween(
                    300,
                    easing = stagedOutEasing
                )
            ))
}

private val stagedOutEasing = Easing { t -> (t / 0.4f).coerceAtMost(1f) }
private val stagedInEasing = Easing { t ->
    if (t <= 0.25f) 0f else LinearOutSlowInEasing.transform((t - 0.25f) / 0.75f)
}
