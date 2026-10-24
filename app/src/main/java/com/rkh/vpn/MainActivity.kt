package com.rkh.vpn

import android.Manifest
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import com.rkh.vpn.service.RkhVpnService
import com.rkh.vpn.service.RavanVpnService
import com.rkh.vpn.service.SimorghPublicVpnService
import com.rkh.callisto.service.CallistoVpnService
import com.rkh.vpn.data.RKhVpnLogStore
import com.rkh.vpn.ui.RKhVpnApp
import com.rkh.vpn.ui.RKhVpnViewModel
import com.rkh.vpn.ui.SimorghPublicViewModel

class MainActivity : ComponentActivity() {
    private fun safeStopService(intent: Intent) {
        runCatching { stopService(intent) }
    }

    private val vm: RKhVpnViewModel by viewModels { RKhVpnViewModel.Factory(application) }
    private val publicVm: SimorghPublicViewModel by viewModels { SimorghPublicViewModel.Factory(application) }

    private enum class PendingVpnMode { PUBLIC, PRIVATE, CF, SIMPLE, SIMPLE_NEXT, SIMPLE_CONFIG, FRAGMENT, NIPO, STORMDNS, RAVAN, CALLISTO, DESYNC }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 12)
        }

        setContent {
            fun pendingVpnPrefs() = getSharedPreferences("simorgh_pending_vpn", MODE_PRIVATE)
            fun loadPendingVpnMode(): PendingVpnMode? = runCatching {
                val saved = pendingVpnPrefs().getString("mode", "").orEmpty()
                if (saved.isBlank()) null else PendingVpnMode.valueOf(saved)
            }.getOrNull()
            fun savePendingVpnMode(mode: PendingVpnMode?) {
                pendingVpnPrefs().edit().apply {
                    if (mode == null) {
                        remove("mode")
                        remove("startedAt")
                    } else {
                        putString("mode", mode.name)
                        putLong("startedAt", System.currentTimeMillis())
                    }
                }.apply()
            }
            fun pendingVpnAgeMs(): Long {
                val started = pendingVpnPrefs().getLong("startedAt", 0L)
                return if (started <= 0L) Long.MAX_VALUE else System.currentTimeMillis() - started
            }
            fun loadPendingTarget(): String? = pendingVpnPrefs().getString("target", null)?.takeIf { it.isNotBlank() }
            fun savePendingTarget(target: String?) {
                pendingVpnPrefs().edit().apply {
                    if (target.isNullOrBlank()) remove("target") else putString("target", target)
                }.apply()
            }

            var pendingMode by remember { mutableStateOf(loadPendingVpnMode()) }
            var pendingTarget by remember { mutableStateOf(loadPendingTarget()) }
            fun setPendingMode(mode: PendingVpnMode?) {
                pendingMode = mode
                savePendingVpnMode(mode)
            }
            fun setPendingTarget(target: String?) {
                pendingTarget = target?.takeIf { it.isNotBlank() }
                savePendingTarget(pendingTarget)
            }

            fun stopIndependentTransports(
                reason: String,
                stopRavan: Boolean = true,
                stopCallisto: Boolean = true
            ) {
                RKhVpnLogStore.append(this, "IndependentUI", "Stopping independent transports before $reason")
                if (stopRavan) {
                    safeStopService(Intent(this, RavanVpnService::class.java).setAction(RavanVpnService.ACTION_STOP))
                }
                if (stopCallisto) {
                    safeStopService(Intent(this, CallistoVpnService::class.java).setAction(CallistoVpnService.ACTION_STOP))
                }
            }

            fun launchRavan(page: String) {
                stopIndependentTransports("Ravan/$page", stopRavan = false)
                RKhVpnLogStore.append(this, "IndependentUI", "Launching Ravan transport=$page")
                val intent = Intent(this, RavanVpnService::class.java)
                    .setAction(RavanVpnService.ACTION_START)
                    .putExtra(RavanVpnService.EXTRA_TRANSPORT, page)
                    .putExtra(RavanVpnService.EXTRA_NESTED, page == "warp_in_warp")
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
            }
            fun launchCallisto(psiphon: Boolean) {
                stopIndependentTransports("Callisto/${if (psiphon) "psiphon" else "tor"}", stopCallisto = false)
                RKhVpnLogStore.append(this, "IndependentUI", "Launching Callisto mode=${if (psiphon) "psiphon" else "tor"}")
                val exitCountry = getSharedPreferences("callisto_preferences", MODE_PRIVATE)
                    .getString(CallistoVpnService.EXTRA_EXIT_COUNTRY, "BEST")
                    .orEmpty()
                    .ifBlank { "BEST" }
                val intent = Intent(this, CallistoVpnService::class.java)
                    .putExtra(CallistoVpnService.EXTRA_PSIPHON_ENABLED, psiphon)
                    .putExtra(CallistoVpnService.EXTRA_EXIT_COUNTRY, exitCountry)
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
            }
            fun launchDesync() {
                val prefs = getSharedPreferences("desync_preferences", MODE_PRIVATE)
                val desyncState = getSharedPreferences("simorgh_desync_state", MODE_PRIVATE or MODE_MULTI_PROCESS)
                val intent = Intent(this, RkhVpnService::class.java)
                    .setAction(RkhVpnService.ACTION_START_DESYNC)
                    .putExtra(RkhVpnService.EXTRA_RAW_CONFIG, prefs.getString("raw_config", "").orEmpty())
                    .putExtra(RkhVpnService.EXTRA_SERVER_NAME, "SIMORGH Desync")
                    .putExtra(RkhVpnService.EXTRA_DESYNC_PROFILE, prefs.getString("profile", "Balanced").orEmpty())
                    .putExtra(RkhVpnService.EXTRA_DESYNC_CUSTOM_ARGS, prefs.getString("custom_args", "").orEmpty())
                RKhVpnLogStore.append(this, "DesyncUI", "Launching Desync profile=${prefs.getString("profile", "Balanced")}")
                desyncState.edit().putString("status", "CONNECTING").putString("detail", "Starting Desync tunnel").apply()
                runCatching {
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
                }.onFailure { error ->
                    desyncState.edit().putString("status", "ERROR").putString("detail", error.message ?: "Could not start Desync service").apply()
                    RKhVpnLogStore.append(this, "DesyncUI", "Desync service start failed", error)
                }
            }

            fun setDesyncUiState(status: String, detail: String) {
                getSharedPreferences("simorgh_desync_state", MODE_PRIVATE or MODE_MULTI_PROCESS).edit()
                    .putString("status", status)
                    .putString("detail", detail)
                    .apply()
            }

            fun startSelectedMode(mode: PendingVpnMode, target: String? = null) {
                when (mode) {
                    PendingVpnMode.PUBLIC -> {
                        stopIndependentTransports("MSP")
                        safeStopService(Intent(this, RkhVpnService::class.java).setAction(RkhVpnService.ACTION_STOP))
                        vm.markDisconnected()
                        publicVm.connectAfterPermission()
                    }
                    PendingVpnMode.PRIVATE -> {
                        stopIndependentTransports("Private")
                        safeStopService(Intent(this, SimorghPublicVpnService::class.java).setAction(SimorghPublicVpnService.ACTION_STOP))
                        publicVm.disconnect()
                        vm.connectAfterPermission()
                    }
                    PendingVpnMode.CF -> {
                        stopIndependentTransports("CF")
                        // Do not call stopService() here. It destroys the shared :vpncore
                        // process asynchronously and can kill the CF start that follows,
                        // which was the reason a second Connect tap was needed. The
                        // ViewModel now performs an ordered pre-connect cleanup.
                        vm.markDisconnected()
                        publicVm.connectCfAfterPermission()
                    }
                    PendingVpnMode.SIMPLE -> {
                        stopIndependentTransports("Simple")
                        // Simple starts through RkhVpnService. Ordered cleanup happens in
                        // simpleConnectAfterPermission(); calling stopService() here races
                        // with the new START and can leave tun2proxy in native -4.
                        vm.markUiDisconnectedOnly()
                        publicVm.prepareSimpleConnectUi()
                        publicVm.simpleConnectAfterPermission()
                    }
                    PendingVpnMode.SIMPLE_NEXT -> {
                        stopIndependentTransports("Simple next")
                        vm.markUiDisconnectedOnly()
                        publicVm.simpleConnectNextHealthyAfterPermission()
                    }
                    PendingVpnMode.SIMPLE_CONFIG -> {
                        stopIndependentTransports("Simple config")
                        vm.markUiDisconnectedOnly()
                        publicVm.simpleConnectSelectedAfterPermission()
                    }
                    PendingVpnMode.FRAGMENT -> {
                        stopIndependentTransports("Fragment")
                        safeStopService(Intent(this, SimorghPublicVpnService::class.java).setAction(SimorghPublicVpnService.ACTION_STOP))
                        vm.markUiDisconnectedOnly()
                        publicVm.fragmentConnectAfterPermission()
                    }
                    PendingVpnMode.NIPO -> {
                        stopIndependentTransports("NipoVPN")
                        safeStopService(Intent(this, SimorghPublicVpnService::class.java).setAction(SimorghPublicVpnService.ACTION_STOP))
                        vm.markUiDisconnectedOnly()
                        publicVm.nipoConnectAfterPermission()
                    }
                    PendingVpnMode.STORMDNS -> {
                        stopIndependentTransports("MasterDNS")
                        safeStopService(Intent(this, SimorghPublicVpnService::class.java).setAction(SimorghPublicVpnService.ACTION_STOP))
                        vm.markUiDisconnectedOnly()
                        publicVm.stormDnsConnectAfterPermission()
                    }
                    PendingVpnMode.RAVAN -> launchRavan(target?.takeIf {
                        it in setOf("warp", "warp_wireguard", "warp_awg", "warp_plus", "warp_in_warp", "soardns", "dnstt")
                    } ?: "warp_plus")
                    PendingVpnMode.CALLISTO -> launchCallisto(target == "psiphon")
                    PendingVpnMode.DESYNC -> {
                        stopIndependentTransports("Desync")
                        safeStopService(Intent(this, SimorghPublicVpnService::class.java).setAction(SimorghPublicVpnService.ACTION_STOP))
                        publicVm.markUiDisconnectedOnly()
                        launchDesync()
                    }
                }
            }

            fun resetModeAfterVpnPermissionFailure(mode: PendingVpnMode?) {
                vm.updateStatus("VPN permission denied")
                if (mode == PendingVpnMode.SIMPLE || mode == PendingVpnMode.SIMPLE_NEXT || mode == PendingVpnMode.SIMPLE_CONFIG) publicVm.simpleDisconnect()
                if (mode == PendingVpnMode.NIPO) publicVm.nipoDisconnect()
                if (mode == PendingVpnMode.FRAGMENT) publicVm.fragmentDisconnect()
                if (mode == PendingVpnMode.STORMDNS) publicVm.stormDnsDisconnect()
                if (mode == PendingVpnMode.DESYNC) setDesyncUiState("DISCONNECTED", "VPN permission was not granted")
            }

            val vpnPermission = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) { result ->
                val mode = pendingMode ?: loadPendingVpnMode()
                val target = pendingTarget ?: loadPendingTarget()
                RKhVpnLogStore.append(this@MainActivity, "IndependentUI", "VPN permission result mode=$mode target=${target ?: "none"} result=${result.resultCode}")
                setPendingTarget(null)
                setPendingMode(null)
                val permissionGranted = result.resultCode == RESULT_OK || runCatching { VpnService.prepare(this@MainActivity) == null }.getOrDefault(false)
                if (permissionGranted && mode != null) {
                    startSelectedMode(mode, target)
                } else {
                    resetModeAfterVpnPermissionFailure(mode)
                }
            }

            LaunchedEffect(Unit) {
                while (true) {
                    delay(350L)
                    val mode = pendingMode ?: loadPendingVpnMode()
                    if (mode != null) {
                        val target = pendingTarget ?: loadPendingTarget()
                        val permissionNowGranted = runCatching { VpnService.prepare(this@MainActivity) == null }.getOrDefault(false)
                        if (permissionNowGranted) {
                            setPendingTarget(null)
                            setPendingMode(null)
                            startSelectedMode(mode, target)
                        } else if (pendingVpnAgeMs() > 90_000L) {
                            setPendingTarget(null)
                            setPendingMode(null)
                            resetModeAfterVpnPermissionFailure(mode)
                        }
                    }
                }
            }

            val stormDnsResolverFile = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri ->
                if (uri != null) {
                    runCatching {
                        contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
                    }.onSuccess { text ->
                        publicVm.importStormDnsResolversText(text)
                    }.onFailure {
                        publicVm.refreshStormDnsLogs()
                    }
                }
            }

            fun requestVpn(mode: PendingVpnMode, target: String? = null) {
                val existingPending = pendingMode ?: loadPendingVpnMode()
                if (existingPending != null) {
                    val permissionNowGranted = runCatching { VpnService.prepare(this@MainActivity) == null }.getOrDefault(false)
                    if (permissionNowGranted) {
                        val existingTarget = pendingTarget ?: loadPendingTarget()
                        setPendingTarget(null)
                        setPendingMode(null)
                        startSelectedMode(existingPending, existingTarget)
                        return
                    } else if (pendingVpnAgeMs() <= 90_000L) {
                        vm.updateStatus("VPN permission is already pending")
                    } else {
                        setPendingTarget(null)
                        setPendingMode(null)
                    }
                    if (pendingMode != null || loadPendingVpnMode() != null) return
                }
                setPendingTarget(target)
                setPendingMode(mode)
                RKhVpnLogStore.append(this, "IndependentUI", "VPN request mode=$mode target=${target ?: "none"}")
                runCatching { VpnService.prepare(this@MainActivity) }
                    .onSuccess { prepareIntent ->
                        if (prepareIntent != null) {
                            runCatching { vpnPermission.launch(prepareIntent) }
                                .onFailure {
                                    setPendingTarget(null)
                                    setPendingMode(null)
                                    vm.updateStatus("VPN permission launcher error")
                                    if (mode == PendingVpnMode.NIPO) publicVm.nipoDisconnect()
                                    if (mode == PendingVpnMode.FRAGMENT) publicVm.fragmentDisconnect()
                                    if (mode == PendingVpnMode.SIMPLE || mode == PendingVpnMode.SIMPLE_NEXT || mode == PendingVpnMode.SIMPLE_CONFIG) publicVm.simpleDisconnect()
                                    if (mode == PendingVpnMode.STORMDNS) publicVm.stormDnsDisconnect()
                                    if (mode == PendingVpnMode.DESYNC) setDesyncUiState("ERROR", "VPN permission dialog could not open")
                                }
                        } else {
                            // Permission is already granted; no ActivityResult callback will run,
                            // so clear the pending flag before starting. Otherwise later MSP/CF
                            // connect clicks are ignored as "permission is already pending".
                            setPendingTarget(null)
                            setPendingMode(null)
                            startSelectedMode(mode, target)
                        }
                    }
                    .onFailure {
                        setPendingTarget(null)
                        setPendingMode(null)
                        vm.updateStatus("VPN permission prepare error")
                        if (mode == PendingVpnMode.NIPO) publicVm.nipoDisconnect()
                        if (mode == PendingVpnMode.FRAGMENT) publicVm.fragmentDisconnect()
                        if (mode == PendingVpnMode.SIMPLE || mode == PendingVpnMode.SIMPLE_NEXT || mode == PendingVpnMode.SIMPLE_CONFIG) publicVm.simpleDisconnect()
                        if (mode == PendingVpnMode.STORMDNS) publicVm.stormDnsDisconnect()
                        if (mode == PendingVpnMode.DESYNC) setDesyncUiState("ERROR", "VPN permission check failed")
                    }
            }

            RKhVpnApp(
                vm = vm,
                publicVm = publicVm,
                onCfConnectIp = { ip ->
                    runCatching {
                        if (publicVm.prepareCfConnectIp(ip)) requestVpn(PendingVpnMode.CF)
                    }.onFailure { e ->
                        val summary = e.message ?: e.javaClass.simpleName
                        publicVm.setMspStartError("CF connect click error: $summary")
                    }
                },
                onSimpleConnect = {
                    runCatching {
                        val simple = publicVm.state.value
                        if (simple.simpleConnected || simple.simpleConnecting) {
                            publicVm.simpleDisconnect()
                        } else {
                            publicVm.prepareSimpleConnectUi()
                            requestVpn(PendingVpnMode.SIMPLE)
                        }
                    }.onFailure { e ->
                        val summary = e.message ?: e.javaClass.simpleName
                        publicVm.setMspStartError("Simple click error: $summary")
                    }
                },
                onSimpleUpdate = { publicVm.updateSimpleSubscription() },
                onSimpleNextHealthy = {
                    val simple = publicVm.state.value
                    if (!simple.simpleServerlessEnabled && simple.simpleConfigCount > 1 && !simple.simpleConnecting) {
                        publicVm.prepareSimpleNextHealthyUi()
                        requestVpn(PendingVpnMode.SIMPLE_NEXT)
                    }
                },
                onSimpleClearCache = { publicVm.clearSimpleCache() },
                onSimplePingAll = { publicVm.pingAllSimpleConfigs() },
                onSimpleConnectConfig = { index ->
                    runCatching {
                        if (publicVm.prepareSimpleConfigConnectUi(index)) {
                            requestVpn(PendingVpnMode.SIMPLE_CONFIG)
                        }
                    }.onFailure { e ->
                        val summary = e.message ?: e.javaClass.simpleName
                        publicVm.setMspStartError("Simple config click error: $summary")
                    }
                },
                onSimpleCustomConnectConfig = { index ->
                    runCatching {
                        if (publicVm.prepareSimpleCustomConfigConnectUi(index)) {
                            requestVpn(PendingVpnMode.SIMPLE_CONFIG)
                        }
                    }.onFailure { e ->
                        val summary = e.message ?: e.javaClass.simpleName
                        publicVm.setMspStartError("Simple Sub/Config click error: $summary")
                    }
                },
                onFragmentConnect = {
                    runCatching {
                        val fragment = publicVm.state.value
                        when {
                            fragment.fragmentConnecting -> {
                                publicVm.fragmentDisconnect()
                            }
                            fragment.fragmentConnected -> {
                                publicVm.fragmentDisconnect()
                            }
                            else -> {
                                if (publicVm.prepareFragmentConnectUi()) requestVpn(PendingVpnMode.FRAGMENT)
                            }
                        }
                    }.onFailure { e ->
                        val summary = e.message ?: e.javaClass.simpleName
                        publicVm.setMspStartError("Fragment click error: $summary")
                    }
                },
                onFragmentConfigChanged = { publicVm.setFragmentConfigInput(it) },
                onFragmentAddressChanged = { publicVm.setFragmentAddress(it) },
                onFragmentPacketsChanged = { publicVm.setFragmentPackets(it) },
                onFragmentLengthsChanged = { publicVm.setFragmentLengths(it) },
                onFragmentDelaysChanged = { publicVm.setFragmentDelays(it) },
                onFragmentMaxSplitChanged = { publicVm.setFragmentMaxSplit(it) },
                onFragmentPing = { publicVm.pingFragmentConfig() },
                onFragmentAddProfile = { publicVm.addFragmentProfile(it) },
                onFragmentSelectProfile = { publicVm.selectFragmentProfile(it) },
                onFragmentPingProfile = { publicVm.pingFragmentProfile(it) },
                onFragmentRenameProfile = { oldName, newName -> publicVm.renameFragmentProfile(oldName, newName) },
                onFragmentDeleteProfile = { publicVm.deleteFragmentProfile(it) },
                onNipoConnect = {
                    val nipo = publicVm.state.value
                    if (nipo.nipoConnected || nipo.nipoConnecting) {
                        publicVm.nipoDisconnect()
                    } else {
                        if (publicVm.prepareNipoConnectUi()) {
                            requestVpn(PendingVpnMode.NIPO)
                        }
                    }
                },
                onStormDnsConnect = {
                    val storm = publicVm.state.value
                    if (storm.stormDnsConnected || storm.stormDnsConnecting) {
                        publicVm.stormDnsDisconnect()
                    } else {
                        publicVm.prepareStormDnsConnectUi()
                        requestVpn(PendingVpnMode.STORMDNS)
                    }
                },
                onStormDnsImportResolvers = { stormDnsResolverFile.launch(arrayOf("text/plain", "text/*", "application/octet-stream")) },
                onPrivateConnect = { requestVpn(PendingVpnMode.PRIVATE) },
                onPrivateStop = { vm.disconnect() },
                onPublicConnect = {
                    runCatching {
                        safeStopService(Intent(this, RkhVpnService::class.java).setAction(RkhVpnService.ACTION_STOP))
                        vm.markDisconnected()
                        if (publicVm.state.value.selectedRunMode == "vpn") {
                            requestVpn(PendingVpnMode.PUBLIC)
                        } else {
                            publicVm.startProxyMode()
                        }
                    }.onFailure { e ->
                        val summary = e.message ?: e.javaClass.simpleName
                        publicVm.setMspStartError("MSP connect click error: $summary")
                    }
                },
                onPublicProxy = {
                    publicVm.setRunMode("proxy")
                },
                onPublicStop = { publicVm.disconnect() },
                onRavanStart = { page -> requestVpn(PendingVpnMode.RAVAN, page) },
                onRavanStop = { startService(Intent(this, RavanVpnService::class.java).setAction(RavanVpnService.ACTION_STOP)) },
                onCallistoStart = { psiphon -> requestVpn(PendingVpnMode.CALLISTO, if (psiphon) "psiphon" else "tor") },
                onCallistoStop = { startService(Intent(this, CallistoVpnService::class.java).setAction(CallistoVpnService.ACTION_STOP)) },
                onDesyncStart = { raw, profile, custom ->
                    getSharedPreferences("desync_preferences", MODE_PRIVATE).edit()
                        .putString("raw_config", raw).putString("profile", profile).putString("custom_args", custom).apply()
                    requestVpn(PendingVpnMode.DESYNC)
                },
                onDesyncStop = {
                    setDesyncUiState("DISCONNECTING", "Stopping PingNG Desync")
                    val stopIntent = Intent(this, RkhVpnService::class.java)
                        .setAction(RkhVpnService.ACTION_STOP)
                        .putExtra(RkhVpnService.EXTRA_STOP_SOURCE, "desync")
                    runCatching { startService(stopIntent) }
                        .onFailure { error ->
                            setDesyncUiState("ERROR", error.message ?: "Could not stop Desync service")
                            RKhVpnLogStore.append(this, "DesyncUI", "Desync stop request failed", error)
                        }
                }
            )
        }
    }

}
