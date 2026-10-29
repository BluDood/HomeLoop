package com.bludood.homeloop.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bludood.homeloop.data.PlaybackRepository
import com.bludood.homeloop.widget.VideoWidgetProvider

class BootRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        if (PlaybackController.serviceAlive) return
        val repository = PlaybackRepository(context)
        repository.reconcileInterruptedPlayback()
        val snapshot = repository.snapshot()
        if (!snapshot.hasVideo || !VideoWidgetProvider.hasWidgets(context)) return
        if (!snapshot.timelineRunning) {
            BootRecoveryJob.cancel(context)
            VideoWidgetProvider.updateAll(context, "Playback paused")
            return
        }
        VideoWidgetProvider.showResumePrompt(context)
        if (!BootRecoveryJob.canStartInBackground(context)) return
        VideoWidgetProvider.updateStatus(context, "Starting playback…")
        BootRecoveryJob.schedule(context)
    }
}
