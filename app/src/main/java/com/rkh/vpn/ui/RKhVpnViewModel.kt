package com.rkh.vpn.ui

import android.app.Application
import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.net.TrafficStats
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.rkh.vpn.core.PingEngine
import com.rkh.vpn.data.AppState
import com.rkh.vpn.data.RKhVpnLogStore
import com.rkh.vpn.data.ServerConfig
import com.rkh.vpn.data.SpeedSample
import com.rkh.vpn.data.SubscriptionRepository
import com.rkh.vpn.data.UsageInfo
import com.rkh.vpn.service.RkhVpnService
import com.rkh.vpn.worker.SubscriptionUpdateWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class RKhVpnViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = SubscriptionRepository()
    private val ping = PingEngine(app)
    private val prefs = app.getSharedPreferences("rkh_vpn_state", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(loadSavedState())
    val state: StateFlow<AppState> = _state
    private var speedJob: Job? = null
    private var autoSubJob: Job? = null
    private var smartJob: Job? = null
    private var connectionSyncJob: Job? = null

    init {
        val packageInfo = getApplication<Application>().packageManager.getPackageInfo(getApplication<Application>().packageName, 0)
        val versionName = packageInfo.versionName ?: "unknown"
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) packageInfo.longVersionCode else @Suppress("DEPRECATION") packageInfo.versionCode.toLong()
        log("App", "ViewModel started • version=$versionName ($versionCode)")
        log("App", "Binary-core mode enabled: no libv2ray AAR. Runtime uses packaged native binaries: libxray.so + libtun2socks.so for this phone ABI.")
        schedule(true)
        startFiveMinuteSubscriptionUpdates()
        startSmartBestServerLoop()
        startConnectionStateSync()
        if (_state.value.connected) startSpeedMeter()
        viewModelScope.launch {
            while (isActive) {
                refreshLogs()
                delay(1200)
            }
        }
    }

    fun updateStatus(message: String) {
        log("UI", message)
        update { it.copy(status = message) }
    }

    fun setPrimaryToken(v: String) = update { it.copy(primaryToken = v) }
    fun setPremiumToken(v: String) = update { it.copy(premiumToken = v) }

    fun selectBase(v: String) = update {
        log("Account", if (v == SubscriptionRepository.PREMIUM_BASE) "Switched to Premium" else "Switched to Standard")
        it.copy(
            selectedBaseUrl = v,
            usage = loadUsageForBase(v),
            servers = loadServersForBase(v),
            selectedServerId = prefs.getString("selectedServerId_${suffix(v)}", null),
            status = if (v == SubscriptionRepository.PREMIUM_BASE) "Premium account" else "Standard account"
        )
    }

    fun toggleSmart(v: Boolean) {
        update {
            log("Smart", "Smart Best Server ${if (v) "enabled" else "disabled"}")
            it.copy(
                smartConnect = v,
                status = if (v) "Smart Best Server enabled: using real Xray latency" else "Smart Best Server disabled"
            )
        }
        if (v) {
            viewModelScope.launch {
                val current = _state.value
                if (current.connected && current.servers.isNotEmpty()) {
                    log("Smart", "Smart enabled while connected; running immediate real Xray latency check")
                    val previousId = current.selectedServerId
                    val best = pingAllInternal(selectBestWhenSmart = true)
                    if (best != null && best.id != previousId) {
                        log("Smart", "Auto-switching to real-latency best config: ${best.name} • ${best.pingMs}ms")
                        startVpnWithServer(best)
                    }
                }
            }
        }
    }

    fun toggleDark(v: Boolean) = update { it.copy(darkTheme = v) }
    fun toggleMonet(v: Boolean) = update { it.copy(monet = v) }
    fun selectServer(id: String) = update { it.copy(selectedServerId = id, smartConnect = false) }

    fun toggleAutoUpdate(v: Boolean) {
        update { it.copy(autoUpdate = v) }
        schedule(v)
    }

    fun loadSubscription() = viewModelScope.launch {
        val s = _state.value
        val token = if (s.selectedBaseUrl == SubscriptionRepository.PRIMARY_BASE) s.primaryToken else s.premiumToken
        if (token.isBlank()) {
            log("Sub", "Subscription code is empty")
            update { it.copy(status = "Enter your subscription code") }
            return@launch
        }

        val account = if (s.selectedBaseUrl == SubscriptionRepository.PREMIUM_BASE) "Premium" else "Standard"
        log("Sub", "Fetching $account subscription")
        update { it.copy(status = "Updating subscription...") }

        runCatching { withContext(Dispatchers.IO) { repo.fetch(s.selectedBaseUrl, token) } }
            .onSuccess { result ->
                val selected = result.servers.minByOrNull { it.pingMs ?: Long.MAX_VALUE }?.id
                    ?: result.servers.firstOrNull()?.id
                log("Sub", "Loaded ${result.servers.size} configs for $account")
                update {
                    it.copy(
                        usage = result.usage,
                        servers = result.servers,
                        selectedServerId = selected,
                        status = "Loaded ${result.servers.size} configs"
                    )
                }
            }
            .onFailure { err ->
                val msg = err.message?.takeIf { it.isNotBlank() }
                    ?: err.javaClass.simpleName.ifBlank { "Unknown error" }
                log("Sub", "Subscription failed", err)
                update { it.copy(status = "Subscription error: $msg") }
            }
    }

    fun pingAll() = viewModelScope.launch {
        update { it.copy(status = "Testing real Xray latency for all configs...") }
        pingAllInternal(selectBestWhenSmart = true)
    }

    private suspend fun pingAllInternal(selectBestWhenSmart: Boolean): ServerConfig? {
        val current = _state.value.servers
        if (current.isEmpty()) {
            log("Ping", "No configs to ping")
            update { it.copy(status = "No configs to ping") }
            return null
        }

        log("Ping", "Running real Xray latency test for ${current.size} configs")
        update { it.copy(status = "Smart Best: testing real Xray latency...") }
        val ranked = withContext(Dispatchers.IO) { ping.pingAll(current) }
        val best = ping.best(ranked)
        log("Ping", best?.let { "Real Xray best: ${it.name} • ${it.pingMs}ms" } ?: "No reachable config")
        update {
            it.copy(
                servers = ranked,
                selectedServerId = if (selectBestWhenSmart && it.smartConnect) best?.id else it.selectedServerId,
                status = best?.let { server -> "Real Xray best: ${server.name} • ${server.pingMs}ms" } ?: "No reachable config"
            )
        }
        return best
    }

    fun pingOne(id: String) = viewModelScope.launch {
        val current = _state.value.servers
        val target = current.firstOrNull { it.id == id } ?: return@launch
        update {
            it.copy(
                servers = current.map { server -> if (server.id == id) server.copy(pingMs = null, error = "Pinging...") else server },
                status = "Testing real Xray latency for ${target.name}..."
            )
        }
        log("Ping", "Testing real Xray latency for ${target.name}")
        val updated = withContext(Dispatchers.IO) { ping.ping(target) }
        log("Ping", updated.pingMs?.let { "${updated.name}: ${it}ms" } ?: "${updated.name}: failed (${updated.error ?: "unknown"})")
        update {
            it.copy(
                servers = current.map { server -> if (server.id == id) updated else server }
                    .sortedWith(compareBy<ServerConfig> { server -> server.pingMs ?: Long.MAX_VALUE }.thenBy { server -> server.name }),
                status = updated.pingMs?.let { ms -> "${updated.name}: ${ms}ms" } ?: "Ping failed"
            )
        }
    }

    fun connectAfterPermission() {
        viewModelScope.launch {
            val before = _state.value
            val selected = if (before.smartConnect) {
                log("Connect", "Smart Best Server is ON; testing real Xray latency before auto-connect")
                update { it.copy(status = "Smart Best: real Xray latency test...") }
                pingAllInternal(selectBestWhenSmart = true)
            } else {
                before.servers.firstOrNull { it.id == before.selectedServerId }
            }

            if (selected == null) {
                log("Connect", "No reachable/selected server")
                update { it.copy(status = "No reachable config") }
                return@launch
            }

            startVpnWithServer(selected)
        }
    }

    private fun startVpnWithServer(selected: ServerConfig) {
        log("Connect", "Starting VPN with ${selected.name}${selected.pingMs?.let { " • real ${it}ms" } ?: ""}")
        prefs.edit()
            .putString("lastRawConfig", selected.raw)
            .putString("lastServerName", selected.name)
            .putString("lastServerId", selected.id)
            .apply()
        val intent = Intent(getApplication(), RkhVpnService::class.java)
            .setAction(RkhVpnService.ACTION_START)
            .putExtra(RkhVpnService.EXTRA_RAW_CONFIG, selected.raw)
            .putExtra(RkhVpnService.EXTRA_SERVER_NAME, selected.name)
        getApplication<Application>().startForegroundService(intent)
        startSpeedMeter()
        update { it.copy(connected = true, selectedServerId = selected.id, status = "Connecting ${selected.name}...") }
    }

    fun disconnect() {
        log("Connect", "Disconnect requested")
        val intent = Intent(getApplication(), RkhVpnService::class.java).setAction(RkhVpnService.ACTION_STOP)
        getApplication<Application>().startService(intent)
        stopSpeedMeter()
        update { it.copy(connected = false, downloadKbps = 0, uploadKbps = 0, status = "Disconnected") }
    }

    fun markDisconnected() = disconnect()

    fun markUiDisconnectedOnly() {
        stopSpeedMeter()
        update { it.copy(connected = false, downloadKbps = 0, uploadKbps = 0, status = "Disconnected") }
    }

    fun importQrImage(uri: Uri) = viewModelScope.launch {
        runCatching {
            withContext(Dispatchers.IO) {
                val input = getApplication<Application>().contentResolver.openInputStream(uri) ?: error("Cannot open image")
                val bmp = BitmapFactory.decodeStream(input) ?: error("Invalid image")
                decodeQrBitmap(bmp)
            }
        }.onSuccess { importText(it) }
            .onFailure { e ->
                log("QR", "QR image decode failed", e)
                update { it.copy(status = "QR failed: ${e.message ?: e.javaClass.simpleName}") }
            }
    }

    fun importQrBitmap(bitmap: Bitmap) = viewModelScope.launch {
        runCatching {
            withContext(Dispatchers.Default) { decodeQrBitmap(bitmap) }
        }.onSuccess { importText(it) }
            .onFailure { e ->
                log("QR", "QR bitmap decode failed", e)
                update { it.copy(status = "QR failed: ${e.message ?: e.javaClass.simpleName}") }
            }
    }

    private fun decodeQrBitmap(bmp: Bitmap): String {
        val pixels = IntArray(bmp.width * bmp.height)
        bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return MultiFormatReader().decode(
            BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bmp.width, bmp.height, pixels)))
        ).text
    }

    fun importScannedText(text: String) = importText(text)

    private fun importText(text: String) {
        val normalized = text.trim().replace(" ", "")
        log("QR", "Scanned: ${normalized.take(80)}")
        when {
            normalized.startsWith(SubscriptionRepository.PRIMARY_BASE) -> update {
                it.copy(
                    selectedBaseUrl = SubscriptionRepository.PRIMARY_BASE,
                    primaryToken = normalized.removePrefix(SubscriptionRepository.PRIMARY_BASE),
                    usage = loadUsageForBase(SubscriptionRepository.PRIMARY_BASE),
                    servers = loadServersForBase(SubscriptionRepository.PRIMARY_BASE),
                    selectedServerId = prefs.getString("selectedServerId_${suffix(SubscriptionRepository.PRIMARY_BASE)}", null),
                    status = "Standard subscription QR imported"
                )
            }
            normalized.startsWith(SubscriptionRepository.PREMIUM_BASE) -> update {
                it.copy(
                    selectedBaseUrl = SubscriptionRepository.PREMIUM_BASE,
                    premiumToken = normalized.removePrefix(SubscriptionRepository.PREMIUM_BASE),
                    usage = loadUsageForBase(SubscriptionRepository.PREMIUM_BASE),
                    servers = loadServersForBase(SubscriptionRepository.PREMIUM_BASE),
                    selectedServerId = prefs.getString("selectedServerId_${suffix(SubscriptionRepository.PREMIUM_BASE)}", null),
                    status = "Premium subscription QR imported"
                )
            }
            normalized.startsWith("http://sub6.iranclude.ir:2096/sub/") -> update {
                it.copy(
                    selectedBaseUrl = SubscriptionRepository.PREMIUM_BASE,
                    premiumToken = normalized.removePrefix("http://sub6.iranclude.ir:2096/sub/"),
                    usage = loadUsageForBase(SubscriptionRepository.PREMIUM_BASE),
                    servers = loadServersForBase(SubscriptionRepository.PREMIUM_BASE),
                    selectedServerId = prefs.getString("selectedServerId_${suffix(SubscriptionRepository.PREMIUM_BASE)}", null),
                    status = "Premium subscription QR imported"
                )
            }
            normalized.startsWith("vmess://") || normalized.startsWith("vless://") || normalized.startsWith("trojan://") || normalized.startsWith("ss://") -> update {
                it.copy(servers = repo.parseConfigs(normalized), status = "Config imported")
            }
            normalized.isNotBlank() -> update {
                val base = it.selectedBaseUrl
                if (base == SubscriptionRepository.PREMIUM_BASE) {
                    it.copy(premiumToken = normalized, status = "Premium code imported from QR")
                } else {
                    it.copy(primaryToken = normalized, status = "Standard code imported from QR")
                }
            }
            else -> update { it.copy(status = "QR is empty") }
        }
    }

    fun refreshLogs() {
        _state.value = _state.value.copy(logs = RKhVpnLogStore.read(getApplication()))
    }

    fun clearLogs() {
        RKhVpnLogStore.clear(getApplication())
        _state.value = _state.value.copy(logs = emptyList(), status = "Logs cleared")
    }

    fun copyLogs() {
        val text = RKhVpnLogStore.read(getApplication()).joinToString("\n")
        val cm = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("SIMORGH Private logs", text))
        update { it.copy(status = "Logs copied") }
    }

    private fun log(source: String, message: String, throwable: Throwable? = null) {
        RKhVpnLogStore.append(getApplication(), source, message, throwable)
        _state.value = _state.value.copy(logs = RKhVpnLogStore.read(getApplication()))
    }

    private fun schedule(on: Boolean) {
        val wm = WorkManager.getInstance(getApplication())
        if (!on) {
            wm.cancelUniqueWork("rkh_auto_sub")
            return
        }
        wm.enqueueUniquePeriodicWork(
            "rkh_auto_sub",
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<SubscriptionUpdateWorker>(15, TimeUnit.MINUTES).build()
        )
    }

    private fun startSpeedMeter() {
        speedJob?.cancel()
        speedJob = viewModelScope.launch {
            var lastRx = TrafficStats.getTotalRxBytes().coerceAtLeast(0L)
            var lastTx = TrafficStats.getTotalTxBytes().coerceAtLeast(0L)
            var tick = 0
            update { it.copy(traffic = emptyList(), downloadKbps = 0, uploadKbps = 0) }
            while (isActive && _state.value.connected) {
                delay(1000)
                val rx = TrafficStats.getTotalRxBytes().coerceAtLeast(0L)
                val tx = TrafficStats.getTotalTxBytes().coerceAtLeast(0L)
                val down = ((rx - lastRx).coerceAtLeast(0L) * 8L) / 1000L
                val up = ((tx - lastTx).coerceAtLeast(0L) * 8L) / 1000L
                lastRx = rx
                lastTx = tx
                tick += 1
                val sample = SpeedSample("${tick}s", down, up)
                update {
                    it.copy(
                        downloadKbps = down,
                        uploadKbps = up,
                        traffic = (it.traffic + sample).takeLast(32)
                    )
                }
            }
        }
    }

    private fun stopSpeedMeter() {
        speedJob?.cancel()
        speedJob = null
    }

    private fun startFiveMinuteSubscriptionUpdates() {
        autoSubJob?.cancel()
        autoSubJob = viewModelScope.launch {
            delay(300_000)
            while (isActive) {
                if (_state.value.autoUpdate) refreshAllSubscriptionsSilently()
                delay(300_000)
            }
        }
    }

    private suspend fun refreshAllSubscriptionsSilently() {
        val snapshot = _state.value
        refreshBaseSilently(SubscriptionRepository.PRIMARY_BASE, snapshot.primaryToken)
        refreshBaseSilently(SubscriptionRepository.PREMIUM_BASE, snapshot.premiumToken)
    }

    private suspend fun refreshBaseSilently(base: String, token: String) {
        if (token.isBlank()) return
        val label = if (base == SubscriptionRepository.PREMIUM_BASE) "Premium" else "Standard"
        runCatching { withContext(Dispatchers.IO) { repo.fetch(base, token) } }
            .onSuccess { result ->
                val key = suffix(base)
                val keepSelected = prefs.getString("selectedServerId_$key", null)
                val selected = keepSelected?.takeIf { id -> result.servers.any { it.id == id } }
                    ?: result.servers.minByOrNull { it.pingMs ?: Long.MAX_VALUE }?.id
                    ?: result.servers.firstOrNull()?.id
                prefs.edit()
                    .putLong("usedBytes_$key", result.usage.usedBytes)
                    .putLong("totalBytes_$key", result.usage.totalBytes)
                    .putString("servers_$key", encodeServers(result.servers))
                    .putString("selectedServerId_$key", selected)
                    .apply()
                if (_state.value.selectedBaseUrl == base) {
                    update { it.copy(usage = result.usage, servers = result.servers, selectedServerId = selected, status = "$label subscription updated") }
                }
                log("Sub", "$label subscription auto-updated (${result.servers.size} configs)")
            }
            .onFailure { log("Sub", "$label auto-update failed", it) }
    }

    private fun startSmartBestServerLoop() {
        smartJob?.cancel()
        smartJob = viewModelScope.launch {
            delay(300_000)
            while (isActive) {
                val s = _state.value
                if (s.connected && s.smartConnect && s.servers.isNotEmpty()) {
                    val previousId = s.selectedServerId
                    log("Smart", "5-minute real Xray latency check started")
                    val best = pingAllInternal(selectBestWhenSmart = true)
                    if (best != null && best.id != previousId) {
                        log("Smart", "Auto-switching to real-latency best config: ${best.name} • ${best.pingMs}ms")
                        startVpnWithServer(best)
                    }
                }
                delay(300_000)
            }
        }
    }

    private fun startConnectionStateSync() {
        connectionSyncJob?.cancel()
        connectionSyncJob = viewModelScope.launch {
            while (isActive) {
                val serviceConnected = prefs.getBoolean("serviceConnected", false)
                val current = _state.value
                if (serviceConnected != current.connected) {
                    val lastName = prefs.getString("lastServerName", "").orEmpty()
                    update {
                        it.copy(
                            connected = serviceConnected,
                            status = if (serviceConnected) {
                                if (lastName.isBlank()) "Connected" else "Connected: $lastName"
                            } else "Disconnected",
                            downloadKbps = if (serviceConnected) it.downloadKbps else 0,
                            uploadKbps = if (serviceConnected) it.uploadKbps else 0
                        )
                    }
                    if (serviceConnected) startSpeedMeter() else stopSpeedMeter()
                }
                delay(1000)
            }
        }
    }

    private fun update(block: (AppState) -> AppState) {
        val next = block(_state.value).copy(logs = RKhVpnLogStore.read(getApplication()))
        _state.value = next
        saveState(next)
    }

    private fun loadSavedState(): AppState {
        val base = prefs.getString("selectedBaseUrl", SubscriptionRepository.PRIMARY_BASE) ?: SubscriptionRepository.PRIMARY_BASE
        return AppState(
            primaryToken = prefs.getString("primaryToken", "").orEmpty(),
            premiumToken = prefs.getString("premiumToken", "").orEmpty(),
            selectedBaseUrl = base,
            usage = loadUsageForBase(base),
            servers = loadServersForBase(base),
            selectedServerId = prefs.getString("selectedServerId_${suffix(base)}", null),
            connected = prefs.getBoolean("serviceConnected", false),
            smartConnect = prefs.getBoolean("smartConnect", true),
            autoUpdate = true,
            darkTheme = prefs.getBoolean("darkTheme", true),
            monet = true,
            status = if (prefs.getBoolean("serviceConnected", false)) {
                prefs.getString("lastServerName", "Connected")?.let { if (it.isBlank()) "Connected" else "Connected: $it" } ?: "Connected"
            } else "Ready",
            logs = RKhVpnLogStore.read(getApplication())
        )
    }

    private fun saveState(s: AppState) {
        val key = suffix(s.selectedBaseUrl)
        prefs.edit()
            .putString("primaryToken", s.primaryToken)
            .putString("premiumToken", s.premiumToken)
            .putString("selectedBaseUrl", s.selectedBaseUrl)
            .putLong("usedBytes_$key", s.usage.usedBytes)
            .putLong("totalBytes_$key", s.usage.totalBytes)
            .putString("servers_$key", encodeServers(s.servers))
            .putString("selectedServerId_$key", s.selectedServerId)
            .putBoolean("smartConnect", s.smartConnect)
            .putBoolean("darkTheme", s.darkTheme)
            .apply()
    }

    private fun suffix(base: String): String = if (base == SubscriptionRepository.PREMIUM_BASE) "premium" else "primary"

    private fun loadUsageForBase(base: String): UsageInfo {
        val key = suffix(base)
        return UsageInfo(prefs.getLong("usedBytes_$key", 0L), prefs.getLong("totalBytes_$key", 0L))
    }

    private fun loadServersForBase(base: String): List<ServerConfig> {
        val key = suffix(base)
        val raw = prefs.getString("servers_$key", "").orEmpty()
        return runCatching { decodeServers(raw) }.getOrDefault(emptyList())
    }

    private fun encodeServers(servers: List<ServerConfig>): String {
        val arr = JSONArray()
        servers.forEach { s ->
            arr.put(JSONObject().apply {
                put("id", s.id)
                put("name", s.name)
                put("raw", s.raw)
                put("host", s.host)
                put("port", s.port ?: 0)
                put("ping", s.pingMs ?: -1L)
                put("error", s.error)
            })
        }
        return arr.toString()
    }

    private fun decodeServers(raw: String): List<ServerConfig> {
        if (raw.isBlank()) return emptyList()
        val arr = JSONArray(raw)
        return List(arr.length()) { index ->
            val o = arr.getJSONObject(index)
            val port = o.optInt("port", 0).takeIf { it > 0 }
            val pingMs = o.optLong("ping", -1L).takeIf { it >= 0L }
            ServerConfig(
                id = o.optString("id"),
                name = o.optString("name"),
                raw = o.optString("raw"),
                host = o.optString("host").ifBlank { null },
                port = port,
                pingMs = pingMs,
                error = o.optString("error").ifBlank { null }
            )
        }
    }

    class Factory(private val app: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(c: Class<T>): T = RKhVpnViewModel(app) as T
    }
}
