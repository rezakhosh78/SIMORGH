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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

    private enum class PendingVpnMode { PUBLIC, PRIVATE, CF, SIMPLE, SIMPLE_NEXT, SIMPLE_CONFIG, FRAGMENT, NIPO, MASTERDNS }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 12)
        }

        setContent {
            var pendingMode by remember { mutableStateOf<PendingVpnMode?>(null) }

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
                        safeStopService(Intent(this, RkhVpnService::class.java).setAction(RkhVpnService.ACTION_STOP))
                        vm.markDisconnected()
                        publicVm.connectCfAfterPermission()
                    }
                    PendingVpnMode.SIMPLE -> {
                        safeStopService(Intent(this, SimorghPublicVpnService::class.java).setAction(SimorghPublicVpnService.ACTION_STOP))
                        // Simple mode starts through RkhVpnService too. Do not send ACTION_STOP
                        // to that service immediately before START, because it can race and
                        // rewrite the Simple header back to "Simple XRAY disconnected".
                        vm.markUiDisconnectedOnly()
                        publicVm.prepareSimpleConnectUi()
                        publicVm.simpleConnectAfterPermission()
                    }
                    PendingVpnMode.SIMPLE_NEXT -> {
                        safeStopService(Intent(this, SimorghPublicVpnService::class.java).setAction(SimorghPublicVpnService.ACTION_STOP))
                        vm.markUiDisconnectedOnly()
                        publicVm.simpleConnectNextHealthyAfterPermission()
                    }
                    PendingVpnMode.SIMPLE_CONFIG -> {
                        safeStopService(Intent(this, SimorghPublicVpnService::class.java).setAction(SimorghPublicVpnService.ACTION_STOP))
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
                    PendingVpnMode.MASTERDNS -> {
                        safeStopService(Intent(this, SimorghPublicVpnService::class.java).setAction(SimorghPublicVpnService.ACTION_STOP))
                        vm.markUiDisconnectedOnly()
                        publicVm.masterDnsConnectAfterPermission()
                    }
                }
            }

            val vpnPermission = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) { result ->
                val mode = pendingMode
                pendingMode = null
                if (result.resultCode == RESULT_OK && mode != null) {
                    startSelectedMode(mode)
                } else {
                    vm.updateStatus("VPN permission denied")
                    if (mode == PendingVpnMode.SIMPLE || mode == PendingVpnMode.SIMPLE_NEXT || mode == PendingVpnMode.SIMPLE_CONFIG) publicVm.simpleDisconnect()
                    if (mode == PendingVpnMode.NIPO) publicVm.nipoDisconnect()
                    if (mode == PendingVpnMode.FRAGMENT) publicVm.fragmentDisconnect()
                    if (mode == PendingVpnMode.MASTERDNS) publicVm.masterDnsDisconnect()
                }
            }

            val masterDnsResolverFile = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri ->
                if (uri != null) {
                    runCatching {
                        contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
                    }.onSuccess { text ->
                        publicVm.importMasterDnsResolversText(text)
                    }.onFailure {
                        publicVm.refreshMasterDnsLogs()
                    }
                }
            }

            fun requestVpn(mode: PendingVpnMode) {
                if (pendingMode != null) {
                    vm.updateStatus("VPN permission is already pending")
                    return
                }
                pendingMode = mode
                runCatching { VpnService.prepare(this) }
                    .onSuccess { prepareIntent ->
                        if (prepareIntent != null) {
                            runCatching { vpnPermission.launch(prepareIntent) }
                                .onFailure {
                                    pendingMode = null
                                    vm.updateStatus("VPN permission launcher error")
                                    if (mode == PendingVpnMode.NIPO) publicVm.nipoDisconnect()
                                    if (mode == PendingVpnMode.FRAGMENT) publicVm.fragmentDisconnect()
                                    if (mode == PendingVpnMode.SIMPLE || mode == PendingVpnMode.SIMPLE_NEXT || mode == PendingVpnMode.SIMPLE_CONFIG) publicVm.simpleDisconnect()
                                    if (mode == PendingVpnMode.MASTERDNS) publicVm.masterDnsDisconnect()
                                }
                        } else {
                            // Permission is already granted; no ActivityResult callback will run,
                            // so clear the pending flag before starting. Otherwise later MSP/CF
                            // connect clicks are ignored as "permission is already pending".
                            pendingMode = null
                            startSelectedMode(mode)
                        }
                    }
                    .onFailure {
                        pendingMode = null
                        vm.updateStatus("VPN permission prepare error")
                        if (mode == PendingVpnMode.NIPO) publicVm.nipoDisconnect()
                        if (mode == PendingVpnMode.FRAGMENT) publicVm.fragmentDisconnect()
                        if (mode == PendingVpnMode.SIMPLE || mode == PendingVpnMode.SIMPLE_NEXT || mode == PendingVpnMode.SIMPLE_CONFIG) publicVm.simpleDisconnect()
                        if (mode == PendingVpnMode.MASTERDNS) publicVm.masterDnsDisconnect()
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
                    publicVm.prepareSimpleConfigConnectUi(index)
                    requestVpn(PendingVpnMode.SIMPLE_CONFIG)
                },
                onFragmentConnect = {
                    runCatching {
                        val fragment = publicVm.state.value
                        when {
                            fragment.fragmentConnecting -> {
                                // Ignore repeated connect taps while Fragment is already starting.
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
                onMasterDnsConnect = {
                    val master = publicVm.state.value
                    if (master.masterDnsConnected || master.masterDnsConnecting) {
                        publicVm.masterDnsDisconnect()
                    } else {
                        publicVm.prepareMasterDnsConnectUi()
                        requestVpn(PendingVpnMode.MASTERDNS)
                    }
                },
                onMasterDnsImportResolvers = { masterDnsResolverFile.launch(arrayOf("text/plain", "text/*", "application/octet-stream")) },
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