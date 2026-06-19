package com.rkh.vpn.ui

import android.content.Intent
import android.graphics.ImageDecoder
import android.os.Build
import android.widget.ImageView
import android.graphics.drawable.AnimatedImageDrawable
import android.net.Uri
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rkh.vpn.R
import com.rkh.vpn.data.FormatUtils
import com.rkh.vpn.data.RKhVpnLogStore
import com.rkh.vpn.data.SimorghPublicState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun SimorghPublicHome(
    state: SimorghPublicState,
    darkTheme: Boolean,
    onToggleTheme: () -> Unit,
    onConnect: () -> Unit,
    onStartProxy: () -> Unit,
    onStop: () -> Unit,
    onModeSelected: (String) -> Unit,
    onIspSelected: (String) -> Unit,
    onToggleSni: (String) -> Unit,
    onManualIpModeChanged: (Boolean) -> Unit,
    onManualIpsChanged: (String) -> Unit,
    onMaxScanIpsChanged: (Int) -> Unit,
    onScanSpeedChanged: (String) -> Unit,
    onProxyProtocolChanged: (String) -> Unit,
    onRouteStrategyChanged: (String) -> Unit,
    onClearSavedCleanIps: () -> Unit,
    onPingSavedCleanIps: () -> Unit,
    onNextRouteIp: () -> Unit,
    onCfEnabledChange: (Boolean) -> Unit,
    onCfVlessChanged: (String) -> Unit,
    onCfPingIp: (String) -> Unit,
    onCfPingAll: () -> Unit,
    onCfConnectIp: (String) -> Unit,
    onSimpleConnect: () -> Unit,
    onSimpleUpdate: () -> Unit,
    onSimpleNextHealthy: () -> Unit,
    onSimpleClearCache: () -> Unit,
    onSimplePingAll: () -> Unit,
    onSimpleConnectConfig: (Int) -> Unit,
    onSimpleServerlessChanged: (Boolean) -> Unit,
    onNipoConnect: () -> Unit,
    onNipoImportChanged: (String) -> Unit,
    onNipoAddProfile: () -> Unit,
    onNipoSelectProfile: (String) -> Unit,
    onNipoDeleteProfile: () -> Unit,
    onNipoSaveProfile: () -> Unit,
    onNipoFieldChanged: (String, String) -> Unit,
    onNipoBooleanChanged: (String, Boolean) -> Unit,
    onNipoTest: () -> Unit,
    onNipoReset: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showSettings by remember { mutableStateOf(false) }
    var mainPage by remember { mutableStateOf("simple") }
    Box(modifier = modifier.fillMaxSize()) {
        AnimatedSimorghBackground(state = state, modifier = Modifier.fillMaxSize())
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            // Fixed header: never scrolls with the content.
            PublicHeader(
                state = state,
                onOpenSettings = { showSettings = true },
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 8.dp)
            )
            HomeModeSwitch(
                selected = mainPage,
                onSelected = { mainPage = it },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 34.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (mainPage) {
                    "advance" -> {
                        item { PublicConnectCard(state = state, onConnect = onConnect, onStop = onStop, onModeSelected = onModeSelected) }
                        item { PublicStatusCard(state = state, onNextRouteIp = onNextRouteIp) }
                        item { PublicCleanIpsCard(state = state, onClearSavedCleanIps = onClearSavedCleanIps, onPingSavedCleanIps = onPingSavedCleanIps) }
                        item { CfConfigCard(state = state, onCfEnabledChange = onCfEnabledChange, onCfVlessChanged = onCfVlessChanged, onCfPingIp = onCfPingIp, onCfPingAll = onCfPingAll, onCfConnectIp = onCfConnectIp) }
                        item { TelegramFooter() }
                    }
                    "nipo" -> {
                        item { NipoVpnCard(
                            state = state,
                            onConnect = onNipoConnect,
                            onImportChanged = onNipoImportChanged,
                            onAddProfile = onNipoAddProfile,
                            onSelectProfile = onNipoSelectProfile,
                            onDeleteProfile = onNipoDeleteProfile,
                            onSaveProfile = onNipoSaveProfile,
                            onFieldChanged = onNipoFieldChanged,
                            onBooleanChanged = onNipoBooleanChanged,
                            onTest = onNipoTest,
                            onReset = onNipoReset
                        ) }
                        item { PublicLiveSpeedCard(state) }
                        item { TelegramFooter() }
                    }
                    else -> {
                        item { SimpleXrayCard(state = state, onConnect = onSimpleConnect, onUpdate = onSimpleUpdate, onNextHealthy = onSimpleNextHealthy, onClearCache = onSimpleClearCache, onPingAll = onSimplePingAll, onConnectConfig = onSimpleConnectConfig, onServerlessChanged = onSimpleServerlessChanged) }
                        item { PublicLiveSpeedCard(state) }
                        item { TelegramFooter() }
                    }
                }
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = "Made By RKh!",
                            color = Color.White.copy(alpha = 0.76f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "v1.1.23.46",
                            color = Color.White.copy(alpha = 0.64f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
    if (showSettings) {
        PublicSettingsDialog(
            state = state,
            onDismiss = { showSettings = false },
            onIspSelected = onIspSelected,
            onToggleSni = onToggleSni,
            onManualIpModeChanged = onManualIpModeChanged,
            onManualIpsChanged = onManualIpsChanged,
            onMaxScanIpsChanged = onMaxScanIpsChanged,
            onScanSpeedChanged = onScanSpeedChanged,
            onProxyProtocolChanged = onProxyProtocolChanged,
            onRouteStrategyChanged = onRouteStrategyChanged
        )
    }
}

@Composable
private fun HomeModeSwitch(selected: String, onSelected: (String) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(999.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.linearGradient(listOf(Color.White.copy(alpha = .12f), Color.White.copy(alpha = .045f), Color(0xFFFF1744).copy(alpha = .035f))))
            .border(1.dp, Color.White.copy(alpha = .16f), shape)
            .padding(5.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        ModePill("Advance", selected == "advance", Modifier.weight(1f)) { onSelected("advance") }
        ModePill("Simple", selected == "simple", Modifier.weight(1f)) { onSelected("simple") }
        ModePill("NipoVPN", selected == "nipo", Modifier.weight(1f)) { onSelected("nipo") }
    }
}

@Composable
private fun ModePill(text: String, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val scale by animateFloatAsState(if (active) 1f else .985f, animationSpec = tween(220), label = "modePillScale")
    val shape = RoundedCornerShape(999.dp)
    Box(
        modifier = modifier
            .graphicsLayer(scaleX = scale, scaleY = scale)
            .clip(shape)
            .background(
                Brush.linearGradient(
                    if (active) listOf(Color.White.copy(alpha = .22f), Color(0xFF10B981).copy(alpha = .08f), Color(0xFFFF1744).copy(alpha = .08f))
                    else listOf(Color.White.copy(alpha = .06f), Color.White.copy(alpha = .025f))
                )
            )
            .border(1.dp, if (active) Color.White.copy(alpha = .26f) else Color.White.copy(alpha = .08f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = Color.White.copy(alpha = if (active) .96f else .70f), fontSize = 12.sp, fontWeight = if (active) FontWeight.ExtraBold else FontWeight.SemiBold, textAlign = TextAlign.Center)
    }
}

@Composable
private fun SimpleXrayCard(
    state: SimorghPublicState,
    onConnect: () -> Unit,
    onUpdate: () -> Unit,
    onNextHealthy: () -> Unit,
    onClearCache: () -> Unit,
    onPingAll: () -> Unit,
    onConnectConfig: (Int) -> Unit,
    onServerlessChanged: (Boolean) -> Unit
) {
    val accent = when {
        state.simpleConnected -> Color(0xFF1ED760)
        state.simpleConnecting -> Color(0xFFFF1744)
        else -> Color(0xFFFF1744)
    }
    var showConfigs by remember { mutableStateOf(true) }
    GlassCard(accent = accent) {
        LiquidBubbleText("Simple", big = true)
        LiquidBubbleParagraph(
            text = if (state.simpleServerlessEnabled) "Connect to Xray Config with one click. • IRAN IPS" else "Connect to Xray Config with one click.",
            modifier = Modifier.fillMaxWidth(),
            centered = false,
            maxLines = 2
        )
        SimpleServerlessDropdown(
            enabled = state.simpleServerlessEnabled,
            locked = state.simpleConnecting,
            onChanged = onServerlessChanged
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            InfoText("Configs", state.simpleConfigCount.toString(), Modifier.weight(1f))
            InfoText(
                "Config",
                if (state.simpleBestName.isNotBlank()) state.simpleBestName else "Tap to list",
                Modifier.weight(1f).clickable { showConfigs = !showConfigs }
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            InfoText("Ping", if (state.simpleBestPingMs >= 0L) "${state.simpleBestPingMs}ms" else "—", Modifier.weight(1f))
            InfoText("Timer", formatSimpleElapsed(state.elapsedSeconds), Modifier.weight(1f))
        }
        if (showConfigs) {
            SimpleConfigListPanel(state = state, onPingAll = onPingAll, onConnectConfig = onConnectConfig)
        }
        SimpleXrayConnectOrb(state = state, color = accent, onConnect = onConnect)
        Button(
            onClick = onUpdate,
            enabled = !state.simpleConnecting,
            shape = RoundedCornerShape(999.dp),
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = .14f), disabledContainerColor = Color.White.copy(alpha = .07f)),
            border = BorderStroke(1.dp, Color.White.copy(alpha = .16f))
        ) {
            Text("Update", color = Color.White, fontWeight = FontWeight.Bold)
        }
        Button(
            onClick = onClearCache,
            enabled = !state.simpleConnecting,
            shape = RoundedCornerShape(999.dp),
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = .12f), disabledContainerColor = Color.White.copy(alpha = .06f)),
            border = BorderStroke(1.dp, Color.White.copy(alpha = .14f))
        ) {
            Text("Clear Cache", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
        val simpleVisibleStatus = if (state.simpleConnecting) state.simpleStatus.ifBlank { "Searching and Ping..." } else state.simpleStatus
        LiquidBubbleParagraph(simpleVisibleStatus, modifier = Modifier.fillMaxWidth(), centered = false, maxLines = 3)
    }
}

@Composable
private fun SimpleConfigListPanel(state: SimorghPublicState, onPingAll: () -> Unit, onConnectConfig: (Int) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        Color.White.copy(alpha = .105f),
                        Color(0xFFFF1744).copy(alpha = .035f),
                        Color.White.copy(alpha = .040f)
                    )
                )
            )
            .border(1.dp, Color.White.copy(alpha = .14f), RoundedCornerShape(22.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Simple Configs", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
            }
            Button(
                onClick = onPingAll,
                enabled = !state.simpleConnecting && state.simpleConfigCount > 0,
                shape = RoundedCornerShape(999.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = .14f), disabledContainerColor = Color.White.copy(alpha = .06f)),
                border = BorderStroke(1.dp, Color.White.copy(alpha = .16f))
            ) {
                Text("Ping All", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            }
        }
        if (state.simpleConfigItems.isEmpty()) {
            Text("No cached configs. Tap Update first.", color = Color.White.copy(alpha = .70f), fontSize = 12.sp)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().height(260.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(state.simpleConfigItems, key = { it.index }) { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (item.selected) Color.White.copy(alpha = .14f) else Color.White.copy(alpha = .060f))
                            .border(1.dp, Color.White.copy(alpha = if (item.selected) .18f else .08f), RoundedCornerShape(16.dp))
                            .padding(horizontal = 10.dp, vertical = 9.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(item.label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(item.pingLabel, color = Color.White.copy(alpha = .82f), fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
                            Button(
                                onClick = { onConnectConfig(item.index) },
                                enabled = !state.simpleConnecting && state.simpleConfigCount > 0,
                                shape = RoundedCornerShape(999.dp),
                                contentPadding = PaddingValues(horizontal = 9.dp, vertical = 4.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = if (item.hasPing) .18f else .11f), disabledContainerColor = Color.White.copy(alpha = .06f)),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = .16f))
                            ) {
                                Text("Connect", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}


@Composable
private fun SimpleDebugLogCard() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var logText by remember { mutableStateOf(simpleRelevantLogs(RKhVpnLogStore.readText(context))) }
    fun refreshLogs() { logText = simpleRelevantLogs(RKhVpnLogStore.readText(context)) }

    GlassCard(accent = Color(0xFF22C55E)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Simple Debug Log", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (logText.isBlank()) "No Simple/Xray logs yet. Try ServerLess Connect, then tap Refresh."
                    else "Copy this log after ServerLess fails.",
                    color = Color.White.copy(alpha = .66f),
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { refreshLogs() },
                    shape = RoundedCornerShape(999.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = .14f))
                ) { Text("Refresh", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                Button(
                    onClick = {
                        val fresh = simpleRelevantLogs(RKhVpnLogStore.readText(context))
                        logText = fresh
                        clipboard.setText(AnnotatedString(fresh.ifBlank { "No Simple/Xray logs yet." }))
                    },
                    shape = RoundedCornerShape(999.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22C55E).copy(alpha = .86f))
                ) { Text("Copy", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
            }
        }
        Spacer(Modifier.height(6.dp))
        SelectionContainer {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(170.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.White.copy(alpha = .095f))
                    .border(1.dp, Color.White.copy(alpha = .10f), RoundedCornerShape(18.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                item {
                    Text(
                        text = logText.ifBlank { "No Simple/Xray logs yet. Enable ServerLess, tap Connect, wait for the fail, then tap Refresh or Copy." },
                        color = Color.White.copy(alpha = .82f),
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

private fun simpleRelevantLogs(raw: String): String {
    if (raw.isBlank()) return ""
    val keywords = listOf(
        "Simple", "ServerLess", "XRAY", "Xray", "xray", "VPN", "CoreBin",
        "Tun2Socks", "tun2socks", "failed", "error", "Exception", "exited"
    )
    return raw.lineSequence()
        .filter { line -> keywords.any { key -> line.contains(key, ignoreCase = true) } }
        .toList()
        .takeLast(160)
        .joinToString("\n")
}

@Composable
private fun SimpleServerlessDropdown(enabled: Boolean, locked: Boolean, onChanged: (Boolean) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(22.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.linearGradient(listOf(Color.White.copy(alpha = .12f), Color(0xFFFF1744).copy(alpha = .055f), Color.Transparent)))
            .border(1.dp, Color.White.copy(alpha = .14f), shape)
            .clickable(enabled = !locked) { expanded = true }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("ServerLess", color = Color.White.copy(alpha = .94f), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
            Text("IRAN IPS", color = Color.White.copy(alpha = .62f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
        Box {
            Text(
                text = if (enabled) "ON ▾" else "OFF ▾",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (enabled) Color(0xFF10B981).copy(alpha = .30f) else Color.White.copy(alpha = .10f))
                    .border(1.dp, Color.White.copy(alpha = .16f), RoundedCornerShape(999.dp))
                    .padding(horizontal = 12.dp, vertical = 7.dp)
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(text = { Text("OFF") }, onClick = { expanded = false; onChanged(false) })
                DropdownMenuItem(text = { Text("ON") }, onClick = { expanded = false; onChanged(true) })
            }
        }
    }
}


@Composable
private fun NipoVpnCard(
    state: SimorghPublicState,
    onConnect: () -> Unit,
    onImportChanged: (String) -> Unit,
    onAddProfile: () -> Unit,
    onSelectProfile: (String) -> Unit,
    onDeleteProfile: () -> Unit,
    onSaveProfile: () -> Unit,
    onFieldChanged: (String, String) -> Unit,
    onBooleanChanged: (String, Boolean) -> Unit,
    onTest: () -> Unit,
    onReset: () -> Unit
) {
    val simpleRed = Color(0xFFFF1744)
    val accent = when {
        state.nipoConnected -> Color(0xFF1ED760)
        state.nipoConnecting -> simpleRed
        else -> simpleRed
    }
    var showProfileOptions by remember { mutableStateOf(false) }
    var showProfileEditor by remember { mutableStateOf(false) }
    val nipoStatusText = if (state.nipoConnected) "NipoVPN connected" else state.nipoStatus
    GlassCard(accent = accent) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                LiquidBubbleText("NipoVPN", big = true)
                Text(
                    "SOCKS5 9992 → XRAY",
                    color = Color.White.copy(alpha = .72f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.White.copy(alpha = .10f))
                        .border(1.dp, Color.White.copy(alpha = .14f), RoundedCornerShape(999.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
            LiquidBubbleParagraph(
                text = "NipoVPN Agent",
                modifier = Modifier.fillMaxWidth(),
                centered = false,
                maxLines = 1
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                InfoText("Profile", state.nipoSelectedProfile.ifBlank { state.nipoName.ifBlank { "—" } }, Modifier.weight(1f))
                InfoText("Nipo Server", if (state.nipoServerAddress.isNotBlank()) "${state.nipoServerAddress}:${state.nipoServerPort}" else "—", Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                InfoText("Ping", if (state.nipoPingMs >= 0L) "${state.nipoPingMs}ms" else "—", Modifier.weight(1f))
                InfoText("Local SOCKS5", "127.0.0.1:${state.nipoSocksPort}", Modifier.weight(1f))
            }
            NipoConnectOrb(state = state, color = accent, onConnect = onConnect)

            OutlinedTextField(
                value = state.nipoImportText,
                onValueChange = onImportChanged,
                enabled = !state.nipoConnecting && !state.nipoConnected,
                modifier = Modifier.fillMaxWidth().height(92.dp),
                label = { Text("nipovpn://") },
                placeholder = { Text("Paste nipovpn:// profile link") },
                textStyle = TextStyle(color = Color.White.copy(alpha = .94f), fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                maxLines = 3
            )
            Button(
                onClick = onAddProfile,
                enabled = !state.nipoConnecting && !state.nipoConnected && state.nipoImportText.isNotBlank(),
                shape = RoundedCornerShape(999.dp),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = .14f), disabledContainerColor = Color.White.copy(alpha = .07f)),
                border = BorderStroke(1.dp, Color.White.copy(alpha = .16f))
            ) { Text("Add Profile", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp) }

            NipoSectionToggle(
                label = if (showProfileOptions) "Hide Profiles" else "Show Profiles",
                selected = showProfileOptions,
                modifier = Modifier.fillMaxWidth()
            ) {
                showProfileOptions = !showProfileOptions
                if (!showProfileOptions) showProfileEditor = false
            }

            if (showProfileOptions) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    Color.White.copy(alpha = .045f),
                                    Color(0xFFFF1744).copy(alpha = .018f),
                                    Color.Transparent
                                )
                            )
                        )
                        .border(1.dp, Color.White.copy(alpha = .085f), RoundedCornerShape(24.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    NipoProfilesList(
                        profiles = state.nipoProfiles,
                        selected = state.nipoSelectedProfile,
                        profileExports = state.nipoProfileExports,
                        locked = state.nipoConnecting || state.nipoConnected,
                        onSelect = { profile ->
                            showProfileEditor = false
                            onSelectProfile(profile)
                        },
                        onEdit = { profile ->
                            if (showProfileEditor && state.nipoSelectedProfile == profile) {
                                showProfileEditor = false
                            } else {
                                showProfileEditor = true
                                onSelectProfile(profile)
                            }
                        },
                        onDelete = onDeleteProfile
                    )

                    if (showProfileEditor && state.nipoSelectedProfile.isNotBlank()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(24.dp))
                                .background(
                                    Brush.linearGradient(
                                        listOf(
                                            Color.White.copy(alpha = .035f),
                                            Color(0xFFFF1744).copy(alpha = .014f),
                                            Color.Transparent
                                        )
                                    )
                                )
                                .border(1.dp, Color.White.copy(alpha = .075f), RoundedCornerShape(24.dp))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                        LiquidBubbleParagraph("Editable profile config", modifier = Modifier.fillMaxWidth(), centered = false, maxLines = 1)
                        NipoTextField("Name", state.nipoName, "name", onFieldChanged, locked = state.nipoConnecting || state.nipoConnected)
                        NipoTextField("Token", state.nipoToken, "token", onFieldChanged, locked = state.nipoConnecting || state.nipoConnected)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NipoTextField("Protocol", state.nipoProtocol, "protocol", onFieldChanged, Modifier.weight(1f), state.nipoConnecting || state.nipoConnected)
                            NipoTextField("Log", state.nipoLogLevel, "logLevel", onFieldChanged, Modifier.weight(1f), state.nipoConnecting || state.nipoConnected)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NipoTextField("Server IP", state.nipoServerAddress, "serverIp", onFieldChanged, Modifier.weight(1f), state.nipoConnecting || state.nipoConnected)
                            NipoTextField("Server Port", state.nipoServerPort.toString(), "serverPort", onFieldChanged, Modifier.weight(1f), state.nipoConnecting || state.nipoConnected)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NipoTextField("Timeout", state.nipoTimeout, "timeout", onFieldChanged, Modifier.weight(1f), state.nipoConnecting || state.nipoConnected)
                            NipoTextField("Pull Timeout", state.nipoPullTimeout, "pullTimeout", onFieldChanged, Modifier.weight(1f), state.nipoConnecting || state.nipoConnected)
                        }
                        NipoTextField("Fake URLs", state.nipoFakeUrls, "fakeUrls", onFieldChanged, locked = state.nipoConnecting || state.nipoConnected, multiLine = true)
                        NipoTextField("Methods", state.nipoMethods, "methods", onFieldChanged, locked = state.nipoConnecting || state.nipoConnected, multiLine = true)
                        NipoTextField("End Points", state.nipoEndPoints, "endPoints", onFieldChanged, locked = state.nipoConnecting || state.nipoConnected, multiLine = true)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NipoToggle("Tunnel", state.nipoTunnelEnable, "tunnelEnable", onBooleanChanged, Modifier.weight(1f), state.nipoConnecting || state.nipoConnected)
                            NipoToggle("Reuse", state.nipoConnectionReuse, "connectionReuse", onBooleanChanged, Modifier.weight(1f), state.nipoConnecting || state.nipoConnected)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NipoToggle("TLS", state.nipoTlsEnable, "tlsEnable", onBooleanChanged, Modifier.weight(1f), state.nipoConnecting || state.nipoConnected)
                            NipoToggle("Verify", state.nipoTlsVerifyPeer, "tlsVerifyPeer", onBooleanChanged, Modifier.weight(1f), state.nipoConnecting || state.nipoConnected)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NipoTextField("HTTP", state.nipoHttpVersion, "httpVersion", onFieldChanged, Modifier.weight(1f), state.nipoConnecting || state.nipoConnected)
                            InfoText("Listen", "127.0.0.1:9992", Modifier.weight(1f))
                        }
                        NipoTextField("User Agent", state.nipoUserAgent, "userAgent", onFieldChanged, locked = state.nipoConnecting || state.nipoConnected, multiLine = true)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = onSaveProfile,
                                enabled = !state.nipoConnecting && !state.nipoConnected,
                                shape = RoundedCornerShape(999.dp),
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = .14f), disabledContainerColor = Color.White.copy(alpha = .07f)),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = .16f))
                            ) { Text("Save Profile", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
                            Button(
                                onClick = {
                                    showProfileEditor = false
                                    onDeleteProfile()
                                },
                                enabled = !state.nipoConnecting && !state.nipoConnected && state.nipoSelectedProfile.isNotBlank(),
                                shape = RoundedCornerShape(999.dp),
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = .08f), disabledContainerColor = Color.White.copy(alpha = .04f)),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = .12f))
                            ) { Text("Delete", color = Color.White.copy(alpha = .88f), fontWeight = FontWeight.Bold, fontSize = 12.sp) }
                        }
                        }
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onTest,
                    enabled = !state.nipoConnecting,
                    shape = RoundedCornerShape(999.dp),
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = .14f), disabledContainerColor = Color.White.copy(alpha = .07f)),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = .16f))
                ) { Text("Test", color = Color.White, fontWeight = FontWeight.Bold) }
                Button(
                    onClick = onReset,
                    enabled = !state.nipoConnecting && !state.nipoConnected,
                    shape = RoundedCornerShape(999.dp),
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = .10f), disabledContainerColor = Color.White.copy(alpha = .05f)),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = .16f))
                ) { Text("Reset", color = Color.White, fontWeight = FontWeight.Bold) }
            }
            LiquidBubbleParagraph(nipoStatusText, modifier = Modifier.fillMaxWidth(), centered = false, maxLines = 3)
        }
    }
}

