package com.bludood.homeloop.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class WidgetTapReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        intent.getStringExtra(VideoWidgetProvider.EXTRA_CONTROL)?.let {
            VideoWidgetProvider.handleControl(context.applicationContext, it)
            return
        }
        VideoWidgetProvider.handleWidgetTap(
            context.applicationContext,
            intent.getBooleanExtra(VideoWidgetProvider.EXTRA_RESUME_ONLY, false)
        )
    }
}
