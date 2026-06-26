package com.rkh.vpn.service

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.rkh.vpn.core.ProcessCoreManager
import com.rkh.vpn.data.CountryCoordinates
import com.rkh.vpn.data.FormatUtils
import com.rkh.vpn.data.RKhVpnLogStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileDescriptor
import java.net.InetAddress
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.util.Locale
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

class SimorghPublicVpnService : VpnService() {
    companion object {
        const val ACTION_START = "com.rkh.simorgh.PUBLIC_START"
        const val ACTION_START_PROXY = "com.rkh.simorgh.PUBLIC_START_PROXY"
        const val ACTION_STOP = "com.rkh.simorgh.PUBLIC_STOP"
        const val ACTION_NEXT_ROUTE = "com.rkh.simorgh.PUBLIC_NEXT_ROUTE"
        const val ACTION_CF_CONNECT = "com.rkh.simorgh.CF_CONNECT"
        const val EXTRA_CF_IP = "cf_ip"
        const val EXTRA_CF_VLESS = "cf_vless"
        private const val DEFAULT_SOCKS5_PROXY_PORT = 9990
        private const val DEFAULT_HTTP_PROXY_PORT = 9991
        private const val XRAY_SOCKS_PORT = 18088
        private const val XRAY_HTTP_PROXY_PORT = 18089
        private const val DEFAULT_SCAN_PORT = 443
        private const val DEFAULT_MAX_SCAN_IPS = 33000
    }

    private val tag = "SIMORGH-MSP"
    private val prefs by lazy { getSharedPreferences("simorgh_public_state", Context.MODE_PRIVATE) }
    private var tun: ParcelFileDescriptor? = null
    private var inheritedTunFd: FileDescriptor? = null
    private var core: ProcessCoreManager? = null
    private var proxyServer: LocalMspProxy? = null
    private var speedThread: Thread? = null
    private val proxyDownloadBytes = AtomicLong(0L)
    private val proxyUploadBytes = AtomicLong(0L)
    private val cfStartGuard = AtomicBoolean(false)
    private val stopGuard = AtomicBoolean(false)
    private val lifecycleGeneration = AtomicInteger(0)
    @Volatile private var running = false

    private fun shieldedAssetExists(path: String): Boolean {
        return runCatching { assets.open(path).close(); true }.getOrDefault(false)
    }

