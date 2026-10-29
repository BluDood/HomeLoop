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
import com.bludood.homeloop.data.RenderingPolicy
import com.bludood.homeloop.data.WidgetTapAction
import com.bludood.homeloop.widget.VideoWidgetProvider
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

// Run only with -e class ...BootRecoveryFixtureTest -e prepareBootFixture true on a disposable emulator.
class BootRecoveryFixtureTest {
    @Test fun prepareRebootFixture() {
        assumeTrue(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("prepareBootFixture") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val repository = PlaybackRepository(context)
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        repository.pauseTimeline()
        context.stopService(PlaybackService.intent(context, PlaybackService.Action.PAUSE))
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (PlaybackController.serviceAlive && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        assertFalse(PlaybackController.serviceAlive)
        val host = AppWidgetHost(context, 9472)
        host.deleteHost()
        val widgetId = host.allocateAppWidgetId()
        try {
            instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
            assertTrue(AppWidgetManager.getInstance(context).bindAppWidgetIdIfAllowed(widgetId,
                ComponentName(context, VideoWidgetProvider::class.java)))
        } finally {
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync { activity.finish() }
        instrumentation.waitForIdleSync()
        val file = File(context.filesDir, "boot-test.mp4")
        instrumentation.context.assets.open("recovery-test.mp4").use { input ->
            file.outputStream().use(input::copyTo)
        }
        assertTrue(repository.importVideo(Uri.fromFile(file)).isSuccess)
        repository.setRenderingPolicy(RenderingPolicy.SCREEN_ON)
        repository.setAudioEnabled(false)
        repository.setWidgetTapAction(WidgetTapAction.TOGGLE_MUTE)
        if (args.getString("paused") == "true") repository.pauseTimeline()
        VideoWidgetProvider.updateAll(context, "Reboot test ready")
    }
}
