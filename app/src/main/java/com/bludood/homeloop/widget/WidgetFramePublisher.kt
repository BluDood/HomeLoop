package com.bludood.homeloop.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import com.bludood.homeloop.R
import java.util.ArrayDeque

class WidgetFramePublisher(context: Context) {
    private val appContext = context.applicationContext
    private val widgetManager = AppWidgetManager.getInstance(appContext)
    private val retainedBitmaps = ArrayDeque<Bitmap>()
    private var consecutiveFailures = 0

    @Synchronized
    fun publish(bitmap: Bitmap): Bitmap? {
        val ids = VideoWidgetProvider.widgetIds(appContext)
        if (ids.isEmpty()) {
            return bitmap
        }
        val views = RemoteViews(appContext.packageName, R.layout.video_widget).apply {
            setImageViewBitmap(R.id.widget_frame, bitmap)
            setViewVisibility(R.id.widget_status, View.GONE)
        }
        val failure = runCatching { widgetManager.partiallyUpdateAppWidget(ids, views) }
            .exceptionOrNull()
        retainedBitmaps.addLast(bitmap)
        val reusable = if (retainedBitmaps.size > RETAINED_BITMAP_COUNT) {
            retainedBitmaps.removeFirst()
        } else {
            null
        }
        if (failure == null) {
            consecutiveFailures = 0
            return reusable
        }
        consecutiveFailures++
        Log.w(TAG, "Launcher rejected a widget frame", failure)
        if (consecutiveFailures < MAX_CONSECUTIVE_FAILURES) return reusable
        reusable?.recycleSafely()
        throw IllegalStateException(
            "The launcher repeatedly rejected widget frames; try a lower Auto percentage",
            failure,
        )
    }

    @Synchronized
    fun close() {
        while (retainedBitmaps.isNotEmpty()) retainedBitmaps.removeFirst().recycleSafely()
    }

    private fun Bitmap.recycleSafely() {
        if (!isRecycled) recycle()
    }

    companion object {
        // Keep only a short hand-off window for the launcher instead of retaining dozens of HD frames.
        private const val RETAINED_BITMAP_COUNT = 3
        private const val MAX_CONSECUTIVE_FAILURES = 3
        private const val TAG = "WidgetFramePublisher"
    }
}