@Composable
private fun NipoSectionToggle(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(999.dp),
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) Color(0xFFFF1744).copy(alpha = .14f) else Color.White.copy(alpha = .10f)
        ),
        border = BorderStroke(1.dp, Color.White.copy(alpha = if (selected) .16f else .14f))
    ) {
        Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
    }
}

@Composable
private fun NipoProfilesList(
    profiles: List<String>,
    selected: String,
    profileExports: Map<String, String>,
    locked: Boolean,
    onSelect: (String) -> Unit,
    onEdit: (String) -> Unit,
    onDelete: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text("Profiles", color = Color.White.copy(alpha = .76f), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
        if (profiles.isEmpty()) {
            LiquidBubbleParagraph("No NipoVPN profiles yet. Paste a nipovpn:// link and tap Add Profile.", modifier = Modifier.fillMaxWidth(), centered = false, maxLines = 2)
        } else {
            profiles.take(12).forEach { profile ->
                val isSelected = profile == selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(if (isSelected) Color(0xFFFF1744).copy(alpha = .14f) else Color.White.copy(alpha = .10f))
                        .border(1.dp, if (isSelected) Color.White.copy(alpha = .18f) else Color.White.copy(alpha = .14f), RoundedCornerShape(18.dp))
                        .clickable(enabled = !locked) { onSelect(profile) }
                        .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(profile, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                        if (isSelected) Text("SELECTED", color = Color.White.copy(alpha = .64f), fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(0.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .clickable(enabled = !locked) { onEdit(profile) },
                            contentAlignment = Alignment.Center
                        ) { Text("✎", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold) }
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .clickable {
                                    val link = profileExports[profile].orEmpty()
                                    clipboard.setText(AnnotatedString(link.ifBlank { "nipovpn://" }))
                                },
                            contentAlignment = Alignment.Center
                        ) { Text("⧉", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold) }
                    }
                }
            }
        }
    }
}

