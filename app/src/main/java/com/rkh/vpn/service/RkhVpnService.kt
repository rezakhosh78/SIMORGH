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
import com.rkh.vpn.data.FormatUtils
import kotlin.concurrent.thread

class RkhVpnService : VpnService() {
    private val tag = "RKhVPN-Service"

    companion object {
        const val ACTION_START = "com.rkh.vpn.START"
        const val ACTION_START_NIPO = "com.rkh.vpn.START_NIPO"
        const val ACTION_STOP = "com.rkh.vpn.STOP"
        const val ACTION_TOGGLE = "com.rkh.vpn.TOGGLE"
        const val EXTRA_RAW_CONFIG = "raw_config"
        const val EXTRA_SERVER_NAME = "server_name"
        const val EXTRA_NIPO_CONFIG = "nipo_config"
    }

    private var tun: ParcelFileDescriptor? = null
    private var core: ProcessCoreManager? = null
    private var inheritedTunFd: java.io.FileDescriptor? = null
    private var notificationThread: Thread? = null
    @Volatile private var running = false

    override fun onCreate() {
        super.onCreate()
        log("Service created • binary-core mode, no libv2ray")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        log("onStartCommand action=${intent?.action ?: "null"}")
        when (intent?.action) {
            ACTION_START -> start(
                intent.getStringExtra(EXTRA_RAW_CONFIG).orEmpty(),
                intent.getStringExtra(EXTRA_SERVER_NAME).orEmpty()
            )
            ACTION_START_NIPO -> startNipo(
                intent.getStringExtra(EXTRA_NIPO_CONFIG).orEmpty()
            )
            ACTION_STOP -> {
                log("Stop action received")
                stopVpn()
                stopSelf()
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
        return START_STICKY
    }


    private fun startNipo(configYaml: String) {
        val cleanConfig = configYaml.replace("﻿", "").trim()
        log("NipoVPN start requested • configChars=${cleanConfig.length} • socks5AgentPort=9992")
        stopVpn(clearSimpleState = true)
        running = true

        runCatching {
            startForeground(
                1001,
                NotificationHelper.vpn(this, "SIMORGH NipoVPN", "Connecting: NipoVPN SOCKS5 9992", connected = true)
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

                runCatching { builder.addDisallowedApplication(packageName) }
                    .onSuccess { log("Excluded app package from NipoVPN loop: $packageName") }
                    .onFailure { log("Could not exclude app from NipoVPN loop", it) }

                val fd = builder.establish() ?: error("Builder.establish returned null. VPN permission may be missing.")
                tun = fd
                log("NipoVPN TUN established. fd=${fd.fd}. mtu=1500")

                val childTunFdNumber = 200
                val childTunFileDescriptor = Os.dup2(fd.fileDescriptor, childTunFdNumber)
                Os.fcntlInt(childTunFileDescriptor, OsConstants.F_SETFD, 0)
                inheritedTunFd = childTunFileDescriptor
                log("Prepared inheritable NipoVPN TUN fd: original=${fd.fd}, child=$childTunFdNumber, FD_CLOEXEC cleared")

                val mgr = ProcessCoreManager(this)
                core = mgr
                val nipoSocksPort = mgr.startNipoAgent(cleanConfig, 9992)
                val config = XrayBinaryConfigBuilder.nipoBridgeConfig(localSocksPort = 10808, nipoSocksPort = nipoSocksPort)
                log("NipoVPN Xray bridge config generated. Flow: Android TUN → tun2socks → Xray 127.0.0.1:10808 → Nipo SOCKS5 127.0.0.1:$nipoSocksPort")
                val socksPort = mgr.startXray(config)
                mgr.startTun2Socks(childTunFdNumber, socksPort)
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
        val cleanRaw = raw.replace("﻿", "").trim()
        val serverLessMode = simpleMode && cleanRaw.contains("Serverless", ignoreCase = true)
        log("Start requested • name=${name.ifBlank { "blank" }} • simpleMode=$simpleMode • serverLessMode=$serverLessMode • rawChars=${cleanRaw.length} • rawJson=${cleanRaw.startsWith("{")} • hasInbounds=${cleanRaw.contains("\"inbounds\"")} • hasOutbounds=${cleanRaw.contains("\"outbounds\"")}")
        stopVpn(clearSimpleState = !simpleMode)
        running = true

        runCatching {
            startForeground(
                1001,
                NotificationHelper.vpn(this, if (name.startsWith("SIMORGH Simple")) "SIMORGH Simple" else "SIMORGH Private", if (name.isBlank()) "Connecting" else "Connecting: $name", connected = true)
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

                runCatching { builder.addDisallowedApplication(packageName) }
                    .onSuccess { log("Excluded app package from VPN loop: $packageName") }
                    .onFailure { log("Could not exclude app from VPN loop", it) }

                val fd = builder.establish() ?: error("Builder.establish returned null. VPN permission may be missing.")
                tun = fd
                log("TUN established. fd=${fd.fd}. Android key icon should be visible now. mtu=1500")
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
                    mgr.startTun2Socks(childTunFdNumber, socksPort)
                    getSharedPreferences("rkh_vpn_state", MODE_PRIVATE).edit().putBoolean("serviceConnected", true).apply()
                    log("VPN binary-core ServerLess started successfully. Traffic should now go: Android VpnService TUN → tun2socks → Xray mixed inbound → ServerLess routing")
                } else {
                    val config = XrayBinaryConfigBuilder.socksConfigFromRaw(raw, 10808, forceGoogleDns = simpleMode && !serverLessMode)
                    log("Xray SOCKS config generated. Length=${config.length} • hasSocksIn=${config.contains("\"socks-in\"")} • hasHttpIn=${config.contains("\"http-in\"")}")
                    val socksPort = mgr.startXray(config)
                    mgr.startTun2Socks(childTunFdNumber, socksPort)
                    getSharedPreferences("rkh_vpn_state", MODE_PRIVATE).edit().putBoolean("serviceConnected", true).apply()
                    log("VPN binary-core started successfully. Traffic should now go: Android TUN → tun2socks → Xray SOCKS → selected config")
                }
            }.onFailure { e ->
                log("VPN start failed", e)
                runCatching {
                    startForeground(
                        1001,
                        NotificationHelper.vpn(this, if (name.startsWith("SIMORGH Simple")) "SIMORGH Simple" else "SIMORGH Private", "Start failed: ${e.message ?: e.javaClass.simpleName}", connected = false)
                    )
                }
                stopVpn()
                stopSelf()
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
        val publicPrefs = if (simpleMode || nipoMode) getSharedPreferences("simorgh_public_state", MODE_PRIVATE) else null
        notificationThread = thread(name = "RKhVPN-notification-speed") {
            var lastRx = TrafficStats.getTotalRxBytes().coerceAtLeast(0L)
            var lastTx = TrafficStats.getTotalTxBytes().coerceAtLeast(0L)
            var lastAt = System.currentTimeMillis()
            val updateIntervalMs = if (simpleMode || nipoMode) 3_000L else 2_500L
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
                    }
                    val label = if (serverName.isBlank()) "Connected" else serverName.take(28)
                    startForeground(
                        1001,
                        NotificationHelper.vpn(this, when { nipoMode -> "SIMORGH NipoVPN"; simpleMode -> "SIMORGH Simple"; else -> "SIMORGH Private" }, "$label • ↓ ${FormatUtils.kbps(down)}  ↑ ${FormatUtils.kbps(up)}", connected = true)
                    )
                } catch (_: Throwable) {
                    // Keep VPN alive even if notification update fails.
                }
            }
        }
    }

    private fun stopVpn(clearSimpleState: Boolean = true) {
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
        log("Stopping VPN")
        runCatching { notificationThread?.interrupt() }
        notificationThread = null
        runCatching { core?.stop() }
            .onFailure { log("binary core stop failed", it) }
        core = null
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
        stopVpn()
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        log("Service destroyed")
        stopVpn()
        super.onDestroy()
    }

    private fun log(message: String, throwable: Throwable? = null) {
        if (throwable == null) Log.d(tag, message) else Log.e(tag, message, throwable)
        RKhVpnLogStore.append(this, "VPN", message, throwable)
    }
}
