package com.bludood.homeloop.ui

import androidx.activity.compose.LocalActivity
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.bludood.homeloop.MainUiState
import com.bludood.homeloop.R
import com.bludood.homeloop.data.OptimizationStatus
import com.bludood.homeloop.data.PlaybackStatus
import com.bludood.homeloop.ui.theme.Space
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PlayerPage(
    state: MainUiState,
    inAppPlayer: InAppPlayer?,
    onUp: () -> Unit,
    onTogglePlayback: () -> Unit,
    onSeek: (Long) -> Unit,
) {
    val playback = state.playback
    val duration = playback.durationMs
    val playing = playback.timelineRunning && playback.status != PlaybackStatus.ERROR
    var controlsVisible by rememberSaveable { mutableStateOf(true) }
    var interactions by remember { mutableIntStateOf(0) }
    val touched: () -> Unit = { interactions++ }

    ImmersiveSystemBars()
    DisposableEffect(inAppPlayer) {
        inAppPlayer?.ownAudio(true)
        inAppPlayer?.updateSound(true)
        onDispose {
            inAppPlayer?.updateSound(false)
            inAppPlayer?.ownAudio(false)
        }
    }
    LaunchedEffect(controlsVisible, playing, interactions) {
        if (controlsVisible && playing) {
            delay(CONTROLS_TIMEOUT_MS)
            controlsVisible = false
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                controlsVisible = !controlsVisible
            },
    ) {
        inAppPlayer?.let {
            CroppedVideo(
                inAppPlayer = it,
                videoWidth = playback.videoWidth,
                videoHeight = playback.videoHeight,
                crop = playback.cropInsets,
                fill = false,
                modifier = Modifier.fillMaxSize(),
            )
        }
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()),
            exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
        ) {
            CompositionLocalProvider(LocalContentColor provides Color.White) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = CONTROLS_SCRIM_ALPHA))
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .safeDrawingPadding()
                            .padding(Space.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onUp, shapes = IconButtonDefaults.shapes()) {
                            Icon(painterResource(R.drawable.ms_arrow_back_24), "Navigate up")
                        }
                        Text(
                            playback.videoName,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(
                        Modifier.align(Alignment.Center),
                        horizontalArrangement = Arrangement.spacedBy(Space.xl),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val skip: (Long) -> Unit = { delta ->
                            touched()
                            onSeek(Math.floorMod(playback.currentPositionMs + delta, duration))
                        }
                        SkipButton(R.drawable.ms_replay_10_24, "Back 10 seconds", duration > 0L) {
                            skip(-SKIP_MS)
                        }
                        FilledIconButton(
                            onClick = { touched(); onTogglePlayback() },
                            enabled = playback.hasVideo && (state.hasWidget || playing) &&
                                    state.optimization.status != OptimizationStatus.RUNNING,
                            shapes = IconButtonDefaults.shapes(),
                            modifier = Modifier.size(IconButtonDefaults.largeContainerSize()),
                        ) {
                            Icon(
                                painterResource(if (playing) R.drawable.ms_pause_24 else R.drawable.ms_play_arrow_24),
                                if (playing) "Pause" else "Play",
                                Modifier.size(IconButtonDefaults.largeIconSize),
                            )
                        }
                        SkipButton(R.drawable.ms_forward_10_24, "Forward 10 seconds", duration > 0L) {
                            skip(SKIP_MS)
                        }
                    }
                    Column(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .safeDrawingPadding()
                            .padding(horizontal = Space.lg, vertical = Space.sm),
                    ) {
                        PlayerTimeline(
                            positionMs = playback.currentPositionMs,
                            durationMs = duration,
                            onSeeking = touched,
                            onSeek = onSeek,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${formatClock(playback.currentPositionMs)} / ${formatClock(duration)}",
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.weight(1f),
                            )
                            val soundOn = inAppPlayer?.soundOn == true
                            IconButton(
                                onClick = { touched(); inAppPlayer?.updateSound(!soundOn) },
                                enabled = inAppPlayer != null,
                                shapes = IconButtonDefaults.shapes(),
                            ) {
                                Icon(
                                    painterResource(if (soundOn) R.drawable.ms_volume_up_24 else R.drawable.ms_volume_off_24),
                                    if (soundOn) "Mute" else "Unmute",
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerTimeline(
    positionMs: Long,
    durationMs: Long,
    onSeeking: () -> Unit,
    onSeek: (Long) -> Unit
) {
    var seekingTo by remember { mutableStateOf<Float?>(null) }
    val fraction = seekingTo
        ?: if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Slider(
        value = fraction,
        onValueChange = { onSeeking(); seekingTo = it },
        onValueChangeFinished = {
            seekingTo?.let { onSeek((it * durationMs).toLong().coerceIn(0L, durationMs - 1L)) }
            seekingTo = null
        },
        enabled = durationMs > 0L,
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 900.dp)
            .semantics { contentDescription = "Loop position" },
    )
}

@Composable
private fun ImmersiveSystemBars() {
    val activity = LocalActivity.current
    val view = LocalView.current
    DisposableEffect(activity) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SkipButton(@DrawableRes icon: Int, label: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        shapes = IconButtonDefaults.shapes(),
        modifier = Modifier.size(IconButtonDefaults.mediumContainerSize()),
    ) { Icon(painterResource(icon), label) }
}

private const val SKIP_MS = 10_000L
private const val CONTROLS_TIMEOUT_MS = 3_000L
private const val CONTROLS_SCRIM_ALPHA = 0.45f