@Composable
private fun NipoTextField(
    label: String,
    value: String,
    field: String,
    onFieldChanged: (String, String) -> Unit,
    modifier: Modifier = Modifier,
    locked: Boolean = false,
    multiLine: Boolean = false
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onFieldChanged(field, it) },
        enabled = !locked,
        modifier = modifier.fillMaxWidth().height(if (multiLine) 96.dp else 58.dp),
        label = { Text(label) },
        textStyle = TextStyle(color = Color.White.copy(alpha = .94f), fontSize = 12.sp, fontFamily = if (multiLine) FontFamily.Monospace else FontFamily.SansSerif),
        maxLines = if (multiLine) 4 else 1
    )
}

@Composable
private fun NipoToggle(
    label: String,
    value: Boolean,
    field: String,
    onBooleanChanged: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    locked: Boolean = false
) {
    Button(
        onClick = { onBooleanChanged(field, !value) },
        enabled = !locked,
        shape = RoundedCornerShape(18.dp),
        modifier = modifier.height(50.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (value) Color(0xFFFF1744).copy(alpha = .14f) else Color.White.copy(alpha = .10f),
            disabledContainerColor = Color.White.copy(alpha = .05f)
        ),
        border = BorderStroke(1.dp, Color.White.copy(alpha = if (value) .16f else .14f))
    ) {
        val toggleText = if (value) "$label: ON" else "$label: OFF"
        Text(toggleText, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun NipoConnectOrb(state: SimorghPublicState, color: Color, onConnect: () -> Unit) {
    val active = state.nipoConnected || state.nipoConnecting
    val transition = rememberInfiniteTransition(label = "nipoConnectAnimation")
    val pulse by transition.animateFloat(
        initialValue = 0.82f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse),
        label = "nipoConnectPulseValue"
    )
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1350), RepeatMode.Restart),
        label = "nipoConnectSpinRing"
    )
    Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(132.dp)) {
            drawCircle(
                color = Color.White.copy(alpha = 0.08f),
                radius = size.minDimension / 2.18f,
                style = Stroke(width = 2.dp.toPx())
            )
            rotate(spin) {
                drawArc(
                    color = color.copy(alpha = 0.92f),
                    startAngle = -90f,
                    sweepAngle = 108f,
                    useCenter = false,
                    topLeft = Offset(7.dp.toPx(), 7.dp.toPx()),
                    size = Size(size.width - 14.dp.toPx(), size.height - 14.dp.toPx()),
                    style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round)
                )
                drawArc(
                    color = Color.White.copy(alpha = 0.46f * pulse),
                    startAngle = 45f,
                    sweepAngle = 42f,
                    useCenter = false,
                    topLeft = Offset(13.dp.toPx(), 13.dp.toPx()),
                    size = Size(size.width - 26.dp.toPx(), size.height - 26.dp.toPx()),
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }
        Box(
            modifier = Modifier
                .size(if (active) 112.dp else 122.dp)
                .shadow(12.dp, CircleShape, ambientColor = color.copy(alpha = .28f), spotColor = color.copy(alpha = .42f))
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(
                            Color.White.copy(alpha = .30f),
                            color.copy(alpha = if (active) .64f else .58f),
                            Color.White.copy(alpha = .10f),
                            color.copy(alpha = .36f)
                        )
                    )
                )
                .border(
                    1.4.dp,
                    Brush.linearGradient(
                        listOf(
                            Color.White.copy(alpha = .62f),
                            color.copy(alpha = .70f),
                            Color.White.copy(alpha = .22f)
                        )
                    ),
                    CircleShape
                )
                .clickable { onConnect() },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(if (active) 88.dp else 96.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = .12f))
                    .border(1.dp, Color.White.copy(alpha = .20f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text(if (active) "■" else "▶", color = Color.White, fontSize = if (active) 34.sp else 40.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.height(5.dp))
                    Text(
                        when {
                            state.nipoConnected -> "DISCONNECT"
                            state.nipoConnecting -> "STARTING"
                            else -> "CONNECT"
                        },
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 9.sp,
                        letterSpacing = .30.sp,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun SimpleXrayConnectOrb(state: SimorghPublicState, color: Color, onConnect: () -> Unit) {
    val active = state.simpleConnected || state.simpleConnecting
    val transition = rememberInfiniteTransition(label = "simpleConnectAnimation")
    val pulse by transition.animateFloat(
        initialValue = 0.82f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse),
        label = "simpleConnectPulseValue"
    )
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1350), RepeatMode.Restart),
        label = "simpleConnectSpinRing"
    )
    Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(132.dp)) {
            drawCircle(
                color = Color.White.copy(alpha = 0.08f),
                radius = size.minDimension / 2.18f,
                style = Stroke(width = 2.dp.toPx())
            )
            rotate(spin) {
                drawArc(
                    color = color.copy(alpha = 0.92f),
                    startAngle = -90f,
                    sweepAngle = 108f,
                    useCenter = false,
                    topLeft = Offset(7.dp.toPx(), 7.dp.toPx()),
                    size = Size(size.width - 14.dp.toPx(), size.height - 14.dp.toPx()),
                    style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round)
                )
                drawArc(
                    color = Color.White.copy(alpha = 0.46f * pulse),
                    startAngle = 45f,
                    sweepAngle = 42f,
                    useCenter = false,
                    topLeft = Offset(13.dp.toPx(), 13.dp.toPx()),
                    size = Size(size.width - 26.dp.toPx(), size.height - 26.dp.toPx()),
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }
        Box(
            modifier = Modifier
                .size(if (active) 112.dp else 122.dp)
                .shadow(12.dp, CircleShape, ambientColor = color.copy(alpha = .28f), spotColor = color.copy(alpha = .42f))
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(
                            Color.White.copy(alpha = .30f),
                            color.copy(alpha = if (active) .64f else .58f),
                            Color.White.copy(alpha = .10f),
                            color.copy(alpha = .36f)
                        )
                    )
                )
                .border(
                    1.4.dp,
                    Brush.linearGradient(
                        listOf(
                            Color.White.copy(alpha = .62f),
                            color.copy(alpha = .70f),
                            Color.White.copy(alpha = .22f)
                        )
                    ),
                    CircleShape
                )
                .clickable(enabled = !state.simpleConnecting) { onConnect() },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(if (active) 88.dp else 96.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = .12f))
                    .border(1.dp, Color.White.copy(alpha = .20f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text(if (active) "■" else "▶", color = Color.White, fontSize = if (active) 34.sp else 40.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.height(5.dp))
                    Text(
                        when {
                            state.simpleConnected -> "DISCONNECT"
                            state.simpleConnecting -> "TESTING"
                            else -> "CONNECT"
                        },
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 9.sp,
                        letterSpacing = .30.sp,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

private fun formatSimpleElapsed(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0L)
    val h = safe / 3600L
    val m = (safe % 3600L) / 60L
    val s = safe % 60L
    return if (h > 0L) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

@Composable
private fun PublicHeader(
    state: SimorghPublicState,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "simorghTitleGlow")
    val glow by transition.animateFloat(
        initialValue = .46f,
        targetValue = .58f,
        animationSpec = infiniteRepeatable(tween(5000), RepeatMode.Reverse),
        label = "simorghTitleGlowValue"
    )
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(5200), RepeatMode.Restart),
        label = "simorghTitleSweep"
    )
    val shape = RoundedCornerShape(30.dp)
    Card(
        modifier = modifier.fillMaxWidth().shadow(10.dp, shape, ambientColor = Color.White.copy(alpha = .05f), spotColor = Color(0xFFFF6B2E).copy(alpha = .08f)),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = .045f)),
        border = BorderStroke(1.dp, Brush.horizontalGradient(listOf(Color.White.copy(alpha = .16f), Color(0xFFFF7A2F).copy(alpha = .10f), Color.White.copy(alpha = .08f))))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(
                            Color.White.copy(alpha = .035f),
                            Color(0xFFFF1744).copy(alpha = .018f),
                            Color.White.copy(alpha = .018f)
                        )
                    )
                )
                .padding(horizontal = 18.dp, vertical = 15.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Box(Modifier.fillMaxWidth().height(52.dp), contentAlignment = Alignment.CenterStart) {
                    Canvas(Modifier.fillMaxSize()) {
                        repeat(13) { index ->
                            val phase = ((sweep + index * 0.071f) % 1f)
                            val x = 16f + size.width * (0.06f + index * 0.055f)
                            val y = size.height * (0.88f - 0.54f * phase)
                            val radius = 3.0f + (index % 4) * 1.25f + 4.0f * phase
                            val alpha = (0.03f + 0.10f * (1f - phase) * glow).coerceIn(0f, .16f)
                            drawCircle(
                                color = Color(0xFFFF7A2F).copy(alpha = alpha),
                                radius = radius,
                                center = Offset(x, y)
                            )
                            drawCircle(
                                color = Color(0xFFFF1744).copy(alpha = alpha * .42f),
                                radius = radius * 1.55f,
                                center = Offset(x + 3f, y + 4f)
                            )
                        }
                    }
                    Text(
                        "SIMORGH",
                        fontSize = 35.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = FontFamily.Serif,
                        letterSpacing = 4.2.sp,
                        color = Color.White.copy(alpha = .12f + .09f * glow),
                        modifier = Modifier.padding(start = 2.dp, top = 5.dp).graphicsLayer(scaleX = 1f + .0012f * glow, scaleY = 1f + .0012f * glow)
                    )
                    Text(
                        "SIMORGH",
                        fontSize = 34.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = FontFamily.Serif,
                        letterSpacing = 4.2.sp,
                        modifier = Modifier.padding(top = 4.dp),
                        style = TextStyle(
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    Color(0xFF10B981),
                                    Color.White.copy(alpha = .96f),
                                    Color(0xFFFF1744),
                                    Color.White.copy(alpha = .88f),
                                    Color(0xFF10B981),
                                    Color(0xFFFF1744)
                                ),
                                start = Offset(-520f + 1040f * sweep, 0f),
                                end = Offset(120f + 1040f * sweep, 0f)
                            )
                        )
                    )
                }
                Text(
                    text = buildString {
                        val simpleActive = state.simpleConnected || state.simpleConnecting || state.activeMode == "simple_xray"
                        val nipoActive = state.nipoConnected || state.nipoConnecting || state.activeMode == "nipo"
                        val ip = state.activeRouteIp.ifBlank { state.route?.ip.orEmpty() }
                        if (nipoActive) {
                            append(
                                when {
                                    state.nipoConnected -> "NipoVPN Connected"
                                    state.nipoConnecting -> "NipoVPN Starting..."
                                    else -> state.nipoStatus.ifBlank { "NipoVPN" }
                                }
                            )
                            if (state.nipoServerAddress.isNotBlank()) append(" • ").append(state.nipoServerAddress).append(":").append(state.nipoServerPort)
                            if (state.nipoPingMs >= 0L) append(" • ").append(state.nipoPingMs).append("ms")
                        } else if (simpleActive) {
                            append(
                                when {
                                    state.simpleConnected -> "Connected"
                                    state.simpleConnecting -> "Searching and Ping..."
                                    else -> state.status.ifBlank { "Simple" }
                                }
                            )
                            if (state.simpleBestName.isNotBlank()) append(" • ").append(state.simpleBestName)
                            if (state.simpleBestPingMs >= 0L) append(" • ").append(state.simpleBestPingMs).append("ms")
                        } else if (ip.isNotBlank()) {
                            append("Active Clean IP → ").append(ip)
                            if (state.activeRoutePingMs >= 0L) append(" • ").append(state.activeRoutePingMs).append("ms")
                        } else {
                            append(state.status)
                        }
                    },
                    color = Color.White.copy(alpha = .78f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            HeaderRoundButton(text = "⚙", onClick = onOpenSettings)
        }
    }
}

