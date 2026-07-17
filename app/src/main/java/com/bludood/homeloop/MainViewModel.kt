package com.bludood.homeloop

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bludood.homeloop.data.PlaybackRepository
import com.bludood.homeloop.data.PlaybackSnapshot
import com.bludood.homeloop.data.PlaybackStatus
import com.bludood.homeloop.data.FrameRateOption
import com.bludood.homeloop.data.CropEdge
import com.bludood.homeloop.data.ScalingDirection
import com.bludood.homeloop.data.ScalingFilter
import com.bludood.homeloop.data.ResolutionOption
import com.bludood.homeloop.data.WidgetTapAction
import com.bludood.homeloop.data.HiddenAudioPolicy
import com.bludood.homeloop.data.RenderingPolicy
import com.bludood.homeloop.data.OptimizationSnapshot
import com.bludood.homeloop.data.OptimizationRecommendation
import com.bludood.homeloop.data.OutputSize
import com.bludood.homeloop.data.OptimizationStatus
import com.bludood.homeloop.playback.AutoResolutionResolver
import com.bludood.homeloop.playback.OptimizationPlanner
import com.bludood.homeloop.playback.OptimizationService
import com.bludood.homeloop.playback.PlaybackService
import com.bludood.homeloop.playback.LauncherVisibilityMonitor
import com.bludood.homeloop.widget.VideoWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MainUiState(
    val playback: PlaybackSnapshot,
    val widgetCount: Int,
    val outputSize: OutputSize,
    val optimization: OptimizationSnapshot,
    val optimizationRecommendation: OptimizationRecommendation,
    val usageAccessGranted: Boolean,
    val isImporting: Boolean = false,
    val message: String? = null,
) {
    val hasWidget: Boolean get() = widgetCount > 0
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = PlaybackRepository(application)
    private val widgetManager = AppWidgetManager.getInstance(application)
    private val widgetProvider = ComponentName(application, VideoWidgetProvider::class.java)
    private val _uiState = MutableStateFlow(buildState())
    val uiState = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.ensureVideoMetadata() }
            refresh()
        }
        viewModelScope.launch {
            while (isActive) {
                refresh()
                delay(500L)
            }
        }
    }

    fun selectVideo(uri: Uri) {
        _uiState.value = _uiState.value.copy(isImporting = true)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { repository.importVideo(uri) }
            _uiState.value = buildState().copy(
                isImporting = false,
                message = result.fold(
                    onSuccess = { "Selected $it" },
                    onFailure = { it.message ?: "The selected video could not be opened." },
                ),
            )
            VideoWidgetProvider.updateAll(
                getApplication(),
                if (result.isSuccess) "Ready — start playback in the app" else "Choose a video in the app",
            )
        }
    }

    fun setFps(frameRate: FrameRateOption) {
        repository.setFrameRate(frameRate)
        refresh()
        restartIfActive()
    }

    fun setResolution(resolution: ResolutionOption) {
        repository.setResolution(resolution)
        refresh()
        restartIfActive()
    }

    fun setScalingFilter(direction: ScalingDirection, filter: ScalingFilter) {
        repository.setScalingFilter(direction, filter)
        refresh()
        val playback = repository.snapshot()
        when {
            playback.status in ACTIVE_STATUSES -> restartIfActive()
            playback.status == PlaybackStatus.PAUSED || !playback.timelineRunning ->
                renderPausedFrame()
        }
    }

    fun setCrop(edge: CropEdge, pixels: Int) {
        val before = repository.snapshot()
        repository.setCrop(edge, pixels.coerceAtLeast(0))
        refresh()
        when {
            before.status in ACTIVE_STATUSES -> restartIfActive()
            before.status == PlaybackStatus.PAUSED || !before.timelineRunning ->
                renderPausedFrame()
        }
    }

    fun setTrimRange(startMs: Long, endMs: Long) {
        val before = repository.snapshot()
        repository.setTrimRange(startMs, endMs)
        refresh()
        when {
            before.status in ACTIVE_STATUSES -> restartIfActive()
            before.status == PlaybackStatus.PAUSED || !before.timelineRunning ->
                renderPausedFrame()
        }
    }

    fun setAudioEnabled(enabled: Boolean) {
        repository.setAudioEnabled(enabled)
        refresh()
        if (_uiState.value.playback.status in ACTIVE_STATUSES) {
            runCatching {
                getApplication<Application>().startService(
                    PlaybackService.intent(getApplication(), PlaybackService.Action.APPLY_AUDIO),
                )
            }
        }
    }

    fun setRenderingPolicy(policy: RenderingPolicy) {
        repository.setRenderingPolicy(policy)
        refresh()
        applyResourcePolicyIfActive()
    }

    fun setHiddenAudioPolicy(policy: HiddenAudioPolicy) {
        repository.setHiddenAudioPolicy(policy)
        refresh()
        applyResourcePolicyIfActive()
    }

    fun setWidgetTapAction(action: WidgetTapAction) {
        repository.setWidgetTapAction(action)
        refresh()
        VideoWidgetProvider.updateAll(getApplication(), _uiState.value.playback.statusDetail)
    }

    fun randomizePosition() {
        if (!repository.snapshot().hasVideo) {
            showMessage("Choose a video first.")
            return
        }
        repository.randomizePosition()
        refresh()
        restartIfActive()
    }

    fun seekTo(positionMs: Long) {
        val before = repository.snapshot()
        if (!before.hasVideo) return
        repository.setPosition(positionMs)
        refresh()
        when {
            before.status in ACTIVE_STATUSES -> restartIfActive()
            before.status == PlaybackStatus.PAUSED || !before.timelineRunning -> {
                renderPausedFrame()
            }
        }
    }

    private fun renderPausedFrame() {
        runCatching {
            ContextCompat.startForegroundService(
                getApplication(),
                PlaybackService.intent(
                    getApplication(),
                    PlaybackService.Action.RENDER_PAUSED_FRAME,
                ),
            )
        }
    }

    fun requestPinWidget() {
        if (!widgetManager.isRequestPinAppWidgetSupported) {
            showMessage("Open your launcher’s widget picker and add HomeLoop.")
            return
        }
        val sent = runCatching {
            widgetManager.requestPinAppWidget(widgetProvider, null, null)
        }.getOrDefault(false)
        showMessage(
            if (sent) "Confirm the widget placement on your home screen."
            else "Your launcher did not accept the widget request.",
        )
    }

    fun startRendering() {
        if (repository.isOptimizationRunning()) {
            showMessage("Playback will resume when optimization finishes.")
            return
        }
        val current = buildState()
        if (!current.playback.hasVideo) {
            showMessage("Choose a video first.")
            return
        }
        if (!current.hasWidget) {
            showMessage("Add the widget to your home screen first.")
            return
        }
        repository.resumeTimeline()
        repository.setStatus(PlaybackStatus.STARTING, "Starting decoder")
        val error = runCatching {
            ContextCompat.startForegroundService(
                getApplication(),
                PlaybackService.intent(getApplication(), PlaybackService.Action.START),
            )
        }.exceptionOrNull()
        if (error != null) {
            repository.setStatus(PlaybackStatus.ERROR, error.message ?: "Could not start playback")
            showMessage("Could not start playback: ${error.message ?: error.javaClass.simpleName}")
        }
        refresh()
    }

    fun togglePlayback() {
        if (repository.isOptimizationRunning()) {
            showMessage("Playback is paused while the optimized copy is being encoded.")
            return
        }
        val playback = repository.snapshot()
        when {
            playback.status in ACTIVE_STATUSES && playback.timelineRunning -> {
                repository.pauseTimeline()
                runCatching {
                    getApplication<Application>().startService(
                        PlaybackService.intent(getApplication(), PlaybackService.Action.PAUSE),
                    )
                }
                repository.setStatus(PlaybackStatus.PAUSED, "Playback paused")
                refresh()
            }

            !playback.timelineRunning || playback.status == PlaybackStatus.PAUSED -> {
                repository.resumeTimeline()
                startRendering()
            }

            else -> startRendering()
        }
    }

    fun optimizeCurrentSetup() {
        val current = buildState()
        if (!current.playback.hasVideo) {
            showMessage("Choose a video first.")
            return
        }
        if (!current.hasWidget) {
            showMessage("Place and resize the widget before optimizing.")
            return
        }
        if (current.optimization.status == OptimizationStatus.RUNNING) return
        val error = runCatching {
            ContextCompat.startForegroundService(
                getApplication(),
                OptimizationService.intent(getApplication()),
            )
        }.exceptionOrNull()
        if (error != null) showMessage("Could not start optimization: ${error.message}")
        refresh()
    }

    fun cancelOptimization() {
        runCatching {
            getApplication<Application>().startService(
                OptimizationService.intent(getApplication(), cancel = true),
            )
        }
        refresh()
    }

    fun deleteOptimizedCopy() {
        val wasActive = buildState().optimization.isUsable
        repository.deleteOptimizedFile()
        refresh()
        if (wasActive) restartIfActive()
        showMessage("Optimized copy deleted; the original will be used.")
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    private fun restartIfActive() {
        if (_uiState.value.playback.status in ACTIVE_STATUSES) {
            runCatching {
                getApplication<Application>().startService(
                    PlaybackService.intent(getApplication(), PlaybackService.Action.RESTART),
                )
            }
        }
    }

    private fun applyResourcePolicyIfActive() {
        if (_uiState.value.playback.status in ACTIVE_STATUSES) {
            runCatching {
                getApplication<Application>().startService(
                    PlaybackService.intent(
                        getApplication(),
                        PlaybackService.Action.APPLY_RESOURCE_POLICY,
                    ),
                )
            }
        }
    }

    private fun refresh() {
        val old = _uiState.value
        _uiState.value = buildState().copy(
            isImporting = old.isImporting,
            message = old.message,
        )
    }

    private fun buildState(): MainUiState {
        repository.reconcileInterruptedPlayback()
        val playback = repository.snapshot()
        val outputSize = if (playback.hasVideo) {
            AutoResolutionResolver.resolve(getApplication(), playback)
        } else {
            OutputSize(0, 0)
        }
        val profile = OptimizationPlanner.profile(getApplication(), playback)
        return MainUiState(
            playback = playback,
            widgetCount = widgetManager.getAppWidgetIds(widgetProvider).size,
            outputSize = outputSize,
            optimization = repository.optimizationSnapshot(profile),
            optimizationRecommendation = OptimizationPlanner.recommendation(playback, profile),
            usageAccessGranted = LauncherVisibilityMonitor.hasUsageAccess(getApplication()),
        )
    }

    private fun showMessage(message: String) {
        _uiState.value = _uiState.value.copy(message = message)
    }

    companion object {
        private val ACTIVE_STATUSES = setOf(
            PlaybackStatus.STARTING,
            PlaybackStatus.RENDERING,
            PlaybackStatus.SUSPENDED,
        )
    }
}
