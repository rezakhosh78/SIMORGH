package com.rkh.vpn.ui

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rkh.vpn.data.AppState
import com.rkh.vpn.data.FormatUtils
import com.rkh.vpn.data.ServerConfig
import com.rkh.vpn.data.SpeedSample
import com.rkh.vpn.data.SubscriptionRepository
import com.rkh.vpn.data.UsageInfo

@Composable
fun RKhVpnApp(
    vm: RKhVpnViewModel,
    publicVm: SimorghPublicViewModel,
    onPrivateConnect: () -> Unit,
    onPrivateStop: () -> Unit,
    onPublicConnect: () -> Unit,
    onPublicProxy: () -> Unit,
    onPublicStop: () -> Unit,
    onCfConnectIp: (String) -> Unit,
    onSimpleConnect: () -> Unit,
    onSimpleUpdate: () -> Unit,
    onSimpleNextHealthy: () -> Unit,
    onSimpleClearCache: () -> Unit,
    onSimplePingAll: () -> Unit,
    onSimpleConnectConfig: (Int) -> Unit,
    onNipoConnect: () -> Unit,
    onScanQr: () -> Unit
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val publicState by publicVm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val dark = state.darkTheme
    val bgPulse by rememberInfiniteTransition(label = "bgPulse").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(7800), repeatMode = RepeatMode.Reverse),
        label = "bgPulseValue"
    )
    val orbPulse by rememberInfiniteTransition(label = "orbPulse").animateFloat(
        initialValue = 0.65f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(animation = tween(4200), repeatMode = RepeatMode.Reverse),
        label = "orbPulseValue"
    )

    val scheme = when {
        state.monet && Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(context)
        state.monet && Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
        dark -> darkColorScheme(
            primary = Color(0xFFFF7A18),
            secondary = Color(0xFFFFB36B),
            surface = Color(0xFF151515),
            background = Color(0xFF090909)
        )
        else -> lightColorScheme(
            primary = Color(0xFFFF7A18),
            secondary = Color(0xFF9C4D00)
        )
    }

    MaterialTheme(colorScheme = scheme) {
        Surface(Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(animatedBackground(bgPulse, dark))
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(Color(0xFFFF7A18).copy(alpha = 0.10f), radius = size.minDimension * .44f * orbPulse, center = Offset(size.width * .90f, size.height * .12f))
                    drawCircle(Color(0xFF6BB6FF).copy(alpha = 0.08f), radius = size.minDimension * .34f * (1.55f - orbPulse), center = Offset(size.width * .10f, size.height * .72f))
                    drawCircle(Color(0xFFB5179E).copy(alpha = 0.07f), radius = size.minDimension * .26f * orbPulse, center = Offset(size.width * .70f, size.height * .88f))
                }
                SimorghPublicHome(
                    state = publicState,
                    darkTheme = state.darkTheme,
                    onToggleTheme = { /* day/night button removed in SIMORGH public UI */ },
                    onConnect = onPublicConnect,
                    onStartProxy = onPublicProxy,
                    onStop = onPublicStop,
                    onModeSelected = { publicVm.setRunMode(it) },
                    onIspSelected = { publicVm.setSelectedIsp(it) },
                    onToggleSni = { publicVm.toggleSni(it) },
                    onManualIpModeChanged = { publicVm.setManualIpMode(it) },
                    onManualIpsChanged = { publicVm.setManualIpsText(it) },
                    onMaxScanIpsChanged = { publicVm.setMaxScanIps(it) },
                    onScanSpeedChanged = { publicVm.setScanSpeed(it) },
                    onProxyProtocolChanged = { publicVm.setProxyProtocol(it) },
                    onRouteStrategyChanged = { publicVm.setRouteStrategy(it) },
                    onClearSavedCleanIps = { publicVm.clearSavedCleanIps() },
                    onPingSavedCleanIps = { publicVm.pingSavedCleanIps() },
                    onNextRouteIp = { publicVm.nextRouteIp() },
                    onCfEnabledChange = { publicVm.setCfEnabled(it) },
                    onCfVlessChanged = { publicVm.setCfVlessConfig(it) },
                    onCfPingIp = { publicVm.pingCfIp(it) },
                    onCfPingAll = { publicVm.pingAllCfIps() },
                    onCfConnectIp = onCfConnectIp,
                    onSimpleConnect = onSimpleConnect,
                    onSimpleUpdate = onSimpleUpdate,
                    onSimpleNextHealthy = onSimpleNextHealthy,
                    onSimpleClearCache = onSimpleClearCache,
                    onSimplePingAll = onSimplePingAll,
                    onSimpleConnectConfig = onSimpleConnectConfig,
                    onSimpleServerlessChanged = { publicVm.setSimpleServerlessEnabled(it) },
                    onNipoConnect = onNipoConnect,
                    onNipoImportChanged = { publicVm.setNipoImportText(it) },
                    onNipoAddProfile = { publicVm.addNipoProfileFromInput() },
                    onNipoSelectProfile = { publicVm.selectNipoProfile(it) },
                    onNipoDeleteProfile = { publicVm.deleteSelectedNipoProfile() },
                    onNipoSaveProfile = { publicVm.saveCurrentNipoProfile() },
                    onNipoFieldChanged = { field, value -> publicVm.setNipoField(field, value) },
                    onNipoBooleanChanged = { field, value -> publicVm.setNipoBoolean(field, value) },
                    onNipoTest = { publicVm.testNipoConfig() },
                    onNipoReset = { publicVm.resetNipoConfig() },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

private fun animatedBackground(pulse: Float, dark: Boolean): Brush {
    return Brush.linearGradient(
        colors = listOf(
            if (dark) Color(0xFF050608) else Color(0xFFFFFBF5),
            Color(0xFFFF7A18).copy(alpha = 0.12f + pulse * 0.10f),
            Color(0xFF3A0CA3).copy(alpha = if (dark) 0.23f else 0.10f),
            Color(0xFF00B4D8).copy(alpha = if (dark) 0.10f else 0.06f),
            if (dark) Color(0xFF101010) else Color(0xFFFFF7EC)
        ),
        start = Offset(0f, 0f),
        end = Offset(1200f, 1800f)
    )
}

@Composable
private fun FixedHeader(
    status: String,
    darkTheme: Boolean,
    usage: UsageInfo,
    selectedServer: ServerConfig?,
    down: Long,
    up: Long,
    onToggleTheme: () -> Unit,
    onScanQr: () -> Unit
) {
    val progress by animateFloatAsState(targetValue = usage.usedPercent.coerceIn(0f, 1f), label = "headerUsage")
    val remainingText = remainingTimeText(selectedServer?.name)
    val shape = RoundedCornerShape(bottomStart = 26.dp, bottomEnd = 26.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.08f), shape)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 18.dp, end = 12.dp, top = 12.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    Text("SIMORGH", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
                    Text(
                        text = status,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 13.sp
                    )
                }
                IconButton(onClick = onToggleTheme) {
                    Text(if (darkTheme) "☀️" else "🌙", fontSize = 22.sp)
                }
                Spacer(Modifier.width(2.dp))
            }
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Usage ${((usage.usedPercent.coerceIn(0f, 1f)) * 100).toInt()}%", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text("↓ ${FormatUtils.kbps(down)}  ↑ ${FormatUtils.kbps(up)}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                Canvas(Modifier.fillMaxWidth().height(7.dp)) {
                    drawLine(Color.White.copy(alpha = .14f), Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f), strokeWidth = size.height, cap = StrokeCap.Round)
                    drawLine(Brush.horizontalGradient(listOf(Color(0xFFFF7A18), Color(0xFFFFD166), Color(0xFF6BB6FF))), Offset(0f, size.height / 2f), Offset(size.width * progress, size.height / 2f), strokeWidth = size.height, cap = StrokeCap.Round)
                }
                Text(
                    "Total: ${FormatUtils.bytes(usage.totalBytes)} • Remaining: ${FormatUtils.bytes(usage.remainingBytes)} • Time: $remainingText",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun BarcodeGlyph() {
    val c = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(28.dp)) {
        val r = 3.dp.toPx()
        fun box(x: Float, y: Float, w: Float = 5f, h: Float = 5f) {
            drawRoundRect(
                color = c,
                topLeft = Offset(x.dp.toPx(), y.dp.toPx()),
                size = Size(w.dp.toPx(), h.dp.toPx()),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r)
            )
        }
        box(1f, 1f, 8f, 8f); box(3f, 3f, 4f, 4f)
        box(19f, 1f, 8f, 8f); box(21f, 3f, 4f, 4f)
        box(1f, 19f, 8f, 8f); box(3f, 21f, 4f, 4f)
        box(13f, 13f, 4f, 4f); box(19f, 14f, 3f, 3f); box(24f, 13f, 3f, 7f)
        box(13f, 21f, 4f, 3f); box(20f, 23f, 7f, 4f); box(11f, 4f, 3f, 8f)
    }
}

