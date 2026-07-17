package com.bludood.homeloop

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bludood.homeloop.ui.VideoWidgetScreen
import com.bludood.homeloop.ui.theme.HomeLoopTheme
import com.bludood.homeloop.data.PlaybackStatus

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HomeLoopTheme {
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
                    onCropChanged = viewModel::setCrop,
                    onTrimChanged = viewModel::setTrimRange,
                    onScalingFilterChanged = viewModel::setScalingFilter,
                    onAudioChanged = viewModel::setAudioEnabled,
                    onRenderingPolicySelected = viewModel::setRenderingPolicy,
                    onHiddenAudioPolicySelected = viewModel::setHiddenAudioPolicy,
                    onOpenUsageAccess = ::openUsageAccessSettings,
                    onWidgetTapActionSelected = viewModel::setWidgetTapAction,
                    onRandomize = viewModel::randomizePosition,
                    onSeek = viewModel::seekTo,
                    onOptimize = viewModel::optimizeCurrentSetup,
                    onCancelOptimization = viewModel::cancelOptimization,
                    onDeleteOptimizedCopy = viewModel::deleteOptimizedCopy,
                    onTogglePlayback = {
                        val pausing = state.playback.timelineRunning &&
                            state.playback.status in setOf(
                                PlaybackStatus.STARTING,
                                PlaybackStatus.RENDERING,
                                PlaybackStatus.SUSPENDED,
                            )
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
                )
            }
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
}
