package com.bludood.homeloop.playback

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.PowerManager
import android.util.Log
import com.bludood.homeloop.data.PlaybackRepository
import com.bludood.homeloop.widget.VideoWidgetProvider

class BootRecoveryJob : JobService() {
    private val handler = Handler(Looper.getMainLooper())

    override fun onStartJob(params: JobParameters): Boolean {
        val repository = PlaybackRepository(this)
        val snapshot = repository.snapshot()
        if (PlaybackController.serviceAlive || !PlaybackLifecycle.shouldRun(
                snapshot.hasVideo, VideoWidgetProvider.hasWidgets(this),
                snapshot.timelineRunning, repository.isOptimizationRunning()
            )
        ) return false
        if (!canStartInBackground(this)) {
            VideoWidgetProvider.showResumePrompt(this)
            return false
        }
        PlaybackController.ensureRunning(this)
        val attempt = params.extras.getInt(EXTRA_ATTEMPT, 0)
        handler.postDelayed({
            if (!PlaybackController.serviceAlive && attempt + 1 < MAX_ATTEMPTS) {
                schedule(this, attempt + 1)
            } else if (!PlaybackController.serviceAlive) {
                VideoWidgetProvider.showResumePrompt(this)
            }
            jobFinished(params, false)
        }, OUTCOME_CHECK_MS)
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        handler.removeCallbacksAndMessages(null)
        return false
    }

    companion object {
        private const val JOB_ID = 3403
        private const val EXTRA_ATTEMPT = "attempt"
        private const val MAX_ATTEMPTS = 4
        private const val FIRST_ATTEMPT_DELAY_MS = 10_000L
        private const val RETRY_DELAY_MS = 30_000L
        private const val OUTCOME_CHECK_MS = 8_000L

        fun batteryExempt(context: Context): Boolean =
            context.getSystemService(PowerManager::class.java)
                ?.isIgnoringBatteryOptimizations(context.packageName) == true

        fun canStartInBackground(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S || batteryExempt(context)

        fun schedule(context: Context, attempt: Int = 0) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            val delayMs = if (attempt == 0) FIRST_ATTEMPT_DELAY_MS else RETRY_DELAY_MS
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, BootRecoveryJob::class.java))
                .setMinimumLatency(delayMs)
                .setOverrideDeadline(delayMs + 30_000L)
                .setExtras(PersistableBundle().apply { putInt(EXTRA_ATTEMPT, attempt) })
                .build()
            runCatching { scheduler.schedule(job) }
                .onFailure { Log.w("HomeLoopBoot", "Could not schedule boot recovery", it) }
        }

        fun cancel(context: Context) {
            context.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
        }
    }
}
