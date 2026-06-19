package com.rkh.vpn.service

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.graphics.drawable.Icon
import com.rkh.vpn.MainActivity
import com.rkh.vpn.R

class RkhVpnTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val prefs = getSharedPreferences("rkh_vpn_state", MODE_PRIVATE)
        val connected = prefs.getBoolean("serviceConnected", false)
        if (connected) {
            startService(Intent(this, RkhVpnService::class.java).setAction(RkhVpnService.ACTION_STOP))
            prefs.edit().putBoolean("serviceConnected", false).apply()
            updateTile(false)
            return
        }

        val raw = prefs.getString("lastRawConfig", "").orEmpty()
        if (raw.isBlank() || VpnService.prepare(this) != null) {
            openMainActivity()
            updateTile(false)
            return
        }

        val intent = Intent(this, RkhVpnService::class.java)
            .setAction(RkhVpnService.ACTION_START)
            .putExtra(RkhVpnService.EXTRA_RAW_CONFIG, raw)
            .putExtra(RkhVpnService.EXTRA_SERVER_NAME, prefs.getString("lastServerName", "SIMORGH Private").orEmpty())
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        prefs.edit().putBoolean("serviceConnected", true).apply()
        updateTile(true)
    }

    private fun openMainActivity() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (Build.VERSION.SDK_INT >= 34) {
            val pi = PendingIntent.getActivity(this, 4101, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun updateTile(forceConnected: Boolean? = null) {
        val tile = qsTile ?: return
        val connected = forceConnected ?: getSharedPreferences("rkh_vpn_state", MODE_PRIVATE).getBoolean("serviceConnected", false)
        tile.label = "SIMORGH Private"
        tile.icon = Icon.createWithResource(this, R.drawable.app_icon_reza)
        tile.subtitle = if (connected) "Connected" else "Tap to connect"
        tile.state = if (connected) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }
}
