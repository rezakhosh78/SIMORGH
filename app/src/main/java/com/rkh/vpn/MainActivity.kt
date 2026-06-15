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
    private val vm: RKhVpnViewModel by viewModels { RKhVpnViewModel.Factory(application) }
    private val publicVm: SimorghPublicViewModel by viewModels { SimorghPublicViewModel.Factory(application) }

    private enum class PendingVpnMode { PUBLIC, PRIVATE, CF, SIMPLE, SIMPLE_NEXT, SIMPLE_CONFIG, NIPO }

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
                        stopService(Intent(this, RkhVpnService::class.java).setAction(RkhVpnService.ACTION_STOP))
                        vm.markDisconnected()
                        publicVm.connectAfterPermission()
                    }
                    PendingVpnMode.PRIVATE -> {
                        stopService(Intent(this, SimorghPublicVpnService::class.java).setAction(SimorghPublicVpnService.ACTION_STOP))
                        publicVm.disconnect()
                        vm.connectAfterPermission()
                    }
                    PendingVpnMode.CF -> {
                        stopService(Intent(this, RkhVpnService::class.java).setAction(RkhVpnService.ACTION_STOP))
                        vm.markDisconnected()
                        publicVm.connectCfAfterPermission()
                    }
                    PendingVpnMode.SIMPLE -> {
                        stopService(Intent(this, SimorghPublicVpnService::class.java).setAction(SimorghPublicVpnService.ACTION_STOP))
                        // Simple mode starts through RkhVpnService too. Do not send ACTION_STOP
                        // to that service immediately before START, because it can race and
                        // rewrite the Simple header back to "Simple XRAY disconnected".
                        vm.markUiDisconnectedOnly()
                        publicVm.prepareSimpleConnectUi()
                        publicVm.simpleConnectAfterPermission()
                    }
                    PendingVpnMode.SIMPLE_NEXT -> {
                        stopService(Intent(this, SimorghPublicVpnService::class.java).setAction(SimorghPublicVpnService.ACTION_STOP))
                        vm.markUiDisconnectedOnly()
                        publicVm.simpleConnectNextHealthyAfterPermission()
                    }
                    PendingVpnMode.SIMPLE_CONFIG -> {
                        stopService(Intent(this, SimorghPublicVpnService::class.java).setAction(SimorghPublicVpnService.ACTION_STOP))
                        vm.markUiDisconnectedOnly()
                        publicVm.simpleConnectSelectedAfterPermission()
                    }
                    PendingVpnMode.NIPO -> {
                        stopService(Intent(this, SimorghPublicVpnService::class.java).setAction(SimorghPublicVpnService.ACTION_STOP))
                        vm.markUiDisconnectedOnly()
                        publicVm.nipoConnectAfterPermission()
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
                }
            }

            fun requestVpn(mode: PendingVpnMode) {
                pendingMode = mode
                val prepareIntent = VpnService.prepare(this)
                if (prepareIntent != null) {
                    vpnPermission.launch(prepareIntent)
                } else {
                    startSelectedMode(mode)
                }
            }

            RKhVpnApp(
                vm = vm,
                publicVm = publicVm,
                onCfConnectIp = { ip ->
                    if (publicVm.prepareCfConnectIp(ip)) requestVpn(PendingVpnMode.CF)
                },
                onSimpleConnect = {
                    val simple = publicVm.state.value
                    if (simple.simpleConnected || simple.simpleConnecting) {
                        publicVm.simpleDisconnect()
                    } else {
                        publicVm.prepareSimpleConnectUi()
                        requestVpn(PendingVpnMode.SIMPLE)
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
                onNipoConnect = {
                    val nipo = publicVm.state.value
                    if (nipo.nipoConnected || nipo.nipoConnecting) {
                        publicVm.nipoDisconnect()
                    } else {
                        publicVm.prepareNipoConnectUi()
                        requestVpn(PendingVpnMode.NIPO)
                    }
                },
                onPrivateConnect = { requestVpn(PendingVpnMode.PRIVATE) },
                onPrivateStop = { vm.disconnect() },
                onPublicConnect = {
                    stopService(Intent(this, RkhVpnService::class.java).setAction(RkhVpnService.ACTION_STOP))
                    vm.markDisconnected()
                    if (publicVm.state.value.selectedRunMode == "vpn") {
                        requestVpn(PendingVpnMode.PUBLIC)
                    } else {
                        publicVm.startProxyMode()
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