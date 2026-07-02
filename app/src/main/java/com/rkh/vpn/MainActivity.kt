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
import com.rkh.vpn.service.SimorghPublicVpnService
import com.rkh.vpn.ui.RKhVpnApp
import com.rkh.vpn.ui.RKhVpnViewModel
import com.rkh.vpn.ui.SimorghPublicViewModel

class MainActivity : ComponentActivity() {
    private fun safeStopService(intent: Intent) {
        runCatching { stopService(intent) }
    }

    private val vm: RKhVpnViewModel by viewModels { RKhVpnViewModel.Factory(application) }
    private val publicVm: SimorghPublicViewModel by viewModels { SimorghPublicViewModel.Factory(application) }

    private enum class PendingVpnMode { PUBLIC, PRIVATE, CF, SIMPLE, SIMPLE_NEXT, SIMPLE_CONFIG, FRAGMENT, NIPO, STORMDNS }

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

            var pendingMode by remember { mutableStateOf(loadPendingVpnMode()) }
            fun setPendingMode(mode: PendingVpnMode?) {
                pendingMode = mode
                savePendingVpnMode(mode)
            }

            fun startSelectedMode(mode: PendingVpnMode) {
                when (mode) {
                    PendingVpnMode.PUBLIC -> {
                        safeStopService(Intent(this, RkhVpnService::class.java).setAction(RkhVpnService.ACTION_STOP))
                        vm.markDisconnected()
                        publicVm.connectAfterPermission()
                    }
                    PendingVpnMode.PRIVATE -> {
                        safeStopService(Intent(this, SimorghPublicVpnService::class.java).setAction(SimorghPublicVpnService.ACTION_STOP))
                        publicVm.disconnect()
                        vm.connectAfterPermission()
                    }
                    PendingVpnMode.CF -> {
                        // Do not call stopService() here. It destroys the shared :vpncore
                        // process asynchronously and can kill the CF start that follows,
                        // which was the reason a second Connect tap was needed. The
                        // ViewModel now performs an ordered pre-connect cleanup.
                        vm.markDisconnected()
                        publicVm.connectCfAfterPermission()
                    }
                    PendingVpnMode.SIMPLE -> {
                        // Simple starts through RkhVpnService. Ordered cleanup happens in
                        // simpleConnectAfterPermission(); calling stopService() here races
                        // with the new START and can leave tun2proxy in native -4.
                        vm.markUiDisconnectedOnly()
                        publicVm.prepareSimpleConnectUi()
                        publicVm.simpleConnectAfterPermission()
                    }
                    PendingVpnMode.SIMPLE_NEXT -> {
                        vm.markUiDisconnectedOnly()
                        publicVm.simpleConnectNextHealthyAfterPermission()
                    }
                    PendingVpnMode.SIMPLE_CONFIG -> {
                        vm.markUiDisconnectedOnly()
                        publicVm.simpleConnectSelectedAfterPermission()
                    }
                    PendingVpnMode.FRAGMENT -> {
                        safeStopService(Intent(this, SimorghPublicVpnService::class.java).setAction(SimorghPublicVpnService.ACTION_STOP))
                        vm.markUiDisconnectedOnly()
                        publicVm.fragmentConnectAfterPermission()
                    }
                    PendingVpnMode.NIPO -> {
                        safeStopService(Intent(this, SimorghPublicVpnService::class.java).setAction(SimorghPublicVpnService.ACTION_STOP))
                        vm.markUiDisconnectedOnly()
                        publicVm.nipoConnectAfterPermission()
                    }
                    PendingVpnMode.STORMDNS -> {
                        safeStopService(Intent(this, SimorghPublicVpnService::class.java).setAction(SimorghPublicVpnService.ACTION_STOP))
                        vm.markUiDisconnectedOnly()
                        publicVm.stormDnsConnectAfterPermission()
                    }
                }
            }

