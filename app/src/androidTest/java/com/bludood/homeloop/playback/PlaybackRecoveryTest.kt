package com.bludood.homeloop.playback

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.bludood.homeloop.MainActivity
import com.bludood.homeloop.data.PlaybackRepository
import com.bludood.homeloop.data.PlaybackStatus
import com.bludood.homeloop.data.RenderingPolicy
import com.bludood.homeloop.widget.VideoWidgetProvider
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue

class PlaybackRecoveryTest {
    @Test fun playbackRecoversAndExplicitPauseSurvivesAutostart() {
        assumeTrue("This fixture changes playback settings and is emulator-only",
            Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val repository = PlaybackRepository(context)
        val host = AppWidgetHost(context, 9471)
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val file = File(context.cacheDir, "recovery-test.mp4")
        instrumentation.context.assets.open("recovery-test.mp4").use { input ->
            file.outputStream().use(input::copyTo)
        }
        val widgetId = host.allocateAppWidgetId()
        try {
            instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
            assertTrue(AppWidgetManager.getInstance(context).bindAppWidgetIdIfAllowed(widgetId,
                ComponentName(context, VideoWidgetProvider::class.java)))
            instrumentation.uiAutomation.dropShellPermissionIdentity()
            VideoWidgetProvider.updateAll(context, "Test playback")
            assertTrue(repository.importVideo(Uri.fromFile(file)).isSuccess)
            repository.setRenderingPolicy(RenderingPolicy.SCREEN_ON)
            PlaybackController.ensureRunning(context)
            await("initial frame delivery") { repository.snapshot().runtimeDiagnostics.publishedFrames > 0 }
            assertFalse(repository.reconcileInterruptedPlayback())

            context.startService(PlaybackService.intent(context, PlaybackService.Action.PAUSE))
            await("pause") { !PlaybackController.serviceAlive && !repository.snapshot().timelineRunning }
            val position = repository.snapshot().currentPositionMs
            PlaybackController.ensureRunning(context)
            SystemClock.sleep(1_000)
            assertFalse(PlaybackController.serviceAlive)
            assertEquals(position, repository.snapshot().currentPositionMs)
            context.startForegroundService(PlaybackService.intent(context, PlaybackService.Action.RENDER_PAUSED_FRAME))
            context.startForegroundService(PlaybackService.intent(context, PlaybackService.Action.RESUME))
            await("resume after preview") {
                repository.snapshot().let { it.status == PlaybackStatus.RENDERING &&
                    it.runtimeDiagnostics.publishedFrames > 0 }
            }
            SystemClock.sleep(1_000)
            assertTrue(repository.snapshot().timelineRunning)
            assertEquals(PlaybackStatus.RENDERING, repository.snapshot().status)

            context.stopService(PlaybackService.intent(context, PlaybackService.Action.ENSURE_RUNNING))
            await("service teardown") { !PlaybackController.serviceAlive }
            assertTrue(repository.snapshot().timelineRunning)
            PlaybackController.ensureRunning(context)
            await("service recreation") {
                PlaybackController.serviceAlive && repository.snapshot().let {
                    it.status == PlaybackStatus.RENDERING && it.runtimeDiagnostics.active &&
                        it.runtimeDiagnostics.publishedFrames > 0
                }
            }
            context.stopService(PlaybackService.intent(context, PlaybackService.Action.ENSURE_RUNNING))
            await("teardown before resume tap") { !PlaybackController.serviceAlive }
            repository.setAudioEnabled(false)
            repository.setWidgetTapAction(com.bludood.homeloop.data.WidgetTapAction.TOGGLE_MUTE)
            VideoWidgetProvider.showResumePrompt(context)
            VideoWidgetProvider.handleWidgetTap(context, resumeOnly = true)
            await("resume-only tap") {
                PlaybackController.serviceAlive && repository.snapshot().let {
                    it.status == PlaybackStatus.RENDERING && it.runtimeDiagnostics.active &&
                        it.runtimeDiagnostics.publishedFrames > 0
                }
            }
            assertFalse("Resume must not unmute", repository.snapshot().audioEnabled)
            VideoWidgetProvider.handleWidgetTap(context)
            assertTrue("Following taps use the configured action", repository.snapshot().audioEnabled)

        } finally {
            instrumentation.uiAutomation.dropShellPermissionIdentity()
            repository.pauseTimeline()
            context.stopService(PlaybackService.intent(context, PlaybackService.Action.PAUSE))
            host.deleteAppWidgetId(widgetId)
            file.delete()
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun await(description: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (!predicate() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        assertTrue("Timed out waiting for $description", predicate())
    }
}