@Composable
private fun StickyMiniUsage(usage: UsageInfo, selectedServer: ServerConfig?, down: Long, up: Long) {
    val progress by animateFloatAsState(targetValue = usage.usedPercent.coerceIn(0f, 1f), label = "miniUsage")
    val remainingText = remainingTimeText(selectedServer?.name)
    val shape = RoundedCornerShape(bottomStart = 22.dp, bottomEnd = 22.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.08f), shape)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Usage ${((usage.usedPercent.coerceIn(0f, 1f)) * 100).toInt()}%", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text("↓ ${FormatUtils.kbps(down)}  ↑ ${FormatUtils.kbps(up)}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
            Canvas(Modifier.fillMaxWidth().height(7.dp)) {
                drawLine(Color.White.copy(alpha = .14f), Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f), strokeWidth = size.height, cap = StrokeCap.Round)
                drawLine(Brush.horizontalGradient(listOf(Color(0xFFFF7A18), Color(0xFFFFD166), Color(0xFF6BB6FF))), Offset(0f, size.height / 2f), Offset(size.width * progress, size.height / 2f), strokeWidth = size.height, cap = StrokeCap.Round)
            }
            Text(
                "Total: ${FormatUtils.bytes(usage.totalBytes)} • Remaining: ${FormatUtils.bytes(usage.remainingBytes)} • Time: $remainingText",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SubscriptionCard(state: AppState, vm: RKhVpnViewModel) = CardBox(accent = Color(0xFFFF7A18), compact = true) {
    val token = if (state.selectedBaseUrl == SubscriptionRepository.PRIMARY_BASE) state.primaryToken else state.premiumToken
    Text("Subscription", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp, top = 6.dp, bottom = 2.dp))
    Spacer(Modifier.height(5.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = state.selectedBaseUrl == SubscriptionRepository.PRIMARY_BASE,
            onClick = { vm.selectBase(SubscriptionRepository.PRIMARY_BASE) },
            label = { Text("Standard") }
        )
        FilterChip(
            selected = state.selectedBaseUrl == SubscriptionRepository.PREMIUM_BASE,
            onClick = { vm.selectBase(SubscriptionRepository.PREMIUM_BASE) },
            label = { Text("Premium") }
        )
    }
    OutlinedTextField(
        value = token,
        onValueChange = {
            if (state.selectedBaseUrl == SubscriptionRepository.PRIMARY_BASE) vm.setPrimaryToken(it) else vm.setPremiumToken(it)
        },
        label = { Text("Code") },
        placeholder = { Text("Private code") },
        supportingText = { Text("Auto update every 5 min", fontSize = 9.sp, maxLines = 1) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(4.dp))
    OutlinedButton(onClick = vm::loadSubscription, modifier = Modifier.fillMaxWidth()) { Text("Update") }
}

@Composable
private fun UsageCard(usage: UsageInfo, selectedServer: ServerConfig?) = CardBox(accent = Color(0xFF00B4D8)) {
    val progress by animateFloatAsState(targetValue = usage.usedPercent, label = "usage")
    val remainingText = remainingTimeText(selectedServer?.name)

    Text("Usage", fontSize = 20.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(12.dp))
    Canvas(Modifier.fillMaxWidth().height(24.dp)) {
        drawLine(
            color = Color.White.copy(alpha = .17f),
            start = Offset(0f, size.height / 2f),
            end = Offset(size.width, size.height / 2f),
            strokeWidth = size.height,
            cap = StrokeCap.Round
        )
        drawLine(
            brush = Brush.horizontalGradient(listOf(Color(0xFFFF7A18), Color(0xFFFFD166), Color(0xFF6BB6FF))),
            start = Offset(0f, size.height / 2f),
            end = Offset(size.width * progress, size.height / 2f),
            strokeWidth = size.height,
            cap = StrokeCap.Round
        )
    }
    Spacer(Modifier.height(8.dp))
    Text(
        text = "Used: ${FormatUtils.bytes(usage.usedBytes)}  •  Remaining: ${FormatUtils.bytes(usage.remainingBytes)}  •  Total: ${FormatUtils.bytes(usage.totalBytes)}",
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Text(
        text = "Remaining time: $remainingText",
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun SpeedCard(down: Long, up: Long, data: List<SpeedSample>) = CardBox(accent = Color(0xFF6BB6FF)) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("Live speed", fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Download / Upload", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, maxLines = 1)
        }
        Text(
            "↓ ${FormatUtils.kbps(down)}  ↑ ${FormatUtils.kbps(up)}",
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
    Spacer(Modifier.height(12.dp))
    val pulse by rememberInfiniteTransition(label = "graphPulse").animateFloat(
        initialValue = .35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(1450), repeatMode = RepeatMode.Reverse),
        label = "graphPulseValue"
    )
    Canvas(Modifier.fillMaxWidth().height(156.dp)) {
        drawRoundRect(
            brush = Brush.verticalGradient(listOf(Color(0xFFFF7A18).copy(alpha = 0.22f), Color(0xFF6BB6FF).copy(alpha = 0.11f), Color.Transparent)),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(34f, 34f)
        )
        repeat(6) { i ->
            val yy = size.height * (i + 1) / 7f
            drawLine(Color.White.copy(alpha = 0.10f), Offset(0f, yy), Offset(size.width, yy), 1.5f)
        }
        val maxSpeed = data.flatMap { listOf(it.downKbps, it.upKbps) }.maxOrNull()?.coerceAtLeast(64L) ?: 64L
        fun y(speed: Long): Float = size.height - ((speed.toFloat() / maxSpeed.toFloat()) * (size.height * .82f)) - size.height * .08f
        if (data.size > 1) {
            data.zipWithNext().forEachIndexed { index, pair ->
                val x1 = size.width * index / (data.size - 1)
                val x2 = size.width * (index + 1) / (data.size - 1)
                drawLine(Color(0xFFFF7A18).copy(alpha = .9f), Offset(x1, y(pair.first.downKbps)), Offset(x2, y(pair.second.downKbps)), 7f, cap = StrokeCap.Round)
                drawLine(Color(0xFF6BB6FF).copy(alpha = .85f), Offset(x1, y(pair.first.upKbps)), Offset(x2, y(pair.second.upKbps)), 5f, cap = StrokeCap.Round)
            }
            val last = data.last()
            drawCircle(Color(0xFFFF7A18).copy(alpha = .65f * pulse), radius = 16f, center = Offset(size.width, y(last.downKbps)))
            drawCircle(Color(0xFF6BB6FF).copy(alpha = .65f * pulse), radius = 13f, center = Offset(size.width, y(last.upKbps)))
        } else {
            drawLine(Color(0xFFFF7A18), Offset(0f, size.height * .65f), Offset(size.width, size.height * .65f), 6f, cap = StrokeCap.Round)
        }
    }
    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("● Download", color = Color(0xFFFF7A18), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text("● Upload", color = Color(0xFF6BB6FF), fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ConnectCard(connected: Boolean, onConnect: () -> Unit, onStop: () -> Unit) {
    val size by animateDpAsState(targetValue = if (connected) 120.dp else 128.dp, label = "connectSize")
    val glow by rememberInfiniteTransition(label = "connectGlow").animateFloat(
        initialValue = .45f,
        targetValue = .96f,
        animationSpec = infiniteRepeatable(animation = tween(1100), repeatMode = RepeatMode.Reverse),
        label = "connectGlowValue"
    )
    val primary = if (connected) Color(0xFF12B76A) else Color(0xFFE53935)
    val secondary = if (connected) Color(0xFF88F0BA) else Color(0xFFFFC1B6)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(size + 52.dp)) {
            drawCircle(primary.copy(alpha = 0.12f * glow), radius = this.size.minDimension / 2f)
            drawCircle(secondary.copy(alpha = 0.07f * glow), radius = this.size.minDimension / 2.25f)
        }
        Button(
            onClick = if (connected) onStop else onConnect,
            modifier = Modifier.size(size).shadow(14.dp, CircleShape),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = primary),
            contentPadding = PaddingValues(0.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                if (connected) StopIconWhite(Modifier.size(46.dp)) else PlayIconWhite(Modifier.size(48.dp))
                Spacer(Modifier.height(4.dp))
                Text(if (connected) "DISCONNECT" else "CONNECT", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.4.sp, color = Color.White, maxLines = 1)
            }
        }
    }
}

@Composable
private fun FloatingMiniConnect(connected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val color = if (connected) Color(0xFF12B76A) else Color(0xFFE53935)
    Button(
        onClick = onClick,
        modifier = modifier.size(54.dp).shadow(10.dp, CircleShape),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = color),
        contentPadding = PaddingValues(0.dp)
    ) { if (connected) StopIconWhite(Modifier.size(20.dp)) else PlayIconWhite(Modifier.size(20.dp)) }
}

@Composable
private fun PlayIconWhite(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(size.width * .34f, size.height * .22f)
            lineTo(size.width * .34f, size.height * .78f)
            lineTo(size.width * .78f, size.height * .50f)
            close()
        }
        drawPath(path, Color.White)
    }
}

