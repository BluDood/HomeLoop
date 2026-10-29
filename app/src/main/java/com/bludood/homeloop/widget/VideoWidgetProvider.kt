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
import com.bludood.homeloop.data.PlaybackSnapshot
import com.bludood.homeloop.data.PlaybackStatus
import com.bludood.homeloop.data.WidgetTapAction
import com.bludood.homeloop.playback.PlaybackController
import com.bludood.homeloop.playback.PlaybackService
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

class VideoWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val snapshot = PlaybackRepository(context).snapshot()
        val status = when {
            !snapshot.hasVideo -> "Choose a video in the app"
            !snapshot.timelineRunning -> "Paused — tap to resume"
            else -> "Preparing playback"
        }
        appWidgetIds.forEach { id ->
            appWidgetManager.updateAppWidget(id, baseViews(context, status))
        }
        PlaybackController.ensureRunning(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        val snapshot = PlaybackRepository(context).snapshot()
        if (snapshot.status.isActive) {
            scheduleResizeRestart(context.applicationContext)
        }
    }

    override fun onDisabled(context: Context) {
        context.stopService(PlaybackService.intent(context, PlaybackService.Action.ENSURE_RUNNING))
    }

    companion object {
        internal const val EXTRA_RESUME_ONLY = "resume_only"
        internal const val EXTRA_CONTROL = "control"
        internal const val CONTROL_PLAY = "play"
        internal const val CONTROL_MUTE = "mute"
        internal const val CONTROL_HIDE = "hide"

        fun showResumePrompt(context: Context) {
            val views = RemoteViews(context.packageName, R.layout.video_widget).apply {
                setTextViewText(R.id.widget_status, "Tap to resume")
                setViewVisibility(R.id.widget_status, View.VISIBLE)
                setViewVisibility(R.id.widget_feedback_icon, View.GONE)
                setViewVisibility(R.id.widget_controls, View.GONE)
                setOnClickPendingIntent(R.id.widget_root, resumePendingIntent(context))
            }
            AppWidgetManager.getInstance(context)
                .partiallyUpdateAppWidget(widgetIds(context), views)
        }

        fun restoreTapAction(context: Context) {
            val views = RemoteViews(context.packageName, R.layout.video_widget).apply {
                setOnClickPendingIntent(R.id.widget_root, widgetTapPendingIntent(context))
            }
            AppWidgetManager.getInstance(context)
                .partiallyUpdateAppWidget(widgetIds(context), views)
        }

        private fun resumePendingIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context, 410,
                Intent(context, WidgetTapReceiver::class.java)
                    .putExtra(EXTRA_RESUME_ONLY, true)
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

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
        private fun showFeedback(context: Context, feedbackIcon: Int) {
            val token = feedbackGeneration.incrementAndGet()
            updateFeedback(context, feedbackIcon, 255, View.VISIBLE)
            for (step in 0 until FEEDBACK_FADE_STEPS) {
                val alpha = (255f * (FEEDBACK_FADE_STEPS - step) / (FEEDBACK_FADE_STEPS + 1)).roundToInt()
                val stepDelayMs = FEEDBACK_FADE_START_MS +
                        (step + 1L) * FEEDBACK_FADE_DURATION_MS / (FEEDBACK_FADE_STEPS + 1L)
                feedbackHandler.postDelayed(
                    {
                        if (feedbackGeneration.get() == token) {
                            updateFeedback(context, feedbackIcon, alpha, View.VISIBLE)
                        }
                    },
                    stepDelayMs,
                )
            }
            feedbackHandler.postDelayed(
                {
                    if (feedbackGeneration.get() == token) {
                        updateFeedback(context, feedbackIcon, 0, View.GONE)
                    }
                },
                FEEDBACK_DURATION_MS,
            )
        }

        fun showControls(context: Context) {
            val token = controlsGeneration.incrementAndGet()
            val snapshot = PlaybackRepository(context).snapshot()
            val manager = AppWidgetManager.getInstance(context)
            widgetIds(context).forEach { id ->
                val widthDp = manager.getAppWidgetOptions(id)
                    .getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
                val views = RemoteViews(context.packageName, R.layout.video_widget).apply {
                    setViewVisibility(R.id.widget_feedback_icon, View.GONE)
                    setViewVisibility(R.id.widget_controls, View.VISIBLE)
                    setOnClickPendingIntent(
                        R.id.widget_controls,
                        controlPendingIntent(context, CONTROL_HIDE)
                    )
                    setImageViewResource(
                        R.id.widget_control_play,
                        if (snapshot.timelineRunning) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
                    )
                    setContentDescription(
                        R.id.widget_control_play,
                        context.getString(if (snapshot.timelineRunning) R.string.widget_pause else R.string.widget_play),
                    )
                    setOnClickPendingIntent(
                        R.id.widget_control_play,
                        controlPendingIntent(context, CONTROL_PLAY)
                    )
                    setImageViewResource(
                        R.id.widget_control_mute,
                        if (snapshot.audioEnabled) R.drawable.ic_widget_volume else R.drawable.ic_widget_muted,
                    )
                    setContentDescription(
                        R.id.widget_control_mute,
                        context.getString(if (snapshot.audioEnabled) R.string.widget_mute else R.string.widget_unmute),
                    )
                    setOnClickPendingIntent(
                        R.id.widget_control_mute,
                        controlPendingIntent(context, CONTROL_MUTE)
                    )
                    setViewVisibility(
                        R.id.widget_control_fullscreen,
                        if (widthDp == 0 || widthDp >= FULL_CONTROLS_MIN_WIDTH_DP) View.VISIBLE else View.GONE,
                    )
                    setOnClickPendingIntent(
                        R.id.widget_control_fullscreen,
                        openAppPendingIntent(context, openPlayer = true),
                    )
                }
                manager.partiallyUpdateAppWidget(id, views)
            }
            controlsHandler.postDelayed(
                { if (controlsGeneration.get() == token) hideControls(context) },
                CONTROLS_TIMEOUT_MS,
            )
        }

        fun hideControls(context: Context) {
            controlsGeneration.incrementAndGet()
            val ids = widgetIds(context)
            if (ids.isEmpty()) return
            val views = RemoteViews(context.packageName, R.layout.video_widget).apply {
                setViewVisibility(R.id.widget_controls, View.GONE)
            }
            AppWidgetManager.getInstance(context).partiallyUpdateAppWidget(ids, views)
        }

        internal fun handleControl(context: Context, control: String) {
            if (control == CONTROL_HIDE) {
                hideControls(context)
                return
            }
            val repository = PlaybackRepository(context)
            repository.reconcileInterruptedPlayback()
            if (repository.isOptimizationRunning()) {
                hideControls(context)
                updateStatus(context, "Playback waits for optimization")
                return
            }
            val snapshot = repository.snapshot()
            when (control) {
                CONTROL_PLAY -> togglePlayback(context, repository, snapshot, feedback = false)
                CONTROL_MUTE -> toggleMute(context, repository, snapshot, feedback = false)
            }
            showControls(context)
        }

        private fun controlPendingIntent(context: Context, control: String): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                when (control) {
                    CONTROL_PLAY -> 420
                    CONTROL_MUTE -> 421
                    else -> 422
                },
                Intent(context, WidgetTapReceiver::class.java)
                    .putExtra(EXTRA_CONTROL, control)
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

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
            val snapshot = PlaybackRepository(context).snapshot()
            if (snapshot.hasVideo && snapshot.timelineRunning && !PlaybackController.serviceAlive) {
                return resumePendingIntent(context)
            }
            val action = snapshot.widgetTapAction
            return if (action == WidgetTapAction.OPEN_APP || action == WidgetTapAction.OPEN_PLAYER) {
                openAppPendingIntent(context, openPlayer = action == WidgetTapAction.OPEN_PLAYER)
            } else {
                PendingIntent.getBroadcast(
                    context,
                    100 + action.ordinal,
                    Intent(context, WidgetTapReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }
        }

        private fun openAppPendingIntent(context: Context, openPlayer: Boolean): PendingIntent =
            PendingIntent.getActivity(
                context,
                if (openPlayer) 1 else 0,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    if (openPlayer) putExtra(MainActivity.EXTRA_OPEN_PLAYER, true)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        internal fun handleWidgetTap(context: Context, resumeOnly: Boolean = false) {
            val repository = PlaybackRepository(context)
            repository.reconcileInterruptedPlayback()
            val snapshot = repository.snapshot()
            if (repository.isOptimizationRunning()) {
                updateStatus(context, "Playback waits for optimization")
                return
            }
            if (snapshot.hasVideo && (resumeOnly ||
                        (snapshot.timelineRunning && !PlaybackController.serviceAlive))
            ) {
                if (!snapshot.timelineRunning) repository.resumeTimeline()
                PlaybackController.ensureRunning(context)
                return
            }
            when (snapshot.widgetTapAction) {
                WidgetTapAction.OPEN_APP, WidgetTapAction.OPEN_PLAYER -> Unit
                WidgetTapAction.SHOW_CONTROLS -> {
                    if (snapshot.hasVideo) showControls(context) else updateStatus(
                        context,
                        "Choose a video in the app"
                    )
                }

                WidgetTapAction.TOGGLE_PLAYBACK -> togglePlayback(context, repository, snapshot)
                WidgetTapAction.TOGGLE_MUTE -> toggleMute(context, repository, snapshot)
            }
        }

        private fun togglePlayback(
            context: Context,
            repository: PlaybackRepository,
            snapshot: PlaybackSnapshot,
            feedback: Boolean = true,
        ) {
            if (!snapshot.hasVideo) {
                updateStatus(context, "Choose a video in the app")
            } else if (snapshot.timelineRunning && PlaybackController.serviceAlive) {
                repository.pauseTimeline()
                repository.setStatus(PlaybackStatus.PAUSED, "Playback paused")
                runCatching {
                    context.startService(
                        PlaybackService.intent(context, PlaybackService.Action.PAUSE),
                    )
                }
                if (feedback) showFeedback(context, R.drawable.widget_feedback_pause)
            } else {
                repository.resumeTimeline()
                repository.setStatus(PlaybackStatus.STARTING, "Resuming playback")
                if (feedback) showFeedback(context, R.drawable.widget_feedback_play)
                runCatching {
                    ContextCompat.startForegroundService(
                        context,
                        PlaybackService.intent(context, PlaybackService.Action.RESUME),
                    )
                }.onFailure {
                    repository.setStatus(
                        PlaybackStatus.ERROR,
                        it.message ?: "Could not resume from the widget",
                    )
                    updateStatus(context, "Could not resume — open the app")
                }
            }
        }

        private fun toggleMute(
            context: Context,
            repository: PlaybackRepository,
            snapshot: PlaybackSnapshot,
            feedback: Boolean = true,
        ) {
            val enabled = !snapshot.audioEnabled
            repository.setAudioEnabled(enabled)
            if (snapshot.status.isActive) {
                runCatching {
                    context.startService(
                        PlaybackService.intent(context, PlaybackService.Action.APPLY_AUDIO),
                    )
                }
            }
            PlaybackController.ensureRunning(context)
            if (feedback) {
                showFeedback(
                    context,
                    if (enabled) R.drawable.widget_feedback_volume else R.drawable.widget_feedback_muted,
                )
            }
        }

        private fun scheduleResizeRestart(context: Context) {
            val token = resizeGeneration.incrementAndGet()
            resizeHandler.postDelayed(
                {
                    if (resizeGeneration.get() != token) return@postDelayed
                    val snapshot = PlaybackRepository(context).snapshot()
                    if (snapshot.status.isActive) {
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

        private fun updateFeedback(context: Context, feedbackIcon: Int, alpha: Int, visibility: Int) {
            val ids = widgetIds(context)
            if (ids.isEmpty()) return
            val views = RemoteViews(context.packageName, R.layout.video_widget).apply {
                setViewVisibility(R.id.widget_status, View.GONE)
                setImageViewResource(R.id.widget_feedback_icon, feedbackIcon)
                setInt(R.id.widget_feedback_icon, "setImageAlpha", alpha)
                setViewVisibility(R.id.widget_feedback_icon, visibility)
            }
            AppWidgetManager.getInstance(context).partiallyUpdateAppWidget(ids, views)
        }

        private const val FEEDBACK_FADE_START_MS = 1_400L
        private const val FEEDBACK_DURATION_MS = 2_000L
        private const val RESIZE_RESTART_DEBOUNCE_MS = 500L
        private const val CONTROLS_TIMEOUT_MS = 3_000L
        private const val FULL_CONTROLS_MIN_WIDTH_DP = 180
        private const val FEEDBACK_FADE_DURATION_MS =
            FEEDBACK_DURATION_MS - FEEDBACK_FADE_START_MS
        private const val FEEDBACK_FADE_STEPS = 9
        private val feedbackHandler = Handler(Looper.getMainLooper())
        private val feedbackGeneration = AtomicInteger(0)
        private val controlsHandler = Handler(Looper.getMainLooper())
        private val controlsGeneration = AtomicInteger(0)
        private val resizeHandler = Handler(Looper.getMainLooper())
        private val resizeGeneration = AtomicInteger(0)
    }
}
