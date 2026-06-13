package com.rkh.vpn.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.TrafficStats
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rkh.vpn.data.CountryCoordinates
import com.rkh.vpn.data.RKhVpnLogStore
import com.rkh.vpn.data.SimorghPublicState
import com.rkh.vpn.data.SimorghRoute
import com.rkh.vpn.core.NativeBinaryManager
import com.rkh.vpn.core.PingEngine
import com.rkh.vpn.data.ServerConfig
import com.rkh.vpn.data.SimpleConfigUiItem
import com.rkh.vpn.data.SubscriptionRepository
import com.rkh.vpn.service.SimorghPublicVpnService
import com.rkh.vpn.service.RkhVpnService
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.joinAll
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.net.ServerSocket
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

class SimorghPublicViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("simorgh_public_state", Context.MODE_PRIVATE)
    private val simpleRepo = SubscriptionRepository()
    private val simplePing = PingEngine(app)
    private val simpleSubscriptionUrl = "https://subsimorgh.salam783.workers.dev"
    private val simpleServerlessAssetName = "serverless.json"
    private val simpleServerlessDisplayName = "ServerLess 🇮🇷"
    private val simpleServerlessDescription = "IRAN IPS"
    private val ispOptions: List<String> by lazy { loadIspOptions() }
    private val sniOptions: List<String> by lazy { loadSniOptions() }
    private val _state = MutableStateFlow(loadState())
    val state: StateFlow<SimorghPublicState> = _state
    private var syncJob: Job? = null
    private var simpleBackgroundLatencyJob: Job? = null

    init {
        ensureDefaults()
        syncManualIpsIntoCleanMemory()
        _state.value = loadState()
        log("Public ViewModel initialized • ISP options=${ispOptions.size} • SNI options=${sniOptions.size}")
        startSyncLoop()
    }

    fun connectAfterPermission() {
        startVpnModeAfterPermission()
    }

    fun startVpnModeAfterPermission() {
        ensureDefaults()
        syncManualIpsIntoCleanMemory()
        val snis = prefs.getStringSet("selectedSnis", setOf("chatgpt.com")) ?: setOf("chatgpt.com")
        val isp = prefs.getString("selectedIsp", defaultIsp()).orEmpty().ifBlank { defaultIsp() }
        val maxScan = prefs.getInt("maxScanIps", 33000).coerceIn(1, 33000)
        val manual = prefs.getBoolean("manualIpMode", false)
        val speed = prefs.getString("scanSpeed", "normal").orEmpty().ifBlank { "normal" }
        val manualCount = parseManualIpText(prefs.getString("manualIpsText", "").orEmpty()).size
        log("VPN Mode requested from Connect button • ISP=$isp • SNI=${snis.joinToString(",")} • port=443 • maxScan=$maxScan • speed=$speed • manualMode=$manual • manualCandidates=$manualCount")
        val intent = Intent(getApplication(), SimorghPublicVpnService::class.java)
            .setAction(SimorghPublicVpnService.ACTION_START)
        runCatching {
            getApplication<Application>().startForegroundService(intent)
        }.onFailure {
            log("Failed to start Public VPN foreground service", it)
        }
        markStarting("vpn", "VPN Mode scanning $isp with RKh-MSP...")
    }

    fun startProxyMode() {
        ensureDefaults()
        syncManualIpsIntoCleanMemory()
        val snis = prefs.getStringSet("selectedSnis", setOf("chatgpt.com")) ?: setOf("chatgpt.com")
        val isp = prefs.getString("selectedIsp", defaultIsp()).orEmpty().ifBlank { defaultIsp() }
        val maxScan = prefs.getInt("maxScanIps", 33000).coerceIn(1, 33000)
        val manual = prefs.getBoolean("manualIpMode", false)
        val speed = prefs.getString("scanSpeed", "normal").orEmpty().ifBlank { "normal" }
        val manualCount = parseManualIpText(prefs.getString("manualIpsText", "").orEmpty()).size
        log("Proxy Mode requested from Connect button • ISP=$isp • SNI=${snis.joinToString(",")} • local=${proxyAddressLabel()} • port=443 • maxScan=$maxScan • speed=$speed • manualMode=$manual • manualCandidates=$manualCount")
        val intent = Intent(getApplication(), SimorghPublicVpnService::class.java)
            .setAction(SimorghPublicVpnService.ACTION_START_PROXY)
        runCatching {
            getApplication<Application>().startForegroundService(intent)
        }.onFailure {
            log("Failed to start Public Proxy foreground service", it)
        }
        markStarting("proxy", "Proxy Mode running on ${proxyAddressLabel()} • scanning $isp...")
    }

    private fun markStarting(mode: String, status: String) {
        val now = System.currentTimeMillis()
        prefs.edit()
            .putBoolean("connecting", true)
            .putBoolean("connected", false)
            .putBoolean("publicEngineAvailable", true)
            .putLong("startedAt", now)
            .putString("engine", if (mode == "proxy") "RKh-MSP Proxy" else "RKh-MSP VPN")
            .putString("activeMode", mode)
            .putString("status", status)
            .putString("lastError", "")
            .putInt("scannedCount", 0)
            .putInt("totalCandidates", 0)
            .putInt("cleanIpCount", loadSavedCleanIps().size)
            .putInt("proxyPort", if (prefs.getString("selectedProxyProtocol", "socks5") == "http") 9991 else 9990)
            .apply()
        _state.value = loadState()
    }

    fun disconnect() {
        log("Disconnect requested from Public UI")
        val intent = Intent(getApplication(), SimorghPublicVpnService::class.java)
            .setAction(SimorghPublicVpnService.ACTION_STOP)
        runCatching {
            getApplication<Application>().startService(intent)
        }.onFailure {
            log("Failed to send Public stop intent", it)
        }
        prefs.edit()
            .putBoolean("connecting", false)
            .putBoolean("connected", false)
            .putString("status", "Disconnected")
            .putString("activeMode", "idle")
            .putLong("startedAt", 0L)
            .apply()
        _state.value = loadState()
    }

    fun setSelectedIsp(isp: String) {
        val value = isp.ifBlank { defaultIsp() }
        prefs.edit().putString("selectedIsp", value).apply()
        log("Selected ISP changed to $value")
        _state.value = loadState()
    }

    fun toggleSni(sni: String) {
        val clean = sni.trim().lowercase(Locale.US)
        if (clean.isBlank()) return
        val current = (prefs.getStringSet("selectedSnis", setOf("chatgpt.com")) ?: setOf("chatgpt.com")).toMutableSet()
        if (clean in current) current.remove(clean) else current.add(clean)
        if (current.isEmpty()) current.add("chatgpt.com")
        prefs.edit().putStringSet("selectedSnis", current).apply()
        log("Selected SNI changed to ${current.joinToString(",")}")
        _state.value = loadState()
    }
    fun setRunMode(mode: String) {
        val normalized = if (mode.equals("vpn", ignoreCase = true)) "vpn" else "proxy"
        prefs.edit().putString("selectedRunMode", normalized).apply()
        log("Selected run mode changed to ${normalized.uppercase(Locale.US)}")
        _state.value = loadState()
    }

    fun setProxyProtocol(protocol: String) {
        val normalized = when (protocol.lowercase(Locale.US)) {
            "http" -> "http"
            else -> "socks5"
        }
        val port = if (normalized == "http") 9991 else 9990
        prefs.edit()
            .putString("selectedProxyProtocol", normalized)
            .putInt("proxyPort", port)
            .putInt("socks5ProxyPort", 9990)
            .putInt("httpProxyPort", 9991)
            .apply()
        log("Selected proxy protocol changed to ${normalized.uppercase(Locale.US)} • 127.0.0.1:$port")
        _state.value = loadState()
    }

    fun setRouteStrategy(strategy: String) {
        val normalized = when (strategy.lowercase(Locale.US).replace(" ", "_")) {
            "random" -> "random"
            "round_robin", "roundrobin" -> "round_robin"
            "least_loss", "leastloss" -> "least_loss"
            "lowest_latency", "lowestlatency" -> "lowest_latency"
            "hybrid", "hybrid_score", "hybridscore" -> "hybrid_score"
            else -> "default"
        }
        prefs.edit().putString("routeStrategy", normalized).apply()
        log("Routing strategy changed to $normalized")
        _state.value = loadState()
    }

    private fun proxyAddressLabel(): String {
        val protocol = prefs.getString("selectedProxyProtocol", "socks5") ?: "socks5"
        val port = if (protocol == "http") 9991 else 9990
        return "${protocol.uppercase(Locale.US)} 127.0.0.1:$port"
    }

    fun setMaxScanIps(value: Int) {
        val v = value.coerceIn(1, 33000)
        prefs.edit().putInt("maxScanIps", v).apply()
        log("Max scan IPs changed to $v")
        _state.value = loadState()
    }

    fun setScanSpeed(speed: String) {
        val normalized = when (speed.lowercase(Locale.US)) {
            "slow", "normal", "fast" -> speed.lowercase(Locale.US)
            else -> "normal"
        }
        prefs.edit().putString("scanSpeed", normalized).apply()
        log("Scan speed changed to $normalized")
        _state.value = loadState()
    }


    fun nextRouteIp() {
        val app = getApplication<Application>()
        val intent = Intent(app, SimorghPublicVpnService::class.java).apply {
            action = SimorghPublicVpnService.ACTION_NEXT_ROUTE
        }
        app.startService(intent)
        log("Manual route switch requested from MSP Route card")
    }


    fun setCfEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean("cfEnabled", enabled)
            .putString("cfStatus", if (enabled) "CF Config enabled" else "CF Config disabled")
            .apply()
        log("CF Config ${if (enabled) "enabled" else "disabled"}")
        _state.value = loadState()
    }

    fun setCfVlessConfig(config: String) {
        prefs.edit().putString("cfVlessConfig", config.trim()).apply()
        log("CF Config VLESS updated • chars=${config.trim().length}")
        _state.value = loadState()
    }

    private fun loadCfCandidateIps(): List<String> {
        val out = linkedSetOf<String>()
        loadSavedCleanIps().forEach { if (isIpv4Literal(it)) out += it }
        parseManualIpText(prefs.getString("manualIpsText", "").orEmpty()).forEach { if (isIpv4Literal(it)) out += it }
        prefs.getString("activeRouteIp", "").orEmpty().trim().takeIf { isIpv4Literal(it) }?.let { out += it }
        prefs.getString("cfConnectingIp", "").orEmpty().trim().takeIf { isIpv4Literal(it) }?.let { out += it }
        return out.toList()
    }

    fun pingAllCfIps() = viewModelScope.launch {
        if (!prefs.getBoolean("cfEnabled", false)) { log("CF Latency All ignored: CF Config is OFF"); return@launch }
        val ips = loadCfCandidateIps()
        if (ips.isEmpty()) {
            prefs.edit().putString("cfStatus", "No clean/saved IPs available for CF latency").apply()
            _state.value = loadState()
            log("CF Latency All ignored: no clean/saved IPs")
            return@launch
        }
        prefs.edit().putString("cfStatus", "CF Latency All: ${ips.size} clean/saved IPs with VLESS/Xray profile...").apply()
        _state.value = loadState()
        for (ip in ips) pingCfIp(ip).join()
        prefs.edit().putString("cfStatus", "CF Latency All finished • sorted by latency").apply()
        _state.value = loadState()
    }

    fun pingCfIp(ip: String) = viewModelScope.launch {
        if (!prefs.getBoolean("cfEnabled", false)) { log("CF latency ignored: CF Config is OFF"); return@launch }
        val cleanIp = ip.trim()
        val parsed = parseVlessForCf(prefs.getString("cfVlessConfig", "").orEmpty())
        if (!isIpv4Literal(cleanIp)) {
            log("CF ping ignored: invalid IP=$cleanIp")
            return@launch
        }
        if (parsed == null) {
            prefs.edit().putString("cfStatus", "Paste a valid vless:// config first").apply()
            _state.value = loadState()
            log("CF latency failed: missing/invalid VLESS config")
            return@launch
        }
        prefs.edit().putString("cfStatus", "Real Xray latency test $cleanIp with ${parsed.sni}:${parsed.port}...").apply()
        _state.value = loadState()
        val vlessRaw = prefs.getString("cfVlessConfig", "").orEmpty()
        val ms = withContext(Dispatchers.IO) { cfXrayLatencyMs(cleanIp, vlessRaw, 9000) }
        val results = loadCfPingResults().toMutableMap()
        if (ms != null) {
            results[cleanIp] = "${ms}ms"
            prefs.edit().putString("cfPingResults", results.entries.joinToString("\n") { "${it.key}=${it.value}" }).putString("cfStatus", "CF Xray route latency OK: $cleanIp • ${ms}ms").apply()
            log("CF Config REAL Xray latency OK: $cleanIp -> ${parsed.sni}:${parsed.port} • ${ms}ms")
        } else {
            results[cleanIp] = "Timeout"
            prefs.edit()
                .putString("cfPingResults", results.entries.joinToString("\n") { "${it.key}=${it.value}" })
                .putString("cfStatus", "CF Xray route latency timeout: $cleanIp")
                .apply()
            log("CF Config REAL Xray latency timeout: $cleanIp -> ${parsed.sni}:${parsed.port}")
        }
        _state.value = loadState()
    }

    fun prepareCfConnectIp(ip: String): Boolean {
        if (!prefs.getBoolean("cfEnabled", false)) { prefs.edit().putString("cfStatus", "Turn CF Config ON first").apply(); _state.value = loadState(); return false }
        val cleanIp = ip.trim()
        val vless = prefs.getString("cfVlessConfig", "").orEmpty().trim()
        if (!isIpv4Literal(cleanIp) || !vless.startsWith("vless://", ignoreCase = true)) {
            prefs.edit().putString("cfStatus", "Invalid CF IP or VLESS config").apply()
            _state.value = loadState()
            log("CF connect ignored: invalid IP or VLESS config")
            return false
        }
        prefs.edit().putString("cfConnectingIp", cleanIp).putString("pendingCfIp", cleanIp).putString("pendingCfVless", vless).putString("cfStatus", "Ready to connect CF VLESS via $cleanIp").apply()
        _state.value = loadState()
        log("CF Config connect prepared: $cleanIp")
        return true
    }

    fun connectCfAfterPermission() {
        val app = getApplication<Application>()
        val cleanIp = prefs.getString("pendingCfIp", "").orEmpty().trim()
        val vless = prefs.getString("pendingCfVless", "").orEmpty().trim()
        val intent = Intent(app, SimorghPublicVpnService::class.java).apply {
            action = SimorghPublicVpnService.ACTION_CF_CONNECT
            putExtra(SimorghPublicVpnService.EXTRA_CF_IP, cleanIp)
            putExtra(SimorghPublicVpnService.EXTRA_CF_VLESS, vless)
        }
        runCatching { app.startForegroundService(intent) }
            .onFailure { log("Failed to start CF VLESS foreground service", it) }
        prefs.edit().putString("cfStatus", "Connecting CF VLESS via $cleanIp...").apply()
        _state.value = loadState()
        log("CF Config connect requested after VPN permission: $cleanIp")
    }

    fun connectCfIp(ip: String) { prepareCfConnectIp(ip) }

    fun updateSimpleSubscription() = viewModelScope.launch {
        updateSimpleSubscriptionInternal(showReady = true)
    }

    fun clearSimpleCache() {
        simpleBackgroundLatencyJob?.cancel()
        val serverless = prefs.getBoolean("simpleServerlessEnabled", false)
        val configCount = loadSimpleConfigs(serverless).size
        prefs.edit()
            // Keep subscription/config bodies. Clear only healthy/ping memory.
            .remove("simpleLatencyCache")
            .remove("simpleLatencyProbeIndex")
            .remove("simpleLatencyHealthyCount")
            .remove("simpleLatencyCacheUpdatedAt")
            .remove("simpleLatencyLastProbeAt")
            .remove("simpleBestId")
            .remove("simpleBestIndex")
            .remove("simpleBestRawHash")
            .putInt("simpleConfigCount", configCount)
            .putString("simpleBestName", "")
            .putLong("simpleBestPingMs", -1L)
            .putString("simpleLatencyScannerStatus", "Healthy cache cleared")
            .putString("simpleStatus", "Simple healthy cache cleared • configs kept: $configCount")
            .putString("status", "Simple healthy cache cleared")
            .apply()
        log("Simple cache cleared • healthy/ping memory removed only • configsKept=$configCount")
        _state.value = loadState()
    }

    fun pingAllSimpleConfigs() = viewModelScope.launch {
        val serverless = prefs.getBoolean("simpleServerlessEnabled", false)
        var configs = loadSimpleConfigs(serverless)
        if (configs.isEmpty()) configs = updateSimpleSubscriptionInternal(showReady = false)
        if (configs.isEmpty()) {
            prefs.edit()
                .putString("simpleStatus", "Ping All: no configs found")
                .putString("status", "Ping All: no configs found")
                .apply()
            _state.value = loadState()
            log("Simple Ping All ignored: config cache is empty")
            return@launch
        }
        // Ping All should show fresh values only, so old latency/healthy memory is cleared first.
        simpleBackgroundLatencyJob?.cancel()
        prefs.edit()
            .remove("simpleLatencyCache")
            .remove("simpleLatencyHealthyCount")
            .remove("simpleLatencyCacheUpdatedAt")
            .remove("simpleBestId")
            .remove("simpleBestIndex")
            .remove("simpleBestRawHash")
            .putString("simpleBestName", "")
            .putLong("simpleBestPingMs", -1L)
            .putString("simpleStatus", "Ping All started • ${configs.size} configs • 20 parallel • fresh 3x Xray ping")
            .putString("status", "Ping All started")
            .apply()
        _state.value = loadState()
        val tested = pingSimpleConfigsParallel(configs, parallelism = 20, statusPrefix = "Ping All")
        val bestPair = tested.filter { it.second.pingMs != null }.minByOrNull { it.second.pingMs!! }
        if (bestPair == null) {
            prefs.edit()
                .putString("simpleStatus", "Ping All finished • no reachable config")
                .putString("status", "Ping All finished")
                .putString("simpleBestName", "")
                .putLong("simpleBestPingMs", -1L)
                .apply()
            log("Simple Ping All finished: no reachable config")
        } else {
            val label = simpleDisplayName(bestPair.first)
            val ping = bestPair.second.pingMs ?: -1L
            prefs.edit()
                .putString("simpleBestName", label)
                .putString("simpleBestId", bestPair.second.id)
                .putInt("simpleBestIndex", bestPair.first)
                .putInt("simpleBestRawHash", bestPair.second.raw.hashCode())
                .putLong("simpleBestPingMs", ping)
                .putString("simpleStatus", "Ping All finished • best $label • ${ping}ms")
                .putString("status", "Ping All finished")
                .apply()
            log("Simple Ping All finished: best=$label • ${ping}ms • tested=${tested.size}")
        }
        _state.value = loadState()
    }

    fun setSimpleServerlessEnabled(enabled: Boolean) {
        if (enabled) simpleBackgroundLatencyJob?.cancel()
        val configs = loadSimpleConfigs(enabled)
        prefs.edit()
            .putBoolean("simpleServerlessEnabled", enabled)
            .putInt("simpleConfigCount", configs.size)
            .putString("simpleBestName", "")
            .putLong("simpleBestPingMs", -1L)
            .putString("simpleStatus", if (enabled) "$simpleServerlessDisplayName ON • ${configs.size} cached configs" else "$simpleServerlessDisplayName OFF • ${configs.size} cached configs")
            .apply()
        log("Simple ServerLess changed to ${if (enabled) "ON" else "OFF"} • cached=${configs.size}")
        _state.value = loadState()
    }

    fun prepareSimpleConnectUi() {
        val startedAt = System.currentTimeMillis()
        prefs.edit()
            .putBoolean("simpleConnecting", true)
            .putBoolean("simpleConnected", false)
            .putString("simpleStatus", "Searching and Ping...")
            .putString("status", "Searching and Ping...")
            .putString("simpleBestName", "")
            .putLong("simpleBestPingMs", -1L)
            .putBoolean("connecting", false)
            .putBoolean("connected", false)
            .putString("activeMode", "simple_xray")
            .putLong("startedAt", startedAt)
            .apply()
        _state.value = loadState()
    }

    fun simpleConnectAfterPermission() {
        viewModelScope.launch {
            try {
                val app = getApplication<Application>()
                runCatching { app.stopService(Intent(app, SimorghPublicVpnService::class.java)) }
                val startedAt = System.currentTimeMillis()
                prefs.edit()
                    .putBoolean("simpleConnecting", true)
                    .putBoolean("simpleConnected", false)
                    .putString("simpleStatus", "Searching and Ping...")
                    .putString("status", "Searching and Ping...")
                    .putString("simpleBestName", "")
                    .putLong("simpleBestPingMs", -1L)
                    .putBoolean("connecting", false)
                    .putBoolean("connected", false)
                    .putString("activeMode", "simple_xray")
                    .putLong("startedAt", startedAt)
                    .putLong("downloadKbps", 0L)
                    .putLong("uploadKbps", 0L)
                    .putLong("simpleLastTrafficAt", startedAt)
                    .putBoolean("simpleHadTraffic", false)
                    .apply()
                _state.value = loadState()

                // First run: if there is no saved config cache yet, load and save it once.
                // Config bodies stay cached until Update. Each Connect performs a fresh shuffled Xray ping scan.
                val serverless = prefs.getBoolean("simpleServerlessEnabled", false)
                var configs = loadSimpleConfigs(serverless)
                if (configs.isEmpty()) {
                    prefs.edit()
                        .putString("simpleStatus", "Searching and Ping... • first run load")
                        .putString("status", "Searching and Ping...")
                        .apply()
                    _state.value = loadState()
                    log("Simple connect first run: no cached configs for ${if (serverless) simpleServerlessDisplayName else "Normal"}, loading config once")
                    configs = updateSimpleSubscriptionInternal(showReady = false)
                }
                if (configs.isEmpty()) {
                    prefs.edit()
                        .putBoolean("simpleConnecting", false)
                        .putBoolean("simpleConnected", false)
                        .putString("simpleStatus", "Simple: no configs found. Tap Update and try again.")
                        .putString("status", "Simple: no configs found")
                        .putString("activeMode", "idle")
                        .putLong("startedAt", 0L)
                        .apply()
                    _state.value = loadState()
                    log("Simple connect stopped: config cache is empty after first-run load")
                    return@launch
                }

                prefs.edit()
                    .putString("simpleStatus", "Preparing Simple configs... • ${configs.size} configs")
                    .putString("status", "Preparing Simple configs")
                    .apply()
                _state.value = loadState()
                log("Simple XRAY connect using cached config list and fresh 3x Xray ping scan • mode=${if (prefs.getBoolean("simpleServerlessEnabled", false)) simpleServerlessDisplayName else "Normal"} • configs=${configs.size}")

                if (serverless && configs.size == 1 && configs.first().raw.trim().startsWith("{")) {
                    // ServerLess is restored from stable 1.1.23.37 behavior: quick Xray ping first,
                    // then one full real Xray ping retry. Do not use the newer 3x strict Simple scanner here.
                    var tested = withContext(Dispatchers.IO) { simplePing.pingQuick(configs.first()) }
                    if (tested.pingMs == null) {
                        prefs.edit()
                            .putString("simpleStatus", "Searching and Ping... • retrying real ServerLess ping")
                            .putString("status", "Searching and Ping...")
                            .apply()
                        _state.value = loadState()
                        log("Simple XRAY ServerLess quick ping failed: ${tested.error ?: "unknown"}; retrying full real Xray ping")
                        tested = withContext(Dispatchers.IO) { simplePing.ping(configs.first()) }
                    }
                    val best = tested.takeIf { it.pingMs != null } ?: configs.first().copy(error = tested.error)
                    if (best.pingMs == null) {
                        prefs.edit()
                            .putBoolean("simpleConnecting", false)
                            .putBoolean("simpleConnected", false)
                            .putString("simpleStatus", "Simple XRAY: no reachable ServerLess config")
                            .putString("status", "Simple XRAY: no reachable config")
                            .putString("activeMode", "idle")
                            .putLong("startedAt", 0L)
                            .apply()
                        _state.value = loadState()
                        log("Simple XRAY ServerLess: no reachable config after 1.1.23.37-compatible ping")
                        return@launch
                    }
                    if (!startSelectedSimpleConfig(app, best.copy(name = simpleServerlessDisplayName), 0, startedAt)) return@launch
                    return@launch
                }

                // Each Connect starts a fresh shuffled Xray ping scan. Cached pings remain visible in the list,
                // but they are not trusted for the actual connection decision.
                prefs.edit()
                    .putString("simpleStatus", "Searching and Ping... • shuffled 20 parallel • 3x ping")
                    .putString("status", "Searching and Ping...")
                    .apply()
                _state.value = loadState()
                val connected = connectFromFirstHealthyShuffledBatch(app, configs, startedAt)
                if (connected == null) {
                    prefs.edit()
                        .putBoolean("simpleConnecting", false)
                        .putBoolean("simpleConnected", false)
                        .putString("simpleStatus", "Simple XRAY: no reachable config")
                        .putString("status", "Simple XRAY: no reachable config")
                        .putString("activeMode", "idle")
                        .putLong("startedAt", 0L)
                        .apply()
                    _state.value = loadState()
                    log("Simple XRAY: no reachable config after shuffled latency scan")
                    return@launch
                }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                prefs.edit()
                    .putBoolean("simpleConnecting", false)
                    .putBoolean("simpleConnected", false)
                    .putString("simpleStatus", "Simple XRAY error: ${e.message ?: e.javaClass.simpleName}")
                    .putString("status", "Simple XRAY error")
                    .putString("activeMode", "idle")
                    .putLong("startedAt", 0L)
                    .putLong("downloadKbps", 0L)
                    .putLong("uploadKbps", 0L)
                    .apply()
                log("Simple XRAY unexpected connect error", e)
                _state.value = loadState()
            }
        }
    }


    fun prepareSimpleConfigConnectUi(index: Int) {
        val configs = loadSimpleConfigs(prefs.getBoolean("simpleServerlessEnabled", false))
        val safeIndex = index.coerceIn(0, (configs.size - 1).coerceAtLeast(0))
        prefs.edit()
            .putInt("simplePendingConnectIndex", safeIndex)
            .putBoolean("simpleConnecting", true)
            .putBoolean("simpleConnected", false)
            .putString("simpleStatus", "Connecting ${simpleDisplayName(safeIndex)}...")
            .putString("status", "Simple XRAY connecting")
            .putString("activeMode", "simple_xray")
            .putLong("startedAt", System.currentTimeMillis())
            .apply()
        _state.value = loadState()
        log("Simple config row connect requested • index=$safeIndex • cached=${configs.size}")
    }

    fun simpleConnectSelectedAfterPermission() {
        viewModelScope.launch {
            try {
                val app = getApplication<Application>()
                runCatching { app.stopService(Intent(app, SimorghPublicVpnService::class.java)) }
                val serverless = prefs.getBoolean("simpleServerlessEnabled", false)
                var configs = loadSimpleConfigs(serverless)
                if (configs.isEmpty()) configs = updateSimpleSubscriptionInternal(showReady = false)
                if (configs.isEmpty()) {
                    prefs.edit()
                        .putBoolean("simpleConnecting", false)
                        .putBoolean("simpleConnected", false)
                        .putString("simpleStatus", "Simple: no configs found. Tap Update and try again.")
                        .putString("status", "Simple: no configs found")
                        .putString("activeMode", "idle")
                        .putLong("startedAt", 0L)
                        .apply()
                    _state.value = loadState()
                    return@launch
                }
                val pending = prefs.getInt("simplePendingConnectIndex", 0).coerceIn(configs.indices)
                val candidate = configs[pending]
                prefs.edit()
                    .putString("simpleStatus", "Testing ${simpleDisplayName(pending)} with 3x Xray ping...")
                    .putString("status", "Simple XRAY testing selected config")
                    .apply()
                _state.value = loadState()
                val selected = withContext(Dispatchers.IO) { simplePing.pingStrict3(candidate) }
                val ping = selected.pingMs
                if (ping == null) {
                    removeSimpleLatencyResult(candidate)
                    prefs.edit()
                        .putBoolean("simpleConnecting", false)
                        .putBoolean("simpleConnected", prefs.getBoolean("simpleConnected", false))
                        .putString("simpleStatus", "${simpleDisplayName(pending)} failed 3x Xray ping")
                        .putString("status", "Selected config failed")
                        .apply()
                    log("Simple row connect blocked by failed 3x Xray ping • index=$pending • ${selected.error ?: "unknown"}")
                    _state.value = loadState()
                    return@launch
                }
                saveSimpleLatencyResult(selected, ping)
                val startedAt = System.currentTimeMillis()
                if (!startSelectedSimpleConfig(app, selected, pending, startedAt)) return@launch
                if (!serverless) startSimpleBackgroundShuffleScan(configs, "Refreshing pings")
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                prefs.edit()
                    .putBoolean("simpleConnecting", false)
                    .putBoolean("simpleConnected", prefs.getBoolean("simpleConnected", false))
                    .putString("simpleStatus", "Config connect error: ${e.message ?: e.javaClass.simpleName}")
                    .putString("status", "Config connect error")
                    .apply()
                log("Simple config row connect error", e)
                _state.value = loadState()
            }
        }
    }

    private fun bestCachedSimpleConfig(configs: List<ServerConfig>): Pair<Int, ServerConfig>? {
        val cache = loadSimpleLatencyCache()
        if (configs.isEmpty() || cache.isEmpty()) return null
        return configs.mapIndexedNotNull { index, server ->
            cache[server.id]?.let { entry -> index to server.copy(pingMs = entry.pingMs, error = null) }
        }.minByOrNull { it.second.pingMs ?: Long.MAX_VALUE }
    }

    private fun startSelectedSimpleConfig(app: Application, best: ServerConfig, bestIndex: Int, startedAt: Long): Boolean {
        val bestLabel = simpleDisplayName(bestIndex)
        val bestPingLabel = best.pingMs?.let { "${it}ms" } ?: "—"
        prefs.edit()
            .putString("simpleBestName", bestLabel)
            .putString("simpleBestId", best.id)
            .putInt("simpleBestIndex", bestIndex)
            .putInt("simpleBestRawHash", best.raw.hashCode())
            .putLong("simpleBestPingMs", best.pingMs ?: -1L)
            .putString("simpleStatus", "Simple XRAY connecting: $bestLabel • Ping $bestPingLabel")
            .putString("status", "Simple XRAY connecting")
            .putString("activeMode", "simple_xray")
            .putLong("startedAt", startedAt)
            .putLong("simpleLastTrafficAt", startedAt)
            .putBoolean("simpleHadTraffic", false)
            .apply()
        _state.value = loadState()
        log("Simple XRAY selected config: $bestLabel • Ping $bestPingLabel • index=$bestIndex")
        val raw = best.raw.replace("﻿", "").trim()
        if (prefs.getBoolean("simpleServerlessEnabled", false)) {
            log("Simple ServerLess connect handoff • rawChars=${raw.length} • startsJson=${raw.startsWith("{")} • containsTunInbound=${raw.contains("\"protocol\":\"tun\"") || raw.contains("\"protocol\": \"tun\"")} • containsOutbounds=${raw.contains("\"outbounds\"")}")
        }
        val intent = Intent(app, RkhVpnService::class.java)
            .setAction(RkhVpnService.ACTION_START)
            .putExtra(RkhVpnService.EXTRA_RAW_CONFIG, best.raw)
            .putExtra(RkhVpnService.EXTRA_SERVER_NAME, "SIMORGH Simple • $bestLabel")
        val result = runCatching { app.startForegroundService(intent) }
        result.onFailure { e ->
            prefs.edit()
                .putBoolean("simpleConnecting", false)
                .putBoolean("simpleConnected", false)
                .putString("simpleStatus", "Simple XRAY start failed: ${e.message ?: e.javaClass.simpleName}")
                .putString("status", "Simple XRAY start failed")
                .putString("activeMode", "idle")
                .putLong("startedAt", 0L)
                .apply()
            log("Simple XRAY service start failed", e)
            _state.value = loadState()
        }
        if (result.isFailure) return false
        prefs.edit()
            .putBoolean("simpleConnecting", false)
            .putBoolean("simpleConnected", true)
            .putString("simpleBestId", best.id)
            .putInt("simpleBestIndex", bestIndex)
            .putInt("simpleBestRawHash", best.raw.hashCode())
            .putString("simpleStatus", "Simple XRAY connected: $bestLabel • Ping $bestPingLabel")
            .putString("status", "Simple XRAY connected")
            .putString("activeMode", "simple_xray")
            .putLong("startedAt", startedAt)
            .apply()
        best.pingMs?.let { saveSimpleLatencyResult(best, it) }
        prefs.edit().putInt("simpleLatencyProbeIndex", bestIndex).apply()
        _state.value = loadState()
        return true
    }

    private suspend fun connectFromFirstHealthyShuffledBatch(app: Application, configs: List<ServerConfig>, startedAt: Long): Pair<Int, ServerConfig>? = coroutineScope {
        val safeParallelism = 20.coerceAtMost(configs.size.coerceAtLeast(1))
        val total = configs.size
        val order = configs.withIndex().toList().shuffled()
        val next = AtomicInteger(0)
        val results = ArrayList<Pair<Int, ServerConfig>>(total)
        val latencyCache = loadSimpleLatencyCache().toMutableMap()
        val resultChannel = Channel<Pair<Int, ServerConfig>>(Channel.UNLIMITED)
        var connected: Pair<Int, ServerConfig>? = null

        val workers = List(safeParallelism) {
            launch(Dispatchers.IO) {
                while (true) {
                    val pos = next.getAndIncrement()
                    if (pos >= order.size) break
                    val indexed = order[pos]
                    val tested = runCatching { simplePing.pingStrict3(indexed.value) }
                        .getOrElse { e -> indexed.value.copy(pingMs = null, error = e.message ?: e.javaClass.simpleName) }
                    resultChannel.send(indexed.index to tested)
                }
            }
        }
        launch {
            workers.joinAll()
            resultChannel.close()
        }

        for (testedPair in resultChannel) {
            results += testedPair
            val (idx, server) = testedPair
            val ping = server.pingMs
            if (ping != null && ping > 0L) {
                latencyCache[server.id] = SimpleLatencyEntry(ping, System.currentTimeMillis())
            } else {
                latencyCache.remove(server.id)
            }
            writeSimpleLatencyCache(latencyCache)

            if (connected == null && ping != null) {
                val started = startSelectedSimpleConfig(app, server, idx, startedAt)
                if (!started) return@coroutineScope null
                connected = testedPair
                log("Simple XRAY connected from first reachable shuffled Xray ping • ${simpleDisplayName(idx)} • ${ping}ms; continuing scan")
            }

            val done = results.size
            val healthy = results.count { it.second.pingMs != null }
            val bestSoFar = results.filter { it.second.pingMs != null }.minByOrNull { it.second.pingMs!! }
            val bestText = bestSoFar?.let { " • best ${simpleDisplayName(it.first)} ${it.second.pingMs}ms" } ?: ""
            val connectedText = connected?.let { "Connected ${simpleDisplayName(it.first)} • " } ?: ""
            if (done == 1 || ping != null || done % 5 == 0 || done == total) {
                prefs.edit()
                    .putString("simpleStatus", "${connectedText}Scanning pings... $done/$total scanned • $healthy healthy$bestText")
                    .putString("status", if (connected == null) "Searching and Ping... $done/$total" else "Simple XRAY connected")
                    .apply()
                _state.value = loadState()
            }
        }
        connected
    }

    private fun startSimpleBackgroundShuffleScan(configs: List<ServerConfig>, statusPrefix: String) {
        if (prefs.getBoolean("simpleServerlessEnabled", false)) return
        if (configs.size < 2) return
        simpleBackgroundLatencyJob?.cancel()
        simpleBackgroundLatencyJob = viewModelScope.launch(Dispatchers.IO) {
            pingSimpleConfigsParallel(configs, parallelism = 20, statusPrefix = statusPrefix)
        }
    }


    fun prepareSimpleNextHealthyUi() {
        val serverless = prefs.getBoolean("simpleServerlessEnabled", false)
        val configs = loadSimpleConfigs(serverless)
        prefs.edit()
            .putBoolean("simpleConnecting", true)
            .putBoolean("simpleConnected", false)
            .putString("simpleStatus", if (serverless) "Next config is only for Simple normal mode" else "Testing next healthy config...")
            .putString("status", if (serverless) "ServerLess has one config" else "Testing next healthy config...")
            .putBoolean("connecting", false)
            .putBoolean("connected", false)
            .putString("activeMode", "simple_xray")
            .putLong("startedAt", System.currentTimeMillis())
            .apply()
        log("Simple XRAY next healthy requested • serverless=$serverless • cached=${configs.size}")
        _state.value = loadState()
    }

    fun simpleConnectNextHealthyAfterPermission() {
        viewModelScope.launch {
            try {
                val app = getApplication<Application>()
                val serverless = prefs.getBoolean("simpleServerlessEnabled", false)
                if (serverless) {
                    prefs.edit()
                        .putBoolean("simpleConnecting", false)
                        .putBoolean("simpleConnected", prefs.getBoolean("simpleConnected", false))
                        .putString("simpleStatus", "ServerLess has one config • Next is for Simple normal")
                        .putString("status", "ServerLess has one config")
                        .apply()
                    _state.value = loadState()
                    return@launch
                }

                prefs.edit()
                    .putBoolean("simpleConnecting", true)
                    .putBoolean("simpleConnected", false)
                    .putString("simpleStatus", "Testing next healthy config...")
                    .putString("status", "Testing next healthy config...")
                    .putString("activeMode", "simple_xray")
                    .apply()
                _state.value = loadState()

                var configs = loadSimpleConfigs(serverless = false)
                if (configs.isEmpty()) configs = updateSimpleSubscriptionInternal(showReady = false)
                if (configs.size < 2) {
                    prefs.edit()
                        .putBoolean("simpleConnecting", false)
                        .putBoolean("simpleConnected", prefs.getBoolean("simpleConnected", false))
                        .putString("simpleStatus", "Need at least 2 configs for Next Healthy")
                        .putString("status", "Need at least 2 configs")
                        .apply()
                    _state.value = loadState()
                    return@launch
                }

                val currentIndex = currentSimpleConfigIndex(configs)
                val latencyCache = loadSimpleLatencyCache()
                val order = buildList {
                    // Next Healthy must use the already-scanned healthy cache, not a fresh full scan.
                    // It still verifies each cached candidate with 3x real Xray ping right before switching.
                    for (i in 1 until configs.size) {
                        val idx = (currentIndex + i).floorMod(configs.size)
                        val candidate = configs.getOrNull(idx) ?: continue
                        if (latencyCache.containsKey(candidate.id)) add(idx)
                    }
                }

                if (order.isEmpty()) {
                    prefs.edit()
                        .putBoolean("simpleConnecting", false)
                        .putBoolean("simpleConnected", prefs.getBoolean("simpleConnected", false))
                        .putString("simpleStatus", "No cached healthy config found • tap Ping All first")
                        .putString("status", "No cached healthy config")
                        .apply()
                    _state.value = loadState()
                    log("Simple XRAY next healthy: no cached healthy candidates; run Ping All first")
                    return@launch
                }

                var selectedIndex = -1
                var selected: ServerConfig? = null

                // Use only cached healthy configs, but re-check each one live with 3x real Xray ping.
                // If a cached config no longer responds, remove it from cache and continue automatically.
                for ((pos, idx) in order.withIndex()) {
                    if (idx !in configs.indices) continue
                    val candidate = configs[idx]
                    val cachedPing = latencyCache[candidate.id]?.pingMs
                    val cachedText = cachedPing?.let { " • cached ${it}ms" } ?: ""
                    prefs.edit()
                        .putString("simpleStatus", "Testing cached healthy ${pos + 1}/${order.size}: ${simpleDisplayName(idx)}$cachedText")
                        .putString("status", "Testing cached healthy...")
                        .apply()
                    _state.value = loadState()
                    log("Simple XRAY next healthy cached candidate live 3x Xray ping • index=$idx • name=${simpleDisplayName(idx)}$cachedText")
                    val tested = withContext(Dispatchers.IO) {
                        runCatching { simplePing.pingStrict3(candidate) }
                            .getOrElse { e -> candidate.copy(pingMs = null, error = e.message ?: e.javaClass.simpleName) }
                    }
                    val testedPing = tested.pingMs
                    if (testedPing != null && testedPing > 0L) {
                        saveSimpleLatencyResult(tested, testedPing)
                        selectedIndex = idx
                        selected = tested
                        break
                    } else {
                        removeSimpleLatencyResult(candidate)
                        val failReason = tested.error ?: "no ping"
                        log("Simple XRAY next healthy skipped stale cached config • index=$idx • name=${simpleDisplayName(idx)} • $failReason")
                    }
                }

                val best = selected
                if (best == null || selectedIndex < 0) {
                    prefs.edit()
                        .putBoolean("simpleConnecting", false)
                        .putBoolean("simpleConnected", prefs.getBoolean("simpleConnected", false))
                        .putString("simpleStatus", "No cached healthy config answered live 3x ping")
                        .putString("status", "No cached healthy answered")
                        .apply()
                    _state.value = loadState()
                    log("Simple XRAY next healthy: no cached healthy config answered live 3x Xray ping")
                    return@launch
                }

                val displayName = simpleDisplayName(selectedIndex)
                val pingLabel = best.pingMs?.let { "${it}ms" } ?: "—"
                prefs.edit()
                    .putString("simpleBestName", displayName)
                    .putString("simpleBestId", best.id)
                    .putInt("simpleBestIndex", selectedIndex)
                    .putInt("simpleBestRawHash", best.raw.hashCode())
                    .putLong("simpleBestPingMs", best.pingMs ?: -1L)
                    .putString("simpleStatus", "Switching to next healthy: $displayName • Ping $pingLabel")
                    .putString("status", "Switching to next healthy")
                    .putString("activeMode", "simple_xray")
                    .putLong("startedAt", System.currentTimeMillis())
                    .putLong("simpleLastTrafficAt", System.currentTimeMillis())
                    .putBoolean("simpleHadTraffic", false)
                    .apply()
                _state.value = loadState()
                log("Simple XRAY next healthy selected: $displayName • $pingLabel • index=$selectedIndex/${configs.size}")

                val intent = Intent(app, RkhVpnService::class.java)
                    .setAction(RkhVpnService.ACTION_START)
                    .putExtra(RkhVpnService.EXTRA_RAW_CONFIG, best.raw)
                    .putExtra(RkhVpnService.EXTRA_SERVER_NAME, "SIMORGH Simple • $displayName")
                runCatching { app.startForegroundService(intent) }
                    .onFailure { e ->
                        prefs.edit()
                            .putBoolean("simpleConnecting", false)
                            .putBoolean("simpleConnected", false)
                            .putString("simpleStatus", "Next Healthy start failed: ${e.message ?: e.javaClass.simpleName}")
                            .putString("status", "Next Healthy start failed")
                            .putString("activeMode", "idle")
                            .putLong("startedAt", 0L)
                            .apply()
                        log("Simple XRAY next healthy service start failed", e)
                        _state.value = loadState()
                        return@launch
                    }

                prefs.edit()
                    .putBoolean("simpleConnecting", false)
                    .putBoolean("simpleConnected", true)
                    .putString("simpleStatus", "Simple XRAY connected: $displayName • Ping $pingLabel")
                    .putString("status", "Simple XRAY connected")
                    .putString("activeMode", "simple_xray")
                    .apply()
                best.pingMs?.let { saveSimpleLatencyResult(best, it) }
                prefs.edit().putInt("simpleLatencyProbeIndex", selectedIndex).apply()
                _state.value = loadState()
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                prefs.edit()
                    .putBoolean("simpleConnecting", false)
                    .putBoolean("simpleConnected", prefs.getBoolean("simpleConnected", false))
                    .putString("simpleStatus", "Next Healthy error: ${e.message ?: e.javaClass.simpleName}")
                    .putString("status", "Next Healthy error")
                    .apply()
                log("Simple XRAY next healthy unexpected error", e)
                _state.value = loadState()
            }
        }
    }

    private data class SimpleLatencyEntry(val pingMs: Long, val at: Long)

    private fun checkSimpleNormalBackgroundLatency() {
        val now = System.currentTimeMillis()
        if (prefs.getBoolean("simpleServerlessEnabled", false)) return
        if (!prefs.getBoolean("simpleConnected", false)) return
        if (prefs.getBoolean("simpleConnecting", false)) return
        if (prefs.getString("activeMode", "") != "simple_xray") return
        if (now - prefs.getLong("simpleLatencyLastProbeAt", 0L) < SIMPLE_LATENCY_PROBE_INTERVAL_MS) return
        if (simpleBackgroundLatencyJob?.isActive == true) return

        val configs = loadSimpleConfigs(serverless = false)
        if (configs.size < 2) return
        val nextIndex = configs.indices.random()
        val candidate = configs[nextIndex]
        prefs.edit()
            .putLong("simpleLatencyLastProbeAt", now)
            .putInt("simpleLatencyProbeIndex", nextIndex)
            .putString("simpleLatencyScannerStatus", "Refreshing ping: ${simpleDisplayName(nextIndex)}")
            .apply()

        simpleBackgroundLatencyJob = viewModelScope.launch(Dispatchers.IO) {
            val tested = runCatching { simplePing.pingStrict3(candidate) }.getOrElse { candidate.copy(pingMs = null, error = it.message ?: it.javaClass.simpleName) }
            val ping = tested.pingMs
            if (ping != null) {
                saveSimpleLatencyResult(tested, ping)
                log("Simple background latency refreshed • index=$nextIndex/${configs.size} • ${simpleDisplayName(nextIndex)} • ${ping}ms • appExcludedFromVpn=true")
            } else {
                removeSimpleLatencyResult(candidate)
                log("Simple background latency failed • index=$nextIndex/${configs.size} • ${simpleDisplayName(nextIndex)} • ${tested.error ?: "unknown"} • appExcludedFromVpn=true")
            }
            prefs.edit()
                .putString("simpleLatencyScannerStatus", if (ping != null) "Ready pings refreshed • ${simpleDisplayName(nextIndex)} ${ping}ms" else "Refreshing pings...")
                .apply()
        }
    }

    private fun loadSimpleLatencyCache(): Map<String, SimpleLatencyEntry> {
        val now = System.currentTimeMillis()
        return prefs.getString("simpleLatencyCache", "").orEmpty()
            .lineSequence()
            .mapNotNull { line ->
                val parts = line.split('|')
                if (parts.size != 3) return@mapNotNull null
                val id = parts[0].trim()
                val ping = parts[1].trim().toLongOrNull()
                val at = parts[2].trim().toLongOrNull()
                if (id.isBlank() || ping == null || at == null || ping <= 0L) return@mapNotNull null
                if (now - at > SIMPLE_LATENCY_CACHE_KEEP_MS) return@mapNotNull null
                id to SimpleLatencyEntry(ping, at)
            }
            .toMap()
    }

    private fun writeSimpleLatencyCache(cache: Map<String, SimpleLatencyEntry>) {
        val now = System.currentTimeMillis()
        val text = cache.entries
            .sortedBy { it.key }
            .joinToString("\n") { (id, entry) -> "$id|${entry.pingMs}|${entry.at}" }
        prefs.edit()
            .putString("simpleLatencyCache", text)
            .putLong("simpleLatencyCacheUpdatedAt", now)
            .putInt("simpleLatencyHealthyCount", cache.size)
            .apply()
    }

    private fun saveSimpleLatencyResult(server: ServerConfig, pingMs: Long) {
        val updated = loadSimpleLatencyCache().toMutableMap()
        updated[server.id] = SimpleLatencyEntry(pingMs, System.currentTimeMillis())
        writeSimpleLatencyCache(updated)
    }

    private fun removeSimpleLatencyResult(server: ServerConfig) {
        val updated = loadSimpleLatencyCache().toMutableMap()
        updated.remove(server.id)
        writeSimpleLatencyCache(updated)
    }

    private fun currentSimpleConfigIndex(configs: List<ServerConfig>): Int {
        if (configs.isEmpty()) return 0
        val savedIndex = prefs.getInt("simpleBestIndex", -1)
        if (savedIndex in configs.indices) return savedIndex
        val savedId = prefs.getString("simpleBestId", "").orEmpty()
        if (savedId.isNotBlank()) {
            val byId = configs.indexOfFirst { it.id == savedId }
            if (byId >= 0) return byId
        }
        val savedHash = prefs.getInt("simpleBestRawHash", Int.MIN_VALUE)
        val byHash = configs.indexOfFirst { it.raw.hashCode() == savedHash }
        return if (byHash >= 0) byHash else 0
    }

    private fun simpleDisplayName(index: Int): String = "Config ${index + 1}"

    private fun buildSimpleConfigItems(configs: List<ServerConfig>): List<SimpleConfigUiItem> {
        if (configs.isEmpty()) return emptyList()
        val cache = loadSimpleLatencyCache()
        val selectedIndex = currentSimpleConfigIndex(configs)
        val savedPing = prefs.getLong("simpleBestPingMs", -1L)
        val savedId = prefs.getString("simpleBestId", "").orEmpty()
        return configs.mapIndexed { index, server ->
            val cachePing = cache[server.id]?.pingMs
            val fallbackPing = savedPing.takeIf { savedId.isNotBlank() && server.id == savedId && it > 0L }
            val ping = cachePing ?: fallbackPing
            SimpleConfigUiItem(
                index = index,
                label = simpleDisplayName(index),
                pingLabel = ping?.let { "${it}ms" } ?: "—",
                selected = savedId.isNotBlank() && index == selectedIndex,
                hasPing = ping != null
            )
        }.sortedWith(
            compareBy<SimpleConfigUiItem> { if (it.hasPing) it.pingLabel.removeSuffix("ms").toLongOrNull() ?: Long.MAX_VALUE else Long.MAX_VALUE }
                .thenBy { it.index }
        )
    }

    private suspend fun pingSimpleConfigsParallel(
        configs: List<ServerConfig>,
        parallelism: Int = 20,
        statusPrefix: String = "Ping All"
    ): List<Pair<Int, ServerConfig>> = coroutineScope {
        val safeParallelism = parallelism.coerceIn(1, 20)
        val total = configs.size
        val order = configs.withIndex().toList().shuffled()
        val results = ArrayList<Pair<Int, ServerConfig>>(total)
        val latencyCache = loadSimpleLatencyCache().toMutableMap()
        for (batch in order.chunked(safeParallelism)) {
            val tested = batch.map { indexed ->
                async(Dispatchers.IO) {
                    indexed.index to runCatching { simplePing.pingStrict3(indexed.value) }
                        .getOrElse { e -> indexed.value.copy(pingMs = null, error = e.message ?: e.javaClass.simpleName) }
                }
            }.awaitAll()
            results += tested
            tested.forEach { (_, server) ->
                val ping = server.pingMs
                if (ping != null && ping > 0L) latencyCache[server.id] = SimpleLatencyEntry(ping, System.currentTimeMillis()) else latencyCache.remove(server.id)
            }
            writeSimpleLatencyCache(latencyCache)
            val done = results.size
            val healthy = results.count { it.second.pingMs != null }
            val bestSoFar = results.filter { it.second.pingMs != null }.minByOrNull { it.second.pingMs!! }
            val bestText = bestSoFar?.let { " • best ${simpleDisplayName(it.first)} ${it.second.pingMs}ms" } ?: ""
            prefs.edit()
                .putString("simpleStatus", "$statusPrefix... $done/$total scanned • $healthy healthy$bestText")
                .putString("status", "$statusPrefix... $done/$total")
                .apply()
            _state.value = loadState()
        }
        results.sortedWith(compareBy<Pair<Int, ServerConfig>> { it.second.pingMs ?: Long.MAX_VALUE }.thenBy { it.first })
    }

    private fun Int.floorMod(mod: Int): Int = ((this % mod) + mod) % mod


    private data class NipoProfile(
        val name: String = "NipoVPN Profile",
        val token: String = "",
        val protocol: String = "socks5",
        val fakeUrls: String = "",
        val methods: String = "GET\nPOST\nPUT\nDELETE",
        val endPoints: String = "api\nlogin\nuser\nupdate",
        val timeout: String = "10",
        val pullTimeout: String = "50",
        val tunnelEnable: Boolean = false,
        val connectionReuse: Boolean = true,
        val tlsEnable: Boolean = true,
        val tlsVerifyPeer: Boolean = false,
        val tlsCertFile: String = "/etc/nipovpn/server.crt",
        val tlsKeyFile: String = "/etc/nipovpn/server.key",
        val tlsCaFile: String = "",
        val logLevel: String = "INFO",
        val serverIp: String = "",
        val serverPort: String = "443",
        val httpVersion: String = "1.1",
        val userAgent: String = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:132.0) Gecko/20100101 Firefox/132.0"
    )

    fun prepareNipoConnectUi() {
        val profile = currentNipoProfileFromPrefs()
        val yaml = buildNipoYaml(profile)
        val endpoint = profile.serverIp.ifBlank { parseNipoEndpoint(yaml).first } to (profile.serverPort.toIntOrNull()?.coerceIn(1, 65535) ?: 443)
        prefs.edit()
            .putBoolean("nipoConnecting", true)
            .putBoolean("nipoConnected", false)
            .putString("nipoConfigYaml", yaml)
            .putString("nipoStatus", "Starting NipoVPN Agent → SOCKS5 127.0.0.1:9992 → Xray...")
            .putString("status", "NipoVPN starting")
            .putString("activeMode", "nipo")
            .putLong("startedAt", System.currentTimeMillis())
            .putString("nipoServerAddress", endpoint.first)
            .putInt("nipoServerPort", endpoint.second)
            .apply()
        _state.value = loadState()
    }

    fun nipoConnectAfterPermission() {
        val app = getApplication<Application>()
        val profile = currentNipoProfileFromPrefs()
        val yaml = buildNipoYaml(profile)
        val endpoint = profile.serverIp.ifBlank { parseNipoEndpoint(yaml).first } to (profile.serverPort.toIntOrNull()?.coerceIn(1, 65535) ?: 443)
        prefs.edit()
            .putBoolean("nipoConnecting", true)
            .putBoolean("nipoConnected", false)
            .putString("nipoConfigYaml", yaml)
            .putString("nipoStatus", "NipoVPN connecting • ${profile.name} • ${endpoint.first}:${endpoint.second} • local SOCKS5 9992")
            .putString("status", "NipoVPN connecting")
            .putString("activeMode", "nipo")
            .putLong("startedAt", System.currentTimeMillis())
            .putString("nipoServerAddress", endpoint.first)
            .putInt("nipoServerPort", endpoint.second)
            .apply()
        _state.value = loadState()
        log("NipoVPN connect requested • profile=${profile.name} • server=${endpoint.first}:${endpoint.second} • localSocks=9992")
        val intent = Intent(app, RkhVpnService::class.java)
            .setAction(RkhVpnService.ACTION_START_NIPO)
            .putExtra(RkhVpnService.EXTRA_NIPO_CONFIG, yaml)
        runCatching { app.startForegroundService(intent) }
            .onFailure { e ->
                prefs.edit()
                    .putBoolean("nipoConnecting", false)
                    .putBoolean("nipoConnected", false)
                    .putString("nipoStatus", "NipoVPN start failed: ${e.message ?: e.javaClass.simpleName}")
                    .putString("status", "NipoVPN start failed")
                    .putString("activeMode", "idle")
                    .putLong("startedAt", 0L)
                    .apply()
                log("Failed to start NipoVPN foreground service", e)
                _state.value = loadState()
            }
    }

    fun nipoDisconnect() {
        val app = getApplication<Application>()
        val intent = Intent(app, RkhVpnService::class.java).setAction(RkhVpnService.ACTION_STOP)
        runCatching { app.startService(intent) }
            .onFailure { log("Failed to stop NipoVPN service", it) }
        prefs.edit()
            .putBoolean("nipoConnecting", false)
            .putBoolean("nipoConnected", false)
            .putString("nipoStatus", "NipoVPN disconnected")
            .putString("status", "NipoVPN disconnected")
            .putString("activeMode", "idle")
            .putLong("startedAt", 0L)
            .putLong("downloadKbps", 0L)
            .putLong("uploadKbps", 0L)
            .apply()
        _state.value = loadState()
        log("NipoVPN disconnect requested")
    }

    fun setNipoImportText(text: String) {
        prefs.edit().putString("nipoImportText", text).apply()
        _state.value = loadState()
    }

    fun addNipoProfileFromInput() {
        val input = prefs.getString("nipoImportText", "").orEmpty().trim()
        val parsed = parseNipoProfileFromLink(input)
        if (parsed == null) {
            prefs.edit().putString("nipoStatus", "Invalid NipoVPN link. Paste a nipovpn:// profile link.").apply()
            _state.value = loadState()
            return
        }
        applyNipoProfile(parsed, save = true, clearInput = true, status = "NipoVPN profile added • ${parsed.name}")
        log("NipoVPN profile added • ${parsed.name} • server=${parsed.serverIp}:${parsed.serverPort}")
    }

    fun selectNipoProfile(name: String) {
        val profile = loadNipoProfiles().firstOrNull { it.name == name } ?: return
        applyNipoProfile(profile, save = false, clearInput = false, status = "NipoVPN profile selected • ${profile.name}")
    }

    fun deleteSelectedNipoProfile() {
        val selected = prefs.getString("nipoSelectedProfile", "").orEmpty()
        val profiles = loadNipoProfiles().filterNot { it.name == selected }
        val next = profiles.firstOrNull()
        val edit = prefs.edit()
            .putString("nipoProfilesJson", profilesToJson(profiles))
            .putLong("nipoPingMs", -1L)
        if (next != null) {
            putNipoProfileFields(edit, next)
            edit.putString("nipoSelectedProfile", next.name)
            edit.putString("nipoConfigYaml", buildNipoYaml(next))
            edit.putString("nipoServerAddress", next.serverIp)
            edit.putInt("nipoServerPort", next.serverPort.toIntOrNull()?.coerceIn(1, 65535) ?: 443)
            edit.putString("nipoStatus", "NipoVPN profile deleted • selected ${next.name}")
        } else {
            val empty = NipoProfile()
            putNipoProfileFields(edit, empty)
            edit.putString("nipoSelectedProfile", "")
            edit.putString("nipoConfigYaml", buildNipoYaml(empty))
            edit.putString("nipoServerAddress", "")
            edit.putInt("nipoServerPort", 443)
            edit.putString("nipoStatus", "NipoVPN profile deleted • add a new profile")
        }
        edit.apply()
        _state.value = loadState()
    }

    fun saveCurrentNipoProfile() {
        val profile = currentNipoProfileFromPrefs().let {
            if (it.name.isBlank()) it.copy(name = it.serverIp.ifBlank { "NipoVPN Profile" }) else it
        }
        applyNipoProfile(profile, save = true, clearInput = false, status = "NipoVPN profile saved • ${profile.name}")
        log("NipoVPN profile saved • ${profile.name}")
    }

    fun setNipoField(field: String, value: String) {
        val edit = prefs.edit()
        when (field) {
            "name" -> edit.putString("nipoName", value)
            "token" -> edit.putString("nipoToken", value)
            "protocol" -> edit.putString("nipoProtocol", value.ifBlank { "socks5" })
            "fakeUrls" -> edit.putString("nipoFakeUrls", value)
            "methods" -> edit.putString("nipoMethods", value)
            "endPoints" -> edit.putString("nipoEndPoints", value)
            "timeout" -> edit.putString("nipoTimeout", value.filter { it.isDigit() }.ifBlank { "10" })
            "pullTimeout" -> edit.putString("nipoPullTimeout", value.filter { it.isDigit() }.ifBlank { "50" })
            "tlsCertFile" -> edit.putString("nipoTlsCertFile", value)
            "tlsKeyFile" -> edit.putString("nipoTlsKeyFile", value)
            "tlsCaFile" -> edit.putString("nipoTlsCaFile", value)
            "logLevel" -> edit.putString("nipoLogLevel", value.ifBlank { "INFO" })
            "serverIp" -> edit.putString("nipoServerAddress", value.trim())
            "serverPort" -> edit.putInt("nipoServerPort", value.filter { it.isDigit() }.toIntOrNull()?.coerceIn(1, 65535) ?: 443)
            "httpVersion" -> edit.putString("nipoHttpVersion", value.ifBlank { "1.1" })
            "userAgent" -> edit.putString("nipoUserAgent", value)
        }
        val after = currentNipoProfileFromPrefsAfter(editPreview = field to value)
        edit.putString("nipoConfigYaml", buildNipoYaml(after))
        edit.putString("nipoStatus", "NipoVPN config edited • tap Save Profile")
        edit.apply()
        _state.value = loadState()
    }

    fun setNipoBoolean(field: String, value: Boolean) {
        val edit = prefs.edit()
        when (field) {
            "tunnelEnable" -> edit.putBoolean("nipoTunnelEnable", value)
            "connectionReuse" -> edit.putBoolean("nipoConnectionReuse", value)
            "tlsEnable" -> edit.putBoolean("nipoTlsEnable", value)
            "tlsVerifyPeer" -> edit.putBoolean("nipoTlsVerifyPeer", value)
        }
        val after = currentNipoProfileFromPrefsAfter(editPreview = field to value.toString())
        edit.putString("nipoConfigYaml", buildNipoYaml(after))
        edit.putString("nipoStatus", "NipoVPN config edited • tap Save Profile")
        edit.apply()
        _state.value = loadState()
    }

    /** Backward-compatible entry point used by older UI builds. Accepts either nipovpn:// or raw YAML. */
    fun setNipoConfigYaml(text: String) {
        if (text.trim().startsWith("nipovpn://")) {
            prefs.edit().putString("nipoImportText", text).apply()
            addNipoProfileFromInput()
            return
        }
        val endpoint = parseNipoEndpoint(text)
        prefs.edit()
            .putString("nipoConfigYaml", text)
            .putString("nipoServerAddress", endpoint.first)
            .putInt("nipoServerPort", endpoint.second)
            .putString("nipoStatus", "NipoVPN raw YAML saved • ${endpoint.first}:${endpoint.second} • local SOCKS5 9992")
            .apply()
        _state.value = loadState()
    }

    fun resetNipoConfig() {
        val empty = NipoProfile()
        applyNipoProfile(empty, save = false, clearInput = true, status = "NipoVPN fields reset • paste a nipovpn:// profile")
        log("NipoVPN editor reset")
    }

    fun testNipoConfig() {
        viewModelScope.launch(Dispatchers.IO) {
            val profile = currentNipoProfileFromPrefs()
            val endpoint = profile.serverIp to (profile.serverPort.toIntOrNull()?.coerceIn(1, 65535) ?: 443)
            prefs.edit()
                .putString("nipoStatus", "Testing NipoVPN server ${endpoint.first}:${endpoint.second}...")
                .putString("nipoServerAddress", endpoint.first)
                .putInt("nipoServerPort", endpoint.second)
                .apply()
            if (endpoint.first.isBlank()) {
                prefs.edit().putLong("nipoPingMs", -1L).putString("nipoStatus", "NipoVPN server is empty. Add/select a profile first.").apply()
                _state.value = loadState()
                return@launch
            }
            val started = System.nanoTime()
            val ping = runCatching {
                Socket().use { socket ->
                    socket.tcpNoDelay = true
                    socket.soTimeout = 3500
                    socket.connect(InetSocketAddress(endpoint.first, endpoint.second), 3500)
                }
                ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L)
            }.getOrNull()
            if (ping != null) {
                prefs.edit()
                    .putLong("nipoPingMs", ping)
                    .putString("nipoStatus", "NipoVPN server reachable • ${endpoint.first}:${endpoint.second} • ${ping}ms")
                    .apply()
                log("NipoVPN server test OK • ${endpoint.first}:${endpoint.second} • ${ping}ms")
            } else {
                prefs.edit()
                    .putLong("nipoPingMs", -1L)
                    .putString("nipoStatus", "NipoVPN server not reachable • ${endpoint.first}:${endpoint.second}")
                    .apply()
                log("NipoVPN server test failed • ${endpoint.first}:${endpoint.second}")
            }
            _state.value = loadState()
        }
    }

    private fun defaultNipoConfigYaml(): String = buildNipoYaml(NipoProfile())

    private fun parseNipoEndpoint(yaml: String): Pair<String, Int> {
        val server = Regex("""(?m)^\s*serverIp\s*:\s*[\"']?([^\"'\s#]+)""").find(yaml)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        val port = Regex("""(?m)^\s*serverPort\s*:\s*(\d+)""").find(yaml)?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceIn(1, 65535) ?: 443
        return server to port
    }

    private fun parseNipoProfileFromLink(raw: String): NipoProfile? = runCatching {
        val input = raw.trim().replace("\r", "")
        if (!input.startsWith("nipovpn://")) return@runCatching null
        val payload = input.removePrefix("nipovpn://").trim()
        val decoded = runCatching { String(Base64.decode(payload, Base64.DEFAULT), Charsets.UTF_8) }
            .getOrElse { String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8) }
        val root = JSONObject(decoded)
        val cfg = root.optJSONObject("config") ?: JSONObject()
        val serverIp = cfg.optStringCompat("serverIp", "")
        NipoProfile(
            name = root.optStringCompat("name", serverIp.ifBlank { "NipoVPN Profile" }).ifBlank { serverIp.ifBlank { "NipoVPN Profile" } },
            token = cfg.optStringCompat("token", ""),
            protocol = cfg.optStringCompat("protocol", "socks5").ifBlank { "socks5" },
            fakeUrls = cfg.optStringCompat("fakeUrls", ""),
            methods = cfg.optStringCompat("methods", "GET\nPOST\nPUT\nDELETE"),
            endPoints = cfg.optStringCompat("endPoints", "api\nlogin\nuser\nupdate"),
            timeout = cfg.optStringCompat("timeout", "10"),
            pullTimeout = cfg.optStringCompat("pullTimeout", "50"),
            tunnelEnable = cfg.optBooleanCompat("tunnelEnable", false),
            connectionReuse = cfg.optBooleanCompat("connectionReuse", true),
            tlsEnable = cfg.optBooleanCompat("tlsEnable", true),
            tlsVerifyPeer = cfg.optBooleanCompat("tlsVerifyPeer", false),
            tlsCertFile = cfg.optStringCompat("tlsCertFile", "/etc/nipovpn/server.crt"),
            tlsKeyFile = cfg.optStringCompat("tlsKeyFile", "/etc/nipovpn/server.key"),
            tlsCaFile = cfg.optStringCompat("tlsCaFile", ""),
            logLevel = cfg.optStringCompat("logLevel", "INFO"),
            serverIp = serverIp,
            serverPort = cfg.optStringCompat("serverPort", "443"),
            httpVersion = cfg.optStringCompat("httpVersion", "1.1"),
            userAgent = cfg.optStringCompat("userAgent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:132.0) Gecko/20100101 Firefox/132.0")
        )
    }.getOrNull()

    private fun JSONObject.optStringCompat(key: String, defaultValue: String): String {
        if (!has(key) || isNull(key)) return defaultValue
        return opt(key)?.toString() ?: defaultValue
    }

    private fun JSONObject.optBooleanCompat(key: String, defaultValue: Boolean): Boolean {
        if (!has(key) || isNull(key)) return defaultValue
        return when (val value = opt(key)) {
            is Boolean -> value
            is String -> value.equals("true", ignoreCase = true) || value == "1" || value.equals("yes", ignoreCase = true)
            is Number -> value.toInt() != 0
            else -> defaultValue
        }
    }

    private fun currentNipoProfileFromPrefs(): NipoProfile = NipoProfile(
        name = prefs.getString("nipoName", "").orEmpty(),
        token = prefs.getString("nipoToken", "").orEmpty(),
        protocol = prefs.getString("nipoProtocol", "socks5").orEmpty().ifBlank { "socks5" },
        fakeUrls = prefs.getString("nipoFakeUrls", "").orEmpty(),
        methods = prefs.getString("nipoMethods", "GET\nPOST\nPUT\nDELETE").orEmpty().ifBlank { "GET\nPOST\nPUT\nDELETE" },
        endPoints = prefs.getString("nipoEndPoints", "api\nlogin\nuser\nupdate").orEmpty().ifBlank { "api\nlogin\nuser\nupdate" },
        timeout = prefs.getString("nipoTimeout", "10").orEmpty().ifBlank { "10" },
        pullTimeout = prefs.getString("nipoPullTimeout", "50").orEmpty().ifBlank { "50" },
        tunnelEnable = prefs.getBoolean("nipoTunnelEnable", false),
        connectionReuse = prefs.getBoolean("nipoConnectionReuse", true),
        tlsEnable = prefs.getBoolean("nipoTlsEnable", true),
        tlsVerifyPeer = prefs.getBoolean("nipoTlsVerifyPeer", false),
        tlsCertFile = prefs.getString("nipoTlsCertFile", "/etc/nipovpn/server.crt").orEmpty(),
        tlsKeyFile = prefs.getString("nipoTlsKeyFile", "/etc/nipovpn/server.key").orEmpty(),
        tlsCaFile = prefs.getString("nipoTlsCaFile", "").orEmpty(),
        logLevel = prefs.getString("nipoLogLevel", "INFO").orEmpty().ifBlank { "INFO" },
        serverIp = prefs.getString("nipoServerAddress", "").orEmpty(),
        serverPort = prefs.getInt("nipoServerPort", 443).toString(),
        httpVersion = prefs.getString("nipoHttpVersion", "1.1").orEmpty().ifBlank { "1.1" },
        userAgent = prefs.getString("nipoUserAgent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:132.0) Gecko/20100101 Firefox/132.0").orEmpty()
    )

    private fun currentNipoProfileFromPrefsAfter(editPreview: Pair<String, String>): NipoProfile {
        val p = currentNipoProfileFromPrefs()
        return when (editPreview.first) {
            "name" -> p.copy(name = editPreview.second)
            "token" -> p.copy(token = editPreview.second)
            "protocol" -> p.copy(protocol = editPreview.second.ifBlank { "socks5" })
            "fakeUrls" -> p.copy(fakeUrls = editPreview.second)
            "methods" -> p.copy(methods = editPreview.second)
            "endPoints" -> p.copy(endPoints = editPreview.second)
            "timeout" -> p.copy(timeout = editPreview.second.filter { it.isDigit() }.ifBlank { "10" })
            "pullTimeout" -> p.copy(pullTimeout = editPreview.second.filter { it.isDigit() }.ifBlank { "50" })
            "tlsCertFile" -> p.copy(tlsCertFile = editPreview.second)
            "tlsKeyFile" -> p.copy(tlsKeyFile = editPreview.second)
            "tlsCaFile" -> p.copy(tlsCaFile = editPreview.second)
            "logLevel" -> p.copy(logLevel = editPreview.second.ifBlank { "INFO" })
            "serverIp" -> p.copy(serverIp = editPreview.second.trim())
            "serverPort" -> p.copy(serverPort = editPreview.second.filter { it.isDigit() }.ifBlank { "443" })
            "httpVersion" -> p.copy(httpVersion = editPreview.second.ifBlank { "1.1" })
            "userAgent" -> p.copy(userAgent = editPreview.second)
            "tunnelEnable" -> p.copy(tunnelEnable = editPreview.second.toBoolean())
            "connectionReuse" -> p.copy(connectionReuse = editPreview.second.toBoolean())
            "tlsEnable" -> p.copy(tlsEnable = editPreview.second.toBoolean())
            "tlsVerifyPeer" -> p.copy(tlsVerifyPeer = editPreview.second.toBoolean())
            else -> p
        }
    }

    private fun applyNipoProfile(profile: NipoProfile, save: Boolean, clearInput: Boolean, status: String) {
        val profiles = if (save) upsertNipoProfile(profile) else loadNipoProfiles()
        val yaml = buildNipoYaml(profile)
        val edit = prefs.edit()
        putNipoProfileFields(edit, profile)
        edit.putString("nipoConfigYaml", yaml)
            .putString("nipoSelectedProfile", profile.name)
            .putString("nipoProfilesJson", profilesToJson(profiles))
            .putString("nipoServerAddress", profile.serverIp)
            .putInt("nipoServerPort", profile.serverPort.toIntOrNull()?.coerceIn(1, 65535) ?: 443)
            .putLong("nipoPingMs", -1L)
            .putString("nipoStatus", status)
        if (clearInput) edit.putString("nipoImportText", "")
        edit.apply()
        _state.value = loadState()
    }

    private fun putNipoProfileFields(edit: android.content.SharedPreferences.Editor, profile: NipoProfile) {
        edit.putString("nipoName", profile.name)
            .putString("nipoToken", profile.token)
            .putString("nipoProtocol", profile.protocol.ifBlank { "socks5" })
            .putString("nipoFakeUrls", profile.fakeUrls)
            .putString("nipoMethods", profile.methods)
            .putString("nipoEndPoints", profile.endPoints)
            .putString("nipoTimeout", profile.timeout.ifBlank { "10" })
            .putString("nipoPullTimeout", profile.pullTimeout.ifBlank { "50" })
            .putBoolean("nipoTunnelEnable", profile.tunnelEnable)
            .putBoolean("nipoConnectionReuse", profile.connectionReuse)
            .putBoolean("nipoTlsEnable", profile.tlsEnable)
            .putBoolean("nipoTlsVerifyPeer", profile.tlsVerifyPeer)
            .putString("nipoTlsCertFile", profile.tlsCertFile)
            .putString("nipoTlsKeyFile", profile.tlsKeyFile)
            .putString("nipoTlsCaFile", profile.tlsCaFile)
            .putString("nipoLogLevel", profile.logLevel.ifBlank { "INFO" })
            .putString("nipoHttpVersion", profile.httpVersion.ifBlank { "1.1" })
            .putString("nipoUserAgent", profile.userAgent)
    }

    private fun loadNipoProfiles(): List<NipoProfile> = runCatching {
        val arr = JSONArray(prefs.getString("nipoProfilesJson", "[]").orEmpty().ifBlank { "[]" })
        buildList {
            for (i in 0 until arr.length()) {
                val root = arr.optJSONObject(i) ?: continue
                val cfg = root.optJSONObject("config") ?: JSONObject()
                val serverIp = cfg.optStringCompat("serverIp", "")
                add(NipoProfile(
                    name = root.optStringCompat("name", serverIp.ifBlank { "NipoVPN Profile ${i + 1}" }),
                    token = cfg.optStringCompat("token", ""),
                    protocol = cfg.optStringCompat("protocol", "socks5"),
                    fakeUrls = cfg.optStringCompat("fakeUrls", ""),
                    methods = cfg.optStringCompat("methods", "GET\nPOST\nPUT\nDELETE"),
                    endPoints = cfg.optStringCompat("endPoints", "api\nlogin\nuser\nupdate"),
                    timeout = cfg.optStringCompat("timeout", "10"),
                    pullTimeout = cfg.optStringCompat("pullTimeout", "50"),
                    tunnelEnable = cfg.optBooleanCompat("tunnelEnable", false),
                    connectionReuse = cfg.optBooleanCompat("connectionReuse", true),
                    tlsEnable = cfg.optBooleanCompat("tlsEnable", true),
                    tlsVerifyPeer = cfg.optBooleanCompat("tlsVerifyPeer", false),
                    tlsCertFile = cfg.optStringCompat("tlsCertFile", "/etc/nipovpn/server.crt"),
                    tlsKeyFile = cfg.optStringCompat("tlsKeyFile", "/etc/nipovpn/server.key"),
                    tlsCaFile = cfg.optStringCompat("tlsCaFile", ""),
                    logLevel = cfg.optStringCompat("logLevel", "INFO"),
                    serverIp = serverIp,
                    serverPort = cfg.optStringCompat("serverPort", "443"),
                    httpVersion = cfg.optStringCompat("httpVersion", "1.1"),
                    userAgent = cfg.optStringCompat("userAgent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:132.0) Gecko/20100101 Firefox/132.0")
                ))
            }
        }
    }.getOrDefault(emptyList())

    private fun upsertNipoProfile(profile: NipoProfile): List<NipoProfile> {
        val cleanName = profile.name.ifBlank { profile.serverIp.ifBlank { "NipoVPN Profile" } }
        val fixed = profile.copy(name = cleanName)
        val existing = loadNipoProfiles().filterNot { it.name == cleanName }
        return listOf(fixed) + existing
    }

    private fun profilesToJson(profiles: List<NipoProfile>): String {
        val arr = JSONArray()
        profiles.forEach { profile -> arr.put(profileToJson(profile)) }
        return arr.toString()
    }

    private fun nipoProfileToLink(profile: NipoProfile): String {
        val json = profileToJson(profile).toString()
        val encoded = Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return "nipovpn://$encoded"
    }

    private fun profileToJson(profile: NipoProfile): JSONObject = JSONObject().apply {
        put("name", profile.name)
        put("config", JSONObject().apply {
            put("token", profile.token)
            put("protocol", profile.protocol)
            put("fakeUrls", profile.fakeUrls)
            put("methods", profile.methods)
            put("endPoints", profile.endPoints)
            put("timeout", profile.timeout)
            put("pullTimeout", profile.pullTimeout)
            put("tunnelEnable", profile.tunnelEnable)
            put("connectionReuse", profile.connectionReuse)
            put("tlsEnable", profile.tlsEnable)
            put("tlsVerifyPeer", profile.tlsVerifyPeer)
            put("tlsCertFile", profile.tlsCertFile)
            put("tlsKeyFile", profile.tlsKeyFile)
            put("tlsCaFile", profile.tlsCaFile)
            put("logLevel", profile.logLevel)
            put("listenIp", "127.0.0.1")
            put("listenPort", "9992")
            put("serverIp", profile.serverIp)
            put("serverPort", profile.serverPort)
            put("httpVersion", profile.httpVersion)
            put("userAgent", profile.userAgent)
        })
    }

    private fun buildNipoYaml(profile: NipoProfile): String {
        val port = profile.serverPort.toIntOrNull()?.coerceIn(1, 65535) ?: 443
        val protocol = profile.protocol.ifBlank { "socks5" }
        val logLevel = profile.logLevel.ifBlank { "INFO" }
        val httpVersion = profile.httpVersion.ifBlank { "1.1" }
        return buildString {
            append("---\n")
            append("general:\n")
            append("  token: ${yamlQuote(profile.token)}\n")
            append("  protocol: $protocol\n")
            append("  fakeUrls:\n")
            append(yamlList(profile.fakeUrls, listOf("nipo.ciron.net"), "    ")).append('\n')
            append("  methods:\n")
            append(yamlList(profile.methods, listOf("GET", "POST", "PUT", "DELETE"), "    ")).append('\n')
            append("  endPoints:\n")
            append(yamlList(profile.endPoints, listOf("api", "login", "user", "update"), "    ")).append('\n')
            append("  timeout: ${profile.timeout.toIntOrNull() ?: 10}\n")
            append("  pullTimeout: ${profile.pullTimeout.toIntOrNull() ?: 50}\n")
            append("  tunnelEnable: ${profile.tunnelEnable}\n")
            append("  connectionReuse: ${profile.connectionReuse}\n")
            append("  tlsEnable: ${profile.tlsEnable}\n")
            append("  tlsVerifyPeer: ${profile.tlsVerifyPeer}\n")
            append("  tlsCertFile: ${yamlQuote(profile.tlsCertFile)}\n")
            append("  tlsKeyFile: ${yamlQuote(profile.tlsKeyFile)}\n")
            append("  tlsCaFile: ${yamlQuote(profile.tlsCaFile)}\n")
            append("log:\n")
            append("  logLevel: ${yamlQuote(logLevel)}\n")
            append("  logFile: \"nipovpn.log\"\n")
            // NipoVPN validates the server block even when running in agent mode.
            // Keep it present and well-formed, but route SIMORGH traffic through the agent block below.
            append("server:\n")
            append("  threads: 8\n")
            append("  listenIp: \"0.0.0.0\"\n")
            append("  listenPort: 80\n")
            append("agent:\n")
            append("  threads: 8\n")
            append("  listenIp: \"127.0.0.1\"\n")
            append("  listenPort: 9992\n")
            append("  serverIp: ${yamlQuote(profile.serverIp)}\n")
            append("  serverPort: $port\n")
            append("  httpVersion: ${yamlQuote(httpVersion)}\n")
            append("  userAgent : ${yamlQuote(profile.userAgent)}\n")
        }
    }

    private fun yamlQuote(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private fun yamlList(value: String, fallback: List<String>, indent: String): String {
        val lines = value.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList().ifEmpty { fallback }
        return lines.joinToString("\n") { "$indent- ${yamlQuote(it)}" }
    }

    fun simpleDisconnect() {
        simpleBackgroundLatencyJob?.cancel()
        simpleBackgroundLatencyJob = null
        val app = getApplication<Application>()
        val intent = Intent(app, RkhVpnService::class.java).setAction(RkhVpnService.ACTION_STOP)
        runCatching { app.startService(intent) }
            .onFailure { log("Failed to stop Simple XRAY service", it) }
        prefs.edit()
            .putBoolean("simpleConnecting", false)
            .putBoolean("simpleConnected", false)
            .putString("simpleStatus", "Simple XRAY disconnected")
            .putString("status", "Simple XRAY disconnected")
            .putString("activeMode", "idle")
            .putLong("startedAt", 0L)
            .putLong("downloadKbps", 0L)
            .putLong("uploadKbps", 0L)
            .apply()
        _state.value = loadState()
        log("Simple XRAY disconnect requested")
    }

    private suspend fun updateSimpleSubscriptionInternal(showReady: Boolean): List<ServerConfig> {
        val serverless = prefs.getBoolean("simpleServerlessEnabled", false)
        val modeLabel = if (serverless) simpleServerlessDisplayName else "Normal"
        val updatingMessage = if (serverless) "Loading ServerLess config..." else "Updating Simple configs..."
        prefs.edit()
            .putString("simpleStatus", updatingMessage)
            .apply()
        _state.value = loadState()
        return runCatching {
            val body = withContext(Dispatchers.IO) { fetchSimpleConfigBody(serverless) }
            if (serverless) {
                val cleanBody = body.replace("﻿", "").trim()
                val remarks = runCatching { JSONObject(cleanBody).optString("remarks", "") }.getOrDefault("")
                log("Simple ServerLess asset read • chars=${cleanBody.length} • hasInbounds=${cleanBody.contains("\"inbounds\"")} • hasOutbounds=${cleanBody.contains("\"outbounds\"")} • remarks=${remarks.ifBlank { "none" }}")
            }
            val configs = parseSimpleConfigs(body, serverless)
            if (serverless) log("Simple ServerLess parser result • configs=${configs.size} • firstRawJson=${configs.firstOrNull()?.raw?.trim()?.startsWith("{") == true}")
            if (configs.isEmpty()) error("No supported configs found")
            prefs.edit()
                .putString(simpleBodyKey(serverless), body)
                .putInt(simpleCountKey(serverless), configs.size)
                .putInt("simpleConfigCount", configs.size)
                .putLong(simpleUpdatedAtKey(serverless), System.currentTimeMillis())
                .putString(
                    "simpleStatus",
                    if (showReady) "$modeLabel config saved • ${configs.size} configs" else "Searching and Ping... • ${configs.size} configs"
                )
                .apply()
            if (showReady) {
                simpleBackgroundLatencyJob?.cancel()
                prefs.edit()
                    .remove("simpleLatencyCache")
                    .remove("simpleLatencyProbeIndex")
                    .remove("simpleLatencyHealthyCount")
                    .remove("simpleLatencyCacheUpdatedAt")
                    .remove("simpleBestId")
                    .remove("simpleBestIndex")
                    .remove("simpleBestRawHash")
                    .putString("simpleBestName", "")
                    .putLong("simpleBestPingMs", -1L)
                    .apply()
                log("Simple XRAY $modeLabel update pressed • old healthy cache cleared")
            }
            log("Simple XRAY $modeLabel config loaded and saved to memory • configs=${configs.size}")
            _state.value = loadState()
            configs
        }.getOrElse { e ->
            val fallback = loadSimpleConfigs(serverless)
            prefs.edit()
                .putString("simpleStatus", if (fallback.isNotEmpty()) "$modeLabel load failed • using cached ${fallback.size} configs" else "$modeLabel load failed: ${e.message ?: e.javaClass.simpleName}")
                .putInt("simpleConfigCount", fallback.size)
                .apply()
            log("Simple XRAY $modeLabel config load failed", e)
            _state.value = loadState()
            fallback
        }
    }

    private fun fetchSimpleConfigBody(serverless: Boolean): String {
        if (serverless) return readBundledServerlessConfig()
        val conn = (URL(simpleSubscriptionUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12000
            readTimeout = 25000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "SIMORGH/1.1.23z29")
            setRequestProperty("Accept", "text/plain, */*")
        }
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty().trim()
            if (code !in 200..299) error("HTTP $code")
            if (body.isBlank()) error("Subscription response is empty")
            body
        } finally {
            conn.disconnect()
        }
    }

    private fun readBundledServerlessConfig(): String {
        return getApplication<Application>().assets.open(simpleServerlessAssetName).bufferedReader().use { it.readText() }
            .replace("﻿", "")
            .trim()
            .also { if (it.isBlank()) error("Bundled ServerLess config is empty") }
    }

    private fun parseSimpleConfigs(body: String, serverless: Boolean): List<ServerConfig> {
        val clean = body.replace("﻿", "").trim()
        val parsed = runCatching { simpleRepo.parseConfigs(clean) }.getOrDefault(emptyList())
        if (parsed.isNotEmpty()) return if (serverless) parsed.map { it.copy(name = simpleServerlessDisplayName) } else parsed
        if (serverless && clean.startsWith("{") && clean.contains("\"outbounds\"") && clean.contains("\"inbounds\"")) {
            return listOf(ServerConfig("serverless_${kotlin.math.abs(clean.hashCode())}", simpleServerlessDisplayName, clean, null, null))
        }
        return emptyList()
    }

    private fun simpleBodyKey(serverless: Boolean) = if (serverless) "simpleServerlessConfigBody" else "simpleSubscriptionBody"
    private fun simpleCountKey(serverless: Boolean) = if (serverless) "simpleServerlessConfigCount" else "simpleNormalConfigCount"
    private fun simpleUpdatedAtKey(serverless: Boolean) = if (serverless) "simpleServerlessConfigUpdatedAt" else "simpleSubscriptionUpdatedAt"

    private fun loadSimpleConfigs(serverless: Boolean = prefs.getBoolean("simpleServerlessEnabled", false)): List<ServerConfig> {
        val cachedBody = prefs.getString(simpleBodyKey(serverless), "").orEmpty()
        val cachedConfigs = if (cachedBody.isBlank()) emptyList() else parseSimpleConfigs(cachedBody, serverless)
        if (cachedConfigs.isNotEmpty()) return cachedConfigs
        if (!serverless) return emptyList()
        return runCatching { parseSimpleConfigs(readBundledServerlessConfig(), serverless = true) }.getOrDefault(emptyList())
    }

    fun clearSavedCleanIps() {
        prefs.edit()
            .remove("savedCleanIps")
            .remove("savedCleanIpPings")
            .remove("cfPingResults")
            .putString("manualIpsText", "")
            .putBoolean("manualIpMode", false)
            .putInt("cleanIpCount", 0)
            .putString("cfStatus", "IP Memory cleared")
            .apply()
        log("IP Memory cleared • saved clean IPs and Manual IP list removed")
        _state.value = loadState()
    }

    fun pingSavedCleanIps() = viewModelScope.launch {
        val ips = loadSavedCleanIps()
        if (ips.isEmpty()) {
            log("Ping Clean IPs requested but memory is empty")
            return@launch
        }
        log("Ping Clean IPs requested • ${ips.size} IPs")
        prefs.edit().putString("status", "Pinging ${ips.size} clean IPs...").apply()
        _state.value = loadState()
        val port = prefs.getInt("selectedPort", 443).takeIf { it in 1..65535 } ?: 443
        val results = withContext(Dispatchers.IO) {
            ips.associateWith { ip -> tcpPingMs(ip, port, 2500) }
        }
        val pingText = ips.mapNotNull { ip -> results[ip]?.let { "$ip=$it" } }.joinToString("\n")
        val ok = results.count { it.value != null }
        prefs.edit()
            .putString("savedCleanIpPings", pingText)
            .putString("status", "Clean IP ping done • $ok/${ips.size} reachable")
            .apply()
        log("Ping Clean IPs done • $ok/${ips.size} reachable")
        _state.value = loadState()
    }

    private fun tcpPingMs(ip: String, port: Int, timeoutMs: Int): Long? {
        return runCatching {
            val started = System.nanoTime()
            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(ip, port), timeoutMs)
            }
            ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0L)
        }.getOrNull()
    }


    fun setManualIpMode(enabled: Boolean) {
        prefs.edit().putBoolean("manualIpMode", enabled).apply()
        val merged = syncManualIpsIntoCleanMemory()
        val count = parseManualIpText(prefs.getString("manualIpsText", "").orEmpty()).size
        log("Manual IP mode ${if (enabled) "enabled" else "disabled"} • manualCandidates=$count • cleanMemory=${merged.size}")
        _state.value = loadState()
    }

    fun setManualIpsText(text: String) {
        val manualIps = parseManualIpText(text)
        prefs.edit().putString("manualIpsText", text).apply()
        val mergedCleanIps = syncManualIpsIntoCleanMemory()
        log("Manual IP list updated • manualCandidates=${manualIps.size} • merged into Clean IP Memory=${mergedCleanIps.size}")
        _state.value = loadState()
    }


    private data class CfVlessProbe(val sni: String, val port: Int)

    private fun parseVlessForCf(raw: String): CfVlessProbe? {
        val clean = raw.trim()
        if (!clean.startsWith("vless://", ignoreCase = true)) return null
        return runCatching {
            val uri = URI(clean)
            val port = if (uri.port in 1..65535) uri.port else 443
            val query = parseQuery(uri.rawQuery.orEmpty())
            val sni = (query["sni"] ?: query["servername"] ?: query["serverName"] ?: query["host"] ?: query["Host"] ?: uri.host.orEmpty()).trim()
            if (sni.isBlank()) null else CfVlessProbe(sni = sni, port = port)
        }.getOrNull()
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split('&').mapNotNull { part ->
            val k = part.substringBefore('=', "").trim()
            if (k.isBlank()) return@mapNotNull null
            val v = part.substringAfter('=', "")
            k to runCatching { URLDecoder.decode(v, "UTF-8") }.getOrDefault(v)
        }.toMap()
    }


    private data class CfVlessFull(
        val uuid: String, val port: Int, val security: String, val network: String,
        val encryption: String, val flow: String, val sni: String, val host: String, val path: String
    )

    private fun parseFullVlessForCf(raw: String): CfVlessFull? {
        val clean = raw.trim()
        if (!clean.startsWith("vless://", ignoreCase = true)) return null
        return runCatching {
            val uri = URI(clean)
            val uuid = uri.userInfo.orEmpty().substringBefore(':').trim()
            val port = if (uri.port in 1..65535) uri.port else 443
            val q = parseQuery(uri.rawQuery.orEmpty())
            val security = (q["security"] ?: "tls").ifBlank { "tls" }
            val network = (q["type"] ?: q["network"] ?: "ws").ifBlank { "ws" }
            val encryption = (q["encryption"] ?: "none").ifBlank { "none" }
            val flow = (q["flow"] ?: "").trim()
            val sni = (q["sni"] ?: q["servername"] ?: q["serverName"] ?: q["host"] ?: q["Host"] ?: uri.host.orEmpty()).trim()
            val host = (q["host"] ?: q["Host"] ?: sni).trim()
            val path = (q["path"] ?: "/").ifBlank { "/" }
            if (uuid.isBlank() || sni.isBlank()) null else CfVlessFull(uuid, port, security, network, encryption, flow, sni, host, path)
        }.getOrNull()
    }

    private fun cfXrayLatencyMs(cleanIp: String, vlessRaw: String, timeoutMs: Int): Long? {
        val cf = parseFullVlessForCf(vlessRaw) ?: return null
        if (!isIpv4Literal(cleanIp)) return null
        var process: Process? = null
        return runCatching {
            val app = getApplication<Application>()
            val xray = NativeBinaryManager(app).prepare("xray")
            val socksPort = freeLocalPort()
            val workDir = File(app.cacheDir, "cf-xray-latency").apply { mkdirs() }
            val config = buildCfLatencyXrayConfig(cf, cleanIp, socksPort)
            val configFile = File(workDir, "cf_latency_${System.currentTimeMillis()}.json").apply { writeText(config) }
            process = ProcessBuilder(listOf(xray.absolutePath, "run", "-config", configFile.absolutePath))
                .directory(workDir).redirectErrorStream(true).start()
            Thread.sleep(650)
            val exit = runCatching { process?.exitValue() }.getOrNull()
            if (exit != null) return null
            val started = System.nanoTime()
            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.soTimeout = timeoutMs
                socket.connect(InetSocketAddress("127.0.0.1", socksPort), timeoutMs)
                // Real latency: go through temporary Xray + VLESS and wait for a real Internet response.
                // Connecting only to the local SOCKS port is NOT counted as latency.
                socks5Connect(socket, "cp.cloudflare.com", 80)
                val out = socket.getOutputStream()
                out.write("GET /generate_204 HTTP/1.1\r\nHost: cp.cloudflare.com\r\nUser-Agent: SIMORGH-CF-Latency/1.0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                out.flush()
                val first = socket.getInputStream().read()
                if (first < 0) error("No remote response through CF VLESS route")
            }
            val ms = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L)
            if (ms < 20L) null else ms
        }.getOrNull().also { process?.destroy() }
    }


    private fun verifyCfOverXray(rawSocket: Socket, cf: CfVlessFull, timeoutMs: Int) {
        val factory = SSLContext.getDefault().socketFactory
        val tls = factory.createSocket(rawSocket, cf.sni, 443, true) as SSLSocket
        tls.soTimeout = timeoutMs
        tls.sslParameters = tls.sslParameters.apply {
            serverNames = listOf(SNIHostName(cf.sni))
            endpointIdentificationAlgorithm = "HTTPS"
        }
        tls.startHandshake()
        val request = buildString {
            append("HEAD / HTTP/1.1\r\n")
            append("Host: ").append(cf.host.ifBlank { cf.sni }).append("\r\n")
            append("User-Agent: SIMORGH-CF-Latency/1.0\r\n")
            append("Connection: close\r\n\r\n")
        }.toByteArray(Charsets.US_ASCII)
        tls.getOutputStream().write(request)
        tls.getOutputStream().flush()
        val line = readHttpStatusLine(tls)
        if (!line.startsWith("HTTP/", ignoreCase = true)) error("No HTTP response through CF VLESS route")
    }

    private fun readHttpStatusLine(socket: Socket): String {
        val input = socket.getInputStream()
        val out = StringBuilder()
        while (out.length < 256) {
            val b = input.read()
            if (b < 0) break
            if (b == 10) break
            if (b != 13) out.append(b.toChar())
        }
        return out.toString()
    }

    private fun freeLocalPort(): Int = ServerSocket(0).use { it.localPort }

    private fun socks5Connect(socket: Socket, host: String, port: Int) {
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        output.write(byteArrayOf(0x05, 0x01, 0x00)); output.flush()
        val hello = ByteArray(2); if (input.read(hello) != 2 || hello[1].toInt() != 0x00) error("SOCKS auth failed")
        val hb = host.toByteArray(Charsets.UTF_8)
        val req = ByteArray(7 + hb.size)
        req[0]=0x05; req[1]=0x01; req[2]=0x00; req[3]=0x03; req[4]=hb.size.toByte()
        System.arraycopy(hb,0,req,5,hb.size); req[5+hb.size]=(port shr 8).toByte(); req[6+hb.size]=port.toByte()
        output.write(req); output.flush()
        val head = ByteArray(4); if (input.read(head) != 4 || head[1].toInt() != 0x00) error("SOCKS connect failed")
        val atyp = head[3].toInt() and 0xff
        val skip = when (atyp) { 1 -> 4; 3 -> input.read(); 4 -> 16; else -> 0 } + 2
        if (skip > 0) { val buf = ByteArray(skip); var off=0; while(off<skip){ val n=input.read(buf,off,skip-off); if(n<0) break; off+=n } }
    }

    private fun buildCfLatencyXrayConfig(cf: CfVlessFull, cleanIp: String, socksPort: Int): String {
        val inbound = JSONObject().apply {
            put("tag", "socks-in"); put("listen", "127.0.0.1"); put("port", socksPort); put("protocol", "socks")
            put("settings", JSONObject().apply { put("udp", false); put("auth", "noauth") })
        }
        val user = JSONObject().apply { put("id", cf.uuid); put("encryption", cf.encryption); if (cf.flow.isNotBlank()) put("flow", cf.flow) }
        val stream = JSONObject().apply {
            put("network", cf.network); put("security", cf.security)
            if (cf.security.equals("tls", true)) put("tlsSettings", JSONObject().apply { put("serverName", cf.sni); put("allowInsecure", false); put("fingerprint", "chrome") })
            if (cf.network.equals("ws", true)) put("wsSettings", JSONObject().apply { put("path", cf.path); put("headers", JSONObject().apply { put("Host", cf.host) }) })
        }
        val outbound = JSONObject().apply {
            put("tag", "cf-vless-out"); put("protocol", "vless")
            put("settings", JSONObject().apply { put("vnext", JSONArray().put(JSONObject().apply { put("address", cleanIp); put("port", cf.port); put("users", JSONArray().put(user)) })) })
            put("streamSettings", stream)
        }
        return JSONObject().apply {
            put("log", JSONObject().apply { put("loglevel", "error") })
            put("inbounds", JSONArray().put(inbound)); put("outbounds", JSONArray().put(outbound))
        }.toString(2)
    }

    private fun cfTlsPingMs(ip: String, sni: String, port: Int, timeoutMs: Int): Long? {
        return runCatching {
            val started = System.nanoTime()
            Socket().use { raw ->
                raw.tcpNoDelay = true
                raw.soTimeout = timeoutMs
                raw.connect(InetSocketAddress(ip, port), timeoutMs)
                val ssl = SSLContext.getInstance("TLS").apply { init(null, null, null) }.socketFactory.createSocket(raw, sni, port, true) as SSLSocket
                ssl.use { sock ->
                    sock.sslParameters = sock.sslParameters.apply {
                        serverNames = listOf(SNIHostName(sni))
                        endpointIdentificationAlgorithm = "HTTPS"
                    }
                    sock.soTimeout = timeoutMs
                    sock.startHandshake()
                }
            }
            ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0L)
        }.getOrNull()
    }

    private fun loadCfPingResults(): Map<String, String> {
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

    private fun loadSavedCleanIpPings(): Map<String, Long> {
        return prefs.getString("savedCleanIpPings", "").orEmpty()
            .lineSequence()
            .mapNotNull { line ->
                val parts = line.split("=", limit = 2)
                val ip = parts.getOrNull(0)?.trim().orEmpty()
                val ping = parts.getOrNull(1)?.trim()?.toLongOrNull()
                if (ip.isNotBlank() && ping != null && ping >= 0L) ip to ping else null
            }
            .toMap()
    }

    private fun startSyncLoop() {
        syncJob?.cancel()
        syncJob = viewModelScope.launch {
            while (isActive) {
                checkSimpleNormalWatchdog()
                checkSimpleNormalBackgroundLatency()
                _state.value = loadState()
                delay(1000)
            }
        }
    }


    private fun checkSimpleNormalWatchdog() {
        val now = System.currentTimeMillis()
        if (prefs.getBoolean("simpleServerlessEnabled", false)) return
        if (!prefs.getBoolean("simpleConnected", false)) return
        if (prefs.getBoolean("simpleConnecting", false)) return
        if (prefs.getString("activeMode", "") != "simple_xray") return
        val startedAt = prefs.getLong("startedAt", 0L)
        if (startedAt <= 0L || now - startedAt < 60_000L) return
        val lastReconnectAt = prefs.getLong("simpleWatchdogReconnectAt", 0L)
        if (now - lastReconnectAt < 90_000L) return
        val hadTraffic = prefs.getBoolean("simpleHadTraffic", false)
        if (!hadTraffic) return
        val lastTrafficAt = prefs.getLong("simpleLastTrafficAt", startedAt)
        val down = prefs.getLong("downloadKbps", 0L)
        val up = prefs.getLong("uploadKbps", 0L)
        if (down > 0L || up > 0L) {
            prefs.edit()
                .putLong("simpleLastTrafficAt", now)
                .putBoolean("simpleHadTraffic", true)
                .apply()
            return
        }
        if (now - lastTrafficAt < 75_000L) return
        prefs.edit()
            .putLong("simpleWatchdogReconnectAt", now)
            .putString("simpleStatus", "Simple auto-reconnect: testing next healthy config...")
            .putString("status", "Simple auto-reconnect")
            .apply()
        log("Simple normal watchdog triggered • no traffic for ${(now - lastTrafficAt) / 1000}s • switching to next healthy config")
        simpleConnectNextHealthyAfterPermission()
    }

    private fun loadState(): SimorghPublicState {
        ensureDefaults()
        val connected = prefs.getBoolean("connected", false)
        val connecting = prefs.getBoolean("connecting", false)
        val simpleConnected = prefs.getBoolean("simpleConnected", false)
        val simpleConnecting = prefs.getBoolean("simpleConnecting", false)
        val nipoConnected = prefs.getBoolean("nipoConnected", false)
        val nipoConnecting = prefs.getBoolean("nipoConnecting", false)
        val startedAt = prefs.getLong("startedAt", 0L)
        val now = System.currentTimeMillis()
        val elapsed = if ((connected || connecting || simpleConnected || simpleConnecting || nipoConnected || nipoConnecting) && startedAt > 0L) ((now - startedAt) / 1000L).coerceAtLeast(0L) else 0L
        val code = prefs.getString("routeCountryCode", "").orEmpty()
        val route = if (code.isNotBlank() || prefs.getString("routeIp", "").orEmpty().isNotBlank()) {
            SimorghRoute(
                engine = prefs.getString("routeEngine", "rkh_msp_http_proxy").orEmpty(),
                countryCode = code,
                countryName = prefs.getString("routeCountryName", "").orEmpty(),
                ip = prefs.getString("routeIp", "").orEmpty(),
                latitude = prefs.getFloat("routeLatitude", Float.NaN).takeIf { !it.isNaN() }?.toDouble(),
                longitude = prefs.getFloat("routeLongitude", Float.NaN).takeIf { !it.isNaN() }?.toDouble()
            ).let { r -> if (r.countryName.isBlank() && r.countryCode.isNotBlank()) CountryCoordinates.routeFor(r.countryCode, r.ip, r.engine) else r }
        } else null
        val simpleServerlessNow = prefs.getBoolean("simpleServerlessEnabled", false)
        val simpleConfigsNow = loadSimpleConfigs(simpleServerlessNow)
        val simpleSavedIndex = prefs.getInt("simpleBestIndex", -1)
        val simpleBestDisplay = if (simpleSavedIndex in simpleConfigsNow.indices) simpleDisplayName(simpleSavedIndex) else prefs.getString("simpleBestName", "").orEmpty()

        return SimorghPublicState(
            connected = connected,
            connecting = connecting,
            status = prefs.getString("status", "Ready").orEmpty(),
            engine = prefs.getString("engine", "RKh-MSP").orEmpty(),
            selectedRunMode = prefs.getString("selectedRunMode", "proxy").orEmpty().ifBlank { "proxy" },
            elapsedSeconds = elapsed,
            downloadKbps = prefs.getLong("downloadKbps", 0L),
            uploadKbps = prefs.getLong("uploadKbps", 0L),
            route = route,
            publicEngineAvailable = true,
            lastError = prefs.getString("lastError", "").orEmpty(),
            selectedIsp = prefs.getString("selectedIsp", defaultIsp()).orEmpty().ifBlank { defaultIsp() },
            selectedSnis = prefs.getStringSet("selectedSnis", setOf("chatgpt.com")) ?: setOf("chatgpt.com"),
            selectedPort = prefs.getInt("selectedPort", 443),
            maxScanIps = prefs.getInt("maxScanIps", 33000).coerceIn(1, 33000),
            scanSpeed = prefs.getString("scanSpeed", "normal").orEmpty().ifBlank { "normal" },
            manualIpMode = prefs.getBoolean("manualIpMode", false),
            manualIpsText = prefs.getString("manualIpsText", "").orEmpty(),
            manualCandidateCount = parseManualIpText(prefs.getString("manualIpsText", "").orEmpty()).size,
            scannedCount = prefs.getInt("scannedCount", 0),
            totalCandidates = prefs.getInt("totalCandidates", 0),
            cleanIpCount = maxOf(prefs.getInt("cleanIpCount", 0), loadSavedCleanIps().size),
            savedCleanIps = loadSavedCleanIps(),
            savedCleanIpPings = loadSavedCleanIpPings(),
            activeRouteTarget = prefs.getString("activeRouteTarget", "").orEmpty(),
            activeRouteIp = prefs.getString("activeRouteIp", "").orEmpty(),
            activeRoutePingMs = prefs.getLong("activeRoutePingMs", -1L),
            proxyPort = prefs.getInt("proxyPort", if (prefs.getString("selectedProxyProtocol", "socks5") == "http") 9991 else 9990),
            socks5ProxyPort = prefs.getInt("socks5ProxyPort", 9990),
            httpProxyPort = prefs.getInt("httpProxyPort", 9991),
            selectedProxyProtocol = prefs.getString("selectedProxyProtocol", "socks5").orEmpty().ifBlank { "socks5" },
            routeStrategy = prefs.getString("routeStrategy", "default").orEmpty().ifBlank { "default" },
            cfVlessConfig = prefs.getString("cfVlessConfig", "").orEmpty(),
            cfEnabled = prefs.getBoolean("cfEnabled", false),
            cfPingResults = loadCfPingResults(),
            cfStatus = prefs.getString("cfStatus", "").orEmpty(),
            cfConnectingIp = prefs.getString("cfConnectingIp", "").orEmpty(),
            activeMode = prefs.getString("activeMode", "idle").orEmpty(),
            simpleConnected = simpleConnected,
            simpleConnecting = simpleConnecting,
            simpleStatus = prefs.getString("simpleStatus", "Simple XRAY ready").orEmpty(),
            simpleConfigCount = simpleConfigsNow.size,
            simpleConfigItems = buildSimpleConfigItems(simpleConfigsNow),
            simpleBestName = simpleBestDisplay,
            simpleBestPingMs = prefs.getLong("simpleBestPingMs", -1L),
            simpleServerlessEnabled = simpleServerlessNow,
            nipoConnected = nipoConnected,
            nipoConnecting = nipoConnecting,
            nipoStatus = prefs.getString("nipoStatus", "NipoVPN ready").orEmpty(),
            nipoConfigYaml = prefs.getString("nipoConfigYaml", "").orEmpty().ifBlank { defaultNipoConfigYaml() },
            nipoConfigInput = prefs.getString("nipoConfigInput", "").orEmpty(),
            nipoImportText = prefs.getString("nipoImportText", "").orEmpty(),
            nipoProfiles = loadNipoProfiles().map { it.name },
            nipoProfileExports = loadNipoProfiles().associate { it.name to nipoProfileToLink(it) },
            nipoSelectedProfile = prefs.getString("nipoSelectedProfile", "").orEmpty(),
            nipoName = prefs.getString("nipoName", "").orEmpty(),
            nipoToken = prefs.getString("nipoToken", "").orEmpty(),
            nipoProtocol = prefs.getString("nipoProtocol", "socks5").orEmpty().ifBlank { "socks5" },
            nipoFakeUrls = prefs.getString("nipoFakeUrls", "").orEmpty(),
            nipoMethods = prefs.getString("nipoMethods", "GET\nPOST\nPUT\nDELETE").orEmpty(),
            nipoEndPoints = prefs.getString("nipoEndPoints", "api\nlogin\nuser\nupdate").orEmpty(),
            nipoTimeout = prefs.getString("nipoTimeout", "10").orEmpty().ifBlank { "10" },
            nipoPullTimeout = prefs.getString("nipoPullTimeout", "50").orEmpty().ifBlank { "50" },
            nipoTunnelEnable = prefs.getBoolean("nipoTunnelEnable", false),
            nipoConnectionReuse = prefs.getBoolean("nipoConnectionReuse", true),
            nipoTlsEnable = prefs.getBoolean("nipoTlsEnable", true),
            nipoTlsVerifyPeer = prefs.getBoolean("nipoTlsVerifyPeer", false),
            nipoTlsCertFile = prefs.getString("nipoTlsCertFile", "/etc/nipovpn/server.crt").orEmpty(),
            nipoTlsKeyFile = prefs.getString("nipoTlsKeyFile", "/etc/nipovpn/server.key").orEmpty(),
            nipoTlsCaFile = prefs.getString("nipoTlsCaFile", "").orEmpty(),
            nipoLogLevel = prefs.getString("nipoLogLevel", "INFO").orEmpty().ifBlank { "INFO" },
            nipoListenIp = "127.0.0.1",
            nipoListenPort = "9992",
            nipoServerAddress = prefs.getString("nipoServerAddress", parseNipoEndpoint(prefs.getString("nipoConfigYaml", "").orEmpty().ifBlank { defaultNipoConfigYaml() }).first).orEmpty(),
            nipoServerPort = prefs.getInt("nipoServerPort", parseNipoEndpoint(prefs.getString("nipoConfigYaml", "").orEmpty().ifBlank { defaultNipoConfigYaml() }).second),
            nipoHttpVersion = prefs.getString("nipoHttpVersion", "1.1").orEmpty().ifBlank { "1.1" },
            nipoUserAgent = prefs.getString("nipoUserAgent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:132.0) Gecko/20100101 Firefox/132.0").orEmpty(),
            nipoPingMs = prefs.getLong("nipoPingMs", -1L),
            nipoSocksPort = prefs.getInt("nipoSocksPort", 9992),
            ispOptions = ispOptions,
            sniOptions = sniOptions
        )
    }

    private fun ensureDefaults() {
        val edit = prefs.edit()
        var changed = false
        if (!prefs.contains("selectedIsp") || prefs.getString("selectedIsp", "").isNullOrBlank()) {
            edit.putString("selectedIsp", defaultIsp())
            changed = true
        }
        if (!prefs.contains("selectedSnis")) {
            edit.putStringSet("selectedSnis", setOf("chatgpt.com"))
            changed = true
        }
        if (!prefs.contains("selectedPort")) {
            edit.putInt("selectedPort", 443)
            changed = true
        }
        if (!prefs.contains("maxScanIps")) {
            edit.putInt("maxScanIps", 33000)
            changed = true
        }
        if (!prefs.contains("scanSpeed")) {
            edit.putString("scanSpeed", "normal")
            changed = true
        }
        if (!prefs.contains("selectedRunMode")) {
            edit.putString("selectedRunMode", "proxy")
            changed = true
        }
        if (!prefs.contains("manualIpMode")) {
            edit.putBoolean("manualIpMode", false)
            changed = true
        }
        if (!prefs.contains("manualIpsText")) {
            edit.putString("manualIpsText", "")
            changed = true
        }
        if (!prefs.contains("selectedProxyProtocol")) {
            edit.putString("selectedProxyProtocol", "socks5")
            changed = true
        }
        if (!prefs.contains("routeStrategy")) {
            edit.putString("routeStrategy", "default")
            changed = true
        }
        if (!prefs.contains("socks5ProxyPort")) {
            edit.putInt("socks5ProxyPort", 9990)
            changed = true
        }
        if (!prefs.contains("httpProxyPort")) {
            edit.putInt("httpProxyPort", 9991)
            changed = true
        }
        if (!prefs.contains("proxyPort")) {
            edit.putInt("proxyPort", if (prefs.getString("selectedProxyProtocol", "socks5") == "http") 9991 else 9990)
            changed = true
        }
        if (!prefs.contains("activeMode")) {
            edit.putString("activeMode", "idle")
            changed = true
        }
        if (!prefs.contains("cfVlessConfig")) {
            edit.putString("cfVlessConfig", "")
            changed = true
        }
        if (!prefs.contains("cfEnabled")) { edit.putBoolean("cfEnabled", false); changed = true }
        if (!prefs.contains("simpleStatus")) { edit.putString("simpleStatus", "Simple XRAY ready"); changed = true }
        if (!prefs.contains("simpleConnected")) { edit.putBoolean("simpleConnected", false); changed = true }
        if (!prefs.contains("simpleConnecting")) { edit.putBoolean("simpleConnecting", false); changed = true }
        if (!prefs.contains("simpleServerlessEnabled")) { edit.putBoolean("simpleServerlessEnabled", false); changed = true }
        if (!prefs.contains("nipoConfigYaml")) {
            val yaml = defaultNipoConfigYaml()
            val endpoint = parseNipoEndpoint(yaml)
            edit.putString("nipoConfigYaml", yaml)
            edit.putString("nipoServerAddress", endpoint.first)
            edit.putInt("nipoServerPort", endpoint.second)
            changed = true
        }
        if (!prefs.contains("nipoStatus")) { edit.putString("nipoStatus", "NipoVPN ready"); changed = true }
        if (!prefs.contains("nipoConnected")) { edit.putBoolean("nipoConnected", false); changed = true }
        if (!prefs.contains("nipoConnecting")) { edit.putBoolean("nipoConnecting", false); changed = true }
        if (!prefs.contains("nipoSocksPort")) { edit.putInt("nipoSocksPort", 9992); changed = true }
        if (!prefs.contains("nipoPingMs")) { edit.putLong("nipoPingMs", -1L); changed = true }
        if (!prefs.contains("nipoProtocol")) { edit.putString("nipoProtocol", "socks5"); changed = true }
        if (!prefs.contains("nipoMethods")) { edit.putString("nipoMethods", "GET\nPOST\nPUT\nDELETE"); changed = true }
        if (!prefs.contains("nipoEndPoints")) { edit.putString("nipoEndPoints", "api\nlogin\nuser\nupdate"); changed = true }
        if (!prefs.contains("nipoTimeout")) { edit.putString("nipoTimeout", "10"); changed = true }
        if (!prefs.contains("nipoPullTimeout")) { edit.putString("nipoPullTimeout", "50"); changed = true }
        if (!prefs.contains("nipoTlsEnable")) { edit.putBoolean("nipoTlsEnable", true); changed = true }
        if (!prefs.contains("nipoConnectionReuse")) { edit.putBoolean("nipoConnectionReuse", true); changed = true }
        if (!prefs.contains("nipoTlsVerifyPeer")) { edit.putBoolean("nipoTlsVerifyPeer", false); changed = true }
        if (!prefs.contains("nipoTunnelEnable")) { edit.putBoolean("nipoTunnelEnable", false); changed = true }
        if (!prefs.contains("nipoLogLevel")) { edit.putString("nipoLogLevel", "INFO"); changed = true }
        if (!prefs.contains("nipoHttpVersion")) { edit.putString("nipoHttpVersion", "1.1"); changed = true }
        if (!prefs.contains("nipoUserAgent")) { edit.putString("nipoUserAgent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:132.0) Gecko/20100101 Firefox/132.0"); changed = true }
        if (changed) edit.apply()
    }

    private fun parseManualIpText(text: String): List<String> {
        val out = linkedSetOf<String>()
        text.lineSequence()
            .flatMap { it.split(',', ';', ' ', '\t').asSequence() }
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .forEach { token ->
                if (isIpv4Literal(token)) out += token
            }
        return out.toList()
    }

    private fun isIpv4Literal(host: String): Boolean {
        val parts = host.split('.')
        return parts.size == 4 && parts.all { part -> part.toIntOrNull()?.let { it in 0..255 } == true }
    }

    private fun defaultIsp(): String = ispOptions.firstOrNull { it.contains("arvan", ignoreCase = true) }
        ?: "AbrArvan CDN and IaaS"

    private fun loadIspOptions(): List<String> {
        val out = linkedSetOf<String>()
        runCatching {
            getApplication<Application>().assets.open("ranges/IPs.csv").bufferedReader().useLines { lines ->
                lines.drop(1).forEach { line ->
                    val cols = parseCsvLine(line)
                    val asName = cols.getOrNull(6)?.trim().orEmpty()
                    if (asName.isNotBlank()) out += asName
                }
            }
        }.onFailure { log("Failed to load ISP options from ranges/IPs.csv", it) }
        val sorted = out.sortedWith(compareBy<String> { !it.contains("arvan", ignoreCase = true) }.thenBy { it.lowercase(Locale.US) })
        return sorted.ifEmpty { listOf("AbrArvan CDN and IaaS") }
    }

    private fun loadSniOptions(): List<String> {
        val out = linkedSetOf("chatgpt.com", "openai.com")
        runCatching {
            getApplication<Application>().assets.open("msp_sni_catalog.txt").bufferedReader().forEachLine { line ->
                val s = line.trim().lowercase(Locale.US)
                if (s.isNotBlank() && !s.startsWith("#")) out += s
            }
        }.onFailure { log("Failed to load SNI catalog", it) }
        return out.toList()
    }

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

    private fun syncManualIpsIntoCleanMemory(): List<String> {
        val manualIps = parseManualIpText(prefs.getString("manualIpsText", "").orEmpty())
        val merged = (loadStoredCleanIps() + manualIps)
            .filter { isIpv4Literal(it) }
            .distinct()
            .take(300)
        prefs.edit()
            .putString("savedCleanIps", merged.joinToString("\n"))
            .putInt("cleanIpCount", merged.size)
            .apply()
        return merged
    }

    private fun loadStoredCleanIps(): List<String> {
        return prefs.getString("savedCleanIps", "").orEmpty()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && isIpv4Literal(it) }
            .distinct()
            .take(300)
            .toList()
    }

    private fun loadSavedCleanIps(): List<String> {
        val out = linkedSetOf<String>()
        loadStoredCleanIps().forEach { out += it }
        // Manual IPs are treated as clean IP memory even when Manual mode is OFF.
        parseManualIpText(prefs.getString("manualIpsText", "").orEmpty())
            .forEach { out += it }
        return out
            .filter { isIpv4Literal(it) }
            .take(300)
            .toList()
    }

    private fun log(message: String, throwable: Throwable? = null) {
        RKhVpnLogStore.append(getApplication(), "SIMORGH-UI", message, throwable)
    }

    class Factory(private val app: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(c: Class<T>): T = SimorghPublicViewModel(app) as T
    }
    companion object {
        private const val SIMPLE_LATENCY_PROBE_INTERVAL_MS = 10_000L
        private const val SIMPLE_LATENCY_CACHE_MAX_AGE_MS = 7 * 24 * 60 * 60_000L
        private const val SIMPLE_LATENCY_CACHE_KEEP_MS = 7 * 24 * 60 * 60_000L
    }

}
