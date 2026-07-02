@file:Suppress("DEPRECATION")

package com.rkh.vpn.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.TrafficStats
import android.os.Build
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rkh.vpn.data.CountryCoordinates
import com.rkh.vpn.data.RKhVpnLogStore
import com.rkh.vpn.data.StormDnsRuntimeLog
import com.rkh.vpn.data.SimorghPublicState
import com.rkh.vpn.data.SimorghRoute
import com.rkh.vpn.core.NativeBinaryManager
import com.rkh.vpn.core.PingEngine
import com.rkh.vpn.core.XrayBinaryConfigBuilder
import com.rkh.vpn.core.XrayConfigBuilder
import com.rkh.vpn.data.ServerConfig
import com.rkh.vpn.data.SimpleConfigUiItem
import com.rkh.vpn.data.SimpleCustomProfileUi
import com.rkh.vpn.data.SubscriptionRepository
import com.rkh.vpn.service.SimorghPublicVpnService
import com.rkh.vpn.service.RkhVpnService
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.joinAll
import java.net.InetSocketAddress
import java.net.Socket
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.net.ServerSocket
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

class SimorghPublicViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs: android.content.SharedPreferences
        get() = publicStatePrefs()

    @Suppress("DEPRECATION")
    private fun publicStatePrefs(): android.content.SharedPreferences =
        getApplication<Application>()
            .getSharedPreferences("simorgh_public_state", Context.MODE_PRIVATE or Context.MODE_MULTI_PROCESS)
            .also { it.all }

    @Suppress("DEPRECATION")
    private fun coreStatePrefs(): android.content.SharedPreferences =
        getApplication<Application>()
            .getSharedPreferences("rkh_vpn_state", Context.MODE_PRIVATE or Context.MODE_MULTI_PROCESS)
            .also { it.all }
    private val simpleRepo = SubscriptionRepository()
    private val simplePing = PingEngine(app)
    private val simpleSubscriptionUrl: String get() = decodeHiddenSimpleSubscriptionUrl()
    private val simpleServerlessAssetKey = "rk_payload/p1.dat"
    private val simpleServerlessDisplayName = "ServerLess 🇮🇷"
    private val simpleServerlessDescription = "IRAN IPS"
    private val STORMDNS_STARTUP_RESOLVER_LIMIT = 10
    private val STORMDNS_STARTUP_PRECHECK_LIMIT = 0
    private val STORMDNS_STARTUP_PRECHECK_BATCH = 24
    private val STORMDNS_STARTUP_FALLBACK_LIMIT = 10
    private val ispOptions: List<String> by lazy { loadIspOptions() }
    private val sniOptions: List<String> by lazy { loadSniOptions() }
    private val _state = MutableStateFlow(loadState())
    val state: StateFlow<SimorghPublicState> = _state
    private var syncJob: Job? = null
    private var simpleBackgroundLatencyJob: Job? = null
    private var simpleHealthyScanJob: Job? = null
    private var stormDnsResolverScanJob: Job? = null
    private var stormDnsLogRefreshJob: Job? = null
    private var cfEnabledOverride: Boolean? = null
    @Volatile private var globalConnectSection: String? = null
    @Volatile private var globalConnectStartedAt: Long = 0L
    @Volatile private var lastMspStopRequestAt: Long = 0L
    @Volatile private var lastNipoStopRequestAt: Long = 0L
    @Volatile private var lastSimpleStopRequestAt: Long = 0L
    @Volatile private var lastFragmentStopRequestAt: Long = 0L
    private var stormDnsRuntimeHealthyCacheAt: Long = 0L
    private var stormDnsRuntimeHealthyCache: List<String> = emptyList()

    private var cachedSimpleConfigKey: String = ""
    private var cachedSimpleConfigs: List<ServerConfig> = emptyList()
    private var cachedSimpleItemsKey: String = ""
    private var cachedSimpleItems: List<SimpleConfigUiItem> = emptyList()
    private var cachedManualCountText: String = ""
    private var cachedManualCount: Int = 0
    private var cachedIspManualCountText: String = ""
    private var cachedIspManualCount: Int = 0
    private var cachedSavedCleanKey: String = ""
    private var cachedSavedCleanIps: List<String> = emptyList()
    private var cachedSavedPingsKey: String = ""
    private var cachedSavedPings: Map<String, Long> = emptyMap()
    private var cachedStormDnsResolversText: String = ""
    private var cachedStormDnsResolversList: List<String> = emptyList()

    init {
        ensureDefaults()
        cleanupAppRuntimeCache()
        syncManualIpsIntoCleanMemory()
        _state.value = loadState()
        log("Public ViewModel initialized • ISP options=${ispOptions.size} • SNI options=${sniOptions.size}")
        startSyncLoop()
    }

    fun connectAfterPermission() {
        startVpnModeAfterPermission()
    }

    fun startVpnModeAfterPermission() {
        val section = "msp-vpn"
        if (!beginGlobalConnect(section)) return
        viewModelScope.launch {
            val app = getApplication<Application>()
            try {
                ensureDefaults()
                syncManualIpsIntoCleanMemory()
                val snis = prefs.getStringSet("selectedSnis", setOf("chatgpt.com")) ?: setOf("chatgpt.com")
                val isp = selectedIspSet().joinToString(", ")
                val maxScan = prefs.getInt("maxScanIps", 33000).coerceIn(1, 33000)
                val manual = prefs.getBoolean("manualIpMode", false)
                val ispManual = prefs.getBoolean("ispManualRangeMode", false)
                val speed = prefs.getString("scanSpeed", "normal").orEmpty().ifBlank { "normal" }
                val manualCount = parseManualIpText(prefs.getString("manualIpsText", "").orEmpty()).size
                val ispManualCount = parseManualIpText(prefs.getString("ispManualRangeText", "").orEmpty()).size
                stopAllCoresBeforeConnect(app, section)
                markStarting("vpn", "VPN Mode scanning $isp with RKh-MSP...")
                val intent = Intent(app, SimorghPublicVpnService::class.java).setAction(SimorghPublicVpnService.ACTION_START)
                runCatching {
                    val component = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) app.startForegroundService(intent) else app.startService(intent)
                }.onFailure {
                    prefs.edit().putBoolean("connecting", false).putString("status", "VPN start failed: ${it.message ?: it.javaClass.simpleName}").putString("activeMode", "idle").apply()
                    log("Failed to start Public VPN foreground service", it)
                    _state.value = loadState()
                }
            } catch (e: Throwable) {
                prefs.edit().putBoolean("connecting", false).putString("status", "VPN start error: ${e.message ?: e.javaClass.simpleName}").putString("activeMode", "idle").apply()
                log("Safe VPN connect error", e)
                _state.value = loadState()
            } finally {
                delay(1_500L)
                endGlobalConnect(section)
            }
        }
    }

    fun startProxyMode() {
        val section = "msp-proxy"
        if (!beginGlobalConnect(section)) return
        viewModelScope.launch {
            val app = getApplication<Application>()
            try {
                ensureDefaults()
                syncManualIpsIntoCleanMemory()
                val snis = prefs.getStringSet("selectedSnis", setOf("chatgpt.com")) ?: setOf("chatgpt.com")
                val isp = selectedIspSet().joinToString(", ")
                val maxScan = prefs.getInt("maxScanIps", 33000).coerceIn(1, 33000)
                val manual = prefs.getBoolean("manualIpMode", false)
                val ispManual = prefs.getBoolean("ispManualRangeMode", false)
                val speed = prefs.getString("scanSpeed", "normal").orEmpty().ifBlank { "normal" }
                val manualCount = parseManualIpText(prefs.getString("manualIpsText", "").orEmpty()).size
                val ispManualCount = parseManualIpText(prefs.getString("ispManualRangeText", "").orEmpty()).size
                stopAllCoresBeforeConnect(app, section)
                markStarting("proxy", "Proxy Mode running on ${proxyAddressLabel()} • scanning $isp...")
                val intent = Intent(app, SimorghPublicVpnService::class.java).setAction(SimorghPublicVpnService.ACTION_START_PROXY)
                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) app.startForegroundService(intent) else app.startService(intent)
                }.onFailure {
                    prefs.edit().putBoolean("connecting", false).putString("status", "Proxy start failed: ${it.message ?: it.javaClass.simpleName}").putString("activeMode", "idle").apply()
                    log("Failed to start Public Proxy foreground service", it)
                    _state.value = loadState()
                }
            } catch (e: Throwable) {
                prefs.edit().putBoolean("connecting", false).putString("status", "Proxy start error: ${e.message ?: e.javaClass.simpleName}").putString("activeMode", "idle").apply()
                log("Safe Proxy connect error", e)
                _state.value = loadState()
            } finally {
                delay(1_500L)
                endGlobalConnect(section)
            }
        }
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

    @Synchronized
    private fun beginGlobalConnect(section: String): Boolean {
        val now = System.currentTimeMillis()
        val current = globalConnectSection
        if (current != null && now - globalConnectStartedAt < 900L) {
            return false
        }
        globalConnectSection = section
        globalConnectStartedAt = now
        return true
    }

    @Synchronized
    private fun endGlobalConnect(section: String) {
        if (globalConnectSection == section) {
            globalConnectSection = null
            globalConnectStartedAt = 0L
        }
    }

    private fun markOtherSectionsIdleForConnect(section: String) {
        val keepMsp = section == "msp" || section == "msp-vpn" || section == "msp-proxy" || section == "cf"
        val keepSimple = section.startsWith("simple")
        val keepFragment = section == "fragment"
        val keepNipo = section == "nipo"
        val keepStormDns = section == "stormdns"
        val edit = prefs.edit()
        if (!keepMsp) {
            edit.putBoolean("connecting", false)
                .putBoolean("connected", false)
        }
        if (!keepSimple) {
            edit.putBoolean("simpleConnecting", false)
                .putBoolean("simpleConnected", false)
        }
        if (!keepFragment) {
            edit.putBoolean("fragmentStartInProgress", false)
                .putBoolean("fragmentConnecting", false)
                .putBoolean("fragmentConnected", false)
        }
        if (!keepNipo) {
            edit.putBoolean("nipoConnecting", false)
                .putBoolean("nipoConnected", false)
        }
        if (!keepStormDns) {
            edit.putBoolean("stormDnsConnecting", false)
                .putBoolean("stormDnsConnected", false)
        }
        edit.apply()
    }

    private suspend fun stopAllCoresBeforeConnect(app: Application, section: String) {
        // Do not cancel the currently running Simple connect job from inside itself.
        if (!section.startsWith("simple")) {
            simpleHealthyScanJob?.cancel()
            simpleHealthyScanJob = null
        }
        simpleBackgroundLatencyJob?.cancel()
        simpleBackgroundLatencyJob = null
        stormDnsResolverScanJob?.cancel()
        stormDnsResolverScanJob = null
        stormDnsLogRefreshJob?.cancel()
        stormDnsLogRefreshJob = null
        withContext(Dispatchers.IO) {
            runCatching {
                app.startService(
                    Intent(app, SimorghPublicVpnService::class.java)
                        .setAction(SimorghPublicVpnService.ACTION_STOP)
                        .putExtra(SimorghPublicVpnService.EXTRA_PRE_CONNECT_RESET, true)
                )
            }.onFailure { log("Safe connect stop Public service failed before $section", it) }
            runCatching {
                val coreStopIntent = Intent(app, RkhVpnService::class.java)
                    .setAction(RkhVpnService.ACTION_STOP)
                    .putExtra(RkhVpnService.EXTRA_PRE_CONNECT_RESET, true)
                if (section.startsWith("simple")) {
                    coreStopIntent.putExtra(RkhVpnService.EXTRA_STOP_SOURCE, "simple")
                }
                app.startService(coreStopIntent)
            }.onFailure { log("Safe connect stop Core service failed before $section", it) }
            if (section.startsWith("simple") || section == "cf") {
                // Simple and CF both use the process-wide tun2proxy singleton in :vpncore.
                // Give the ordered pre-connect STOP handlers time to close the old fd-run
                // loop before the new foreground service is delivered.
                kotlinx.coroutines.delay(1_650L)
            }
        }
        markOtherSectionsIdleForConnect(section)
        delay(1_800L)
    }

    private fun clearStormDnsHealthyRuntimeCache() {
        // Clear only UI/runtime log memory. Do NOT delete StormDNS core runtime files.
        // Termux keeps its working directory between runs; deleting success/MTU/session
        // cache files here made SIMORGH behave differently from the same config in Termux.
        StormDnsRuntimeLog.clear(getApplication<Application>())
        prefs.edit()
            .putInt("stormDnsResolverValidCount", 0)
            .putInt("stormDnsResolverScanned", 0)
            .putString("stormDnsHealthyResolversText", "")
            .putString("stormDnsResolverScanStatus", "Healthy DNS cache cleared")
            .apply()
    }

    fun setMspStartError(message: String) {
        prefs.edit()
            .putBoolean("connecting", false)
            .putBoolean("connected", false)
            .putString("status", message)
            .putString("lastError", message)
            .putString("activeMode", "idle")
            .apply()
        _state.value = loadState()
        log("MSP start error shown safely: $message")
    }


    fun disconnect() {
        val now = System.currentTimeMillis()
        if (now - lastMspStopRequestAt < 900L) {
            log("MSP disconnect duplicate tap ignored safely")
            return
        }
        lastMspStopRequestAt = now
        simpleHealthyScanJob?.cancel()
        simpleHealthyScanJob = null
        simpleBackgroundLatencyJob?.cancel()
        simpleBackgroundLatencyJob = null
        stormDnsResolverScanJob?.cancel()
        stormDnsResolverScanJob = null
        stormDnsLogRefreshJob?.cancel()
        stormDnsLogRefreshJob = null
        clearStormDnsHealthyRuntimeCache()

        val app = getApplication<Application>()

        // مسیر درست بستن MSP: ارسال ACTION_STOP به خود VpnService تا stopPublic() اجرا شود و TUN بسته شود.
        prefs.edit()
            .putBoolean("connecting", false)
            .putString("status", "MSP disconnecting...")
            .apply()
        _state.value = loadState()

        val publicStopIntent = Intent(app, SimorghPublicVpnService::class.java)
            .setAction(SimorghPublicVpnService.ACTION_STOP)
        val publicStopDelivered = runCatching {
            app.startService(publicStopIntent)
            true
        }.getOrElse {
            log("Failed to send Public ACTION_STOP; trying fallback stopService", it)
            false
        }
        if (!publicStopDelivered) {
            runCatching { app.stopService(Intent(app, SimorghPublicVpnService::class.java)) }
                .onFailure { fallback -> log("Fallback Public stopService failed safely", fallback) }
        }

        // Core/Simple هم از مسیر ACTION_STOP خودش بسته شود، ولی هیچ خطایی اجازه خروج برنامه را نگیرد.
        val coreStopIntent = Intent(app, RkhVpnService::class.java)
            .setAction(RkhVpnService.ACTION_STOP)
        runCatching {
            app.startService(coreStopIntent)
        }.onFailure {
            log("Failed to send Core ACTION_STOP; trying fallback stopService", it)
            runCatching { app.stopService(Intent(app, RkhVpnService::class.java)) }
                .onFailure { fallback -> log("Fallback Core stopService failed safely", fallback) }
        }

        viewModelScope.launch {
            kotlinx.coroutines.delay(450L)
            prefs.edit()
                .putBoolean("connecting", false)
                .putBoolean("connected", false)
                .putBoolean("simpleConnecting", false)
                .putBoolean("simpleConnected", false)
                .putBoolean("nipoConnecting", false)
                .putBoolean("nipoConnected", false)
                .putBoolean("stormDnsConnecting", false)
                .putBoolean("stormDnsConnected", false)
                .putString("status", "Disconnected")
                .putString("activeMode", "idle")
                .putLong("startedAt", 0L)
                .putLong("downloadKbps", 0L)
                .putLong("uploadKbps", 0L)
                .apply()
            _state.value = loadState()
        }
    }


    fun setSelectedIsp(isp: String) {
        val value = isp.trim().ifBlank { defaultIsp() }
        val current = linkedSetOf<String>().apply { selectedIspSet().forEach { add(it) } }
        val existing = current.firstOrNull { it.equals(value, ignoreCase = true) }
        if (existing != null) {
            current.remove(existing)
        } else {
            current.add(value)
        }
        if (current.isEmpty()) current.add(defaultIsp())
        val stable = current.toList()
        val primary = stable.firstOrNull() ?: defaultIsp()
        prefs.edit()
            .putString("selectedIsp", primary)
            .putString("selectedIspsCsv", stable.joinToString("\n"))
            .putStringSet("selectedIsps", stable.toSet())
            .apply()
        log("Selected ISPs changed to ${stable.joinToString(", ")}")
        _state.value = loadState()
    }

    fun toggleSni(sni: String) {
        val clean = sni.trim().lowercase(Locale.US)
        if (clean.isBlank()) return
        val current = selectedSniList().toMutableList()
        val existingIndex = current.indexOfFirst { it.equals(clean, ignoreCase = true) }
        if (existingIndex >= 0) {
            current.removeAt(existingIndex)
        } else {
            // New selections should immediately jump to the top of the Settings/SNI list.
            current.add(0, clean)
        }
        if (current.isEmpty()) current.add("chatgpt.com")
        val stable = current.distinctBy { it.lowercase(Locale.US) }
        prefs.edit()
            .putString("selectedSnisCsv", stable.joinToString("\n"))
            .putStringSet("selectedSnis", stable.toSet())
            .apply()
        log("Selected SNI changed to ${stable.joinToString(",")}")
        _state.value = loadState()
    }
    fun setRunMode(mode: String) {
        val normalized = if (mode.equals("vpn", ignoreCase = true)) "vpn" else "proxy"
        prefs.edit().putString("selectedRunMode", normalized).apply()
        log("Selected run mode changed to ${normalized.uppercase(Locale.US)}")
        _state.value = loadState()
    }

    private fun normalizeTunnelSection(section: String): String = when (section.lowercase(Locale.US)) {
        "msp", "advance" -> "msp"
        "nipo", "nipovpn" -> "nipo"
        "stormdns" -> "stormdns"
        else -> "simple"
    }

    private fun tunnelModeKey(section: String) = "tunnelAppMode_${normalizeTunnelSection(section)}"
    private fun normalizeTunnelMode(mode: String): String = when (mode.lowercase(Locale.US)) {
        "exclude" -> "exclude"
        "only" -> "only"
        else -> "all"
    }
    private fun tunnelPackagesKey(section: String) = "tunnelAppPackages_${normalizeTunnelSection(section)}"
    private fun tunnelPackagesKey(section: String, mode: String) = "tunnelAppPackages_${normalizeTunnelSection(section)}_${normalizeTunnelMode(mode)}"

    fun setTunnelAppMode(section: String, mode: String) {
        val safe = normalizeTunnelMode(mode)
        val normalizedSection = normalizeTunnelSection(section)
        prefs.edit().putString(tunnelModeKey(normalizedSection), safe).apply()
        log("Tunnel app mode changed for $normalizedSection to $safe")
        _state.value = loadState()
    }

    fun toggleTunnelAppPackage(section: String, mode: String, packageName: String) {
        val clean = packageName.trim()
        val safeMode = normalizeTunnelMode(mode)
        if (clean.isBlank() || safeMode == "all") return
        val normalizedSection = normalizeTunnelSection(section)
        val key = tunnelPackagesKey(normalizedSection, safeMode)
        val current = (prefs.getStringSet(key, emptySet<String>()) ?: emptySet<String>()).toMutableSet()
        if (clean in current) current.remove(clean) else current.add(clean)
        prefs.edit().putStringSet(key, current).apply()
        log("Tunnel app package toggled for $normalizedSection/$safeMode: $clean • selected=${current.size}")
        _state.value = loadState()
    }

    fun clearTunnelAppPackages(section: String, mode: String) {
        val safeMode = normalizeTunnelMode(mode)
        val normalizedSection = normalizeTunnelSection(section)
        prefs.edit().putStringSet(tunnelPackagesKey(normalizedSection, safeMode), emptySet<String>()).apply()
        log("Tunnel app package list cleared for $normalizedSection/$safeMode")
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
        prefs.edit()
            .putString("routeStrategy", normalized)
            .putBoolean("manualRouteLock", if (normalized == "default") prefs.getBoolean("manualRouteLock", false) else false)
            .apply()
        log("Routing strategy changed to $normalized${if (normalized == "default") "" else " • manual route lock cleared"}")
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
        cfEnabledOverride = enabled
        prefs.edit()
            .putBoolean("cfEnabled", enabled)
            .putString("cfStatus", if (enabled) "CF Config enabled" else "CF Config disabled")
            .commit()
        log("CF Config ${if (enabled) "enabled" else "disabled"}")
        _state.value = loadState().copy(cfEnabled = enabled)
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
        if (!(cfEnabledOverride ?: prefs.getBoolean("cfEnabled", false))) { log("CF Ping ignored: CF Config is OFF"); return@launch }
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
        if (!(cfEnabledOverride ?: prefs.getBoolean("cfEnabled", false))) { log("CF ping ignored: CF Config is OFF"); return@launch }
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
        if (!(cfEnabledOverride ?: prefs.getBoolean("cfEnabled", false))) { prefs.edit().putString("cfStatus", "Turn CF Config ON first").apply(); _state.value = loadState(); return false }
        val cleanIp = ip.trim()
        val vless = prefs.getString("cfVlessConfig", "").orEmpty().trim()
        val parsed = parseFullVlessForCf(vless)
        val hasAddress = runCatching { URI(vless).host.orEmpty().isNotBlank() }.getOrDefault(false)
        if (!isIpv4Literal(cleanIp) || parsed == null || !hasAddress) {
            prefs.edit().putString("cfStatus", "Invalid CF IP or VLESS config").apply()
            _state.value = loadState()
            log("CF connect ignored: invalid IP or VLESS config")
            return false
        }
        prefs.edit()
            .putString("cfConnectingIp", cleanIp)
            .putString("pendingCfIp", cleanIp)
            .putString("pendingCfVless", vless)
            .putString("cfStatus", "Ready to connect CF VLESS via $cleanIp")
            .apply()
        _state.value = loadState()
        log("CF Config connect prepared: $cleanIp")
        return true
    }

    fun connectCfAfterPermission() {
        val app = getApplication<Application>()
        val cleanIp = prefs.getString("pendingCfIp", "").orEmpty().trim()
        val vless = prefs.getString("pendingCfVless", "").orEmpty().trim()
        val parsed = parseFullVlessForCf(vless)
        val hasAddress = runCatching { URI(vless).host.orEmpty().isNotBlank() }.getOrDefault(false)
        if (!isIpv4Literal(cleanIp) || parsed == null || !hasAddress) {
            prefs.edit()
                .putBoolean("connecting", false)
                .remove("cfConnectingIp")
                .putString("cfStatus", "Invalid CF IP or VLESS config")
                .putString("lastError", "Invalid CF IP or VLESS config")
                .putString("activeMode", "idle")
                .commit()
            _state.value = loadState()
            log("CF connect after permission cancelled: invalid pending IP/config")
            return
        }

        viewModelScope.launch {
            val wasAnyCoreActive = prefs.getBoolean("connected", false) ||
                prefs.getBoolean("connecting", false) ||
                prefs.getBoolean("simpleConnected", false) ||
                prefs.getBoolean("simpleConnecting", false) ||
                prefs.getBoolean("fragmentConnected", false) ||
                prefs.getBoolean("fragmentConnecting", false) ||
                prefs.getBoolean("nipoConnected", false) ||
                prefs.getBoolean("nipoConnecting", false) ||
                prefs.getBoolean("stormDnsConnected", false) ||
                prefs.getBoolean("stormDnsConnecting", false) ||
                prefs.getString("activeMode", "").orEmpty() !in setOf("", "idle")
            if (wasAnyCoreActive) {
                prefs.edit()
                    .putString("cfStatus", "Preparing CF VLESS switch • stopping previous tunnel...")
                    .putString("status", "Preparing CF VLESS switch")
                    .commit()
                _state.value = loadState()
            }
            // CF Config shares the same :vpncore process with Simple/MSP. Stop both
            // services in ordered pre-connect mode before delivering ACTION_CF_CONNECT,
            // so the first Connect tap does not race a stale tun2proxy native loop.
            stopAllCoresBeforeConnect(app, "cf")

            val intent = Intent(app, SimorghPublicVpnService::class.java).apply {
                action = SimorghPublicVpnService.ACTION_CF_CONNECT
                putExtra(SimorghPublicVpnService.EXTRA_CF_IP, cleanIp)
                putExtra(SimorghPublicVpnService.EXTRA_CF_VLESS, vless)
            }
            var serviceStarted = true
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) app.startForegroundService(intent) else app.startService(intent)
            }.onFailure {
                serviceStarted = false
                prefs.edit()
                    .putBoolean("connecting", false)
                    .remove("cfConnectingIp")
                    .putString("cfStatus", "CF service start failed: ${it.message ?: it.javaClass.simpleName}")
                    .putString("lastError", it.message ?: it.javaClass.simpleName)
                    .putString("activeMode", "idle")
                    .commit()
                log("Failed to start CF VLESS foreground service", it)
            }
            if (!serviceStarted) {
                _state.value = loadState()
                return@launch
            }
            prefs.edit()
                .putString("cfStatus", "Connecting CF VLESS via $cleanIp...")
                .putBoolean("connecting", true)
                .putBoolean("connected", false)
                .putString("activeMode", "vpn")
                .commit()
            _state.value = loadState()
            log("CF Config connect requested after VPN permission: $cleanIp")
        }
    }

    fun connectCfIp(ip: String) { prepareCfConnectIp(ip) }

    fun updateSimpleSubscription() = viewModelScope.launch {
        updateSimpleSubscriptionInternal(showReady = true)
    }

    fun clearSimpleCache() {
        simpleHealthyScanJob?.cancel()
        simpleHealthyScanJob = null
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

    fun pingAllSimpleConfigs() {
        simpleHealthyScanJob?.cancel()
        simpleHealthyScanJob = viewModelScope.launch {
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
            .putString("simpleStatus", "Ping All started • ${configs.size} configs • real Xray test • $SIMPLE_FAST_PROBE_PARALLELISM parallel")
            .putString("status", "Real Xray testing")
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

    private fun stormDnsAssetText(assetName: String, fallback: String): String {
        return runCatching {
            getApplication<Application>().assets.open("stormdns/$assetName").bufferedReader().use { it.readText() }
        }.getOrDefault(fallback)
    }

    private fun defaultStormDnsClientConfig(): String = stormDnsAssetText("client_config.toml", """
        DOMAINS = ["v.domain.com"]
        DATA_ENCRYPTION_METHOD = 1
        ENCRYPTION_KEY = "change-me"
        PROTOCOL_TYPE = "SOCKS5"
        LISTEN_IP = "127.0.0.1"
        LISTEN_PORT = 18000
        LOCAL_DNS_ENABLED = false
        LOCAL_DNS_IP = "127.0.0.1"
        LOCAL_DNS_PORT = 5353
    """.trimIndent())
    private fun defaultStormDnsResolvers(): String =
        stormDnsAssetText("client_resolvers.txt", "1.1.1.1:53\n8.8.8.8:53")
            .replace("﻿", "")
            .trimEnd()

    private fun defaultStormDnsServerConfig(): String = """
        # SIMORGH StormDNS server profile
        [server]
        listen = "0.0.0.0:53"
        protocol = "udp"
        mtu = 1232

        [dns]
        upstream = ["1.1.1.1:53", "8.8.8.8:53"]

        [runtime]
        log_level = "info"
    """.trimIndent()

    private fun extractStormDnsTomlInt(config: String, key: String): Int? {
        return Regex("""(?m)^\s*""" + Regex.escape(key) + """\s*=\s*(\d+)""").find(config)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun syncStormDnsPortsFromClientConfig(config: String, editor: android.content.SharedPreferences.Editor) {
        extractStormDnsTomlInt(config, "LISTEN_PORT")?.let { editor.putInt("stormDnsSocksPort", it.coerceIn(1024, 65535)) }
        extractStormDnsTomlInt(config, "LOCAL_DNS_PORT")?.let { port ->
            // Android app processes cannot bind privileged ports such as 53.
            // Keep StormDNS local DNS on an unprivileged localhost port and let Xray bridge route DNS to it.
            val safeDnsPort = if (port < 1024) 5353 else port.coerceIn(1024, 65535)
            editor.putInt("stormDnsLocalDnsPort", safeDnsPort)
        }
    }

    private fun normalizeStormDnsResolvers(input: String): List<String> {
        // UI/count-only parser. Runtime uses the raw resolver file so SIMORGH does
        // not change the working Termux/Windows resolver ordering or formatting.
        return input.replace("﻿", "")
            .lineSequence()
            .map { raw ->
                raw.trim()
                    .substringBefore("#")
                    .substringBefore("//")
                    .trim()
                    .trim('*', ',', ';', ' ', '	')
            }
            .filter { it.isNotBlank() }
            .map { endpoint -> if (endpoint.contains(':')) endpoint else "$endpoint:53" }
            .distinct()
            .toList()
    }

    private fun rawStormDnsResolversForRuntime(input: String): String {
        // Exact Termux parity: do not split, de-duplicate, reorder, or append :53
        // to the resolver file passed to StormDNS. The Go core already knows
        // how to parse client_resolvers.txt exactly like Termux/Windows.
        val clean = input.replace("﻿", "").trimEnd()
        return if (clean.isBlank()) "" else clean + "\n"
    }

    private fun tomlEscape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun stormDnsProfileObject(name: String): JSONObject {
        return JSONObject()
            .put("name", name.ifBlank { "Default" })
            .put("runMode", prefs.getString("stormDnsRunMode", "proxy").orEmpty().ifBlank { "proxy" })
            .put("domain", prefs.getString("stormDnsDomain", "").orEmpty())
            .put("key", prefs.getString("stormDnsKey", "").orEmpty())
            .put("clientConfig", prefs.getString("stormDnsClientConfig", defaultStormDnsClientConfig()).orEmpty())
            .put("serverConfig", prefs.getString("stormDnsServerConfig", defaultStormDnsServerConfig()).orEmpty())
            .put("socksPort", prefs.getInt("stormDnsSocksPort", 18000))
            .put("dnsPort", prefs.getInt("stormDnsLocalDnsPort", 5353))
    }

    private fun readJsonArrayPref(key: String): JSONArray {
        return runCatching { JSONArray(prefs.getString(key, "[]")) }.getOrDefault(JSONArray())
    }

    private fun stormDnsProfilesArray(): JSONArray {
        val arr = readJsonArrayPref("stormDnsProfilesJson")
        if (arr.length() == 0) arr.put(stormDnsProfileObject("Default"))
        return arr
    }

    private fun stormDnsProfileNames(): List<String> {
        val arr = stormDnsProfilesArray()
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() } }.distinct()
    }

    private fun putStormDnsProfilesArray(arr: JSONArray) {
        prefs.edit().putString("stormDnsProfilesJson", arr.toString()).apply()
    }

    fun setStormDnsRunMode(mode: String) {
        val safe = if (mode == "vpn") "vpn" else "proxy"
        prefs.edit()
            .putString("stormDnsRunMode", safe)
            .putString("stormDnsStatus", "MasterDNS ${safe.uppercase(Locale.US)} mode selected")
            .apply()
        _state.value = loadState()
    }

    fun setStormDnsField(field: String, value: String) {
        val editor = prefs.edit()
        when (field) {
            "profileName" -> editor.putString("stormDnsProfileName", value)
            "resolverProfileName" -> editor.putString("stormDnsResolverProfileName", value)
            "domain" -> editor.putString("stormDnsDomain", value.trim())
            "key" -> editor.putString("stormDnsKey", value.trim())
            "resolvers" -> {
                editor.putString("stormDnsResolvers", value)
                editor.putString("stormDnsHealthyResolversText", "")
            }
            "clientConfig" -> {
                editor.putString("stormDnsClientConfig", value)
                syncStormDnsPortsFromClientConfig(value, editor)
            }
            "serverConfig" -> editor.putString("stormDnsServerConfig", value)
            "socksPort" -> editor.putInt("stormDnsSocksPort", value.toIntOrNull()?.coerceIn(1024, 65535) ?: 18000)
            "dnsPort" -> editor.putInt("stormDnsLocalDnsPort", value.toIntOrNull()?.coerceIn(1024, 65535) ?: 5353)
        }
        editor.apply()
        if (field == "resolvers") startStormDnsResolverValidation(value) else _state.value = loadState()
    }

    fun importStormDnsResolversText(text: String) {
        val clean = rawStormDnsResolversForRuntime(text)
        val count = normalizeStormDnsResolvers(clean).size
        if (count == 0) {
            prefs.edit().putString("stormDnsStatus", "Import TXT ignored: no resolver found").apply()
            _state.value = loadState()
            return
        }
        val existing = resolverProfileNames().toSet()
        val typed = prefs.getString("stormDnsResolverProfileName", "").orEmpty().trim()
        val base = typed.takeIf { it.isNotBlank() && it !in existing } ?: "Imported Resolvers"
        var name = base
        if (name in existing) {
            var i = existing.size + 1
            while ("$base $i" in existing) i++
            name = "$base $i"
        }
        val arr = resolverProfilesArray()
        arr.put(JSONObject().put("name", name).put("resolvers", clean))
        prefs.edit()
            .putString("stormDnsResolverProfilesJson", arr.toString())
            .putString("stormDnsSelectedResolverProfile", name)
            .putString("stormDnsResolverProfileName", name)
            .putString("stormDnsResolvers", clean)
            .putString("stormDnsHealthyResolversText", "")
            .putString("stormDnsStatus", "Imported TXT as new resolver profile: $name • $count resolver(s)")
            .apply()
        startStormDnsResolverValidation(clean)
    }

    fun saveCurrentStormDnsProfile() {
        val selected = prefs.getString("stormDnsSelectedProfile", "Default").orEmpty().ifBlank { "Default" }
        val name = prefs.getString("stormDnsProfileName", "").orEmpty().trim().ifBlank { selected }
        val arr = stormDnsProfilesArray()
        val out = JSONArray()
        var replaced = false
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val objName = obj.optString("name")
            when {
                objName == selected -> { out.put(stormDnsProfileObject(name)); replaced = true }
                objName == name -> { /* remove duplicate target name while renaming selected profile */ }
                else -> out.put(obj)
            }
        }
        if (!replaced) out.put(stormDnsProfileObject(name))
        prefs.edit()
            .putString("stormDnsProfilesJson", out.toString())
            .putString("stormDnsSelectedProfile", name)
            .putString("stormDnsProfileName", name)
            .putString("stormDnsStatus", "MasterDNS config profile saved: $name")
            .apply()
        _state.value = loadState()
    }

    fun addStormDnsProfile() {
        val existing = stormDnsProfileNames().toSet()
        val typed = prefs.getString("stormDnsProfileName", "").orEmpty().trim()
        var name = typed.ifBlank { "Server ${existing.size + 1}" }
        if (name in existing) {
            var i = existing.size + 1
            while ("Server $i" in existing) i++
            name = "Server $i"
        }
        prefs.edit().putString("stormDnsProfileName", name).apply()
        saveCurrentStormDnsProfile()
    }

    fun selectStormDnsProfile(name: String) {
        val arr = stormDnsProfilesArray()
        val obj = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.firstOrNull { it.optString("name") == name } ?: return
        val clientConfig = obj.optString("clientConfig", defaultStormDnsClientConfig()).ifBlank { defaultStormDnsClientConfig() }
        val editor = prefs.edit()
            .putString("stormDnsSelectedProfile", name)
            .putString("stormDnsProfileName", name)
            .putString("stormDnsRunMode", obj.optString("runMode", "proxy").ifBlank { "proxy" })
            .putString("stormDnsDomain", obj.optString("domain", ""))
            .putString("stormDnsKey", obj.optString("key", ""))
            .putString("stormDnsClientConfig", clientConfig)
            .putString("stormDnsServerConfig", obj.optString("serverConfig", defaultStormDnsServerConfig()).ifBlank { defaultStormDnsServerConfig() })
            .putString("stormDnsStatus", "MasterDNS config profile loaded: $name")
        syncStormDnsPortsFromClientConfig(clientConfig, editor)
        editor.putInt("stormDnsSocksPort", obj.optInt("socksPort", prefs.getInt("stormDnsSocksPort", 18000)).coerceIn(1024, 65535))
            .putInt("stormDnsLocalDnsPort", obj.optInt("dnsPort", prefs.getInt("stormDnsLocalDnsPort", 5353)).coerceIn(1, 65535))
            .apply()
        _state.value = loadState()
    }

    fun deleteSelectedStormDnsProfile() {
        val selected = prefs.getString("stormDnsSelectedProfile", "Default").orEmpty().ifBlank { "Default" }
        val arr = stormDnsProfilesArray()
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optString("name") != selected) out.put(obj)
        }
        if (out.length() == 0) out.put(stormDnsProfileObject("Default"))
        val next = out.optJSONObject(0)?.optString("name") ?: "Default"
        prefs.edit().putString("stormDnsProfilesJson", out.toString()).apply()
        selectStormDnsProfile(next)
    }

    private fun resolverProfilesArray(): JSONArray {
        val arr = readJsonArrayPref("stormDnsResolverProfilesJson")
        if (arr.length() == 0) arr.put(JSONObject().put("name", "Default Resolvers").put("resolvers", prefs.getString("stormDnsResolvers", defaultStormDnsResolvers()).orEmpty()))
        return arr
    }

    private fun resolverProfileNames(): List<String> {
        val arr = resolverProfilesArray()
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() } }.distinct()
    }

    fun addStormDnsResolverProfile() {
        val existing = resolverProfileNames().toSet()
        val typed = prefs.getString("stormDnsResolverProfileName", "").orEmpty().trim()
        val base = typed.ifBlank { "Resolvers" }
        var name = if (base in existing) "Resolvers ${existing.size + 1}" else base
        if (name in existing) {
            var i = existing.size + 1
            while ("Resolvers $i" in existing) i++
            name = "Resolvers $i"
        }
        val arr = resolverProfilesArray()
        arr.put(JSONObject().put("name", name).put("resolvers", ""))
        prefs.edit()
            .putString("stormDnsResolverProfilesJson", arr.toString())
            .putString("stormDnsSelectedResolverProfile", name)
            .putString("stormDnsResolverProfileName", name)
            .putString("stormDnsResolvers", "")
            .putString("stormDnsHealthyResolversText", "")
            .putString("stormDnsStatus", "New empty resolver profile created: $name")
            .apply()
        _state.value = loadState()
    }

    fun saveCurrentStormDnsResolverProfile() {
        val name = prefs.getString("stormDnsResolverProfileName", "").orEmpty().trim().ifBlank {
            prefs.getString("stormDnsSelectedResolverProfile", "Default Resolvers").orEmpty().ifBlank { "Default Resolvers" }
        }
        val resolvers = prefs.getString("stormDnsResolvers", "1.1.1.1:53\n8.8.8.8:53").orEmpty()
        val arr = resolverProfilesArray()
        val out = JSONArray()
        var replaced = false
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optString("name") == name) { out.put(JSONObject().put("name", name).put("resolvers", resolvers)); replaced = true } else out.put(obj)
        }
        if (!replaced) out.put(JSONObject().put("name", name).put("resolvers", resolvers))
        prefs.edit()
            .putString("stormDnsResolverProfilesJson", out.toString())
            .putString("stormDnsSelectedResolverProfile", name)
            .putString("stormDnsResolverProfileName", name)
            .putString("stormDnsHealthyResolversText", "")
            .putString("stormDnsStatus", "Resolver profile saved: $name")
            .apply()
        _state.value = loadState()
    }

    fun selectStormDnsResolverProfile(name: String) {
        val arr = resolverProfilesArray()
        val obj = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.firstOrNull { it.optString("name") == name } ?: return
        val resolvers = obj.optString("resolvers", "1.1.1.1:53\n8.8.8.8:53")
        prefs.edit()
            .putString("stormDnsSelectedResolverProfile", name)
            .putString("stormDnsResolverProfileName", name)
            .putString("stormDnsResolvers", resolvers)
            .putString("stormDnsHealthyResolversText", "")
            .putString("stormDnsStatus", "Resolver profile loaded: $name")
            .apply()
        startStormDnsResolverValidation(resolvers)
        _state.value = loadState()
    }

    fun deleteSelectedStormDnsResolverProfile() {
        val selected = prefs.getString("stormDnsSelectedResolverProfile", "Default Resolvers").orEmpty().ifBlank { "Default Resolvers" }
        val arr = resolverProfilesArray()
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optString("name") != selected) out.put(obj)
        }
        if (out.length() == 0) out.put(JSONObject().put("name", "Default Resolvers").put("resolvers", "1.1.1.1:53\n8.8.8.8:53"))
        val next = out.optJSONObject(0)?.optString("name") ?: "Default Resolvers"
        prefs.edit().putString("stormDnsResolverProfilesJson", out.toString()).apply()
        selectStormDnsResolverProfile(next)
    }

    fun refreshStormDnsLogs() {
        _state.value = loadState()
    }

    private fun parseStormDnsResolverEndpoint(value: String): Pair<String, Int>? {
        val raw = value.trim()
        if (raw.isBlank() || raw.startsWith("#")) return null
        val noCidr = raw.substringBefore('/').trim()
        return when {
            noCidr.startsWith("[") && noCidr.contains("]") -> {
                val host = noCidr.substringAfter("[").substringBefore("]")
                val port = noCidr.substringAfter("]:", "53").toIntOrNull()?.coerceIn(1, 65535) ?: 53
                host to port
            }
            noCidr.count { it == ':' } == 1 && noCidr.substringAfterLast(':').toIntOrNull() != null -> {
                noCidr.substringBeforeLast(':') to noCidr.substringAfterLast(':').toInt().coerceIn(1, 65535)
            }
            else -> noCidr to 53
        }
    }

    private fun stormDnsProbePacket(): ByteArray {
        return byteArrayOf(
            0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x07, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(), 'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(),
            0x03, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
            0x00, 0x00, 0x01, 0x00, 0x01
        )
    }

    private suspend fun isStormDnsResolverHealthy(value: String): Boolean = withContext(Dispatchers.IO) {
        val endpoint = parseStormDnsResolverEndpoint(value) ?: return@withContext false
        withTimeoutOrNull(1200L) {
            runCatching {
                val query = stormDnsProbePacket()
                DatagramSocket().use { socket ->
                    socket.soTimeout = 1000
                    val address = InetAddress.getByName(endpoint.first)
                    socket.send(DatagramPacket(query, query.size, address, endpoint.second))
                    val buffer = ByteArray(512)
                    val response = DatagramPacket(buffer, buffer.size)
                    socket.receive(response)
                    response.length >= 12 && buffer[0] == query[0] && buffer[1] == query[1]
                }
            }.getOrDefault(false)
        } ?: false
    }


    private fun cachedHealthyStormDnsResolversForStartup(candidates: List<String>): List<String> {
        val allowed = candidates.toSet()
        return prefs.getString("stormDnsHealthyResolversText", "").orEmpty()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && it in allowed }
            .distinct()
            .take(STORMDNS_STARTUP_RESOLVER_LIMIT)
            .toList()
    }

    private suspend fun selectStormDnsStartupResolvers(candidates: List<String>): Pair<List<String>, List<String>> = coroutineScope {
        val unique = candidates.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (unique.isEmpty()) return@coroutineScope emptyList<String>() to emptyList()

        val healthy = linkedSetOf<String>()
        var scanned = 0

        prefs.edit()
            .putInt("stormDnsResolverTotal", unique.size)
            .putInt("stormDnsResolverScanned", 0)
            .putInt("stormDnsResolverValidCount", 0)
            .putBoolean("stormDnsResolverScanning", true)
            .putString("stormDnsHealthyResolversText", "")
            .putString("stormDnsResolverScanStatus", "Full DNS scan before VPN 0/${unique.size} • healthy=0")
            .putString("stormDnsStatus", "MasterDNS full DNS scan before starting core...")
            .remove("stormDnsPendingResolversText")
            .apply()
        _state.value = loadState()

        for (batch in unique.chunked(STORMDNS_STARTUP_PRECHECK_BATCH)) {
            val batchHealthy = batch.map { resolver ->
                async(Dispatchers.IO) { if (isStormDnsResolverHealthy(resolver)) resolver else null }
            }.awaitAll().filterNotNull()
            healthy.addAll(batchHealthy)
            scanned += batch.size
            prefs.edit()
                .putInt("stormDnsResolverTotal", unique.size)
                .putInt("stormDnsResolverScanned", scanned)
                .putInt("stormDnsResolverValidCount", healthy.size)
                .putBoolean("stormDnsResolverScanning", scanned < unique.size)
                .putString("stormDnsHealthyResolversText", healthy.joinToString("\n"))
                .putString("stormDnsResolverScanStatus", "Full DNS scan $scanned/${unique.size} • healthy=${healthy.size}")
                .putString("stormDnsStatus", "MasterDNS scanning all DNS before core start • $scanned/${unique.size}")
                .apply()
            _state.value = loadState()
            delay(80L)
        }

        val healthyList = healthy.toList()
        val selected = when {
            healthyList.isNotEmpty() -> healthyList
            else -> unique.take(STORMDNS_STARTUP_FALLBACK_LIMIT)
        }
        prefs.edit()
            .putInt("stormDnsResolverTotal", unique.size)
            .putInt("stormDnsResolverScanned", unique.size)
            .putInt("stormDnsResolverValidCount", healthyList.size)
            .putBoolean("stormDnsResolverScanning", false)
            .putString("stormDnsHealthyResolversText", healthyList.joinToString("\n"))
            .putString(
                "stormDnsResolverScanStatus",
                if (healthyList.isNotEmpty()) "Full DNS scan finished • ${healthyList.size}/${unique.size} healthy • starting MasterDNS" else "Full DNS scan finished • no healthy DNS • using fallback startup set"
            )
            .putString("stormDnsStatus", if (healthyList.isNotEmpty()) "Starting MasterDNS after full DNS scan with ${selected.size} DNS" else "Starting MasterDNS after full scan with fallback DNS")
            .remove("stormDnsPendingResolversText")
            .apply()
        _state.value = loadState()
        selected to healthyList
    }

    private fun startStormDnsBackgroundResolverScan(app: Application, candidates: List<String>, seedHealthy: List<String>) {
        stormDnsResolverScanJob?.cancel()
        val unique = candidates.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (unique.isEmpty()) return
        stormDnsResolverScanJob = viewModelScope.launch(Dispatchers.IO) {
            val healthy = linkedSetOf<String>()
            seedHealthy.forEach { resolver -> if (resolver.isNotBlank()) healthy.add(resolver) }
            var scanned = 0
            updateStormDnsResolverScanState(unique.size, scanned, healthy.toList(), true)
            delay(1_500L)
            if (healthy.isNotEmpty()) updateRunningStormDnsResolvers(app, healthy.joinToString("\n"))
            for (batch in unique.chunked(STORMDNS_STARTUP_PRECHECK_BATCH)) {
                if (!isActive) break
                val before = healthy.size
                val batchHealthy = batch.map { resolver ->
                    async(Dispatchers.IO) {
                        if (healthy.any { it.equals(resolver, ignoreCase = true) }) resolver
                        else if (isStormDnsResolverHealthy(resolver)) resolver
                        else null
                    }
                }.awaitAll().filterNotNull()
                healthy.addAll(batchHealthy)
                scanned += batch.size
                val scanning = scanned < unique.size && isActive
                updateStormDnsResolverScanState(unique.size, scanned, healthy.toList(), scanning)
                if (healthy.size != before && healthy.isNotEmpty()) {
                    updateRunningStormDnsResolvers(app, healthy.joinToString("\n"))
                }
                delay(120L)
            }
            updateStormDnsResolverScanState(unique.size, unique.size, healthy.toList(), false)
            if (healthy.isNotEmpty()) {
                val finalResolvers = healthy.joinToString("\n")
                updateRunningStormDnsResolvers(app, finalResolvers)
                flushStormDnsResolversWhenConnected(app, finalResolvers)
            }
        }
    }

    private fun updateStormDnsResolverScanState(total: Int, scanned: Int, healthy: List<String>, scanning: Boolean) {
        val status = if (scanning) {
            "Scanning DNS $scanned/$total • healthy ${healthy.size}"
        } else {
            "DNS scan finished • $scanned/$total scanned • healthy ${healthy.size}"
        }
        prefs.edit()
            .putInt("stormDnsResolverTotal", total)
            .putInt("stormDnsResolverScanned", scanned)
            .putInt("stormDnsResolverValidCount", healthy.size)
            .putBoolean("stormDnsResolverScanning", scanning)
            .putString("stormDnsHealthyResolversText", healthy.joinToString("\n"))
            .putString("stormDnsResolverScanStatus", status)
            .apply()
        _state.value = loadState()
    }

    private fun startStormDnsResolverValidation(text: String) {
        stormDnsResolverScanJob?.cancel()
        val candidates = normalizeStormDnsResolvers(text)
        prefs.edit()
            .putInt("stormDnsResolverTotal", candidates.size)
            .putInt("stormDnsResolverScanned", 0)
            .putInt("stormDnsResolverValidCount", 0)
            .putBoolean("stormDnsResolverScanning", false)
            .putString("stormDnsHealthyResolversText", "")
            .putString("stormDnsResolverScanStatus", "Healthy DNS will be detected from StormDNS client logs after Connect")
            .apply()
        _state.value = loadState()
    }

    private fun startStormDnsService(app: Application, mode: String, clientConfig: String, resolvers: String) {
        val editor = prefs.edit()
        syncStormDnsPortsFromClientConfig(clientConfig, editor)
        // Commit ports synchronously so RkhVpnService never reads an old SOCKS port
        // while StormDNS core is configured to listen on a new one such as 18000.
        editor.commit()
        val intent = Intent(app, RkhVpnService::class.java)
            .setAction(RkhVpnService.ACTION_START_STORMDNS)
            .putExtra(RkhVpnService.EXTRA_STORMDNS_CONFIG, clientConfig)
            .putExtra(RkhVpnService.EXTRA_STORMDNS_RESOLVERS, resolvers)
            .putExtra(RkhVpnService.EXTRA_STORMDNS_MODE, mode)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) app.startForegroundService(intent) else app.startService(intent)
        }.onFailure { e ->
            prefs.edit()
                .putBoolean("stormDnsConnecting", false)
                .putBoolean("stormDnsConnected", false)
                .putString("stormDnsStatus", "MasterDNS start failed: ${e.message ?: e.javaClass.simpleName}")
                .putString("status", "MasterDNS start failed")
                .putString("activeMode", "idle")
                .apply()
            log("StormDNS service start failed", e)
        }
    }

    private fun updateRunningStormDnsResolvers(app: Application, resolvers: String) {
        val clean = resolvers.replace("﻿", "").trim()
        if (clean.isBlank()) return
        prefs.edit()
            .putString("stormDnsPendingResolversText", clean)
            .apply()
        val connected = prefs.getBoolean("stormDnsConnected", false) && prefs.getString("activeMode", "").orEmpty() == "stormdns"
        if (!connected) {
            log("StormDNS healthy resolver update queued until core SOCKS is ready • lines=${clean.lineSequence().count { it.trim().isNotBlank() }}")
            return
        }
        runCatching {
            app.startService(
                Intent(app, RkhVpnService::class.java)
                    .setAction(RkhVpnService.ACTION_UPDATE_STORMDNS_RESOLVERS)
                    .putExtra(RkhVpnService.EXTRA_STORMDNS_RESOLVERS, clean)
            )
        }.onFailure { log("Failed to update running StormDNS resolvers", it) }
    }

    private suspend fun flushStormDnsResolversWhenConnected(app: Application, resolvers: String, timeoutMs: Long = 120_000L) {
        val clean = resolvers.replace("﻿", "").trim()
        if (clean.isBlank()) return
        val started = System.currentTimeMillis()
        while (kotlinx.coroutines.currentCoroutineContext().isActive && System.currentTimeMillis() - started < timeoutMs) {
            val connected = prefs.getBoolean("stormDnsConnected", false) && prefs.getString("activeMode", "").orEmpty() == "stormdns"
            if (connected) {
                updateRunningStormDnsResolvers(app, clean)
                return
            }
            delay(750L)
        }
        prefs.edit().putString("stormDnsPendingResolversText", clean).apply()
        log("StormDNS healthy resolver update kept queued after waiting for connection • lines=${clean.lineSequence().count { it.trim().isNotBlank() }}")
    }


    private fun cachedStormDnsResolvers(text: String): List<String> {
        if (text == cachedStormDnsResolversText) return cachedStormDnsResolversList
        cachedStormDnsResolversText = text
        cachedStormDnsResolversList = normalizeStormDnsResolvers(text)
        return cachedStormDnsResolversList
    }

    private fun loadStormDnsLogLines(): List<String> = StormDnsRuntimeLog.readRecent(getApplication<Application>(), 80)

    private fun normalizeStormDnsLogEndpoint(raw: String): String {
        val value = raw.trim().trim(',', ';', ')', ' ')
        if (value.isBlank()) return ""
        if (value.startsWith("[") && value.contains("]:")) {
            val host = value.substringAfter("[").substringBefore("]").trim()
            val port = value.substringAfter("]:", "53").takeWhile { it.isDigit() }.ifBlank { "53" }
            return "[$host]:$port"
        }
        val clean = value.removePrefix("[").substringBefore("]")
        return when {
            Regex("""^\d{1,3}(?:\.\d{1,3}){3}:\d+$""").matches(clean) -> clean
            Regex("""^\d{1,3}(?:\.\d{1,3}){3}$""").matches(clean) -> "$clean:53"
            clean.contains(":") -> clean
            else -> clean
        }
    }

    private fun extractStormDnsHealthyResolversFromLogs(): List<String> {
        val configuredResolvers = cachedStormDnsResolvers(prefs.getString("stormDnsResolvers", defaultStormDnsResolvers()).orEmpty())
        val configuredByEndpoint = linkedMapOf<String, String>()
        configuredResolvers.forEach { resolver ->
            val normalized = normalizeStormDnsLogEndpoint(resolver)
            if (normalized.isNotBlank()) {
                configuredByEndpoint[normalized.lowercase(Locale.US)] = resolver
                val host = normalized.removePrefix("[").substringBefore("]").substringBeforeLast(':', normalized).lowercase(Locale.US)
                if (host.isNotBlank()) configuredByEndpoint.putIfAbsent(host, resolver)
            }
        }
        val out = linkedSetOf<String>()
        fun add(raw: String?) {
            val normalized = normalizeStormDnsLogEndpoint(raw.orEmpty())
            if (normalized.isBlank()) return
            val lower = normalized.lowercase(Locale.US)
            val host = normalized.removePrefix("[").substringBefore("]").substringBeforeLast(':', normalized).lowercase(Locale.US)
            out += configuredByEndpoint[lower] ?: configuredByEndpoint[host] ?: normalized
        }

        StormDnsRuntimeLog.acceptedResolversSnapshot().forEach { add(it) }

        val acceptedPattern = Regex("""(?i)(?:✅\s*)?accepted.*?\bvia\s+(\[[^\]]+\]:\d+|[^\s|),;]+)""")
        val reactivatedPattern = Regex("""(?i)dns\s+resolver\s+reactivated:\s+(\[[^\]]+\]:\d+|[^\s|),;]+)""")
        val validTablePattern = Regex("""(?i)(?:^|\s)(\d{1,3}(?:\.\d{1,3}){3}:\d+|\[[0-9a-f:]+\]:\d+)\s+\d+\s+\d+\s+(?:\d+(?:ms|s)|\d+\.\d+s|[0-9.]+s)\s+\S+""")
        StormDnsRuntimeLog.readRecent(getApplication<Application>(), 80).forEach { line ->
            val lower = line.lowercase(Locale.US)
            if (!lower.contains("rejected") && !lower.contains("timeout") && !lower.contains("fail")) {
                if (lower.contains("accepted")) acceptedPattern.find(line)?.groupValues?.getOrNull(1)?.let { add(it) }
                if (lower.contains("dns resolver reactivated")) reactivatedPattern.find(line)?.groupValues?.getOrNull(1)?.let { add(it) }
                validTablePattern.find(line)?.groupValues?.getOrNull(1)?.let { add(it) }
            }
        }
        return out.toList()
    }


    private fun loadStormDnsHealthyResolversFromRuntimeFiles(): List<String> {
        val now = System.currentTimeMillis()
        if (StormDnsRuntimeLog.acceptedResolversSnapshot().isNotEmpty()) {
            stormDnsRuntimeHealthyCacheAt = now
            stormDnsRuntimeHealthyCache = emptyList()
            return emptyList()
        }
        if (now - stormDnsRuntimeHealthyCacheAt < 20_000L) return stormDnsRuntimeHealthyCache
        val dir = File(getApplication<Application>().filesDir, "stormdns-runtime")
        if (!dir.exists()) {
            stormDnsRuntimeHealthyCacheAt = now
            stormDnsRuntimeHealthyCache = emptyList()
            return emptyList()
        }
        val configuredResolvers = cachedStormDnsResolvers(prefs.getString("stormDnsResolvers", defaultStormDnsResolvers()).orEmpty())
        val configuredByHost = configuredResolvers.associateBy { resolver ->
            resolver.removePrefix("[").substringBefore("]").substringBeforeLast(':').lowercase(Locale.US)
        }
        if (configuredByHost.isEmpty()) {
            stormDnsRuntimeHealthyCacheAt = now
            stormDnsRuntimeHealthyCache = emptyList()
            return emptyList()
        }
        val out = linkedSetOf<String>()
        dir.listFiles()
            ?.filter { it.isFile && (it.name.contains("success", ignoreCase = true) || it.name.contains("mtu", ignoreCase = true)) }
            ?.sortedByDescending { it.lastModified() }
            ?.take(3)
            ?.forEach { file ->
                runCatching { file.readText().takeLast(80_000).lowercase(Locale.US) }.getOrNull()?.let { text ->
                    configuredByHost.forEach { (host, resolver) ->
                        if (host.isNotBlank() && (text.contains(host) || text.contains(resolver.lowercase(Locale.US)))) out += resolver
                    }
                }
            }
        stormDnsRuntimeHealthyCacheAt = now
        stormDnsRuntimeHealthyCache = out.toList()
        return stormDnsRuntimeHealthyCache
    }


    private fun extractStormDnsProgressFromLogs(lines: List<String>): Pair<Int, Int>? {
        val pattern = Regex("""\((\d+)\s*/\s*(\d+)\)""")
        var scanned = 0
        var total = 0
        lines.forEach { line ->
            pattern.findAll(line).forEach { match ->
                val a = match.groupValues.getOrNull(1)?.toIntOrNull() ?: 0
                val b = match.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
                if (a > scanned) scanned = a
                if (b > total) total = b
            }
        }
        return if (total > 0) scanned to total else null
    }

    fun clearStormDnsLogs() {
        StormDnsRuntimeLog.clear(getApplication<Application>())
        prefs.edit()
            .putString("stormDnsStatus", "MasterDNS logs cleared")
            .apply()
        _state.value = loadState()
    }

    fun prepareStormDnsConnectUi() {
        saveCurrentStormDnsProfile()
        val mode = prefs.getString("stormDnsRunMode", "proxy").orEmpty().ifBlank { "proxy" }
        prefs.edit()
            .putBoolean("stormDnsConnecting", true)
            .putBoolean("stormDnsConnected", false)
            .putString("stormDnsStatus", "MasterDNS ${mode.uppercase(Locale.US)} starting...")
            .putString("status", "MasterDNS starting")
            .putBoolean("simpleConnecting", false)
            .putBoolean("simpleConnected", false)
            .putBoolean("connected", false)
            .putBoolean("connecting", false)
            .putBoolean("nipoConnecting", false)
            .putBoolean("nipoConnected", false)
            .putString("activeMode", "stormdns")
            .putLong("startedAt", System.currentTimeMillis())
            .putLong("downloadKbps", 0L)
            .putLong("uploadKbps", 0L)
            .apply()
        _state.value = loadState()
    }

    private fun startStormDnsLogRefreshLoop() {
        stormDnsLogRefreshJob?.cancel()
        stormDnsLogRefreshJob = viewModelScope.launch {
            repeat(900) {
                if (!prefs.getBoolean("stormDnsConnecting", false) && !prefs.getBoolean("stormDnsConnected", false) && prefs.getString("activeMode", "") != "stormdns") return@launch
                _state.value = loadState()
                delay(if (prefs.getBoolean("stormDnsConnecting", false)) 2_000L else 3_000L)
            }
        }
    }

    fun stormDnsConnectAfterPermission() {
        val section = "stormdns"
        if (!beginGlobalConnect(section)) return
        viewModelScope.launch {
            val app = getApplication<Application>()
            try {
                // Simple MasterDNS flow:
                // 1) Do NOT run a separate Android-side DNS pre-scan.
                // 2) Start the StormDNS native core with the full resolver list.
                // 3) Let MasterDNS finish its own scan and open SOCKS on 127.0.0.1:18000.
                // 4) RkhVpnService waits for that local SOCKS port, then creates the VPN tunnel.
                stormDnsResolverScanJob?.cancel()
                stormDnsResolverScanJob = null

                val mode = prefs.getString("stormDnsRunMode", "proxy").orEmpty().ifBlank { "proxy" }
                val clientConfig = buildStormDnsClientConfig()
                val resolvers = prefs.getString("stormDnsResolvers", defaultStormDnsResolvers()).orEmpty().ifBlank { defaultStormDnsResolvers() }
                val candidates = normalizeStormDnsResolvers(resolvers)
                if (clientConfig.isBlank() || candidates.isEmpty()) {
                    prefs.edit()
                        .putBoolean("stormDnsConnecting", false)
                        .putBoolean("stormDnsConnected", false)
                        .putBoolean("stormDnsResolverScanning", false)
                        .putString("stormDnsStatus", "MasterDNS: client_config.toml and client_resolvers.txt are required")
                        .putString("status", "MasterDNS config incomplete")
                        .putString("activeMode", "idle")
                        .apply()
                    _state.value = loadState()
                    return@launch
                }

                stopAllCoresBeforeConnect(app, section)

                val coreResolvers = rawStormDnsResolversForRuntime(resolvers)
                prefs.edit()
                    .putInt("stormDnsResolverTotal", candidates.size)
                    .putInt("stormDnsResolverScanned", 0)
                    .putInt("stormDnsResolverValidCount", 0)
                    .putBoolean("stormDnsResolverScanning", true)
                    .putString("stormDnsHealthyResolversText", "")
                    .putString("stormDnsResolverScanStatus", "MasterDNS default core scan running • VPN will attach to 127.0.0.1:18000 when ready")
                    .putString("stormDnsStatus", "Starting MasterDNS default core • waiting for 127.0.0.1:18000")
                    .putBoolean("stormDnsConnecting", true)
                    .putBoolean("stormDnsConnected", false)
                    .putBoolean("simpleConnecting", false)
                    .putBoolean("simpleConnected", false)
                    .putBoolean("connected", false)
                    .putBoolean("connecting", false)
                    .putBoolean("nipoConnecting", false)
                    .putBoolean("nipoConnected", false)
                    .putString("activeMode", "stormdns")
                    .putLong("startedAt", System.currentTimeMillis())
                    .remove("stormDnsPendingResolversText")
                    .apply()
                _state.value = loadState()

                startStormDnsService(app, mode, clientConfig, coreResolvers)
                startStormDnsLogRefreshLoop()
                _state.value = loadState()
            } catch (e: Throwable) {
                prefs.edit()
                    .putBoolean("stormDnsConnecting", false)
                    .putBoolean("stormDnsConnected", false)
                    .putBoolean("stormDnsResolverScanning", false)
                    .putString("stormDnsStatus", "MasterDNS start error: ${e.message ?: e.javaClass.simpleName}")
                    .putString("status", "MasterDNS start error")
                    .putString("activeMode", "idle")
                    .apply()
                log("Safe StormDNS connect error", e)
                _state.value = loadState()
            } finally {
                delay(1_500L)
                endGlobalConnect(section)
            }
        }
    }

    fun stormDnsDisconnect() {
        val app = getApplication<Application>()
        runCatching {
            app.startService(Intent(app, RkhVpnService::class.java).setAction(RkhVpnService.ACTION_STOP))
        }.onFailure { log("Failed to send MasterDNS stop intent", it) }
        prefs.edit()
            .putBoolean("stormDnsConnecting", false)
            .putBoolean("stormDnsConnected", false)
            .putString("stormDnsStatus", "MasterDNS disconnected")
            .putString("status", "MasterDNS disconnected")
            .putString("activeMode", "idle")
            .putLong("downloadKbps", 0L)
            .putLong("uploadKbps", 0L)
            .putLong("startedAt", 0L)
            .apply()
        _state.value = loadState()
    }

    private fun buildStormDnsClientConfig(): String {
        return prefs.getString("stormDnsClientConfig", defaultStormDnsClientConfig()).orEmpty().ifBlank { defaultStormDnsClientConfig() }
    }

    private fun buildStormDnsServerConfig(): String {
        return prefs.getString("stormDnsServerConfig", defaultStormDnsServerConfig()).orEmpty().ifBlank { defaultStormDnsServerConfig() }
    }

    fun prepareSimpleConnectUi() {
        val startedAt = System.currentTimeMillis()
        resetSimpleHealthyMemory()
        prefs.edit()
            .putBoolean("simpleConnecting", true)
            .putBoolean("simpleConnected", false)
            .putString("simpleStatus", "Starting Simple Balancer...")
            .putString("status", "Simple Balancer starting")
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
        val section = "simple"
        if (!beginGlobalConnect(section)) return
        if (simpleHealthyScanJob?.isActive == true) {
            log("Simple connect ignored: connect job already running")
            endGlobalConnect(section)
            return
        }
        simpleHealthyScanJob = viewModelScope.launch {
            try {
                val app = getApplication<Application>()
                stopAllCoresBeforeConnect(app, section)
                val startedAt = System.currentTimeMillis()
                resetSimpleHealthyMemory()
                prefs.edit()
                    .putBoolean("simpleConnecting", true)
                    .putBoolean("simpleConnected", false)
                    .putString("simpleStatus", if (prefs.getBoolean("simpleServerlessEnabled", false)) "Searching and Ping..." else "Starting Simple Balancer...")
                    .putString("status", if (prefs.getBoolean("simpleServerlessEnabled", false)) "Searching and Ping..." else "Simple Balancer starting")
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
                // Config bodies stay cached until Update. Simple normal Connect builds one Xray balancer config.
                val serverless = prefs.getBoolean("simpleServerlessEnabled", false)
                var configs = loadSimpleConfigs(serverless)
                if (configs.isEmpty()) {
                    prefs.edit()
                        .putString("simpleStatus", if (serverless) "Searching and Ping... • first run load" else "Loading configs for Simple Balancer...")
                        .putString("status", if (serverless) "Searching and Ping..." else "Simple Balancer loading")
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
                log("Simple XRAY connect using cached configs • mode=${if (prefs.getBoolean("simpleServerlessEnabled", false)) simpleServerlessDisplayName else "Normal Active Balancer"} • configs=${configs.size}")

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
                    val best = if (tested.pingMs != null) {
                        tested.copy(name = simpleServerlessDisplayName)
                    } else {
                        // ServerLess JSON can still work even when the external latency endpoint fails.
                        // Do not block startup on ping failure; start the bundled config directly.
                        log("Simple XRAY ServerLess ping failed (${tested.error ?: "unknown"}); starting ServerLess config directly")
                        prefs.edit()
                            .putString("simpleStatus", "Starting ServerLess... • ping test skipped")
                            .putString("status", "Starting ServerLess")
                            .apply()
                        _state.value = loadState()
                        configs.first().copy(name = simpleServerlessDisplayName, pingMs = null, error = null)
                    }
                    if (!startSelectedSimpleConfig(app, best, 0, startedAt)) return@launch
                    return@launch
                }

                // Active Simple balancer: run real Xray URL tests for normal configs, show pings in the list,
                // connect to the first working config, then move to the latest lowest-ping result.
                prefs.edit()
                    .putString("simpleStatus", "Real Xray testing configs... • ${configs.size} configs")
                    .putString("status", "Real Xray testing")
                    .apply()
                _state.value = loadState()
                val connected = connectFromFastHealthyScanner(app, configs, startedAt)
                if (connected == null) {
                    prefs.edit()
                        .putBoolean("simpleConnecting", false)
                        .putBoolean("simpleConnected", false)
                        .putString("simpleStatus", "Simple Balancer: no healthy config")
                        .putString("status", "Simple Balancer: no healthy config")
                        .putString("activeMode", "idle")
                        .putLong("startedAt", 0L)
                        .apply()
                    _state.value = loadState()
                    log("Simple active balancer: no healthy config after real Xray test")
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
            } finally {
                delay(1_500L)
                endGlobalConnect(section)
            }
        }
    }


    fun prepareSimpleConfigConnectUi(index: Int): Boolean {
        if (prefs.getBoolean("simpleConnecting", false)) {
            log("Simple config row connect ignored: Simple is already connecting")
            return false
        }
        val configs = loadSimpleConfigs(prefs.getBoolean("simpleServerlessEnabled", false))
        if (configs.isEmpty()) {
            prefs.edit()
                .putBoolean("simpleConnecting", false)
                .putBoolean("simpleConnected", false)
                .putString("simpleStatus", "Simple: no cached configs. Tap Update first.")
                .putString("status", "Simple: no cached configs")
                .putString("activeMode", "idle")
                .putLong("startedAt", 0L)
                .apply()
            _state.value = loadState()
            log("Simple config row connect ignored: no cached configs")
            return false
        }
        val safeIndex = index.coerceIn(0, configs.lastIndex)
        val xraySwitchOnly = !prefs.getBoolean("simpleServerlessEnabled", false) &&
            prefs.getBoolean("simpleConnected", false) &&
            prefs.getString("activeMode", "") == "simple_xray"
        prefs.edit()
            .putInt("simplePendingConnectIndex", safeIndex)
            .putString("simplePendingConnectSource", "default")
            .putBoolean("simpleConnecting", true)
            .putBoolean("simpleConnected", xraySwitchOnly)
            .putString("simpleStatus", if (xraySwitchOnly) "Switching ${simpleDisplayName(safeIndex)} with Xray only..." else "Connecting ${simpleDisplayName(safeIndex)}...")
            .putString("status", if (xraySwitchOnly) "Simple Xray switching" else "Simple XRAY connecting")
            .putString("activeMode", "simple_xray")
            .putLong("startedAt", System.currentTimeMillis())
            .apply()
        _state.value = loadState()
        log("Simple config row connect requested • index=$safeIndex • cached=${configs.size}")
        return true
    }

    fun simpleConnectSelectedAfterPermission() {
        val section = "simple-selected"
        if (!beginGlobalConnect(section)) return
        viewModelScope.launch {
            try {
                val app = getApplication<Application>()
                val pendingSource = prefs.getString("simplePendingConnectSource", "default").orEmpty()
                if (pendingSource == "custom") {
                    val customId = simpleCustomSelectedProfileId()
                    val configs = loadSimpleCustomConfigs(customId)
                    val pending = prefs.getInt("simplePendingConnectIndex", 0).let { idx -> if (configs.isEmpty()) 0 else idx.coerceIn(configs.indices) }
                    if (configs.isEmpty()) {
                        prefs.edit()
                            .putBoolean("simpleConnecting", false)
                            .putBoolean("simpleConnected", false)
                            .putString("simpleCustomStatus", "No configs in selected profile. Tap Update Link first.")
                            .putString("simpleStatus", "Sub/Config profile has no configs")
                            .putString("status", "Simple Sub/Config: no configs")
                            .putString("activeMode", "idle")
                            .putLong("startedAt", 0L)
                            .apply()
                        _state.value = loadState()
                        return@launch
                    }
                    val xraySwitchOnly = prefs.getBoolean("simpleConnected", false) && prefs.getString("activeMode", "") == "simple_xray"
                    if (!xraySwitchOnly) {
                        stopAllCoresBeforeConnect(app, "simple-custom")
                    } else {
                        simpleBackgroundLatencyJob?.cancel()
                        simpleBackgroundLatencyJob = null
                    }
                    val profileName = simpleCustomProfileRemark(customId).ifBlank { "Sub/Config" }
                    val selected = configs[pending]
                    val startedAt = System.currentTimeMillis()
                    prefs.edit()
                        .putString("simpleCustomStatus", "Connecting $profileName • Config ${pending + 1}")
                        .putString("simpleStatus", "Sub/Config connecting: $profileName • Config ${pending + 1}")
                        .putString("status", "Simple Sub/Config connecting")
                        .putString("activeMode", "simple_xray")
                        .putLong("startedAt", startedAt)
                        .apply()
                    _state.value = loadState()
                    startSelectedSimpleConfig(app, selected, pending, startedAt, forceXraySwitch = xraySwitchOnly, displayLabel = "$profileName • Config ${pending + 1}")
                    return@launch
                }
                val serverless = prefs.getBoolean("simpleServerlessEnabled", false)
                val xraySwitchOnly = !serverless && prefs.getBoolean("simpleConnected", false) && prefs.getString("activeMode", "") == "simple_xray"
                if (!xraySwitchOnly) {
                    stopAllCoresBeforeConnect(app, section)
                } else {
                    simpleBackgroundLatencyJob?.cancel()
                    simpleBackgroundLatencyJob = null
                    prefs.edit()
                        .putBoolean("simpleConnecting", true)
                        .putBoolean("simpleConnected", true)
                        .putString("simpleStatus", "Switching selected config with Xray only...")
                        .putString("status", "Simple Xray switching")
                        .putString("activeMode", "simple_xray")
                        .apply()
                    _state.value = loadState()
                    log("Simple selected config will switch Xray only; keeping VPN/tun2proxy alive")
                }
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
                    .putString("simpleStatus", if (serverless) "Testing ${simpleDisplayName(pending)} with Xray ping..." else "Fast testing ${simpleDisplayName(pending)}...")
                    .putString("status", if (serverless) "Simple XRAY testing selected config" else "Fast testing selected config")
                    .apply()
                _state.value = loadState()
                val selected = withContext(Dispatchers.IO) { if (serverless) simplePing.pingStrict3(candidate) else simpleFastProbe(candidate) }
                val ping = selected.pingMs
                if (ping == null && !serverless) {
                    removeSimpleLatencyResult(candidate)
                    prefs.edit()
                        .putBoolean("simpleConnecting", false)
                        .putBoolean("simpleConnected", prefs.getBoolean("simpleConnected", false))
                        .putString("simpleStatus", "${simpleDisplayName(pending)} failed health test")
                        .putString("status", "Selected config failed")
                        .apply()
                    log("Simple row connect blocked by failed health test • index=$pending • ${selected.error ?: "unknown"}")
                    _state.value = loadState()
                    return@launch
                }
                if (ping != null) saveSimpleLatencyResult(selected, ping)
                val startedAt = System.currentTimeMillis()
                val selectedForStart = if (serverless && ping == null) candidate.copy(name = simpleServerlessDisplayName, pingMs = null, error = null) else selected
                if (!startSelectedSimpleConfig(app, selectedForStart, pending, startedAt, forceXraySwitch = xraySwitchOnly)) return@launch
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
            } finally {
                delay(1_500L)
                endGlobalConnect(section)
            }
        }
    }


    private fun simpleCustomProfilesRaw(): List<Pair<String, String>> {
        val raw = prefs.getString("simpleCustomProfiles", "[]").orEmpty()
        val parsed = runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = obj.optString("id").trim()
                val remark = obj.optString("remark").trim().ifBlank { "Profile" }
                if (id.isBlank()) null else id to remark
            }
        }.getOrDefault(emptyList())
        return parsed.distinctBy { it.first }
    }

    private fun saveSimpleCustomProfilesRaw(list: List<Pair<String, String>>) {
        val arr = JSONArray()
        list.distinctBy { it.first }.forEach { (id, remark) ->
            arr.put(JSONObject().put("id", id).put("remark", remark.ifBlank { "Profile" }))
        }
        prefs.edit().putString("simpleCustomProfiles", arr.toString()).apply()
    }

    private fun newSimpleCustomId(): String = "sc_" + System.currentTimeMillis().toString(36) + "_" + kotlin.math.abs((0..999999).random()).toString(36)

    private fun simpleCustomSelectedProfileId(): String {
        val profiles = simpleCustomProfilesRaw()
        val saved = prefs.getString("simpleCustomSelectedProfile", "").orEmpty()
        return profiles.firstOrNull { it.first == saved }?.first ?: profiles.firstOrNull()?.first.orEmpty()
    }

    private fun simpleCustomProfileRemark(id: String): String {
        return simpleCustomProfilesRaw().firstOrNull { it.first == id }?.second.orEmpty()
    }

    private fun simpleCustomKey(prefix: String, id: String): String = prefix + "_" + id.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.ifBlank { "default" }

    private fun simpleCustomInput(id: String): String = prefs.getString(simpleCustomKey("simpleCustomInput", id), "").orEmpty()

    private fun simpleCustomBody(id: String): String = decodeSimpleBodyFromCache(prefs.getString(simpleCustomKey("simpleCustomBody", id), "").orEmpty())

    private fun loadSimpleCustomConfigs(id: String = simpleCustomSelectedProfileId()): List<ServerConfig> {
        if (id.isBlank()) return emptyList()
        val body = simpleCustomBody(id).ifBlank { simpleCustomInput(id).takeUnless { it.trim().startsWith("http", ignoreCase = true) }.orEmpty() }
        val clean = body.replace("﻿", "").trim()
        if (clean.isBlank()) return emptyList()
        val parsed = runCatching { parseSimpleConfigs(clean, serverless = false) }.getOrDefault(emptyList())
        val remark = simpleCustomProfileRemark(id).ifBlank { "Sub/Config" }
        val configs = if (parsed.isNotEmpty()) parsed else {
            if (clean.startsWith("{") && clean.contains("\"outbounds\"") && clean.contains("\"inbounds\"")) {
                listOf(ServerConfig("simple_custom_${kotlin.math.abs(clean.hashCode())}", remark, clean, null, null))
            } else emptyList()
        }
        return configs.mapIndexed { index, cfg ->
            cfg.copy(name = cfg.name.ifBlank { "$remark ${index + 1}" })
        }
    }

    private fun simpleCustomProfileItems(): List<SimpleCustomProfileUi> {
        val selected = simpleCustomSelectedProfileId()
        return simpleCustomProfilesRaw().map { (id, remark) ->
            SimpleCustomProfileUi(
                id = id,
                remark = remark,
                selected = id == selected,
                configCount = loadSimpleCustomConfigs(id).size
            )
        }
    }

    fun addSimpleCustomProfile() {
        val profiles = simpleCustomProfilesRaw()
        val nextNumber = profiles.size + 1
        val id = newSimpleCustomId()
        val remark = "Profile $nextNumber"
        saveSimpleCustomProfilesRaw(profiles + (id to remark))
        prefs.edit()
            .putString("simpleCustomSelectedProfile", id)
            .putString(simpleCustomKey("simpleCustomInput", id), "")
            .putString(simpleCustomKey("simpleCustomBody", id), "")
            .putString("simpleCustomStatus", "Empty Sub/Config profile created")
            .apply()
        _state.value = loadState()
    }

    fun selectSimpleCustomProfile(id: String) {
        if (simpleCustomProfilesRaw().none { it.first == id }) return
        prefs.edit().putString("simpleCustomSelectedProfile", id).apply()
        _state.value = loadState()
    }

    fun deleteSimpleCustomProfile(id: String) {
        val profiles = simpleCustomProfilesRaw()
        val next = profiles.filterNot { it.first == id }
        saveSimpleCustomProfilesRaw(next)
        prefs.edit()
            .remove(simpleCustomKey("simpleCustomInput", id))
            .remove(simpleCustomKey("simpleCustomBody", id))
            .putString("simpleCustomSelectedProfile", next.firstOrNull()?.first.orEmpty())
            .putString("simpleCustomStatus", "Sub/Config profile deleted")
            .apply()
        _state.value = loadState()
    }

    fun setSimpleCustomRemark(text: String) {
        val id = simpleCustomSelectedProfileId()
        if (id.isBlank()) return
        val clean = text.take(48).trim().ifBlank { "Profile" }
        val updated = simpleCustomProfilesRaw().map { if (it.first == id) id to clean else it }
        saveSimpleCustomProfilesRaw(updated)
        _state.value = loadState()
    }

    fun setSimpleCustomInput(text: String) {
        val id = simpleCustomSelectedProfileId()
        if (id.isBlank()) return
        prefs.edit()
            .putString(simpleCustomKey("simpleCustomInput", id), text)
            .putString("simpleCustomStatus", "Sub/Config profile updated locally")
            .apply()
        _state.value = loadState()
    }

    private fun fetchSimpleCustomBody(input: String): String {
        val clean = input.trim()
        if (!clean.startsWith("http://", true) && !clean.startsWith("https://", true)) return clean
        val conn = (URL(clean).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12000
            readTimeout = 25000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "SIMORGH/SubConfig")
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

    fun updateSimpleCustomProfile(profileId: String = "") = viewModelScope.launch {
        val requestedId = profileId.trim()
        val id = requestedId.takeIf { it.isNotBlank() && simpleCustomProfilesRaw().any { profile -> profile.first == it } } ?: simpleCustomSelectedProfileId()
        if (id.isBlank()) {
            prefs.edit().putString("simpleCustomStatus", "Tap Add first").apply()
            _state.value = loadState()
            return@launch
        }
        val input = simpleCustomInput(id).trim()
        if (input.isBlank()) {
            prefs.edit().putString("simpleCustomStatus", "Paste a subscription link or config first").apply()
            _state.value = loadState()
            return@launch
        }
        prefs.edit()
            .putString("simpleCustomSelectedProfile", id)
            .putString("simpleCustomStatus", "Updating Sub/Config profile...")
            .apply()
        _state.value = loadState()
        runCatching {
            val body = withContext(Dispatchers.IO) { fetchSimpleCustomBody(input) }
            val parsed = parseSimpleConfigs(body, serverless = false).ifEmpty {
                if (body.trim().startsWith("{") && body.contains("\"outbounds\"") && body.contains("\"inbounds\"")) {
                    listOf(ServerConfig("simple_custom_${kotlin.math.abs(body.hashCode())}", simpleCustomProfileRemark(id), body, null, null))
                } else emptyList()
            }
            prefs.edit()
                .putString(simpleCustomKey("simpleCustomBody", id), encodeSimpleBodyForCache(body))
                .putString("simpleCustomStatus", "Updated • ${parsed.size} configs detected")
                .apply()
            log("Simple Sub/Config updated • profile=${simpleCustomProfileRemark(id)} • configs=${parsed.size}")
        }.onFailure { e ->
            prefs.edit().putString("simpleCustomStatus", "Update failed: ${e.message ?: e.javaClass.simpleName}").apply()
            log("Simple Sub/Config update failed", e)
        }
        _state.value = loadState()
    }

    fun prepareSimpleCustomConfigConnectUi(index: Int): Boolean {
        if (prefs.getBoolean("simpleConnecting", false)) {
            prefs.edit().putString("simpleCustomStatus", "Simple is already connecting").apply()
            _state.value = loadState()
            return false
        }
        val id = simpleCustomSelectedProfileId()
        val configs = loadSimpleCustomConfigs(id)
        if (configs.isEmpty()) {
            prefs.edit().putString("simpleCustomStatus", "No configs in selected profile. Tap Update Link first.").apply()
            _state.value = loadState()
            return false
        }
        val safeIndex = index.coerceIn(0, configs.lastIndex)
        val xraySwitchOnly = prefs.getBoolean("simpleConnected", false) && prefs.getString("activeMode", "") == "simple_xray"
        prefs.edit()
            .putString("simplePendingConnectSource", "custom")
            .putInt("simplePendingConnectIndex", safeIndex)
            .putBoolean("simpleConnecting", true)
            .putBoolean("simpleConnected", xraySwitchOnly)
            .putString("simpleCustomStatus", "Connecting ${simpleCustomProfileRemark(id)} • Config ${safeIndex + 1}")
            .putString("simpleStatus", "Sub/Config connecting...")
            .putString("status", "Simple Sub/Config connecting")
            .putString("activeMode", "simple_xray")
            .putLong("startedAt", System.currentTimeMillis())
            .apply()
        _state.value = loadState()
        return true
    }

    fun pingSimpleCustomConfig(index: Int) = viewModelScope.launch {
        val id = simpleCustomSelectedProfileId()
        val configs = loadSimpleCustomConfigs(id)
        if (configs.isEmpty()) {
            prefs.edit().putString("simpleCustomStatus", "No configs to ping. Tap Update Link first.").apply()
            _state.value = loadState()
            return@launch
        }
        val safeIndex = index.coerceIn(0, configs.lastIndex)
        val target = configs[safeIndex]
        val label = target.name.ifBlank { "Config ${safeIndex + 1}" }
        prefs.edit().putString("simpleCustomStatus", "Pinging $label with Xray...").apply()
        _state.value = loadState()
        val tested = withContext(Dispatchers.IO) { simpleFastProbe(target) }
        tested.pingMs?.let { saveSimpleLatencyResult(target, it) }
        val status = tested.pingMs?.let { "$label • ${it}ms" } ?: "$label ping failed"
        prefs.edit()
            .putString("simpleCustomStatus", status)
            .apply()
        log("Simple Sub/Config ping • $status")
        _state.value = loadState()
    }

    private fun bestCachedSimpleConfig(configs: List<ServerConfig>): Pair<Int, ServerConfig>? {
        val cache = loadSimpleLatencyCache()
        if (configs.isEmpty() || cache.isEmpty()) return null
        return configs.mapIndexedNotNull { index, server ->
            cache[server.id]?.let { entry -> index to server.copy(pingMs = entry.pingMs, error = null) }
        }.minByOrNull { it.second.pingMs ?: Long.MAX_VALUE }
    }


    private fun selectSimpleBalancerConfigs(configs: List<ServerConfig>): List<Pair<Int, ServerConfig>> {
        val cache = loadSimpleLatencyCache()
        val indexed = configs.withIndex().filter { !it.value.raw.trim().startsWith("{") }
        if (indexed.isEmpty()) return emptyList()
        val cached = indexed
            .filter { cache.containsKey(it.value.id) }
            .sortedBy { cache[it.value.id]?.pingMs ?: Long.MAX_VALUE }
        val uncached = indexed
            .filterNot { cache.containsKey(it.value.id) }
            .shuffled()
        return (cached + uncached)
            .distinctBy { it.value.id }
            .take(SIMPLE_BALANCER_MAX_OUTBOUNDS)
            .map { it.index to it.value }
    }

    private fun startSimpleBalancerConfig(app: Application, selected: List<Pair<Int, ServerConfig>>, startedAt: Long): Boolean {
        val firstIndex = selected.firstOrNull()?.first ?: 0
        val label = "Balancer • ${selected.size} configs"
        val balancedRaw = runCatching {
            XrayBinaryConfigBuilder.hiddifyLikeBalancedSocksConfigFromRawList(selected.map { it.second.raw }, socksPort = 18188, forceGoogleDns = true)
        }.getOrElse { e ->
            prefs.edit()
                .putBoolean("simpleConnecting", false)
                .putBoolean("simpleConnected", false)
                .putString("simpleStatus", "Simple Balancer build failed: ${e.message ?: e.javaClass.simpleName}")
                .putString("status", "Simple Balancer build failed")
                .putString("activeMode", "idle")
                .putLong("startedAt", 0L)
                .apply()
            log("Simple Hiddify-like balancer config build failed", e)
            _state.value = loadState()
            return false
        }

        prefs.edit()
            .putString("simpleBestName", label)
            .putString("simpleBestId", "simple-hiddify-balancer")
            .putInt("simpleBestIndex", firstIndex)
            .putInt("simpleBestRawHash", balancedRaw.hashCode())
            .putLong("simpleBestPingMs", -1L)
            .putString("simpleStatus", "Simple Balancer connecting • ${selected.size} configs")
            .putString("status", "Simple Balancer connecting")
            .putString("activeMode", "simple_xray")
            .putLong("startedAt", startedAt)
            .putLong("simpleLastTrafficAt", startedAt)
            .putBoolean("simpleHadTraffic", false)
            .apply()
        _state.value = loadState()
        log("Simple Hiddify-like balancer selected • outbounds=${selected.size} • firstIndex=$firstIndex • rawChars=${balancedRaw.length}")

        val intent = Intent(app, RkhVpnService::class.java)
            .setAction(RkhVpnService.ACTION_START)
            .putExtra(RkhVpnService.EXTRA_RAW_CONFIG, balancedRaw)
            .putExtra(RkhVpnService.EXTRA_SERVER_NAME, "SIMORGH Simple • Hiddify Balancer")
        val result = safeStartCoreService(app, intent)
        result.onFailure { e ->
            prefs.edit()
                .putBoolean("simpleConnecting", false)
                .putBoolean("simpleConnected", false)
                .putString("simpleStatus", "Simple Balancer start failed: ${e.message ?: e.javaClass.simpleName}")
                .putString("status", "Simple Balancer start failed")
                .putString("activeMode", "idle")
                .putLong("startedAt", 0L)
                .apply()
            log("Simple Hiddify-like balancer service start failed", e)
            _state.value = loadState()
        }
        if (result.isFailure) return false
        prefs.edit()
            .putBoolean("simpleConnecting", true)
            .putBoolean("simpleConnected", false)
            .putString("simpleStatus", "Simple Balancer starting backend • ${selected.size} configs")
            .putString("status", "Simple Balancer starting backend")
            .putString("activeMode", "simple_xray")
            .putLong("startedAt", startedAt)
            .apply()
        _state.value = loadState()
        return true
    }

    private fun safeStartCoreService(app: Application, intent: Intent): Result<android.content.ComponentName?> = runCatching {
        val action = intent.action.orEmpty().ifBlank { "null" }
        val component = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) app.startForegroundService(intent) else app.startService(intent)
        component
    }

    private suspend fun waitForSimpleTunnelReadyForXraySwitch(timeoutMs: Long = 10_000L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val serviceConnected = coreStatePrefs().getBoolean("serviceConnected", false)
            val simpleActive = prefs.getString("activeMode", "") == "simple_xray"
            if (serviceConnected && simpleActive) return true
            delay(150L)
        }
        return false
    }

    private suspend fun startSelectedSimpleConfig(app: Application, best: ServerConfig, bestIndex: Int, startedAt: Long, forceXraySwitch: Boolean = false, displayLabel: String? = null): Boolean {
        val bestLabel = displayLabel ?: simpleDisplayName(bestIndex)
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
        if (raw.isBlank()) {
            prefs.edit()
                .putBoolean("simpleConnecting", false)
                .putBoolean("simpleConnected", false)
                .putString("simpleStatus", "Simple XRAY start skipped: empty config")
                .putString("status", "Simple empty config")
                .putString("activeMode", "idle")
                .putLong("startedAt", 0L)
                .apply()
            _state.value = loadState()
            log("Simple XRAY start skipped because selected raw config is blank")
            return false
        }
        if (prefs.getBoolean("simpleServerlessEnabled", false)) {
            log("Simple ServerLess connect handoff • rawChars=${raw.length} • startsJson=${raw.startsWith("{")} • containsTunInbound=${raw.contains("\"protocol\":\"tun\"") || raw.contains("\"protocol\": \"tun\"")} • containsOutbounds=${raw.contains("\"outbounds\"")}")
        }
        val serverlessSelected = prefs.getBoolean("simpleServerlessEnabled", false) || raw.startsWith("{") || raw.contains("Serverless", ignoreCase = true)
        val xraySwitchOnly = !serverlessSelected && (forceXraySwitch || (prefs.getBoolean("simpleConnected", false) && prefs.getString("activeMode", "") == "simple_xray"))
        if (xraySwitchOnly && !waitForSimpleTunnelReadyForXraySwitch()) {
            prefs.edit()
                .putBoolean("simpleConnecting", false)
                .putBoolean("simpleConnected", prefs.getBoolean("simpleConnected", false))
                .putString("simpleStatus", "Xray switch skipped: VPN tunnel not ready yet")
                .putString("status", "Simple Xray switch skipped")
                .putString("activeMode", "simple_xray")
                .apply()
            log("Simple XRAY switch skipped because existing VPN/tun2proxy was not ready; no full restart used")
            _state.value = loadState()
            return false
        }
        val intent = Intent(app, RkhVpnService::class.java)
            .setAction(if (xraySwitchOnly) RkhVpnService.ACTION_SWITCH_XRAY else RkhVpnService.ACTION_START)
            .putExtra(RkhVpnService.EXTRA_RAW_CONFIG, raw)
            .putExtra(RkhVpnService.EXTRA_SERVER_NAME, "SIMORGH Simple • $bestLabel")
        val result = safeStartCoreService(app, intent)
        result.onFailure { e ->
            prefs.edit()
                .putBoolean("simpleConnecting", false)
                .putBoolean("simpleConnected", if (xraySwitchOnly) true else false)
                .putString("simpleStatus", if (xraySwitchOnly) "Simple XRAY switch request failed: ${e.message ?: e.javaClass.simpleName}" else "Simple XRAY start failed: ${e.message ?: e.javaClass.simpleName}")
                .putString("status", if (xraySwitchOnly) "Simple XRAY switch request failed" else "Simple XRAY start failed")
                .putString("activeMode", if (xraySwitchOnly) "simple_xray" else "idle")
                .putLong("startedAt", if (xraySwitchOnly) startedAt else 0L)
                .apply()
            log(if (xraySwitchOnly) "Simple XRAY service switch request failed" else "Simple XRAY service start failed", e)
            _state.value = loadState()
        }
        if (result.isFailure) return false
        prefs.edit()
            .putBoolean("simpleConnecting", true)
            .putBoolean("simpleConnected", if (xraySwitchOnly) true else false)
            .putString("simpleBestId", best.id)
            .putInt("simpleBestIndex", bestIndex)
            .putInt("simpleBestRawHash", best.raw.hashCode())
            .putString("simpleStatus", if (xraySwitchOnly) "Simple XRAY switching backend: $bestLabel • Ping $bestPingLabel" else "Simple XRAY starting backend: $bestLabel • Ping $bestPingLabel")
            .putString("status", if (xraySwitchOnly) "Simple XRAY switching backend" else "Simple XRAY starting backend")
            .putString("activeMode", "simple_xray")
            .putLong("startedAt", startedAt)
            .apply()
        best.pingMs?.let { saveSimpleLatencyResult(best, it) }
        prefs.edit().putInt("simpleLatencyProbeIndex", bestIndex).apply()
        _state.value = loadState()
        if (xraySwitchOnly) {
            // Keep Simple switches serialized from the UI side too. The service also
            // has a guard, but waiting here prevents the scanner from queuing several
            // Xray replacements faster than Xray can release/rebind the SOCKS port.
            val settleDeadline = System.currentTimeMillis() + 5_000L
            while (System.currentTimeMillis() < settleDeadline && prefs.getBoolean("simpleConnecting", false)) {
                delay(150L)
            }
        }
        return true
    }

    private suspend fun connectFromFastHealthyScanner(app: Application, configs: List<ServerConfig>, startedAt: Long): Pair<Int, ServerConfig>? = coroutineScope {
        val candidates = configs.withIndex().filter { !it.value.raw.trim().startsWith("{") }.shuffled()
        if (candidates.isEmpty()) return@coroutineScope null
        val total = candidates.size
        val latencyCache = loadSimpleLatencyCache().toMutableMap()
        val healthy = ArrayList<Pair<Int, ServerConfig>>()
        var connected: Pair<Int, ServerConfig>? = null
        var done = 0

        for (batch in candidates.chunked(SIMPLE_FAST_PROBE_PARALLELISM)) {
            val tested = batch.map { indexed ->
                async(Dispatchers.IO) {
                    indexed.index to runCatching { simpleFastProbe(indexed.value) }
                        .getOrElse { e -> indexed.value.copy(pingMs = null, error = e.message ?: e.javaClass.simpleName) }
                }
            }.awaitAll()
            done += tested.size

            tested.forEach { (idx, server) ->
                val ping = server.pingMs
                if (ping != null && ping > 0L) {
                    latencyCache[server.id] = SimpleLatencyEntry(ping, System.currentTimeMillis())
                    healthy += idx to server
                } else {
                    latencyCache.remove(server.id)
                }
            }
            writeSimpleLatencyCache(latencyCache)

            val bestSoFar = healthy.minByOrNull { it.second.pingMs ?: Long.MAX_VALUE }
            if (connected == null && bestSoFar != null) {
                val started = startSelectedSimpleConfig(app, bestSoFar.second, bestSoFar.first, startedAt)
                if (!started) return@coroutineScope null
                connected = bestSoFar
                log("Simple active balancer connected to first healthy config • ${simpleDisplayName(bestSoFar.first)} • ${bestSoFar.second.pingMs}ms; scanner continues for lower ping")
            } else if (connected != null && bestSoFar != null && bestSoFar.first != connected!!.first) {
                val currentPing = connected!!.second.pingMs ?: Long.MAX_VALUE
                val bestPing = bestSoFar.second.pingMs ?: Long.MAX_VALUE
                if (bestPing < currentPing) {
                    val switched = startSelectedSimpleConfig(app, bestSoFar.second, bestSoFar.first, startedAt, forceXraySwitch = true)
                    if (switched) {
                        log("Simple active balancer switched to lower ping during scan • ${simpleDisplayName(bestSoFar.first)} • ${bestPing}ms < ${currentPing}ms")
                        connected = bestSoFar
                    }
                }
            }

            val bestText = bestSoFar?.let { " • best ${simpleDisplayName(it.first)} ${it.second.pingMs}ms" } ?: ""
            val connectedText = connected?.let { "Connected ${simpleDisplayName(it.first)} • " } ?: ""
            prefs.edit()
                .putString("simpleStatus", "${connectedText}Real Xray test... $done/$total scanned • ${healthy.size} healthy$bestText")
                .putString("status", if (connected == null) "Real Xray test... $done/$total" else "Simple XRAY connected")
                .apply()
            _state.value = loadState()
        }

        val finalBest = healthy.minByOrNull { it.second.pingMs ?: Long.MAX_VALUE }
        if (connected != null && finalBest != null && finalBest.first != connected!!.first) {
            val switched = startSelectedSimpleConfig(app, finalBest.second, finalBest.first, startedAt, forceXraySwitch = true)
            if (switched) {
                log("Simple active balancer final selected lowest ping • ${simpleDisplayName(finalBest.first)} • ${finalBest.second.pingMs}ms")
                connected = finalBest
            }
        }
        if (connected != null) startSimpleAutoSwitchLoop()
        connected
    }


    private fun startSimpleBackgroundShuffleScan(configs: List<ServerConfig>, statusPrefix: String) {
        if (prefs.getBoolean("simpleServerlessEnabled", false)) return
        if (configs.size < 2) return
        simpleBackgroundLatencyJob?.cancel()
        simpleBackgroundLatencyJob = viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                pingSimpleConfigsParallel(configs, parallelism = SIMPLE_FAST_PROBE_PARALLELISM, statusPrefix = statusPrefix)
            }.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                log("Simple background ping refresh failed safely", e)
            }
        }
    }

    private fun startSimpleAutoSwitchLoop() {
        if (prefs.getBoolean("simpleServerlessEnabled", false)) return
        simpleBackgroundLatencyJob?.cancel()
        val app = getApplication<Application>()
        simpleBackgroundLatencyJob = viewModelScope.launch {
            while (isActive && prefs.getBoolean("simpleConnected", false) && prefs.getString("activeMode", "") == "simple_xray" && !prefs.getBoolean("simpleServerlessEnabled", false)) {
                delay(SIMPLE_AUTO_SWITCH_INTERVAL_MS)
                try {
                    val configs = loadSimpleConfigs(serverless = false)
                    if (configs.size < 2) continue
                    val tested = pingSimpleConfigsParallel(configs, parallelism = SIMPLE_FAST_PROBE_PARALLELISM, statusPrefix = "Auto Real Best")
                    val best = tested.firstOrNull { it.second.pingMs != null } ?: continue
                    val current = currentSimpleConfigIndex(configs)
                    val ping = best.second.pingMs ?: continue
                    if (best.first != current) {
                        prefs.edit()
                            .putString("simpleStatus", "Auto best: ${simpleDisplayName(best.first)} • ${ping}ms")
                            .putString("status", "Simple auto best switching")
                            .apply()
                        _state.value = loadState()
                        log("Simple active balancer selected latest lowest ping • from=$current to=${best.first} • ${ping}ms")
                        runCatching { startSelectedSimpleConfig(app, best.second, best.first, System.currentTimeMillis(), forceXraySwitch = true) }
                            .onFailure { e ->
                                if (e is kotlinx.coroutines.CancellationException) throw e
                                prefs.edit()
                                    .putString("simpleStatus", "Auto best switch skipped safely: ${safeThrowableLabel(e)}")
                                    .putString("status", "Simple auto best safe skip")
                                    .apply()
                                log("Simple auto switch skipped safely", e)
                            }
                    } else {
                        prefs.edit()
                            .putString("simpleStatus", "Best selected: ${simpleDisplayName(best.first)} • ${ping}ms")
                            .putString("status", "Simple best selected")
                            .apply()
                        _state.value = loadState()
                    }
                } catch (e: Throwable) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    prefs.edit()
                        .putString("simpleStatus", "Auto best refresh skipped safely: ${safeThrowableLabel(e)}")
                        .putString("status", "Simple auto best safe skip")
                        .apply()
                    log("Simple auto best loop recovered", e)
                    _state.value = loadState()
                }
            }
        }
    }


    fun prepareSimpleNextHealthyUi() {
        prefs.edit()
            .putBoolean("simpleConnecting", false)
            .putString("simpleStatus", "Next Healthy removed • Simple uses Active Balancer")
            .putString("status", "Simple Active Balancer")
            .apply()
        _state.value = loadState()
        log("Next Healthy ignored: Simple normal now uses Active Balancer")
    }

    fun simpleConnectNextHealthyAfterPermission() {
        prefs.edit()
            .putBoolean("simpleConnecting", false)
            .putString("simpleStatus", "Next Healthy removed • Active Balancer switches automatically")
            .putString("status", "Simple Active Balancer")
            .apply()
        _state.value = loadState()
        log("Next Healthy action ignored: Active Balancer handles switching")
    }


    private fun resetSimpleHealthyMemory() {
        simpleBackgroundLatencyJob?.cancel()
        simpleBackgroundLatencyJob = null
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
            .putString("simpleLatencyScannerStatus", "")
            .apply()
    }

    private data class SimpleLatencyEntry(val pingMs: Long, val at: Long)

    private fun checkSimpleNormalBackgroundLatency() {
        // The Simple active balancer has its own scan/auto-switch loop;
        // do not start the old background latency scanner here.
        return
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
        val keptEntries = cache.entries
            .sortedWith(compareBy<Map.Entry<String, SimpleLatencyEntry>> { it.value.pingMs }.thenBy { it.key })
            .take(SIMPLE_LATENCY_CACHE_MAX_ENTRIES)
        val text = keptEntries
            .sortedBy { it.key }
            .joinToString("\n") { (id, entry) -> "$id|${entry.pingMs}|${entry.at}" }
        prefs.edit()
            .putString("simpleLatencyCache", text)
            .putLong("simpleLatencyCacheUpdatedAt", now)
            .putInt("simpleLatencyHealthyCount", keptEntries.size)
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
        val latencyRaw = prefs.getString("simpleLatencyCache", "").orEmpty()
        val savedIndex = prefs.getInt("simpleBestIndex", -1)
        val savedPing = prefs.getLong("simpleBestPingMs", -1L)
        val savedId = prefs.getString("simpleBestId", "").orEmpty()
        val configMarker = "${configs.size}:${configs.firstOrNull()?.id}:${configs.lastOrNull()?.id}"
        val key = "$configMarker|${latencyRaw.length}|${latencyRaw.hashCode()}|$savedIndex|$savedPing|$savedId"
        if (key == cachedSimpleItemsKey) return cachedSimpleItems
        val cache = loadSimpleLatencyCache()
        val selectedIndex = currentSimpleConfigIndex(configs)
        val result = configs.mapIndexed { index, server ->
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
        cachedSimpleItemsKey = key
        cachedSimpleItems = result
        return result
    }


    private fun buildSimpleCustomConfigItems(configs: List<ServerConfig>): List<SimpleConfigUiItem> {
        if (configs.isEmpty()) return emptyList()
        val cache = loadSimpleLatencyCache()
        val savedId = prefs.getString("simpleBestId", "").orEmpty()
        val savedPing = prefs.getLong("simpleBestPingMs", -1L)
        return configs.mapIndexed { index, server ->
            val cachePing = cache[server.id]?.pingMs
            val fallbackPing = savedPing.takeIf { savedId.isNotBlank() && server.id == savedId && it > 0L }
            val ping = cachePing ?: fallbackPing
            val label = server.name.trim()
                .takeIf { it.isNotBlank() }
                ?: server.id.trim().takeIf { it.isNotBlank() }
                ?: "Config ${index + 1}"
            SimpleConfigUiItem(
                index = index,
                label = label,
                pingLabel = ping?.let { "${it}ms" } ?: "—",
                selected = savedId.isNotBlank() && server.id == savedId,
                hasPing = ping != null
            )
        }
    }


    private suspend fun simpleFastProbe(server: ServerConfig): ServerConfig = withContext(Dispatchers.IO) {
        if (server.raw.isBlank()) return@withContext server.copy(pingMs = null, error = "Empty config")
        withTimeoutOrNull(SIMPLE_REAL_XRAY_TEST_TIMEOUT_MS) {
            simplePing.pingQuick(server)
        } ?: server.copy(pingMs = null, error = "Real Xray test timeout")
    }

    private data class SimpleProbeOutcome(val ms: Long? = null, val error: String? = null)

    private fun tcpConnectWithDeadline(host: String, port: Int, timeoutMs: Int): SimpleProbeOutcome {
        val socket = Socket()
        val result = AtomicReference<SimpleProbeOutcome?>(null)
        val thread = Thread {
            val outcome = runCatching {
                val started = System.nanoTime()
                socket.soTimeout = timeoutMs
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                SimpleProbeOutcome(ms = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L))
            }.getOrElse { e ->
                SimpleProbeOutcome(error = (e.message ?: e.javaClass.simpleName).take(120))
            }
            result.set(outcome)
        }.apply {
            isDaemon = true
            name = "simple-fast-probe"
        }
        thread.start()
        thread.join((timeoutMs + 350).toLong())
        result.get()?.let { completed ->
            runCatching { socket.close() }
            return completed
        }
        runCatching { socket.close() }
        return SimpleProbeOutcome(error = SocketTimeoutException("Probe timeout").message ?: "Probe timeout")
    }

    private fun simpleProbeTarget(server: ServerConfig): Pair<String, Int>? {
        val directHost = server.host?.trim().orEmpty()
        val directPort = (server.port ?: -1).takeIf { it in 1..65535 }
        if (directHost.isNotBlank() && directPort != null) return directHost to directPort
        val raw = server.raw.replace("﻿", "").trim().substringBefore('#')
        return runCatching {
            when {
                raw.startsWith("vmess://", ignoreCase = true) -> {
                    val payload = raw.substringAfter("://")
                    val json = String(Base64.decode(payload, Base64.DEFAULT), Charsets.UTF_8)
                    val o = JSONObject(json)
                    val host = o.optString("add", "").trim()
                    val port = o.optInt("port", 443).takeIf { it in 1..65535 } ?: 443
                    if (host.isBlank()) null else host to port
                }
                raw.startsWith("ss://", ignoreCase = true) -> {
                    val after = raw.removePrefix("ss://")
                    val hostPort = if ('@' in after) after.substringAfter('@') else after
                    val host = hostPort.substringBefore(':').trim()
                    val port = hostPort.substringAfter(':', "443").substringBefore('?').toIntOrNull()?.takeIf { it in 1..65535 } ?: 443
                    if (host.isBlank()) null else host to port
                }
                raw.contains("://") -> {
                    val uri = URI(raw)
                    val host = uri.host?.trim().orEmpty()
                    val port = uri.port.takeIf { it in 1..65535 } ?: 443
                    if (host.isBlank()) null else host to port
                }
                else -> null
            }
        }.getOrNull()
    }

    private suspend fun pingSimpleConfigsParallel(
        configs: List<ServerConfig>,
        parallelism: Int = 20,
        statusPrefix: String = "Ping All"
    ): List<Pair<Int, ServerConfig>> = coroutineScope {
        val safeParallelism = parallelism.coerceIn(1, SIMPLE_FAST_PROBE_PARALLELISM)
        val total = configs.size
        val order = configs.withIndex().filter { !it.value.raw.trim().startsWith("{") }.toList().shuffled()
        val results = ArrayList<Pair<Int, ServerConfig>>(total)
        val latencyCache = loadSimpleLatencyCache().toMutableMap()
        for (batch in order.chunked(safeParallelism)) {
            val tested = batch.map { indexed ->
                async(Dispatchers.IO) {
                    indexed.index to runCatching { simpleFastProbe(indexed.value) }
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
        val serverIp: String = "127.0.0.1",
        val serverPort: String = "9992",
        val httpVersion: String = "1.1",
        val userAgent: String = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:132.0) Gecko/20100101 Firefox/132.0"
    )

    fun prepareNipoConnectUi(): Boolean {
        val profile = currentNipoProfileFromPrefs()
        val yaml = buildNipoYaml(profile)
        val parsedEndpoint = parseNipoEndpoint(yaml)
        val endpoint = profile.serverIp.ifBlank { parsedEndpoint.first } to (profile.serverPort.toIntOrNull()?.coerceIn(1, 65535) ?: parsedEndpoint.second)
        if (endpoint.first.isBlank()) {
            prefs.edit()
                .putBoolean("nipoConnecting", false)
                .putBoolean("nipoConnected", false)
                .putString("nipoStatus", "NipoVPN profile error: server IP is empty")
                .putString("status", "NipoVPN profile error")
                .putString("activeMode", "idle")
                .putLong("startedAt", 0L)
                .apply()
            _state.value = loadState()
            return false
        }
        prefs.edit()
            .putBoolean("nipoConnecting", true)
            .putBoolean("nipoConnected", false)
            .putString("nipoConfigYaml", yaml)
            .putString("nipoStatus", "Starting NipoVPN VPN MODE...")
            .putString("status", "NipoVPN starting")
            .putString("activeMode", "nipo")
            .putLong("startedAt", System.currentTimeMillis())
            .putString("nipoServerAddress", endpoint.first)
            .putInt("nipoServerPort", endpoint.second)
            .apply()
        _state.value = loadState()
        return true
    }


    private fun fragmentProfilesArray(): JSONArray = runCatching {
        JSONArray(prefs.getString("fragmentProfilesJson", "[]").orEmpty().ifBlank { "[]" })
    }.getOrElse { JSONArray() }

    private fun fragmentProfileNames(): List<String> {
        val arr = fragmentProfilesArray()
        val names = (0 until arr.length()).mapNotNull { index ->
            arr.optJSONObject(index)?.optString("name")?.trim()?.takeIf { it.isNotBlank() }
        }.distinctBy { it.lowercase(Locale.US) }
        return names.ifEmpty { listOf("Default") }
    }

    private fun fragmentProfilePings(): Map<String, Long> {
        val arr = fragmentProfilesArray()
        val out = linkedMapOf<String, Long>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val name = obj.optString("name").trim()
            val ping = if (obj.has("pingMs")) obj.optLong("pingMs", -1L) else -1L
            if (name.isNotBlank() && ping >= 0L) out[name] = ping
        }
        val selected = prefs.getString("fragmentSelectedProfile", "Default").orEmpty().ifBlank { "Default" }
        val currentPing = prefs.getLong("fragmentPingMs", -1L)
        if (currentPing >= 0L) out[selected] = currentPing
        return out
    }

    private fun fragmentProfileFromPrefs(name: String): JSONObject = JSONObject().apply {
        put("name", name.ifBlank { "Default" })
        put("address", prefs.getString("fragmentAddress", "").orEmpty())
        put("config", prefs.getString("fragmentConfigInput", "").orEmpty())
        put("packets", prefs.getString("fragmentPackets", "tlshello").orEmpty().ifBlank { "tlshello" })
        put("lengths", prefs.getString("fragmentLengths", "3-5,6-8,10-20").orEmpty().ifBlank { "3-5,6-8,10-20" })
        put("delays", prefs.getString("fragmentDelays", "1-2,5-6,10-20").orEmpty().ifBlank { "1-2,5-6,10-20" })
        put("maxSplit", prefs.getString("fragmentMaxSplit", "64").orEmpty().ifBlank { "64" })
        put("pingMs", prefs.getLong("fragmentPingMs", -1L))
    }

    private fun fragmentProfileObject(name: String): JSONObject? {
        val target = name.trim()
        val arr = fragmentProfilesArray()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optString("name").equals(target, ignoreCase = true)) return JSONObject(obj.toString())
        }
        return if (target.equals("Default", ignoreCase = true)) fragmentProfileFromPrefs("Default") else null
    }

    private fun putFragmentProfileObject(profile: JSONObject) {
        val name = profile.optString("name").trim().ifBlank { "Default" }
        profile.put("name", name)
        val arr = fragmentProfilesArray()
        val out = JSONArray()
        var replaced = false
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optString("name").equals(name, ignoreCase = true)) {
                out.put(profile)
                replaced = true
            } else {
                out.put(obj)
            }
        }
        if (!replaced) out.put(profile)
        prefs.edit()
            .putString("fragmentProfilesJson", out.toString())
            .putString("fragmentSelectedProfile", name)
            .apply()
    }

    private fun uniqueFragmentProfileName(rawName: String, excluding: String = ""): String {
        val baseName = rawName.trim().ifBlank { "Profile ${fragmentProfileNames().size + 1}" }.take(42)
        val existing = fragmentProfileNames()
            .filterNot { it.equals(excluding, ignoreCase = true) }
            .map { it.lowercase(Locale.US) }
            .toSet()
        if (baseName.lowercase(Locale.US) !in existing) return baseName
        var index = 2
        while (true) {
            val candidate = "$baseName $index".take(48)
            if (candidate.lowercase(Locale.US) !in existing) return candidate
            index++
        }
    }

    private fun emptyFragmentProfile(name: String): JSONObject = JSONObject().apply {
        put("name", name.ifBlank { "Default" })
        put("address", "")
        put("config", "")
        put("packets", "tlshello")
        put("lengths", "3-5,6-8,10-20")
        put("delays", "1-2,5-6,10-20")
        put("maxSplit", "64")
        put("pingMs", -1L)
    }

    private fun applyFragmentProfile(profile: JSONObject, status: String) {
        val name = profile.optString("name").trim().ifBlank { "Default" }
        val config = profile.optString("config", "")
        val address = profile.optString("address", "")
        val effective = address.ifBlank { fragmentTargetFromRaw(config)?.first.orEmpty() }
        prefs.edit()
            .putString("fragmentSelectedProfile", name)
            .putString("fragmentConfigInput", config)
            .putString("fragmentAddress", address)
            .putString("fragmentEffectiveAddress", effective)
            .putString("fragmentPackets", profile.optString("packets", "tlshello").ifBlank { "tlshello" })
            .putString("fragmentLengths", profile.optString("lengths", "3-5,6-8,10-20").ifBlank { "3-5,6-8,10-20" })
            .putString("fragmentDelays", profile.optString("delays", "1-2,5-6,10-20").ifBlank { "1-2,5-6,10-20" })
            .putString("fragmentMaxSplit", profile.optString("maxSplit", "64").ifBlank { "64" })
            .putLong("fragmentPingMs", profile.optLong("pingMs", -1L))
            .putString("fragmentStatus", status)
            .apply()
    }

    private fun saveSelectedFragmentProfileFromPrefs() {
        val name = prefs.getString("fragmentSelectedProfile", "Default").orEmpty().ifBlank { "Default" }
        putFragmentProfileObject(fragmentProfileFromPrefs(name))
    }

    fun addFragmentProfile(name: String) {
        val clean = uniqueFragmentProfileName(name)
        val profile = emptyFragmentProfile(clean)
        putFragmentProfileObject(profile)
        applyFragmentProfile(profile, "Fragment profile added: $clean")
        _state.value = loadState()
    }

    fun renameFragmentProfile(oldName: String, newName: String) {
        val oldClean = oldName.trim().ifBlank { prefs.getString("fragmentSelectedProfile", "Default").orEmpty().ifBlank { "Default" } }
        val targetName = newName.trim()
        if (targetName.isBlank() || targetName.equals(oldClean, ignoreCase = true)) return
        val clean = uniqueFragmentProfileName(targetName, excluding = oldClean)
        val profile = fragmentProfileObject(oldClean) ?: fragmentProfileFromPrefs(oldClean)
        profile.put("name", clean)
        val arr = fragmentProfilesArray()
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (!obj.optString("name").equals(oldClean, ignoreCase = true)) out.put(obj)
        }
        out.put(profile)
        prefs.edit().putString("fragmentProfilesJson", out.toString()).putString("fragmentSelectedProfile", clean).apply()
        applyFragmentProfile(profile, "Fragment profile renamed: $clean")
        _state.value = loadState()
    }

    fun deleteFragmentProfile(name: String) {
        val target = name.trim().ifBlank { return }
        val arr = fragmentProfilesArray()
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (!obj.optString("name").equals(target, ignoreCase = true)) out.put(obj)
        }
        prefs.edit().putString("fragmentProfilesJson", out.toString()).apply()
        val remaining = (0 until out.length()).mapNotNull { index -> out.optJSONObject(index)?.optString("name")?.trim()?.takeIf { it.isNotBlank() } }
        val selected = prefs.getString("fragmentSelectedProfile", "Default").orEmpty().ifBlank { "Default" }
        if (selected.equals(target, ignoreCase = true) || remaining.isEmpty()) {
            val nextName = remaining.firstOrNull() ?: "Default"
            val profile = if (remaining.isEmpty()) emptyFragmentProfile(nextName) else fragmentProfileObject(nextName) ?: emptyFragmentProfile(nextName)
            if (remaining.isEmpty()) putFragmentProfileObject(profile)
            applyFragmentProfile(profile, "Fragment profile deleted: $target")
        } else {
            prefs.edit().putString("fragmentStatus", "Fragment profile deleted: $target").apply()
        }
        _state.value = loadState()
    }

    fun selectFragmentProfile(name: String) {
        val profile = fragmentProfileObject(name) ?: return
        putFragmentProfileObject(profile)
        applyFragmentProfile(profile, "Fragment profile selected: ${profile.optString("name")}")
        _state.value = loadState()
    }

    fun pingFragmentProfile(name: String) {
        selectFragmentProfile(name)
        pingFragmentConfig()
    }

    fun setFragmentConfigInput(text: String) {
        val edit = prefs.edit().putString("fragmentConfigInput", text)
        detectFragmentSettings(text)?.let { detected ->
            edit.putString("fragmentPackets", detected.packets)
            edit.putString("fragmentLengths", detected.lengths)
            edit.putString("fragmentDelays", detected.delays)
            edit.putString("fragmentMaxSplit", detected.maxSplit)
            edit.putString("fragmentStatus", "Fragment settings detected from JSON")
        }
        fragmentTargetFromRaw(text.trim())?.first?.let { detectedAddress ->
            edit.putString("fragmentEffectiveAddress", detectedAddress)
        }
        edit.apply()
        saveSelectedFragmentProfileFromPrefs()
        _state.value = loadState()
    }

    fun setFragmentAddress(text: String) {
        prefs.edit().putString("fragmentAddress", text.trim()).apply()
        saveSelectedFragmentProfileFromPrefs()
        _state.value = loadState()
    }

    fun setFragmentPackets(text: String) {
        prefs.edit().putString("fragmentPackets", text).apply()
        saveSelectedFragmentProfileFromPrefs()
        _state.value = loadState()
    }

    fun setFragmentLengths(text: String) {
        prefs.edit().putString("fragmentLengths", text).apply()
        saveSelectedFragmentProfileFromPrefs()
        _state.value = loadState()
    }

    fun setFragmentDelays(text: String) {
        prefs.edit().putString("fragmentDelays", text).apply()
        saveSelectedFragmentProfileFromPrefs()
        _state.value = loadState()
    }

    fun setFragmentMaxSplit(text: String) {
        prefs.edit().putString("fragmentMaxSplit", text.filter { it.isDigit() }.ifBlank { text }).apply()
        saveSelectedFragmentProfileFromPrefs()
        _state.value = loadState()
    }

    fun prepareFragmentConnectUi(): Boolean {
        return runCatching {
            val raw = prefs.getString("fragmentConfigInput", "").orEmpty().trim()
            val json = buildFragmentXrayJson(raw)
            prefs.edit()
                .putBoolean("fragmentConnecting", true)
                .putBoolean("fragmentConnected", false)
                .putString("fragmentStatus", "VLESS/Trojan converted to JSON • finalmask added")
                .putString("fragmentGeneratedJson", json)
                .putString("status", "Fragment connecting")
                .putString("activeMode", "fragment")
                .putLong("startedAt", System.currentTimeMillis())
                .apply()
            _state.value = loadState()
            true
        }.getOrElse { e ->
            val msg = e.message ?: e.javaClass.simpleName
            prefs.edit()
                .putBoolean("fragmentConnecting", false)
                .putBoolean("fragmentConnected", false)
                .putString("fragmentStatus", "Fragment config error: $msg")
                .putString("status", "Fragment config error")
                .putString("activeMode", "idle")
                .apply()
            _state.value = loadState()
            false
        }
    }

    fun fragmentConnectAfterPermission() {
        val section = "fragment"
        if (!beginGlobalConnect(section)) return
        viewModelScope.launch {
            val app = getApplication<Application>()
            try {
                val raw = prefs.getString("fragmentConfigInput", "").orEmpty().trim()
                val json = buildFragmentXrayJson(raw)
                prefs.edit()
                    .putBoolean("fragmentStartInProgress", true)
                    .putBoolean("fragmentConnecting", true)
                    .putBoolean("fragmentConnected", false)
                    .putString("fragmentGeneratedJson", json)
                    .putString("fragmentStatus", "Fragment connecting • JSON + finalmask ready")
                    .putString("status", "Fragment connecting")
                    .putString("activeMode", "fragment")
                    .putLong("startedAt", System.currentTimeMillis())
                    .apply()
                _state.value = loadState()

                // Stop previous core, but keep Fragment UI in connecting state during cleanup.
                stopAllCoresBeforeConnect(app, section)
                prefs.edit()
                    .putBoolean("fragmentStartInProgress", true)
                    .putBoolean("fragmentConnecting", true)
                    .putBoolean("fragmentConnected", false)
                    .putString("fragmentGeneratedJson", json)
                    .putString("fragmentStatus", "Fragment connecting • starting Xray")
                    .putString("status", "Fragment connecting")
                    .putString("activeMode", "fragment")
                    .putLong("startedAt", System.currentTimeMillis())
                    .apply()
                _state.value = loadState()

                val intent = Intent(app, RkhVpnService::class.java)
                    .setAction(RkhVpnService.ACTION_START)
                    .putExtra(RkhVpnService.EXTRA_RAW_CONFIG, json)
                    .putExtra(RkhVpnService.EXTRA_SERVER_NAME, "SIMORGH Fragment")
                val result = safeStartCoreService(app, intent)
                result.onFailure { e ->
                    prefs.edit()
                        .putBoolean("fragmentStartInProgress", false)
                        .putBoolean("fragmentConnecting", false)
                        .putBoolean("fragmentConnected", false)
                        .putString("fragmentStatus", "Fragment start failed: ${e.message ?: e.javaClass.simpleName}")
                        .putString("status", "Fragment start failed")
                        .putString("activeMode", "idle")
                        .putLong("startedAt", 0L)
                        .apply()
                    log("Fragment service start failed", e)
                    _state.value = loadState()
                }
                if (result.isSuccess) {
                    prefs.edit()
                        .putBoolean("fragmentStartInProgress", false)
                        .putBoolean("fragmentConnecting", true)
                        .putBoolean("fragmentConnected", false)
                        .putString("fragmentStatus", "Fragment Xray starting...")
                        .putString("status", "Fragment connecting")
                        .putString("activeMode", "fragment")
                        .apply()
                    _state.value = loadState()
                }
            } catch (e: Throwable) {
                prefs.edit()
                    .putBoolean("fragmentStartInProgress", false)
                    .putBoolean("fragmentConnecting", false)
                    .putBoolean("fragmentConnected", false)
                    .putString("fragmentStatus", "Fragment error: ${e.message ?: e.javaClass.simpleName}")
                    .putString("status", "Fragment error")
                    .putString("activeMode", "idle")
                    .putLong("startedAt", 0L)
                    .apply()
                log("Safe Fragment connect error", e)
                _state.value = loadState()
            } finally {
                delay(1_500L)
                prefs.edit().putBoolean("fragmentStartInProgress", false).apply()
                endGlobalConnect(section)
            }
        }
    }


    fun fragmentDisconnect() {
        val now = System.currentTimeMillis()
        if (now - lastFragmentStopRequestAt < 900L) {
            log("Fragment duplicate disconnect tap ignored safely")
            return
        }
        lastFragmentStopRequestAt = now
        val app = getApplication<Application>()
        val intent = Intent(app, RkhVpnService::class.java)
            .setAction(RkhVpnService.ACTION_STOP)
            .putExtra(RkhVpnService.EXTRA_STOP_SOURCE, "fragment")
        prefs.edit()
            .putBoolean("fragmentStartInProgress", false)
            .putBoolean("fragmentConnecting", false)
            .putString("fragmentStatus", "Fragment disconnecting...")
            .putString("status", "Fragment disconnecting")
            .apply()
        _state.value = loadState()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { app.startService(intent) }
                .onFailure { e ->
                    log("Failed to deliver Fragment ACTION_STOP safely", e)
                    runCatching { app.stopService(Intent(app, RkhVpnService::class.java)) }
                        .onFailure { fallback -> log("Fallback stopService for Fragment failed", fallback) }
                }
            kotlinx.coroutines.delay(450L)
            prefs.edit()
                .putBoolean("fragmentStartInProgress", false)
                .putBoolean("fragmentConnecting", false)
                .putBoolean("fragmentConnected", false)
                .putString("fragmentStatus", "Fragment disconnected")
                .putString("status", "Fragment disconnected")
                .putString("activeMode", "idle")
                .putLong("startedAt", 0L)
                .putLong("downloadKbps", 0L)
                .putLong("uploadKbps", 0L)
                .apply()
            _state.value = loadState()
            log("Fragment disconnect requested")
        }
    }


    fun pingFragmentConfig() {
        viewModelScope.launch(Dispatchers.IO) {
            val raw = prefs.getString("fragmentConfigInput", "").orEmpty().trim()
            prefs.edit()
                .putString("fragmentStatus", "Fragment ping checking...")
                .putLong("fragmentPingMs", -1L)
                .apply()
            _state.value = loadState()
            val ping = runCatching {
                val json = buildFragmentXrayJson(raw)
                fragmentXrayLatencyMs(json, 9000)
            }.getOrNull() ?: fragmentDirectTargetLatencyMs(raw, 7000)
            prefs.edit()
                .putLong("fragmentPingMs", ping ?: -1L)
                .putString("fragmentStatus", if (ping != null) "Ping OK • ${ping}ms" else "Ping failed")
                .apply()
            saveSelectedFragmentProfileFromPrefs()
            _state.value = loadState()
        }
    }


    private fun fragmentXrayLatencyMs(finalJson: String, timeoutMs: Int): Long? {
        var process: Process? = null
        var configFile: File? = null
        return runCatching {
            val app = getApplication<Application>()
            val xray = NativeBinaryManager(app).prepare("xray")
            val socksPort = freeLocalPort()
            val workDir = File(app.cacheDir, "fragment-xray-ping").apply { mkdirs() }
            pruneRuntimeDir(workDir)
            val runtimeConfig = XrayBinaryConfigBuilder.socksConfigFromRaw(finalJson, socksPort)
            configFile = File(workDir, "fragment_ping_${System.currentTimeMillis()}.json").apply { writeText(runtimeConfig) }
            process = ProcessBuilder(listOf(xray.absolutePath, "run", "-config", configFile!!.absolutePath))
                .directory(workDir)
                .redirectErrorStream(true)
                .start()
            Thread.sleep(900L)
            val exit = runCatching { process?.exitValue() }.getOrNull()
            if (exit != null) return null
            val targets = listOf("www.gstatic.com", "cp.cloudflare.com", "cloudflare.com")
            for (host in targets) {
                val measured = runCatching {
                    val started = System.nanoTime()
                    Socket().use { socket ->
                        socket.tcpNoDelay = true
                        socket.soTimeout = timeoutMs
                        socket.connect(InetSocketAddress("127.0.0.1", socksPort), timeoutMs)
                        // Real Fragment ping: traffic goes through temporary Xray using the final generated JSON.
                        socks5Connect(socket, host, 443)
                        verifyFragmentTlsOverXray(socket, host, timeoutMs)
                    }
                    ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L)
                }.getOrNull()
                if (measured != null) return@runCatching measured
            }
            null
        }.getOrNull().also {
            process?.destroy()
            runCatching { process?.destroyForcibly() }
            runCatching { configFile?.delete() }
        }
    }

    private fun verifyFragmentTlsOverXray(rawSocket: Socket, host: String, timeoutMs: Int) {
        val tls = SSLContext.getDefault().socketFactory.createSocket(rawSocket, host, 443, true) as SSLSocket
        tls.soTimeout = timeoutMs
        tls.sslParameters = tls.sslParameters.apply {
            serverNames = listOf(SNIHostName(host))
            endpointIdentificationAlgorithm = "HTTPS"
        }
        tls.startHandshake()
        val request = "HEAD /generate_204 HTTP/1.1\r\nHost: $host\r\nUser-Agent: SIMORGH-Fragment-Ping/1.0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII)
        tls.getOutputStream().write(request)
        tls.getOutputStream().flush()
        val line = readHttpStatusLine(tls)
        if (!line.startsWith("HTTP/", ignoreCase = true)) error("No HTTPS response through Fragment route")
    }

    private fun fragmentDirectTargetLatencyMs(rawInput: String, timeoutMs: Int): Long? {
        val target = fragmentTargetFromRaw(rawInput) ?: return null
        val host = prefs.getString("fragmentAddress", "").orEmpty().trim().ifBlank { target.first }
        val port = target.second.coerceIn(1, 65535)
        if (host.isBlank()) return null
        return runCatching {
            val started = System.nanoTime()
            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.soTimeout = timeoutMs
                socket.connect(InetSocketAddress(host, port), timeoutMs)
            }
            ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L)
        }.getOrNull()
    }

    private data class FragmentDetectedSettings(
        val packets: String,
        val lengths: String,
        val delays: String,
        val maxSplit: String
    )

    private fun detectFragmentSettings(rawInput: String): FragmentDetectedSettings? {
        val raw = rawInput.replace("﻿", "").trim()
        if (!raw.startsWith("{")) return null
        return runCatching {
            val settings = JSONObject(raw)
                .optJSONObject("finalmask")
                ?.optJSONArray("tcp")
                ?.optJSONObject(0)
                ?.optJSONObject("settings")
                ?: return@runCatching null
            FragmentDetectedSettings(
                packets = settings.optString("packets", "tlshello").ifBlank { "tlshello" },
                lengths = jsonArrayToCsv(settings.optJSONArray("lengths"), "3-5,6-8,10-20"),
                delays = jsonArrayToCsv(settings.optJSONArray("delays"), "1-2,5-6,10-20"),
                maxSplit = settings.optInt("maxSplit", 64).coerceIn(1, 1024).toString()
            )
        }.getOrNull()
    }

    private fun jsonArrayToCsv(arr: JSONArray?, fallback: String): String {
        if (arr == null || arr.length() == 0) return fallback
        return (0 until arr.length()).joinToString(",") { index -> arr.optString(index).trim() }.ifBlank { fallback }
    }

    private fun buildFragmentXrayJson(rawInput: String): String {
        val raw = rawInput.replace("﻿", "").trim()
        if (raw.isBlank()) error("Paste a VLESS or Trojan config")

        val base = when {
            raw.startsWith("{") -> JSONObject(raw)
            raw.startsWith("vless://", ignoreCase = true) || raw.startsWith("trojan://", ignoreCase = true) -> {
                // کاربر معمولاً لینک خام VLESS/Trojan وارد می‌کند.
                // اینجا اول لینک با parser خود Xray به JSON معتبر تبدیل می‌شود،
                // بعد finalmask با JSONObject.put به ریشه JSON اضافه می‌شود؛
                // هیچ string-concat دستی انجام نمی‌شود که parse JSON خراب شود.
                JSONObject(XrayConfigBuilder.configFromRaw(raw))
            }
            else -> error("Paste a VLESS or Trojan config")
        }

        applyFragmentAddressOverride(base)
        base.put("finalmask", fragmentFinalMaskJson())

        val generated = base.toString(2)
        // Validate once more after finalmask insertion so a broken JSON never reaches Xray.
        JSONObject(generated)
        return generated
    }

    private fun applyFragmentAddressOverride(base: JSONObject) {
        val manualAddress = prefs.getString("fragmentAddress", "").orEmpty().trim()
        var effectiveAddress = ""

        val outbounds = base.optJSONArray("outbounds")
        if (outbounds != null) {
            for (i in 0 until outbounds.length()) {
                val outbound = outbounds.optJSONObject(i) ?: continue
                val protocol = outbound.optString("protocol").lowercase(Locale.US)
                if (protocol == "vless") {
                    val vnext = outbound.optJSONObject("settings")?.optJSONArray("vnext")?.optJSONObject(0)
                    if (vnext != null) {
                        val current = vnext.optString("address").trim()
                        val chosen = manualAddress.ifBlank { current }
                        if (chosen.isNotBlank()) {
                            vnext.put("address", chosen)
                            effectiveAddress = chosen
                            break
                        }
                    }
                } else if (protocol == "trojan") {
                    val server = outbound.optJSONObject("settings")?.optJSONArray("servers")?.optJSONObject(0)
                    if (server != null) {
                        val current = server.optString("address").trim()
                        val chosen = manualAddress.ifBlank { current }
                        if (chosen.isNotBlank()) {
                            server.put("address", chosen)
                            effectiveAddress = chosen
                            break
                        }
                    }
                }
            }
        }

        prefs.edit().putString("fragmentEffectiveAddress", effectiveAddress).apply()
    }

    private fun fragmentFinalMaskJson(): JSONObject {
        return JSONObject().apply {
            put("tcp", JSONArray().put(JSONObject().apply {
                put("type", "fragment")
                put("settings", JSONObject().apply {
                    put("packets", prefs.getString("fragmentPackets", "tlshello").orEmpty().ifBlank { "tlshello" })
                    put("lengths", JSONArray(fragmentCsvValues(prefs.getString("fragmentLengths", "3-5,6-8,10-20").orEmpty())))
                    put("delays", JSONArray(fragmentCsvValues(prefs.getString("fragmentDelays", "1-2,5-6,10-20").orEmpty())))
                    put("maxSplit", (prefs.getString("fragmentMaxSplit", "64").orEmpty().toIntOrNull() ?: 64).coerceIn(1, 1024))
                })
            }))
        }
    }


    private fun fragmentCsvValues(text: String): List<String> {
        return text.split(',', '\n', ';')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .ifEmpty { listOf("3-5") }
    }

    private fun fragmentTargetFromRaw(rawInput: String): Pair<String, Int>? {
        val raw = rawInput.trim()
        return runCatching {
            if (raw.startsWith("{")) {
                val base = JSONObject(raw)
                val outs = base.optJSONArray("outbounds") ?: return@runCatching null
                for (i in 0 until outs.length()) {
                    val out = outs.optJSONObject(i) ?: continue
                    when (out.optString("protocol").lowercase(Locale.US)) {
                        "vless" -> {
                            val vnext = out.optJSONObject("settings")?.optJSONArray("vnext")?.optJSONObject(0)
                            val address = vnext?.optString("address").orEmpty()
                            val port = vnext?.optInt("port", 443) ?: 443
                            if (address.isNotBlank()) return@runCatching (address to port)
                        }
                        "trojan" -> {
                            val server = out.optJSONObject("settings")?.optJSONArray("servers")?.optJSONObject(0)
                            val address = server?.optString("address").orEmpty()
                            val port = server?.optInt("port", 443) ?: 443
                            if (address.isNotBlank()) return@runCatching (address to port)
                        }
                    }
                }
                null
            } else {
                val uri = URI(raw.substringBefore('#'))
                uri.host.orEmpty() to (if (uri.port in 1..65535) uri.port else 443)
            }
        }.getOrNull()
    }

    fun nipoConnectAfterPermission() {
        val section = "nipo"
        if (!beginGlobalConnect(section)) return
        viewModelScope.launch {
            val app = getApplication<Application>()
            try {
                val profile = currentNipoProfileFromPrefs()
                val yaml = buildNipoYaml(profile)
                val parsedEndpoint = parseNipoEndpoint(yaml)
                val endpoint = profile.serverIp.ifBlank { parsedEndpoint.first } to (profile.serverPort.toIntOrNull()?.coerceIn(1, 65535) ?: parsedEndpoint.second)
                if (endpoint.first.isBlank()) {
                    prefs.edit()
                        .putBoolean("nipoConnecting", false)
                        .putBoolean("nipoConnected", false)
                        .putString("nipoStatus", "NipoVPN profile error: server IP is empty")
                        .putString("status", "NipoVPN profile error")
                        .putString("activeMode", "idle")
                        .putLong("startedAt", 0L)
                        .apply()
                    _state.value = loadState()
                    endGlobalConnect(section)
                    return@launch
                }
                stopAllCoresBeforeConnect(app, section)
                prefs.edit()
                    .putBoolean("nipoConnecting", true)
                    .putBoolean("nipoConnected", false)
                    .putString("nipoConfigYaml", yaml)
                    .putString("nipoStatus", "NipoVPN connecting • ${profile.name} • ${endpoint.first}:${endpoint.second}")
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
                runCatching { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) app.startForegroundService(intent) else app.startService(intent) }
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
            } catch (e: Throwable) {
                prefs.edit()
                    .putBoolean("nipoConnecting", false)
                    .putBoolean("nipoConnected", false)
                    .putString("nipoStatus", "NipoVPN start error: ${e.message ?: e.javaClass.simpleName}")
                    .putString("status", "NipoVPN start error")
                    .putString("activeMode", "idle")
                    .putLong("startedAt", 0L)
                    .apply()
                log("Safe NipoVPN connect error", e)
                _state.value = loadState()
            } finally {
                delay(1_500L)
                endGlobalConnect(section)
            }
        }
    }

    fun nipoDisconnect() {
        val now = System.currentTimeMillis()
        if (now - lastNipoStopRequestAt < 900L) {
            log("NipoVPN duplicate disconnect tap ignored safely")
            return
        }
        lastNipoStopRequestAt = now

        val app = getApplication<Application>()
        val stopIntent = Intent(app, RkhVpnService::class.java)
            .setAction(RkhVpnService.ACTION_STOP)
            .putExtra(RkhVpnService.EXTRA_STOP_SOURCE, "nipo")

        prefs.edit()
            .putBoolean("nipoConnecting", false)
            .putString("nipoStatus", "NipoVPN disconnecting...")
            .putString("status", "NipoVPN disconnecting...")
            .apply()
        _state.value = loadState()

        viewModelScope.launch(Dispatchers.IO) {
            runCatching { app.startService(stopIntent) }
                .onFailure { e ->
                    log("Failed to deliver NipoVPN ACTION_STOP safely", e)
                    runCatching { app.stopService(Intent(app, RkhVpnService::class.java)) }
                        .onFailure { fallback -> log("Fallback stopService for NipoVPN failed", fallback) }
                }

            kotlinx.coroutines.delay(1_200L)
            // NipoVPN runs inside RkhVpnService/:vpncore; after the graceful ACTION_STOP,
            // also ask Android to stop the service so a missed/late native teardown cannot
            // leave the VPN key active behind the UI.
            runCatching { app.stopService(Intent(app, RkhVpnService::class.java)) }
                .onFailure { fallback -> log("NipoVPN final stopService fallback failed", fallback) }
            app.getSharedPreferences("rkh_vpn_state", Context.MODE_PRIVATE or Context.MODE_MULTI_PROCESS)
                .edit()
                .putBoolean("serviceConnected", false)
                .commit()
            prefs.edit()
                .putBoolean("nipoConnecting", false)
                .putBoolean("nipoConnected", false)
                .putString("nipoStatus", "NipoVPN disconnected")
                .putString("status", "NipoVPN disconnected")
                .putString("activeMode", "idle")
                .putLong("startedAt", 0L)
                .putLong("downloadKbps", 0L)
                .putLong("uploadKbps", 0L)
                .commit()
            _state.value = loadState()
            log("NipoVPN disconnect requested and final stop fallback completed")
        }
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
            .putString("nipoStatus", "NipoVPN raw YAML saved • ${endpoint.first}:${endpoint.second}")
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

    private fun defaultNipoConfigYaml(): String = buildNipoYaml(NipoProfile(serverIp = "127.0.0.1", serverPort = "9992"))

    private fun parseNipoEndpoint(yaml: String): Pair<String, Int> {
        val server = Regex("""(?m)^\s*serverIp\s*:\s*[\"']?([^\"'\s#]+)""").find(yaml)?.groupValues?.getOrNull(1)?.trim().orEmpty().ifBlank { "127.0.0.1" }
        val port = Regex("""(?m)^\s*serverPort\s*:\s*(\d+)""").find(yaml)?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceIn(1, 65535) ?: 9992
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
        serverIp = prefs.getString("nipoServerAddress", "").orEmpty().ifBlank { "127.0.0.1" },
        serverPort = prefs.getInt("nipoServerPort", 9992).toString(),
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
        val serverIp = profile.serverIp.ifBlank { "127.0.0.1" }
        val port = profile.serverPort.toIntOrNull()?.coerceIn(1, 65535) ?: 9992
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
            append("  serverIp: ${yamlQuote(serverIp)}\n")
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
        val now = System.currentTimeMillis()
        if (now - lastSimpleStopRequestAt < 900L) {
            log("Simple duplicate disconnect tap ignored safely")
            return
        }
        lastSimpleStopRequestAt = now
        simpleHealthyScanJob?.cancel()
        simpleHealthyScanJob = null
        simpleBackgroundLatencyJob?.cancel()
        simpleBackgroundLatencyJob = null
        val app = getApplication<Application>()
        val intent = Intent(app, RkhVpnService::class.java)
            .setAction(RkhVpnService.ACTION_STOP)
            .putExtra(RkhVpnService.EXTRA_STOP_SOURCE, if (prefs.getBoolean("simpleServerlessEnabled", false)) "simple_serverless" else "simple")
        prefs.edit()
            .putBoolean("simpleConnecting", false)
            .putString("simpleStatus", "Simple XRAY disconnecting...")
            .putString("status", "Simple XRAY disconnecting")
            .apply()
        _state.value = loadState()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                runCatching { app.startService(intent) }
                    .onFailure { e ->
                        log("Failed to stop Simple XRAY service safely", e)
                        runCatching { app.stopService(Intent(app, RkhVpnService::class.java)) }
                            .onFailure { fallback -> log("Fallback stopService for Simple XRAY failed", fallback) }
                    }
                // Do not force stopService immediately after ACTION_STOP. Let the service
                // perform a single in-service teardown path to avoid VpnService/onDestroy races.
                kotlinx.coroutines.delay(650L)
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
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                prefs.edit()
                    .putBoolean("simpleConnecting", false)
                    .putBoolean("simpleConnected", false)
                    .putString("simpleStatus", "Simple disconnect recovered: ${t.message ?: t.javaClass.simpleName}")
                    .putString("status", "Simple disconnect recovered")
                    .putString("activeMode", "idle")
                    .putLong("startedAt", 0L)
                    .putLong("downloadKbps", 0L)
                    .putLong("uploadKbps", 0L)
                    .apply()
                _state.value = loadState()
                log("Simple disconnect coroutine recovered safely", t)
            }
        }
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
                .putString(simpleBodyKey(serverless), encodeSimpleBodyForCache(body))
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
                .putString("simpleStatus", simpleLoadFailureStatus(modeLabel, fallback.size))
                .putInt("simpleConfigCount", fallback.size)
                .apply()
            log("Simple XRAY $modeLabel config load failed • ${safeThrowableLabel(e)}")
            _state.value = loadState()
            fallback
        }
    }

    private fun simpleLoadFailureStatus(modeLabel: String, cachedCount: Int): String {
        return if (cachedCount > 0) {
            "$modeLabel load failed • using cached $cachedCount configs"
        } else {
            "$modeLabel load failed • check internet and try again"
        }
    }

    private fun safeThrowableLabel(t: Throwable): String = t.javaClass.simpleName.ifBlank { "Error" }

    private fun decodeHiddenSimpleSubscriptionUrl(): String {
        // Subscription URL is intentionally not stored as a plain string or Base64 blob.
        // This only hides it from casual source/decompile search; any URL used by an APK can still be recovered at runtime.
        val shield = byteArrayOf(80, -101, -68, -108, -18, 69, 126, 95, -125, -76, -62, -18, 12, 54, 102, -105, -93, -43, -11, 27, 53, 96, -58, -80, -115, -83, 65, 30, 119, -117, -96, -46, -13, 124, 68, 47, -125, -1, -36)
        val key = packageKeySeed()
        val bytes = ByteArray(shield.size) { i ->
            (((shield[i].toInt() and 0xFF) xor (key[i % key.size].toInt() and 0xFF) xor ((i * 37 + 91) and 0xFF))).toByte()
        }
        return String(bytes, Charsets.UTF_8)
    }

    private fun packageKeySeed(): ByteArray {
        val app = getApplication<Application>()
        val seed = app.packageName.ifBlank { "com.rkh.simorgh" }
        return seed.toByteArray(Charsets.UTF_8)
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
        return readShieldedAssetText(simpleServerlessAssetKey)
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


    private fun encodeSimpleBodyForCache(body: String): String {
        val clean = body.replace("﻿", "").trim()
        if (clean.isBlank()) return ""
        return runCatching {
            val out = java.io.ByteArrayOutputStream()
            java.util.zip.GZIPOutputStream(out).use { gzip -> gzip.write(clean.toByteArray(Charsets.UTF_8)) }
            "gz64:" + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }.getOrDefault(clean)
    }

    private fun decodeSimpleBodyFromCache(value: String): String {
        val clean = value.trim()
        if (!clean.startsWith("gz64:")) return clean
        return runCatching {
            val bytes = Base64.decode(clean.removePrefix("gz64:"), Base64.NO_WRAP)
            java.util.zip.GZIPInputStream(java.io.ByteArrayInputStream(bytes)).use { gzip ->
                String(gzip.readBytes(), Charsets.UTF_8)
            }
        }.getOrDefault("")
    }

    private fun simpleBodyKey(serverless: Boolean) = if (serverless) "simpleServerlessConfigBody" else "simpleSubscriptionBody"
    private fun simpleCountKey(serverless: Boolean) = if (serverless) "simpleServerlessConfigCount" else "simpleNormalConfigCount"
    private fun simpleUpdatedAtKey(serverless: Boolean) = if (serverless) "simpleServerlessConfigUpdatedAt" else "simpleSubscriptionUpdatedAt"

    private fun loadSimpleConfigs(serverless: Boolean = prefs.getBoolean("simpleServerlessEnabled", false)): List<ServerConfig> {
        val raw = prefs.getString(simpleBodyKey(serverless), "").orEmpty()
        val key = "${serverless}|${raw.length}|${raw.hashCode()}"
        if (key == cachedSimpleConfigKey) return cachedSimpleConfigs
        val cachedBody = decodeSimpleBodyFromCache(raw)
        val result = if (cachedBody.isNotBlank()) {
            parseSimpleConfigs(cachedBody, serverless)
        } else if (serverless) {
            runCatching { parseSimpleConfigs(readBundledServerlessConfig(), serverless = true) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        cachedSimpleConfigKey = key
        cachedSimpleConfigs = result
        return result
    }



    private fun cleanupAppRuntimeCache() {
        val app = getApplication<Application>()
        val now = System.currentTimeMillis()
        // Real Xray health tests must not leave big temporary geo/runtime folders in Android cache.
        // They are disposable, so remove the ping runtime root on app start.
        runCatching { File(app.cacheDir, "xray-ping").deleteRecursively() }
        runCatching { File(app.cacheDir, "xray-empty-assets").deleteRecursively() }
        listOf(
            File(app.cacheDir, "cf-xray-latency")
        ).forEach { pruneRuntimeDir(it, now) }
        runCatching { File(app.filesDir, "native-bin").deleteRecursively() }
        compactCachedSimpleBodies()
    }

    private fun compactCachedSimpleBodies() {
        val edit = prefs.edit()
        var changed = false
        listOf("simpleSubscriptionBody", "simpleServerlessConfigBody").forEach { key ->
            val value = prefs.getString(key, "").orEmpty()
            if (value.isNotBlank() && !value.startsWith("gz64:")) {
                edit.putString(key, encodeSimpleBodyForCache(value))
                changed = true
            }
        }
        if (changed) edit.apply()
    }

    private fun pruneRuntimeDir(dir: File, now: Long = System.currentTimeMillis()) {
        if (!dir.exists()) return
        dir.listFiles()?.forEach { child ->
            if (now - child.lastModified() > RUNTIME_CACHE_KEEP_MS) {
                runCatching { if (child.isDirectory) child.deleteRecursively() else child.delete() }
            }
        }
        val children = dir.listFiles().orEmpty().sortedByDescending { it.lastModified() }
        var totalBytes = 0L
        children.forEachIndexed { index, child ->
            val childBytes = safeFileSize(child)
            totalBytes += childBytes
            if (index >= RUNTIME_CACHE_MAX_FILES || totalBytes > RUNTIME_CACHE_MAX_BYTES) {
                runCatching { if (child.isDirectory) child.deleteRecursively() else child.delete() }
            }
        }
    }

    private fun safeFileSize(file: File): Long {
        return runCatching {
            if (file.isFile) file.length()
            else file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }.getOrDefault(0L)
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

    fun setIspManualRangeMode(enabled: Boolean) {
        prefs.edit().putBoolean("ispManualRangeMode", enabled).apply()
        val count = parseManualIpText(prefs.getString("ispManualRangeText", "").orEmpty()).size
        log("ISP Manual IP/Range mode ${if (enabled) "enabled" else "disabled"} • scanCandidates=$count")
        _state.value = loadState()
    }

    fun setIspManualRangeText(text: String) {
        val candidates = parseManualIpText(text)
        prefs.edit().putString("ispManualRangeText", text).apply()
        log("ISP Manual IP/Range updated • scanCandidates=${candidates.size} • will be scanned by MSP, not treated as clean")
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
        var configFile: File? = null
        return runCatching {
            val app = getApplication<Application>()
            val xray = NativeBinaryManager(app).prepare("xray")
            val socksPort = freeLocalPort()
            val workDir = File(app.cacheDir, "cf-xray-latency").apply { mkdirs() }
            pruneRuntimeDir(workDir)
            val config = buildCfLatencyXrayConfig(cf, cleanIp, socksPort)
            configFile = File(workDir, "cf_latency_${System.currentTimeMillis()}.json").apply { writeText(config) }
            process = ProcessBuilder(listOf(xray.absolutePath, "run", "-config", configFile!!.absolutePath))
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
        }.getOrNull().also {
            process?.destroy()
            runCatching { configFile?.delete() }
        }
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
        val raw = prefs.getString("savedCleanIpPings", "").orEmpty()
        if (raw == cachedSavedPingsKey) return cachedSavedPings
        val parsed = raw.lineSequence()
            .mapNotNull { line ->
                val parts = line.split("=", limit = 2)
                val ip = parts.getOrNull(0)?.trim().orEmpty()
                val ping = parts.getOrNull(1)?.trim()?.toLongOrNull()
                if (ip.isNotBlank() && ping != null && ping >= 0L) ip to ping else null
            }
            .toMap()
        cachedSavedPingsKey = raw
        cachedSavedPings = parsed
        return parsed
    }



    private fun startSyncLoop() {
        syncJob?.cancel()
        syncJob = viewModelScope.launch {
            while (isActive) {
                checkSimpleNormalWatchdog()
                checkSimpleNormalBackgroundLatency()
                val current = loadState()
                _state.value = current
                val fast = current.connecting || current.connected ||
                    current.simpleConnecting || current.simpleConnected ||
                    current.fragmentConnecting || current.fragmentConnected ||
                    current.nipoConnecting || current.nipoConnected ||
                    current.stormDnsConnecting || current.stormDnsConnected
                delay(if (fast) 750L else SYNC_LOOP_INTERVAL_MS)
            }
        }
    }

    private fun checkSimpleNormalWatchdog() {
        // The old Next Healthy watchdog is disabled; active balancer rotation is handled
        // by startSimpleAutoSwitchLoop() after Simple normal connects.
        return
    }

    private fun tunnelSections(): List<String> = listOf("simple", "msp", "fragment", "nipo", "stormdns")

    private fun loadTunnelAppModesBySection(): Map<String, String> {
        return tunnelSections().associateWith { section ->
            prefs.getString(tunnelModeKey(section), "all").orEmpty().ifBlank { "all" }
        }
    }

    private fun loadTunnelAppPackagesBySection(): Map<String, Set<String>> {
        return tunnelSections().associateWith { section ->
            val mode = prefs.getString(tunnelModeKey(section), "all").orEmpty().ifBlank { "all" }
            if (mode == "all") emptySet() else prefs.getStringSet(tunnelPackagesKey(section, mode), emptySet<String>()) ?: emptySet<String>()
        }
    }

    private fun loadTunnelAppPackagesBySectionMode(): Map<String, Set<String>> {
        val out = linkedMapOf<String, Set<String>>()
        tunnelSections().forEach { section ->
            listOf("only", "exclude").forEach { mode ->
                out["$section:$mode"] = prefs.getStringSet(tunnelPackagesKey(section, mode), emptySet<String>()) ?: emptySet<String>()
            }
        }
        return out
    }


    private fun cachedManualCandidateCount(text: String): Int {
        if (text == cachedManualCountText) return cachedManualCount
        cachedManualCountText = text
        cachedManualCount = parseManualIpText(text).size
        return cachedManualCount
    }

    private fun cachedIspManualCandidateCount(text: String): Int {
        if (text == cachedIspManualCountText) return cachedIspManualCount
        cachedIspManualCountText = text
        cachedIspManualCount = parseManualIpText(text).size
        return cachedIspManualCount
    }

    private fun loadState(): SimorghPublicState {
        // The VPN services run in the isolated :vpncore process. Re-open the
        // SharedPreferences with MODE_MULTI_PROCESS on every UI sync tick so Android
        // checks the backing XML timestamp and reloads values written by the backend.
        publicStatePrefs()
        coreStatePrefs()
        ensureDefaults()
        val prefs = publicStatePrefs()
        val corePrefs = coreStatePrefs()
        val activeModeNow = prefs.getString("activeMode", "idle").orEmpty().ifBlank { "idle" }
        val coreBackendConnected = corePrefs.getBoolean("serviceConnected", false)
        val mspModeActive = activeModeNow == "vpn" || activeModeNow == "proxy"
        val simpleModeActive = activeModeNow == "simple_xray"
        val fragmentModeActive = activeModeNow == "fragment"
        val nipoModeActive = activeModeNow == "nipo"
        val stormDnsModeActive = activeModeNow == "stormdns"
        val connected = prefs.getBoolean("connected", false) && mspModeActive
        val connecting = prefs.getBoolean("connecting", false) && mspModeActive && !connected
        val simpleRawConnecting = prefs.getBoolean("simpleConnecting", false)
        val nipoRawConnecting = prefs.getBoolean("nipoConnecting", false)
        val stormDnsRawConnecting = prefs.getBoolean("stormDnsConnecting", false)
        val fragmentRawConnecting = prefs.getBoolean("fragmentConnecting", false) || prefs.getBoolean("fragmentStartInProgress", false)
        val simplePrefConnected = prefs.getBoolean("simpleConnected", false)
        val nipoPrefConnected = prefs.getBoolean("nipoConnected", false)
        val stormDnsPrefConnected = prefs.getBoolean("stormDnsConnected", false)
        val fragmentPrefConnected = prefs.getBoolean("fragmentConnected", false)
        // Backend truth wins over temporary UI flags. If the isolated VPN core has already
        // reported serviceConnected=true for the active section, never let a stale
        // *Connecting flag keep the card/header in Connecting forever.
        val simpleConnected = simpleModeActive && (simplePrefConnected || coreBackendConnected)
        val simpleConnecting = simpleModeActive && simpleRawConnecting && !simpleConnected
        val nipoConnected = nipoModeActive && (nipoPrefConnected || coreBackendConnected)
        val nipoConnecting = nipoModeActive && nipoRawConnecting && !nipoConnected
        val stormDnsConnected = stormDnsModeActive && (stormDnsPrefConnected || coreBackendConnected)
        val stormDnsConnecting = stormDnsModeActive && stormDnsRawConnecting && !stormDnsConnected
        val fragmentConnected = fragmentModeActive && (fragmentPrefConnected || coreBackendConnected)
        val fragmentConnecting = fragmentModeActive && fragmentRawConnecting && !fragmentConnected
        if (coreBackendConnected || simpleConnected || nipoConnected || stormDnsConnected || fragmentConnected) {
            val fix = prefs.edit()
            var changed = false
            fun putConnected(section: String, status: String) {
                when (section) {
                    "simple" -> {
                        if (simpleRawConnecting || !simplePrefConnected) changed = true
                        fix.putBoolean("simpleConnecting", false).putBoolean("simpleConnected", true).putString("simpleStatus", status)
                    }
                    "fragment" -> {
                        if (fragmentRawConnecting || !fragmentPrefConnected) changed = true
                        fix.putBoolean("fragmentStartInProgress", false).putBoolean("fragmentConnecting", false).putBoolean("fragmentConnected", true).putString("fragmentStatus", status)
                    }
                    "nipo" -> {
                        if (nipoRawConnecting || !nipoPrefConnected) changed = true
                        fix.putBoolean("nipoConnecting", false).putBoolean("nipoConnected", true).putString("nipoStatus", status)
                    }
                    "stormdns" -> {
                        if (stormDnsRawConnecting || !stormDnsPrefConnected) changed = true
                        fix.putBoolean("stormDnsConnecting", false).putBoolean("stormDnsConnected", true).putString("stormDnsStatus", status)
                    }
                }
                fix.putString("status", status).putString("activeMode", activeModeNow)
            }
            when {
                simpleConnected -> putConnected("simple", "Simple XRAY Connected")
                fragmentConnected -> putConnected("fragment", "Fragment Connected")
                nipoConnected -> putConnected("nipo", "NipoVPN Connected")
                stormDnsConnected -> putConnected("stormdns", "MasterDNS Connected")
            }
            if (changed) fix.commit()
        }
        val startedAt = prefs.getLong("startedAt", 0L)
        val now = System.currentTimeMillis()
        val elapsed = if ((connected || connecting || simpleConnected || simpleConnecting || nipoConnected || nipoConnecting || stormDnsConnected || stormDnsConnecting || fragmentConnected || fragmentConnecting) && startedAt > 0L) ((now - startedAt) / 1000L).coerceAtLeast(0L) else 0L
        val code = prefs.getString("routeCountryCode", "").orEmpty()
        val activeRouteIpNow = prefs.getString("activeRouteIp", "").orEmpty()
        val routeIpNow = prefs.getString("routeIp", "").orEmpty().ifBlank { activeRouteIpNow }
        val route = if (code.isNotBlank() || routeIpNow.isNotBlank()) {
            SimorghRoute(
                engine = prefs.getString("routeEngine", "rkh_msp_http_proxy").orEmpty(),
                countryCode = code,
                countryName = prefs.getString("routeCountryName", "").orEmpty(),
                ip = routeIpNow,
                latitude = prefs.getFloat("routeLatitude", Float.NaN).takeIf { !it.isNaN() }?.toDouble(),
                longitude = prefs.getFloat("routeLongitude", Float.NaN).takeIf { !it.isNaN() }?.toDouble()
            ).let { r -> if (r.countryName.isBlank() && r.countryCode.isNotBlank()) CountryCoordinates.routeFor(r.countryCode, r.ip, r.engine) else r }
        } else null
        val simpleServerlessNow = prefs.getBoolean("simpleServerlessEnabled", false)
        val simpleConfigsNow = loadSimpleConfigs(simpleServerlessNow)
        val simpleSavedIndex = prefs.getInt("simpleBestIndex", -1)
        val simpleBestDisplay = if (simpleSavedIndex in simpleConfigsNow.indices) simpleDisplayName(simpleSavedIndex) else prefs.getString("simpleBestName", "").orEmpty()
        val simpleCustomProfilesNow = simpleCustomProfileItems()
        val simpleCustomSelectedNow = simpleCustomSelectedProfileId()
        val simpleCustomConfigsNow = loadSimpleCustomConfigs(simpleCustomSelectedNow)
        val stormDnsLogLinesNow = loadStormDnsLogLines()
        val stormDnsHealthyFromLogs = extractStormDnsHealthyResolversFromLogs()
        val stormDnsHealthyFromRuntimeFiles = loadStormDnsHealthyResolversFromRuntimeFiles()
        val (runtimeScanned, runtimeTotal) = StormDnsRuntimeLog.progressSnapshot(getApplication<Application>())
        val stormDnsProgressFromLogs = extractStormDnsProgressFromLogs(stormDnsLogLinesNow)
        val stormDnsHealthyFromPrefs = prefs.getString("stormDnsHealthyResolversText", "").orEmpty()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()
        val stormDnsHealthyNow = (stormDnsHealthyFromPrefs + stormDnsHealthyFromLogs + stormDnsHealthyFromRuntimeFiles).distinct()
        val stormDnsTotalNow = listOf(runtimeTotal, stormDnsProgressFromLogs?.second ?: 0, cachedStormDnsResolvers(prefs.getString("stormDnsResolvers", defaultStormDnsResolvers()).orEmpty()).size).maxOrNull() ?: 0
        val stormDnsScannedNow = listOf(runtimeScanned, stormDnsProgressFromLogs?.first ?: 0, prefs.getInt("stormDnsResolverScanned", 0)).maxOrNull() ?: 0
        val stormDnsHasProgress = stormDnsTotalNow > 0 || stormDnsScannedNow > 0
        val stormDnsPrefsScanStatus = prefs.getString("stormDnsResolverScanStatus", "Healthy DNS will be detected from MasterDNS client logs").orEmpty().replace("StormDNS", "MasterDNS")
        val stormDnsLogStatus = when {
            prefs.getBoolean("stormDnsResolverScanning", false) -> stormDnsPrefsScanStatus
            stormDnsHasProgress -> "MasterDNS log scan ${stormDnsScannedNow}/${stormDnsTotalNow} • healthy ${stormDnsHealthyNow.size}"
            stormDnsHealthyNow.isNotEmpty() -> "MasterDNS healthy DNS • ${stormDnsHealthyNow.size}"
            else -> stormDnsPrefsScanStatus
        }.replace("StormDNS", "MasterDNS")

        val selectedIspsNow = selectedIspSet()
        val selectedIspPrimary = selectedIspsNow.firstOrNull() ?: defaultIsp()
        val selectedSnisNow = selectedSniList()
        val orderedIspOptionsNow = orderIspOptionsForSelection(ispOptions, selectedIspsNow)
        val orderedSniOptionsNow = orderSniOptionsForSelection(sniOptions, selectedSnisNow)

        val rawStatusNow = prefs.getString("status", "Ready").orEmpty().replace("StormDNS", "MasterDNS")
        val simpleStatusNow = when {
            simpleConnected -> prefs.getString("simpleStatus", "Simple XRAY Connected").orEmpty().takeUnless { it.contains("connecting", true) || it.contains("starting", true) }.orEmpty().ifBlank { "Simple XRAY Connected" }
            simpleConnecting -> prefs.getString("simpleStatus", "Simple XRAY Connecting").orEmpty().ifBlank { "Simple XRAY Connecting" }
            else -> prefs.getString("simpleStatus", "Simple XRAY ready").orEmpty()
        }
        val nipoStatusNow = when {
            nipoConnected -> "NipoVPN Connected"
            nipoConnecting -> prefs.getString("nipoStatus", "NipoVPN Connecting").orEmpty().ifBlank { "NipoVPN Connecting" }
            else -> prefs.getString("nipoStatus", "NipoVPN ready").orEmpty()
        }
        val stormDnsStatusNow = when {
            stormDnsConnected -> "MasterDNS Connected"
            stormDnsConnecting -> prefs.getString("stormDnsStatus", "MasterDNS Connecting").orEmpty().replace("StormDNS", "MasterDNS").ifBlank { "MasterDNS Connecting" }
            else -> prefs.getString("stormDnsStatus", "MasterDNS ready").orEmpty().replace("StormDNS", "MasterDNS")
        }
        val fragmentStatusNow = when {
            fragmentConnected -> "Fragment Connected"
            fragmentConnecting -> prefs.getString("fragmentStatus", "Fragment Connecting").orEmpty().takeUnless { it.contains("starting", true) && coreBackendConnected }.orEmpty().ifBlank { "Fragment Connecting" }
            else -> prefs.getString("fragmentStatus", "Fragment ready").orEmpty()
        }
        val globalStatusNow = when {
            fragmentConnected -> "Fragment Connected"
            nipoConnected -> "NipoVPN Connected"
            stormDnsConnected -> "MasterDNS Connected"
            simpleConnected -> if (rawStatusNow.contains("connecting", true) || rawStatusNow.contains("starting", true)) "Simple XRAY Connected" else rawStatusNow.ifBlank { "Simple XRAY Connected" }
            connected -> rawStatusNow.ifBlank { if (activeModeNow == "proxy") "MSP Proxy Connected" else "MSP VPN Connected" }
            fragmentConnecting -> "Fragment Connecting"
            nipoConnecting -> "NipoVPN Connecting"
            stormDnsConnecting -> "MasterDNS Connecting"
            simpleConnecting -> rawStatusNow.ifBlank { "Simple XRAY Connecting" }
            connecting -> rawStatusNow.ifBlank { "MSP Connecting" }
            else -> rawStatusNow
        }

        return SimorghPublicState(
            connected = connected,
            connecting = connecting,
            status = globalStatusNow,
            engine = prefs.getString("engine", "RKh-MSP").orEmpty(),
            selectedRunMode = prefs.getString("selectedRunMode", "proxy").orEmpty().ifBlank { "proxy" },
            elapsedSeconds = elapsed,
            downloadKbps = prefs.getLong("downloadKbps", 0L),
            uploadKbps = prefs.getLong("uploadKbps", 0L),
            route = route,
            publicEngineAvailable = true,
            lastError = prefs.getString("lastError", "").orEmpty(),
            selectedIsp = selectedIspPrimary,
            selectedIsps = selectedIspsNow.toSet(),
            selectedSnis = linkedSetOf<String>().apply { selectedSnisNow.forEach { add(it) } },
            selectedPort = prefs.getInt("selectedPort", 443),
            maxScanIps = prefs.getInt("maxScanIps", 33000).coerceIn(1, 33000),
            scanSpeed = prefs.getString("scanSpeed", "normal").orEmpty().ifBlank { "normal" },
            manualIpMode = prefs.getBoolean("manualIpMode", false),
            manualIpsText = prefs.getString("manualIpsText", "").orEmpty(),
            manualCandidateCount = cachedManualCandidateCount(prefs.getString("manualIpsText", "").orEmpty()),
            ispManualRangeMode = prefs.getBoolean("ispManualRangeMode", false),
            ispManualRangeText = prefs.getString("ispManualRangeText", "").orEmpty(),
            ispManualRangeCandidateCount = cachedIspManualCandidateCount(prefs.getString("ispManualRangeText", "").orEmpty()),
            scannedCount = prefs.getInt("scannedCount", 0),
            totalCandidates = prefs.getInt("totalCandidates", 0),
            cleanIpCount = maxOf(prefs.getInt("cleanIpCount", 0), loadSavedCleanIps().size),
            savedCleanIps = loadSavedCleanIps(),
            savedCleanIpPings = loadSavedCleanIpPings(),
            activeRouteTarget = prefs.getString("activeRouteTarget", "").orEmpty(),
            activeRouteIp = activeRouteIpNow,
            activeRoutePingMs = prefs.getLong("activeRoutePingMs", -1L),
            proxyPort = prefs.getInt("proxyPort", if (prefs.getString("selectedProxyProtocol", "socks5") == "http") 9991 else 9990),
            socks5ProxyPort = prefs.getInt("socks5ProxyPort", 9990),
            httpProxyPort = prefs.getInt("httpProxyPort", 9991),
            selectedProxyProtocol = prefs.getString("selectedProxyProtocol", "socks5").orEmpty().ifBlank { "socks5" },
            routeStrategy = prefs.getString("routeStrategy", "default").orEmpty().ifBlank { "default" },
            cfVlessConfig = prefs.getString("cfVlessConfig", "").orEmpty(),
            cfEnabled = cfEnabledOverride ?: prefs.getBoolean("cfEnabled", false),
            cfPingResults = loadCfPingResults(),
            cfStatus = prefs.getString("cfStatus", "").orEmpty(),
            cfConnectingIp = prefs.getString("cfConnectingIp", "").orEmpty(),
            activeMode = activeModeNow,
            simpleConnected = simpleConnected,
            simpleConnecting = simpleConnecting,
            simpleStatus = simpleStatusNow,
            simpleConfigCount = simpleConfigsNow.size,
            simpleConfigItems = buildSimpleConfigItems(simpleConfigsNow),
            simpleBestName = simpleBestDisplay,
            simpleBestPingMs = prefs.getLong("simpleBestPingMs", -1L),
            simpleServerlessEnabled = simpleServerlessNow,
            simpleCustomProfiles = simpleCustomProfilesNow,
            simpleCustomSelectedProfile = simpleCustomSelectedNow,
            simpleCustomRemark = simpleCustomProfileRemark(simpleCustomSelectedNow),
            simpleCustomInput = simpleCustomInput(simpleCustomSelectedNow),
            simpleCustomStatus = prefs.getString("simpleCustomStatus", "").orEmpty(),
            simpleCustomConfigCount = simpleCustomConfigsNow.size,
            simpleCustomConfigItems = buildSimpleCustomConfigItems(simpleCustomConfigsNow),
            nipoConnected = nipoConnected,
            nipoConnecting = nipoConnecting,
            nipoStatus = nipoStatusNow,
            stormDnsConnected = stormDnsConnected,
            stormDnsConnecting = stormDnsConnecting,
            stormDnsStatus = stormDnsStatusNow,
            fragmentConnected = fragmentConnected,
            fragmentConnecting = fragmentConnecting,
            fragmentStatus = fragmentStatusNow,
            fragmentConfigInput = prefs.getString("fragmentConfigInput", "").orEmpty(),
            fragmentAddress = prefs.getString("fragmentAddress", "").orEmpty(),
            fragmentEffectiveAddress = prefs.getString("fragmentEffectiveAddress", "").orEmpty(),
            fragmentPackets = prefs.getString("fragmentPackets", "tlshello").orEmpty().ifBlank { "tlshello" },
            fragmentLengths = prefs.getString("fragmentLengths", "3-5,6-8,10-20").orEmpty().ifBlank { "3-5,6-8,10-20" },
            fragmentDelays = prefs.getString("fragmentDelays", "1-2,5-6,10-20").orEmpty().ifBlank { "1-2,5-6,10-20" },
            fragmentMaxSplit = prefs.getString("fragmentMaxSplit", "64").orEmpty().ifBlank { "64" },
            fragmentPingMs = prefs.getLong("fragmentPingMs", -1L),
            fragmentProfiles = fragmentProfileNames(),
            fragmentSelectedProfile = prefs.getString("fragmentSelectedProfile", "Default").orEmpty().ifBlank { "Default" },
            fragmentProfilePings = fragmentProfilePings(),
            fragmentGeneratedJson = prefs.getString("fragmentGeneratedJson", "").orEmpty(),
            stormDnsRunMode = prefs.getString("stormDnsRunMode", "proxy").orEmpty().ifBlank { "proxy" },
            stormDnsDomain = prefs.getString("stormDnsDomain", "").orEmpty(),
            stormDnsKey = prefs.getString("stormDnsKey", "").orEmpty(),
            stormDnsResolvers = prefs.getString("stormDnsResolvers", defaultStormDnsResolvers()).orEmpty(),
            stormDnsClientConfig = prefs.getString("stormDnsClientConfig", defaultStormDnsClientConfig()).orEmpty().ifBlank { defaultStormDnsClientConfig() },
            stormDnsServerConfig = prefs.getString("stormDnsServerConfig", defaultStormDnsServerConfig()).orEmpty().ifBlank { defaultStormDnsServerConfig() },
            stormDnsProfileName = prefs.getString("stormDnsProfileName", "Default").orEmpty().ifBlank { "Default" },
            stormDnsSelectedProfile = prefs.getString("stormDnsSelectedProfile", "Default").orEmpty().ifBlank { "Default" },
            stormDnsProfiles = stormDnsProfileNames(),
            stormDnsResolverProfileName = prefs.getString("stormDnsResolverProfileName", "Default Resolvers").orEmpty().ifBlank { "Default Resolvers" },
            stormDnsSelectedResolverProfile = prefs.getString("stormDnsSelectedResolverProfile", "Default Resolvers").orEmpty().ifBlank { "Default Resolvers" },
            stormDnsResolverProfiles = resolverProfileNames(),
            stormDnsHealthyResolvers = stormDnsHealthyNow,
            stormDnsLogLines = stormDnsLogLinesNow,
            stormDnsResolverTotal = stormDnsTotalNow,
            stormDnsResolverScanned = stormDnsScannedNow,
            stormDnsResolverValidCount = stormDnsHealthyNow.size,
            stormDnsResolverScanning = prefs.getBoolean("stormDnsResolverScanning", false),
            stormDnsResolverScanStatus = stormDnsLogStatus,
            stormDnsSocksPort = prefs.getInt("stormDnsSocksPort", 18000),
            stormDnsLocalDnsPort = prefs.getInt("stormDnsLocalDnsPort", 5353),
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
            nipoServerAddress = prefs.getString("nipoServerAddress", parseNipoEndpoint(prefs.getString("nipoConfigYaml", "").orEmpty().ifBlank { defaultNipoConfigYaml() }).first).orEmpty().ifBlank { "127.0.0.1" },
            nipoServerPort = prefs.getInt("nipoServerPort", parseNipoEndpoint(prefs.getString("nipoConfigYaml", "").orEmpty().ifBlank { defaultNipoConfigYaml() }).second),
            nipoHttpVersion = prefs.getString("nipoHttpVersion", "1.1").orEmpty().ifBlank { "1.1" },
            nipoUserAgent = prefs.getString("nipoUserAgent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:132.0) Gecko/20100101 Firefox/132.0").orEmpty(),
            nipoPingMs = prefs.getLong("nipoPingMs", -1L),
            nipoSocksPort = prefs.getInt("nipoSocksPort", 9992),
            tunnelAppMode = prefs.getString("tunnelAppMode", "all").orEmpty().ifBlank { "all" },
            tunnelAppPackages = prefs.getStringSet("tunnelAppPackages", emptySet<String>()) ?: emptySet<String>(),
            tunnelAppModesBySection = loadTunnelAppModesBySection(),
            tunnelAppPackagesBySection = loadTunnelAppPackagesBySection(),
            tunnelAppPackagesBySectionMode = loadTunnelAppPackagesBySectionMode(),
            ispOptions = orderedIspOptionsNow,
            sniOptions = orderedSniOptionsNow
        )
    }

    private fun ensureDefaults() {
        val edit = prefs.edit()
        var changed = false
        if (!prefs.contains("selectedIsp") || prefs.getString("selectedIsp", "").isNullOrBlank()) {
            edit.putString("selectedIsp", defaultIsp())
            changed = true
        }
        if (!prefs.contains("selectedIsps") || (prefs.getStringSet("selectedIsps", emptySet()) ?: emptySet()).isEmpty()) {
            val seed = prefs.getString("selectedIsp", defaultIsp()).orEmpty().ifBlank { defaultIsp() }
            edit.putStringSet("selectedIsps", setOf(seed))
            changed = true
        }
        if (!prefs.contains("selectedIspsCsv") || prefs.getString("selectedIspsCsv", "").orEmpty().isBlank()) {
            val seedList = (prefs.getStringSet("selectedIsps", emptySet()) ?: emptySet())
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .sortedWith(String.CASE_INSENSITIVE_ORDER)
                .ifEmpty { listOf(prefs.getString("selectedIsp", defaultIsp()).orEmpty().ifBlank { defaultIsp() }) }
            edit.putString("selectedIspsCsv", seedList.joinToString("\n"))
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
        if (!prefs.contains("ispManualRangeMode")) {
            edit.putBoolean("ispManualRangeMode", false)
            changed = true
        }
        if (!prefs.contains("ispManualRangeText")) {
            edit.putString("ispManualRangeText", "")
            changed = true
        }
        if (!prefs.contains("fragmentAddress")) {
            edit.putString("fragmentAddress", "")
            changed = true
        }
        if (!prefs.contains("fragmentPackets")) {
            edit.putString("fragmentPackets", "tlshello")
            changed = true
        }
        if (!prefs.contains("fragmentLengths")) {
            edit.putString("fragmentLengths", "3-5,6-8,10-20")
            changed = true
        }
        if (!prefs.contains("fragmentDelays")) {
            edit.putString("fragmentDelays", "1-2,5-6,10-20")
            changed = true
        }
        if (!prefs.contains("fragmentMaxSplit")) {
            edit.putString("fragmentMaxSplit", "64")
            changed = true
        }
        if (!prefs.contains("fragmentSelectedProfile")) {
            edit.putString("fragmentSelectedProfile", "Default")
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
        if (!prefs.contains("tunnelAppMode")) { edit.putString("tunnelAppMode", "all"); changed = true }
        if (!prefs.contains("tunnelAppPackages")) { edit.putStringSet("tunnelAppPackages", emptySet<String>()); changed = true }
        listOf("simple", "msp", "nipo", "stormdns").forEach { section ->
            if (!prefs.contains("tunnelAppMode_$section")) { edit.putString("tunnelAppMode_$section", "all"); changed = true }
            if (!prefs.contains("tunnelAppPackages_$section")) { edit.putStringSet("tunnelAppPackages_$section", emptySet<String>()); changed = true }
            listOf("only", "exclude").forEach { mode ->
                if (!prefs.contains("tunnelAppPackages_${section}_$mode")) { edit.putStringSet("tunnelAppPackages_${section}_$mode", emptySet<String>()); changed = true }
            }
        }
        if (!prefs.contains("stormDnsStatus")) { edit.putString("stormDnsStatus", "MasterDNS ready"); changed = true }
        if (!prefs.contains("stormDnsConnected")) { edit.putBoolean("stormDnsConnected", false); changed = true }
        if (!prefs.contains("stormDnsConnecting")) { edit.putBoolean("stormDnsConnecting", false); changed = true }
        if (!prefs.contains("stormDnsRunMode")) { edit.putString("stormDnsRunMode", "proxy"); changed = true }
        if (!prefs.contains("stormDnsDomain")) { edit.putString("stormDnsDomain", ""); changed = true }
        if (!prefs.contains("stormDnsKey")) { edit.putString("stormDnsKey", ""); changed = true }
        if (!prefs.contains("stormDnsResolvers")) { edit.putString("stormDnsResolvers", defaultStormDnsResolvers()); changed = true }
        if (!prefs.contains("stormDnsHealthyResolversText")) { edit.putString("stormDnsHealthyResolversText", ""); changed = true }
        if (!prefs.contains("stormDnsClientConfig")) { edit.putString("stormDnsClientConfig", defaultStormDnsClientConfig()); changed = true }
        if (!prefs.contains("stormDnsServerConfig")) { edit.putString("stormDnsServerConfig", defaultStormDnsServerConfig()); changed = true }
        if (!prefs.contains("stormDnsProfileName")) { edit.putString("stormDnsProfileName", "Default"); changed = true }
        if (!prefs.contains("stormDnsSelectedProfile")) { edit.putString("stormDnsSelectedProfile", "Default"); changed = true }
        if (!prefs.contains("stormDnsResolverProfileName")) { edit.putString("stormDnsResolverProfileName", "Default Resolvers"); changed = true }
        if (!prefs.contains("stormDnsSelectedResolverProfile")) { edit.putString("stormDnsSelectedResolverProfile", "Default Resolvers"); changed = true }
        if (!prefs.contains("stormDnsProfilesJson")) { edit.putString("stormDnsProfilesJson", JSONArray().put(stormDnsProfileObject("Default")).toString()); changed = true }
        if (!prefs.contains("stormDnsResolverProfilesJson")) { edit.putString("stormDnsResolverProfilesJson", JSONArray().put(JSONObject().put("name", "Default Resolvers").put("resolvers", defaultStormDnsResolvers())).toString()); changed = true }
        if (!prefs.contains("stormDnsResolverTotal")) { edit.putInt("stormDnsResolverTotal", normalizeStormDnsResolvers(defaultStormDnsResolvers()).size); changed = true }
        if (!prefs.contains("stormDnsResolverValidCount")) { edit.putInt("stormDnsResolverValidCount", 0); changed = true }
        if (!prefs.contains("stormDnsResolverScanStatus")) { edit.putString("stormDnsResolverScanStatus", "DNS scan idle"); changed = true }
        if (!prefs.contains("stormDnsSocksPort")) { edit.putInt("stormDnsSocksPort", 18000); changed = true }
        if (!prefs.contains("stormDnsLocalDnsPort")) { edit.putInt("stormDnsLocalDnsPort", 5353); changed = true }
        val bundledStormDnsClient = defaultStormDnsClientConfig()
        val bundledStormDnsResolvers = defaultStormDnsResolvers()
        val bundledStormDnsStamp = bundledStormDnsClient.hashCode().toString() + ":" + bundledStormDnsResolvers.hashCode().toString()
        if (prefs.getString("stormDnsBundledAssetsStamp", "") != bundledStormDnsStamp) {
            edit.putString("stormDnsBundledAssetsStamp", bundledStormDnsStamp)
            edit.putString("stormDnsClientConfig", bundledStormDnsClient)
            edit.putString("stormDnsResolvers", bundledStormDnsResolvers)
            edit.putString("stormDnsResolverProfilesJson", JSONArray().put(JSONObject().put("name", "Default Resolvers").put("resolvers", bundledStormDnsResolvers)).toString())
            edit.remove("stormDnsLastSessionResolver")
            edit.remove("stormDnsSessionSuccessResolvers")
            edit.putString("stormDnsHealthyResolversText", "")
            edit.putInt("stormDnsResolverTotal", normalizeStormDnsResolvers(bundledStormDnsResolvers).size)
            edit.putInt("stormDnsResolverValidCount", 0)
            edit.putString("stormDnsResolverScanStatus", "Bundled MasterDNS profile loaded • waiting for core logs")
            changed = true
        }
        if (changed) edit.apply()
    }

    private fun parseManualIpText(text: String): List<String> {
        val out = linkedSetOf<String>()
        text.lineSequence()
            .flatMap { it.split(',', ';', ' ', '	').asSequence() }
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .forEach { token -> expandManualIpToken(token).forEach { out += it } }
        return out.take(33000).toList()
    }

    private fun expandManualIpToken(token: String): List<String> {
        if ('/' in token) return expandIpv4Cidr(token)
        if ('-' in token) return expandIpv4Range(token)
        return if (isIpv4Literal(token)) listOf(token) else emptyList()
    }

    private fun expandIpv4Cidr(token: String): List<String> {
        val ip = token.substringBefore('/').trim()
        val prefix = token.substringAfter('/').toIntOrNull()?.coerceIn(0, 32) ?: return emptyList()
        val base = ipv4ToLong(ip) ?: return emptyList()
        val mask = if (prefix == 0) 0L else (-1L shl (32 - prefix)) and 0xFFFFFFFFL
        val network = base and mask
        val size = 1L shl (32 - prefix)
        val start = if (prefix <= 30) network + 1 else network
        val end = if (prefix <= 30) network + size - 2 else network + size - 1
        if (end < start) return emptyList()
        val limitEnd = minOf(end, start + 33000L - 1L)
        return (start..limitEnd).map { longToIpv4(it) }
    }

    private fun expandIpv4Range(token: String): List<String> {
        val left = token.substringBefore('-').trim()
        val right = token.substringAfter('-').trim()
        val start = ipv4ToLong(left) ?: return emptyList()
        val end = if (isIpv4Literal(right)) {
            ipv4ToLong(right) ?: return emptyList()
        } else {
            val last = right.toIntOrNull()?.coerceIn(0, 255) ?: return emptyList()
            val prefix = left.substringBeforeLast('.', missingDelimiterValue = "")
            ipv4ToLong("$prefix.$last") ?: return emptyList()
        }
        if (end < start) return emptyList()
        val limitEnd = minOf(end, start + 33000L - 1L)
        return (start..limitEnd).map { longToIpv4(it) }
    }

    private fun ipv4ToLong(ip: String): Long? {
        val parts = ip.split('.')
        if (parts.size != 4) return null
        var out = 0L
        for (part in parts) {
            val n = part.toIntOrNull() ?: return null
            if (n !in 0..255) return null
            out = (out shl 8) or n.toLong()
        }
        return out and 0xFFFFFFFFL
    }

    private fun longToIpv4(value: Long): String {
        return listOf(24, 16, 8, 0).joinToString(".") { shift -> ((value shr shift) and 255L).toString() }
    }

    private fun isIpv4Literal(host: String): Boolean {
        val parts = host.split('.')
        return parts.size == 4 && parts.all { part -> part.toIntOrNull()?.let { it in 0..255 } == true }
    }


    private fun readShieldedAssetText(path: String): String {
        val encoded = getApplication<Application>().assets.open(path).use { input -> input.readBytes() }
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

    private fun defaultIsp(): String {
        return prefs.getString("selectedIsp", "AbrArvan CDN and IaaS").orEmpty().ifBlank { "AbrArvan CDN and IaaS" }
    }

    private fun selectedIspSet(): List<String> {
        val legacy = prefs.getString("selectedIsp", "AbrArvan CDN and IaaS").orEmpty().ifBlank { "AbrArvan CDN and IaaS" }
        val out = linkedSetOf<String>()
        prefs.getString("selectedIspsCsv", "").orEmpty()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach { value -> if (out.none { it.equals(value, ignoreCase = true) }) out.add(value) }
        if (out.isEmpty()) {
            val stored = prefs.getStringSet("selectedIsps", emptySet()) ?: emptySet()
            stored.map { it.trim() }
                .filter { it.isNotBlank() }
                .sortedWith(String.CASE_INSENSITIVE_ORDER)
                .forEach { value -> if (out.none { it.equals(value, ignoreCase = true) }) out.add(value) }
        }
        if (out.isEmpty()) out.add(legacy)
        return out.toList()
    }

    private fun orderIspOptionsForSelection(options: List<String>, selected: List<String>): List<String> {
        val selectedClean = selected.map { it.trim() }.filter { it.isNotBlank() }
        val selectedTop = selectedClean.filter { selectedName -> options.any { it.equals(selectedName, ignoreCase = true) } || selectedName.isNotBlank() }
        val rest = options.filterNot { option -> selectedClean.any { it.equals(option, ignoreCase = true) } }
        return (selectedTop + rest).distinctBy { it.lowercase(Locale.US) }
    }

    private fun selectedSniList(): List<String> {
        val out = linkedSetOf<String>()
        prefs.getString("selectedSnisCsv", "").orEmpty()
            .lineSequence()
            .flatMap { it.split(',', ';').asSequence() }
            .map { it.trim().lowercase(Locale.US) }
            .filter { it.isNotBlank() }
            .forEach { value -> if (out.none { it.equals(value, ignoreCase = true) }) out.add(value) }
        if (out.isEmpty()) {
            (prefs.getStringSet("selectedSnis", setOf("chatgpt.com")) ?: setOf("chatgpt.com"))
                .map { it.trim().lowercase(Locale.US) }
                .filter { it.isNotBlank() }
                .sortedWith(String.CASE_INSENSITIVE_ORDER)
                .forEach { value -> if (out.none { it.equals(value, ignoreCase = true) }) out.add(value) }
        }
        if (out.isEmpty()) out.add("chatgpt.com")
        return out.toList()
    }

    private fun orderSniOptionsForSelection(options: List<String>, selected: List<String>): List<String> {
        val selectedClean = selected.map { it.trim().lowercase(Locale.US) }.filter { it.isNotBlank() }
        val selectedTop = selectedClean.filter { selectedName -> options.any { it.equals(selectedName, ignoreCase = true) } || selectedName.isNotBlank() }
        val rest = options.filterNot { option -> selectedClean.any { it.equals(option, ignoreCase = true) } }
        return (selectedTop + rest).distinctBy { it.lowercase(Locale.US) }
    }

    private fun loadIspOptions(): List<String> {
        val out = linkedSetOf<String>()
        runCatching {
            readShieldedAssetText("rk_payload/p0.dat").lineSequence().drop(1).forEach { line: String ->
                val cols = parseCsvLine(line)
                val asName = cols.getOrNull(6)?.trim().orEmpty()
                if (asName.isNotBlank()) out += asName
            }
        }.onFailure { log("Failed to load ISP options from hidden MSP ranges asset", it) }
        val selectedDefault = prefs.getString("selectedIsp", "AbrArvan CDN and IaaS").orEmpty().ifBlank { "AbrArvan CDN and IaaS" }
        val stable = out
            .filterNot { it.equals(selectedDefault, ignoreCase = true) }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
        return (listOf(selectedDefault) + stable).distinctBy { it.lowercase(Locale.US) }.ifEmpty { listOf("AbrArvan CDN and IaaS") }
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
        val savedRaw = prefs.getString("savedCleanIps", "").orEmpty()
        val manualRaw = prefs.getString("manualIpsText", "").orEmpty()
        val key = "${savedRaw.length}:${savedRaw.hashCode()}|${manualRaw.length}:${manualRaw.hashCode()}"
        if (key == cachedSavedCleanKey) return cachedSavedCleanIps
        val out = linkedSetOf<String>()
        savedRaw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && isIpv4Literal(it) }
            .take(300)
            .forEach { out += it }
        // Manual IPs are treated as clean IP memory even when Manual mode is OFF.
        parseManualIpText(manualRaw).forEach { out += it }
        val parsed = out.filter { isIpv4Literal(it) }.take(300).toList()
        cachedSavedCleanKey = key
        cachedSavedCleanIps = parsed
        return parsed
    }


    private fun log(message: String, throwable: Throwable? = null) {
        RKhVpnLogStore.append(getApplication(), "SIMORGH-UI", message, throwable)
    }


    override fun onCleared() {
        simpleHealthyScanJob?.cancel()
        simpleBackgroundLatencyJob?.cancel()
        stormDnsLogRefreshJob?.cancel()
        cleanupGeoAssetsAfterAppClose()
        super.onCleared()
    }

    private fun cleanupGeoAssetsAfterAppClose() {
        val app = getApplication<Application>()
        // UI close: always remove disposable Xray health-test cache.
        runCatching { File(app.cacheDir, "xray-ping").deleteRecursively() }
        runCatching { File(app.cacheDir, "xray-empty-assets").deleteRecursively() }

        // Shared geo assets are removed on UI close only when no VPN/Simple core is active.
        // If VPN is still running in the background, keep them to avoid breaking active routing.
        @Suppress("DEPRECATION")
        val publicPrefs = app.getSharedPreferences("simorgh_public_state", Context.MODE_PRIVATE or Context.MODE_MULTI_PROCESS).also { it.all }
        @Suppress("DEPRECATION")
        val mainPrefs = app.getSharedPreferences("rkh_vpn_state", Context.MODE_PRIVATE or Context.MODE_MULTI_PROCESS).also { it.all }
        val activeMode = publicPrefs.getString("activeMode", "").orEmpty()
        val vpnStillActive = mainPrefs.getBoolean("serviceConnected", false) ||
            publicPrefs.getBoolean("simpleConnected", false) ||
            publicPrefs.getBoolean("simpleConnecting", false) ||
            activeMode == "simple_xray" ||
            activeMode == "public" ||
            activeMode == "nipo" ||
            activeMode == "stormdns"
        if (!vpnStillActive) {
            runCatching { File(app.filesDir, "xray-assets").deleteRecursively() }
            runCatching { File(app.filesDir, "xray-bin-runtime/geosite.dat").delete() }
            runCatching { File(app.filesDir, "xray-bin-runtime/geoip.dat").delete() }
        }
    }

    class Factory(private val app: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(c: Class<T>): T = SimorghPublicViewModel(app) as T
    }
    companion object {
        private const val SYNC_LOOP_INTERVAL_MS = 5_000L
        private const val SIMPLE_LATENCY_PROBE_INTERVAL_MS = 10 * 60_000L
        private const val SIMPLE_LATENCY_CACHE_MAX_AGE_MS = 24 * 60 * 60_000L
        private const val SIMPLE_LATENCY_CACHE_KEEP_MS = 24 * 60 * 60_000L
        private const val SIMPLE_LATENCY_CACHE_MAX_ENTRIES = 80
        private const val RUNTIME_CACHE_KEEP_MS = 5 * 60_000L
        private const val RUNTIME_CACHE_MAX_FILES = 4
        private const val RUNTIME_CACHE_MAX_BYTES = 8L * 1024L * 1024L
        private const val SIMPLE_CONNECT_MIN_SNI_HEALTHY = 3
        private const val SIMPLE_SCAN_PARALLELISM = 8
        private const val SIMPLE_FAST_PROBE_PARALLELISM = 2
        private const val SIMPLE_FAST_PROBE_TIMEOUT_MS = 1800
        private const val SIMPLE_REAL_XRAY_TEST_TIMEOUT_MS = 9_000L
        private const val SIMPLE_AUTO_SWITCH_INTERVAL_MS = 15_000L
        private const val SIMPLE_BALANCER_MAX_OUTBOUNDS = 32
        private const val SIMPLE_SNI_TEST_HOST = "google.com"
    }

}
