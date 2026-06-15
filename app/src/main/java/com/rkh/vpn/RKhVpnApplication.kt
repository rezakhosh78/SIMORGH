package com.rkh.vpn

import android.app.Application
import com.rkh.vpn.analytics.SimorghTelemetry
import com.rkh.vpn.service.NotificationHelper

class RKhVpnApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannels(this)
        SimorghTelemetry.start(this)
    }
}