    private fun readShieldedAssetText(path: String): String {
        val encoded = assets.open(path).use { input -> input.readBytes() }
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

    private fun isCurrentLifecycle(generation: Int): Boolean {
        return running && lifecycleGeneration.get() == generation
    }

    override fun onCreate() {
        super.onCreate()
        log("Public MSP service created on Android ${Build.VERSION.RELEASE} / SDK ${Build.VERSION.SDK_INT}; package=$packageName")
        val xrayOk = nativeLibExists("libxray.so")
        val tun2socksOk = nativeLibExists("libtun2socks.so")
        val rangesOk = shieldedAssetExists("rk_payload/p0.dat")
        log("Diagnostics: mode=RKh-MSP, ranges=$rangesOk, libxray=$xrayOk, libtun2socks=$tun2socksOk")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return try {
            when (intent?.action) {
                ACTION_START -> startPublic(vpnMode = true)
                ACTION_START_PROXY -> startPublic(vpnMode = false)
                ACTION_STOP -> {
                    runCatching { stopPublic() }
                        .onFailure { log("MSP ACTION_STOP recovered safely", it) }
                    runCatching { stopSelf() }
                        .onFailure { log("MSP stopSelf failed safely", it) }
                    return START_NOT_STICKY
                }
                ACTION_NEXT_ROUTE -> {
                    forceNextCleanRoute()
                    return START_STICKY
                }
                ACTION_CF_CONNECT -> {
                    startCfVlessTunnel(intent.getStringExtra(EXTRA_CF_IP).orEmpty(), intent.getStringExtra(EXTRA_CF_VLESS).orEmpty())
                    return START_STICKY
                }
                else -> log("Unknown action=${intent?.action}")
            }
            START_STICKY
        } catch (e: Throwable) {
            log("Safe Public service guard caught onStartCommand crash", e)
            runCatching { stopPublic() }
            prefs.edit()
                .putBoolean("connecting", false)
                .putBoolean("connected", false)
                .putString("status", "MSP start error: ${e.message ?: e.javaClass.simpleName}")
                .putString("lastError", e.message ?: e.javaClass.simpleName)
                .putString("activeMode", "idle")
                .apply()
            stopSelf()
            START_NOT_STICKY
        }
    }


    private fun forceNextCleanRoute() {
        val saved = loadSavedCleanIps()
        if (saved.isEmpty()) {
            log("Manual route switch requested but Clean IP memory is empty")
            return
        }
        val current = prefs.getString("activeRouteIp", "").orEmpty().trim()
        val currentIndex = saved.indexOf(current).takeIf { it >= 0 } ?: -1
        val selected = saved[(currentIndex + 1).floorMod(saved.size)]
        val ping = tcpPingMs(selected, DEFAULT_SCAN_PORT, 2200) ?: prefs.getLong("activeRoutePingMs", -1L)
        prefs.edit().putBoolean("manualRouteLock", true).apply()
        setActiveRoute("", selected, ping)
        saveCleanIp(selected, ping)
        proxyServer?.addIp(selected)
        updateState(status = "Manual route switched to $selected", activeMode = prefs.getString("activeMode", "idle"))
        log("Manual route switch: $current -> $selected • ${if (ping >= 0) "${ping}ms" else "ping n/a"}")
    }

    private fun Int.floorMod(mod: Int): Int = Math.floorMod(this, mod)

    private fun tcpPingMs(ip: String, port: Int, timeoutMs: Int): Long? {
        if (!isIpv4Literal(ip)) return null
        return runCatching {
            val started = System.nanoTime()
            Socket().use { socket ->
                protect(socket)
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(ip, port), timeoutMs)
            }
            ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0L)
        }.getOrNull()
    }

    private fun startPublic(vpnMode: Boolean) {
        log(if (vpnMode) "ACTION_START received • VPN Mode" else "ACTION_START_PROXY received • Proxy Mode only")
        stopGuard.set(false)
        runCatching { stopPublic() }
            .onFailure { log("MSP pre-start cleanup recovered safely", it) }
        stopGuard.set(false)
        running = true
        val generation = lifecycleGeneration.incrementAndGet()
        proxyDownloadBytes.set(0L)
        proxyUploadBytes.set(0L)
        val selectedIsp = prefs.getString("selectedIsp", "AbrArvan CDN and IaaS").orEmpty().ifBlank { "AbrArvan CDN and IaaS" }
        val selectedSnis = (prefs.getStringSet("selectedSnis", setOf("chatgpt.com")) ?: setOf("chatgpt.com"))
            .map { it.trim().lowercase(Locale.US) }
            .filter { it.isNotBlank() }
            .distinct()
            .ifEmpty { listOf("chatgpt.com") }
        val selectedPort = prefs.getInt("selectedPort", DEFAULT_SCAN_PORT).takeIf { it in 1..65535 } ?: DEFAULT_SCAN_PORT
        val maxScanIps = prefs.getInt("maxScanIps", DEFAULT_MAX_SCAN_IPS).coerceIn(1, DEFAULT_MAX_SCAN_IPS)
        val manualIpMode = prefs.getBoolean("manualIpMode", false)
        val ispManualRangeMode = prefs.getBoolean("ispManualRangeMode", false)
        val scanSpeed = prefs.getString("scanSpeed", "normal").orEmpty().ifBlank { "normal" }
        val manualIpsText = prefs.getString("manualIpsText", "").orEmpty()
        val ispManualRangeText = prefs.getString("ispManualRangeText", "").orEmpty()
        val proxyProtocol = prefs.getString("selectedProxyProtocol", "socks5").orEmpty().ifBlank { "socks5" }
            .lowercase(Locale.US).let { if (it == "http") "http" else "socks5" }
        val proxyPortWanted = if (proxyProtocol == "http") DEFAULT_HTTP_PROXY_PORT else DEFAULT_SOCKS5_PROXY_PORT
        val routeStrategy = prefs.getString("routeStrategy", "default").orEmpty().ifBlank { "default" }
        prefs.edit()
            .putString("selectedProxyProtocol", proxyProtocol)
            .putInt("proxyPort", proxyPortWanted)
            .putInt("socks5ProxyPort", DEFAULT_SOCKS5_PROXY_PORT)
            .putInt("httpProxyPort", DEFAULT_HTTP_PROXY_PORT)
            .putString("routeStrategy", routeStrategy)
            .apply()
        val modeLabel = if (vpnMode) "VPN Mode" else "Proxy Mode"
        val savedCleanOnStart = loadSavedCleanIps()

        updateState(
            connected = false,
            connecting = true,
            status = if (vpnMode) "VPN Mode scanning $selectedIsp..." else "Proxy Mode starting 127.0.0.1:$proxyPortWanted...",
            lastError = "",
            cleanIpCount = savedCleanOnStart.size,
            activeMode = if (vpnMode) "vpn" else "proxy"
        )
        val foregroundStarted = runCatching {
            startForeground(
                2001,
                NotificationHelper.publicVpn(this, "SIMORGH", "$modeLabel • RKh-MSP scanning $selectedIsp", connected = true)
            )
        }.onFailure { e ->
            val summary = e.message ?: e.javaClass.simpleName
            log("Public MSP foreground start failed • $summary", e)
            updateState(connected = false, connecting = false, status = "MSP notification error: $summary", lastError = summary)
            running = false
            stopSelf()
        }.isSuccess
        if (!foregroundStarted || !isCurrentLifecycle(generation)) return
        startNotificationSpeedLoop(generation)

        thread(name = "SIMORGH-MSP-start") {
            runCatching {
                if (!isCurrentLifecycle(generation)) {
                    log("MSP start thread aborted because a newer start/stop was requested")
                    return@runCatching
                }
                log("Public MSP start • mode=$modeLabel • ISP=$selectedIsp • SNI=${selectedSnis.joinToString(",")} • scanPort=$selectedPort • localProxy=${proxyProtocol.uppercase(Locale.US)} 127.0.0.1:$proxyPortWanted • maxScan=$maxScanIps • speed=$scanSpeed • manualCleanMode=$manualIpMode • ispManualRangeMode=$ispManualRangeMode • routeStrategy=$routeStrategy")
                val candidates = when {
                    manualIpMode -> {
                        val manualCleanCandidates = parseManualCandidates(manualIpsText, maxCandidates = maxScanIps)
                        log("Manual clean mode enabled from Settings > Manual: using ${manualCleanCandidates.size} IPs as clean pool; ISP/MSP scan will be skipped like older builds")
                        manualCleanCandidates
                    }
                    ispManualRangeMode -> {
                        val manualScanCandidates = parseManualCandidates(ispManualRangeText, maxCandidates = maxScanIps)
                        log("ISP Manual IP/Range enabled: using ${manualScanCandidates.size} candidates as MSP scanner input; TLS/SNI scan will run like Selected ISP")
                        manualScanCandidates
                    }
                    else -> loadCandidatesForIsp(selectedIsp, maxCandidates = maxScanIps)
                }
                if (candidates.isEmpty()) {
                    when {
                        manualIpMode -> error("Manual mode is ON but no valid clean IPs were parsed. Add IPs in Settings > Manual.")
                        ispManualRangeMode -> error("ISP Manual IP/Range is ON but no valid IPs were parsed. Add IPs/CIDR/ranges in Settings > ISP > Manual IP/Range.")
                        else -> error("No IP candidates for ISP=$selectedIsp. Hidden MSP ranges asset may be missing")
                    }
                }
                if (!isCurrentLifecycle(generation)) {
                    log("MSP start aborted after candidate loading because a newer start/stop was requested")
                    return@runCatching
                }
                prefs.edit().putInt("totalCandidates", candidates.size).putInt("scannedCount", 0).apply()
                updateState(status = when {
                    manualIpMode -> "Manual clean mode • loading ${candidates.size} IPs..."
                    ispManualRangeMode -> "Manual IP/Range • MSP scanning ${candidates.size} IPs on $selectedPort..."
                    else -> "Scanning ${candidates.size} IPs on $selectedPort..."
                })

                val pool = Collections.synchronizedList(mutableListOf<String>())
                val savedClean = savedCleanOnStart
                if (savedClean.isNotEmpty()) {
                    synchronized(pool) { savedClean.forEach { if (!pool.contains(it)) pool.add(it) } }
                    prefs.edit().putInt("cleanIpCount", pool.size).apply()
                    log("Loaded ${savedClean.size} saved clean IPs from memory into live pool")
                }
                val firstCleanSeen = AtomicBoolean(false)
                val vpnStarted = AtomicBoolean(false)
                var firstFailure: Throwable? = null

                val localProxy: LocalMspProxy = if (proxyProtocol == "http") {
                    MspHttpProxy(pool, selectedSnis, DEFAULT_HTTP_PROXY_PORT)
                } else {
                    MspSocks5Proxy(pool, selectedSnis, DEFAULT_SOCKS5_PROXY_PORT)
                }
                proxyServer = localProxy
                val proxyPort = localProxy.start()
                if (!isCurrentLifecycle(generation)) {
                    runCatching { localProxy.stop() }
                    log("MSP start aborted after local proxy start because a newer start/stop was requested")
                    return@runCatching
                }
                prefs.edit().putInt("proxyPort", proxyPort).apply()
                log("Local MSP ${proxyProtocol.uppercase(Locale.US)} proxy listening on 127.0.0.1:$proxyPort • mode=$modeLabel • compatible with v2rayNG ${proxyProtocol.uppercase(Locale.US)} outbound")

                if (manualIpMode) {
                    synchronized(pool) { candidates.forEach { if (!pool.contains(it)) pool.add(it) } }
                    candidates.firstOrNull()?.let { setRoute("", it) }
                    prefs.edit()
                        .putInt("cleanIpCount", pool.size)
                        .putInt("totalCandidates", candidates.size)
                        .putInt("scannedCount", candidates.size)
                        .apply()
                    log("Manual clean mode: preloaded ${pool.size} IPs into live pool; MSP scan skipped by design")
                    if (vpnMode) {
                        updateState(status = "Manual clean IPs loaded. Starting VPN tunnel...", cleanIpCount = pool.size, activeMode = "vpn")
                        val xrayConfig = buildXrayHttpProxyConfig(proxyPort, selectedSnis, proxyProtocol)
                        log("Xray config generated: SOCKS=$XRAY_SOCKS_PORT -> ${proxyProtocol.uppercase(Locale.US)} proxy 127.0.0.1:$proxyPort; chars=${xrayConfig.length}")
                        if (!isCurrentLifecycle(generation)) {
                            log("MSP VPN start skipped before TUN because a newer start/stop was requested")
                            return@runCatching
                        }
                        startAndroidTunThroughXray(xrayConfig)
                        updateState(connected = true, connecting = false, status = "VPN Mode connected • manual clean pool=${pool.size}", cleanIpCount = pool.size, activeMode = "vpn")
                        log("VPN Mode active with manual clean IP pool: ${pool.size}. No ISP/TLS scan is running.")
                    } else {
                        updateState(connected = true, connecting = false, status = "Proxy Mode active • manual clean pool=${pool.size} • 127.0.0.1:$proxyPort", cleanIpCount = pool.size, activeMode = "proxy")
                        log("Proxy Mode active with manual clean IP pool. Use external client with ${proxyProtocol.uppercase(Locale.US)} 127.0.0.1:$proxyPort. No MSP scan is running.")
                    }
                    return@runCatching
                }

                if (vpnMode && savedClean.isNotEmpty() && vpnStarted.compareAndSet(false, true)) {
                    updateState(status = "Saved clean IPs loaded. Starting VPN tunnel while scanner continues...", cleanIpCount = pool.size, activeMode = "vpn")
                    val xrayConfig = buildXrayHttpProxyConfig(proxyPort, selectedSnis, proxyProtocol)
                    log("Xray config generated from saved clean IP pool: SOCKS=$XRAY_SOCKS_PORT -> ${proxyProtocol.uppercase(Locale.US)} proxy 127.0.0.1:$proxyPort; saved=${savedClean.size}; chars=${xrayConfig.length}")
                    startAndroidTunThroughXray(xrayConfig)
                    updateState(connected = true, connecting = false, status = "VPN Mode connected • saved pool=${pool.size} • scanner continues", cleanIpCount = pool.size, activeMode = "vpn")
                    log("VPN Mode active from saved clean IPs. Scanner continues and new clean IPs will be added to live pool + memory.")
                }

                if (!vpnMode) {
                    updateState(connected = true, connecting = false, status = "Proxy Mode active • 127.0.0.1:$proxyPort • scanning continues", cleanIpCount = pool.size, activeMode = "proxy")
                    log("Proxy Mode active. Use v2rayNG ${proxyProtocol.uppercase(Locale.US)} proxy server 127.0.0.1:$proxyPort. No Android VPN/TUN/Xray is started by SIMORGH.")
                }

                scanCleanIpsStreaming(candidates, selectedSnis, selectedPort) { result, scanned ->
                    val added = synchronized(pool) {
                        if (pool.contains(result.ip)) false else { pool.add(result.ip); true }
                    }
                    if (added) {
                        saveCleanIp(result.ip, result.latencyMs)
                        prefs.edit().putInt("cleanIpCount", pool.size).putInt("scannedCount", scanned).apply()
                        proxyServer?.addIp(result.ip)
                        log("Clean IP added to live pool and memory: ${result.ip}:$selectedPort • sni=${result.sni} • tls=verified • latency=${result.latencyMs}ms • pool=${pool.size}")
                    }
                    if (firstCleanSeen.compareAndSet(false, true)) {
                        setRoute("", result.ip)
                    }
                    if (vpnMode && vpnStarted.compareAndSet(false, true)) {
                        runCatching {
                            updateState(status = "First clean IP found. Starting VPN tunnel...", cleanIpCount = pool.size, activeMode = "vpn")
                            val xrayConfig = buildXrayHttpProxyConfig(proxyPort, selectedSnis, proxyProtocol)
                            log("Xray config generated: SOCKS=$XRAY_SOCKS_PORT -> ${proxyProtocol.uppercase(Locale.US)} proxy 127.0.0.1:$proxyPort; chars=${xrayConfig.length}")
                            startAndroidTunThroughXray(xrayConfig)
                            updateState(connected = true, connecting = false, status = "VPN Mode connected • scanning continues • ${pool.size} clean IPs", cleanIpCount = pool.size, activeMode = "vpn")
                            log("VPN Mode active from first IP. Scanner continues in background: TUN → tun2socks → Xray internal SOCKS:$XRAY_SOCKS_PORT → Xray proxy outbound → RKh-MSP proxy 127.0.0.1:$proxyPort")
                        }.onFailure { e ->
                            firstFailure = e
                            running = false
                            throw e
                        }
                    } else if (!vpnMode) {
                        updateState(connected = true, connecting = false, status = "Proxy Mode active • scanning $scanned/${candidates.size} • clean=${pool.size}", cleanIpCount = pool.size, activeMode = "proxy")
                    }
                }

                firstFailure?.let { throw it }
                if (vpnMode && !vpnStarted.get()) {
                    error("RKh-MSP did not find clean IPs for ISP=$selectedIsp with SNI=${selectedSnis.joinToString(",")}")
                }
                updateState(status = if (vpnMode) "VPN Mode connected • scan finished • ${pool.size} clean IPs" else "Proxy Mode active • scan finished • ${pool.size} clean IPs", cleanIpCount = pool.size, activeMode = if (vpnMode) "vpn" else "proxy")
                log("Background scan finished: mode=$modeLabel, candidates=${candidates.size}, livePool=${pool.size}")
            }.onFailure { e ->
                val summary = e.message ?: e.javaClass.simpleName
                log("Public MSP start failed • $summary", e)
                updateState(connected = false, connecting = false, status = "Public start failed: $summary", lastError = summary)
                runCatching { startForeground(2001, NotificationHelper.publicVpn(this, "SIMORGH", "Start failed: $summary", connected = false)) }
                stopPublic()
                stopSelf()
            }
        }
    }


    private fun startCfVlessTunnel(cleanIp: String, vlessRaw: String) {
        val ip = cleanIp.trim()
        val displayLabel = cfDisplayLabelForIp(ip)
        if (!cfStartGuard.compareAndSet(false, true)) {
            log("CF Config connect ignored: another CF start is already running • ip=$ip")
            prefs.edit().putString("cfStatus", "CF connect is already starting...").apply()
            runCatching { startForeground(2001, NotificationHelper.publicVpn(this, "SIMORGH", "CF VLESS starting...", connected = true)) }
            return
        }
        try {
            val parsed = parseCfVless(vlessRaw)
            if (!isIpv4Literal(ip) || parsed == null) {
                val reason = "Invalid CF VLESS config or clean IP"
                log("CF Config connect failed: $reason • ip=$ip")
                prefs.edit().putString("cfStatus", reason).apply()
                updateState(connected = false, connecting = false, status = reason, lastError = reason)
                cfStartGuard.set(false)
                return
            }

            // Switching between CF configs must close the previous TUN/Core first.
            // Reset stopGuard around this internal cleanup so a later big Disconnect can still close the new CF tunnel.
            stopGuard.set(false)
            runCatching { stopPublic() }
                .onFailure { log("CF pre-switch cleanup recovered safely", it) }
            stopGuard.set(false)

            running = true
            val generation = lifecycleGeneration.incrementAndGet()
            cfStartGuard.set(true)
            proxyDownloadBytes.set(0L)
            proxyUploadBytes.set(0L)

            updateState(connected = false, connecting = true, status = "CF VLESS connecting via $displayLabel...", lastError = "", activeMode = "vpn")
            prefs.edit().putString("cfConnectingIp", ip).putString("cfDisplayLabel", displayLabel).putString("cfStatus", "Connecting CF VLESS via $displayLabel...").apply()

            val cfForegroundStarted = runCatching {
                startForeground(2001, NotificationHelper.publicVpn(this, "SIMORGH", "CF VLESS • $displayLabel", connected = true))
            }.onFailure { e ->
                val summary = e.message ?: e.javaClass.simpleName
                log("CF VLESS foreground switch failed • $summary", e)
                prefs.edit().putString("cfStatus", "CF notification failed: $summary").apply()
                updateState(connected = false, connecting = false, status = "CF notification failed: $summary", lastError = summary)
                cfStartGuard.set(false)
                runCatching { stopPublic() }
                stopSelf()
            }.isSuccess
            if (!cfForegroundStarted || !isCurrentLifecycle(generation)) {
                cfStartGuard.set(false)
                return
            }

            startNotificationSpeedLoop(generation)
            thread(name = "SIMORGH-CF-vless-start") {
                runCatching {
                    if (!isCurrentLifecycle(generation)) {
                        log("CF VLESS start aborted because a newer start/stop was requested")
                        return@runCatching
                    }
                    val config = buildCfVlessXrayConfig(parsed, ip)
                    log("CF Config Xray generated: address=${parsed.address} -> $ip • sni=${parsed.sni} • network=${parsed.network} • path=${parsed.path} • chars=${config.length}")
                    val ping = tcpPingMs(ip, parsed.port, 2500) ?: -1L
                    if (!isCurrentLifecycle(generation)) {
                        log("CF VLESS start aborted after ping because a newer start/stop was requested")
                        return@runCatching
                    }
                    saveCleanIp(ip, ping)
                    setRoute("", ip)
                    setActiveRoute("CF VLESS", ip, ping)
                    if (!isCurrentLifecycle(generation)) {
                        log("CF VLESS start skipped before TUN because a newer start/stop was requested")
                        return@runCatching
                    }
                    startAndroidTunThroughXray(config)
                    if (!isCurrentLifecycle(generation)) {
                        log("CF VLESS tunnel was started but lifecycle became stale; closing it safely")
                        runCatching { stopPublic() }
                        return@runCatching
                    }
                    prefs.edit().putString("cfConnectingIp", ip).putString("cfDisplayLabel", displayLabel).putString("cfStatus", "Connected CF VLESS via $displayLabel").apply()
                    updateState(connected = true, connecting = false, status = "CF VLESS connected via $displayLabel", cleanIpCount = loadSavedCleanIps().size, activeMode = "vpn")
                    log("CF VLESS VPN Mode active via clean IP $ip")
                }.onFailure { e ->
                    val summary = e.message ?: e.javaClass.simpleName
                    log("CF VLESS start failed • $summary", e)
                    prefs.edit().putString("cfStatus", "CF connect failed: $summary").apply()
                    if (isCurrentLifecycle(generation)) {
                        updateState(connected = false, connecting = false, status = "CF connect failed: $summary", lastError = summary)
                        runCatching { stopPublic() }
                        stopSelf()
                    }
                }
                cfStartGuard.set(false)
                stopGuard.set(false)
            }
        } catch (e: Throwable) {
            val summary = e.message ?: e.javaClass.simpleName
            log("Safe CF connect guard caught crash • $summary", e)
            prefs.edit().putString("cfStatus", "CF connect error: $summary").apply()
            updateState(connected = false, connecting = false, status = "CF connect error: $summary", lastError = summary)
            cfStartGuard.set(false)
            runCatching { stopPublic() }
            stopSelf()
        }
    }

    private fun cfDisplayLabelForIp(ip: String): String {
        if (packageName != "com.rkh.simorgh") return ip
        val cleanIps = loadSavedCleanIps()
            .map { it.trim() }
            .filter { it.isNotBlank() && isIpv4Literal(it) }
            .distinct()
        val cfPings = loadCfPingResultsForDisplay()
        val savedPings = loadSavedCleanIpPings()
        val sorted = cleanIps.sortedWith(
            compareBy<String> { value -> cfLatencyRankForDisplay(cfPings[value]) }
                .thenBy { value -> savedPings[value] ?: Long.MAX_VALUE }
                .thenBy { value -> value }
        )
        val index = sorted.indexOf(ip).takeIf { it >= 0 } ?: 0
        return "CONFIG-${index + 1}"
    }

    private fun loadCfPingResultsForDisplay(): Map<String, String> {
        return prefs.getString("cfPingResults", "").orEmpty()
            .lineSequence()
            .mapNotNull { line ->
                val parts = line.split("=", limit = 2)
                val ip = parts.getOrNull(0)?.trim().orEmpty()
                val value = parts.getOrNull(1)?.trim().orEmpty()
                if (ip.isNotBlank() && value.isNotBlank()) ip to value else null
            }
            .toMap()
    }

    private fun cfLatencyRankForDisplay(value: String?): Long {
        val text = value?.trim().orEmpty()
        if (text.isBlank()) return Long.MAX_VALUE - 2
        if (text.equals("timeout", ignoreCase = true)) return Long.MAX_VALUE - 1
        if (text.equals("failed", ignoreCase = true) || text.equals("fail", ignoreCase = true)) return Long.MAX_VALUE
        return text.removeSuffix("ms").trim().toLongOrNull() ?: Long.MAX_VALUE - 2
    }

    private data class CfVlessConfig(
        val uuid: String,
        val address: String,
        val port: Int,
        val security: String,
        val network: String,
        val encryption: String,
        val flow: String,
        val sni: String,
        val host: String,
        val path: String
    )

    private fun parseCfVless(raw: String): CfVlessConfig? {
        val clean = raw.trim()
        if (!clean.startsWith("vless://", ignoreCase = true)) return null
        return runCatching {
            val uri = URI(clean)
            val uuid = uri.userInfo.orEmpty().substringBefore(':').trim()
            val address = uri.host.orEmpty().trim()
            val port = if (uri.port in 1..65535) uri.port else 443
            val q = parseQuery(uri.rawQuery.orEmpty())
            val security = (q["security"] ?: "tls").ifBlank { "tls" }
            val network = (q["type"] ?: q["network"] ?: "ws").ifBlank { "ws" }
            val encryption = (q["encryption"] ?: "none").ifBlank { "none" }
            val flow = (q["flow"] ?: "").trim()
            val sni = (q["sni"] ?: q["servername"] ?: q["serverName"] ?: q["host"] ?: q["Host"] ?: address).trim()
            val host = (q["host"] ?: q["Host"] ?: sni).trim()
            val path = (q["path"] ?: "/").ifBlank { "/" }
            if (uuid.isBlank() || address.isBlank() || sni.isBlank()) null else CfVlessConfig(uuid, address, port, security, network, encryption, flow, sni, host, path)
        }.getOrNull()
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split('&').mapNotNull { part ->
            val key = part.substringBefore('=', "").trim()
            if (key.isBlank()) return@mapNotNull null
            val rawValue = part.substringAfter('=', "")
            val value = runCatching { URLDecoder.decode(rawValue, "UTF-8") }.getOrDefault(rawValue)
            key to value
        }.toMap()
    }

    private fun buildCfVlessXrayConfig(cf: CfVlessConfig, cleanIp: String): String {
        val socksInbound = JSONObject().apply {
            put("tag", "socks-in")
            put("listen", "127.0.0.1")
            put("port", XRAY_SOCKS_PORT)
            put("protocol", "socks")
            put("settings", JSONObject().apply { put("udp", true); put("auth", "noauth") })
            put("sniffing", JSONObject().apply {
                put("enabled", true)
                put("destOverride", JSONArray(listOf("http", "tls", "quic")))
                put("routeOnly", false)
            })
        }
        val httpInbound = JSONObject().apply {
            put("tag", "http-in")
            put("listen", "127.0.0.1")
            put("port", XRAY_HTTP_PROXY_PORT)
            put("protocol", "http")
            put("settings", JSONObject())
        }
        val user = JSONObject().apply {
            put("id", cf.uuid)
            put("encryption", cf.encryption)
            if (cf.flow.isNotBlank()) put("flow", cf.flow)
        }
        val stream = JSONObject().apply {
            put("network", cf.network)
            put("security", cf.security)
            if (cf.security.equals("tls", ignoreCase = true)) {
                put("tlsSettings", JSONObject().apply {
                    put("serverName", cf.sni)
                    put("allowInsecure", false)
                    put("fingerprint", "chrome")
                })
            }
            if (cf.network.equals("ws", ignoreCase = true)) {
                put("wsSettings", JSONObject().apply {
                    put("path", cf.path)
                    put("headers", JSONObject().apply { put("Host", cf.host) })
                })
            }
        }
        val outbound = JSONObject().apply {
            put("tag", "cf-vless-out")
            put("protocol", "vless")
            put("settings", JSONObject().apply {
                put("vnext", JSONArray().put(JSONObject().apply {
                    put("address", cleanIp)
                    put("port", cf.port)
                    put("users", JSONArray().put(user))
                }))
            })
            put("streamSettings", stream)
        }
        val block = JSONObject().apply { put("tag", "block-out"); put("protocol", "blackhole"); put("settings", JSONObject()) }
        val quicBlockRule = JSONObject().apply { put("type", "field"); put("network", "udp"); put("port", "443"); put("outboundTag", "block-out") }
        return JSONObject().apply {
            put("log", JSONObject().apply { put("loglevel", "warning") })
            put("inbounds", JSONArray().put(socksInbound).put(httpInbound))
            put("outbounds", JSONArray().put(outbound).put(block))
            put("routing", JSONObject().apply { put("domainStrategy", "AsIs"); put("rules", JSONArray().put(quicBlockRule)) })
        }.toString(2)
    }

    private fun applyTunnelAppPolicy(builder: Builder, label: String) {
        val section = "msp"
        val mode = prefs.getString("tunnelAppMode_$section", "all").orEmpty().ifBlank { "all" }
        val packages = if (mode == "all") emptySet<String>() else prefs.getStringSet("tunnelAppPackages_${section}_$mode", emptySet<String>()) ?: emptySet<String>()
        if (mode == "only" && packages.isNotEmpty()) {
            packages.forEach { pkg -> runCatching { builder.addAllowedApplication(pkg) }.onFailure { log("Tunnel ONLY add failed for $pkg", it) } }
            log("Tunnel app policy for $label/$section: ONLY ${packages.size} app(s)")
        } else {
            runCatching { builder.addDisallowedApplication(packageName) }
                .onSuccess { log("VPN exclude SIMORGH: OK • package=$packageName • scanner/proxy sockets will use direct network") }
                .onFailure { log("VPN exclude SIMORGH: FAILED • scanner may loop through VPN", it) }
            if (mode == "exclude") {
                packages.forEach { pkg -> runCatching { builder.addDisallowedApplication(pkg) }.onFailure { log("Tunnel EXCLUDE add failed for $pkg", it) } }
                log("Tunnel app policy for $label/$section: EXCLUDE ${packages.size} app(s)")
            } else {
                log("Tunnel app policy for $label/$section: ALL apps")
            }
        }
    }

    private fun startAndroidTunThroughXray(xrayConfig: String) {
        log("Building Android VPN TUN interface for Public MSP")
        var localFd: ParcelFileDescriptor? = null
        var localInheritedFd: FileDescriptor? = null
        var localMgr: ProcessCoreManager? = null
        try {
            val builder = Builder()
                .setSession("SIMORGH")
                .addAddress("172.30.0.2", 30)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
                .addRoute("0.0.0.0", 0)
                .allowFamily(OsConstants.AF_INET)
                .setMtu(1500)
            applyTunnelAppPolicy(builder, "MSP")
            val fd = builder.establish() ?: error("Builder.establish returned null. VPN permission may be missing.")
            localFd = fd
            tun = fd
            log("TUN established for Public MSP. fd=${fd.fd}")
            val childTunFdNumber = 202
            val childTunFileDescriptor = Os.dup2(fd.fileDescriptor, childTunFdNumber)
            localInheritedFd = childTunFileDescriptor
            Os.fcntlInt(childTunFileDescriptor, OsConstants.F_SETFD, 0)
            inheritedTunFd = childTunFileDescriptor
            log("Prepared inheritable Public TUN fd: original=${fd.fd}, child=$childTunFdNumber")
            val mgr = ProcessCoreManager(this)
            localMgr = mgr
            core = mgr
            val socksPort = mgr.startXray(xrayConfig)
            mgr.startTun2Socks(childTunFdNumber, socksPort)
        } catch (e: Throwable) {
            log("Public MSP TUN/Xray start failed safely", e)
            runCatching { localMgr?.stop() }
            if (core === localMgr) core = null
            runCatching { localInheritedFd?.let { Os.close(it) } }
            if (inheritedTunFd === localInheritedFd) inheritedTunFd = null
            runCatching { localFd?.close() }
            if (tun === localFd) tun = null
            throw e
        }
    }

    private fun buildXrayHttpProxyConfig(proxyPort: Int, selectedSnis: List<String>, proxyProtocol: String): String {
        val socksInbound = JSONObject().apply {
            put("tag", "socks-in")
            put("listen", "127.0.0.1")
            put("port", XRAY_SOCKS_PORT)
            put("protocol", "socks")
            put("settings", JSONObject().apply { put("udp", true); put("auth", "noauth") })
            // v2rayNG-compatible: recover TLS/HTTP host from TUN traffic and pass domain to SOCKS5 proxy when possible.
            put("sniffing", JSONObject().apply {
                put("enabled", true)
                put("destOverride", JSONArray(listOf("http", "tls", "quic")))
                put("routeOnly", false)
            })
        }
        val httpInbound = JSONObject().apply {
            put("tag", "http-in")
            put("listen", "127.0.0.1")
            put("port", XRAY_HTTP_PROXY_PORT)
            put("protocol", "http")
            put("settings", JSONObject())
        }
        val mspOutbound = JSONObject().apply {
            put("tag", "msp-proxy-out")
            put("protocol", if (proxyProtocol == "http") "http" else "socks")
            put("settings", JSONObject().apply {
                put("servers", JSONArray().put(JSONObject().apply {
                    put("address", "127.0.0.1")
                    put("port", proxyPort)
                }))
            })
        }
        val directOutbound = JSONObject().apply {
            put("tag", "direct-out")
            put("protocol", "freedom")
            put("settings", JSONObject())
        }
        val blockOutbound = JSONObject().apply {
            put("tag", "block-out")
            put("protocol", "blackhole")
            put("settings", JSONObject())
        }
        val dnsRule = JSONObject().apply {
            put("type", "field")
            put("network", "udp")
            put("port", "53")
            put("outboundTag", "direct-out")
        }
        val quicBlockRule = JSONObject().apply {
            put("type", "field")
            put("network", "udp")
            put("port", "443")
            put("outboundTag", "block-out")
        }
        log("Xray routing: V2RayNG-compatible ${proxyProtocol.uppercase(Locale.US)} proxy mode; default=msp-proxy-out 127.0.0.1:$proxyPort; UDP/443 blocked; DNS direct")
        return JSONObject().apply {
            put("log", JSONObject().apply { put("loglevel", "warning") })
            put("inbounds", JSONArray().put(socksInbound).put(httpInbound))
            // Match v2rayNG SOCKS5 proxy behaviour: all TCP falls back to the MSP local proxy.
            // The MSP proxy itself decides whether to use a clean IP or direct fallback.
            put("outbounds", JSONArray().put(mspOutbound).put(directOutbound).put(blockOutbound))
            put("routing", JSONObject().apply {
                put("domainStrategy", "AsIs")
                put("rules", JSONArray().put(dnsRule).put(quicBlockRule))
            })
        }.toString(2)
    }

    private fun buildMspDomainRules(selectedSnis: List<String>): JSONArray {
        val domains = linkedSetOf<String>()
        fun addDomain(host: String) {
            val h = host.trim().lowercase(Locale.US).removeSuffix(".")
            if (h.isBlank() || isIpv4Literal(h) || h.contains(':')) return
            domains += "domain:$h"
        }
        selectedSnis.forEach { addDomain(it) }
        listOf(
            "chatgpt.com",
            "openai.com",
            "oaistatic.com",
            "oaiusercontent.com",
            "chat.openai.com",
            "auth.openai.com",
            "auth0.openai.com",
            "platform.openai.com",
            "api.openai.com",
            "cdn.openai.com"
        ).forEach { addDomain(it) }
        return JSONArray(domains.toList())
    }


    private data class ScanResult(val ip: String, val sni: String, val latencyMs: Long, val statusCode: Int)

    private fun scanCleanIpsStreaming(
        candidates: List<String>,
        snis: List<String>,
        port: Int,
        onClean: (ScanResult, Int) -> Unit
    ) {
        val start = System.currentTimeMillis()
        val seenClean = Collections.synchronizedSet(mutableSetOf<String>())
        val speed = prefs.getString("scanSpeed", "normal").orEmpty().ifBlank { "normal" }
        val workerCount = when (speed.lowercase(Locale.US)) {
            "slow" -> 48
            "fast" -> 400
            else -> 160
        }
        log("Scanner profile: RKh-MSP-compatible TLS scanner • speed=$speed • workers=$workerCount • timeout=2.5s/10s hard • no TrustAll • sequential candidate order")
        val executor = Executors.newFixedThreadPool(workerCount)
        val completed = AtomicInteger(0)
        val cleanCount = AtomicInteger(0)
        val futures = candidates.map { ip ->
            executor.submit {
                if (!running) return@submit
                val res = testIpWithSnis(ip, snis, port)
                val done = completed.incrementAndGet()
                prefs.edit().putInt("scannedCount", done).apply()
                if (res != null && seenClean.add(res.ip)) {
                    val count = cleanCount.incrementAndGet()
                    log("Clean IP found: ${res.ip}:$port • sni=${res.sni} • tls=verified • latency=${res.latencyMs}ms • clean=$count • scanned=$done/${candidates.size}")
                    updateState(status = if (prefs.getBoolean("connected", false)) "Connected • scanning $done/${candidates.size} • clean=$count" else "Found first routes • scanning $done/${candidates.size}", cleanIpCount = count)
                    onClean(res, done)
                } else if (done % 100 == 0) {
                    log("Scan progress: $done/${candidates.size} • clean=${cleanCount.get()}")
                    updateState(status = if (prefs.getBoolean("connected", false)) "Connected • scanning $done/${candidates.size} • clean=${cleanCount.get()}" else "Scanning $done/${candidates.size} • clean=${cleanCount.get()}", cleanIpCount = cleanCount.get())
                }
            }
        }
        for (f in futures) {
            if (!running) break
            runCatching { f.get() }
        }
        executor.shutdownNow()
    }

    private fun testIpWithSnis(ip: String, snis: List<String>, port: Int): ScanResult? {
        for (sni in snis) {
            val started = System.nanoTime()
            try {
                val verified = verifySniRoute(ip, sni, port, timeoutMs = 2500, sendHttpProbe = false)
                if (verified) {
                    val ms = (System.nanoTime() - started) / 1_000_000L
                    return ScanResult(ip, sni, ms, 0)
                }
            } catch (_: Throwable) {
                // Match RKh-MSP Windows behavior: failed TLS/certificate/hostname validation = not clean.
            }
        }
        return null
    }

    private fun verifiedSslContext(): SSLContext = SSLContext.getInstance("TLS").apply { init(null, null, null) }

    /**
     * Same idea as RKh-MSP Windows verify_sni(): a route is clean only if TLS succeeds
     * with real certificate + hostname verification for the requested SNI/domain.
     * Do not use TrustAll here; TrustAll was the reason Android found many false clean IPs.
     */
    private fun verifySniRoute(
        ip: String,
        domain: String,
        port: Int,
        timeoutMs: Int = 2500,
        sendHttpProbe: Boolean = false
    ): Boolean {
        val normalizedDomain = domain.trim().lowercase(Locale.US).removeSuffix(".")
        if (normalizedDomain.isBlank()) return false
        if (verifySniOnce(ip, normalizedDomain, port, timeoutMs, sendHttpProbe)) return true

        // RKh-MSP.py fallback: if certificate validation fails for a subdomain,
        // try the base domain as SNI. This is important for CDN-hosted targets.
        val parts = normalizedDomain.split('.').filter { it.isNotBlank() }
        if (parts.size > 2) {
            val baseSni = parts.takeLast(2).joinToString(".")
            if (baseSni != normalizedDomain && verifySniOnce(ip, baseSni, port, timeoutMs, sendHttpProbe = false)) {
                return true
            }
        }
        return false
    }

    private fun verifySniOnce(
        ip: String,
        domain: String,
        port: Int,
        timeoutMs: Int,
        sendHttpProbe: Boolean
    ): Boolean {
        val raw = Socket()
        var sslSocket: SSLSocket? = null
        return try {
            protect(raw)
            raw.tcpNoDelay = true
            raw.soTimeout = timeoutMs
            raw.connect(InetSocketAddress(ip, port), timeoutMs)
            sslSocket = verifiedSslContext().socketFactory.createSocket(raw, domain, port, true) as SSLSocket
            sslSocket.sslParameters = sslSocket.sslParameters.apply {
                serverNames = listOf(SNIHostName(domain))
                endpointIdentificationAlgorithm = "HTTPS"
            }
            sslSocket.soTimeout = timeoutMs
            sslSocket.startHandshake()

            if (sendHttpProbe) {
                val out = BufferedOutputStream(sslSocket.getOutputStream())
                out.write("HEAD / HTTP/1.1\r\nHost: $domain\r\nUser-Agent: SIMORGH-MSP/1.1\r\nConnection: close\r\n\r\n".toByteArray())
                out.flush()
                val statusLine = readAsciiLine(BufferedInputStream(sslSocket.getInputStream())).orEmpty()
                if (!statusLine.startsWith("HTTP/", ignoreCase = true)) return false
                // Match RKh-MSP.py behavior for Google-like domains: avoid known blocked status codes.
                if (statusLine.contains(" 403 ") || statusLine.contains(" 451 ")) return false
            }
            true
        } catch (_: Throwable) {
            false
        } finally {
            runCatching { sslSocket?.close() }
            runCatching { raw.close() }
        }
    }

    private fun parseManualCandidates(text: String, maxCandidates: Int): List<String> {
        val out = linkedSetOf<String>()
        text.lineSequence()
            .flatMap { it.split(',', ';', ' ', '\t').asSequence() }
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .forEach { token ->
                if (out.size >= maxCandidates) return@forEach
                when {
                    token.contains('/') -> cidrSample(token, perRange = maxCandidates - out.size).forEach { ip -> if (out.size < maxCandidates) out += ip }
                    token.contains('-') -> rangeSample(token, maxCount = maxCandidates - out.size).forEach { ip -> if (out.size < maxCandidates) out += ip }
                    isIpv4Literal(token) -> out += token
                }
            }
        return out.take(maxCandidates)
    }

    private fun rangeSample(token: String, maxCount: Int): List<String> {
        val parts = token.split('-', limit = 2).map { it.trim() }
        if (parts.size != 2 || maxCount <= 0) return emptyList()
        val start = runCatching { ipv4ToLong(parts[0]) }.getOrNull() ?: return emptyList()
        val end = if (isIpv4Literal(parts[1])) {
            runCatching { ipv4ToLong(parts[1]) }.getOrNull() ?: return emptyList()
        } else {
            val prefix = parts[0].substringBeforeLast('.', "")
            val lastOctet = parts[1].toLongOrNull()?.takeIf { it in 0..255 } ?: return emptyList()
            runCatching { ipv4ToLong("$prefix.$lastOctet") }.getOrNull() ?: return emptyList()
        }
        val first = min(start, end)
        val last = max(start, end)
        val total = last - first + 1
        val count = min(maxCount.toLong(), total).toInt()
        val step = max(1L, total / count.coerceAtLeast(1))
        val out = mutableListOf<String>()
        var x = first
        while (x <= last && out.size < count) {
            out += longToIpv4(x)
            x += step
        }
        return out
    }

    private fun loadCandidatesForIsp(selectedIsp: String, maxCandidates: Int): List<String> {
        val ranges = mutableListOf<String>()
        runCatching {
            readShieldedAssetText("rk_payload/p0.dat").lineSequence().drop(1).forEach { line: String ->
                val cols = parseCsvLine(line)
                if (cols.size >= 9 && matchesIsp(cols, selectedIsp)) ranges.add(cols[0])
            }
        }.onFailure { log("Failed to read hidden MSP ranges asset", it) }

        val candidates = linkedSetOf<String>()
        // RKh-MSP.py expands targets sequentially in CSV/range order and stops at MAX_EXPAND_IPS.
        // Older SIMORGH builds sampled each range and then shuffled; this found different IPs than
        // the Windows/Termux scanner. Keep the exact expansion order so Android and Python scan
        // the same candidates first.
        for (target in ranges) {
            if (candidates.size >= maxCandidates) break
            expandTargetSequential(target, remaining = maxCandidates - candidates.size).forEach { ip ->
                if (candidates.size < maxCandidates) candidates.add(ip)
            }
        }
        log("Loaded ${candidates.size} candidates from ${ranges.size} ranges for ISP=$selectedIsp; cap=$maxCandidates; order=RKh-MSP-sequential; shuffle=false")
        return candidates.take(maxCandidates)
    }

    private fun matchesIsp(cols: List<String>, selectedIsp: String): Boolean {
        val asName = cols.getOrNull(6).orEmpty()
        if (selectedIsp.equals("Auto", ignoreCase = true)) return cols.getOrNull(2).equals("IR", ignoreCase = true)
        if (asName.equals(selectedIsp, ignoreCase = true)) return true
        val haystack = cols.joinToString(" ").lowercase(Locale.US)
        return haystack.contains(selectedIsp.lowercase(Locale.US))
    }

    private fun expandTargetSequential(target: String, remaining: Int): List<String> {
        if (remaining <= 0) return emptyList()
        val token = target.trim()
        if (token.isBlank()) return emptyList()
        return when {
            token.contains('/') -> cidrExpandSequential(token, remaining)
            token.contains('-') -> rangeExpandSequential(token, remaining)
            isIpv4Literal(token) -> listOf(token)
            else -> emptyList()
        }
    }

    private fun cidrExpandSequential(cidr: String, maxCount: Int): List<String> {
        val parts = cidr.split('/')
        if (parts.size != 2 || maxCount <= 0) return emptyList()
        val base = runCatching { ipv4ToLong(parts[0]) }.getOrNull() ?: return emptyList()
        val prefix = parts[1].toIntOrNull()?.coerceIn(0, 32) ?: return emptyList()
        val size = if (prefix == 32) 1L else 1L shl (32 - prefix)
        val network = base and ((-1L shl (32 - prefix)) and 0xffffffffL)
        // Match Python ipaddress.ip_network iteration: include network/broadcast too.
        val count = min(maxCount.toLong(), size).toInt()
        val out = ArrayList<String>(count)
        var x = network
        while (out.size < count) {
            out += longToIpv4(x)
            x++
        }
        return out
    }

    private fun rangeExpandSequential(token: String, maxCount: Int): List<String> {
        val parts = token.split('-', limit = 2).map { it.trim() }
        if (parts.size != 2 || maxCount <= 0) return emptyList()
        val start = runCatching { ipv4ToLong(parts[0]) }.getOrNull() ?: return emptyList()
        val end = if (isIpv4Literal(parts[1])) {
            runCatching { ipv4ToLong(parts[1]) }.getOrNull() ?: return emptyList()
        } else {
            val prefix = parts[0].substringBeforeLast('.', "")
            val lastOctet = parts[1].toLongOrNull()?.takeIf { it in 0..255 } ?: return emptyList()
            runCatching { ipv4ToLong("$prefix.$lastOctet") }.getOrNull() ?: return emptyList()
        }
        val first = min(start, end)
        val last = max(start, end)
        val count = min(maxCount.toLong(), last - first + 1).toInt()
        val out = ArrayList<String>(count)
        var x = first
        while (x <= last && out.size < count) {
            out += longToIpv4(x)
            x++
        }
        return out
    }

    private fun cidrSample(cidr: String, perRange: Int): List<String> {
        val parts = cidr.split('/')
        if (parts.size != 2) return emptyList()
        val base = runCatching { ipv4ToLong(parts[0]) }.getOrNull() ?: return emptyList()
        val prefix = parts[1].toIntOrNull()?.coerceIn(0, 32) ?: return emptyList()
        val size = if (prefix == 32) 1L else 1L shl (32 - prefix)
        val network = base and (-1L shl (32 - prefix)).and(0xffffffffL)
        val first = if (size > 2) network + 1 else network
        val last = if (size > 2) network + size - 2 else network + size - 1
        if (last < first) return emptyList()
        val count = min(perRange.toLong(), last - first + 1).toInt()
        if (count <= 0) return emptyList()
        val step = max(1L, (last - first + 1) / count)
        val out = mutableListOf<String>()
        var x = first
        while (x <= last && out.size < count) {
            out += longToIpv4(x)
            x += step
        }
        return out
    }

    private fun ipv4ToLong(ip: String): Long {
        val p = ip.split('.')
        require(p.size == 4)
        return ((p[0].toLong() and 255) shl 24) or ((p[1].toLong() and 255) shl 16) or ((p[2].toLong() and 255) shl 8) or (p[3].toLong() and 255)
    }

    private fun longToIpv4(value: Long): String = listOf(
        (value ushr 24) and 255,
        (value ushr 16) and 255,
        (value ushr 8) and 255,
        value and 255
    ).joinToString(".")

    private fun parseCsvLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> { cur.append('"'); i++ }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> { out += cur.toString(); cur.clear() }
                else -> cur.append(c)
            }
            i++
        }
        out += cur.toString()
        return out
    }

    private interface LocalMspProxy {
        fun start(): Int
        fun stop()
        fun addIp(ip: String)
    }

    private inner class MspSocks5Proxy(
        private val cleanIps: MutableList<String>,
        private val snis: List<String>,
        private val preferredPort: Int
    ) : LocalMspProxy {
        private var server: ServerSocket? = null
        private var proxyThread: Thread? = null
        private val index = AtomicInteger(0)
        private val routeFailures = ConcurrentHashMap<String, AtomicInteger>()
        private val exactRoutes = ConcurrentHashMap<String, String>()
        private val wildcardRoutes = ConcurrentHashMap<String, String>()
        private val perHostCursor = ConcurrentHashMap<String, AtomicInteger>()
        private val hostRouteCache = ConcurrentHashMap<String, String>()
        private val roundRobinCursor = AtomicInteger(0)
        private val ipFailures = ConcurrentHashMap<String, AtomicInteger>()
        private val activeCleanIp = AtomicReference<String>(prefs.getString("activeRouteIp", "").orEmpty())
        private val healthCursor = AtomicInteger(0)
        private var healthThread: Thread? = null

        override fun start(): Int {
            val loopback = InetAddress.getByName("127.0.0.1")
            server = runCatching { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(loopback, preferredPort)) } }
                .onFailure { log("[MSP SOCKS5] preferred port $preferredPort is busy/unavailable; falling back to a random local port. If Termux RKh-MSP is running on 9990, stop it first.", it) }
                .getOrElse { ServerSocket(0, 50, loopback) }
            val port = server!!.localPort
            proxyThread = thread(name = "SIMORGH-MSP-socks5-proxy") {
                while (running) {
                    val client = runCatching { server?.accept() }.getOrNull() ?: break
                    thread(name = "SIMORGH-MSP-socks5-client") {
                        runCatching { handleClient(client) }
                            .onFailure { e -> log("[MSP SOCKS5] client worker crashed safely", e) }
                        runCatching { client.close() }
                    }
                }
            }
            startHealthLoop()
            return port
        }

        override fun stop() {
            runCatching { server?.close() }
            server = null
            runCatching { proxyThread?.interrupt() }
            proxyThread = null
            runCatching { healthThread?.interrupt() }
            healthThread = null
        }

        override fun addIp(ip: String) {
            synchronized(cleanIps) {
                if (!cleanIps.contains(ip)) {
                    cleanIps.add(ip)
                    routeFailures.remove(ip)
                }
            }
        }

        private fun routeCandidatesForHost(host: String, port: Int): List<String> {
            val h = host.trim().lowercase(Locale.US).removeSuffix(".")
            val out = linkedSetOf<String>()
            if (h == "localhost" || h == "127.0.0.1") return listOf(h)
            val locked = prefs.getBoolean("manualRouteLock", false)
            val lockedIp = prefs.getString("activeRouteIp", "").orEmpty().trim()
            if (locked && lockedIp.isNotBlank() && lockedIp in cleanSnapshot()) {
                return listOf(lockedIp)
            }
            if (port == 443 && h.isNotBlank() && !isIpv4Literal(h) && !h.contains(':')) {
                hostRouteCache[h]?.takeIf { cleanSnapshot().contains(it) }?.let { out += it }
                hostRouteCache[baseDomain(h)]?.takeIf { cleanSnapshot().contains(it) }?.let { out += it }
                val active = prefs.getString("activeRouteIp", "").orEmpty().trim()
                if (active.isNotBlank() && active in cleanSnapshot()) out += active
                orderedCleanCandidates().forEach { out += it }
            }
            if (h.isNotBlank()) out += h
            return out.toList()
        }

        private fun orderedCleanCandidates(): List<String> {
            val pool = cleanSnapshot().filter { isIpv4Literal(it) }
            if (pool.size <= 1) return pool
            val strategy = prefs.getString("routeStrategy", "default").orEmpty().ifBlank { "default" }
            val pingMap = loadSavedCleanIpPings()
            return when (strategy) {
                "random" -> pool.shuffled()
                "round_robin" -> {
                    val start = Math.floorMod(roundRobinCursor.getAndIncrement(), pool.size)
                    List(pool.size) { idx -> pool[(start + idx) % pool.size] }
                }
                "least_loss" -> pool.sortedWith(compareBy<String> { ipFailures[it]?.get() ?: 0 })
                "lowest_latency" -> pool.sortedWith(compareBy<String> { pingMap[it] ?: Long.MAX_VALUE })
                "hybrid_score" -> pool.sortedWith(compareBy<String> { ipFailures[it]?.get() ?: 0 }.thenBy { pingMap[it] ?: Long.MAX_VALUE })
                else -> pool
            }
        }

        private fun setActiveCleanIp(ip: String, pingMs: Long) {
            if (!isIpv4Literal(ip)) return
            activeCleanIp.set(ip)
            saveCleanIp(ip, pingMs)
            setActiveRoute("", ip, pingMs)
        }

        private fun clearActiveCleanIp(ip: String) {
            if (activeCleanIp.compareAndSet(ip, "")) {
                prefs.edit().putString("activeRouteIp", "").putLong("activeRoutePingMs", -1L).apply()
                log("[MSP SOCKS5] active clean IP failed; looking for replacement • old=$ip")
            }
        }

        private fun tcpProbe(ip: String, port: Int = 443, timeoutMs: Int = 2500): Long? {
            if (!isIpv4Literal(ip)) return null
            return runCatching {
                val started = System.nanoTime()
                connectTcpIpv4(ip, port, timeoutMs).use { }
                ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0L)
            }.getOrNull()
        }

        private fun startHealthLoop() {
            if (healthThread != null) return
            healthThread = thread(name = "SIMORGH-MSP-clean-ip-health") {
                while (running) {
                    try {
                        val pool = cleanSnapshot().filter { isIpv4Literal(it) }
                        val active = activeCleanIp.get().trim()
                        var activeOk = false
                        if (active.isNotBlank() && active in pool) {
                            val ping = tcpProbe(active, DEFAULT_SCAN_PORT, 2200)
                            if (ping != null) {
                                activeOk = true
                                setActiveRoute("", active, ping)
                                saveCleanIp(active, ping)
                            } else {
                                clearActiveCleanIp(active)
                            }
                        }

                        // Continuously health-check the rest. If active is healthy, test a rotating batch.
                        // If active failed, walk all clean IPs until a replacement works.
                        var replacement: Pair<String, Long>? = null
                        val others = pool.filter { it != active }
                        val healthBatch = if (activeOk && others.isNotEmpty()) {
                            val start = Math.floorMod(healthCursor.getAndAdd(8), others.size)
                            List(min(8, others.size)) { offset -> others[(start + offset) % others.size] }
                        } else {
                            orderedCleanCandidates().filter { it != active }
                        }
                        healthBatch.forEach { ip ->
                            if (!running) return@forEach
                            val ping = tcpProbe(ip, DEFAULT_SCAN_PORT, 2200)
                            if (ping != null) {
                                saveCleanIp(ip, ping)
                                if (!activeOk && replacement == null) replacement = ip to ping
                            }
                        }
                        if (!activeOk && replacement != null) {
                            val (ip, ping) = replacement!!
                            setActiveCleanIp(ip, ping)
                            log("[MSP SOCKS5] replacement clean IP selected by route watchdog -> $ip • ${ping}ms")
                        }
                        Thread.sleep(10_000)
                    } catch (_: InterruptedException) {
                        break
                    } catch (t: Throwable) {
                        log("[MSP SOCKS5] health-check loop warning", t)
                        runCatching { Thread.sleep(5000) }
                    }
                }
            }
        }

        private fun cleanSnapshot(): List<String> = synchronized(cleanIps) { cleanIps.toList() }

        private fun connectBestRoute(host: String, port: Int): Triple<Socket, String, Long> {
            val normalizedHost = host.trim().lowercase(Locale.US).removeSuffix(".")
            val candidates = routeCandidatesForHost(normalizedHost, port)
            var lastError: Throwable? = null
            var tries = 0
            for (candidate in candidates) {
                if (!running) error("proxy stopped")
                tries++
                try {
                    if (port == 443 && isIpv4Literal(candidate) && normalizedHost.isNotBlank() && !isIpv4Literal(normalizedHost) && !normalizedHost.contains(':')) {
                        val okForHost = verifySniRoute(candidate, normalizedHost, port, timeoutMs = 2500, sendHttpProbe = false)
                        if (!okForHost) {
                            ipFailures.getOrPut(candidate) { AtomicInteger(0) }.incrementAndGet()
                            markFailure("$normalizedHost via $candidate", "SNI verify failed")
                            continue
                        }
                    }
                    val started = System.nanoTime()
                    val socket = connectTcpIpv4(candidate, port, timeoutMs = 7000)
                    val pingMs = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0L)
                    if (port == 443 && isIpv4Literal(candidate)) {
                        setActiveCleanIp(candidate, pingMs)
                        hostRouteCache[normalizedHost] = candidate
                        hostRouteCache[baseDomain(normalizedHost)] = candidate
                    }
                    val strategyLabel = prefs.getString("routeStrategy", "default") ?: "default"
                    if (tries > 1) log("[MSP SOCKS5] failover selected clean IP for $normalizedHost:$port -> $candidate after $tries tries • strategy=$strategyLabel • ping=${pingMs}ms")
                    return Triple(socket, candidate, pingMs)
                } catch (t: Throwable) {
                    lastError = t
                    markFailure("$normalizedHost via $candidate", "${t.javaClass.simpleName}: ${t.message}")
                    if (isIpv4Literal(candidate)) {
                        ipFailures.getOrPut(candidate) { AtomicInteger(0) }.incrementAndGet()
                        if (hostRouteCache[normalizedHost] == candidate) hostRouteCache.remove(normalizedHost)
                        if (hostRouteCache[baseDomain(normalizedHost)] == candidate) hostRouteCache.remove(baseDomain(normalizedHost))
                        clearActiveCleanIp(candidate)
                        log("[MSP SOCKS5] route watchdog invalidated stale clean IP for $normalizedHost -> $candidate; next request will test the rest of the pool")
                    }
                }
            }
            error("no working route for $normalizedHost:$port after $tries tries • last=${lastError?.javaClass?.simpleName}: ${lastError?.message}")
        }

        private fun connectTcpIpv4(routeHost: String, port: Int, timeoutMs: Int): Socket {
            val targetIp = if (isIpv4Literal(routeHost)) routeHost else resolveIpv4(routeHost)
            val socket = Socket()
            protect(socket)
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.soTimeout = 20_000
            socket.connect(InetSocketAddress(targetIp, port), timeoutMs)
            return socket
        }

        private fun resolveIpv4(host: String): String {
            if (isIpv4Literal(host)) return host
            val all = InetAddress.getAllByName(host).filterIsInstance<Inet4Address>()
            return (all.firstOrNull() ?: error("no IPv4 address for $host")).hostAddress ?: error("invalid IPv4 address for $host")
        }

        private fun baseDomain(host: String): String {
            val parts = host.split('.').filter { it.isNotBlank() }
            return if (parts.size >= 2) parts.takeLast(2).joinToString(".") else host
        }

        private fun markFailure(target: String, reason: String) {
            val fails = routeFailures.getOrPut(target) { AtomicInteger(0) }.incrementAndGet()
            if (fails == 1 || fails % 10 == 0) {
                log("[MSP SOCKS5] route failure $fails: $target • $reason • pool=${cleanSnapshot().size}")
            }
        }

        private fun handleClient(client: Socket) {
            var remote: Socket? = null
            var targetLabel: String? = null
            try {
                runCatching { protect(client) }
                    .onFailure { e -> log("[MSP SOCKS5] protect(client) failed safely", e) }
                client.soTimeout = 20_000
                val input = BufferedInputStream(client.getInputStream())
                val output = BufferedOutputStream(client.getOutputStream())

                val ver = input.read()
                if (ver != 0x05) error("unsupported SOCKS version=$ver")
                val nMethods = input.read().takeIf { it >= 0 } ?: error("missing SOCKS methods")
                repeat(nMethods) { input.read() }
                output.write(byteArrayOf(0x05, 0x00))
                output.flush()

                val reqVer = input.read()
                val cmd = input.read()
                input.read() // RSV
                val atyp = input.read()
                if (reqVer != 0x05) error("unsupported SOCKS request ver=$reqVer cmd=$cmd")

                val host = when (atyp) {
                    0x01 -> readExact(input, 4).joinToString(".") { (it.toInt() and 0xff).toString() }
                    0x03 -> {
                        val len = input.read().takeIf { it >= 0 } ?: error("missing domain length")
                        String(readExact(input, len), Charsets.ISO_8859_1)
                    }
                    0x04 -> {
                        val bytes = readExact(input, 16)
                        InetAddress.getByAddress(bytes).hostAddress ?: error("invalid IPv6")
                    }
                    else -> error("unsupported address type=$atyp")
                }
                val portBytes = readExact(input, 2)
                val port = ((portBytes[0].toInt() and 0xff) shl 8) or (portBytes[1].toInt() and 0xff)
                targetLabel = "$host:$port"

                if (cmd == 0x03) {
                    // v2rayNG may open UDP ASSOCIATE for DNS/QUIC. RKh-MSP is TCP-focused;
                    // reply success with a dummy local endpoint and close quietly instead of spamming errors.
                    output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0, 0))
                    output.flush()
                    log("[MSP SOCKS5] UDP ASSOCIATE ignored quietly for $targetLabel")
                    return
                }
                if (cmd != 0x01) error("unsupported SOCKS request cmd=$cmd")

                val (connectedRemote, routeIp, pingMs) = connectBestRoute(host, port)
                remote = connectedRemote
                if (isIpv4Literal(routeIp)) setActiveRoute("", routeIp, pingMs) else setActiveRoute(host, routeIp, pingMs)
                output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                output.flush()
                log("[MSP SOCKS5] CONNECT $host:$port -> $routeIp:$port • ping=${pingMs}ms • pool=${cleanSnapshot().size}")
                pipeBoth(client, connectedRemote)
            } catch (e: Throwable) {
                runCatching { client.getOutputStream().write(byteArrayOf(0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0)) }
                markFailure(targetLabel ?: "unknown", "${e.javaClass.simpleName}: ${e.message}")
            } finally {
                runCatching { client.close() }
                runCatching { remote?.close() }
            }
        }

        private fun readExact(input: BufferedInputStream, len: Int): ByteArray {
            val data = ByteArray(len)
            var off = 0
            while (off < len) {
                val n = input.read(data, off, len - off)
                if (n < 0) error("unexpected EOF")
                off += n
            }
            return data
        }

        private fun pipeBoth(a: Socket, b: Socket) {
            val t1 = thread { copy(a, b, upload = true) }
            val t2 = thread { copy(b, a, upload = false) }
            t1.join()
            t2.join()
        }

        private fun copy(src: Socket, dst: Socket, upload: Boolean) {
            val buf = ByteArray(64 * 1024)
            try {
                val input = src.getInputStream()
                val output = dst.getOutputStream()
                while (running) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    output.write(buf, 0, n)
                    output.flush()
                    if (upload) proxyUploadBytes.addAndGet(n.toLong()) else proxyDownloadBytes.addAndGet(n.toLong())
                }
            } catch (_: Throwable) {
            } finally {
                runCatching { dst.shutdownOutput() }
            }
        }
    }

    private inner class MspHttpProxy(
        private val cleanIps: MutableList<String>,
        private val snis: List<String>,
        private val port: Int
    ) : LocalMspProxy {
        @Volatile private var server: ServerSocket? = null
        private val routeFailures = ConcurrentHashMap<String, AtomicInteger>()
        private val hostRouteCache = ConcurrentHashMap<String, String>()
        private val roundRobinCursor = AtomicInteger(0)
        private val ipFailures = ConcurrentHashMap<String, AtomicInteger>()

        override fun start(): Int {
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress("127.0.0.1", port))
            server = socket
            thread(name = "SIMORGH-MSP-http-proxy") {
                log("[MSP HTTP] listening on 127.0.0.1:${socket.localPort} • cleanPool=${cleanSnapshot().size}")
                while (running) {
                    try {
                        val client = socket.accept()
                        thread(name = "SIMORGH-MSP-http-client") {
                            runCatching { handleClient(client) }
                                .onFailure { e -> log("[MSP HTTP] client worker crashed safely", e) }
                            runCatching { client.close() }
                        }
                    } catch (_: Throwable) {
                        if (running) log("[MSP HTTP] accept failed")
                    }
                }
            }
            return socket.localPort
        }

        override fun stop() { runCatching { server?.close() } }

        override fun addIp(ip: String) { synchronized(cleanIps) { if (!cleanIps.contains(ip)) cleanIps.add(ip) } }

        private fun cleanSnapshot(): List<String> = synchronized(cleanIps) { cleanIps.toList() }

        private fun orderedCleanCandidates(): List<String> {
            val pool = cleanSnapshot().filter { isIpv4Literal(it) }
            if (pool.size <= 1) return pool
            val strategy = prefs.getString("routeStrategy", "default").orEmpty().ifBlank { "default" }
            val pingMap = loadSavedCleanIpPings()
            return when (strategy) {
                "random" -> pool.shuffled()
                "round_robin" -> {
                    val start = Math.floorMod(roundRobinCursor.getAndIncrement(), pool.size)
                    List(pool.size) { idx -> pool[(start + idx) % pool.size] }
                }
                "least_loss" -> pool.sortedWith(compareBy<String> { ipFailures[it]?.get() ?: 0 })
                "lowest_latency" -> pool.sortedWith(compareBy<String> { pingMap[it] ?: Long.MAX_VALUE })
                "hybrid_score" -> pool.sortedWith(compareBy<String> { ipFailures[it]?.get() ?: 0 }.thenBy { pingMap[it] ?: Long.MAX_VALUE })
                else -> pool
            }
        }

        private fun candidates(host: String, port: Int): List<String> {
            val h = host.trim().lowercase(Locale.US).removeSuffix(".")
            val out = linkedSetOf<String>()
            if (port == 443 && h.isNotBlank() && !isIpv4Literal(h) && !h.contains(':')) {
                hostRouteCache[h]?.takeIf { cleanSnapshot().contains(it) }?.let { out += it }
                hostRouteCache[baseDomain(h)]?.takeIf { cleanSnapshot().contains(it) }?.let { out += it }
                val active = prefs.getString("activeRouteIp", "").orEmpty().trim()
                if (active.isNotBlank() && active in cleanSnapshot()) out += active
                orderedCleanCandidates().forEach { out += it }
            }
            if (h.isNotBlank()) out += h
            return out.toList()
        }

        private fun connectBest(host: String, port: Int): Triple<Socket, String, Long> {
            val normalized = host.trim().lowercase(Locale.US).removeSuffix(".")
            var last: Throwable? = null
            var tries = 0
            for (candidate in candidates(normalized, port)) {
                tries++
                try {
                    if (port == 443 && isIpv4Literal(candidate) && normalized.isNotBlank() && !isIpv4Literal(normalized) && !normalized.contains(':')) {
                        if (!verifySniRoute(candidate, normalized, port, timeoutMs = 2500, sendHttpProbe = false)) {
                            ipFailures.getOrPut(candidate) { AtomicInteger(0) }.incrementAndGet()
                            markFailure("$normalized via $candidate", "SNI verify failed")
                            continue
                        }
                    }
                    val started = System.nanoTime()
                    val sock = connectTcpIpv4(candidate, port, 7000)
                    val ping = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0L)
                    if (isIpv4Literal(candidate)) {
                        saveCleanIp(candidate, ping)
                        setActiveRoute("", candidate, ping)
                        hostRouteCache[normalized] = candidate
                        hostRouteCache[baseDomain(normalized)] = candidate
                    }
                    val strategyLabel = prefs.getString("routeStrategy", "default") ?: "default"
                    if (tries > 1) log("[MSP HTTP] failover selected clean IP for $normalized:$port -> $candidate after $tries tries • strategy=$strategyLabel • ping=${ping}ms")
                    return Triple(sock, candidate, ping)
                } catch (t: Throwable) {
                    last = t
                    if (isIpv4Literal(candidate)) ipFailures.getOrPut(candidate) { AtomicInteger(0) }.incrementAndGet()
                    markFailure("$normalized via $candidate", "${t.javaClass.simpleName}: ${t.message}")
                }
            }
            error("no working HTTP route for $normalized:$port after $tries tries • last=${last?.javaClass?.simpleName}: ${last?.message}")
        }

        private fun connectTcpIpv4(routeHost: String, port: Int, timeoutMs: Int): Socket {
            val targetIp = if (isIpv4Literal(routeHost)) routeHost else resolveIpv4(routeHost)
            val socket = Socket()
            protect(socket)
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.soTimeout = 20_000
            socket.connect(InetSocketAddress(targetIp, port), timeoutMs)
            return socket
        }

        private fun resolveIpv4(host: String): String {
            if (isIpv4Literal(host)) return host
            val all = InetAddress.getAllByName(host).filterIsInstance<Inet4Address>()
            return (all.firstOrNull() ?: error("no IPv4 address for $host")).hostAddress ?: error("invalid IPv4 address for $host")
        }

        private fun baseDomain(host: String): String {
            val parts = host.split('.').filter { it.isNotBlank() }
            return if (parts.size >= 2) parts.takeLast(2).joinToString(".") else host
        }

        private fun markFailure(target: String, reason: String) {
            val fails = routeFailures.getOrPut(target) { AtomicInteger(0) }.incrementAndGet()
            if (fails == 1 || fails % 10 == 0) log("[MSP HTTP] route failure $fails: $target • $reason • pool=${cleanSnapshot().size}")
        }

        private fun handleClient(client: Socket) {
            var remote: Socket? = null
            try {
                runCatching { protect(client) }
                    .onFailure { e -> log("[MSP HTTP] protect(client) failed safely", e) }
                client.soTimeout = 20_000
                val input = BufferedInputStream(client.getInputStream())
                val output = BufferedOutputStream(client.getOutputStream())
                val requestLine = readAsciiLine(input) ?: return
                val headers = mutableListOf<String>()
                while (true) {
                    val line = readAsciiLine(input) ?: break
                    if (line.isBlank()) break
                    headers += line
                }
                val parts = requestLine.split(' ', limit = 3)
                val method = parts.getOrNull(0).orEmpty().uppercase(Locale.US)
                val target = parts.getOrNull(1).orEmpty()
                if (method == "CONNECT") {
                    val (host, targetPort) = parseHostPort(target, 443)
                    val (sock, route, ping) = connectBest(host, targetPort)
                    remote = sock
                    output.write("HTTP/1.1 200 Connection Established\r\nProxy-Agent: SIMORGH\r\n\r\n".toByteArray())
                    output.flush()
                    log("[MSP HTTP] CONNECT $host:$targetPort -> $route:$targetPort • ping=${ping}ms • pool=${cleanSnapshot().size}")
                    pipeBoth(client, sock)
                } else {
                    val (host, targetPort) = parseHttpTarget(target, headers)
                    val (sock, route, ping) = connectBest(host, targetPort)
                    remote = sock
                    val rebuilt = buildString {
                        append(method).append(' ').append(target.substringAfter(host, "/")).append(" HTTP/1.1\r\n")
                        headers.forEach { append(it).append("\r\n") }
                        append("\r\n")
                    }.toByteArray()
                    sock.getOutputStream().write(rebuilt)
                    sock.getOutputStream().flush()
                    log("[MSP HTTP] $method $host:$targetPort -> $route:$targetPort • ping=${ping}ms • pool=${cleanSnapshot().size}")
                    pipeBoth(client, sock)
                }
            } catch (t: Throwable) {
                markFailure("http-client", "${t.javaClass.simpleName}: ${t.message}")
            } finally {
                runCatching { client.close() }
                runCatching { remote?.close() }
            }
        }

        private fun pipeBoth(a: Socket, b: Socket) {
            val t1 = thread { copy(a, b, upload = true) }
            val t2 = thread { copy(b, a, upload = false) }
            t1.join(); t2.join()
        }

        private fun copy(src: Socket, dst: Socket, upload: Boolean) {
            val buf = ByteArray(64 * 1024)
            try {
                val input = src.getInputStream(); val output = dst.getOutputStream()
                while (running) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    output.write(buf, 0, n); output.flush()
                    if (upload) proxyUploadBytes.addAndGet(n.toLong()) else proxyDownloadBytes.addAndGet(n.toLong())
                }
            } catch (_: Throwable) {
            } finally { runCatching { dst.shutdownOutput() } }
        }
    }

    private fun readAsciiLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
            if (sb.length > 8192) error("HTTP line too long")
        }
        return sb.toString()
    }

    private fun isIpv4Literal(host: String): Boolean {
        val parts = host.split('.')
        return parts.size == 4 && parts.all { part -> part.toIntOrNull()?.let { it in 0..255 } == true }
    }

    private fun isMspAllowedHost(host: String): Boolean {
        val h = host.trim().lowercase(Locale.US).removeSuffix(".")
        if (h.isBlank() || isIpv4Literal(h) || h.contains(':')) return false
        val selected = prefs.getStringSet("selectedSnis", setOf("chatgpt.com")) ?: setOf("chatgpt.com")
        val allowedBases = linkedSetOf(
            "chatgpt.com",
            "openai.com",
            "oaistatic.com",
            "oaiusercontent.com"
        )
        selected.map { it.trim().lowercase(Locale.US).removeSuffix(".") }
            .filter { it.isNotBlank() && !isIpv4Literal(it) && !it.contains(':') }
            .forEach { allowedBases += it }
        return allowedBases.any { base -> h == base || h.endsWith(".$base") }
    }

    private fun parseHostPort(target: String, defaultPort: Int): Pair<String, Int> {
        val clean = target.removePrefix("http://").removePrefix("https://")
        val hostPort = clean.substringBefore('/')
        val host = hostPort.substringBefore(':')
        val port = hostPort.substringAfter(':', defaultPort.toString()).toIntOrNull() ?: defaultPort
        return host to port
    }

    private fun parseHttpTarget(target: String, headers: List<String>): Pair<String, Int> {
        val hostHeader = headers.firstOrNull { it.startsWith("Host:", ignoreCase = true) }?.substringAfter(':')?.trim().orEmpty()
        return parseHostPort(hostHeader.ifBlank { target }, 80)
    }


    private fun setActiveRoute(target: String, ip: String, pingMs: Long) {
        prefs.edit()
            .putString("activeRouteTarget", target)
            .putString("activeRouteIp", ip)
            .putLong("activeRoutePingMs", pingMs.coerceAtLeast(0L))
            .apply()
    }

    private fun setRoute(countryCode: String, ip: String) {
        val route = CountryCoordinates.routeFor(countryCode, ip, "rkh_msp_socks5_proxy")
        prefs.edit()
            .putString("routeEngine", route.engine)
            .putString("routeCountryCode", route.countryCode)
            .putString("routeCountryName", route.countryName)
            .putString("routeIp", route.ip)
            .putFloat("routeLatitude", route.latitude?.toFloat() ?: Float.NaN)
            .putFloat("routeLongitude", route.longitude?.toFloat() ?: Float.NaN)
            .apply()
    }

    private fun loadSavedCleanIps(): List<String> {
        val out = linkedSetOf<String>()
        prefs.getString("savedCleanIps", "").orEmpty()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && isIpv4Literal(it) }
            .forEach { out += it }
        parseManualCandidates(prefs.getString("manualIpsText", "").orEmpty(), maxCandidates = 300)
            .filter { isIpv4Literal(it) }
            .forEach { out += it }
        return out.take(300).toList()
    }

    private fun loadSavedCleanIpPings(): MutableMap<String, Long> {
        return prefs.getString("savedCleanIpPings", "").orEmpty()
            .lineSequence()
            .mapNotNull { line ->
                val parts = line.split("=", limit = 2)
                val ip = parts.getOrNull(0)?.trim().orEmpty()
                val ping = parts.getOrNull(1)?.trim()?.toLongOrNull()
                if (ip.isNotBlank() && ping != null && ping >= 0L) ip to ping else null
            }
            .toMap()
            .toMutableMap()
    }

    private fun saveCleanIp(ip: String, pingMs: Long = -1L) {
        if (!isIpv4Literal(ip)) return
        val merged = (listOf(ip) + loadSavedCleanIps()).distinct().take(300)
        val pingMap = loadSavedCleanIpPings()
        if (pingMs >= 0L) pingMap[ip] = pingMs
        val pingText = merged.mapNotNull { savedIp -> pingMap[savedIp]?.let { "$savedIp=$it" } }.joinToString("\n")
        prefs.edit()
            .putString("savedCleanIps", merged.joinToString("\n"))
            .putString("savedCleanIpPings", pingText)
            .putInt("cleanIpCount", merged.size)
            .apply()
    }

    private fun startNotificationSpeedLoop(generation: Int = lifecycleGeneration.get()) {
        speedThread = thread(name = "SIMORGH-public-speed") {
            var lastAt = System.currentTimeMillis()
            while (running && isCurrentLifecycle(generation)) {
                try {
                    Thread.sleep(3_000)
                    val now = System.currentTimeMillis()
                    val elapsedSeconds = ((now - lastAt).coerceAtLeast(1000L)) / 1000L
                    lastAt = now
                    // Live Speed is now measured from bytes actually relayed by SIMORGH SOCKS5,
                    // not from total phone TrafficStats. This keeps the graph from showing unrelated app traffic.
                    val down = ((proxyDownloadBytes.getAndSet(0L).coerceAtLeast(0L) * 8L) / 1000L) / elapsedSeconds
                    val up = ((proxyUploadBytes.getAndSet(0L).coerceAtLeast(0L) * 8L) / 1000L) / elapsedSeconds
                    prefs.edit().putLong("downloadKbps", down).putLong("uploadKbps", up).apply()
                    val label = prefs.getString("status", "RKh-MSP").orEmpty().take(36)
                    startForeground(2001, NotificationHelper.publicVpn(this, "SIMORGH", "$label • ↓ ${FormatUtils.kbps(down)}  ↑ ${FormatUtils.kbps(up)}", connected = true))
                } catch (_: Throwable) {}
            }
        }
    }

    private fun stopPublic() {
        if (!stopGuard.compareAndSet(false, true)) {
            running = false
            cfStartGuard.set(false)
            lifecycleGeneration.incrementAndGet()
            return
        }
        runCatching {
            lifecycleGeneration.incrementAndGet()
            if (running || tun != null || core != null || proxyServer != null) log("Stopping Public MSP tunnel")
            running = false
            cfStartGuard.set(false)
            val keepSimpleState = prefs.getBoolean("simpleConnecting", false) ||
                prefs.getBoolean("simpleConnected", false) ||
                prefs.getString("activeMode", "") == "simple_xray"
            runCatching {
                if (keepSimpleState) {
                    prefs.edit()
                        .putBoolean("connected", false)
                        .putBoolean("connecting", false)
                        .putInt("cleanIpCount", 0)
                        .putInt("scannedCount", 0)
                        .putInt("totalCandidates", 0)
                        .apply()
                } else {
                    updateState(connected = false, connecting = false, status = "Disconnected", cleanIpCount = 0)
                    prefs.edit().putInt("scannedCount", 0).putInt("totalCandidates", 0).apply()
                }
            }.onFailure { log("MSP state cleanup failed during disconnect", it) }

            val oldSpeedThread = speedThread
            speedThread = null
            runCatching { oldSpeedThread?.interrupt() }
                .onFailure { log("MSP speed thread interrupt failed", it) }

            val oldProxy = proxyServer
            proxyServer = null
            runCatching { oldProxy?.stop() }
                .onFailure { log("MSP proxy stop failed", it) }

            val oldCore = core
            core = null
            runCatching { oldCore?.stop() }
                .onFailure { log("binary core stop failed", it) }

            val oldInheritedFd = inheritedTunFd
            inheritedTunFd = null
            runCatching { oldInheritedFd?.let { Os.close(it) } }
                .onFailure { log("Inherited TUN fd close failed", it) }

            val oldTun = tun
            tun = null
            runCatching { oldTun?.close() }
                .onSuccess { if (oldTun != null) log("TUN closed") }
                .onFailure { log("TUN close failed", it) }

            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
            }.onFailure { log("stopForeground failed safely", it) }
        }.onFailure { e ->
            log("Safe MSP disconnect recovered", e)
            running = false
            cfStartGuard.set(false)
        }
    }

    override fun onRevoke() {
        runCatching { stopPublic() }.onFailure { log("MSP onRevoke stop recovered", it) }
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        runCatching { stopPublic() }.onFailure { log("MSP onDestroy stop recovered", it) }
        runCatching { super.onDestroy() }.onFailure { log("MSP super.onDestroy recovered", it) }
    }

    private fun updateState(
        connected: Boolean? = null,
        connecting: Boolean? = null,
        status: String? = null,
        lastError: String? = null,
        cleanIpCount: Int? = null,
        activeMode: String? = null
    ) {
        val e = prefs.edit()
        connected?.let { e.putBoolean("connected", it) }
        connecting?.let { e.putBoolean("connecting", it) }
        status?.let { e.putString("status", it) }
        lastError?.let { e.putString("lastError", it) }
        cleanIpCount?.let { e.putInt("cleanIpCount", it) }
        activeMode?.let { e.putString("activeMode", it) }
        e.putString("engine", when (activeMode ?: prefs.getString("activeMode", "idle")) {
            "proxy" -> "RKh-MSP Proxy"
            "vpn" -> "RKh-MSP VPN"
            else -> "RKh-MSP"
        })
        e.putBoolean("publicEngineAvailable", true)
        if ((connected == true || connecting == true) && prefs.getLong("startedAt", 0L) == 0L) e.putLong("startedAt", System.currentTimeMillis())
        if (connected == false && connecting == false) e.putLong("startedAt", 0L).putLong("downloadKbps", 0L).putLong("uploadKbps", 0L).putString("activeMode", "idle").putString("activeRouteTarget", "").putString("activeRouteIp", "").putLong("activeRoutePingMs", -1L)
        e.apply()
    }

    private fun assetExists(name: String): Boolean = runCatching { assets.open(name).close(); true }.getOrDefault(false)

    private fun nativeLibExists(name: String): Boolean {
        val mapped = if (name.startsWith("lib")) name else System.mapLibraryName(name)
        return applicationInfo.nativeLibraryDir?.let { File(it, mapped).exists() } == true
    }

    private fun log(message: String, throwable: Throwable? = null) {
        if (throwable == null) Log.d(tag, message) else Log.e(tag, message, throwable)
        RKhVpnLogStore.append(this, "MSP", message, throwable)
    }
}
