package com.bludood.homeloop.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class WidgetTapReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        VideoWidgetProvider.handleWidgetTap(context.applicationContext)
    }
}