@Composable
private fun HeaderRoundButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = CircleShape,
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier.size(48.dp).shadow(8.dp, CircleShape, ambientColor = Color.White.copy(alpha = .06f), spotColor = Color.White.copy(alpha = .05f)),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.White.copy(alpha = .105f),
            contentColor = Color.White.copy(alpha = .86f)
        ),
        border = BorderStroke(1.2.dp, Brush.horizontalGradient(listOf(Color.White.copy(alpha=.34f), Color(0xFFFF7A2F).copy(alpha=.16f), Color.White.copy(alpha=.16f))))
    ) { Text(text, fontSize = 21.sp, color = Color.White.copy(alpha = .82f), fontWeight = FontWeight.Black) }
}

@Composable
private fun PublicConnectCard(state: SimorghPublicState, onConnect: () -> Unit, onStop: () -> Unit, onModeSelected: (String) -> Unit) {
    val active = state.connected || state.connecting
    val transition = rememberInfiniteTransition(label = "publicConnectAnimation")
    val pulse by transition.animateFloat(
        initialValue = 0.82f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse),
        label = "publicConnectPulseValue"
    )
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1350), RepeatMode.Restart),
        label = "publicConnectSpinRing"
    )
    val color = when {
        state.connected && state.activeMode == "proxy" -> Color(0xFF22C55E)
        state.connected -> Color(0xFF1ED760)
        state.connecting -> Color(0xFFFF1744)
        else -> Color(0xFFFF1744)
    }
    GlassCard(accent = color) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                LiquidBubbleText("Advance", big = true)
                LiquidBubbleText(if (state.selectedRunMode == "vpn") "VPN Mode" else "Proxy Mode", big = false)
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeButton("Proxy", state.selectedRunMode == "proxy", Modifier.weight(1f)) { onModeSelected("proxy") }
                ModeButton("VPN", state.selectedRunMode == "vpn", Modifier.weight(1f)) { onModeSelected("vpn") }
            }
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(132.dp)) {
                    drawCircle(
                        color = Color.White.copy(alpha = 0.08f),
                        radius = size.minDimension / 2.18f,
                        style = Stroke(width = 2.dp.toPx())
                    )
                    rotate(spin) {
                        drawArc(
                            color = color.copy(alpha = 0.92f),
                            startAngle = -90f,
                            sweepAngle = 108f,
                            useCenter = false,
                            topLeft = Offset(7.dp.toPx(), 7.dp.toPx()),
                            size = Size(size.width - 14.dp.toPx(), size.height - 14.dp.toPx()),
                            style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round)
                        )
                        drawArc(
                            color = Color.White.copy(alpha = 0.46f * pulse),
                            startAngle = 45f,
                            sweepAngle = 42f,
                            useCenter = false,
                            topLeft = Offset(13.dp.toPx(), 13.dp.toPx()),
                            size = Size(size.width - 26.dp.toPx(), size.height - 26.dp.toPx()),
                            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .size(if (active) 112.dp else 122.dp)
                        .shadow(12.dp, CircleShape, ambientColor = color.copy(alpha = .28f), spotColor = color.copy(alpha = .42f))
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                listOf(
                                    Color.White.copy(alpha = .30f),
                                    color.copy(alpha = if (active) .64f else .58f),
                                    Color.White.copy(alpha = .10f),
                                    color.copy(alpha = .36f)
                                )
                            )
                        )
                        .border(
                            1.4.dp,
                            Brush.linearGradient(
                                listOf(
                                    Color.White.copy(alpha = .62f),
                                    color.copy(alpha = .70f),
                                    Color.White.copy(alpha = .22f)
                                )
                            ),
                            CircleShape
                        )
                        .clickable { if (active) onStop() else onConnect() },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(if (active) 88.dp else 96.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = .12f))
                            .border(1.dp, Color.White.copy(alpha = .20f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Text(if (active) "■" else "▶", color = Color.White, fontSize = if (active) 34.sp else 40.sp, fontWeight = FontWeight.ExtraBold)
                            Spacer(Modifier.height(5.dp))
                            Text(
                                when {
                                    state.connected -> "DISCONNECT"
                                    state.connecting -> "STARTING"
                                    state.selectedRunMode == "vpn" -> "CONNECT VPN"
                                    else -> "CONNECT PROXY"
                                },
                                color = Color.White,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 9.sp,
                                letterSpacing = .30.sp,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                InfoText("Mode", if (state.selectedRunMode == "vpn") "VPN" else "Proxy")
                InfoText("Active", when (state.activeMode) { "proxy" -> "Proxy"; "vpn" -> "VPN"; else -> "Idle" })
                InfoText(state.selectedProxyProtocol.uppercase(), "127.0.0.1:${state.proxyPort}")
            }
            Spacer(Modifier.height(8.dp))
            LiquidBubbleParagraph(
                text = if (state.selectedRunMode == "proxy")
                    "Proxy selection only sets the mode. Tap the big Connect button to start ${state.selectedProxyProtocol.uppercase()} on 127.0.0.1:${state.proxyPort}."
                else
                    "VPN selection only sets the mode. Tap the big Connect button to start SIMORGH VPN/TUN.",
                modifier = Modifier.fillMaxWidth(),
                centered = true,
                maxLines = 3
            )
            if (state.lastError.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(state.lastError, color = Color(0xFFFF1744), fontSize = 11.sp, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ModeButton(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.shadow(if (selected) 10.dp else 2.dp, RoundedCornerShape(18.dp)),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) Color(0xFFFF1744).copy(alpha = .92f) else Color.White.copy(alpha = .12f),
            contentColor = Color.White
        ),
        border = BorderStroke(1.dp, if (selected) Color.White.copy(alpha = .36f) else Color.White.copy(alpha = .14f)),
        contentPadding = PaddingValues(vertical = 11.dp)
    ) { Text(if (selected) "✓ $text" else text, color = Color.White, fontWeight = FontWeight.ExtraBold, letterSpacing = .2.sp) }
}

@Composable
private fun PublicStatusCard(state: SimorghPublicState, onNextRouteIp: () -> Unit) {
    val customIsp = state.selectedIsp != "AbrArvan CDN and IaaS"
    val customSni = state.selectedSnis != setOf("chatgpt.com")
    val hasCustomInfo = customIsp || customSni || state.manualIpMode
    GlassCard(accent = Color.White.copy(alpha = .90f)) {
        LiquidBubbleText("MSP Route", big = true)
        if (hasCustomInfo) {
            if (customIsp) Text("ISP: ${state.selectedIsp}", color = Color.White.copy(alpha = .76f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (customSni) Text("SNI: ${state.selectedSnis.joinToString(", ")}", color = Color.White.copy(alpha = .76f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (state.manualIpMode) Text("Manual IPs: ON • ${state.manualCandidateCount} candidates", color = Color.White.copy(alpha = .76f), fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
        } else {
            LiquidBubbleText("Default SIMORGH route profile is active", big = false)
            Spacer(Modifier.height(8.dp))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            InfoText("Scanned", "${state.scannedCount}/${state.totalCandidates.takeIf { it > 0 } ?: state.maxScanIps}")
            InfoText("Clean", state.cleanIpCount.toString())
            InfoText("Saved", state.savedCleanIps.size.toString())
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            InfoText("Active IP", state.activeRouteIp.ifBlank { "—" } + if (state.activeRoutePingMs >= 0L) " • ${state.activeRoutePingMs}ms" else "")
            Button(
                onClick = onNextRouteIp,
                enabled = state.savedCleanIps.isNotEmpty() || state.cleanIpCount > 1,
                shape = RoundedCornerShape(999.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 9.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF1744).copy(alpha = .90f), disabledContainerColor = Color.White.copy(alpha = .10f))
            ) { Text("Next IP", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
        }
        Spacer(Modifier.height(8.dp))
        LiquidBubbleParagraph(state.status, modifier = Modifier.fillMaxWidth(), centered = false, maxLines = 2)
    }
}

@Composable
private fun PublicLiveSpeedCard(state: SimorghPublicState) {
    val pulse by rememberInfiniteTransition(label = "speedPulse").animateFloat(
        initialValue = .35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
        label = "speedPulseValue"
    )
    GlassCard(accent = Color(0xFF6BB6FF)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Live Speed", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    when {
                        state.simpleConnected || state.activeMode == "simple_xray" -> "Simple XRAY VPN traffic through selected config"
                        state.activeMode == "proxy" -> "SOCKS5 proxy: 127.0.0.1:${state.proxyPort}"
                        else -> "Android traffic through Xray + MSP SOCKS5"
                    },
                    color = Color.White.copy(alpha = .66f),
                    fontSize = 11.sp
                )
            }
            Text("↓ ${FormatUtils.kbps(state.downloadKbps)}  ↑ ${FormatUtils.kbps(state.uploadKbps)}", color = Color(0xFFFF1744), fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, maxLines = 1)
        }
        Spacer(Modifier.height(12.dp))
        Canvas(Modifier.fillMaxWidth().height(108.dp)) {
            drawRoundRect(brush = Brush.verticalGradient(listOf(Color.White.copy(.12f), Color.Transparent)), cornerRadius = androidx.compose.ui.geometry.CornerRadius(34f, 34f))
            repeat(6) { i ->
                val yy = size.height * (i + 1) / 7f
                drawLine(Color.White.copy(alpha = 0.10f), Offset(0f, yy), Offset(size.width, yy), 1.2f)
            }
            val baseY = size.height * .76f
            val peak = maxOf(64f, state.downloadKbps.toFloat(), state.uploadKbps.toFloat())
            val maxHeight = size.height * .58f
            val downHeight = ((state.downloadKbps.toFloat() / peak) * maxHeight).coerceIn(0f, maxHeight)
            val upHeight = ((state.uploadKbps.toFloat() / peak) * maxHeight).coerceIn(0f, maxHeight)
            val downY = baseY - downHeight
            val upY = baseY + 18f - upHeight
            drawLine(Color(0xFFFF1744), Offset(0f, baseY), Offset(size.width, downY), 6f, cap = StrokeCap.Round)
            drawLine(Color(0xFF6BB6FF), Offset(0f, baseY + 18f), Offset(size.width, upY), 4f, cap = StrokeCap.Round)
            drawCircle(Color(0xFFFF1744).copy(alpha = .6f * pulse), 14f, Offset(size.width, downY))
        }
    }
}

@Composable
private fun PublicCleanIpsCard(state: SimorghPublicState, onClearSavedCleanIps: () -> Unit, onPingSavedCleanIps: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val savedText = state.savedCleanIps.joinToString("\n")
    var expanded by remember { mutableStateOf(false) }
    GlassCard(accent = Color(0xFFFF1744)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                LiquidBubbleText("IP Memory", big = true)
                Spacer(Modifier.height(7.dp))
                LiquidBubbleText("${state.savedCleanIps.size} clean IPs • ping shown when available", big = false)
            }
            Button(onClick = { expanded = !expanded }, shape = RoundedCornerShape(999.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = .16f))) { Text(if (expanded) "Hide" else "Show", color = Color.White, fontSize = 12.sp) }
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPingSavedCleanIps, enabled = state.savedCleanIps.isNotEmpty(), shape = RoundedCornerShape(999.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22C55E).copy(alpha = .90f))) { Text("Ping All", color = Color.White, fontSize = 12.sp) }
                Button(onClick = { clipboard.setText(AnnotatedString(savedText)) }, enabled = savedText.isNotBlank(), shape = RoundedCornerShape(999.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = .16f))) { Text("Copy", color = Color.White, fontSize = 12.sp) }
                Button(onClick = onClearSavedCleanIps, enabled = state.savedCleanIps.isNotEmpty(), shape = RoundedCornerShape(999.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935).copy(alpha = .82f))) { Text("Clear", color = Color.White, fontSize = 12.sp) }
            }
            Spacer(Modifier.height(8.dp))
            SelectionContainer {
                val sortedIps = state.savedCleanIps.sortedWith(
                    compareBy<String> { ip -> state.savedCleanIpPings[ip]?.takeIf { it >= 0L } ?: Long.MAX_VALUE }
                        .thenBy { it }
                )
                Column(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().background(Color.White.copy(alpha = .10f), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 7.dp)) {
                        Text("IP", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text("Latency", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.width(82.dp))
                    }
                    Spacer(Modifier.height(6.dp))
                    LazyColumn(modifier = Modifier.fillMaxWidth().height(170.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (sortedIps.isEmpty()) {
                            item { Text("No saved clean IPs yet.", color = Color.White.copy(alpha = .78f), fontSize = 11.sp) }
                        } else {
                            items(sortedIps) { ip: String ->
                                val ping = state.savedCleanIpPings[ip]
                                Row(Modifier.fillMaxWidth().background(Color.White.copy(alpha = .055f), RoundedCornerShape(9.dp)).padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(ip, color = Color.White.copy(alpha = .88f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    Text(if (ping != null && ping >= 0L) "${ping}ms" else "—", color = Color.White.copy(alpha = .76f), fontSize = 12.sp, textAlign = TextAlign.End, modifier = Modifier.width(82.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PublicSettingsDialog(
    state: SimorghPublicState,
    onDismiss: () -> Unit,
    onIspSelected: (String) -> Unit,
    onToggleSni: (String) -> Unit,
    onManualIpModeChanged: (Boolean) -> Unit,
    onManualIpsChanged: (String) -> Unit,
    onMaxScanIpsChanged: (Int) -> Unit,
    onScanSpeedChanged: (String) -> Unit,
    onProxyProtocolChanged: (String) -> Unit,
    onRouteStrategyChanged: (String) -> Unit
) {
    var page by remember { mutableStateOf("ISP") }
    var ispSearch by remember { mutableStateOf("") }
    var sniSearch by remember { mutableStateOf("") }
    var maxScanText by remember(state.maxScanIps) { mutableStateOf(state.maxScanIps.toString()) }
    val filteredIsps = remember(state.ispOptions, ispSearch) {
        val q = ispSearch.trim().lowercase()
        if (q.isBlank()) state.ispOptions else state.ispOptions.filter { it.lowercase().contains(q) }
    }
    val filteredSnis = remember(state.sniOptions, sniSearch) {
        val q = sniSearch.trim().lowercase()
        if (q.isBlank()) state.sniOptions else state.sniOptions.filter { it.lowercase().contains(q) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { LiquidBubbleText("Settings", big = true) },
        containerColor = Color(0xFF050507).copy(alpha = .96f),
        titleContentColor = Color.White,
        textContentColor = Color.White,
        shape = RoundedCornerShape(30.dp),
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(26.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(
                                Color.White.copy(alpha = .105f),
                                Color(0xFFFF1744).copy(alpha = .045f),
                                Color.White.copy(alpha = .045f)
                            )
                        )
                    )
                    .border(1.dp, Color.White.copy(alpha = .16f), RoundedCornerShape(26.dp))
                    .padding(10.dp)
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SettingTabButton("ISP", page == "ISP", Modifier.weight(1f)) { page = "ISP" }
                    SettingTabButton("SNI", page == "SNI", Modifier.weight(1f)) { page = "SNI" }
                    SettingTabButton("Manual", page == "Manual", Modifier.weight(1.15f)) { page = "Manual" }
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SettingTabButton("Scan", page == "Scan", Modifier.weight(1f)) { page = "Scan" }
                    SettingTabButton("Proxy", page == "Proxy", Modifier.weight(1f)) { page = "Proxy" }
                    SettingTabButton("Route", page == "Route", Modifier.weight(1f)) { page = "Route" }
                }
                Spacer(Modifier.height(10.dp))
                when (page) {
                    "ISP" -> Column(Modifier.fillMaxWidth()) {
                        Text("Selected ISP", fontWeight = FontWeight.Bold)
                        Text(state.selectedIsp, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(value = ispSearch, onValueChange = { ispSearch = it }, label = { Text("Search ISP") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        LazyColumn(modifier = Modifier.fillMaxWidth().height(300.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(filteredIsps) { isp -> SelectableRow(text = isp, selected = isp == state.selectedIsp, onClick = { onIspSelected(isp) }) }
                        }
                    }
                    "SNI" -> Column(Modifier.fillMaxWidth()) {
                        Text("Selected SNI", fontWeight = FontWeight.Bold)
                        Text(state.selectedSnis.joinToString(", "), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(value = sniSearch, onValueChange = { sniSearch = it }, label = { Text("Search SNI") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        LazyColumn(modifier = Modifier.fillMaxWidth().height(300.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(filteredSnis) { sni -> SelectableRow(text = sni, selected = state.selectedSnis.contains(sni), onClick = { onToggleSni(sni) }) }
                        }
                    }
                    "Manual" -> Column(Modifier.fillMaxWidth()) {
                        Text("Manual IPs", fontWeight = FontWeight.Bold)
                        Text("Paste IPs exactly like RKh-MSP. When enabled, ISP range scanning is skipped.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 16.sp)
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SettingTabButton("Manual ON", state.manualIpMode, Modifier.weight(1f)) { onManualIpModeChanged(true) }
                            SettingTabButton("Manual OFF", !state.manualIpMode, Modifier.weight(1f)) { onManualIpModeChanged(false) }
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = state.manualIpsText,
                            onValueChange = onManualIpsChanged,
                            label = { Text("IP list / one per line") },
                            placeholder = { Text("203.0.113.10\n198.51.100.25\n192.0.2.0/24") },
                            modifier = Modifier.fillMaxWidth().height(210.dp),
                            minLines = 8,
                            maxLines = 12
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("Parsed: ${state.manualCandidateCount} • SOCKS5: 127.0.0.1:${state.proxyPort}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    "Scan" -> Column(Modifier.fillMaxWidth()) {
                        Text("Scan Performance", fontWeight = FontWeight.Bold)
                        Text("Choose scan speed and max IP count.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SettingTabButton("Slow", state.scanSpeed == "slow", Modifier.weight(1f)) { onScanSpeedChanged("slow") }
                            SettingTabButton("Normal", state.scanSpeed == "normal", Modifier.weight(1f)) { onScanSpeedChanged("normal") }
                            SettingTabButton("Fast", state.scanSpeed == "fast", Modifier.weight(1f)) { onScanSpeedChanged("fast") }
                        }
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = maxScanText,
                            onValueChange = { value ->
                                maxScanText = value.filter { it.isDigit() }.take(5)
                                maxScanText.toIntOrNull()?.let { onMaxScanIpsChanged(it) }
                            },
                            label = { Text("Max IPs to scan") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(12.dp))
                        SettingInfoLine("Current mode", if (state.selectedRunMode == "vpn") "VPN" else "Proxy")
                        SettingInfoLine("Selected proxy", "${state.selectedProxyProtocol.uppercase()} 127.0.0.1:${state.proxyPort}")
                        SettingInfoLine("Saved IPs", state.savedCleanIps.size.toString())
                        SettingInfoLine("Manual", if (state.manualIpMode) "ON (${state.manualCandidateCount})" else "OFF")
                    }
                    "Proxy" -> Column(Modifier.fillMaxWidth()) {
                        Text("Proxy Protocol", fontWeight = FontWeight.Bold)
                        Text("Choose the local proxy protocol. Ports are separate to avoid conflicts.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SettingTabButton("SOCKS5", state.selectedProxyProtocol == "socks5", Modifier.weight(1f)) { onProxyProtocolChanged("socks5") }
                            SettingTabButton("HTTP", state.selectedProxyProtocol == "http", Modifier.weight(1f)) { onProxyProtocolChanged("http") }
                        }
                        Spacer(Modifier.height(12.dp))
                        SettingInfoLine("SOCKS5", "127.0.0.1:${state.socks5ProxyPort}")
                        SettingInfoLine("HTTP", "127.0.0.1:${state.httpProxyPort}")
                        SettingInfoLine("Active selection", "${state.selectedProxyProtocol.uppercase()} 127.0.0.1:${state.proxyPort}")
                    }
                    "Route" -> Column(Modifier.fillMaxWidth()) {
                        Text("Routing Strategy", fontWeight = FontWeight.Bold)
                        Text("Default tries all clean/saved IPs for the requested host and keeps the working route until it fails.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 16.sp)
                        Spacer(Modifier.height(10.dp))
                        val options = listOf(
                            "default" to "0. Default",
                            "random" to "1. Random",
                            "round_robin" to "2. Round Robin",
                            "least_loss" to "3. Least Loss",
                            "lowest_latency" to "4. Lowest Latency",
                            "hybrid_score" to "5. Hybrid Score"
                        )
                        LazyColumn(modifier = Modifier.fillMaxWidth().height(300.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(options) { item ->
                                SelectableRow(text = item.second, selected = state.routeStrategy == item.first, onClick = { onRouteStrategyChanged(item.first) })
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { LiquidBubbleText("Done", big = false) } }
    )
}


@Composable
private fun SettingTabButton(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val softPulse by animateFloatAsState(
        targetValue = if (selected) 1.0f else 0.96f,
        animationSpec = tween(durationMillis = 260),
        label = "settingTabPulse"
    )
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = softPulse
                scaleY = softPulse
            }
            .clip(shape)
            .background(
                Brush.linearGradient(
                    if (selected) {
                        listOf(
                            Color.White.copy(alpha = .23f),
                            Color.White.copy(alpha = .10f),
                            Color(0xFFFF1744).copy(alpha = .09f)
                        )
                    } else {
                        listOf(
                            Color.White.copy(alpha = .10f),
                            Color.White.copy(alpha = .045f)
                        )
                    }
                )
            )
            .border(
                1.dp,
                if (selected) Color.White.copy(alpha = .34f) else Color.White.copy(alpha = .14f),
                shape
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (selected) Color.White else Color.White.copy(alpha = .78f),
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SelectableRow(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                Brush.linearGradient(
                    if (selected) {
                        listOf(
                            Color.White.copy(alpha = .20f),
                            Color(0xFF00E676).copy(alpha = .08f),
                            Color.White.copy(alpha = .08f)
                        )
                    } else {
                        listOf(
                            Color.White.copy(alpha = .075f),
                            Color.White.copy(alpha = .035f)
                        )
                    }
                )
            )
            .border(
                1.dp,
                if (selected) Color.White.copy(alpha = .30f) else Color.White.copy(alpha = .10f),
                shape
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            color = Color.White.copy(alpha = if (selected) .96f else .78f),
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (selected) {
            Text("✓", color = Color.White.copy(alpha = .90f), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun SettingInfoLine(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(15.dp))
            .background(Color.White.copy(alpha = .070f))
            .border(1.dp, Color.White.copy(alpha = .10f), RoundedCornerShape(15.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = Color.White.copy(alpha = .68f),
            fontSize = 11.sp,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            value,
            color = Color.White.copy(alpha = .90f),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1.25f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
    Spacer(Modifier.height(7.dp))
}

@Composable
private fun TelegramFooter() {
    val context = LocalContext.current
    val pulse by rememberInfiniteTransition(label = "telegramPulse").animateFloat(
        initialValue = .42f,
        targetValue = .86f,
        animationSpec = infiniteRepeatable(tween(1700), RepeatMode.Reverse),
        label = "telegramPulseValue"
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(
                    Brush.linearGradient(
                        listOf(
                            Color.White.copy(alpha = .20f + .05f * pulse),
                            Color.White.copy(alpha = .075f),
                            Color(0xFFFF1744).copy(alpha = .08f + .04f * pulse)
                        )
                    )
                )
                .border(
                    1.dp,
                    Brush.linearGradient(
                        listOf(
                            Color.White.copy(alpha = .44f),
                            Color.White.copy(alpha = .12f),
                            Color(0xFFFF1744).copy(alpha = .20f)
                        )
                    ),
                    RoundedCornerShape(999.dp)
                )
                .shadow(10.dp, RoundedCornerShape(999.dp), clip = false)
                .clickable {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/pingplas_channel")))
                    }
                }
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                text = "Telegram: @pingplas_channel",
                color = Color.White.copy(alpha = .88f + .08f * pulse),
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center
            )
        }
    }
}
private fun cfLatencyRank(value: String?): Long {
    val text = value?.trim().orEmpty()
    if (text.isBlank()) return Long.MAX_VALUE - 2
    if (text.equals("timeout", ignoreCase = true)) return Long.MAX_VALUE - 1
    if (text.equals("failed", ignoreCase = true) || text.equals("fail", ignoreCase = true)) return Long.MAX_VALUE
    return text.removeSuffix("ms").trim().toLongOrNull() ?: Long.MAX_VALUE - 2
}

@Composable
private fun CfConfigCard(
    state: SimorghPublicState,
    onCfEnabledChange: (Boolean) -> Unit,
    onCfVlessChanged: (String) -> Unit,
    onCfPingIp: (String) -> Unit,
    onCfPingAll: () -> Unit,
    onCfConnectIp: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var vlessText by remember(state.cfVlessConfig) { mutableStateOf(state.cfVlessConfig) }
    var sortByLatency by remember { mutableStateOf(true) }
    val cfIps = remember(state.savedCleanIps) {
        // CF Config must use only the IP Memory source. Manual IPs are merged into IP Memory by the ViewModel.
        state.savedCleanIps.map { it.trim() }.filter { it.isNotBlank() }.distinct()
    }
    val sortedIps = remember(cfIps, state.cfPingResults, state.savedCleanIpPings, sortByLatency) {
        if (sortByLatency) {
            cfIps.sortedWith(
                compareBy<String> { ip -> cfLatencyRank(state.cfPingResults[ip]) }
                    .thenBy { ip -> state.savedCleanIpPings[ip] ?: Long.MAX_VALUE }
                    .thenBy { ip -> ip }
            )
        } else {
            cfIps
        }
    }
    val validVless = state.cfVlessConfig.trim().startsWith("vless://", ignoreCase = true)
    GlassCard(accent = Color(0xFFEF4444)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                LiquidBubbleText("CF Config", big = true)
                Spacer(Modifier.height(8.dp))
                LiquidBubbleText("Clouflare Vless Ws TLS Config", big = false)
                Spacer(Modifier.height(8.dp))
                CfLiquidToggle(
                    enabled = state.cfEnabled,
                    onToggle = { onCfEnabledChange(!state.cfEnabled) }
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onCfPingAll,
                    enabled = state.cfEnabled && validVless && cfIps.isNotEmpty(),
                    shape = RoundedCornerShape(999.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF1744).copy(alpha = .88f))
                ) { Text("Latency All", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                Button(
                    onClick = { expanded = !expanded },
                    shape = RoundedCornerShape(999.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = .16f))
                ) { Text(if (expanded) "Hide" else "Show", color = Color.White, fontSize = 12.sp) }
            }
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = vlessText,
                onValueChange = { value ->
                    vlessText = value
                    onCfVlessChanged(value)
                },
                label = { Text("Paste VLESS config") },
                placeholder = { Text("vless://uuid@domain:443?security=tls&type=ws&host=...&path=...") },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
                enabled = state.cfEnabled,
                textStyle = TextStyle(fontSize = 12.sp)
            )
            if (state.cfStatus.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                LiquidBubbleParagraph(
                    text = state.cfStatus,
                    modifier = Modifier.fillMaxWidth(),
                    centered = false,
                    maxLines = 2
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().background(Color.White.copy(alpha = .10f), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Clean IP", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(
                    "Latency ↕",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.End,
                    modifier = Modifier
                        .width(76.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { sortByLatency = true }
                        .padding(vertical = 2.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("Action", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.width(132.dp))
            }
            Spacer(Modifier.height(6.dp))
            LazyColumn(modifier = Modifier.fillMaxWidth().height(220.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (sortedIps.isEmpty()) {
                    item { Text("No IP Memory yet. Run SIMORGH scanner or add Manual IPs first.", color = Color.White.copy(alpha = .76f), fontSize = 11.sp) }
                } else {
                    items(sortedIps) { ip: String ->
                        val pingText = state.cfPingResults[ip]
                        Row(Modifier.fillMaxWidth().background(Color.White.copy(alpha = .055f), RoundedCornerShape(12.dp)).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(ip, color = Color.White.copy(alpha = .90f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text(
                                pingText ?: "—",
                                color = when {
                                    pingText?.endsWith("ms") == true -> Color.White.copy(alpha = .82f)
                                    pingText.equals("Timeout", ignoreCase = true) || pingText.equals("Failed", ignoreCase = true) -> Color(0xFFFFB4B4)
                                    else -> Color.White.copy(alpha = .66f)
                                },
                                fontSize = 12.sp,
                                textAlign = TextAlign.End,
                                modifier = Modifier.width(76.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Row(Modifier.width(132.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Button(
                                    onClick = { onCfPingIp(ip) },
                                    enabled = state.cfEnabled && validVless,
                                    shape = RoundedCornerShape(999.dp),
                                    contentPadding = PaddingValues(horizontal = 9.dp, vertical = 6.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22C55E).copy(alpha = .88f))
                                ) { Text("Latency", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                                Button(
                                    onClick = { onCfConnectIp(ip) },
                                    enabled = state.cfEnabled && validVless,
                                    shape = RoundedCornerShape(999.dp),
                                    contentPadding = PaddingValues(horizontal = 9.dp, vertical = 6.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF1744).copy(alpha = .90f))
                                ) { Text("Connect", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                            }
                        }
                    }
                }
            }
            Text("Only the VLESS address is replaced with the clean IP. SNI/Host/Path stay from your config.", color = Color.White.copy(alpha = .58f), fontSize = 10.sp, lineHeight = 13.sp)
        }
    }
}

@Composable
private fun CfLiquidToggle(enabled: Boolean, onToggle: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        Color.White.copy(alpha = if (enabled) .20f else .11f),
                        Color(0xFFFF1744).copy(alpha = if (enabled) .13f else .055f),
                        Color.White.copy(alpha = .055f)
                    )
                )
            )
            .border(1.dp, Color.White.copy(alpha = if (enabled) .22f else .11f), RoundedCornerShape(999.dp))
            .clickable { onToggle() }
            .padding(horizontal = 7.dp, vertical = 5.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                modifier = Modifier
                    .width(32.dp)
                    .height(18.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (enabled) Color(0xFFE11D48).copy(alpha = .48f) else Color.White.copy(alpha = .14f))
                    .padding(2.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .align(if (enabled) Alignment.CenterEnd else Alignment.CenterStart)
                        .clip(CircleShape)
                        .background(if (enabled) Color.White.copy(alpha = .92f) else Color.White.copy(alpha = .55f))
                )
            }
            Text(
                if (enabled) "CF ON" else "CF OFF",
                color = Color.White.copy(alpha = .88f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun LiquidBubbleText(text: String, big: Boolean) {
    val transition = rememberInfiniteTransition(label = "liquidBubble")
    val glow by transition.animateFloat(
        initialValue = .62f,
        targetValue = .68f,
        animationSpec = infiniteRepeatable(tween(if (big) 5600 else 6400), RepeatMode.Reverse),
        label = "liquidBubbleGlow"
    )
    val shift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(11800), RepeatMode.Restart),
        label = "liquidBubbleShift"
    )
    Box(
        modifier = Modifier
            .graphicsLayer(scaleX = 1f + .0008f * glow, scaleY = 1f + .0008f * glow)
            .shadow(if (big) 4.dp else 2.dp, RoundedCornerShape(999.dp), ambientColor = Color.White.copy(alpha = .018f * glow), spotColor = Color(0xFFFF1744).copy(alpha = .026f * glow))
            .clip(RoundedCornerShape(999.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = .14f + .015f * glow),
                        Color(0xFFFF1744).copy(alpha = .045f + .014f * glow),
                        Color.White.copy(alpha = .035f + .010f * glow)
                    ),
                    start = Offset(-180f + 360f * shift, 0f),
                    end = Offset(180f + 360f * shift, 80f)
                )
            )
            .padding(horizontal = if (big) 13.dp else 10.dp, vertical = if (big) 7.dp else 5.dp)
    ) {
        Text(
            text = text,
            color = Color.White.copy(alpha = if (big) .98f else .78f),
            fontSize = if (big) 18.sp else 11.sp,
            fontFamily = FontFamily.SansSerif,
            fontWeight = if (big) FontWeight.ExtraBold else FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}


@Composable
private fun LiquidBubbleParagraph(text: String, modifier: Modifier = Modifier, centered: Boolean = false, maxLines: Int = 3) {
    val transition = rememberInfiniteTransition(label = "liquidParagraph")
    val glow by transition.animateFloat(
        initialValue = .48f,
        targetValue = .56f,
        animationSpec = infiniteRepeatable(tween(7200), RepeatMode.Reverse),
        label = "liquidParagraphGlow"
    )
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        Color.White.copy(alpha = .105f + .010f * glow),
                        Color(0xFFFF1744).copy(alpha = .026f + .008f * glow),
                        Color.White.copy(alpha = .040f)
                    )
                )
            )
            .border(1.dp, Color.White.copy(alpha = .10f + .018f * glow), RoundedCornerShape(20.dp))
            .padding(horizontal = 11.dp, vertical = 8.dp)
    ) {
        Text(
            text = text,
            color = Color.White.copy(alpha = .74f),
            fontSize = 11.sp,
            lineHeight = 15.sp,
            textAlign = if (centered) TextAlign.Center else TextAlign.Start,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun PublicLogsCard() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var showLogs by remember { mutableStateOf(false) }
    var logText by remember { mutableStateOf(RKhVpnLogStore.readText(context)) }
    fun refreshLogs() { logText = RKhVpnLogStore.readText(context) }
    val grouped = remember(logText) { categorizeLogs(logText) }

    GlassCard(accent = Color(0xFF22C55E)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Clean Logs", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(if (logText.isBlank()) "No logs yet." else "${logText.lineSequence().count()} raw lines • grouped", color = Color.White.copy(alpha = .66f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Button(onClick = { refreshLogs(); showLogs = true }, shape = RoundedCornerShape(999.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22C55E))) {
                Text("Logs", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
            }
        }
    }
    if (showLogs) {
        val visibleText = grouped.ifBlank { "No SIMORGH logs yet." }
        AlertDialog(
            onDismissRequest = { showLogs = false },
            title = { Text("SIMORGH Logs") },
            text = { SelectionContainer { LazyColumn(modifier = Modifier.fillMaxWidth().height(360.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { item { Text(visibleText, color = MaterialTheme.colorScheme.onSurface, fontSize = 11.sp, lineHeight = 15.sp) } } } },
            confirmButton = { TextButton(onClick = { refreshLogs(); clipboard.setText(AnnotatedString(categorizeLogs(RKhVpnLogStore.readText(context)))) }) { Text("Copy Clean") } },
            dismissButton = { Row { TextButton(onClick = { RKhVpnLogStore.clear(context); refreshLogs() }) { Text("Clear") }; TextButton(onClick = { refreshLogs() }) { Text("Refresh") }; TextButton(onClick = { showLogs = false }) { Text("Close") } } }
        )
    }
}

private fun categorizeLogs(raw: String): String {
    if (raw.isBlank()) return ""
    val scan = mutableListOf<String>()
    val proxy = mutableListOf<String>()
    val core = mutableListOf<String>()
    val errors = mutableListOf<String>()
    val other = mutableListOf<String>()
    raw.lineSequence().filter { it.isNotBlank() }.toList().takeLast(240).forEach { line: String ->
        when {
            line.contains("failed", true) || line.contains("error", true) || line.contains("Exception", true) -> errors += line
            line.contains("Clean IP", true) || line.contains("Scan progress", true) || line.contains("Scanner profile", true) -> scan += line
            line.contains("SOCKS5", true) || line.contains("MSP Proxy", true) || line.contains("MSP SOCKS5", true) -> proxy += line
            line.contains("CoreBin", true) || line.contains("Xray", true) || line.contains("Tun2Socks", true) -> core += line
            else -> other += line
        }
    }
    fun section(name: String, lines: List<String>) = if (lines.isEmpty()) "" else "\n[$name]\n" + lines.takeLast(70).joinToString("\n") + "\n"
    return buildString {
        append(section("Errors", errors))
        append(section("Scanner", scan))
        append(section("SOCKS5 Proxy", proxy))
        append(section("Core", core))
        append(section("Other", other))
    }.trim()
}

@Composable
private fun InfoText(label: String, value: String, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "infoBubble")
    val glow by transition.animateFloat(
        initialValue = .50f,
        targetValue = .56f,
        animationSpec = infiniteRepeatable(tween(7200), RepeatMode.Reverse),
        label = "infoBubbleGlow"
    )
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        Color.White.copy(alpha = .115f + .015f * glow),
                        Color(0xFFFF1744).copy(alpha = .035f + .010f * glow),
                        Color.White.copy(alpha = .040f)
                    )
                )
            )
            .border(1.dp, Color.White.copy(alpha = .12f + .025f * glow), RoundedCornerShape(18.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, color = Color.White.copy(alpha = .62f), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(value, color = Color.White.copy(alpha = .92f), fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun GlassCard(accent: Color, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    Card(
        modifier = modifier.fillMaxWidth().shadow(14.dp, shape),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = .095f)),
        border = BorderStroke(1.dp, Color.White.copy(alpha = .18f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(
                            Color.White.copy(.12f),
                            Color(0xFFFF1744).copy(.055f),
                            Color.Transparent
                        )
                    )
                )
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) { content() }
    }
}

@Composable
private fun AnimatedSimorghBackground(state: SimorghPublicState, modifier: Modifier = Modifier) {
    val active = state.connected || state.connecting || state.simpleConnected || state.simpleConnecting || state.nipoConnected || state.nipoConnecting
    var gifFailed by remember { mutableStateOf(false) }
    val overlayAlpha = if (active) .18f else .42f
    val imageAlpha = if (active) .74f else .52f

    Box(
        modifier.background(
            Brush.verticalGradient(
                listOf(
                    Color.Black,
                    Color(0xFF050003),
                    Color.Black
                )
            )
        )
    ) {
        if (!gifFailed) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    ImageView(ctx).apply {
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        adjustViewBounds = true
                        setBackgroundColor(android.graphics.Color.BLACK)
                        alpha = imageAlpha
                        runCatching {
                            if (Build.VERSION.SDK_INT >= 28) {
                                val source = ImageDecoder.createSource(ctx.resources, R.drawable.simorgh_fire_bg)
                                val drawable = ImageDecoder.decodeDrawable(source)
                                setImageDrawable(drawable)
                                if (drawable is AnimatedImageDrawable) {
                                    drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                                    drawable.start()
                                }
                            } else {
                                // Older devices may not animate GIFs natively, but this is safer than crashing.
                                setImageResource(R.drawable.simorgh_fire_bg)
                            }
                        }.onFailure {
                            setImageDrawable(null)
                            gifFailed = true
                        }
                    }
                },
                update = { view ->
                    view.scaleType = ImageView.ScaleType.FIT_CENTER
                    view.alpha = imageAlpha
                    val drawable = view.drawable
                    if (Build.VERSION.SDK_INT >= 28 && drawable is AnimatedImageDrawable && !drawable.isRunning) {
                        runCatching { drawable.start() }
                    }
                }
            )
        }

        Canvas(Modifier.fillMaxSize()) {
            if (gifFailed) {
                drawCircle(
                    Color(0xFFFF1F3D).copy(alpha = if (active) .30f else .16f),
                    radius = size.minDimension * .42f,
                    center = Offset(size.width * .52f, size.height * .42f)
                )
                drawCircle(
                    Color(0xFFFF9A2E).copy(alpha = if (active) .20f else .08f),
                    radius = size.minDimension * .30f,
                    center = Offset(size.width * .20f, size.height * .76f)
                )
            }
            drawRect(Color.Black.copy(alpha = overlayAlpha))
            drawRect(
                brush = Brush.verticalGradient(
                    listOf(
                        Color.Black.copy(alpha = .92f),
                        Color.Black.copy(alpha = .18f),
                        Color.Transparent,
                        Color.Black.copy(alpha = .20f),
                        Color.Black.copy(alpha = .94f)
                    )
                )
            )
            drawRect(
                brush = Brush.horizontalGradient(
                    listOf(
                        Color.Black.copy(alpha = .72f),
                        Color.Transparent,
                        Color.Transparent,
                        Color.Black.copy(alpha = .72f)
                    )
                )
            )
        }
    }
}



private fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
