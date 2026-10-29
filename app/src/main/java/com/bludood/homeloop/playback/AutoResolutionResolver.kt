package com.bludood.homeloop.playback

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.res.Configuration
import com.bludood.homeloop.data.OutputSize
import com.bludood.homeloop.data.PlaybackSnapshot
import com.bludood.homeloop.widget.VideoWidgetProvider
import kotlin.math.min
import kotlin.math.roundToInt

object AutoResolutionResolver {
    fun resolve(context: Context, snapshot: PlaybackSnapshot): OutputSize {
        val metrics = context.resources.displayMetrics
        val widgetManager = AppWidgetManager.getInstance(context)
        val portrait =
            context.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
        val desired = VideoWidgetProvider.widgetIds(context)
            .map { id ->
                val options = widgetManager.getAppWidgetOptions(id)
                val widthDp = options.getInt(
                    if (portrait) AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH
                    else AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH,
                )
                val heightDp = options.getInt(
                    if (portrait) AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT
                    else AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,
                )
                if (widthDp <= 0 || heightDp <= 0) OutputSize(0, 0) else OutputSize(
                    width = (widthDp * metrics.density).roundToInt(),
                    height = (heightDp * metrics.density).roundToInt(),
                )
            }
            .filter { it.width > 0 && it.height > 0 }
            .maxByOrNull { it.width.toLong() * it.height }
            ?: OutputSize(FALLBACK_WIDTH, FALLBACK_HEIGHT)
        val scale = snapshot.resolution.autoScalePercent / 100.0

        return AutoResolutionCalculator.calculate(
            desiredWidth = (desired.width * scale).roundToInt(),
            desiredHeight = (desired.height * scale).roundToInt(),
            sourceWidth = snapshot.cropInsets.remainingWidth(snapshot.videoWidth),
            sourceHeight = snapshot.cropInsets.remainingHeight(snapshot.videoHeight),
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels,
        )
    }
}

object AutoResolutionCalculator {
    fun calculate(
        desiredWidth: Int,
        desiredHeight: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        screenWidth: Int,
        screenHeight: Int,
    ): OutputSize {
        val width = desiredWidth.coerceAtLeast(2)
        val height = desiredHeight.coerceAtLeast(2)
        var scale = 1.0
        if (sourceWidth > 0 && sourceHeight > 0) {
            scale = min(scale, sourceWidth.toDouble() / width)
            scale = min(scale, sourceHeight.toDouble() / height)
        }
        if (screenWidth > 0 && screenHeight > 0) {
            scale = min(scale, screenWidth.toDouble() / width)
            scale = min(scale, screenHeight.toDouble() / height)
        }
        return OutputSize(
            width = even((width * scale).roundToInt()),
            height = even((height * scale).roundToInt()),
        )
    }

    private fun even(value: Int): Int = value.coerceAtLeast(2).let { it - it % 2 }
}

private const val FALLBACK_WIDTH = 854
private const val FALLBACK_HEIGHT = 480