            fun resetModeAfterVpnPermissionFailure(mode: PendingVpnMode?) {
                vm.updateStatus("VPN permission denied")
                if (mode == PendingVpnMode.SIMPLE || mode == PendingVpnMode.SIMPLE_NEXT || mode == PendingVpnMode.SIMPLE_CONFIG) publicVm.simpleDisconnect()
                if (mode == PendingVpnMode.NIPO) publicVm.nipoDisconnect()
                if (mode == PendingVpnMode.FRAGMENT) publicVm.fragmentDisconnect()
                if (mode == PendingVpnMode.STORMDNS) publicVm.stormDnsDisconnect()
            }

            val vpnPermission = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) { result ->
                val mode = pendingMode ?: loadPendingVpnMode()
                setPendingMode(null)
                val permissionGranted = result.resultCode == RESULT_OK || runCatching { VpnService.prepare(this@MainActivity) == null }.getOrDefault(false)
                if (permissionGranted && mode != null) {
                    startSelectedMode(mode)
                } else {
                    resetModeAfterVpnPermissionFailure(mode)
                }
            }

            LaunchedEffect(Unit) {
                while (true) {
                    delay(350L)
                    val mode = pendingMode ?: loadPendingVpnMode()
                    if (mode != null) {
                        val permissionNowGranted = runCatching { VpnService.prepare(this@MainActivity) == null }.getOrDefault(false)
                        if (permissionNowGranted) {
                            setPendingMode(null)
                            startSelectedMode(mode)
                        } else if (pendingVpnAgeMs() > 90_000L) {
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

            fun requestVpn(mode: PendingVpnMode) {
                val existingPending = pendingMode ?: loadPendingVpnMode()
                if (existingPending != null) {
                    val permissionNowGranted = runCatching { VpnService.prepare(this@MainActivity) == null }.getOrDefault(false)
                    if (permissionNowGranted) {
                        setPendingMode(null)
                        startSelectedMode(existingPending)
                        return
                    } else if (pendingVpnAgeMs() <= 90_000L) {
                        vm.updateStatus("VPN permission is already pending")
                    } else {
                        setPendingMode(null)
                    }
                    if (pendingMode != null || loadPendingVpnMode() != null) return
                }
                setPendingMode(mode)
                runCatching { VpnService.prepare(this@MainActivity) }
                    .onSuccess { prepareIntent ->
                        if (prepareIntent != null) {
                            runCatching { vpnPermission.launch(prepareIntent) }
                                .onFailure {
                                    setPendingMode(null)
                                    vm.updateStatus("VPN permission launcher error")
                                    if (mode == PendingVpnMode.NIPO) publicVm.nipoDisconnect()
                                    if (mode == PendingVpnMode.FRAGMENT) publicVm.fragmentDisconnect()
                                    if (mode == PendingVpnMode.SIMPLE || mode == PendingVpnMode.SIMPLE_NEXT || mode == PendingVpnMode.SIMPLE_CONFIG) publicVm.simpleDisconnect()
                                    if (mode == PendingVpnMode.STORMDNS) publicVm.stormDnsDisconnect()
                                }
                        } else {
                            // Permission is already granted; no ActivityResult callback will run,
                            // so clear the pending flag before starting. Otherwise later MSP/CF
                            // connect clicks are ignored as "permission is already pending".
                            setPendingMode(null)
                            startSelectedMode(mode)
                        }
                    }
                    .onFailure {
                        setPendingMode(null)
                        vm.updateStatus("VPN permission prepare error")
                        if (mode == PendingVpnMode.NIPO) publicVm.nipoDisconnect()
                        if (mode == PendingVpnMode.FRAGMENT) publicVm.fragmentDisconnect()
                        if (mode == PendingVpnMode.SIMPLE || mode == PendingVpnMode.SIMPLE_NEXT || mode == PendingVpnMode.SIMPLE_CONFIG) publicVm.simpleDisconnect()
                        if (mode == PendingVpnMode.STORMDNS) publicVm.stormDnsDisconnect()
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
                onPublicStop = { publicVm.disconnect() }
            )
        }
    }

}