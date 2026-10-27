package com.rkh.vpn.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text as MaterialText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import kotlinx.coroutines.delay
import com.rkh.vpn.R
import com.rkh.vpn.data.RKhVpnLogStore

private data class LauncherEntry(@DrawableRes val iconRes: Int, val key: String, val title: String, val symbol: String, val color: Color)

private val launcherEntries = listOf(
    LauncherEntry(R.drawable.ic_simorgh_msp, "advance", "MSP", "✦", Color(0xFF2D9CDB)),
    LauncherEntry(R.drawable.ic_simorgh_free_v2ray, "simple", "Free V2Ray", "◈", Color(0xFF7C5CFC)),
    LauncherEntry(R.drawable.ic_simorgh_serverless, "serverless", "ServerLess", "◫", Color(0xFF06B6D4)),
    LauncherEntry(R.drawable.ic_simorgh_fragment, "fragment", "Fragment", "⌁", Color(0xFF10B981)),
    LauncherEntry(R.drawable.ic_simorgh_nipovpn, "nipo", "NipoVPN", "◉", Color(0xFFF97316)),
    LauncherEntry(R.drawable.ic_simorgh_master_dns, "stormdns", "MasterDNS", "◎", Color(0xFF14B8A6)),
    LauncherEntry(R.drawable.ic_simorgh_desync, "desync", "Desync", "≋", Color(0xFFEC4899))
)

@Composable
private fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: androidx.compose.ui.text.font.FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: androidx.compose.ui.text.style.TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: androidx.compose.ui.text.style.TextOverflow = androidx.compose.ui.text.style.TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: (TextLayoutResult) -> Unit = {},
    style: TextStyle = LocalTextStyle.current
) {
    val renderedColor = if (color.red >= .96f && color.green >= .96f && color.blue >= .96f) {
        val inherited = LocalContentColor.current.takeUnless { it == Color.Unspecified } ?: MaterialTheme.colorScheme.onSurface
        inherited.copy(alpha = color.alpha)
    } else color
    MaterialText(
        text = text,
        modifier = modifier,
        color = renderedColor,
        fontSize = fontSize,
        fontStyle = fontStyle,
        fontWeight = fontWeight,
        fontFamily = fontFamily,
        letterSpacing = letterSpacing,
        textDecoration = textDecoration,
        textAlign = textAlign,
        lineHeight = lineHeight,
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        minLines = minLines,
        onTextLayout = onTextLayout,
        style = style
    )
}

internal val LocalSectionAccent = staticCompositionLocalOf { Color(0xFF2D9CDB) }
internal val LocalAppIsDarkTheme = staticCompositionLocalOf { true }

internal fun simorghPageBackground(darkTheme: Boolean): Color =
    Color(0xFF0D1219)

internal fun simorghCardSurface(darkTheme: Boolean): Color =
    Color(0xFF171E28)

internal fun simorghInsetSurface(darkTheme: Boolean): Color =
    Color(0xFF202A36)

internal fun simorghControlSurface(darkTheme: Boolean): Color =
    Color(0xFF26313F)

@Composable
internal fun simorghSelectedSurface(darkTheme: Boolean): Color =
    LocalSectionAccent.current.copy(alpha = .24f)

internal fun simorghOutline(darkTheme: Boolean): Color =
    Color(0xFF354151)

internal fun simorghPrimaryText(darkTheme: Boolean): Color =
    Color(0xFFF4F7FA)

internal fun simorghSecondaryText(darkTheme: Boolean): Color =
    Color(0xFFB1BCC9)

@Composable
internal fun simorghAdaptiveButtonColors() = ButtonDefaults.buttonColors(
    containerColor = simorghControlSurface(LocalAppIsDarkTheme.current),
    contentColor = simorghPrimaryText(LocalAppIsDarkTheme.current),
    disabledContainerColor = simorghControlSurface(LocalAppIsDarkTheme.current).copy(alpha = .62f),
    disabledContentColor = simorghSecondaryText(LocalAppIsDarkTheme.current).copy(alpha = .75f)
)

internal fun sectionAccentForPage(key: String): Color = when (key) {
    "home" -> Color(0xFF2D9CDB)
    else -> launcherEntries.firstOrNull { it.key == key }?.color ?: Color(0xFF2D9CDB)
}

