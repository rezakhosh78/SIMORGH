package com.rkh.vpn.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.rkh.vpn.MainActivity
import com.rkh.vpn.R

object NotificationHelper {
    const val CHANNEL = "rkh_vpn_status"

    fun createChannels(c: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            c.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "SIMORGH VPN Status", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    fun vpn(c: Context, title: String, text: String, connected: Boolean = true) =
        build(c, title, text, connected, privateStopIntent(c), requestCode = 2001)

    fun publicVpn(c: Context, title: String, text: String, connected: Boolean = true) =
        build(c, title, text, connected, publicStopIntent(c), requestCode = 2101)

    private fun build(c: Context, title: String, text: String, connected: Boolean, stopIntent: PendingIntent, requestCode: Int) =
        NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_simorgh_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSubText(if (connected) "VPN active" else "VPN stopped")
            .setOngoing(connected)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent(c, requestCode))
            .addAction(
                R.drawable.ic_simorgh_notification,
                if (connected) "⏹ Disconnect" else "▶ Connect",
                if (connected) stopIntent else openAppIntent(c, requestCode + 1)
            )
            .addAction(R.drawable.ic_simorgh_notification, "Open", openAppIntent(c, requestCode + 2))
            .build()

    private fun privateStopIntent(c: Context): PendingIntent {
        val i = Intent(c, RkhVpnService::class.java).setAction(RkhVpnService.ACTION_STOP)
        return PendingIntent.getService(c, 2002, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun publicStopIntent(c: Context): PendingIntent {
        val i = Intent(c, SimorghPublicVpnService::class.java).setAction(SimorghPublicVpnService.ACTION_STOP)
        return PendingIntent.getService(c, 2102, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun openAppIntent(c: Context, requestCode: Int): PendingIntent {
        val i = Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(c, requestCode, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
