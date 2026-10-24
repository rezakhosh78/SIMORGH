package com.rkh.vpn.analytics

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.rkh.vpn.data.RKhVpnLogStore
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

object SimorghTelemetry {
    private const val TAG = "Telemetry"
    private const val PREFS = "simorgh_analytics"
    private const val PUBLIC_PREFS = "simorgh_public_state"
    private const val KEY_INSTALL_ID = "anonymousInstallId"
    private const val KEY_INSTALL_SENT = "firstInstallSent"
    private const val HEARTBEAT_INTERVAL_MS = 60_000L
    private val started = AtomicBoolean(false)

    fun start(context: Context) {
        val appContext = context.applicationContext
        if (!started.compareAndSet(false, true)) return
        val endpoint = loadEndpoint(appContext)
        if (endpoint.isBlank()) {
            log(appContext, "Analytics disabled: endpoint is empty")
            return
        }
        thread(name = "simorgh-telemetry", isDaemon = true) {
            runCatching {
                val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val installId = prefs.getString(KEY_INSTALL_ID, null) ?: UUID.randomUUID().toString().also {
                    prefs.edit().putString(KEY_INSTALL_ID, it).apply()
                }
                if (!prefs.getBoolean(KEY_INSTALL_SENT, false)) {
                    if (sendEvent(appContext, endpoint, installId, "first_install")) {
                        prefs.edit().putBoolean(KEY_INSTALL_SENT, true).apply()
                    }
                }
                sendEvent(appContext, endpoint, installId, "app_open")
                while (true) {
                    sendEvent(appContext, endpoint, installId, "heartbeat")
                    Thread.sleep(HEARTBEAT_INTERVAL_MS)
                }
            }.onFailure { log(appContext, "Telemetry loop stopped: ${it.message ?: it.javaClass.simpleName}", it) }
        }
    }

    fun track(context: Context, event: String, modeOverride: String? = null) {
        val appContext = context.applicationContext
        val endpoint = loadEndpoint(appContext)
        if (endpoint.isBlank()) return
        thread(name = "simorgh-telemetry-event", isDaemon = true) {
            val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val installId = prefs.getString(KEY_INSTALL_ID, null) ?: UUID.randomUUID().toString().also {
                prefs.edit().putString(KEY_INSTALL_ID, it).apply()
            }
            sendEvent(appContext, endpoint, installId, event, modeOverride)
        }
    }

    private fun sendEvent(context: Context, endpoint: String, installId: String, event: String, modeOverride: String? = null): Boolean {
        return runCatching {
            val payload = buildPayload(context, installId, event, modeOverride)
            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 3500
                readTimeout = 3500
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("User-Agent", "SIMORGH-Android")
            }
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            conn.disconnect()
            code in 200..299
        }.onFailure { log(context, "Telemetry send failed: ${it.message ?: it.javaClass.simpleName}") }.getOrDefault(false)
    }

    private fun buildPayload(context: Context, installId: String, event: String, modeOverride: String?): JSONObject {
        val publicPrefs = context.getSharedPreferences(PUBLIC_PREFS, Context.MODE_PRIVATE)
        val packageInfo = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        val versionName = packageInfo?.versionName ?: "unknown"
        val versionCode = if (packageInfo != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) packageInfo.longVersionCode else @Suppress("DEPRECATION") (packageInfo?.versionCode ?: 0).toLong()
        val activeMode = modeOverride ?: publicPrefs.getString("activeMode", "idle").orEmpty().ifBlank { "idle" }
        val simpleServerless = publicPrefs.getBoolean("simpleServerlessEnabled", false)
        return JSONObject().apply {
            put("app", "SIMORGH")
            put("installId", installId)
            put("event", event)
            put("version", versionName)
            put("versionCode", versionCode)
            put("mode", activeMode)
            put("serverless", simpleServerless)
            put("sdk", Build.VERSION.SDK_INT)
            put("device", Build.MANUFACTURER + " " + Build.MODEL)
            put("ts", System.currentTimeMillis())
        }
    }

    private fun readShieldedAssetText(context: Context, path: String): String {
        val encoded = context.assets.open(path).use { input -> input.readBytes() }
        val key = byteArrayOf(
            82, 75, 45, 83, 73, 77, 79, 82, 71, 72, 45, 65, 83, 83,
            69, 84, 45, 83, 72, 73, 69, 76, 68, 45, 118, 50
        )
        val decoded = ByteArray(encoded.size)
        for (i in encoded.indices) {
            decoded[i] = (encoded[i].toInt() xor key[i % key.size].toInt() xor ((i * 31 + 0x5D) and 0xFF)).toByte()
        }
        return decoded.toString(Charsets.UTF_8)
    }

    private fun loadEndpoint(context: Context): String {
        return runCatching {
            val raw = readShieldedAssetText(context, "rk_payload/p2.dat").lineSequence().toList()
                .map { it.trim() }
                .firstOrNull { it.isNotBlank() && !it.startsWith("#") }
                .orEmpty()
                .trimEnd('/')
            when {
                raw.isBlank() -> ""
                raw.endsWith("/event") -> raw
                else -> "$raw/event"
            }
        }.getOrDefault("")
    }

    private fun log(context: Context, message: String, throwable: Throwable? = null) {
        RKhVpnLogStore.append(context, TAG, message, throwable)
    }
}