@Composable
private fun StopIconWhite(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        drawRoundRect(
            color = Color.White,
            topLeft = Offset(size.width * .24f, size.height * .24f),
            size = Size(size.width * .52f, size.height * .52f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * .08f, size.width * .08f)
        )
    }
}

@Composable
private fun SmartBestCard(state: AppState, vm: RKhVpnViewModel) = CardBox(accent = Color(0xFFFFD166), compact = true) {
    val best = state.servers.minByOrNull { it.pingMs ?: Long.MAX_VALUE }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Smart Best Server", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(
                text = best?.let { "Real Xray best: ${it.name} • ${it.pingMs ?: "--"}ms" } ?: "Uses real Xray latency, not fake TCP ping",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 12.sp
            )
        }
        Switch(checked = state.smartConnect, onCheckedChange = vm::toggleSmart)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = vm::pingAll, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
            Text("Ping", fontSize = 12.sp)
        }
    }
    Text(
        "Smart ON = auto-connect best server + auto-switch every 5 min",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 11.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun ServerSection(state: AppState, vm: RKhVpnViewModel, onConnect: () -> Unit) = CardBox(accent = Color(0xFFB5179E)) {
    var expanded by remember { mutableStateOf(false) }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Servers (${state.servers.size})", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text(
                text = state.servers.firstOrNull { it.id == state.selectedServerId }?.name ?: "No selected server",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        OutlinedButton(onClick = vm::pingAll, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) { Text("Ping", fontSize = 12.sp) }
        Spacer(Modifier.width(6.dp))
        Button(onClick = { expanded = !expanded }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) { Text(if (expanded) "Hide" else "Show", fontSize = 12.sp) }
    }
    AnimatedVisibility(visible = expanded) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 10.dp)) {
            state.servers.forEach { server ->
                ServerRow(
                    server = server,
                    selected = state.selectedServerId == server.id,
                    onSelect = { vm.selectServer(server.id) },
                    onPing = { vm.pingOne(server.id) },
                    onConnect = {
                        vm.selectServer(server.id)
                        onConnect()
                    }
                )
            }
        }
    }
}

