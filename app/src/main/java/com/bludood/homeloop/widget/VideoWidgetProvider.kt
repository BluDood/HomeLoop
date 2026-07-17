package com.bludood.homeloop.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import com.bludood.homeloop.MainActivity
import com.bludood.homeloop.R
import com.bludood.homeloop.data.PlaybackRepository
import com.bludood.homeloop.data.PlaybackStatus
import com.bludood.homeloop.data.WidgetTapAction
import com.bludood.homeloop.playback.PlaybackService
import java.util.concurrent.atomic.AtomicInteger

class VideoWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        appWidgetIds.forEach { id ->
            appWidgetManager.updateAppWidget(id, baseViews(context, "Open the app to begin"))
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        val snapshot = PlaybackRepository(context).snapshot()
        if (snapshot.resolution.isAuto && snapshot.status in ACTIVE_STATUSES) {
            scheduleResizeRestart(context.applicationContext)
        }
    }

    companion object {
        fun updateAll(context: Context, status: String) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = widgetIds(context)
            if (ids.isNotEmpty()) manager.updateAppWidget(ids, baseViews(context, status))
        }

        fun updateStatus(context: Context, status: String) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = widgetIds(context)
            if (ids.isEmpty()) return
            val views = RemoteViews(context.packageName, R.layout.video_widget).apply {
                setTextViewText(R.id.widget_status, status)
                setViewVisibility(R.id.widget_status, View.VISIBLE)
                setViewVisibility(R.id.widget_feedback_icon, View.GONE)
            }
            manager.partiallyUpdateAppWidget(ids, views)
        }

        fun showFeedback(context: Context, iconResource: Int) {
            val token = feedbackGeneration.incrementAndGet()
            updateFeedback(context, iconResource, 255, View.VISIBLE)
            FEEDBACK_FADE_ALPHAS.forEachIndexed { index, alpha ->
                val stepDelayMs = FEEDBACK_FADE_START_MS +
                    (index + 1L) * FEEDBACK_FADE_DURATION_MS /
                    (FEEDBACK_FADE_ALPHAS.size + 1L)
                feedbackHandler.postDelayed(
                    {
                        if (feedbackGeneration.get() == token) {
                            updateFeedback(context, iconResource, alpha, View.VISIBLE)
                        }
                    },
                    stepDelayMs,
                )
            }
            feedbackHandler.postDelayed(
                {
                    if (feedbackGeneration.get() == token) {
                        updateFeedback(context, iconResource, 0, View.GONE)
                    }
                },
                FEEDBACK_DURATION_MS,
            )
        }

        fun hasWidgets(context: Context): Boolean = widgetIds(context).isNotEmpty()

        fun widgetIds(context: Context): IntArray = AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, VideoWidgetProvider::class.java))

        private fun baseViews(context: Context, status: String): RemoteViews =
            RemoteViews(context.packageName, R.layout.video_widget).apply {
                setTextViewText(R.id.widget_status, status)
                setOnClickPendingIntent(
                    R.id.widget_root,
                    widgetTapPendingIntent(context),
                )
            }

        private fun widgetTapPendingIntent(context: Context): PendingIntent {
            val action = PlaybackRepository(context).snapshot().widgetTapAction
            return if (action == WidgetTapAction.OPEN_APP) {
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            } else {
                PendingIntent.getBroadcast(
                    context,
                    100 + action.ordinal,
                    Intent(context, WidgetTapReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }
        }

        internal fun handleWidgetTap(context: Context) {
            val repository = PlaybackRepository(context)
            repository.reconcileInterruptedPlayback()
            val snapshot = repository.snapshot()
            when (snapshot.widgetTapAction) {
                WidgetTapAction.OPEN_APP -> Unit
                WidgetTapAction.TOGGLE_PLAYBACK -> {
                    if (!snapshot.hasVideo) {
                        updateStatus(context, "Choose a video in the app")
                    } else if (snapshot.timelineRunning) {
                        repository.pauseTimeline()
                        repository.setStatus(PlaybackStatus.PAUSED, "Playback paused")
                        runCatching {
                            context.startService(
                                PlaybackService.intent(context, PlaybackService.Action.PAUSE),
                            )
                        }
                        showFeedback(context, R.drawable.ic_widget_pause)
                    } else {
                        repository.resumeTimeline()
                        repository.setStatus(PlaybackStatus.STARTING, "Resuming playback")
                        showFeedback(context, R.drawable.ic_widget_play)
                        runCatching {
                            ContextCompat.startForegroundService(
                                context,
                                PlaybackService.intent(context, PlaybackService.Action.RESUME),
                            )
                        }.onFailure {
                            repository.pauseTimeline()
                            repository.setStatus(
                                PlaybackStatus.ERROR,
                                it.message ?: "Could not resume from the widget",
                            )
                            updateStatus(context, "Could not resume — open the app")
                        }
                    }
                }

                WidgetTapAction.TOGGLE_MUTE -> {
                    val enabled = !snapshot.audioEnabled
                    repository.setAudioEnabled(enabled)
                    if (snapshot.status in ACTIVE_STATUSES) {
                        runCatching {
                            context.startService(
                                PlaybackService.intent(context, PlaybackService.Action.APPLY_AUDIO),
                            )
                        }
                    }
                    showFeedback(
                        context,
                        if (enabled) R.drawable.ic_widget_volume else R.drawable.ic_widget_muted,
                    )
                }
            }
        }

        private fun scheduleResizeRestart(context: Context) {
            val token = resizeGeneration.incrementAndGet()
            resizeHandler.postDelayed(
                {
                    if (resizeGeneration.get() != token) return@postDelayed
                    val snapshot = PlaybackRepository(context).snapshot()
                    if (snapshot.resolution.isAuto && snapshot.status in ACTIVE_STATUSES) {
                        runCatching {
                            context.startService(
                                PlaybackService.intent(
                                    context,
                                    PlaybackService.Action.RESTART_VIDEO,
                                ),
                            )
                        }
                    }
                },
                RESIZE_RESTART_DEBOUNCE_MS,
            )
        }

        private fun updateFeedback(
            context: Context,
            iconResource: Int,
            alpha: Int,
            visibility: Int,
        ) {
            val ids = widgetIds(context)
            if (ids.isEmpty()) return
            val views = RemoteViews(context.packageName, R.layout.video_widget).apply {
                setViewVisibility(R.id.widget_status, View.GONE)
                setImageViewResource(
                    R.id.widget_feedback_icon,
                    feedbackDrawableResource(iconResource),
                )
                setInt(R.id.widget_feedback_icon, "setImageAlpha", alpha)
                setViewVisibility(R.id.widget_feedback_icon, visibility)
            }
            AppWidgetManager.getInstance(context).partiallyUpdateAppWidget(ids, views)
        }

        private fun feedbackDrawableResource(iconResource: Int): Int = when (iconResource) {
            R.drawable.ic_widget_pause -> R.drawable.widget_feedback_pause
            R.drawable.ic_widget_sync -> R.drawable.widget_feedback_sync
            R.drawable.ic_widget_volume -> R.drawable.widget_feedback_volume
            R.drawable.ic_widget_muted -> R.drawable.widget_feedback_muted
            else -> R.drawable.widget_feedback_play
        }

        private const val FEEDBACK_FADE_START_MS = 1_400L
        private const val FEEDBACK_DURATION_MS = 2_000L
        private const val RESIZE_RESTART_DEBOUNCE_MS = 500L
        private const val FEEDBACK_FADE_DURATION_MS =
            FEEDBACK_DURATION_MS - FEEDBACK_FADE_START_MS
        private val FEEDBACK_FADE_ALPHAS = intArrayOf(
            230,
            204,
            179,
            153,
            128,
            102,
            77,
            51,
            26,
        )
        private val feedbackHandler = Handler(Looper.getMainLooper())
        private val feedbackGeneration = AtomicInteger(0)
        private val resizeHandler = Handler(Looper.getMainLooper())
        private val resizeGeneration = AtomicInteger(0)
        private val ACTIVE_STATUSES = setOf(
            PlaybackStatus.STARTING,
            PlaybackStatus.RENDERING,
            PlaybackStatus.SUSPENDED,
        )
    }
}
