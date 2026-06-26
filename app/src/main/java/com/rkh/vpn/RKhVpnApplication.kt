package com.rkh.vpn

import android.app.Application
import android.os.Looper
import com.rkh.vpn.data.RKhVpnLogStore
import com.rkh.vpn.analytics.SimorghTelemetry
import com.rkh.vpn.service.NotificationHelper

class RKhVpnApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        installBackgroundCrashGuard()
        NotificationHelper.createChannels(this)
        SimorghTelemetry.start(this)
    }

    private fun installBackgroundCrashGuard() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                RKhVpnLogStore.append(this, "CrashGuard", "Recovered background crash in ${thread.name}: ${throwable.javaClass.simpleName}: ${throwable.message}", throwable)
                getSharedPreferences("simorgh_public_state", MODE_PRIVATE).edit()
                    .putBoolean("connecting", false)
                    .putBoolean("simpleConnecting", false)
                    .putBoolean("nipoConnecting", false)
                    .putBoolean("masterDnsConnecting", false)
                    .putString("lastError", throwable.message ?: throwable.javaClass.simpleName)
                    .apply()
            }
            if (thread == Looper.getMainLooper().thread) {
                previous?.uncaughtException(thread, throwable)
            }
        }
    }
}