private data class DesyncCustomFields(
    val proto: String = "", val auto: String = "", val cacheTtl: String = "", val hosts: String = "",
    val portFilter: String = "", val split: String = "", val splitRange: String = "", val disorder: String = "", val oob: String = "",
    val disoob: String = "", val fake: String = "", val ttl: String = "", val ipOpt: String = "",
    val fakeOffset: String = "", val fakeData: String = "", val tlsSni: String = "", val oobData: String = "",
    val modHttp: String = "", val tlsRecord: String = "", val udpFake: String = "", val defTtl: String = "",
    val ttlRange: String = "", val oobTtl: String = "", val delay: String = "", val delayRange: String = "", val connIp: String = "", val bufferSize: String = "", val maxConn: String = "",
    val protectPath: String = "", val noDomain: Boolean = false, val noIpv6: Boolean = false,
    val noUdp: Boolean = false, val md5sig: Boolean = false, val fakeJitter: Boolean = false, val dropSack: Boolean = false, val notWaitSend: Boolean = false
) {
    private fun add(out: MutableList<String>, name: String, value: String) { if (value.isNotBlank()) out += listOf(name, value.trim()) }
    private fun quoted(value: String): String = if (value.any(Char::isWhitespace) || '"' in value || '\\' in value) {
        "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
    } else value
    fun toCommandString(): String {
        val out = mutableListOf<String>()
        if (noDomain) out += "--no-domain"; if (noIpv6) out += "--no-ipv6"; if (noUdp) out += "--no-udp"
        add(out, "--proto", proto); add(out, "--auto", auto); add(out, "--cache-ttl", cacheTtl); add(out, "--hosts", hosts); add(out, "--pf", portFilter)
        add(out, "--split", split); add(out, "--split-range", splitRange); add(out, "--disorder", disorder); add(out, "--oob", oob); add(out, "--disoob", disoob); add(out, "--fake", fake); add(out, "--ttl", ttl); add(out, "--ttl-range", ttlRange); add(out, "--oob-ttl", oobTtl); add(out, "--ip-opt", ipOpt)
        if (fakeJitter) out += "--fake-jitter"
        if (md5sig) out += "--md5sig"; add(out, "--fake-offset", fakeOffset); add(out, "--fake-data", fakeData); add(out, "--tls-sni", tlsSni); add(out, "--oob-data", oobData); add(out, "--mod-http", modHttp); add(out, "--tlsrec", tlsRecord); add(out, "--udp-fake", udpFake); add(out, "--def-ttl", defTtl); add(out, "--delay", delay)
        if (notWaitSend) out += "--not-wait-send"; add(out, "--delay-range", delayRange); add(out, "--conn-ip", connIp); add(out, "--buf-size", bufferSize); add(out, "--max-conn", maxConn); if (dropSack) out += "--drop-sack"; add(out, "--protect-path", protectPath)
        return out.joinToString(" ", transform = ::quoted)
    }
}

private fun parseDesyncCustomFields(raw: String): DesyncCustomFields {
    val args = mutableListOf<String>()
    val token = StringBuilder()
    var quote: Char? = null
    var escaped = false
    fun flush() { if (token.isNotEmpty()) { args += token.toString(); token.clear() } }
    raw.forEach { c ->
        when {
            escaped -> { token.append(c); escaped = false }
            c == '\\' && quote != '\'' -> escaped = true
            quote != null && c == quote -> quote = null
            quote == null && (c == '\'' || c == '"') -> quote = c
            quote == null && c.isWhitespace() -> flush()
            else -> token.append(c)
        }
    }
    if (escaped) token.append('\\')
    flush()
    val values = linkedMapOf<String, String>()
    val flags = mutableSetOf<String>()
    var i = 0
    while (i < args.size) {
        val part = args[i++].removePrefix("--")
        val equal = part.indexOf('=')
        if (equal >= 0) {
            values[part.substring(0, equal)] = part.substring(equal + 1)
        } else if (part in setOf("no-domain", "no-ipv6", "no-udp", "md5sig", "fake-jitter", "drop-sack", "not-wait-send")) {
            flags += part
        } else if (i < args.size && !args[i].startsWith("--")) {
            values[part] = args[i++]
        }
    }
    return DesyncCustomFields(
        proto = values["proto"].orEmpty(), auto = values["auto"].orEmpty(), cacheTtl = values["cache-ttl"].orEmpty(), hosts = values["hosts"].orEmpty(),
        portFilter = values["pf"].orEmpty(), split = values["split"].orEmpty(), splitRange = values["split-range"].orEmpty(), disorder = values["disorder"].orEmpty(),
        oob = values["oob"].orEmpty(), disoob = values["disoob"].orEmpty(), fake = values["fake"].orEmpty(), ttl = values["ttl"].orEmpty(),
        ttlRange = values["ttl-range"].orEmpty(), oobTtl = values["oob-ttl"].orEmpty(), ipOpt = values["ip-opt"].orEmpty(),
        fakeOffset = values["fake-offset"].orEmpty(), fakeData = values["fake-data"].orEmpty(), tlsSni = values["tls-sni"].orEmpty(),
        oobData = values["oob-data"].orEmpty(), modHttp = values["mod-http"].orEmpty(), tlsRecord = values["tlsrec"].orEmpty(),
        udpFake = values["udp-fake"].orEmpty(), defTtl = values["def-ttl"].orEmpty(), delay = values["delay"].orEmpty(),
        delayRange = values["delay-range"].orEmpty(), connIp = values["conn-ip"].orEmpty(), bufferSize = values["buf-size"].orEmpty(),
        maxConn = values["max-conn"].orEmpty(), protectPath = values["protect-path"].orEmpty(), noDomain = "no-domain" in flags,
        noIpv6 = "no-ipv6" in flags, noUdp = "no-udp" in flags, md5sig = "md5sig" in flags, fakeJitter = "fake-jitter" in flags,
        dropSack = "drop-sack" in flags, notWaitSend = "not-wait-send" in flags,
    )
}