@Composable
private fun ServerRow(server: ServerConfig, selected: Boolean, onSelect: () -> Unit, onPing: () -> Unit, onConnect: () -> Unit) = CardBox(
    modifier = Modifier
        .clickable { onSelect() }
        .border(
            width = if (selected) 2.dp else 0.dp,
            color = MaterialTheme.colorScheme.primary,
            shape = RoundedCornerShape(22.dp)
        ),
    accent = if (selected) MaterialTheme.colorScheme.primary else Color(0xFF6BB6FF)
) {
    val pingText = server.pingMs?.let { "${it}ms" } ?: (server.error ?: "not pinged")
    val hostText = server.host ?: "unknown"
    val portText = server.port ?: 0
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) {
            Text(server.name, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
            Text("$hostText:$portText  •  $pingText", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        OutlinedButton(onClick = onPing, contentPadding = PaddingValues(horizontal = 7.dp, vertical = 1.dp), modifier = Modifier.height(31.dp)) { Text("Ping", fontSize = 10.sp) }
        Spacer(Modifier.width(4.dp))
        Button(onClick = onConnect, contentPadding = PaddingValues(horizontal = 7.dp, vertical = 1.dp), modifier = Modifier.height(31.dp)) { Text("Go", fontSize = 10.sp) }
    }
}

@Composable
private fun CardBox(modifier: Modifier = Modifier, accent: Color = Color(0xFFFF7A18), compact: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val dark = MaterialTheme.colorScheme.background.red < 0.12f && MaterialTheme.colorScheme.background.green < 0.12f
    val shape = RoundedCornerShape(24.dp)
    val surfaceAlpha = if (dark) .72f else .92f
    val borderAlpha = if (dark) .18f else .10f
    Card(
        modifier = modifier.fillMaxWidth().shadow(if (dark) 7.dp else 3.dp, shape),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = surfaceAlpha)),
        border = BorderStroke(1.dp, accent.copy(alpha = borderAlpha))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.surface.copy(alpha = if (dark) .34f else .72f),
                            MaterialTheme.colorScheme.surface.copy(alpha = if (dark) .24f else .62f)
                        ),
                        start = Offset(0f, 0f),
                        end = Offset(700f, 480f)
                    )
                )
                .padding(if (compact) 9.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 6.dp),
            content = content
        )
    }
}


private fun remainingTimeText(serverName: String?): String {
    val name = serverName.orEmpty()
    val patterns = listOf(
        Regex("(\\d{1,3})\\s*(?:days|day|d|روز)", RegexOption.IGNORE_CASE),
        Regex("(\\d{1,3})\\s*(?:hours|hour|h|ساعت)", RegexOption.IGNORE_CASE),
        Regex("(\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2})"),
        Regex("(\\d{1,2}[-/.]\\d{1,2}[-/.]\\d{2,4})")
    )
    for (p in patterns) {
        val m = p.find(name)
        if (m != null) return m.value
    }
    return "Not provided by subscription"
}
