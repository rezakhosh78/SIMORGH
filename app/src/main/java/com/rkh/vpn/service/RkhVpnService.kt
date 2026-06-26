package com.rkh.vpn.service

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.net.TrafficStats
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.rkh.vpn.core.ProcessCoreManager
import com.rkh.vpn.core.XrayBinaryConfigBuilder
import com.rkh.vpn.data.RKhVpnLogStore
import com.rkh.vpn.data.MasterDnsRuntimeLog
import com.rkh.vpn.data.FormatUtils
import kotlin.concurrent.thread
import java.net.ServerSocket
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

class RkhVpnService : VpnService() {
    private val tag = "RKhVPN-Service"

    companion object {
        const val ACTION_START = "com.rkh.vpn.START"
        const val ACTION_START_NIPO = "com.rkh.vpn.START_NIPO"
        const val ACTION_START_MASTERDNS = "com.rkh.vpn.START_MASTERDNS"
        const val ACTION_UPDATE_MASTERDNS_RESOLVERS = "com.rkh.vpn.UPDATE_MASTERDNS_RESOLVERS"
        const val ACTION_STOP = "com.rkh.vpn.STOP"
        const val ACTION_TOGGLE = "com.rkh.vpn.TOGGLE"
        const val EXTRA_RAW_CONFIG = "raw_config"
        const val EXTRA_SERVER_NAME = "server_name"
        const val EXTRA_NIPO_CONFIG = "nipo_config"
        const val EXTRA_MASTERDNS_CONFIG = "masterdns_config"
        const val EXTRA_MASTERDNS_SERVER_CONFIG = "masterdns_server_config"
        const val EXTRA_MASTERDNS_RESOLVERS = "masterdns_resolvers"
        const val EXTRA_MASTERDNS_MODE = "masterdns_mode"
        const val EXTRA_STOP_SOURCE = "stop_source"
    }

    private var tun: ParcelFileDescriptor? = null
    private var core: ProcessCoreManager? = null
    private var inheritedTunFd: java.io.FileDescriptor? = null
    private var inheritedTunPfd: ParcelFileDescriptor? = null
    private var notificationThread: Thread? = null
    @Volatile private var running = false
    private val lifecycleGeneration = AtomicInteger(0)