private data class DesyncPreset(val name: String, val fields: DesyncCustomFields)

private val DESYNC_CUSTOM_PRESETS = listOf(
    DesyncPreset("Split", DesyncCustomFields(proto = "tls", split = "1+s", tlsRecord = "1+s")),
    DesyncPreset("Disorder", DesyncCustomFields(proto = "tls", disorder = "1+s", tlsRecord = "1+s")),
    DesyncPreset("Fake SNI", DesyncCustomFields(proto = "tls", fake = "-1", tlsSni = "example.com", ttl = "8")),
    DesyncPreset("Out of Band", DesyncCustomFields(proto = "tls", oob = "1+s")),
    DesyncPreset("Disorder + OOB", DesyncCustomFields(proto = "tls", disorder = "1+s", oob = "1+s"))
)

private data class IndependentTheme(val accent: Color, val surface: Color)

@Composable
private fun independentTheme(key: String): IndependentTheme =
    IndependentTheme(sectionAccentForPage(key), simorghCardSurface(LocalAppIsDarkTheme.current))

@Composable
fun HomeLauncher(onOpen: (String) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        LauncherRows(launcherEntries, onOpen)
    }
}

@Composable
private fun LauncherRows(entries: List<LauncherEntry>, onOpen: (String) -> Unit) {
    entries.chunked(3).forEach { row ->
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { entry ->
                ServiceLauncherTile(entry, Modifier.weight(1f)) { onOpen(entry.key) }
            }
            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun ServiceLauncherTile(entry: LauncherEntry, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val darkTheme = LocalAppIsDarkTheme.current
    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = simorghCardSurface(darkTheme)),
        border = androidx.compose.foundation.BorderStroke(1.dp, simorghOutline(darkTheme))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier.size(62.dp).background(Brush.linearGradient(listOf(entry.color, entry.color.copy(alpha = .55f))), RoundedCornerShape(19.dp)),
                contentAlignment = Alignment.Center
            ) { androidx.compose.material3.Icon(painterResource(entry.iconRes), contentDescription = entry.title, tint = Color.Unspecified, modifier = Modifier.size(34.dp)) }
            Text(entry.title, color = Color.White.copy(alpha = .92f), fontSize = 11.sp, fontFamily = FontFamily.SansSerif, textAlign = TextAlign.Center, maxLines = 2)
        }
    }
}

@Composable
private fun IndependentConnectOrb(
    active: Boolean,
    connected: Boolean,
    color: Color,
    onClick: () -> Unit
) {
    val transition = rememberInfiniteTransition(label = "independentConnectAnimation")
    val pulse by transition.animateFloat(
        initialValue = 0.82f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse),
        label = "independentConnectPulse"
    )
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1350), RepeatMode.Restart),
        label = "independentConnectSpin"
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
                            Color.White.copy(alpha = .060f),
                            color.copy(alpha = .36f)
                        )
                    )
                )
                .border(
                    1.4.dp,
                    Brush.linearGradient(listOf(Color.White.copy(alpha = .62f), color.copy(alpha = .70f), Color.White.copy(alpha = .22f))),
                    CircleShape
                )
                .clickable { runCatching { onClick() } },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(if (active) 88.dp else 96.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = .075f))
                    .border(1.dp, Color.White.copy(alpha = .20f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                CompositionLocalProvider(LocalContentColor provides Color.White) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text(if (active) "■" else "▶", color = Color.White, fontSize = if (active) 34.sp else 40.sp, fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.SansSerif)
                        Spacer(Modifier.height(5.dp))
                        Text(
                            if (active) "Disconnect" else "Connect",
                            color = Color.White,
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = FontFamily.SansSerif,
                            fontSize = 10.sp,
                            letterSpacing = 0.sp,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CopyableLogBlock(
    logs: String,
    onClear: () -> Unit,
    emptyText: String
) {
    val clipboard = LocalClipboardManager.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            modifier = Modifier.weight(1f),
            enabled = logs.isNotBlank(),
            onClick = { clipboard.setText(AnnotatedString(logs)) },
            shape = RoundedCornerShape(999.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF1D4ED8),
                contentColor = Color.White,
                disabledContainerColor = Color(0xFF475569),
                disabledContentColor = Color.White.copy(alpha = .72f)
            ),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = .24f))
        ) { Text("COPY LOG", color = Color.White, fontFamily = FontFamily.SansSerif, fontSize = 10.sp) }
        Button(
            modifier = Modifier.weight(1f),
            onClick = onClear,
            shape = RoundedCornerShape(999.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB91C1C), contentColor = Color.White),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = .24f))
        ) { Text("CLEAR LOG", color = Color.White, fontFamily = FontFamily.SansSerif, fontSize = 10.sp) }
    }
    SelectionContainer {
        Text(
            logs.takeLast(6000).ifBlank { emptyText },
            color = Color.White.copy(alpha = .72f),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.fillMaxWidth().height(180.dp)
        )
    }
}

