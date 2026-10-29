package com.bludood.homeloop

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bludood.homeloop.data.AppSettings
import com.bludood.homeloop.data.AppUiPreferences
import com.bludood.homeloop.ui.VideoWidgetScreen
import com.bludood.homeloop.ui.theme.HomeLoopTheme

class MainActivity : ComponentActivity() {
    private lateinit var uiPreferences: AppUiPreferences
    private var settings by mutableStateOf(AppSettings())
    private var openPlayerRequests by mutableIntStateOf(0)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeOpenPlayer(intent)
    }

    private fun consumeOpenPlayer(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_PLAYER, false) != true) return
        intent.removeExtra(EXTRA_OPEN_PLAYER)
        openPlayerRequests++
    }

    override fun onResume() {
        super.onResume()
        com.bludood.homeloop.playback.PlaybackController.ensureRunning(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        uiPreferences = AppUiPreferences(this)
        settings = uiPreferences.settings()
        if (savedInstanceState == null) consumeOpenPlayer(intent)
        setContent {
            HomeLoopTheme(settings.theme, settings.dynamicColor) {
                val platformHaptics = LocalHapticFeedback.current
                CompositionLocalProvider(
                    LocalHapticFeedback provides if (settings.hapticFeedback) platformHaptics else SilentHaptics,
                ) {
                    val viewModel: MainViewModel = viewModel()
                    val state by viewModel.uiState.collectAsStateWithLifecycle()
                    val videoPicker = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.OpenDocument(),
                        onResult = { uri -> uri?.let(viewModel::selectVideo) },
                    )
                    val notificationPermission = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.RequestPermission(),
                        onResult = { viewModel.togglePlayback() },
                    )

                    VideoWidgetScreen(
                        state = state,
                        onChooseVideo = { videoPicker.launch(arrayOf("video/*")) },
                        onAddWidget = viewModel::requestPinWidget,
                        onFpsSelected = viewModel::setFps,
                        onResolutionSelected = viewModel::setResolution,
                        onCropChanged = viewModel::setCropInsets,
                        loadPreviewFrame = viewModel::previewFrame,
                        loadFrameAt = viewModel::frameAt,
                        detectBlackBars = viewModel::detectBlackBars,
                        settings = settings,
                        onSettingsChanged = { updated ->
                            if (updated.hapticFeedback && !settings.hapticFeedback) {
                                platformHaptics.performHapticFeedback(HapticFeedbackType.ToggleOn)
                            }
                            settings = updated
                            uiPreferences.setSettings(updated)
                        },
                        onTrimChanged = viewModel::setTrimRange,
                        onScalingFilterChanged = viewModel::setScalingFilter,
                        onAudioChanged = viewModel::setAudioEnabled,
                        onRenderingPolicySelected = viewModel::setRenderingPolicy,
                        onHiddenAudioPolicySelected = viewModel::setHiddenAudioPolicy,
                        onOpenUsageAccess = ::openUsageAccessSettings,
                        onOpenBatterySettings = ::openBatterySettings,
                        onWidgetTapActionSelected = viewModel::setWidgetTapAction,
                        onRandomize = viewModel::randomizePosition,
                        onSeek = viewModel::seekTo,
                        onOptimize = viewModel::optimizeCurrentSetup,
                        onCancelOptimization = viewModel::cancelOptimization,
                        onDeleteOptimizedCopy = viewModel::deleteOptimizedCopy,
                        onTogglePlayback = {
                            val pausing = state.playback.timelineRunning && state.playback.status.isActive
                            if (
                                !pausing &&
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                ContextCompat.checkSelfPermission(
                                    this,
                                    Manifest.permission.POST_NOTIFICATIONS,
                                ) != PackageManager.PERMISSION_GRANTED
                            ) {
                                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                viewModel.togglePlayback()
                            }
                        },
                        onMessageShown = viewModel::clearMessage,
                        openPlayerRequests = openPlayerRequests,
                    )
                }
            }
        }
    }

    private fun openBatterySettings() {
        @Suppress("BatteryLife")
        val request = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            "package:$packageName".toUri()
        )
        runCatching { startActivity(request) }
            .recoverCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            .onFailure {
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        "package:$packageName".toUri()
                    )
                )
            }
    }

    private fun openUsageAccessSettings() {
        val appPage = Intent(
            Settings.ACTION_USAGE_ACCESS_SETTINGS,
            "package:$packageName".toUri(),
        )
        runCatching { startActivity(appPage) }.onFailure {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
    }

    companion object {
        const val EXTRA_OPEN_PLAYER = "open_player"
    }
}

private val SilentHaptics = object : HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) = Unit
}