    override fun onCreate() {
        super.onCreate()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                log("Safe service thread crash caught from ${thread.name}", throwable)
                getSharedPreferences("simorgh_public_state", MODE_PRIVATE).edit()
                    .putBoolean("masterDnsConnecting", false)
                    .putBoolean("connecting", false)
                    .putBoolean("simpleConnecting", false)
                    .putBoolean("nipoConnecting", false)
                    .putString("masterDnsStatus", "Core thread error: ${throwable.message ?: throwable.javaClass.simpleName}")
                    .putString("status", "Core thread error")
                    .apply()
            }
        }
        log("Service created • binary-core mode, no libv2ray")
    }

    private fun tunnelSectionForLabel(label: String): String {
        val lower = label.lowercase()
        return when {
            lower.contains("master") -> "masterdns"
            lower.contains("nipo") -> "nipo"
            lower.contains("fragment") -> "fragment"
            lower.contains("msp") || lower.contains("public") -> "msp"
            else -> "simple"
        }
    }

    private fun applyTunnelAppPolicy(builder: Builder, label: String) {
        val publicPrefs = getSharedPreferences("simorgh_public_state", MODE_PRIVATE)
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
                ACTION_START_NIPO -> startNipo(
                    intent.getStringExtra(EXTRA_NIPO_CONFIG).orEmpty()
                )
                ACTION_START_MASTERDNS -> startMasterDns(
                    intent.getStringExtra(EXTRA_MASTERDNS_CONFIG).orEmpty(),
                    intent.getStringExtra(EXTRA_MASTERDNS_MODE).orEmpty().ifBlank { "proxy" },
                    intent.getStringExtra(EXTRA_MASTERDNS_SERVER_CONFIG).orEmpty(),
                    intent.getStringExtra(EXTRA_MASTERDNS_RESOLVERS).orEmpty()
                )
                ACTION_UPDATE_MASTERDNS_RESOLVERS -> updateMasterDnsResolvers(intent.getStringExtra(EXTRA_MASTERDNS_RESOLVERS).orEmpty())
                ACTION_STOP -> {
                    val stopSource = intent.getStringExtra(EXTRA_STOP_SOURCE).orEmpty()
                    val label = when (stopSource) {
                        "nipo" -> "NipoVPN"
                        "fragment" -> "Fragment"
                        "simple_serverless" -> "Simple ServerLess"
                        "simple" -> "Simple"
                        else -> "Core"
                    }
                    log("$label stop action received")
                    runCatching { stopVpn() }
                        .onFailure { log("Safe $label stop caught error", it) }
                    runCatching { stopSelf() }
                        .onFailure { log("$label stopSelf failed safely", it) }
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
            getSharedPreferences("simorgh_public_state", MODE_PRIVATE).edit()
                .putBoolean("connecting", false)
                .putBoolean("simpleConnecting", false)
                .putBoolean("simpleConnected", false)
                .putBoolean("fragmentStartInProgress", false)
                .putBoolean("fragmentConnecting", false)
                .putBoolean("fragmentConnected", false)
                .putBoolean("nipoConnecting", false)
                .putBoolean("masterDnsConnecting", false)
                .putString("status", "Core start error: ${e.message ?: e.javaClass.simpleName}")
                .putString("lastError", e.message ?: e.javaClass.simpleName)
                .putString("activeMode", "idle")
                .apply()
            runCatching { stopSelf() }.onFailure { log("Safe guard stopSelf failed", it) }
            START_NOT_STICKY
        }
    }



    private fun waitForMasterDnsListeningLog(socksPort: Int, publicPrefs: android.content.SharedPreferences) {
        log("Waiting for MasterDNS client listening log before marking connected: SOCKS5 127.0.0.1:$socksPort")
        val startedAt = System.currentTimeMillis()
        while (running && System.currentTimeMillis() - startedAt < 180_000L) {
            val ready = MasterDnsRuntimeLog.isSocksListeningDetected() || MasterDnsRuntimeLog.readRecent(12).any { line ->
                val lower = line.lowercase()
                lower.contains("socks5 proxy server is listening on") ||
                    (lower.contains("proxy server is listening") && lower.contains(socksPort.toString())) ||
                    (lower.contains("listening on") && lower.contains(socksPort.toString()))
            }
            publicPrefs.edit()
                .putString("masterDnsStatus", "MasterDNS testing • waiting for SOCKS5 listening log")
                .apply()
            if (ready) {
                log("MasterDNS SOCKS5 listening log detected • 127.0.0.1:$socksPort")
                return
            }
            Thread.sleep(350L)
        }
        throw IllegalStateException("MasterDNS SOCKS5 listening log was not detected after 180 seconds")
    }

    private fun masterDnsAcceptedDnsCount(lines: List<String>): Int {
        return lines.count { line ->
            val lower = line.lowercase()
            (lower.contains("✅ accepted") || lower.contains("[info]") && lower.contains("accepted") || lower.contains(" accepted")) &&
                !lower.contains("rejected") && !lower.contains("fail") && !lower.contains("timeout")
        }
    }

    private fun masterDnsMtuProgress(lines: List<String>): Pair<Int, Int> {
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

    private fun waitForMasterDnsScanToSettleBeforeVpn(publicPrefs: android.content.SharedPreferences) {
        log("MasterDNS VPN will be created right after DNS scan settles")
        val startedAt = System.currentTimeMillis()
        var lastMarker = -1
        var stableTicks = 0
        while (running && System.currentTimeMillis() - startedAt < 20_000L) {
            val lines = MasterDnsRuntimeLog.read()
            val accepted = maxOf(masterDnsAcceptedDnsCount(lines), MasterDnsRuntimeLog.acceptedResolversSnapshot().size)
            val cachedProgress = MasterDnsRuntimeLog.progressSnapshot()
            val parsedProgress = masterDnsMtuProgress(lines)
            val scanned = maxOf(cachedProgress.first, parsedProgress.first)
            val total = maxOf(cachedProgress.second, parsedProgress.second)
            val tail = lines.takeLast(16).joinToString("\n").lowercase()
            val completed = tail.contains("mtu test completed") || tail.contains("mtu testing completed") ||
                tail.contains("testing completed") || tail.contains("all resolver") || tail.contains("all resolvers") ||
                (total > 0 && scanned >= total)
            publicPrefs.edit()
                .putString("masterDnsStatus", if (total > 0) "MasterDNS testing DNS $scanned/$total • healthy=$accepted" else "MasterDNS testing DNS • healthy=$accepted")
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
        val lines = MasterDnsRuntimeLog.read()
        val parsedProgress = masterDnsMtuProgress(lines)
        val cachedProgress = MasterDnsRuntimeLog.progressSnapshot()
        val scanned = maxOf(cachedProgress.first, parsedProgress.first)
        val total = maxOf(cachedProgress.second, parsedProgress.second)
        val accepted = maxOf(masterDnsAcceptedDnsCount(lines), MasterDnsRuntimeLog.acceptedResolversSnapshot().size)
        log("MasterDNS DNS settle window finished • creating VPN with progress=$scanned/$total • healthy=$accepted")
    }



    private fun isLocalPortAvailable(port: Int): Boolean {
        return runCatching { ServerSocket(port).use { true } }.getOrDefault(false)
    }

    private fun chooseMasterDnsBridgePort(masterDnsSocksPort: Int): Int {
        val candidates = listOf(masterDnsSocksPort + 8, 18088, 18098, 18108, 10818, 10828)
            .filter { it in 1024..65535 && it != masterDnsSocksPort }
        return candidates.firstOrNull { isLocalPortAvailable(it) }
            ?: ServerSocket(0).use { it.localPort }
    }

    private fun clearMasterDnsHealthyRuntimeCache() {
        MasterDnsRuntimeLog.clear()
        val runtimeDir = java.io.File(filesDir, "masterdns-runtime")
        runtimeDir.listFiles()?.forEach { file ->
            val name = file.name.lowercase()
            if (name.contains("success") || name.contains("mtu") || name.contains("healthy")) runCatching { file.delete() }
        }
        getSharedPreferences("simorgh_public_state", MODE_PRIVATE).edit()
            .putInt("masterDnsResolverValidCount", 0)
            .putInt("masterDnsResolverScanned", 0)
            .putString("masterDnsHealthyResolversText", "")
            .putString("masterDnsResolverScanStatus", "Healthy DNS cache cleared")
            .apply()
    }


    private fun masterDnsVpnBridgeConfig(localSocksPort: Int, masterDnsSocksPort: Int, localDnsPort: Int): String = """
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
              "tag": "masterdns-socks-out",
              "protocol": "socks",
              "settings": { "servers": [ { "address": "127.0.0.1", "port": $masterDnsSocksPort } ] }
            },
            { "tag": "dns-out", "protocol": "dns", "settings": {} }
          ],
          "routing": {
            "domainStrategy": "AsIs",
            "rules": [
              { "type": "field", "network": "tcp,udp", "port": "53", "outboundTag": "dns-out" },
              { "type": "field", "network": "udp", "port": "443", "outboundTag": "masterdns-socks-out" },
              { "type": "field", "network": "tcp,udp", "outboundTag": "masterdns-socks-out" }
            ]
          }
        }
    """.trimIndent()


    private fun masterDnsVpnBridgeFallbackConfig(localSocksPort: Int, masterDnsSocksPort: Int): String = """
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
              "tag": "masterdns-socks-out",
              "protocol": "socks",
              "settings": { "servers": [ { "address": "127.0.0.1", "port": $masterDnsSocksPort } ] }
            },
            { "tag": "direct-out", "protocol": "freedom", "settings": {} }
          ],
          "routing": {
            "domainStrategy": "AsIs",
            "rules": [
              { "type": "field", "network": "udp", "port": "53", "outboundTag": "direct-out" },
              { "type": "field", "network": "udp", "port": "443", "outboundTag": "masterdns-socks-out" },
              { "type": "field", "network": "tcp,udp", "outboundTag": "masterdns-socks-out" }
            ]
          }
        }
    """.trimIndent()


    private fun updateMasterDnsResolvers(resolversText: String) {
        val clean = resolversText.replace("﻿", "").trim()
        val publicPrefs = getSharedPreferences("simorgh_public_state", MODE_PRIVATE)
        runCatching {
            core?.updateMasterDnsResolvers(clean)
            publicPrefs.edit()
                .putString("masterDnsStatus", "MasterDNS healthy DNS updated • ${clean.lineSequence().count { it.trim().isNotBlank() }} resolver(s)")
                .apply()
            log("MasterDNS resolver list updated while running • lines=${clean.lineSequence().count { it.trim().isNotBlank() }}")
        }.onFailure { e ->
            log("MasterDNS resolver update failed", e)
            publicPrefs.edit().putString("masterDnsStatus", "MasterDNS resolver update failed: ${e.message ?: e.javaClass.simpleName}").apply()
        }
    }


    private fun startMasterDns(configToml: String, runMode: String, serverConfigToml: String, resolversText: String) {
        val mode = if (runMode.trim().lowercase(Locale.US) == "vpn") "vpn" else "proxy"
        val cleanConfig = configToml.replace("﻿", "").trim()
        val cleanResolvers = resolversText.replace("﻿", "").trim()
        val publicPrefs = getSharedPreferences("simorgh_public_state", MODE_PRIVATE)
        log("MasterDNS client start requested • mode=$mode • clientConfigChars=${cleanConfig.length} • resolversChars=${cleanResolvers.length}")
        if (cleanConfig.isBlank()) {
            publicPrefs.edit()
                .putBoolean("masterDnsConnecting", false)
                .putBoolean("masterDnsConnected", false)
                .putString("masterDnsStatus", "MasterDNS config is blank")
                .putString("status", "MasterDNS config is blank")
                .putString("activeMode", "idle")
                .apply()
            stopSelf()
            return
        }
        stopVpn(clearSimpleState = true)
        running = true

        runCatching {
            startForeground(
                1001,
                NotificationHelper.vpn(this, "SIMORGH MasterDNS", "Starting MasterDNS ${mode.uppercase()} mode", connected = true)
            )
            log("Foreground notification started for MasterDNS")
            startNotificationSpeedLoop("SIMORGH MasterDNS")
        }.onFailure { e ->
            log("MasterDNS startForeground failed", e)
            stopSelf()
            return
        }

        thread(name = "RKhVPN-masterdns-core") {
            runCatching {
                publicPrefs.edit()
                    .putBoolean("masterDnsConnecting", true)
                    .putBoolean("masterDnsConnected", false)
                    .putString("masterDnsStatus", "Starting MasterDNS core...")
                    .putString("status", "MasterDNS starting")
                    .putString("activeMode", "masterdns")
                    .putLong("startedAt", System.currentTimeMillis())
                    .apply()

                val mgr = ProcessCoreManager(this)
                core = mgr
                val socksPort = publicPrefs.getInt("masterDnsSocksPort", 9993).coerceIn(1024, 65535)
                val requestedLocalDnsPort = publicPrefs.getInt("masterDnsLocalDnsPort", 5353)
                val localDnsPort = if (requestedLocalDnsPort < 1024) 5353 else requestedLocalDnsPort.coerceIn(1024, 65535)
                if (requestedLocalDnsPort != localDnsPort) {
                    publicPrefs.edit().putInt("masterDnsLocalDnsPort", localDnsPort).apply()
                    log("MasterDNS local DNS port changed from privileged $requestedLocalDnsPort to safe $localDnsPort")
                }
                val startedSocksPort = mgr.startMasterDnsAgent(cleanConfig, resolversText = cleanResolvers, socksPort = socksPort)
                log("MasterDNS SOCKS5 local endpoint ready: 127.0.0.1:$startedSocksPort")
                if (mode == "vpn") {
                    waitForMasterDnsScanToSettleBeforeVpn(publicPrefs)
                    log("Building Android VPN TUN interface for MasterDNS after DNS scan")
                    val builder = Builder()
                        .setSession("SIMORGH MasterDNS")
                        .addAddress("172.19.0.8", 30)
                        .addRoute("0.0.0.0", 0)
                        .allowFamily(android.system.OsConstants.AF_INET)
                        .addDnsServer("1.1.1.1")
                        .addDnsServer("8.8.8.8")
                        .setMtu(1500)
                    applyTunnelAppPolicy(builder, "MasterDNS")
                    val fd = builder.establish() ?: error("Builder.establish returned null. VPN permission may be missing.")
                    tun = fd
                    inheritedTunPfd = ParcelFileDescriptor.dup(fd.fileDescriptor)
                    val childTunFdNumber = inheritedTunPfd?.fd ?: error("Could not duplicate MasterDNS TUN fd")
                    log("Prepared safe duplicated MasterDNS TUN fd: original=${fd.fd}, child=$childTunFdNumber")
                    val bridgeLocalPort = chooseMasterDnsBridgePort(startedSocksPort)
                    val bridgeConfig = masterDnsVpnBridgeConfig(localSocksPort = bridgeLocalPort, masterDnsSocksPort = startedSocksPort, localDnsPort = localDnsPort)
                    val bridgeSocksPort = runCatching {
                        mgr.startXray(bridgeConfig)
                    }.getOrElse { bridgeError ->
                        log("MasterDNS DNS/QUIC bridge failed; retrying safe fallback bridge", bridgeError)
                        mgr.startXray(masterDnsVpnBridgeFallbackConfig(localSocksPort = bridgeLocalPort, masterDnsSocksPort = startedSocksPort))
                    }
                    mgr.startTun2Socks(childTunFdNumber, bridgeSocksPort)
                    log("MasterDNS VPN path started: Android TUN → tun2socks → Xray DNS/QUIC Bridge 127.0.0.1:$bridgeSocksPort → MasterDNS SOCKS5 127.0.0.1:$startedSocksPort")
                } else {
                    log("MasterDNS proxy path started: local SOCKS5 127.0.0.1:$startedSocksPort")
                }
                getSharedPreferences("rkh_vpn_state", MODE_PRIVATE).edit().putBoolean("serviceConnected", mode == "vpn").apply()
                publicPrefs.edit()
                    .putBoolean("masterDnsConnecting", false)
                    .putBoolean("masterDnsConnected", true)
                    .putString("masterDnsStatus", if (mode == "vpn") "MasterDNS VPN connected" else "MasterDNS proxy connected • SOCKS5 127.0.0.1:$startedSocksPort")
                    .putString("status", "MasterDNS connected")
                    .putString("activeMode", "masterdns")
                    .putLong("startedAt", System.currentTimeMillis())
                    .apply()
            }.onFailure { e ->
                log("MasterDNS start failed", e)
                publicPrefs.edit()
                    .putBoolean("masterDnsConnecting", false)
                    .putBoolean("masterDnsConnected", false)
                    .putString("masterDnsStatus", "MasterDNS start failed: ${e.message ?: e.javaClass.simpleName}")
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
            }
        }
    }

    private fun startNipo(configYaml: String) {
        val cleanConfig = configYaml.replace("﻿", "").trim()
        log("NipoVPN start requested • configChars=${cleanConfig.length} • socks5AgentPort=9992")
        stopVpn(clearSimpleState = true)
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
                val publicPrefs = getSharedPreferences("simorgh_public_state", MODE_PRIVATE)
                publicPrefs.edit()
                    .putBoolean("nipoConnecting", true)
                    .putBoolean("nipoConnected", false)
                    .putString("nipoStatus", "Starting NipoVPN agent on SOCKS5 127.0.0.1:9992...")
                    .putString("status", "NipoVPN starting")
                    .putString("activeMode", "nipo")
                    .putLong("startedAt", System.currentTimeMillis())
                    .apply()

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

                val childTunFdNumber = 200
                val childTunFileDescriptor = Os.dup2(fd.fileDescriptor, childTunFdNumber)
                Os.fcntlInt(childTunFileDescriptor, OsConstants.F_SETFD, 0)
                inheritedTunFd = childTunFileDescriptor
                log("Prepared inheritable NipoVPN TUN fd: original=${fd.fd}, child=$childTunFdNumber, FD_CLOEXEC cleared")

                val mgr = ProcessCoreManager(this)
                core = mgr
                val nipoSocksPort = mgr.startNipoAgent(cleanConfig, 9992)
                if (!running) {
                    log("NipoVPN start aborted after agent start because disconnect was requested")
                    runCatching { mgr.stop() }
                    return@runCatching
                }
                val config = XrayBinaryConfigBuilder.nipoBridgeConfig(localSocksPort = 10808, nipoSocksPort = nipoSocksPort)
                log("NipoVPN Xray bridge config generated. Flow: Android TUN → tun2socks → Xray 127.0.0.1:10808 → Nipo SOCKS5 127.0.0.1:$nipoSocksPort")
                val socksPort = mgr.startXray(config)
                mgr.startTun2Socks(childTunFdNumber, socksPort)
                if (!running) {
                    log("NipoVPN start aborted after tun2socks start because disconnect was requested")
                    runCatching { mgr.stop() }
                    return@runCatching
                }
                getSharedPreferences("rkh_vpn_state", MODE_PRIVATE).edit().putBoolean("serviceConnected", true).apply()
                publicPrefs.edit()
                    .putBoolean("nipoConnecting", false)
                    .putBoolean("nipoConnected", true)
                    .putString("nipoStatus", "NipoVPN connected")
                    .putString("status", "NipoVPN connected")
                    .putString("activeMode", "nipo")
                    .putLong("startedAt", System.currentTimeMillis())
                    .apply()
                log("NipoVPN started successfully. Traffic path: Android VpnService TUN → tun2socks → Xray SOCKS inbound → Xray SOCKS outbound → NipoVPN agent")
            }.onFailure { e ->
                log("NipoVPN start failed", e)
                getSharedPreferences("simorgh_public_state", MODE_PRIVATE).edit()
                    .putBoolean("nipoConnecting", false)
                    .putBoolean("nipoConnected", false)
                    .putString("nipoStatus", "NipoVPN start failed: ${e.message ?: e.javaClass.simpleName}")
                    .putString("status", "NipoVPN start failed")
                    .putString("activeMode", "idle")
                    .putLong("startedAt", 0L)
                    .apply()
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
        stopVpn(clearSimpleState = !simpleMode)
        val generation = lifecycleGeneration.incrementAndGet()
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
                    // architecture-level: v2rayNG-style Android TUN -> tun2socks -> Xray mixed inbound.
                    log("ServerLess IPv4-only Android TUN route active • no experimental IPv6 route")
                }

                applyTunnelAppPolicy(builder, when {
                    simpleMode -> "Simple"
                    fragmentMode -> "Fragment"
                    else -> "VPN"
                })
                val fd = builder.establish() ?: error("Builder.establish returned null. VPN permission may be missing.")
                tun = fd
                log("TUN established. fd=${fd.fd}. Android key icon should be visible now. mtu=1500")
                if (!isCurrentLifecycle(generation)) {
                    log("Simple/Private start aborted after TUN establish because a newer start/stop was requested")
                    runCatching { fd.close() }
                    return@runCatching
                }
                // External native processes cannot reliably see the raw VpnService fd because
                // Android marks many Java/ParcelFileDescriptor handles close-on-exec.
                // Duplicate the TUN fd to a stable number and explicitly clear FD_CLOEXEC so
                // child native processes can inherit it.
                val childTunFdNumber = 200
                val childTunFileDescriptor = Os.dup2(fd.fileDescriptor, childTunFdNumber)
                Os.fcntlInt(childTunFileDescriptor, OsConstants.F_SETFD, 0)
                inheritedTunFd = childTunFileDescriptor
                log("Prepared inheritable TUN fd: original=${fd.fd}, child=$childTunFdNumber, FD_CLOEXEC cleared, target=tun2socks")

                val mgr = ProcessCoreManager(this)
                core = mgr
                if (!isCurrentLifecycle(generation)) {
                    log("Simple/Private start aborted before core start because a newer start/stop was requested")
                    runCatching { mgr.stop() }
                    return@runCatching
                }
                if (serverLessMode) {
                    val config = XrayBinaryConfigBuilder.socksConfigFromRaw(raw, 10808)
                    val hasTunInbound = config.contains("\"protocol\": \"tun\"") || config.contains("\"protocol\":\"tun\"")
                    val hasMixedInbound = config.contains("\"protocol\": \"mixed\"") || config.contains("\"protocol\":\"mixed\"")
                    val hasFakeDns = config.contains("\"address\": \"fakedns\"", ignoreCase = true) || config.contains("\"address\":\"fakedns\"", ignoreCase = true)
                    val hasDnsOutPort53 = Regex(""""outboundTag"\s*:\s*"dns-out"[\s\S]{0,120}"port"\s*:\s*"?53"?""").containsMatchIn(config)
                    val hasTcpFragment = config.contains("\"outboundTag\": \"tcp-fragment\"") || config.contains("\"tag\": \"tcp-fragment\"")
                    val cloudflareDohRuntime = config.contains("https://cloudflare-dns.com", ignoreCase = true)
                    val mixedFakeDnsOverride = Regex("\\\"protocol\\\"\\s*:\\s*\\\"mixed\\\"[\\s\\S]{0,900}\\\"destOverride\\\"\\s*:\\s*\\[[\\s\\S]{0,250}\\\"fakedns\\\"").containsMatchIn(config)
                    val realDnsForTun2Socks = !hasFakeDns && config.contains("\"address\": \"localhost\"")
                    log("ServerLess upstream HEV/tun2socks mode active • useTun2Socks=true • xrayInbound=mixed-127.0.0.1:10808 • hasTunInbound=$hasTunInbound • hasMixedInbound=$hasMixedInbound • fakeDnsServer=$hasFakeDns • mixedFakeDnsOverride=$mixedFakeDnsOverride • realDnsForTun2Socks=$realDnsForTun2Socks • cloudflareDohRuntime=$cloudflareDohRuntime • dohReplacedWithLocalhost=false • dnsOutPort53=$hasDnsOutPort53 • tcpFragment=$hasTcpFragment • ipv6TunRoute=false • mtu=1500")
                    val socksPort = mgr.startXray(config)
                    if (!isCurrentLifecycle(generation)) {
                        log("ServerLess start aborted after Xray start because a newer start/stop was requested")
                        runCatching { mgr.stop() }
                        return@runCatching
                    }
                    mgr.startTun2Socks(childTunFdNumber, socksPort)
                    if (!isCurrentLifecycle(generation)) {
                        log("ServerLess start aborted after tun2socks because a newer start/stop was requested")
                        runCatching { mgr.stop() }
                        return@runCatching
                    }
                    getSharedPreferences("rkh_vpn_state", MODE_PRIVATE).edit().putBoolean("serviceConnected", true).apply()
                    log("VPN binary-core ServerLess started successfully. Traffic should now go: Android VpnService TUN → tun2socks → Xray mixed inbound → ServerLess routing")
                } else {
                    val config = XrayBinaryConfigBuilder.socksConfigFromRaw(raw, 10808, forceGoogleDns = simpleMode && !serverLessMode)
                    log("Xray SOCKS config generated. Length=${config.length} • hasSocksIn=${config.contains("\"socks-in\"")} • hasHttpIn=${config.contains("\"http-in\"")}")
                    val socksPort = mgr.startXray(config)
                    if (!isCurrentLifecycle(generation)) {
                        log("Simple normal start aborted after Xray start because a newer start/stop was requested")
                        runCatching { mgr.stop() }
                        return@runCatching
                    }
                    mgr.startTun2Socks(childTunFdNumber, socksPort)
                    if (!isCurrentLifecycle(generation)) {
                        log("Simple normal start aborted after tun2socks because a newer start/stop was requested")
                        runCatching { mgr.stop() }
                        return@runCatching
                    }
                    getSharedPreferences("rkh_vpn_state", MODE_PRIVATE).edit().putBoolean("serviceConnected", true).apply()
                    if (fragmentMode) {
                        getSharedPreferences("simorgh_public_state", MODE_PRIVATE).edit()
                            .putBoolean("fragmentConnecting", false)
                            .putBoolean("fragmentConnected", true)
                            .putString("fragmentStatus", "Fragment connected")
                            .putString("status", "Fragment connected")
                            .putString("activeMode", "fragment")
                            .putLong("startedAt", System.currentTimeMillis())
                            .apply()
                    }
                    log("VPN binary-core started successfully. Traffic should now go: Android TUN → tun2socks → Xray SOCKS → selected config")
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
                        getSharedPreferences("simorgh_public_state", MODE_PRIVATE).edit()
                            .putBoolean("fragmentConnecting", false)
                            .putBoolean("fragmentConnected", false)
                            .putString("fragmentStatus", "Fragment start failed: ${e.message ?: e.javaClass.simpleName}")
                            .putString("status", "Fragment start failed")
                            .putString("activeMode", "idle")
                            .putLong("startedAt", 0L)
                            .apply()
                    }
                    stopVpn()
                    stopSelf()
                } else {
                    log("Stale Simple/Private start failure ignored because a newer start/stop is active", e)
                }
            }
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

    private fun startNotificationSpeedLoop(serverName: String) {
        val simpleMode = serverName.startsWith("SIMORGH Simple")
        val nipoMode = serverName.startsWith("SIMORGH NipoVPN")
        val masterDnsMode = serverName.startsWith("SIMORGH MasterDNS")
        val fragmentMode = serverName.startsWith("SIMORGH Fragment")
        val publicPrefs = if (simpleMode || nipoMode || masterDnsMode || fragmentMode) getSharedPreferences("simorgh_public_state", MODE_PRIVATE) else null
        notificationThread = thread(name = "RKhVPN-notification-speed") {
            var lastRx = TrafficStats.getTotalRxBytes().coerceAtLeast(0L)
            var lastTx = TrafficStats.getTotalTxBytes().coerceAtLeast(0L)
            var lastAt = System.currentTimeMillis()
            val updateIntervalMs = if (simpleMode || nipoMode || masterDnsMode || fragmentMode) 3_000L else 2_500L
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
                            ?.putString("status", "Simple XRAY connected")
                        if (down > 0L || up > 0L) {
                            editor?.putLong("simpleLastTrafficAt", System.currentTimeMillis())
                                ?.putBoolean("simpleHadTraffic", true)
                        }
                        editor?.apply()
                    } else if (nipoMode) {
                        publicPrefs?.edit()
                            ?.putLong("downloadKbps", down)
                            ?.putLong("uploadKbps", up)
                            ?.putBoolean("nipoConnected", true)
                            ?.putBoolean("nipoConnecting", false)
                            ?.putString("activeMode", "nipo")
                            ?.putString("status", "NipoVPN connected")
                            ?.putString("nipoStatus", "NipoVPN connected")
                            ?.apply()
                    } else if (masterDnsMode) {
                        publicPrefs?.edit()
                            ?.putLong("downloadKbps", down)
                            ?.putLong("uploadKbps", up)
                            ?.apply()
                    }
                    val label = if (serverName.isBlank()) "Connected" else serverName.take(28)
                    startForeground(
                        1001,
                        NotificationHelper.vpn(this, when { masterDnsMode -> "SIMORGH MasterDNS"; nipoMode -> "SIMORGH NipoVPN"; simpleMode -> "SIMORGH Simple"; else -> "SIMORGH Private" }, "$label • ↓ ${FormatUtils.kbps(down)}  ↑ ${FormatUtils.kbps(up)}", connected = true)
                    )
                } catch (_: Throwable) {
                    // Keep VPN alive even if notification update fails.
                }
            }
        }
    }

    private fun stopVpn(clearSimpleState: Boolean = true) {
        lifecycleGeneration.incrementAndGet()
        running = false
        getSharedPreferences("rkh_vpn_state", MODE_PRIVATE).edit().putBoolean("serviceConnected", false).apply()
        if (clearSimpleState) {
            val publicPrefs = getSharedPreferences("simorgh_public_state", MODE_PRIVATE)
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
        val publicPrefsForNipo = getSharedPreferences("simorgh_public_state", MODE_PRIVATE)
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
        if (publicPrefsForNipo.getBoolean("masterDnsConnected", false) || publicPrefsForNipo.getBoolean("masterDnsConnecting", false) || publicPrefsForNipo.getString("activeMode", "") == "masterdns") {
            clearMasterDnsHealthyRuntimeCache()
            publicPrefsForNipo.edit()
                .putBoolean("masterDnsConnecting", false)
                .putBoolean("masterDnsConnected", false)
                .putString("masterDnsStatus", "MasterDNS disconnected")
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
        runCatching { core?.stop() }
            .onFailure { log("binary core stop failed", it) }
        core = null
        runCatching { inheritedTunPfd?.close() }
            .onSuccess { if (inheritedTunPfd != null) log("Inherited TUN PFD closed") }
            .onFailure { log("Inherited TUN PFD close failed", it) }
        inheritedTunPfd = null
        runCatching { inheritedTunFd?.let { Os.close(it) } }
            .onSuccess { if (inheritedTunFd != null) log("Inherited TUN fd closed") }
            .onFailure { log("Inherited TUN fd close failed", it) }
        inheritedTunFd = null
        runCatching { tun?.close() }
            .onSuccess { log("TUN closed") }
            .onFailure { log("TUN close failed", it) }
        tun = null
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            .onSuccess { log("Foreground notification removed") }
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
    }

    private fun log(message: String, throwable: Throwable? = null) {
        if (throwable == null) Log.d(tag, message) else Log.e(tag, message, throwable)
        if (message.contains("MasterDNS", ignoreCase = true)) {
            MasterDnsRuntimeLog.append(if (throwable == null) message else "$message • ${throwable.javaClass.simpleName}: ${throwable.message ?: ""}")
        } else {
            RKhVpnLogStore.append(this, "VPN", message, throwable)
        }
    }
}
