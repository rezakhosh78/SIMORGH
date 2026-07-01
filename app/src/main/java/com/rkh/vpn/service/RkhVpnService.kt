@file:Suppress("DEPRECATION")

package com.rkh.vpn.service

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.net.TrafficStats
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.rkh.vpn.core.ProcessCoreManager
import com.rkh.vpn.core.Tun2ProxyBridge
import com.rkh.vpn.core.XrayBinaryConfigBuilder
import com.rkh.vpn.data.RKhVpnLogStore
import com.rkh.vpn.data.StormDnsRuntimeLog
import com.rkh.vpn.data.FormatUtils
import kotlin.concurrent.thread
import java.net.ServerSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class RkhVpnService : VpnService() {
    private val tag = "RKhVPN-Service"

    companion object {
        const val ACTION_START = "com.rkh.vpn.START"
        const val ACTION_SWITCH_XRAY = "com.rkh.vpn.SWITCH_XRAY"
        const val ACTION_START_NIPO = "com.rkh.vpn.START_NIPO"
        const val ACTION_START_STORMDNS = "com.rkh.vpn.START_STORMDNS"
        const val ACTION_UPDATE_STORMDNS_RESOLVERS = "com.rkh.vpn.UPDATE_STORMDNS_RESOLVERS"
        const val ACTION_STOP = "com.rkh.vpn.STOP"
        const val ACTION_TOGGLE = "com.rkh.vpn.TOGGLE"
        const val EXTRA_RAW_CONFIG = "raw_config"
        const val EXTRA_SERVER_NAME = "server_name"
        const val EXTRA_NIPO_CONFIG = "nipo_config"
        const val EXTRA_STORMDNS_CONFIG = "stormdns_config"
        const val EXTRA_STORMDNS_SERVER_CONFIG = "stormdns_server_config"
        const val EXTRA_STORMDNS_RESOLVERS = "stormdns_resolvers"
        const val EXTRA_STORMDNS_MODE = "stormdns_mode"
        const val EXTRA_STOP_SOURCE = "stop_source"
        const val EXTRA_PRE_CONNECT_RESET = "pre_connect_reset"
    }

    private var tun: ParcelFileDescriptor? = null
    // Raw TUN fd used by tun2proxy fd-run. Detached from ParcelFileDescriptor to avoid Android fdsan/double-close aborts on disconnect.
    private var detachedTunFd: Int? = null
    @Volatile private var core: ProcessCoreManager? = null
    private var inheritedTunFd: java.io.FileDescriptor? = null
    private var inheritedTunPfd: ParcelFileDescriptor? = null
    private var notificationThread: Thread? = null
    @Volatile private var running = false
    private val lifecycleGeneration = AtomicInteger(0)
    private val stopGuard = AtomicBoolean(false)
    private val xraySwitchGuard = AtomicBoolean(false)
    private val stormDnsStartLock = Any()
    @Volatile private var stormDnsStartInProgress = false

    private fun isStormDnsRouteBypassMode(): Boolean {
        return try {
            val p = getSharedPreferences("simorgh_public_prefs", MODE_PRIVATE)
            p.getString("selectedCore", "") == "stormdns" ||
                p.getString("activeCore", "") == "stormdns" ||
                p.getBoolean("stormDnsEnabled", false) ||
                p.getBoolean("stormDnsConnected", false)
        } catch (_: Throwable) {
            false
        }
    }



    override fun onCreate() {
        super.onCreate()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                log("Safe service thread crash caught from ${thread.name}", throwable)
                getSharedPreferences("simorgh_public_state", Context.MODE_PRIVATE or Context.MODE_MULTI_PROCESS).edit()
                    .putBoolean("stormDnsConnecting", false)
                    .putBoolean("connecting", false)
                    .putBoolean("simpleConnecting", false)
                    .putBoolean("fragmentConnecting", false)
                    .putBoolean("fragmentConnected", false)
                    .putBoolean("nipoConnecting", false)
                    .putString("simpleStatus", "Core thread error: ${throwable.message ?: throwable.javaClass.simpleName}")
                    .putString("fragmentStatus", "Core thread error: ${throwable.message ?: throwable.javaClass.simpleName}")
                    .putString("stormDnsStatus", "Core thread error: ${throwable.message ?: throwable.javaClass.simpleName}")
                    .putString("status", "Core thread error")
                    .commit()
            }
        }
        log("Service created • binary-core mode, no libv2ray")
    }

    private fun tunnelSectionForLabel(label: String): String {
        val lower = label.lowercase()
        return when {
            lower.contains("storm") || lower.contains("dns") -> "stormdns"
            lower.contains("nipo") -> "nipo"
            lower.contains("fragment") -> "fragment"
            lower.contains("msp") || lower.contains("public") -> "msp"
            else -> "simple"
        }
    }

    private fun applyTunnelAppPolicy(builder: Builder, label: String) {
        val publicPrefs = getSharedPreferences("simorgh_public_state", Context.MODE_PRIVATE or Context.MODE_MULTI_PROCESS)
        val section = tunnelSectionForLabel(label)
        val mode = publicPrefs.getString("tunnelAppMode_$section", "all").orEmpty().ifBlank { "all" }
        val packages = if (mode == "all") emptySet<String>() else publicPrefs.getStringSet("tunnelAppPackages_${section}_$mode", emptySet<String>()) ?: emptySet<String>()
        if (mode == "only" && packages.isNotEmpty()) {
            packages.forEach { pkg -> runCatching { builder.addAllowedApplication(pkg) }.onFailure { log("Tunnel ONLY add failed for $pkg", it) } }
            log("Tunnel app policy for $label/$section: ONLY ${packages.size} app(s)")
        } else {
            runCatching { builder.addDisallowedApplication(packageName) }
                .onSuccess { log("Excluded app package from $label VPN loop: $packageName") }
                .onFailure { log("Could not exclude app from $label VPN loop", it) }
            if (mode == "exclude") {
                packages.forEach { pkg -> runCatching { builder.addDisallowedApplication(pkg) }.onFailure { log("Tunnel EXCLUDE add failed for $pkg", it) } }
                log("Tunnel app policy for $label/$section: EXCLUDE ${packages.size} app(s)")
            } else {
                log("Tunnel app policy for $label/$section: ALL apps")
            }
        }
    }


    private fun isCurrentLifecycle(generation: Int): Boolean = running && lifecycleGeneration.get() == generation

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        log("onStartCommand action=${intent?.action ?: "null"}")
        return try {
            when (intent?.action) {
                ACTION_START -> start(
                    intent.getStringExtra(EXTRA_RAW_CONFIG).orEmpty(),
                    intent.getStringExtra(EXTRA_SERVER_NAME).orEmpty()
                )
                ACTION_SWITCH_XRAY -> switchXrayOnly(
                    intent.getStringExtra(EXTRA_RAW_CONFIG).orEmpty(),
                    intent.getStringExtra(EXTRA_SERVER_NAME).orEmpty()
                )
                ACTION_START_NIPO -> startNipo(
                    intent.getStringExtra(EXTRA_NIPO_CONFIG).orEmpty()
                )
                ACTION_START_STORMDNS -> startStormDns(
                    intent.getStringExtra(EXTRA_STORMDNS_CONFIG).orEmpty(),
                    intent.getStringExtra(EXTRA_STORMDNS_MODE).orEmpty().ifBlank { "proxy" },
                    intent.getStringExtra(EXTRA_STORMDNS_SERVER_CONFIG).orEmpty(),
                    intent.getStringExtra(EXTRA_STORMDNS_RESOLVERS).orEmpty()
                )
                ACTION_UPDATE_STORMDNS_RESOLVERS -> log("MasterDNS live resolver update ignored in core-scan mode; VPN uses the resolver list from initial start")
                ACTION_STOP -> {
                    val stopSource = intent.getStringExtra(EXTRA_STOP_SOURCE).orEmpty()
                    val preConnectReset = intent.getBooleanExtra(EXTRA_PRE_CONNECT_RESET, false)
                    val label = when (stopSource) {
                        "nipo" -> "NipoVPN"
                        "fragment" -> "Fragment"
                        "simple_serverless" -> "Simple ServerLess"
                        "simple" -> "Simple"
                        else -> "Core"
                    }
                    log("$label stop action received${if (preConnectReset) " • pre-connect reset, no process exit" else ""}")
                    thread(name = "RKhVPN-stop-$label") {
                        runCatching { stopVpn(allowProcessExit = !preConnectReset) }
                            .onFailure { log("Safe $label stop caught error", it) }
                        // Do not call stopSelf() immediately after disconnect. On Android 14/15
                        // this can race with VpnService/onDestroy while tun2proxy/Xray callbacks are
                        // unwinding and can close the whole app task. The service is already non-sticky
                        // and has removed foreground state; the system can reclaim it safely.
                    }
                    return START_NOT_STICKY
                }
                ACTION_TOGGLE -> {
                    val prefs = getSharedPreferences("rkh_vpn_state", MODE_PRIVATE)
                    if (prefs.getBoolean("serviceConnected", false)) {
                        log("Toggle action: stopping VPN")
                        stopVpn()
                        stopSelf()
                        return START_NOT_STICKY
                    } else {
                        log("Toggle action: starting last VPN config")
                        start(
                            prefs.getString("lastRawConfig", "").orEmpty(),
                            prefs.getString("lastServerName", "").orEmpty()
                        )
                    }
                }
                else -> log("Unknown service action")
            }
            START_STICKY
        } catch (e: Throwable) {
            log("Safe service guard caught onStartCommand crash", e)
            runCatching { stopVpn() }
            getSharedPreferences("simorgh_public_state", Context.MODE_PRIVATE or Context.MODE_MULTI_PROCESS).edit()
                .putBoolean("connecting", false)
                .putBoolean("simpleConnecting", false)
                .putBoolean("simpleConnected", false)
                .putBoolean("fragmentStartInProgress", false)
                .putBoolean("fragmentConnecting", false)
                .putBoolean("fragmentConnected", false)
                .putBoolean("nipoConnecting", false)
                .putBoolean("stormDnsConnecting", false)
                .putString("status", "Core start error: ${e.message ?: e.javaClass.simpleName}")
                .putString("lastError", e.message ?: e.javaClass.simpleName)
                .putString("activeMode", "idle")
                .commit()
            runCatching { stopSelf() }.onFailure { log("Safe guard stopSelf failed", it) }
            START_NOT_STICKY
        }
    }



    private fun waitForStormDnsListeningLog(socksPort: Int, publicPrefs: android.content.SharedPreferences) {
        log("Waiting for MasterDNS client listening log before marking connected: SOCKS5 127.0.0.1:$socksPort")
        val startedAt = System.currentTimeMillis()
        while (running && System.currentTimeMillis() - startedAt < 180_000L) {
            val ready = StormDnsRuntimeLog.isSocksListeningDetected() || StormDnsRuntimeLog.readRecent(12).any { line ->
                val lower = line.lowercase()
                lower.contains("socks5 proxy server is listening on") ||
                    (lower.contains("proxy server is listening") && lower.contains(socksPort.toString())) ||
                    (lower.contains("listening on") && lower.contains(socksPort.toString()))
            }
            publicPrefs.edit()
                .putString("stormDnsStatus", "MasterDNS testing • waiting for SOCKS5 listening log")
                .apply()
            if (ready) {
                log("MasterDNS SOCKS5 listening log detected • 127.0.0.1:$socksPort")
                return
            }
            Thread.sleep(350L)
        }
        throw IllegalStateException("MasterDNS SOCKS5 listening log was not detected after 180 seconds")
    }

    private fun stormDnsAcceptedDnsCount(lines: List<String>): Int {
        return lines.count { line ->
            val lower = line.lowercase()
            (lower.contains("✅ accepted") || lower.contains("[info]") && lower.contains("accepted") || lower.contains(" accepted") || lower.contains("dns resolver reactivated")) &&
                !lower.contains("rejected") && !lower.contains("fail") && !lower.contains("timeout")
        }
    }

    private fun stormDnsMtuProgress(lines: List<String>): Pair<Int, Int> {
        val progressPattern = Regex("""\((\d+)\s*/\s*(\d+)\)""")
        var scanned = 0
        var total = 0
        lines.forEach { line ->
            val lower = line.lowercase()
            if (!lower.contains("accepted") && !lower.contains("rejected") && !lower.contains("mtu")) return@forEach
            progressPattern.findAll(line).forEach { match ->
                val a = match.groupValues.getOrNull(1)?.toIntOrNull() ?: 0
                val b = match.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
                if (b > 0) {
                    if (a > scanned) scanned = a
                    if (b > total) total = b
                }
            }
        }
        return scanned to total
    }

    private fun waitForStormDnsScanToSettleBeforeVpn(publicPrefs: android.content.SharedPreferences) {
        log("MasterDNS VPN will be created right after DNS scan settles")
        val startedAt = System.currentTimeMillis()
        var lastMarker = -1
        var stableTicks = 0
        while (running && System.currentTimeMillis() - startedAt < 20_000L) {
            val lines = StormDnsRuntimeLog.read()
            val accepted = maxOf(stormDnsAcceptedDnsCount(lines), StormDnsRuntimeLog.acceptedResolversSnapshot().size)
            val cachedProgress = StormDnsRuntimeLog.progressSnapshot()
            val parsedProgress = stormDnsMtuProgress(lines)
            val scanned = maxOf(cachedProgress.first, parsedProgress.first)
            val total = maxOf(cachedProgress.second, parsedProgress.second)
            val tail = lines.takeLast(16).joinToString("\n").lowercase()
            val completed = tail.contains("mtu test completed") || tail.contains("mtu testing completed") ||
                tail.contains("testing completed") || tail.contains("all resolver") || tail.contains("all resolvers") ||
                (total > 0 && scanned >= total)
            publicPrefs.edit()
                .putString("stormDnsStatus", if (total > 0) "MasterDNS testing DNS $scanned/$total • healthy=$accepted" else "MasterDNS testing DNS • healthy=$accepted")
                .apply()
            if (completed) {
                log("MasterDNS MTU/DNS scan completed • progress=$scanned/$total • healthy=$accepted")
                return
            }
            val marker = if (total > 0) scanned else accepted
            if (marker == lastMarker) stableTicks++ else stableTicks = 0
            lastMarker = marker
            if ((total == 0 && accepted > 0 && stableTicks >= 6) || (total > 0 && stableTicks >= 6 && accepted > 0)) {
                log("MasterDNS DNS settle reached stable state • progress=$scanned/$total • healthy=$accepted")
                return
            }
            Thread.sleep(250L)
        }
        val lines = StormDnsRuntimeLog.read()
        val parsedProgress = stormDnsMtuProgress(lines)
        val cachedProgress = StormDnsRuntimeLog.progressSnapshot()
        val scanned = maxOf(cachedProgress.first, parsedProgress.first)
        val total = maxOf(cachedProgress.second, parsedProgress.second)
        val accepted = maxOf(stormDnsAcceptedDnsCount(lines), StormDnsRuntimeLog.acceptedResolversSnapshot().size)
        log("MasterDNS DNS settle window finished • creating VPN with progress=$scanned/$total • healthy=$accepted")
    }



    private fun isLocalPortAvailable(port: Int): Boolean {
        return runCatching { ServerSocket(port).use { true } }.getOrDefault(false)
    }

    private fun chooseStormDnsBridgePort(stormDnsSocksPort: Int): Int {
        val candidates = listOf(stormDnsSocksPort + 8, 18088, 18098, 18108, 10818, 10828)
            .filter { it in 1024..65535 && it != stormDnsSocksPort }
        return candidates.firstOrNull { isLocalPortAvailable(it) }
            ?: ServerSocket(0).use { it.localPort }
    }

    private fun waitForLocalTcpPort(port: Int, timeoutMs: Long): Boolean {
        val startedAt = System.currentTimeMillis()
        while (running && System.currentTimeMillis() - startedAt < timeoutMs) {
            val ok = runCatching {
                Socket().use { socket ->
                    runCatching { protect(socket) }
                    socket.tcpNoDelay = true
                    socket.connect(InetSocketAddress("127.0.0.1", port), 300)
                }
                true
            }.getOrDefault(false)
            if (ok) return true
            Thread.sleep(120L)
        }
        return false
    }

    private fun countStormDnsResolverLines(resolversText: String): Int = normalizeStormDnsRuntimeResolvers(resolversText).size


    private fun normalizeStormDnsRuntimeResolvers(resolversText: String): List<String> {
        return resolversText.replace("﻿", "")
            .lineSequence()
            .map { raw ->
                raw.trim()
                    .substringBefore("#")
                    .substringBefore("//")
                    .trim()
                    .trim('*', ',', ';', ' ', '\t')
            }
            .filter { it.isNotBlank() }
            .map { endpoint ->
                val clean = endpoint.removePrefix("[").substringBefore("]").trim()
                when {
                    clean.contains(":") -> clean
                    Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(clean) -> "$clean:53"
                    else -> clean
                }
            }
            .distinct()
            .toList()
    }

    private fun stormDnsStartupResolverText(resolversText: String, publicPrefs: android.content.SharedPreferences): String {
        // Runtime must be byte/line compatible with Termux/Windows: no resolver
        // normalization, no de-duplication, no successful-resolver reordering.
        val runtimeText = resolversText.replace("﻿", "").trimEnd().let { if (it.isBlank()) "" else it + "\n" }
        val count = countStormDnsResolverLines(runtimeText)
        publicPrefs.edit()
            .putString("stormDnsStatus", "MasterDNS resolver file • $count resolver(s)")
            .putString("stormDnsResolverScanStatus", "MasterDNS resolver file • $count resolver(s)")
            .remove("stormDnsLastSessionResolver")
            .remove("stormDnsSessionSuccessResolvers")
            .apply()
        log("MasterDNS default core startup: resolver file preserved exactly • parsedResolvers=$count • app-side reordering=OFF")
        return runtimeText
    }

    private fun extractStormDnsTomlInt(config: String, key: String): Int? {
        return Regex("""(?m)^\s*""" + Regex.escape(key) + """\s*=\s*(\d+)""").find(config)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun queueStormDnsResolverUpdate(publicPrefs: android.content.SharedPreferences, cleanResolvers: String, reason: String) {
        val count = countStormDnsResolverLines(cleanResolvers)
        publicPrefs.edit()
            .putString("stormDnsPendingResolversText", cleanResolvers)
            .putString("stormDnsStatus", "MasterDNS queued healthy DNS update • $count resolver(s)")
            .apply()
        log("MasterDNS resolver update queued; $reason • lines=$count")
    }

    private fun flushQueuedStormDnsResolverUpdate(publicPrefs: android.content.SharedPreferences, mgr: ProcessCoreManager, socksPort: Int) {
        val pending = publicPrefs.getString("stormDnsPendingResolversText", "").orEmpty().replace("﻿", "").trim()
        if (pending.isBlank()) return
        if (!waitForLocalTcpPort(socksPort, 1_500L)) {
            log("MasterDNS pending resolver update kept queued; SOCKS5 127.0.0.1:$socksPort not ready yet")
            return
        }
        runCatching {
            mgr.updateStormDnsResolvers(pending)
            publicPrefs.edit()
                .remove("stormDnsPendingResolversText")
                .putString("stormDnsStatus", "MasterDNS healthy DNS applied • ${countStormDnsResolverLines(pending)} resolver(s)")
                .apply()
            log("MasterDNS queued resolver update applied after SOCKS ready • lines=${countStormDnsResolverLines(pending)}")
        }.onFailure { e ->
            log("MasterDNS queued resolver update apply failed", e)
        }
    }

    private fun clearStormDnsHealthyRuntimeCache() {
        StormDnsRuntimeLog.clear(this)
        val runtimeDir = java.io.File(filesDir, "stormdns-runtime")
        runtimeDir.listFiles()?.forEach { file ->
            val name = file.name.lowercase()
            if (name.contains("success") || name.contains("mtu") || name.contains("healthy")) runCatching { file.delete() }
        }
        getSharedPreferences("simorgh_public_state", Context.MODE_PRIVATE or Context.MODE_MULTI_PROCESS).edit()
            .putInt("stormDnsResolverValidCount", 0)
            .putInt("stormDnsResolverScanned", 0)
            .putString("stormDnsHealthyResolversText", "")
            .putString("stormDnsResolverScanStatus", "Healthy DNS cache cleared")
            .commit()
    }


    private fun stormDnsVpnBridgeConfig(localSocksPort: Int, stormDnsSocksPort: Int, localDnsPort: Int): String = """
        {
          "log": { "loglevel": "warning" },
          "dns": {
            "servers": [
              { "address": "127.0.0.1", "port": $localDnsPort },
              "1.1.1.1",
              "8.8.8.8"
            ],
            "queryStrategy": "UseIPv4"
          },
          "inbounds": [
            {
              "tag": "socks-in",
              "listen": "127.0.0.1",
              "port": $localSocksPort,
              "protocol": "socks",
              "settings": { "auth": "noauth", "udp": true },
              "sniffing": {
                "enabled": true,
                "destOverride": ["http", "tls", "quic"],
                "routeOnly": false
              }
            }
          ],
          "outbounds": [
            {
              "tag": "stormdns-socks-out",
              "protocol": "socks",
              "settings": { "servers": [ { "address": "127.0.0.1", "port": $stormDnsSocksPort } ] }
            },
            { "tag": "dns-out", "protocol": "dns", "settings": {} }
          ],
          "routing": {
            "domainStrategy": "AsIs",
            "rules": [
              { "type": "field", "network": "tcp,udp", "port": "53", "outboundTag": "dns-out" },
              { "type": "field", "network": "udp", "port": "443", "outboundTag": "stormdns-socks-out" },
              { "type": "field", "network": "tcp,udp", "outboundTag": "stormdns-socks-out" }
            ]
          }
        }
    """.trimIndent()


    private fun stormDnsVpnBridgeFallbackConfig(localSocksPort: Int, stormDnsSocksPort: Int): String = """
        {
          "log": { "loglevel": "warning" },
          "dns": {
            "servers": ["1.1.1.1", "8.8.8.8"],
            "queryStrategy": "UseIPv4"
          },
          "inbounds": [
            {
              "tag": "socks-in",
              "listen": "127.0.0.1",
              "port": $localSocksPort,
              "protocol": "socks",
              "settings": { "auth": "noauth", "udp": true },
              "sniffing": {
                "enabled": true,
                "destOverride": ["http", "tls", "quic"],
                "routeOnly": false
              }
            }
          ],
          "outbounds": [
            {
              "tag": "stormdns-socks-out",
              "protocol": "socks",
              "settings": { "servers": [ { "address": "127.0.0.1", "port": $stormDnsSocksPort } ] }
            },
            { "tag": "direct-out", "protocol": "freedom", "settings": {} }
          ],
          "routing": {
            "domainStrategy": "AsIs",
            "rules": [
              { "type": "field", "network": "udp", "port": "53", "outboundTag": "direct-out" },
              { "type": "field", "network": "udp", "port": "443", "outboundTag": "stormdns-socks-out" },
              { "type": "field", "network": "tcp,udp", "outboundTag": "stormdns-socks-out" }
            ]
          }
        }
    """.trimIndent()


    private fun updateStormDnsResolvers(resolversText: String) {
        val clean = resolversText.replace("﻿", "").trim()
        val publicPrefs = getSharedPreferences("simorgh_public_state", Context.MODE_PRIVATE or Context.MODE_MULTI_PROCESS)
        val count = countStormDnsResolverLines(clean)
        if (clean.isBlank()) {
            log("MasterDNS resolver update ignored: blank payload")
            return
        }
        val socksPort = publicPrefs.getInt("stormDnsSocksPort", 18000).coerceIn(1024, 65535)
        val coreReady = running && core != null && waitForLocalTcpPort(socksPort, 250L)
        if (!coreReady) {
            queueStormDnsResolverUpdate(publicPrefs, clean, "core/SOCKS5 127.0.0.1:$socksPort not ready")
            return
        }
        runCatching {
            core?.updateStormDnsResolvers(clean) ?: error("MasterDNS core manager is not available")
            publicPrefs.edit()
                .remove("stormDnsPendingResolversText")
                .putString("stormDnsStatus", "MasterDNS healthy DNS updated • $count resolver(s)")
                .apply()
            log("MasterDNS resolver list updated while running • lines=$count")
        }.onFailure { e ->
            queueStormDnsResolverUpdate(publicPrefs, clean, "live update failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }


    private fun startStormDns(configToml: String, runMode: String, serverConfigToml: String, resolversText: String) {
        val mode = if (runMode.trim().lowercase(Locale.US) == "vpn") "vpn" else "proxy"
        val cleanConfig = configToml.replace("﻿", "").trim()
        val cleanResolvers = resolversText.replace("﻿", "").trim()
        val resolverCount = countStormDnsResolverLines(cleanResolvers)
        val publicPrefs = getSharedPreferences("simorgh_public_state", Context.MODE_PRIVATE or Context.MODE_MULTI_PROCESS)
        log("MasterDNS client start requested • mode=$mode • clientConfigChars=${cleanConfig.length} • resolversChars=${cleanResolvers.length} • resolverCount=$resolverCount")
        if (cleanConfig.isBlank()) {
            publicPrefs.edit()
                .putBoolean("stormDnsConnecting", false)
                .putBoolean("stormDnsConnected", false)
                .putString("stormDnsStatus", "MasterDNS config is blank")
                .putString("status", "MasterDNS config is blank")
                .putString("activeMode", "idle")
                .apply()
            stopSelf()
            return
        }
        synchronized(stormDnsStartLock) {
            val alreadyActiveStormDns = running &&
                publicPrefs.getString("activeMode", "").orEmpty() == "stormdns" &&
                (publicPrefs.getBoolean("stormDnsConnecting", false) || publicPrefs.getBoolean("stormDnsConnected", false))
            if (stormDnsStartInProgress || alreadyActiveStormDns) {
                log("MasterDNS start ignored: another MasterDNS start is already preparing or running the SOCKS5/VPN path")
                publicPrefs.edit()
                    .putString("stormDnsStatus", "MasterDNS start already in progress • waiting for current start")
                    .putString("status", "MasterDNS start already in progress")
                    .apply()
                return
            }
            stormDnsStartInProgress = true
        }

        stopVpn(clearSimpleState = true, allowProcessExit = false)
        val generation = lifecycleGeneration.incrementAndGet()
        stopGuard.set(false)
        running = true

        runCatching {
            startForeground(
                1001,
                NotificationHelper.vpn(this, "SIMORGH MasterDNS", "Starting MasterDNS ${mode.uppercase()} mode", connected = true)
            )
            log("Foreground notification started for MasterDNS")
            startNotificationSpeedLoop("SIMORGH MasterDNS")
        }.onFailure { e ->
            synchronized(stormDnsStartLock) { stormDnsStartInProgress = false }
            log("MasterDNS startForeground failed", e)
            stopSelf()
            return
        }

        thread(name = "RKhVPN-stormdns-core") {
            try {
                runCatching {
                    publicPrefs.edit()
                        .putBoolean("stormDnsConnecting", true)
                        .putBoolean("stormDnsConnected", false)
                        .putBoolean("stormDnsResolverScanning", true)
                        .putString("stormDnsResolverScanStatus", "MasterDNS default core scan • waiting for 127.0.0.1:18000")
                        .putString("stormDnsStatus", "Starting MasterDNS default core scan • waiting for 127.0.0.1:18000")
                        .putString("status", "MasterDNS starting")
                        .putString("activeMode", "stormdns")
                        .putLong("startedAt", System.currentTimeMillis())
                        .commit()

                    val mgr = ProcessCoreManager(this)
                    core = mgr
                    val configSocksPort = extractStormDnsTomlInt(cleanConfig, "LISTEN_PORT")?.coerceIn(1024, 65535)
                    val socksPort = (configSocksPort ?: publicPrefs.getInt("stormDnsSocksPort", 18000)).coerceIn(1024, 65535)
                    val requestedLocalDnsPort = extractStormDnsTomlInt(cleanConfig, "LOCAL_DNS_PORT") ?: publicPrefs.getInt("stormDnsLocalDnsPort", 5353)
                    val localDnsPort = if (requestedLocalDnsPort < 1024) 5353 else requestedLocalDnsPort.coerceIn(1024, 65535)
                    publicPrefs.edit()
                        .putInt("stormDnsSocksPort", socksPort)
                        .putInt("stormDnsLocalDnsPort", localDnsPort)
                        .apply()
                    if (requestedLocalDnsPort != localDnsPort) {
                        log("MasterDNS local DNS port changed from privileged $requestedLocalDnsPort to safe $localDnsPort")
                    }
                    log("MasterDNS startup ports resolved from client config • SOCKS5=127.0.0.1:$socksPort • localDns=$localDnsPort")
                    val runtimeResolvers = stormDnsStartupResolverText(cleanResolvers, publicPrefs)
                    val runtimeResolverCount = countStormDnsResolverLines(runtimeResolvers)
                    log("MasterDNS starting core with default full resolver list • runtimeResolvers=$runtimeResolverCount • originalResolvers=$resolverCount")
                    if (!isCurrentLifecycle(generation)) {
                        log("MasterDNS start aborted before native core start because a newer start/stop was requested")
                        return@runCatching
                    }
                    val startedSocksPort = mgr.startStormDnsAgent(cleanConfig, resolversText = runtimeResolvers, socksPort = socksPort)
                    log("MasterDNS SOCKS5 local endpoint ready: 127.0.0.1:$startedSocksPort")
                    if (!isCurrentLifecycle(generation)) {
                        log("MasterDNS start aborted after SOCKS5 became ready because a newer start/stop was requested")
                        runCatching { mgr.stop() }
                        return@runCatching
                    }
                    publicPrefs.edit()
                        .remove("stormDnsPendingResolversText")
                        .putString("stormDnsStatus", "MasterDNS SOCKS5 127.0.0.1:$startedSocksPort ready • creating VPN tunnel")
                        .commit()
                    if (mode == "vpn") {
                        log("Building Android VPN TUN interface for MasterDNS after SOCKS5 127.0.0.1:$startedSocksPort is ready")
                        val builder = Builder()
                            .setSession("SIMORGH MasterDNS")
                            .addAddress("172.19.0.8", 30)
                            .addRoute("0.0.0.0", 0)
                            .allowFamily(android.system.OsConstants.AF_INET)
                            .addDnsServer("1.1.1.1")
                            .addDnsServer("8.8.8.8")
                            .setMtu(1500)
                        applyTunnelAppPolicy(builder, "MasterDNS")
                        if (isStormDnsRouteBypassMode()) {
                            try {
                                builder.addDisallowedApplication(applicationContext.packageName)
                                log("MasterDNS own UID bypass enabled: ${applicationContext.packageName} is excluded from VPN routes so the embedded core can initialize sessions directly")
                            } catch (t: Throwable) {
                                log("MasterDNS own UID bypass unavailable: ${t.message ?: t.javaClass.simpleName}")
                            }
                        }

                        val fd = builder.establish() ?: error("Builder.establish returned null. VPN permission may be missing.")
                        val directTunFdNumber = fd.detachFd()
                        tun = null
                        detachedTunFd = directTunFdNumber
                        if (!isCurrentLifecycle(generation)) {
                            log("MasterDNS start aborted after TUN establish because a newer start/stop was requested")
                            closeDetachedTunFd("stormdns-start-abort")
                            return@runCatching
                        }
                        log("Prepared detached MasterDNS TUN fd for tun2proxy JNI bridge: fd=$directTunFdNumber")
                        // Direct MasterDNS VPN path:
                        // MasterDNS itself creates the final local SOCKS5 endpoint on 127.0.0.1:18000.
                        // Do not insert an extra Xray bridge here; attach Android TUN → tun2proxy JNI → MasterDNS.
                        if (!waitForLocalTcpPort(startedSocksPort, 25_000L)) {
                            log("MasterDNS SOCKS5 127.0.0.1:$startedSocksPort readiness check was transiently unreachable after TUN establish; starting tun2proxy anyway because the native core already reported the listener")
                        }
                        if (!isCurrentLifecycle(generation)) {
                            log("MasterDNS start aborted before tun2proxy because a newer start/stop was requested")
                            return@runCatching
                        }
                        detachedTunFd = null
                        mgr.startTun2ProxyBridge(directTunFdNumber, startedSocksPort, mtu = 1500, dnsStrategy = Tun2ProxyBridge.DNS_OVER_TCP)
                        log("MasterDNS TUN fd ownership transferred to ProcessCore safe fd-close stop; native closeFdOnDrop=false • fd=$directTunFdNumber • DNS over TCP through MasterDNS SOCKS")
                        if (!isCurrentLifecycle(generation)) {
                            log("MasterDNS start aborted after tun2proxy because a newer start/stop was requested")
                            runCatching { mgr.stop() }
                            return@runCatching
                        }
                        log("MasterDNS VPN path started: Android TUN → tun2proxy JNI bridge → MasterDNS SOCKS5 127.0.0.1:$startedSocksPort")
                    } else {
                        log("MasterDNS proxy path started: local SOCKS5 127.0.0.1:$startedSocksPort")
                    }
                    getSharedPreferences("rkh_vpn_state", MODE_PRIVATE).edit().putBoolean("serviceConnected", mode == "vpn").commit()
                    publicPrefs.edit()
                        .putBoolean("stormDnsConnecting", false)
                        .putBoolean("stormDnsConnected", true)
                        .putBoolean("stormDnsResolverScanning", false)
                        .putString("stormDnsResolverScanStatus", "MasterDNS SOCKS5 127.0.0.1:$startedSocksPort ready")
                        .putString("stormDnsStatus", if (mode == "vpn") "MasterDNS VPN connected" else "MasterDNS proxy connected • SOCKS5 127.0.0.1:$startedSocksPort")
                        .putString("status", "MasterDNS connected")
                        .putString("activeMode", "stormdns")
                        .putLong("startedAt", System.currentTimeMillis())
                        .commit()
                }.onFailure { e ->
                    if (isCurrentLifecycle(generation)) {
                        log("MasterDNS start failed", e)
                        publicPrefs.edit()
                            .putBoolean("stormDnsConnecting", false)
                            .putBoolean("stormDnsConnected", false)
                            .putBoolean("stormDnsResolverScanning", false)
                            .putString("stormDnsResolverScanStatus", "MasterDNS core start failed")
                            .putString("stormDnsStatus", "MasterDNS start failed: ${e.message ?: e.javaClass.simpleName}")
                            .putString("status", "MasterDNS start failed")
                            .putString("activeMode", "idle")
                            .putLong("startedAt", 0L)
                            .apply()
                        runCatching {
                            startForeground(
                                1001,
                                NotificationHelper.vpn(this, "SIMORGH MasterDNS", "Start failed: ${e.message ?: e.javaClass.simpleName}", connected = false)
                            )
                        }
                        stopVpn()
                        stopSelf()
                    } else {
                        log("Stale MasterDNS start failure ignored after newer lifecycle", e)
                    }
                }
            } finally {
                synchronized(stormDnsStartLock) { stormDnsStartInProgress = false }
            }
        }
    }

    private fun startNipo(configYaml: String) {
        val cleanConfig = configYaml.replace("﻿", "").trim()
        log("NipoVPN start requested • configChars=${cleanConfig.length} • socks5AgentPort=9992")
        stopVpn(clearSimpleState = true, allowProcessExit = false)
        lifecycleGeneration.incrementAndGet()
        // stopVpn() intentionally leaves stopGuard=true after cleanup to make duplicate
        // disconnects safe. Nipo starts after that cleanup in the same service instance,
        // so the next real Disconnect must be allowed to close TUN/Xray/Nipo/tun2proxy.
        stopGuard.set(false)
        running = true

        runCatching {
            startForeground(
                1001,
                NotificationHelper.vpn(this, "SIMORGH NipoVPN", "Connecting: NipoVPN VPN MODE", connected = true)
            )
            log("Foreground notification started for NipoVPN")
            startNotificationSpeedLoop("SIMORGH NipoVPN")
        }.onFailure { e ->
            log("NipoVPN startForeground failed", e)
            stopSelf()
            return
        }

        thread(name = "RKhVPN-nipo-core") {
            runCatching {
                val publicPrefs = getSharedPreferences("simorgh_public_state", Context.MODE_PRIVATE or Context.MODE_MULTI_PROCESS)
                publicPrefs.edit()
                    .putBoolean("nipoConnecting", true)
                    .putBoolean("nipoConnected", false)
                    .putString("nipoStatus", "Starting NipoVPN agent on SOCKS5 127.0.0.1:9992...")
                    .putString("status", "NipoVPN starting")
                    .putString("activeMode", "nipo")
                    .putLong("startedAt", System.currentTimeMillis())
                    .commit()

                log("Building Android VPN TUN interface for NipoVPN")
                val builder = Builder()
                    .setSession("SIMORGH NipoVPN")
                    .addAddress("172.19.0.6", 30)
                    .addRoute("0.0.0.0", 0)
                    .allowFamily(android.system.OsConstants.AF_INET)
                    .addDnsServer("8.8.8.8")
                    .addDnsServer("8.8.4.4")
                    .setMtu(1500)

                applyTunnelAppPolicy(builder, "NipoVPN")
                val fd = builder.establish() ?: error("Builder.establish returned null. VPN permission may be missing.")
                tun = fd
                log("NipoVPN TUN established. fd=${fd.fd}. mtu=1500")
                if (!running) {
                    log("NipoVPN start aborted after TUN establish because disconnect was requested")
                    runCatching { fd.close() }
                    return@runCatching
                }

                val directTunFdNumber = fd.detachFd()
                tun = null
                detachedTunFd = directTunFdNumber
                log("Prepared detached NipoVPN TUN fd for tun2proxy JNI bridge: fd=$directTunFdNumber")

                val mgr = ProcessCoreManager(this)
                core = mgr
                val nipoSocksPort = mgr.startNipoAgent(cleanConfig, 9992)
                if (!running) {
                    log("NipoVPN start aborted after agent start because disconnect was requested")
                    runCatching { mgr.stop() }
                    return@runCatching
                }
                val config = XrayBinaryConfigBuilder.nipoBridgeConfig(localSocksPort = 18188, nipoSocksPort = nipoSocksPort)
                log("NipoVPN Xray bridge config generated. Flow: Android TUN → tun2proxy → Xray 127.0.0.1:18188 → Nipo SOCKS5 127.0.0.1:$nipoSocksPort")
                val socksPort = mgr.startXray(config)
                if (!running) {
                    log("NipoVPN start aborted after Xray start because disconnect was requested")
                    runCatching { mgr.stop() }
                    return@runCatching
                }
                log("About to start tun2proxy for NipoVPN • tunFd=$directTunFdNumber • socksPort=$socksPort")
                detachedTunFd = null
                mgr.startTun2ProxyBridge(directTunFdNumber, socksPort, mtu = 1500)
                log("NipoVPN TUN fd ownership transferred to ProcessCore safe fd-close stop; native closeFdOnDrop=false • fd=$directTunFdNumber")
                if (!running) {
                    log("NipoVPN start aborted after tun2proxy start because disconnect was requested")
                    runCatching { mgr.stop() }
                    return@runCatching
                }
                getSharedPreferences("rkh_vpn_state", MODE_PRIVATE).edit().putBoolean("serviceConnected", true).commit()
                publicPrefs.edit()
                    .putBoolean("nipoConnecting", false)
                    .putBoolean("nipoConnected", true)
                    .putString("nipoStatus", "NipoVPN connected")
                    .putString("status", "NipoVPN connected")
                    .putString("activeMode", "nipo")
                    .putLong("startedAt", System.currentTimeMillis())
                    .commit()
                log("NipoVPN started successfully. Traffic path: Android VpnService TUN → tun2proxy → Xray SOCKS inbound → Xray SOCKS outbound → NipoVPN agent")
            }.onFailure { e ->
                log("NipoVPN start failed", e)
                getSharedPreferences("simorgh_public_state", Context.MODE_PRIVATE or Context.MODE_MULTI_PROCESS).edit()
                    .putBoolean("nipoConnecting", false)
                    .putBoolean("nipoConnected", false)
                    .putString("nipoStatus", "NipoVPN start failed: ${e.message ?: e.javaClass.simpleName}")
                    .putString("status", "NipoVPN start failed")
                    .putString("activeMode", "idle")
                    .putLong("startedAt", 0L)
                    .commit()
                runCatching {
                    startForeground(
                        1001,
                        NotificationHelper.vpn(this, "SIMORGH NipoVPN", "Start failed: ${e.message ?: e.javaClass.simpleName}", connected = false)
                    )
                }
                stopVpn()
                stopSelf()
            }
        }
    }

    @Synchronized
    private fun switchXrayOnly(raw: String, name: String) {
        val simpleMode = name.startsWith("SIMORGH Simple")
        val cleanRaw = raw.replace("﻿", "").trim()
        val serverLessMode = simpleMode && (cleanRaw.startsWith("{") || cleanRaw.contains("Serverless", ignoreCase = true))
        if (cleanRaw.isBlank()) {
            log("Simple Xray-only switch skipped: raw config is blank")
            return
        }
        if (!simpleMode || serverLessMode) {
            log("Xray-only switch is only for normal Simple configs; falling back to full start • simpleMode=$simpleMode • serverLessMode=$serverLessMode")
            start(raw, name)
            return
        }
        if (!xraySwitchGuard.compareAndSet(false, true)) {
            log("Duplicate Simple Xray-only switch ignored safely; previous switch is still running")
            return
        }

        val label = name.substringAfter("SIMORGH Simple •", "Simple XRAY").trim().ifBlank { "Simple XRAY" }
        val publicPrefs = getSharedPreferences("simorgh_public_state", Context.MODE_PRIVATE or Context.MODE_MULTI_PROCESS)
        publicPrefs.edit()
            .putBoolean("simpleConnecting", true)
            .putBoolean("simpleConnected", true)
            .putString("simpleStatus", "Switching Xray only: $label")
            .putString("status", "Simple Xray switching")
            .putString("activeMode", "simple_xray")
            .commit()
        runCatching {
            startForeground(
                1001,
                NotificationHelper.vpn(this, "SIMORGH Simple", "Switching Xray: $label", connected = true)
            )
        }.onFailure { log("Simple Xray-only switch notification update failed safely", it) }

        thread(name = "RKhVPN-simple-xray-only-switch") {
            try {
                val corePrefs = getSharedPreferences("rkh_vpn_state", MODE_PRIVATE)
                val deadline = System.currentTimeMillis() + 10_000L
                var mgr = core
                while (System.currentTimeMillis() < deadline && (mgr == null || !running || !corePrefs.getBoolean("serviceConnected", false))) {
                    Thread.sleep(150L)
                    mgr = core
                }
                val readyMgr = mgr
                if (readyMgr == null || !running || !corePrefs.getBoolean("serviceConnected", false)) {
                    publicPrefs.edit()
                        .putBoolean("simpleConnecting", false)
                        .putBoolean("simpleConnected", running)
                        .putString("simpleStatus", "Xray switch deferred: VPN tunnel not ready yet")
                        .putString("status", "Simple Xray switch deferred")
                        .putString("activeMode", if (running) "simple_xray" else "idle")
                        .commit()
                    log("Simple Xray-only switch deferred because existing VPN/tun2proxy was not ready; full VPN restart was intentionally not used")
                    return@thread
                }

                val switchGeneration = lifecycleGeneration.get()
                val config = XrayBinaryConfigBuilder.socksConfigFromRaw(cleanRaw, 18188, forceGoogleDns = true)
                log("Simple Xray-only switch requested • label=$label • rawChars=${cleanRaw.length} • configChars=${config.length}")
                val socksPort = readyMgr.switchXrayOnly(config)
                if (running && lifecycleGeneration.get() == switchGeneration) {
                    getSharedPreferences("rkh_vpn_state", MODE_PRIVATE).edit().putBoolean("serviceConnected", true).commit()
                    markPublicCoreConnected(name)
                    publicPrefs.edit()
                        .putBoolean("simpleConnecting", false)
                        .putBoolean("simpleConnected", true)
                        .putString("simpleStatus", "Simple XRAY switched: $label")
                        .putString("status", "Simple XRAY connected")
                        .putString("activeMode", "simple_xray")
                        .commit()
                    runCatching {
                        startForeground(
                            1001,
                            NotificationHelper.vpn(this, "SIMORGH Simple", currentSimpleNotificationText(fallbackServerName = "SIMORGH Simple • $label"), connected = true)
                        )
                    }.onFailure { log("Simple Xray-only switch success notification update failed safely", it) }
                    log("Simple Xray-only switch completed • label=$label • socksPort=$socksPort • VPN/tun2proxy kept alive")
                } else {
                    log("Simple Xray-only switch completed but state update skipped because lifecycle changed")
                }
            } catch (e: Throwable) {
                publicPrefs.edit()
                    .putBoolean("simpleConnecting", false)
                    .putBoolean("simpleConnected", running)
                    .putString("simpleStatus", "Xray switch failed: ${e.message ?: e.javaClass.simpleName}")
                    .putString("status", "Simple Xray switch failed")
                    .putString("activeMode", if (running) "simple_xray" else "idle")
                    .putString("lastError", "Xray switch failed: ${e.message ?: e.javaClass.simpleName}")
                    .commit()
                log("Simple Xray-only switch failed; VPN/tun2proxy were not restarted", e)
            } finally {
                xraySwitchGuard.set(false)
            }
        }
    }

    @Synchronized
    private fun start(raw: String, name: String) {
        if (raw.isBlank()) {
            log("Start failed: raw config is blank")
            stopSelf()
            return
        }

        val simpleMode = name.startsWith("SIMORGH Simple")
        val fragmentMode = name.startsWith("SIMORGH Fragment")
        val cleanRaw = raw.replace("﻿", "").trim()
        val serverLessMode = simpleMode && cleanRaw.contains("Serverless", ignoreCase = true)
        log("Start requested • name=${name.ifBlank { "blank" }} • simpleMode=$simpleMode • serverLessMode=$serverLessMode • rawChars=${cleanRaw.length} • rawJson=${cleanRaw.startsWith("{")} • hasInbounds=${cleanRaw.contains("\"inbounds\"")} • hasOutbounds=${cleanRaw.contains("\"outbounds\"")}")
        stopVpn(clearSimpleState = !simpleMode, allowProcessExit = false)
        val generation = lifecycleGeneration.incrementAndGet()
        stopGuard.set(false)
        running = true

        runCatching {
            startForeground(
                1001,
                NotificationHelper.vpn(this, when {
                    name.startsWith("SIMORGH Simple") -> "SIMORGH Simple"
                    name.startsWith("SIMORGH Fragment") -> "SIMORGH Fragment"
                    else -> "SIMORGH Private"
                }, if (name.isBlank()) "Connecting" else "Connecting: $name", connected = true)
            )
            log("Foreground notification started")
            startNotificationSpeedLoop(name)
        }.onFailure { e ->
            log("startForeground failed", e)
            stopSelf()
            return
        }

        thread(name = "RKhVPN-binary-core") {
            runCatching {
                log("Building Android VPN TUN interface")
                val builder = Builder()
                    .setSession("SIMORGH Private")
                    .addAddress("172.19.0.2", 30)
                    .addRoute("0.0.0.0", 0)
                    .allowFamily(android.system.OsConstants.AF_INET)
                    .setMtu(1500)

                if (simpleMode && !serverLessMode) {
                    builder.addDnsServer("8.8.8.8")
                    builder.addDnsServer("8.8.4.4")
                    log("Simple normal Google DNS active • 8.8.8.8, 8.8.4.4")
                } else {
                    builder.addDnsServer("1.1.1.1")
                    builder.addDnsServer("8.8.8.8")
                }

                if (serverLessMode) {
                    // z23: Do not add the experimental IPv6 TUN route here. The last logs
                    // showed Xray could open outbound TCP connections, so the next fix is
                    // architecture-level: Android TUN -> tun2proxy -> Xray mixed inbound.
                    log("ServerLess IPv4-only Android TUN route active • no experimental IPv6 route")
                }

                applyTunnelAppPolicy(builder, when {
                    simpleMode -> "Simple"
                    fragmentMode -> "Fragment"
                    else -> "VPN"
                })
                val fd = builder.establish() ?: error("Builder.establish returned null. VPN permission may be missing.")
                val directTunFdNumber = fd.detachFd()
                tun = null
                detachedTunFd = directTunFdNumber
                log("TUN established and detached for native tun2proxy. fd=$directTunFdNumber. Android key icon should be visible now. mtu=1500")
                if (!isCurrentLifecycle(generation)) {
                    log("Simple/Private start aborted after TUN establish because a newer start/stop was requested")
                    closeDetachedTunFd("simple-fragment-start-abort")
                    return@runCatching
                }
                // Simple/Fragment now use a detached Android TUN fd -> tun2proxy path.
                // detachFd() is required for native TUN consumers to avoid Android fdsan/double-close aborts on disconnect.
                log("Prepared detached Simple/Fragment TUN fd for tun2proxy JNI bridge: fd=$directTunFdNumber")

                val mgr = ProcessCoreManager(this)
                core = mgr
                if (!isCurrentLifecycle(generation)) {
                    log("Simple/Private start aborted before core start because a newer start/stop was requested")
                    runCatching { mgr.stop() }
                    return@runCatching
                }
                if (serverLessMode) {
                    val config = XrayBinaryConfigBuilder.socksConfigFromRaw(raw, 18188)
                    val hasTunInbound = config.contains("\"protocol\": \"tun\"") || config.contains("\"protocol\":\"tun\"")
                    val hasMixedInbound = config.contains("\"protocol\": \"mixed\"") || config.contains("\"protocol\":\"mixed\"")
                    val hasFakeDns = config.contains("\"address\": \"fakedns\"", ignoreCase = true) || config.contains("\"address\":\"fakedns\"", ignoreCase = true)
                    val hasDnsOutPort53 = Regex(""""outboundTag"\s*:\s*"dns-out"[\s\S]{0,120}"port"\s*:\s*"?53"?""").containsMatchIn(config)
                    val hasTcpFragment = config.contains("\"outboundTag\": \"tcp-fragment\"") || config.contains("\"tag\": \"tcp-fragment\"")
                    val cloudflareDohRuntime = config.contains("https://cloudflare-dns.com", ignoreCase = true)
                    val mixedFakeDnsOverride = Regex("\\\"protocol\\\"\\s*:\\s*\\\"mixed\\\"[\\s\\S]{0,900}\\\"destOverride\\\"\\s*:\\s*\\[[\\s\\S]{0,250}\\\"fakedns\\\"").containsMatchIn(config)
                    val realDnsForTun2Proxy = !hasFakeDns && config.contains("\"address\": \"localhost\"")
                    log("ServerLess upstream tun2proxy mode active • useTun2Proxy=true • xrayInbound=mixed-127.0.0.1:18188 • hasTunInbound=$hasTunInbound • hasMixedInbound=$hasMixedInbound • fakeDnsServer=$hasFakeDns • mixedFakeDnsOverride=$mixedFakeDnsOverride • realDnsForTun2Proxy=$realDnsForTun2Proxy • cloudflareDohRuntime=$cloudflareDohRuntime • dohReplacedWithLocalhost=false • dnsOutPort53=$hasDnsOutPort53 • tcpFragment=$hasTcpFragment • ipv6TunRoute=false • mtu=1500")
                    val socksPort = mgr.startXray(config)
                    if (!isCurrentLifecycle(generation)) {
                        log("ServerLess start aborted after Xray start because a newer start/stop was requested")
                        runCatching { mgr.stop() }
                        return@runCatching
                    }
                    log("About to start tun2proxy for ${if (serverLessMode) "Simple ServerLess" else if (fragmentMode) "Fragment" else "Simple"} • tunFd=$directTunFdNumber • socksPort=$socksPort")
                    detachedTunFd = null
                    mgr.startTun2ProxyBridge(directTunFdNumber, socksPort, mtu = 1500)
                    log("ServerLess/Fragment TUN fd ownership transferred to ProcessCore safe fd-close stop; native closeFdOnDrop=false • fd=$directTunFdNumber")
                    if (!isCurrentLifecycle(generation)) {
                        log("ServerLess start aborted after tun2proxy because a newer start/stop was requested")
                        runCatching { mgr.stop() }
                        return@runCatching
                    }
                    getSharedPreferences("rkh_vpn_state", MODE_PRIVATE).edit().putBoolean("serviceConnected", true).commit()
                    markPublicCoreConnected(name)
                    log("VPN binary-core ServerLess started successfully. Traffic should now go: Android VpnService TUN → tun2proxy → Xray mixed inbound → ServerLess routing")
                } else {
                    val config = XrayBinaryConfigBuilder.socksConfigFromRaw(raw, 18188, forceGoogleDns = simpleMode && !serverLessMode)
                    log("Xray SOCKS config generated. Length=${config.length} • hasSocksIn=${config.contains("\"socks-in\"")} • hasHttpIn=${config.contains("\"http-in\"")}")
                    val socksPort = mgr.startXray(config)
                    if (!isCurrentLifecycle(generation)) {
                        log("Simple normal start aborted after Xray start because a newer start/stop was requested")
                        runCatching { mgr.stop() }
                        return@runCatching
                    }
                    log("About to start tun2proxy for ${if (serverLessMode) "Simple ServerLess" else if (fragmentMode) "Fragment" else "Simple"} • tunFd=$directTunFdNumber • socksPort=$socksPort")
                    detachedTunFd = null
                    mgr.startTun2ProxyBridge(directTunFdNumber, socksPort, mtu = 1500)
                    log("Simple/Fragment TUN fd ownership transferred to ProcessCore safe fd-close stop; native closeFdOnDrop=false • fd=$directTunFdNumber")
                    if (!isCurrentLifecycle(generation)) {
                        log("Simple normal start aborted after tun2proxy because a newer start/stop was requested")
                        runCatching { mgr.stop() }
                        return@runCatching
                    }
                    getSharedPreferences("rkh_vpn_state", MODE_PRIVATE).edit().putBoolean("serviceConnected", true).commit()
                    markPublicCoreConnected(name)
                    log("VPN binary-core started successfully. Traffic should now go: Android TUN → tun2proxy → Xray SOCKS → selected config")
                }
            }.onFailure { e ->
                log("VPN start failed", e)
                if (isCurrentLifecycle(generation)) {
                    runCatching {
                        startForeground(
                            1001,
                            NotificationHelper.vpn(this, when {
                            name.startsWith("SIMORGH Simple") -> "SIMORGH Simple"
                            name.startsWith("SIMORGH Fragment") -> "SIMORGH Fragment"
                            else -> "SIMORGH Private"
                        }, "Start failed: ${e.message ?: e.javaClass.simpleName}", connected = false)
                        )
                    }
                    if (fragmentMode) {
                        getSharedPreferences("simorgh_public_state", Context.MODE_PRIVATE or Context.MODE_MULTI_PROCESS).edit()
                            .putBoolean("fragmentConnecting", false)
                            .putBoolean("fragmentConnected", false)
                            .putString("fragmentStatus", "Fragment start failed: ${e.message ?: e.javaClass.simpleName}")
                            .putString("lastError", "Fragment start failed: ${e.message ?: e.javaClass.simpleName}")
                            .putString("status", "Fragment start failed")
                            .putString("activeMode", "idle")
                            .putLong("startedAt", 0L)
                            .commit()
                    }
                    if (simpleMode) {
                        getSharedPreferences("simorgh_public_state", Context.MODE_PRIVATE or Context.MODE_MULTI_PROCESS).edit()
                            .putBoolean("simpleConnecting", false)
                            .putBoolean("simpleConnected", false)
                            .putString("simpleStatus", "Simple start failed: ${e.message ?: e.javaClass.simpleName}")
                            .putString("lastError", "Simple start failed: ${e.message ?: e.javaClass.simpleName}")
                            .putString("status", "Simple start failed")
                            .putString("activeMode", "idle")
                            .putLong("startedAt", 0L)
                            .putLong("downloadKbps", 0L)
                            .putLong("uploadKbps", 0L)
                            .commit()
                    }
                    runCatching { stopVpn() }.onFailure { log("Safe Simple/Private failure stop caught", it) }
                    runCatching { stopSelf() }.onFailure { log("Safe Simple/Private failure stopSelf caught", it) }
                } else {
                    log("Stale Simple/Private start failure ignored because a newer start/stop is active", e)
                }
            }
        }
    }

    private fun currentSimpleNotificationLabel(fallbackServerName: String = "SIMORGH Simple"): String {
        val publicPrefs = getSharedPreferences("simorgh_public_state", Context.MODE_PRIVATE or Context.MODE_MULTI_PROCESS)
        val saved = publicPrefs.getString("simpleBestName", "").orEmpty().trim()
        if (saved.isNotBlank()) return saved
        val extracted = fallbackServerName.substringAfter("SIMORGH Simple •", "").trim()
        return extracted.ifBlank { "Simple XRAY" }
    }

    private fun currentSimpleNotificationText(downKbps: Long? = null, upKbps: Long? = null, fallbackServerName: String = "SIMORGH Simple"): String {
        val label = currentSimpleNotificationLabel(fallbackServerName)
        return if (downKbps != null && upKbps != null) {
            "$label • ↓ ${FormatUtils.kbps(downKbps)}  ↑ ${FormatUtils.kbps(upKbps)}"
        } else {
            "Connected: $label"
        }
    }

    private fun enableBlockingTunForServerLess(fd: ParcelFileDescriptor) {
        runCatching {
            // Xray native TUN is more sensitive than tun2socks to non-blocking Android
            // VPN file descriptors. v2rayNG-style native TUN works best with a blocking
            // fd; keep this only for Simple > ServerLess.
            val current = Os.fcntlInt(fd.fileDescriptor, OsConstants.F_GETFL, 0)
            Os.fcntlInt(fd.fileDescriptor, OsConstants.F_SETFL, current and OsConstants.O_NONBLOCK.inv())
            log("ServerLess TUN fd blocking mode enabled on original fd=${fd.fd}")
        }.onFailure { log("ServerLess TUN fd blocking mode could not be enabled", it) }
    }

    private fun markPublicCoreConnected(serverName: String) {
        val publicPrefs = getSharedPreferences("simorgh_public_state", Context.MODE_PRIVATE or Context.MODE_MULTI_PROCESS)
        val now = System.currentTimeMillis()
        when {
            serverName.startsWith("SIMORGH Simple") -> {
                val label = serverName.substringAfter("SIMORGH Simple •", "").trim().ifBlank { "Simple XRAY" }
                publicPrefs.edit()
                    .putBoolean("simpleConnecting", false)
                    .putBoolean("simpleConnected", true)
                    .putString("simpleStatus", "Simple XRAY connected${if (label == "Simple XRAY") "" else ": $label"}")
                    .putString("status", "Simple XRAY connected")
                    .putString("activeMode", "simple_xray")
                    .putLong("startedAt", now)
                    .commit()
                runCatching {
                    startForeground(
                        1001,
                        NotificationHelper.vpn(this, "SIMORGH Simple", currentSimpleNotificationText(fallbackServerName = serverName), connected = true)
                    )
                }.onFailure { log("Simple connected notification update failed safely", it) }
            }
            serverName.startsWith("SIMORGH Fragment") -> {
                publicPrefs.edit()
                    .putBoolean("fragmentStartInProgress", false)
                    .putBoolean("fragmentConnecting", false)
                    .putBoolean("fragmentConnected", true)
                    .putString("fragmentStatus", "Fragment Connected")
                    .putString("status", "Fragment Connected")
                    .putString("activeMode", "fragment")
                    .putLong("startedAt", now)
                    .commit()
            }
        }
    }

    private fun startNotificationSpeedLoop(serverName: String) {
        val simpleMode = serverName.startsWith("SIMORGH Simple")
        val nipoMode = serverName.startsWith("SIMORGH NipoVPN")
        val stormDnsMode = serverName.startsWith("SIMORGH MasterDNS")
        val fragmentMode = serverName.startsWith("SIMORGH Fragment")
        val publicPrefs = if (simpleMode || nipoMode || stormDnsMode || fragmentMode) getSharedPreferences("simorgh_public_state", Context.MODE_PRIVATE or Context.MODE_MULTI_PROCESS) else null
        notificationThread = thread(name = "RKhVPN-notification-speed") {
            var lastRx = TrafficStats.getTotalRxBytes().coerceAtLeast(0L)
            var lastTx = TrafficStats.getTotalTxBytes().coerceAtLeast(0L)
            var lastAt = System.currentTimeMillis()
            val updateIntervalMs = if (simpleMode || nipoMode || stormDnsMode || fragmentMode) 3_000L else 2_500L
            while (running) {
                try {
                    Thread.sleep(updateIntervalMs)
                    val now = System.currentTimeMillis()
                    val elapsedSeconds = ((now - lastAt).coerceAtLeast(1000L)) / 1000L
                    val rx = TrafficStats.getTotalRxBytes().coerceAtLeast(0L)
                    val tx = TrafficStats.getTotalTxBytes().coerceAtLeast(0L)
                    val down = (((rx - lastRx).coerceAtLeast(0L) * 8L) / 1000L) / elapsedSeconds
                    val up = (((tx - lastTx).coerceAtLeast(0L) * 8L) / 1000L) / elapsedSeconds
                    lastRx = rx
                    lastTx = tx
                    lastAt = now
                    if (simpleMode) {
                        val editor = publicPrefs?.edit()
                            ?.putLong("downloadKbps", down)
                            ?.putLong("uploadKbps", up)
                            ?.putBoolean("simpleConnected", true)
                            ?.putBoolean("simpleConnecting", false)
                            ?.putString("activeMode", "simple_xray")
                            ?.putString("status", "Simple XRAY Connected")
                            ?.putString("simpleStatus", "Simple XRAY Connected")
                        if (down > 0L || up > 0L) {
                            editor?.putLong("simpleLastTrafficAt", System.currentTimeMillis())
                                ?.putBoolean("simpleHadTraffic", true)
                        }
                        editor?.commit()
                    } else if (nipoMode) {
                        publicPrefs?.edit()
                            ?.putLong("downloadKbps", down)
                            ?.putLong("uploadKbps", up)
                            ?.putBoolean("nipoConnected", true)
                            ?.putBoolean("nipoConnecting", false)
                            ?.putString("activeMode", "nipo")
                            ?.putString("status", "NipoVPN Connected")
                            ?.putString("nipoStatus", "NipoVPN Connected")
                            ?.commit()
                    } else if (fragmentMode) {
                        publicPrefs?.edit()
                            ?.putLong("downloadKbps", down)
                            ?.putLong("uploadKbps", up)
                            ?.putBoolean("fragmentStartInProgress", false)
                            ?.putBoolean("fragmentConnected", true)
                            ?.putBoolean("fragmentConnecting", false)
                            ?.putString("activeMode", "fragment")
                            ?.putString("status", "Fragment Connected")
                            ?.putString("fragmentStatus", "Fragment Connected")
                            ?.commit()
                    } else if (stormDnsMode) {
                        publicPrefs?.edit()
                            ?.putLong("downloadKbps", down)
                            ?.putLong("uploadKbps", up)
                            ?.commit()
                    }
                    val notificationText = when {
                        simpleMode -> currentSimpleNotificationText(down, up, serverName)
                        else -> {
                            val label = if (serverName.isBlank()) "Connected" else serverName.take(28)
                            "$label • ↓ ${FormatUtils.kbps(down)}  ↑ ${FormatUtils.kbps(up)}"
                        }
                    }
                    startForeground(
                        1001,
                        NotificationHelper.vpn(this, when { stormDnsMode -> "SIMORGH MasterDNS"; nipoMode -> "SIMORGH NipoVPN"; simpleMode -> "SIMORGH Simple"; fragmentMode -> "SIMORGH Fragment"; else -> "SIMORGH Private" }, notificationText, connected = true)
                    )
                } catch (_: Throwable) {
                    // Keep VPN alive even if notification update fails.
                }
            }
        }
    }

    private fun closeDetachedTunFd(reason: String) {
        val rawFd = detachedTunFd
        detachedTunFd = null
        if (rawFd != null) {
            runCatching { ParcelFileDescriptor.adoptFd(rawFd).close() }
                .onSuccess { log("Detached TUN fd closed before core stop ($reason): fd=$rawFd") }
                .onFailure { log("Detached TUN fd close failed safely ($reason): fd=$rawFd", it) }
        }
    }


    private fun exitIsolatedVpnProcessAfterDisconnect(reason: String) {
        val pid = android.os.Process.myPid()
        RKhVpnLogStore.appendSync(this, "VPN", "Scheduling isolated VPN service process exit after disconnect ($reason) • pid=$pid")
        thread(name = "simorgh-vpncore-exit", isDaemon = false) {
            runCatching { Thread.sleep(350L) }
            RKhVpnLogStore.appendSync(this, "VPN", "Exiting isolated VPN service process after disconnect ($reason) • pid=$pid")
            kotlin.system.exitProcess(0)
        }
    }

    @Synchronized
    private fun stopVpn(clearSimpleState: Boolean = true, allowProcessExit: Boolean = true) {
        if (!stopGuard.compareAndSet(false, true)) {
            lifecycleGeneration.incrementAndGet()
            running = false
            log("Duplicate stop ignored safely; first disconnect cleanup is still running")
            return
        }
        try {
        lifecycleGeneration.incrementAndGet()
        running = false
        getSharedPreferences("rkh_vpn_state", MODE_PRIVATE).edit().putBoolean("serviceConnected", false).commit()
        if (clearSimpleState) {
            val publicPrefs = getSharedPreferences("simorgh_public_state", Context.MODE_PRIVATE or Context.MODE_MULTI_PROCESS)
            val isSearchingSimple = publicPrefs.getBoolean("simpleConnecting", false)
            val simpleStatus = publicPrefs.getString("simpleStatus", "").orEmpty()
            val simpleStartedAt = publicPrefs.getLong("startedAt", 0L)
            val simpleSearchRecentlyRequested = simpleStatus.contains("Searching and Ping", ignoreCase = true) &&
                simpleStartedAt > 0L &&
                (System.currentTimeMillis() - simpleStartedAt) < 45000L
            if (!isSearchingSimple && !simpleSearchRecentlyRequested && (publicPrefs.getBoolean("simpleConnected", false) || publicPrefs.getString("activeMode", "") == "simple_xray")) {
                publicPrefs.edit()
                    .putBoolean("simpleConnecting", false)
                    .putBoolean("simpleConnected", false)
                    .putString("simpleStatus", "Simple XRAY disconnected")
                    .putString("status", "Simple XRAY disconnected")
                    .putString("activeMode", "idle")
                    .putLong("downloadKbps", 0L)
                    .putLong("uploadKbps", 0L)
                    .putLong("startedAt", 0L)
                    .apply()
            }
        }
        val publicPrefsForNipo = getSharedPreferences("simorgh_public_state", Context.MODE_PRIVATE or Context.MODE_MULTI_PROCESS)
        if (publicPrefsForNipo.getBoolean("nipoConnected", false) || publicPrefsForNipo.getBoolean("nipoConnecting", false) || publicPrefsForNipo.getString("activeMode", "") == "nipo") {
            publicPrefsForNipo.edit()
                .putBoolean("nipoConnecting", false)
                .putBoolean("nipoConnected", false)
                .putString("nipoStatus", "NipoVPN disconnected")
                .putString("status", "NipoVPN disconnected")
                .putString("activeMode", "idle")
                .putLong("downloadKbps", 0L)
                .putLong("uploadKbps", 0L)
                .putLong("startedAt", 0L)
                .apply()
        }
        val fragmentStartInProgress = publicPrefsForNipo.getBoolean("fragmentStartInProgress", false)
        if (!fragmentStartInProgress && (publicPrefsForNipo.getBoolean("fragmentConnected", false) || publicPrefsForNipo.getBoolean("fragmentConnecting", false) || publicPrefsForNipo.getString("activeMode", "") == "fragment")) {
            publicPrefsForNipo.edit()
                .putBoolean("fragmentConnecting", false)
                .putBoolean("fragmentConnected", false)
                .putString("fragmentStatus", "Fragment disconnected")
                .putString("status", "Fragment disconnected")
                .putString("activeMode", "idle")
                .putLong("downloadKbps", 0L)
                .putLong("uploadKbps", 0L)
                .putLong("startedAt", 0L)
                .apply()
        }
        if (publicPrefsForNipo.getBoolean("stormDnsConnected", false) || publicPrefsForNipo.getBoolean("stormDnsConnecting", false) || publicPrefsForNipo.getString("activeMode", "") == "stormdns") {
            clearStormDnsHealthyRuntimeCache()
            publicPrefsForNipo.edit()
                .putBoolean("stormDnsConnecting", false)
                .putBoolean("stormDnsConnected", false)
                .putString("stormDnsStatus", "MasterDNS disconnected")
                .remove("stormDnsPendingResolversText")
                .putString("status", "MasterDNS disconnected")
                .putString("activeMode", "idle")
                .putLong("downloadKbps", 0L)
                .putLong("uploadKbps", 0L)
                .putLong("startedAt", 0L)
                .apply()
        }
        log("Stopping VPN")
        runCatching { notificationThread?.interrupt() }
        notificationThread = null

        // Close Android TUN before stopping the native/core manager. tun2proxy is a
        // blocking native loop and only exits reliably after the VpnService fd is closed.
        // For tun2proxy paths the fd is detached from ParcelFileDescriptor.
        // Once ProcessCore accepts the fd, detachedTunFd is cleared and ProcessCore closes it
        // from JNI on disconnect. Only pre-core abort/failure leaves detachedTunFd non-null.
        if (detachedTunFd != null) closeDetachedTunFd("disconnect-before-native-ownership")
        val oldTun = tun
        tun = null
        runCatching { oldTun?.close() }
            .onSuccess { if (oldTun != null) log("TUN ParcelFileDescriptor closed before core stop") }
            .onFailure { log("TUN ParcelFileDescriptor close failed", it) }

        val oldInheritedTunPfd = inheritedTunPfd
        inheritedTunPfd = null
        runCatching { oldInheritedTunPfd?.close() }
            .onSuccess { if (oldInheritedTunPfd != null) log("Inherited TUN PFD closed before core stop") }
            .onFailure { log("Inherited TUN PFD close failed", it) }

        val oldInheritedTunFd = inheritedTunFd
        inheritedTunFd = null
        runCatching { oldInheritedTunFd?.let { Os.close(it) } }
            .onSuccess { if (oldInheritedTunFd != null) log("Inherited TUN fd closed before core stop") }
            .onFailure { log("Inherited TUN fd close failed", it) }

        val oldCore = core
        core = null
        runCatching { oldCore?.stop(allowProcessExitOnTun2ProxyReturn = allowProcessExit, waitForTun2ProxyExitMs = if (allowProcessExit) 0L else 2_500L) }
            .onFailure { log("binary core stop failed", it) }

        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            .onSuccess { log("Foreground notification removed") }
            .onFailure { log("Foreground notification remove failed safely", it) }
        if (allowProcessExit) {
            exitIsolatedVpnProcessAfterDisconnect("RkhVpnService.stopVpn")
        } else {
            log("Isolated VPN process exit skipped for pre-start/pre-connect cleanup")
        }
        } finally {
            // Keep stopGuard=true until the next explicit start resets it. This makes
            // ACTION_STOP, onDestroy, onRevoke, widget/tile stop and duplicate UI taps
            // idempotent and prevents double teardown crashes after Disconnect.
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        log("onTaskRemoved called; app task removed or system cleared service • action=${rootIntent?.action ?: "null"}")
        runCatching { stopVpn() }
            .onFailure { log("Safe task-removed stop caught error", it) }
        runCatching { super.onTaskRemoved(rootIntent) }
            .onFailure { log("Safe super.onTaskRemoved caught error", it) }
    }

    override fun onRevoke() {
        log("VPN permission revoked by system")
        runCatching { stopVpn() }
            .onFailure { log("Safe revoke stop caught error", it) }
        runCatching { stopSelf() }
            .onFailure { log("Safe revoke stopSelf caught error", it) }
        runCatching { super.onRevoke() }
            .onFailure { log("Safe super.onRevoke caught error", it) }
    }

    override fun onDestroy() {
        log("Service destroyed")
        runCatching { stopVpn() }
            .onFailure { log("Safe destroy stop caught error", it) }
        runCatching { super.onDestroy() }
            .onFailure { log("Safe super.onDestroy caught error", it) }
        log("Service destroyed cleanup completed safely")
    }

    private fun log(message: String, throwable: Throwable? = null) {
        if (throwable == null) Log.d(tag, message) else Log.e(tag, message, throwable)
        if (message.contains("MasterDNS", ignoreCase = true)) {
            StormDnsRuntimeLog.append(this, if (throwable == null) message else "$message • ${throwable.javaClass.simpleName}: ${throwable.message ?: ""}")
        } else {
            RKhVpnLogStore.append(this, "VPN", message, throwable)
        }
    }
}
