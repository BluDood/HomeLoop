package com.bludood.homeloop.playback

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process

/**
 * Best-effort foreground-launcher detection. Android does not expose live AppWidgetHostView
 * visibility to widget providers, so this deliberately fails open when Usage Access or launcher
 * information is unavailable.
 */
class LauncherVisibilityMonitor(context: Context) {
    private val appContext = context.applicationContext
    private val usageStatsManager = appContext.getSystemService(UsageStatsManager::class.java)
    private var lastQueryWallMs = 0L
    private var latestResumedEventMs = Long.MIN_VALUE
    private var foregroundPackage: String? = null

    fun isLauncherForeground(): Boolean? {
        if (!hasUsageAccess(appContext)) return null
        val launcherPackage = defaultLauncherPackage(appContext) ?: return null
        val now = System.currentTimeMillis()
        val begin = if (lastQueryWallMs == 0L) {
            now - INITIAL_LOOKBACK_MS
        } else {
            (lastQueryWallMs - QUERY_OVERLAP_MS).coerceAtLeast(now - INITIAL_LOOKBACK_MS)
        }
        val events = runCatching { usageStatsManager?.queryEvents(begin, now) }.getOrNull()
            ?: return null
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (
                event.eventType == UsageEvents.Event.ACTIVITY_RESUMED &&
                event.timeStamp >= latestResumedEventMs
            ) {
                latestResumedEventMs = event.timeStamp
                foregroundPackage = event.packageName
            }
        }
        lastQueryWallMs = now
        return foregroundPackage?.let { it == launcherPackage }
    }

    fun reset() {
        lastQueryWallMs = 0L
        latestResumedEventMs = Long.MIN_VALUE
        foregroundPackage = null
    }

    companion object {
        private const val INITIAL_LOOKBACK_MS = 24L * 60L * 60L * 1_000L
        private const val QUERY_OVERLAP_MS = 2_000L

        fun hasUsageAccess(context: Context): Boolean {
            val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
            @Suppress("DEPRECATION")
            val mode = appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
            return mode == AppOpsManager.MODE_ALLOWED
        }

        fun defaultLauncherPackage(context: Context): String? {
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.resolveActivity(
                    homeIntent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
            }
            return resolved?.activityInfo?.packageName
                ?.takeUnless { it == "android" }
        }
    }
}
