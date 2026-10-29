package com.bludood.homeloop.playback

import android.content.Context
import androidx.core.content.ContextCompat
import com.bludood.homeloop.data.PlaybackRepository
import com.bludood.homeloop.data.PlaybackStatus
import com.bludood.homeloop.widget.VideoWidgetProvider

object PlaybackController {
    @Volatile
    var serviceAlive: Boolean = false
        internal set

    @Volatile
    var inAppAudio: Boolean = false

    fun ensureRunning(context: Context) {
        val repository = PlaybackRepository(context)
        repository.reconcileInterruptedOptimization()
        val snapshot = repository.snapshot()
        if (!PlaybackLifecycle.shouldRun(
                snapshot.hasVideo, VideoWidgetProvider.hasWidgets(context),
                snapshot.timelineRunning, repository.isOptimizationRunning()
            )
        ) return
        if (serviceAlive && snapshot.status != PlaybackStatus.ERROR) return
        runCatching {
            ContextCompat.startForegroundService(
                context,
                PlaybackService.intent(context, PlaybackService.Action.ENSURE_RUNNING)
            )
        }.onFailure {
            repository.setStatus(
                PlaybackStatus.IDLE,
                "Android deferred playback; tap the widget to resume"
            )
            VideoWidgetProvider.showResumePrompt(context)
        }
    }
}
