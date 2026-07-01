@file:Suppress("DEPRECATION")

package com.rkh.vpn

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.ComponentCallbacks2
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.Process
import com.rkh.vpn.analytics.SimorghTelemetry
import com.rkh.vpn.data.RKhVpnLogStore
import com.rkh.vpn.service.NotificationHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

class RKhVpnApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val processName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Application.getProcessName() else packageName
        RKhVpnLogStore.appendSync(this, "AppLifecycle", "Application onCreate • process=$processName • pid=${Process.myPid()} • sdk=${Build.VERSION.SDK_INT}")
        replayPreviousProcessExitInfo()
        installCrashGuard()
        installActivityLifecycleLogger()
        NotificationHelper.createChannels(this)
        SimorghTelemetry.start(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val label = trimLevelToString(level)
        RKhVpnLogStore.appendSync(this, "AppLifecycle", "onTrimMemory level=$level/$label • pid=${Process.myPid()}")
    }

    override fun onLowMemory() {
        RKhVpnLogStore.appendSync(this, "AppLifecycle", "onLowMemory received • Android may kill process if memory pressure continues")
        super.onLowMemory()
    }

    override fun onTerminate() {
        RKhVpnLogStore.appendSync(this, "AppLifecycle", "onTerminate called")
        super.onTerminate()
    }

    private fun installActivityLifecycleLogger() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                RKhVpnLogStore.append(this@RKhVpnApplication, "AppLifecycle", "Activity created: ${activity.localClassName}")
            }

            override fun onActivityStarted(activity: Activity) {
                RKhVpnLogStore.append(this@RKhVpnApplication, "AppLifecycle", "Activity started: ${activity.localClassName}")
            }

            override fun onActivityResumed(activity: Activity) {
                RKhVpnLogStore.append(this@RKhVpnApplication, "AppLifecycle", "Activity resumed: ${activity.localClassName}")
            }

            override fun onActivityPaused(activity: Activity) {
                RKhVpnLogStore.appendSync(this@RKhVpnApplication, "AppLifecycle", "Activity paused: ${activity.localClassName}")
            }

            override fun onActivityStopped(activity: Activity) {
                RKhVpnLogStore.appendSync(this@RKhVpnApplication, "AppLifecycle", "Activity stopped: ${activity.localClassName}")
            }

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

            override fun onActivityDestroyed(activity: Activity) {
                RKhVpnLogStore.appendSync(this@RKhVpnApplication, "AppLifecycle", "Activity destroyed: ${activity.localClassName}")
            }
        })
    }

    private fun installCrashGuard() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val isMain = thread == Looper.getMainLooper().thread
                val message = "FATAL uncaught exception • thread=${thread.name} • main=$isMain • " +
                    "pid=${Process.myPid()} • ${throwable.javaClass.name}: ${throwable.message ?: "no-message"}"
                RKhVpnLogStore.appendSync(this, "CrashGuard", message, throwable)
                getSharedPreferences("simorgh_public_state", MODE_PRIVATE or MODE_MULTI_PROCESS).edit()
                    .putBoolean("connecting", false)
                    .putBoolean("simpleConnecting", false)
                    .putBoolean("nipoConnecting", false)
                    .putBoolean("stormDnsConnecting", false)
                    .putString("lastError", "App crashed: ${throwable.javaClass.simpleName}: ${throwable.message ?: "no-message"}")
                    .commit()
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }

    private fun replayPreviousProcessExitInfo() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            RKhVpnLogStore.append(this, "ProcessExit", "Historical process-exit info unavailable below Android 11/API 30")
            return
        }
        runCatching {
            val prefs = getSharedPreferences("simorgh_crash_guard", MODE_PRIVATE)
            val lastSeen = prefs.getLong("lastExitTimestamp", 0L)
            val am = getSystemService(ActivityManager::class.java)
            val exits = am.getHistoricalProcessExitReasons(packageName, 0, 8)
                .filter { it.timestamp > lastSeen && it.pid != Process.myPid() }
                .sortedBy { it.timestamp }
            var maxTs = lastSeen
            exits.forEach { info ->
                maxTs = maxOf(maxTs, info.timestamp)
                val msg = buildString {
                    append("Previous app/process exit • reason=")
                    append(exitReasonToString(info.reason))
                    append(" • status=").append(info.status)
                    append(" • importance=").append(info.importance)
                    append(" • pid=").append(info.pid)
                    append(" • pssKb=").append(info.pss)
                    append(" • rssKb=").append(info.rss)
                    append(" • time=").append(formatTime(info.timestamp))
                    val desc = info.description.orEmpty().trim()
                    if (desc.isNotBlank()) append(" • description=").append(desc.take(320))
                }
                RKhVpnLogStore.appendSync(this, "ProcessExit", msg)
            }
            if (maxTs > lastSeen) prefs.edit().putLong("lastExitTimestamp", maxTs).commit()
            if (exits.isEmpty()) RKhVpnLogStore.append(this, "ProcessExit", "No new previous process-exit record")
        }.onFailure { e ->
            RKhVpnLogStore.appendSync(this, "ProcessExit", "Failed to read previous process-exit info", e)
        }
    }

    private fun exitReasonToString(reason: Int): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) when (reason) {
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_CRASH -> "JAVA_CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "NATIVE_CRASH"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_UNKNOWN -> "UNKNOWN"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        else -> "reason-$reason"
    } else "unavailable-$reason"

    private fun trimLevelToString(level: Int): String = when (level) {
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> "RUNNING_MODERATE"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "RUNNING_LOW"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "RUNNING_CRITICAL"
        ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> "UI_HIDDEN"
        ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> "BACKGROUND"
        ComponentCallbacks2.TRIM_MEMORY_MODERATE -> "MODERATE"
        ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "COMPLETE"
        else -> "level-$level"
    }

    private fun formatTime(timestamp: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timestamp))
}