@Composable
fun IndependentTransportCard(page: String, onStart: (String) -> Unit, onStop: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("simorgh_ravan_state", android.content.Context.MODE_PRIVATE) }
    var state by remember { mutableStateOf(prefs.getString("state", "IDLE").orEmpty()) }
    var detail by remember { mutableStateOf(prefs.getString("detail", "Ready").orEmpty()) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var endpoint by remember { mutableStateOf(prefs.getString("ravanWarpEndpoint", "engage.cloudflareclient.com:443").orEmpty()) }
    var warpKey by remember { mutableStateOf(prefs.getString("ravanWarpKey", "").orEmpty()) }
    var logs by remember { mutableStateOf("") }
    LaunchedEffect(page) {
        while (true) {
            state = prefs.getString("state", "IDLE").orEmpty()
            detail = prefs.getString("detail", "Ready").orEmpty()
            logs = RKhVpnLogStore.readText(context)
            delay(600L)
        }
    }
    val usesRavanTunnel = page in setOf("warp", "warp_plus", "warp_in_warp", "warp_wireguard", "warp_awg")
    val showsWarpPlusOptions = page in setOf("warp", "warp_plus", "warp_in_warp")
    val title = when (page) {
        "warp", "warp_plus" -> "WARP Plus"
        "warp_wireguard" -> "WARP WireGuard"
        "warp_awg" -> "WARP AWG"
        "warp_in_warp" -> "WARP-in-WARP"
        else -> "WARP"
    }
    val description = when (page) {
        "warp", "warp_plus" -> "Cloudflare WARP Plus through SIMORGH's independent tunnel."
        "warp_wireguard" -> "A Cloudflare WARP WireGuard account registered automatically on first connect."
        "warp_awg" -> "Cloudflare WARP over the AmneziaWG Go engine with its own isolated TUN."
        "warp_in_warp" -> "Two independent WARP layers chained inside one transport page."
        else -> "A separate transport profile and backend slot."
    }
    val theme = independentTheme(page)
    val accent = theme.accent
    val active = usesRavanTunnel && (state == "RUNNING" || state == "STARTING")
    val connected = state == "RUNNING"
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(30.dp),
        colors = CardDefaults.cardColors(containerColor = theme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, simorghOutline(LocalAppIsDarkTheme.current))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .background(theme.surface)
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(title, color = accent, fontSize = 27.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.SansSerif)
            Text(description, color = Color.White.copy(alpha = .70f), fontSize = 13.sp, fontFamily = FontFamily.SansSerif, textAlign = TextAlign.Center)
            IndependentConnectOrb(
                active = active,
                connected = connected,
                color = accent,
                onClick = { if (active) onStop() else onStart(page) }
            )
            Text(detail.take(180).ifBlank { "Ready to connect." }, color = Color.White.copy(alpha = .70f), fontSize = 12.sp, fontFamily = FontFamily.SansSerif, textAlign = TextAlign.Center)
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = { showDiagnostics = !showDiagnostics },
                shape = RoundedCornerShape(999.dp),
                colors = simorghAdaptiveButtonColors(),
                border = androidx.compose.foundation.BorderStroke(1.dp, simorghOutline(LocalAppIsDarkTheme.current))
            ) { Text(if (showDiagnostics) "HIDE SETTINGS & LOGS" else "SETTINGS & LOGS", fontFamily = FontFamily.SansSerif, fontSize = 11.sp) }
            if (showDiagnostics) {
                OutlinedTextField(
                    value = endpoint,
                    onValueChange = { endpoint = it },
                    label = { Text("WARP endpoint") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (showsWarpPlusOptions) {
                    OutlinedTextField(
                        value = warpKey,
                        onValueChange = { warpKey = it },
                        label = { Text("WARP Plus license key (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Text("SIMORGH registers a WARP account automatically when connecting. Set an endpoint only if you want to override the registered endpoint.", color = Color.White.copy(alpha = .58f), fontSize = 11.sp, textAlign = TextAlign.Center)
                }
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        prefs.edit().putString("ravanWarpEndpoint", endpoint.trim()).putString("ravanWarpKey", warpKey.trim()).apply()
                        RKhVpnLogStore.append(context, "RavanUI", "WARP settings saved")
                        logs = RKhVpnLogStore.readText(context)
                    },
                    shape = RoundedCornerShape(999.dp),
                    colors = simorghAdaptiveButtonColors(),
                    border = androidx.compose.foundation.BorderStroke(1.dp, simorghOutline(LocalAppIsDarkTheme.current))
                ) { Text("SAVE SETTINGS", fontFamily = FontFamily.SansSerif, fontSize = 11.sp) }
                Text("Logs", color = Color.White.copy(alpha = .86f), fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.SansSerif)
                CopyableLogBlock(
                    logs = logs,
                    onClear = { RKhVpnLogStore.clear(context); logs = "" },
                    emptyText = "No logs yet. Tap CONNECT to collect diagnostics."
                )
            }
            Text("This page is independent from MSP, Free V2Ray, Fragment, NipoVPN and MasterDNS.", color = Color.White.copy(alpha = .54f), fontSize = 11.sp, fontFamily = FontFamily.SansSerif, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun DnsTunnelTransportCard(page: String, onStart: (String) -> Unit, onStop: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("simorgh_ravan_state", android.content.Context.MODE_PRIVATE) }
    val soar = page == "soardns"
    val title = if (soar) "SoarDNS" else "DNSTT"
    val theme = independentTheme(page)
    var state by remember { mutableStateOf(prefs.getString("state", "IDLE").orEmpty()) }
    var detail by remember { mutableStateOf(prefs.getString("detail", "Ready").orEmpty()) }
    var showSettings by remember { mutableStateOf(false) }
    var logs by remember { mutableStateOf("") }
    var zone by remember { mutableStateOf(prefs.getString("soarZone", "").orEmpty()) }
    var serverKey by remember { mutableStateOf(prefs.getString("soarServerKey", "").orEmpty()) }
    var soarResolvers by remember { mutableStateOf(prefs.getString("soarResolvers", "1.1.1.1\n8.8.8.8").orEmpty()) }
    var dnsttDomain by remember { mutableStateOf(prefs.getString("dnsttDomain", "").orEmpty()) }
    var dnsttKey by remember { mutableStateOf(prefs.getString("dnsttPublicKey", "").orEmpty()) }
    var dnsttResolver by remember { mutableStateOf(prefs.getString("dnsttResolver", "1.1.1.1").orEmpty()) }
    var dnsttTransport by remember { mutableStateOf(prefs.getString("dnsttTransport", "udp").orEmpty()) }
    var transportMenu by remember { mutableStateOf(false) }
    LaunchedEffect(page) {
        while (true) {
            state = prefs.getString("state", "IDLE").orEmpty()
            detail = prefs.getString("detail", "Ready").orEmpty()
            logs = RKhVpnLogStore.readText(context)
            delay(600L)
        }
    }
    val active = state == "RUNNING" || state == "STARTING"
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(30.dp),
        colors = CardDefaults.cardColors(containerColor = theme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, simorghOutline(LocalAppIsDarkTheme.current))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .background(theme.surface)
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(title, color = theme.accent, fontSize = 27.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.SansSerif)
            Text(
                if (soar) "DNS tunnel with resolver selection and a local SOCKS route. Add your Soar zone and server public key."
                else "DNSTT DNS tunnel over UDP, DoH or DoT. Add the server's domain and 32-byte public key.",
                color = Color.White.copy(alpha = .70f), fontSize = 13.sp, fontFamily = FontFamily.SansSerif, textAlign = TextAlign.Center
            )
            IndependentConnectOrb(active, state == "RUNNING", theme.accent) { if (active) onStop() else onStart(page) }
            Text(detail.take(180).ifBlank { "Ready to connect." }, color = Color.White.copy(alpha = .70f), fontSize = 12.sp, fontFamily = FontFamily.SansSerif, textAlign = TextAlign.Center)
            Button(
                modifier = Modifier.fillMaxWidth(), onClick = { showSettings = !showSettings }, shape = RoundedCornerShape(999.dp),
                colors = simorghAdaptiveButtonColors(),
                border = androidx.compose.foundation.BorderStroke(1.dp, simorghOutline(LocalAppIsDarkTheme.current))
            ) { Text(if (showSettings) "HIDE SETTINGS & LOGS" else "SETTINGS & LOGS", fontFamily = FontFamily.SansSerif, fontSize = 11.sp) }
            if (showSettings) {
                if (soar) {
                    OutlinedTextField(value = zone, onValueChange = { zone = it }, label = { Text("SoarDNS zone") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = serverKey, onValueChange = { serverKey = it }, label = { Text("SoarDNS server public key") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = soarResolvers, onValueChange = { soarResolvers = it }, label = { Text("DNS resolvers (one per line)") }, minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth())
                } else {
                    OutlinedTextField(value = dnsttDomain, onValueChange = { dnsttDomain = it }, label = { Text("DNSTT domain") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = dnsttKey, onValueChange = { dnsttKey = it }, label = { Text("DNSTT public key (64 hex characters)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = dnsttResolver, onValueChange = { dnsttResolver = it }, label = { Text("DNS resolver") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Box {
                        Button(
                            modifier = Modifier.fillMaxWidth(), onClick = { transportMenu = true }, shape = RoundedCornerShape(999.dp),
                            colors = simorghAdaptiveButtonColors(),
                            border = androidx.compose.foundation.BorderStroke(1.dp, simorghOutline(LocalAppIsDarkTheme.current))
                        ) { Text("DNSTT transport: ${dnsttTransport.uppercase()}", fontFamily = FontFamily.SansSerif, fontSize = 11.sp) }
                        DropdownMenu(expanded = transportMenu, onDismissRequest = { transportMenu = false }) {
                            listOf("udp", "doh", "dot").forEach { option ->
                                DropdownMenuItem(text = { Text(option.uppercase(), fontFamily = FontFamily.SansSerif) }, onClick = {
                                    dnsttTransport = option
                                    transportMenu = false
                                })
                            }
                        }
                    }
                }
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        prefs.edit()
                            .putString("soarZone", zone.trim())
                            .putString("soarServerKey", serverKey.trim())
                            .putString("soarResolvers", soarResolvers.trim())
                            .putString("dnsttDomain", dnsttDomain.trim())
                            .putString("dnsttPublicKey", dnsttKey.trim())
                            .putString("dnsttResolver", dnsttResolver.trim())
                            .putString("dnsttTransport", dnsttTransport)
                            .apply()
                        logs = RKhVpnLogStore.readText(context)
                    },
                    shape = RoundedCornerShape(999.dp), colors = simorghAdaptiveButtonColors(),
                    border = androidx.compose.foundation.BorderStroke(1.dp, simorghOutline(LocalAppIsDarkTheme.current))
                ) { Text("SAVE SETTINGS", fontFamily = FontFamily.SansSerif, fontSize = 11.sp) }
                Text("Logs", color = Color.White.copy(alpha = .86f), fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.SansSerif)
                CopyableLogBlock(logs = logs, onClear = { RKhVpnLogStore.clear(context); logs = "" }, emptyText = "No logs yet. Save server details, then tap CONNECT.")
            }
            Text("Tunnel path: Android VPN → SIMORGH tun2proxy → local ${if (soar) "SoarDNS" else "DNSTT"} SOCKS5", color = Color.White.copy(alpha = .54f), fontSize = 11.sp, fontFamily = FontFamily.SansSerif, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun DesyncTransportCard(onStart: (String, String, String) -> Unit, onStop: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("desync_preferences", android.content.Context.MODE_PRIVATE) }
    val statePrefs = remember(context) {
        context.getSharedPreferences(
            "simorgh_desync_state",
            android.content.Context.MODE_PRIVATE or android.content.Context.MODE_MULTI_PROCESS
        )
    }
    var rawConfig by remember { mutableStateOf(prefs.getString("raw_config", "").orEmpty()) }
    var profile by remember { mutableStateOf(prefs.getString("profile", "Balanced").orEmpty()) }
    val savedCustomArgs = prefs.getString("custom_args", "").orEmpty()
    val matchedPreset = DESYNC_CUSTOM_PRESETS.firstOrNull { it.fields.toCommandString() == savedCustomArgs }
    var customFields by remember { mutableStateOf(matchedPreset?.fields ?: if (savedCustomArgs.isNotBlank()) parseDesyncCustomFields(savedCustomArgs) else DESYNC_CUSTOM_PRESETS.first().fields) }
    var selectedCustomPreset by remember {
        mutableStateOf(matchedPreset?.name ?: if (savedCustomArgs.isBlank()) DESYNC_CUSTOM_PRESETS.first().name else "Custom")
    }
    var status by remember { mutableStateOf(statePrefs.getString("status", "DISCONNECTED").orEmpty()) }
    var detail by remember { mutableStateOf(statePrefs.getString("detail", "Ready").orEmpty()) }
    var logs by remember { mutableStateOf("") }
    var showDiagnostics by remember { mutableStateOf(true) }
    val profiles = listOf("Off", "Light", "Balanced", "Severe", "Adaptive", "Custom")
    LaunchedEffect(Unit) {
        while (true) {
            status = statePrefs.getString("status", "DISCONNECTED").orEmpty()
            detail = statePrefs.getString("detail", "Ready").orEmpty()
            logs = RKhVpnLogStore.readText(context)
            delay(600L)
        }
    }
    val active = status in setOf("CONNECTING", "CONNECTED", "DISCONNECTING")
    val connected = status == "CONNECTED"
    val theme = independentTheme("desync")
    val accent = theme.accent
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(30.dp),
        colors = CardDefaults.cardColors(containerColor = theme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, simorghOutline(LocalAppIsDarkTheme.current))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .background(theme.surface)
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Desync", color = accent, fontSize = 27.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.SansSerif)
            Text("PingNG native Desync applied to your selected Xray config.", color = Color.White.copy(alpha = .70f), fontSize = 13.sp, fontFamily = FontFamily.SansSerif, textAlign = TextAlign.Center)
            IndependentConnectOrb(
                active = active,
                connected = connected,
                color = accent,
                onClick = {
                    if (active) onStop()
                    else if (rawConfig.isNotBlank() && profile != "Off") onStart(rawConfig.trim(), profile, customFields.toCommandString())
                    else {
                        detail = when {
                            rawConfig.isBlank() -> "Paste a supported Xray config before connecting."
                            else -> "Select a Desync profile before connecting."
                        }
                        statePrefs.edit().putString("detail", detail).apply()
                        RKhVpnLogStore.append(context, "DesyncUI", "Connect blocked • ${detail}")
                    }
                }
            )
            Text(detail.take(180).ifBlank { "Add a VMess, VLESS, Trojan, SS or full Xray config." }, color = Color.White.copy(alpha = .70f), fontSize = 12.sp, fontFamily = FontFamily.SansSerif, textAlign = TextAlign.Center)
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = { showDiagnostics = !showDiagnostics },
                shape = RoundedCornerShape(999.dp),
                colors = simorghAdaptiveButtonColors(),
                border = androidx.compose.foundation.BorderStroke(1.dp, simorghOutline(LocalAppIsDarkTheme.current))
            ) { Text(if (showDiagnostics) "HIDE SETTINGS & LOGS" else "SETTINGS & LOGS", fontFamily = FontFamily.SansSerif, fontSize = 11.sp) }
            if (showDiagnostics) {
                Text("Desync profile", color = Color.White.copy(alpha = .86f), fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.SansSerif)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    profiles.take(3).forEach { item -> FilterChip(selected = profile == item, onClick = { profile = item }, label = { Text(item, color = Color.White, fontSize = 10.sp) }) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    profiles.drop(3).forEach { item -> FilterChip(selected = profile == item, onClick = { profile = item }, label = { Text(item, color = Color.White, fontSize = 10.sp) }) }
                }
                OutlinedTextField(value = rawConfig, onValueChange = { rawConfig = it }, label = { Text("Config (URI or full Xray JSON)") }, modifier = Modifier.fillMaxWidth(), minLines = 5, maxLines = 9)
                if (profile == "Custom") {
                    Text("Choose a preset, then edit each PingNG argument separately", color = Color.White.copy(alpha = .86f), fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.SansSerif)
                    DESYNC_CUSTOM_PRESETS.chunked(2).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { preset ->
                                FilterChip(
                                    modifier = Modifier.weight(1f),
                                    selected = selectedCustomPreset == preset.name,
                                    onClick = {
                                        selectedCustomPreset = preset.name
                                        customFields = preset.fields
                                    },
                                    label = { Text(preset.name, color = Color.White, fontSize = 10.sp, maxLines = 1) }
                                )
                            }
                            if (row.size < 2) Spacer(Modifier.weight(1f))
                        }
                    }
                    Text("Argument fields", color = accent, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.SansSerif)
                    Text("Leave a field empty to omit that command. Values go directly to the embedded PingNG engine.", color = Color.White.copy(alpha = .62f), fontSize = 11.sp, fontFamily = FontFamily.SansSerif, textAlign = TextAlign.Center)
                    DesyncArgumentField("--proto", "tls, http, udp", customFields.proto, accent) { customFields = customFields.copy(proto = it); selectedCustomPreset = "Custom" }
                    DesyncArgumentField("--auto", "torst,redirect,ssl_err,none", customFields.auto, accent) { customFields = customFields.copy(auto = it); selectedCustomPreset = "Custom" }
                    DesyncArgumentField("--cache-ttl", "seconds", customFields.cacheTtl, accent) { customFields = customFields.copy(cacheTtl = it); selectedCustomPreset = "Custom" }
                    DesyncArgumentField("--hosts", "file path or :host", customFields.hosts, accent) { customFields = customFields.copy(hosts = it); selectedCustomPreset = "Custom" }
                    DesyncArgumentField("--pf", "port or port-port", customFields.portFilter, accent) { customFields = customFields.copy(portFilter = it); selectedCustomPreset = "Custom" }
                    DesyncArgumentField("--split", "n, n+s, n+h or n+e", customFields.split, accent) { customFields = customFields.copy(split = it); selectedCustomPreset = "Custom" }
                    DesyncArgumentField("--split-range", "a-b, optionally +s, +h or +e", customFields.splitRange, accent) { customFields = customFields.copy(splitRange = it); selectedCustomPreset = "Custom" }
                    DesyncArgumentField("--disorder", "n, n+s, n+h or n+e", customFields.disorder, accent) { customFields = customFields.copy(disorder = it); selectedCustomPreset = "Custom" }
                    DesyncArgumentField("--oob", "n, n+s, n+h or n+e", customFields.oob, accent) { customFields = customFields.copy(oob = it); selectedCustomPreset = "Custom" }
                    DesyncArgumentField("--disoob", "n, n+s, n+h or n+e", customFields.disoob, accent) { customFields = customFields.copy(disoob = it); selectedCustomPreset = "Custom" }
                    DesyncArgumentField("--fake", "n, n+s, n+h or n+e", customFields.fake, accent) { customFields = customFields.copy(fake = it); selectedCustomPreset = "Custom" }
                    DesyncArgumentField("--ttl", "1-255", customFields.ttl, accent) { customFields = customFields.copy(ttl = it); selectedCustomPreset = "Custom" }
                    DesyncArgumentField("--ttl-range", "minimum-maximum (1-255)", customFields.ttlRange, accent) { customFields = customFields.copy(ttlRange = it); selectedCustomPreset = "Custom" }
                    DesyncArgumentField("--oob-ttl", "OOB packet TTL", customFields.oobTtl, accent) { customFields = customFields.copy(oobTtl = it); selectedCustomPreset = "Custom" }
                    DesyncArgumentField("--ip-opt", "file or :bytes", customFields.ipOpt, accent) { customFields = customFields.copy(ipOpt = it); selectedCustomPreset = "Custom" }
                    DesyncArgumentField("--fake-offset", "bytes", customFields.fakeOffset, accent) { customFields = customFields.copy(fakeOffset = it); selectedCustomPreset = "Custom" }
                    DesyncArgumentField("--fake-data", "file or :data", customFields.fakeData, accent) { customFields = customFields.copy(fakeData = it); selectedCustomPreset = "Custom" }
                    DesyncArgumentField("--tls-sni", "fake SNI hostname", customFields.tlsSni, accent) { customFields = customFields.copy(tlsSni = it); selectedCustomPreset = "Custom" }
                    DesyncArgumentField("--oob-data", "one escaped byte", customFields.oobData, accent) { customFields = customFields.copy(oobData = it); selectedCustomPreset = "Custom" }
                    DesyncArgumentField("--mod-http", "hcsmix, dcsmix, rmspace", customFields.modHttp, accent) { customFields = customFields.copy(modHttp = it); selectedCustomPreset = "Custom" }
                    DesyncArgumentField("--tlsrec", "n, n+s, n+h or n+e", customFields.tlsRecord, accent) { customFields = customFields.copy(tlsRecord = it); selectedCustomPreset = "Custom" }
                    DesyncArgumentField("--udp-fake", "count", customFields.udpFake, accent) { customFields = customFields.copy(udpFake = it); selectedCustomPreset = "Custom" }
                    DesyncArgumentField("--def-ttl", "outgoing TTL", customFields.defTtl, accent) { customFields = customFields.copy(defTtl = it); selectedCustomPreset = "Custom" }
                    DesyncArgumentField("--delay", "milliseconds", customFields.delay, accent) { customFields = customFields.copy(delay = it); selectedCustomPreset = "Custom" }
                    DesyncArgumentField("--delay-range", "minimum-maximum milliseconds", customFields.delayRange, accent) { customFields = customFields.copy(delayRange = it); selectedCustomPreset = "Custom" }
                    DesyncArgumentField("--conn-ip", "bind address", customFields.connIp, accent) { customFields = customFields.copy(connIp = it); selectedCustomPreset = "Custom" }
                    DesyncArgumentField("--buf-size", "bytes", customFields.bufferSize, accent) { customFields = customFields.copy(bufferSize = it); selectedCustomPreset = "Custom" }
                    DesyncArgumentField("--max-conn", "connection limit", customFields.maxConn, accent) { customFields = customFields.copy(maxConn = it); selectedCustomPreset = "Custom" }
                    DesyncArgumentField("--protect-path", "path", customFields.protectPath, accent) { customFields = customFields.copy(protectPath = it); selectedCustomPreset = "Custom" }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = customFields.noDomain, onClick = { customFields = customFields.copy(noDomain = !customFields.noDomain); selectedCustomPreset = "Custom" }, label = { Text("--no-domain", color = Color.White, fontSize = 10.sp) })
                        FilterChip(selected = customFields.noIpv6, onClick = { customFields = customFields.copy(noIpv6 = !customFields.noIpv6); selectedCustomPreset = "Custom" }, label = { Text("--no-ipv6", color = Color.White, fontSize = 10.sp) })
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = customFields.noUdp, onClick = { customFields = customFields.copy(noUdp = !customFields.noUdp); selectedCustomPreset = "Custom" }, label = { Text("--no-udp", color = Color.White, fontSize = 10.sp) })
                        FilterChip(selected = customFields.md5sig, onClick = { customFields = customFields.copy(md5sig = !customFields.md5sig); selectedCustomPreset = "Custom" }, label = { Text("--md5sig", color = Color.White, fontSize = 10.sp) })
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = customFields.dropSack, onClick = { customFields = customFields.copy(dropSack = !customFields.dropSack); selectedCustomPreset = "Custom" }, label = { Text("--drop-sack", color = Color.White, fontSize = 10.sp) })
                        FilterChip(selected = customFields.notWaitSend, onClick = { customFields = customFields.copy(notWaitSend = !customFields.notWaitSend); selectedCustomPreset = "Custom" }, label = { Text("--not-wait-send", color = Color.White, fontSize = 10.sp) })
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = customFields.fakeJitter, onClick = { customFields = customFields.copy(fakeJitter = !customFields.fakeJitter); selectedCustomPreset = "Custom" }, label = { Text("--fake-jitter", color = Color.White, fontSize = 10.sp) })
                    }
                }
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val generatedArgs = customFields.toCommandString()
                        prefs.edit().putString("raw_config", rawConfig.trim()).putString("profile", profile).putString("custom_args", generatedArgs).apply()
                        RKhVpnLogStore.append(context, "DesyncUI", "Desync config/settings saved • profile=$profile • configChars=${rawConfig.length}")
                    },
                    shape = RoundedCornerShape(999.dp),
                    colors = simorghAdaptiveButtonColors(),
                    border = androidx.compose.foundation.BorderStroke(1.dp, simorghOutline(LocalAppIsDarkTheme.current))
                ) { Text("SAVE CONFIG & SETTINGS", fontFamily = FontFamily.SansSerif, fontSize = 11.sp) }
                Text("PingNG local SOCKS is attached between Xray and the remote server. Supported: VMess, VLESS, Trojan, Shadowsocks and full Xray JSON.", color = Color.White.copy(alpha = .62f), fontSize = 11.sp, fontFamily = FontFamily.SansSerif, textAlign = TextAlign.Center)
                Text("Logs", color = Color.White.copy(alpha = .86f), fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.SansSerif)
                CopyableLogBlock(
                    logs = logs,
                    onClear = { RKhVpnLogStore.clear(context); logs = "" },
                    emptyText = "No logs yet. Save a config and tap CONNECT."
                )
            }
        }
    }
}
