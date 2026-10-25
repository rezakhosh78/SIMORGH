@file:Suppress("DEPRECATION")

package com.rkh.vpn.ui

import android.content.Intent
import android.graphics.ImageDecoder
import android.os.Build
import android.widget.ImageView
import android.graphics.drawable.AnimatedImageDrawable
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
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
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text as MaterialText
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.rkh.vpn.R
import com.rkh.vpn.SimorghInstallResult
import com.rkh.vpn.SimorghUpdate
import com.rkh.vpn.SimorghUpdateManager
import com.rkh.vpn.data.FormatUtils
import com.rkh.vpn.data.RKhVpnLogStore
import com.rkh.vpn.data.SimorghPublicState
import com.rkh.vpn.data.SimorghRealDelayTargets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val UiRegularFontFamily = FontFamily.SansSerif
private val UiBoldFontFamily = FontFamily.SansSerif

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
    overflow: TextOverflow = TextOverflow.Clip,
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

@Composable
fun SimorghPublicHome(
    state: SimorghPublicState,
    onConnect: () -> Unit,
    onStartProxy: () -> Unit,
    onStop: () -> Unit,
    onModeSelected: (String) -> Unit,
    onIspSelected: (String) -> Unit,
    onToggleSni: (String) -> Unit,
    onManualIpModeChanged: (Boolean) -> Unit,
    onManualIpsChanged: (String) -> Unit,
    onIspManualRangeModeChanged: (Boolean) -> Unit,
    onIspManualRangeChanged: (String) -> Unit,
    onMaxScanIpsChanged: (Int) -> Unit,
    onScanSpeedChanged: (String) -> Unit,
    onProxyProtocolChanged: (String) -> Unit,
    onRouteStrategyChanged: (String) -> Unit,
    onClearSavedCleanIps: () -> Unit,
    onPingSavedCleanIps: () -> Unit,
    onNextRouteIp: () -> Unit,
    onCfEnabledChange: (Boolean) -> Unit,
    onCfVlessChanged: (String) -> Unit,
    onCfRealDelayUrlChanged: (String) -> Unit,
    onCfPingIp: (String) -> Unit,
    onCfPingAll: () -> Unit,
    onCfConnectIp: (String) -> Unit,
    onSimpleConnect: () -> Unit,
    onSimpleUpdate: () -> Unit,
    onSimpleNextHealthy: () -> Unit,
    onSimpleClearCache: () -> Unit,
    onSimplePingAll: () -> Unit,
    onSimpleConnectConfig: (Int) -> Unit,
    onSimpleSelectConfig: (Int) -> Unit,
    onSimpleServerlessChanged: (Boolean) -> Unit,
    onSimpleCustomAddProfile: () -> Unit,
    onSimpleCustomSelectProfile: (String) -> Unit,
    onSimpleCustomDeleteProfile: (String) -> Unit,
    onSimpleCustomRemarkChanged: (String) -> Unit,
    onSimpleCustomInputChanged: (String) -> Unit,
    onSimpleCustomUpdate: () -> Unit,
    onSimpleCustomUpdateProfile: (String) -> Unit,
    onSimpleCustomPingConfig: (Int) -> Unit,
    onSimpleCustomConnectConfig: (Int) -> Unit,
    onFragmentConnect: () -> Unit,
    onFragmentConfigChanged: (String) -> Unit,
    onFragmentAddressChanged: (String) -> Unit,
    onFragmentPacketsChanged: (String) -> Unit,
    onFragmentLengthsChanged: (String) -> Unit,
    onFragmentDelaysChanged: (String) -> Unit,
    onFragmentMaxSplitChanged: (String) -> Unit,
    onFragmentPing: () -> Unit,
    onFragmentAddProfile: (String) -> Unit,
    onFragmentSelectProfile: (String) -> Unit,
    onFragmentPingProfile: (String) -> Unit,
    onFragmentRenameProfile: (String, String) -> Unit,
    onFragmentDeleteProfile: (String) -> Unit,
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
    onStormDnsConnect: () -> Unit,
    onStormDnsFieldChanged: (String, String) -> Unit,
    onStormDnsModeChanged: (String) -> Unit,
    onStormDnsImportResolvers: () -> Unit,
    onStormDnsSaveProfile: () -> Unit,
    onStormDnsAddProfile: () -> Unit,
    onStormDnsSelectProfile: (String) -> Unit,
    onStormDnsDeleteProfile: () -> Unit,
    onStormDnsSaveResolverProfile: () -> Unit,
    onStormDnsAddResolverProfile: () -> Unit,
    onStormDnsSelectResolverProfile: (String) -> Unit,
    onStormDnsDeleteResolverProfile: () -> Unit,
    onStormDnsRefreshLogs: () -> Unit,
    onStormDnsClearLogs: () -> Unit,
    onTunnelAppModeChanged: (String, String) -> Unit,
    onTunnelAppToggled: (String, String, String) -> Unit,
    onTunnelAppsCleared: (String, String) -> Unit,
    onRavanStart: (String) -> Unit = {},
    onRavanStop: () -> Unit = {},
    onDesyncStart: (String, String, String) -> Unit = { _, _, _ -> },
    onDesyncStop: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showAppSettings by remember { mutableStateOf(false) }
    var showMspSettings by remember { mutableStateOf(false) }
    var showTunnelApps by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val deviceAppVersion = remember(context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }.getOrDefault("")
    }
    var updateDialogVisible by remember { mutableStateOf(false) }
    var isCheckingUpdates by remember { mutableStateOf(false) }
    var isDownloadingUpdate by remember { mutableStateOf(false) }
    var updateMessage by remember { mutableStateOf("") }
    var installedAppVersion by remember { mutableStateOf("") }
    var latestAppVersion by remember { mutableStateOf("") }
    var availableUpdate by remember { mutableStateOf<SimorghUpdate?>(null) }
    var downloadedUpdateApk by remember { mutableStateOf<File?>(null) }
    DisposableEffect(lifecycleOwner, context) {
        val updateCheckObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                coroutineScope.launch {
                    runCatching { SimorghUpdateManager.checkForUpdate(context) }
                        .onSuccess { check ->
                            installedAppVersion = check.installedVersion
                            latestAppVersion = check.latestVersion
                            if (check.update != null) {
                                availableUpdate = check.update
                                updateDialogVisible = true
                            }
                        }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(updateCheckObserver)
        onDispose { lifecycleOwner.lifecycle.removeObserver(updateCheckObserver) }
    }
    fun checkForUpdatesFromSettings() {
        showAppSettings = false
        updateDialogVisible = true
        isCheckingUpdates = true
        updateMessage = "Checking GitHub releases…"
        availableUpdate = null
        latestAppVersion = ""
        downloadedUpdateApk = null
        coroutineScope.launch {
            runCatching { SimorghUpdateManager.checkForUpdate(context) }
                .onSuccess { check ->
                    installedAppVersion = check.installedVersion
                    latestAppVersion = check.latestVersion
                    availableUpdate = check.update
                    downloadedUpdateApk = null
                    updateMessage = if (check.update == null) "You're using the latest version (${check.latestVersion})." else ""
                }
                .onFailure { error -> updateMessage = "Could not check for updates: ${error.message ?: "network error"}" }
            isCheckingUpdates = false
        }
    }
    fun downloadAndInstallUpdate() {
        val update = availableUpdate ?: return
        coroutineScope.launch {
            isDownloadingUpdate = true
            updateMessage = "Downloading version ${update.version}…"
            runCatching { SimorghUpdateManager.downloadApk(context, update) }
                .onSuccess { apk ->
                    downloadedUpdateApk = apk
                    if (SimorghUpdateManager.canInstallPackages(context)) {
                        runCatching { SimorghUpdateManager.installOrRequestPermission(context, apk) }
                            .onSuccess { updateMessage = "Android's installer opened. Confirm the update there to finish installation." }
                            .onFailure { error -> updateMessage = "Downloaded, but the installer could not open: ${error.message ?: "unknown error"}" }
                    } else {
                        updateMessage = "Downloaded. Allow SIMORGH to install unknown apps in Android Settings, then tap Install update."
                    }
                }
                .onFailure { error -> updateMessage = "Download failed: ${error.message ?: "network error"}" }
            isDownloadingUpdate = false
        }
    }
    val uiPrefs = remember(context) { context.applicationContext.getSharedPreferences("simorgh_public_state", android.content.Context.MODE_PRIVATE or android.content.Context.MODE_MULTI_PROCESS) }
    val validMainPages = remember { setOf("home", "advance", "simple", "serverless", "fragment", "nipo", "stormdns", "desync") }
    val pageHistory = remember { mutableStateListOf<String>() }
    val storedPage = uiPrefs.getString("lastMainPage", "home").orEmpty()
    var mainPage by remember {
        mutableStateOf(
            when {
                storedPage in setOf("advance", "simple", "fragment", "nipo", "stormdns", "ravan") -> "home"
                storedPage in validMainPages -> storedPage
                else -> "home"
            }
        )
    }
    val setMainPageAndRemember: (String) -> Unit = { page ->
        if (page in validMainPages && page != mainPage) {
            pageHistory.add(mainPage)
            mainPage = page
            uiPrefs.edit().putString("lastMainPage", page).apply()
        }
    }
    val goBack: () -> Unit = {
        when {
            updateDialogVisible -> updateDialogVisible = false
            showAppSettings -> showAppSettings = false
            showMspSettings -> showMspSettings = false
            showTunnelApps -> showTunnelApps = false
            pageHistory.isNotEmpty() -> {
                mainPage = pageHistory.removeAt(pageHistory.lastIndex)
                uiPrefs.edit().putString("lastMainPage", mainPage).apply()
            }
            mainPage != "home" -> {
                mainPage = "home"
                uiPrefs.edit().putString("lastMainPage", "home").apply()
            }
            else -> Unit
        }
    }
    BackHandler(enabled = true, onBack = goBack)
    LaunchedEffect(mainPage) {
        when (mainPage) {
            "simple" -> onSimpleServerlessChanged(false)
            "serverless" -> onSimpleServerlessChanged(true)
        }
    }
    val tunnelSection = when (mainPage) {
        "advance" -> "msp"
        "fragment" -> "fragment"
        "nipo" -> "nipo"
        "stormdns" -> "stormdns"
        "desync" -> "desync"
        "home" -> "home"
        else -> "simple"
    }
    val tunnelSectionTitle = when (tunnelSection) {
        "msp" -> "MSP"
        "fragment" -> "Fragment"
        "nipo" -> "NipoVPN"
        "stormdns" -> "MasterDNS"
        "desync" -> "Desync"
        "home" -> "SIMORGH"
        else -> "Free V2Ray"
    }
    val sectionAccent = sectionAccentForPage(mainPage)
    Box(modifier = modifier.fillMaxSize()) {
        AnimatedSimorghBackground(darkTheme = true, modifier = Modifier.fillMaxSize())
        CompositionLocalProvider(
            LocalSectionAccent provides sectionAccent,
            LocalAppIsDarkTheme provides true,
            LocalContentColor provides simorghPrimaryText(true)
        ) {
            Column(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                // Fixed header: never scrolls with the content.
                PublicHeader(
                    state = state,
                    currentPage = mainPage,
                    showSettingsButton = mainPage == "home",
                    onOpenSettings = { showAppSettings = true },
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 8.dp)
                )
                val contentListState = rememberLazyListState()
                LazyColumn(
                    state = contentListState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 34.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    when (mainPage) {
                        "home" -> {
                            item { HomeLauncher(onOpen = setMainPageAndRemember) }
                        }
                        "serverless" -> {
                            item {
                                SimpleXrayCard(
                                    title = "ServerLess",
                                    state = state,
                                    onConnect = onSimpleConnect,
                                    onUpdate = onSimpleUpdate,
                                    onNextHealthy = onSimpleNextHealthy,
                                    onClearCache = onSimpleClearCache,
                                    onPingAll = onSimplePingAll,
                                    onConnectConfig = onSimpleConnectConfig,
                                    onSelectConfig = onSimpleSelectConfig,
                                    onCustomAddProfile = onSimpleCustomAddProfile,
                                    onCustomSelectProfile = onSimpleCustomSelectProfile,
                                    onCustomDeleteProfile = onSimpleCustomDeleteProfile,
                                    onCustomRemarkChanged = onSimpleCustomRemarkChanged,
                                    onCustomInputChanged = onSimpleCustomInputChanged,
                                    onCustomUpdate = onSimpleCustomUpdate,
                                    onCustomUpdateProfile = onSimpleCustomUpdateProfile,
                                    onCustomPingConfig = onSimpleCustomPingConfig,
                                    onCustomConnectConfig = onSimpleCustomConnectConfig
                                )
                            }
                            item { TelegramFooter() }
                        }
                        "advance" -> {
                            item { MspSettingsShortcut(onClick = { showMspSettings = true }) }
                            item { PublicConnectCard(state = state, onConnect = onConnect, onStop = onStop, onModeSelected = onModeSelected) }
                            item { PublicStatusCard(state = state, onNextRouteIp = onNextRouteIp, onClearSavedCleanIps = onClearSavedCleanIps) }
                            item { CfConfigCard(state = state, onCfEnabledChange = onCfEnabledChange, onCfVlessChanged = onCfVlessChanged, onCfRealDelayUrlChanged = onCfRealDelayUrlChanged, onCfPingIp = onCfPingIp, onCfPingAll = onCfPingAll, onCfConnectIp = onCfConnectIp) }
                            item { TelegramFooter() }
                        }
                        "fragment" -> {
                            item {
                                FragmentXrayCard(
                                    state = state,
                                    onConnect = onFragmentConnect,
                                    onConfigChanged = onFragmentConfigChanged,
                                    onAddressChanged = onFragmentAddressChanged,
                                    onPacketsChanged = onFragmentPacketsChanged,
                                    onLengthsChanged = onFragmentLengthsChanged,
                                    onDelaysChanged = onFragmentDelaysChanged,
                                    onMaxSplitChanged = onFragmentMaxSplitChanged,
                                    onPing = onFragmentPing,
                                    onAddProfile = onFragmentAddProfile,
                                    onSelectProfile = onFragmentSelectProfile,
                                    onPingProfile = onFragmentPingProfile,
                                    onRenameProfile = onFragmentRenameProfile,
                                    onDeleteProfile = onFragmentDeleteProfile
                                )
                            }
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
                            item { TelegramFooter() }
                        }
                        "stormdns" -> {
                            item { StormDnsCard(
                                state = state,
                                collapseScrollOffset = contentListState.firstVisibleItemIndex * 100_000 + contentListState.firstVisibleItemScrollOffset,
                                onConnect = onStormDnsConnect,
                                onFieldChanged = onStormDnsFieldChanged,
                                onModeChanged = onStormDnsModeChanged,
                                onImportResolvers = onStormDnsImportResolvers,
                                onSaveProfile = onStormDnsSaveProfile,
                                onAddProfile = onStormDnsAddProfile,
                                onSelectProfile = onStormDnsSelectProfile,
                                onDeleteProfile = onStormDnsDeleteProfile,
                                onSaveResolverProfile = onStormDnsSaveResolverProfile,
                                onAddResolverProfile = onStormDnsAddResolverProfile,
                                onSelectResolverProfile = onStormDnsSelectResolverProfile,
                                onDeleteResolverProfile = onStormDnsDeleteResolverProfile,
                                onRefreshLogs = onStormDnsRefreshLogs,
                                onClearLogs = onStormDnsClearLogs
                            ) }
                            item { TelegramFooter() }
                        }
                        "desync" -> {
                            item { DesyncTransportCard(onStart = onDesyncStart, onStop = onDesyncStop) }
                            item { TelegramFooter() }
                        }
                        else -> {
                            item {
                                SimpleXrayCard(
                                    state = state,
                                    onConnect = onSimpleConnect,
                                    onUpdate = onSimpleUpdate,
                                    onNextHealthy = onSimpleNextHealthy,
                                    onClearCache = onSimpleClearCache,
                                    onPingAll = onSimplePingAll,
                                    onConnectConfig = onSimpleConnectConfig,
                                    onSelectConfig = onSimpleSelectConfig,
                                    onCustomAddProfile = onSimpleCustomAddProfile,
                                    onCustomSelectProfile = onSimpleCustomSelectProfile,
                                    onCustomDeleteProfile = onSimpleCustomDeleteProfile,
                                    onCustomRemarkChanged = onSimpleCustomRemarkChanged,
                                    onCustomInputChanged = onSimpleCustomInputChanged,
                                    onCustomUpdate = onSimpleCustomUpdate,
                                    onCustomUpdateProfile = onSimpleCustomUpdateProfile,
                                    onCustomPingConfig = onSimpleCustomPingConfig,
                                    onCustomConnectConfig = onSimpleCustomConnectConfig
                                )
                            }
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
                                text = "Powered by ReZa Kh",
                                color = Color.White.copy(alpha = 0.76f),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Normal,
                                fontFamily = UiRegularFontFamily,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = when {
                                    deviceAppVersion.isBlank() -> "SIMORGH"
                                    deviceAppVersion.startsWith("v", ignoreCase = true) -> deviceAppVersion
                                    else -> "v$deviceAppVersion"
                                },
                                color = Color.White.copy(alpha = 0.64f),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Normal,
                                fontFamily = UiRegularFontFamily,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }
    if (showMspSettings) {
        CompositionLocalProvider(LocalSectionAccent provides sectionAccent) {
            PublicSettingsDialog(
                state = state,
                onDismiss = { showMspSettings = false },
                onIspSelected = onIspSelected,
                onToggleSni = onToggleSni,
                onManualIpModeChanged = onManualIpModeChanged,
                onManualIpsChanged = onManualIpsChanged,
                onIspManualRangeModeChanged = onIspManualRangeModeChanged,
                onIspManualRangeChanged = onIspManualRangeChanged,
                onMaxScanIpsChanged = onMaxScanIpsChanged,
                onScanSpeedChanged = onScanSpeedChanged,
                onProxyProtocolChanged = onProxyProtocolChanged,
                onRouteStrategyChanged = onRouteStrategyChanged
            )
        }
    }
    if (showAppSettings) {
        CompositionLocalProvider(LocalSectionAccent provides sectionAccentForPage("home")) {
            AppSettingsDialog(
                onDismiss = { showAppSettings = false },
                onOpenTunnelApps = { showAppSettings = false; showTunnelApps = true },
                onCheckForUpdates = { checkForUpdatesFromSettings() }
            )
        }
    }
    if (showTunnelApps) {
        CompositionLocalProvider(LocalSectionAccent provides sectionAccent) {
            TunnelAppsDialog(
                state = state,
                sectionKey = tunnelSection,
                sectionTitle = tunnelSectionTitle,
                onDismiss = { showTunnelApps = false },
                onModeChanged = onTunnelAppModeChanged,
                onTogglePackage = onTunnelAppToggled,
                onClear = onTunnelAppsCleared
            )
        }
    }
    if (updateDialogVisible) {
        CompositionLocalProvider(LocalSectionAccent provides sectionAccentForPage("home")) {
            AppUpdateDialog(
                installedVersion = installedAppVersion,
                latestVersion = latestAppVersion,
                update = availableUpdate,
                checking = isCheckingUpdates,
                downloading = isDownloadingUpdate,
                downloadedApk = downloadedUpdateApk,
                message = updateMessage,
                onDismiss = { updateDialogVisible = false },
                onDownload = { downloadAndInstallUpdate() },
                onInstall = {
                    val apk = downloadedUpdateApk
                    if (apk != null) {
                        runCatching { SimorghUpdateManager.installOrRequestPermission(context, apk) }
                            .onSuccess { result ->
                                updateMessage = if (result == SimorghInstallResult.PERMISSION_REQUIRED) {
                                    "Allow SIMORGH to install unknown apps in Android Settings, return here, then tap Install update."
                                } else "Android's installer opened. Confirm the update there to finish installation."
                            }
                            .onFailure { error -> updateMessage = "Could not open installer: ${error.message ?: "unknown error"}" }
                    }
                },
                onOpenRelease = {
                    val url = availableUpdate?.releaseUrl ?: "https://github.com/rezakhosh78/SIMORGH/releases"
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                },
                onRetry = { checkForUpdatesFromSettings() }
            )
        }
    }
}

@Composable
private fun HomeModeSwitch(selected: String, onSelected: (String) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(999.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(simorghInsetSurface(LocalAppIsDarkTheme.current))
            .border(1.dp, simorghOutline(LocalAppIsDarkTheme.current), shape)
            .horizontalScroll(rememberScrollState())
            .padding(5.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        ModePill("MSP", selected == "advance", Modifier.width(74.dp)) { onSelected("advance") }
        ModePill("Free V2Ray", selected == "simple", Modifier.width(112.dp)) { onSelected("simple") }
        ModePill("Fragment", selected == "fragment", Modifier.width(98.dp)) { onSelected("fragment") }
        ModePill("NipoVPN", selected == "nipo", Modifier.width(96.dp)) { onSelected("nipo") }
        ModePill("MasterDNS", selected == "stormdns", Modifier.width(104.dp)) { onSelected("stormdns") }
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
            .background(if (active) simorghSelectedSurface(LocalAppIsDarkTheme.current) else simorghControlSurface(LocalAppIsDarkTheme.current))
            .border(1.dp, if (active) LocalSectionAccent.current.copy(alpha = .56f) else simorghOutline(LocalAppIsDarkTheme.current), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = Color.White.copy(alpha = if (active) .96f else .70f), fontSize = 11.sp, fontWeight = if (active) FontWeight.ExtraBold else FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun FragmentXrayCard(
    state: SimorghPublicState,
    onConnect: () -> Unit,
    onConfigChanged: (String) -> Unit,
    onAddressChanged: (String) -> Unit,
    onPacketsChanged: (String) -> Unit,
    onLengthsChanged: (String) -> Unit,
    onDelaysChanged: (String) -> Unit,
    onMaxSplitChanged: (String) -> Unit,
    onPing: () -> Unit,
    onAddProfile: (String) -> Unit,
    onSelectProfile: (String) -> Unit,
    onPingProfile: (String) -> Unit,
    onRenameProfile: (String, String) -> Unit,
    onDeleteProfile: (String) -> Unit
) {
    val active = state.fragmentConnected || state.fragmentConnecting
    var showFragmentProfiles by remember { mutableStateOf(false) }
    val accent = LocalSectionAccent.current
    GlassCard {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                LiquidBubbleText("Fragment", big = true)
                Text(
                    "VPN MODE",
                    color = Color.White.copy(alpha = .84f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold, fontFamily = UiBoldFontFamily,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(simorghInsetSurface(LocalAppIsDarkTheme.current))
                        .border(1.dp, simorghOutline(LocalAppIsDarkTheme.current), RoundedCornerShape(999.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
            Spacer(Modifier.height(6.dp))
            LiquidBubbleText("Cloudflare Dirty IP Recovery", big = false, fontFamily = UiRegularFontFamily, fontWeight = FontWeight.Normal)
        }
        Spacer(Modifier.height(10.dp))
        SpeedMiniBubble(state)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            InfoText("Ping", if (state.fragmentPingMs >= 0L) "${state.fragmentPingMs}ms" else "—", Modifier.weight(1f))
            InfoText("Address", state.fragmentEffectiveAddress.ifBlank { "—" }, Modifier.weight(1f))
        }
        FragmentConnectOrb(state = state, color = accent, onConnect = onConnect)
        Button(
            onClick = { showFragmentProfiles = !showFragmentProfiles },
            shape = RoundedCornerShape(999.dp),
            modifier = Modifier.fillMaxWidth(),
            colors = simorghAdaptiveButtonColors(),
            border = BorderStroke(1.dp, simorghOutline(LocalAppIsDarkTheme.current))
        ) {
            Text("Profiles", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Normal, fontFamily = UiRegularFontFamily)
        }
        if (showFragmentProfiles) {
            FragmentProfilesCard(
                state = state,
                onConfigChanged = onConfigChanged,
                onAddressChanged = onAddressChanged,
                onPacketsChanged = onPacketsChanged,
                onLengthsChanged = onLengthsChanged,
                onDelaysChanged = onDelaysChanged,
                onMaxSplitChanged = onMaxSplitChanged,
                onPing = onPing,
                onAddProfile = onAddProfile,
                onSelectProfile = onSelectProfile,
                onPingProfile = onPingProfile,
                onRenameProfile = onRenameProfile,
                onDeleteProfile = onDeleteProfile
            )
        }
    }
}

@Composable
private fun FragmentProfilesCard(
    state: SimorghPublicState,
    onConfigChanged: (String) -> Unit,
    onAddressChanged: (String) -> Unit,
    onPacketsChanged: (String) -> Unit,
    onLengthsChanged: (String) -> Unit,
    onDelaysChanged: (String) -> Unit,
    onMaxSplitChanged: (String) -> Unit,
    onPing: () -> Unit,
    onAddProfile: (String) -> Unit,
    onSelectProfile: (String) -> Unit,
    onPingProfile: (String) -> Unit,
    onRenameProfile: (String, String) -> Unit,
    onDeleteProfile: (String) -> Unit
) {
    var newProfileName by remember { mutableStateOf("") }
    var showEditor by remember { mutableStateOf(state.fragmentConfigInput.isBlank()) }
    var remarkText by remember(state.fragmentSelectedProfile) { mutableStateOf(state.fragmentSelectedProfile.ifBlank { "Default" }) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(simorghInsetSurface(LocalAppIsDarkTheme.current))
            .border(1.dp, simorghOutline(LocalAppIsDarkTheme.current), RoundedCornerShape(24.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            LiquidBubbleText("Profiles", big = true)
            Text(state.fragmentSelectedProfile.ifBlank { "Default" }, color = Color.White.copy(alpha = .70f), fontSize = 11.sp, fontFamily = UiRegularFontFamily, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newProfileName,
                onValueChange = { newProfileName = it.take(42) },
                label = { Text("Profile name") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                textStyle = TextStyle(fontSize = 12.sp, fontFamily = UiRegularFontFamily)
            )
            Button(
                onClick = {
                    onAddProfile(newProfileName)
                    newProfileName = ""
                    showEditor = true
                },
                shape = RoundedCornerShape(999.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 9.dp),
                colors = simorghAdaptiveButtonColors(),
                border = BorderStroke(1.dp, simorghOutline(LocalAppIsDarkTheme.current))
            ) { Text("Add", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Normal, fontFamily = UiRegularFontFamily) }
        }
        val profiles = state.fragmentProfiles.ifEmpty { listOf("Default") }
        LazyColumn(modifier = Modifier.fillMaxWidth().height(145.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            items(profiles, key = { it }) { profile ->
                val selected = profile == state.fragmentSelectedProfile
                val ping = state.fragmentProfilePings[profile]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (selected) simorghSelectedSurface(LocalAppIsDarkTheme.current) else simorghInsetSurface(LocalAppIsDarkTheme.current))
                        .border(1.dp, if (selected) LocalSectionAccent.current.copy(alpha = .54f) else simorghOutline(LocalAppIsDarkTheme.current), RoundedCornerShape(16.dp))
                        .clickable { onSelectProfile(profile) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(profile, color = Color.White.copy(alpha = if (selected) .98f else .80f), fontSize = 12.sp, fontFamily = UiRegularFontFamily, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (ping != null && ping >= 0L) "${ping}ms" else "—", color = Color.White.copy(alpha = .58f), fontSize = 10.sp, fontFamily = UiRegularFontFamily, maxLines = 1)
                    }
                    Button(
                        onClick = { onPingProfile(profile) },
                        enabled = !state.fragmentConnecting,
                        shape = RoundedCornerShape(999.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        colors = simorghAdaptiveButtonColors(),
                        border = BorderStroke(1.dp, simorghOutline(LocalAppIsDarkTheme.current))
                    ) { Text("Ping", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Normal, fontFamily = UiRegularFontFamily) }
                    Button(
                        onClick = {
                            if (showEditor && selected) {
                                showEditor = false
                            } else {
                                onSelectProfile(profile)
                                showEditor = true
                            }
                        },
                        shape = RoundedCornerShape(999.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        colors = simorghAdaptiveButtonColors(),
                        border = BorderStroke(1.dp, simorghOutline(LocalAppIsDarkTheme.current))
                    ) { Text("Edit", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Normal, fontFamily = UiRegularFontFamily) }
                    Button(
                        onClick = {
                            onDeleteProfile(profile)
                            if (selected) showEditor = false
                        },
                        shape = RoundedCornerShape(999.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        colors = simorghAdaptiveButtonColors(),
                        border = BorderStroke(1.dp, simorghOutline(LocalAppIsDarkTheme.current))
                    ) { Text("Delete", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Normal, fontFamily = UiRegularFontFamily) }
                }
            }
        }
        LiquidBubbleParagraph(state.fragmentStatus, modifier = Modifier.fillMaxWidth(), centered = false, maxLines = 2)
        if (showEditor) {
            OutlinedTextField(
                value = remarkText,
                onValueChange = { value ->
                    val next = value.take(42)
                    remarkText = next
                    if (next.trim().isNotBlank() && next.trim() != state.fragmentSelectedProfile) {
                        onRenameProfile(state.fragmentSelectedProfile, next)
                    }
                },
                label = { Text("Remark") },
                placeholder = { Text("Profile name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                textStyle = TextStyle(fontSize = 12.sp, fontFamily = UiRegularFontFamily)
            )
            OutlinedTextField(
                value = state.fragmentAddress,
                onValueChange = onAddressChanged,
                label = { Text("Address") },
                placeholder = { Text("IP or domain override") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                textStyle = TextStyle(fontSize = 12.sp, fontFamily = UiRegularFontFamily)
            )
            OutlinedTextField(
                value = state.fragmentConfigInput,
                onValueChange = onConfigChanged,
                label = { Text("VLESS / Trojan config") },
                placeholder = { Text("vless://...  or  trojan://...") },
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
                textStyle = TextStyle(fontSize = 12.sp, fontFamily = UiRegularFontFamily)
            )
            LiquidBubbleText("Fragment Setting", big = false, fontFamily = UiRegularFontFamily, fontWeight = FontWeight.Normal)
            OutlinedTextField(
                value = state.fragmentPackets,
                onValueChange = onPacketsChanged,
                label = { Text("packets") },
                placeholder = { Text("tlshello") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                textStyle = TextStyle(fontSize = 12.sp, fontFamily = UiRegularFontFamily)
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.fragmentLengths,
                    onValueChange = onLengthsChanged,
                    label = { Text("lengths") },
                    placeholder = { Text("3-5,6-8,10-20") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    textStyle = TextStyle(fontSize = 12.sp, fontFamily = UiRegularFontFamily)
                )
                OutlinedTextField(
                    value = state.fragmentDelays,
                    onValueChange = onDelaysChanged,
                    label = { Text("delays") },
                    placeholder = { Text("1-2,5-6,10-20") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    textStyle = TextStyle(fontSize = 12.sp, fontFamily = UiRegularFontFamily)
                )
            }
            OutlinedTextField(
                value = state.fragmentMaxSplit,
                onValueChange = onMaxSplitChanged,
                label = { Text("maxSplit") },
                placeholder = { Text("64") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                textStyle = TextStyle(fontSize = 12.sp, fontFamily = UiRegularFontFamily)
            )
        }
    }
}


@Composable
private fun FragmentConnectOrb(state: SimorghPublicState, color: Color, onConnect: () -> Unit) {
    val active = state.fragmentConnected || state.fragmentConnecting
    val transition = rememberInfiniteTransition(label = "fragmentConnectAnimation")
    val pulse by transition.animateFloat(
        initialValue = 0.82f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse),
        label = "fragmentConnectPulseValue"
    )
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1350), RepeatMode.Restart),
        label = "fragmentConnectSpinRing"
    )
    Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(132.dp)) {
            drawCircle(color = Color.White.copy(alpha = 0.08f), radius = size.minDimension / 2.18f, style = Stroke(width = 2.dp.toPx()))
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
                .clickable(enabled = true) { runCatching { onConnect() } },
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
                        Text(if (active) "■" else "▶", color = Color.White, fontSize = if (active) 34.sp else 40.sp, fontWeight = FontWeight.ExtraBold, fontFamily = UiBoldFontFamily)
                        Spacer(Modifier.height(5.dp))
                        Text(
                            when {
                                state.fragmentConnected -> "DISCONNECT"
                                state.fragmentConnecting -> "CONNECTING"
                                else -> "CONNECT"
                            },
                            color = Color.White,
                            fontWeight = FontWeight.ExtraBold, fontFamily = UiBoldFontFamily,
                            fontSize = 9.sp,
                            letterSpacing = .30.sp,
                            maxLines = 1
                        )
                    }
                }
            }
        }
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
    onSelectConfig: (Int) -> Unit,
    title: String = "Free V2Ray",
    onCustomAddProfile: () -> Unit,
    onCustomSelectProfile: (String) -> Unit,
    onCustomDeleteProfile: (String) -> Unit,
    onCustomRemarkChanged: (String) -> Unit,
    onCustomInputChanged: (String) -> Unit,
    onCustomUpdate: () -> Unit,
    onCustomUpdateProfile: (String) -> Unit,
    onCustomPingConfig: (Int) -> Unit,
    onCustomConnectConfig: (Int) -> Unit
) {
    val isServerless = title == "ServerLess"
    val accent = if (isServerless) Color(0xFF0E7490) else LocalSectionAccent.current
    val serverlessHasSelection = state.simpleConfigItems.any { it.selected }
    var showConfigs by remember(isServerless) { mutableStateOf(isServerless) }
    var showSubConfig by remember { mutableStateOf(false) }
    GlassCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            LiquidBubbleText(title, big = true)
            Text(
                "VPN MODE",
                color = Color.White.copy(alpha = .78f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold, fontFamily = UiBoldFontFamily,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(simorghInsetSurface(LocalAppIsDarkTheme.current))
                    .border(1.dp, simorghOutline(LocalAppIsDarkTheme.current), RoundedCornerShape(999.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
        SpeedMiniBubble(state)
        if (isServerless) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                InfoText("Configs", state.simpleConfigCount.toString(), Modifier.weight(1f))
                InfoText(
                    "Selected",
                    if (state.simpleBestName.isNotBlank()) state.simpleBestName else "Choose config",
                    Modifier.weight(2f).clickable { showConfigs = !showConfigs }
                )
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                InfoText("Count", state.simpleConfigCount.toString(), Modifier.weight(1f))
                InfoText("Ping", if (state.simpleBestPingMs >= 0L) "${state.simpleBestPingMs}ms" else "—", Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                InfoText(
                    "Config",
                    if (state.simpleBestName.isNotBlank()) state.simpleBestName else "Tap to list",
                    Modifier.fillMaxWidth().clickable { showConfigs = !showConfigs }
                )
            }
        }
        if (showConfigs) {
            SimpleConfigListPanel(
                state = state,
                onPingAll = onPingAll,
                onConnectConfig = onConnectConfig,
                onSelectConfig = onSelectConfig,
                title = if (isServerless) "ServerLess Configurations" else "Free V2Ray List",
                showPingAll = !isServerless,
                selectionOnly = isServerless,
                emptyMessage = if (isServerless) "No ServerLess configs are cached. Update the link first." else "No cached configs. Tap Update first."
            )
        }
        SimpleXrayConnectOrb(
            state = state,
            color = accent,
            onConnect = onConnect,
            enabled = !isServerless || serverlessHasSelection || state.simpleConnected || state.simpleConnecting,
            selectionNeeded = isServerless && !serverlessHasSelection && !state.simpleConnected && !state.simpleConnecting
        )
        if (isServerless) {
            Button(
                onClick = onUpdate,
                enabled = !state.simpleConnecting,
                shape = RoundedCornerShape(999.dp),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF155E75),
                    contentColor = Color.White,
                    disabledContainerColor = simorghControlSurface(LocalAppIsDarkTheme.current),
                    disabledContentColor = simorghSecondaryText(LocalAppIsDarkTheme.current)
                ),
                border = BorderStroke(1.dp, accent.copy(alpha = .35f))
            ) {
                Text("Update Subscription", color = Color.White, fontWeight = FontWeight.SemiBold, fontFamily = UiBoldFontFamily)
            }
        }
        if (!isServerless) {
            Button(
                onClick = onUpdate,
                enabled = !state.simpleConnecting,
                shape = RoundedCornerShape(999.dp),
                modifier = Modifier.fillMaxWidth(),
                colors = simorghAdaptiveButtonColors(),
                border = BorderStroke(1.dp, simorghOutline(LocalAppIsDarkTheme.current))
            ) {
                Text("Update", color = Color.White, fontWeight = FontWeight.Normal, fontFamily = UiRegularFontFamily)
            }
            Button(
                onClick = { showSubConfig = !showSubConfig },
                enabled = !state.simpleConnecting,
                shape = RoundedCornerShape(999.dp),
                modifier = Modifier.fillMaxWidth(),
                colors = simorghAdaptiveButtonColors(),
                border = BorderStroke(1.dp, simorghOutline(LocalAppIsDarkTheme.current))
            ) {
                Text("Sub/Config", color = Color.White, fontWeight = FontWeight.Normal, fontFamily = UiRegularFontFamily)
            }
            if (showSubConfig) {
                SimpleSubConfigPanel(
                    state = state,
                    onAddProfile = onCustomAddProfile,
                    onSelectProfile = onCustomSelectProfile,
                    onDeleteProfile = onCustomDeleteProfile,
                    onRemarkChanged = onCustomRemarkChanged,
                    onInputChanged = onCustomInputChanged,
                    onUpdate = onCustomUpdate,
                    onUpdateProfile = onCustomUpdateProfile,
                    onPingConfig = onCustomPingConfig,
                    onConnectConfig = onCustomConnectConfig
                )
            }
            Button(
                onClick = onClearCache,
                enabled = !state.simpleConnecting,
                shape = RoundedCornerShape(999.dp),
                modifier = Modifier.fillMaxWidth(),
                colors = simorghAdaptiveButtonColors(),
                border = BorderStroke(1.dp, simorghOutline(LocalAppIsDarkTheme.current))
            ) {
                Text("Clear Data", color = Color.White, fontWeight = FontWeight.Normal, fontFamily = UiRegularFontFamily, fontSize = 12.sp)
            }
        }
        val simpleVisibleStatus = if (isServerless) state.simpleStatus else simpleConfigScanOnlyStatusText(state).replace("Simple", "Free V2Ray")
        LiquidBubbleParagraph(simpleVisibleStatus, modifier = Modifier.fillMaxWidth(), centered = false, maxLines = 3)
    }
}


@Composable
private fun SimpleSubConfigPanel(
    state: SimorghPublicState,
    onAddProfile: () -> Unit,
    onSelectProfile: (String) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onRemarkChanged: (String) -> Unit,
    onInputChanged: (String) -> Unit,
    onUpdate: () -> Unit,
    onUpdateProfile: (String) -> Unit,
    onPingConfig: (Int) -> Unit,
    onConnectConfig: (Int) -> Unit
) {
    var editingProfileId by remember { mutableStateOf<String?>(null) }
    val selectedProfileId = state.simpleCustomSelectedProfile
    LaunchedEffect(selectedProfileId) {
        if (editingProfileId != null && editingProfileId != selectedProfileId) editingProfileId = null
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(simorghInsetSurface(LocalAppIsDarkTheme.current))
            .border(1.dp, simorghOutline(LocalAppIsDarkTheme.current), RoundedCornerShape(24.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            LiquidBubbleText("Sub/Config", big = true)
            Button(
                onClick = {
                    editingProfileId = null
                    onAddProfile()
                },
                shape = RoundedCornerShape(999.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                colors = simorghAdaptiveButtonColors(),
                border = BorderStroke(1.dp, simorghOutline(LocalAppIsDarkTheme.current))
            ) { Text("Add", color = Color.White, fontSize = 12.sp, fontFamily = UiRegularFontFamily) }
        }

        if (state.simpleCustomProfiles.isEmpty()) {
            Text("Tap Add to create an empty profile.", color = Color.White.copy(alpha = .70f), fontSize = 12.sp, fontFamily = UiRegularFontFamily)
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth().height(126.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(state.simpleCustomProfiles, key = { it.id }) { profile ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (profile.selected) simorghSelectedSurface(LocalAppIsDarkTheme.current) else simorghInsetSurface(LocalAppIsDarkTheme.current))
                            .border(1.dp, if (profile.selected) LocalSectionAccent.current.copy(alpha = .56f) else simorghOutline(LocalAppIsDarkTheme.current), RoundedCornerShape(16.dp))
                            .clickable {
                                editingProfileId = null
                                onSelectProfile(profile.id)
                            }
                            .padding(horizontal = 9.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(profile.remark.ifBlank { "Profile" }, color = Color.White.copy(alpha = .92f), fontSize = 12.sp, fontFamily = UiRegularFontFamily, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${profile.configCount} configs", color = Color.White.copy(alpha = .58f), fontSize = 10.sp, fontFamily = UiRegularFontFamily, maxLines = 1)
                        }
                        Button(
                            onClick = {
                                onSelectProfile(profile.id)
                                onUpdateProfile(profile.id)
                            },
                            shape = RoundedCornerShape(999.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 5.dp),
                            colors = simorghAdaptiveButtonColors(),
                            border = BorderStroke(1.dp, simorghOutline(LocalAppIsDarkTheme.current))
                        ) { Text("Update", color = Color.White, fontSize = 9.sp, fontFamily = UiRegularFontFamily) }
                        Button(
                            onClick = {
                                onSelectProfile(profile.id)
                                editingProfileId = if (editingProfileId == profile.id) null else profile.id
                            },
                            shape = RoundedCornerShape(999.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 5.dp),
                            colors = simorghAdaptiveButtonColors(),
                            border = BorderStroke(1.dp, simorghOutline(LocalAppIsDarkTheme.current))
                        ) { Text("Edit", color = Color.White, fontSize = 9.sp, fontFamily = UiRegularFontFamily) }
                        Button(
                            onClick = {
                                editingProfileId = null
                                onDeleteProfile(profile.id)
                            },
                            shape = RoundedCornerShape(999.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 5.dp),
                            colors = simorghAdaptiveButtonColors(),
                            border = BorderStroke(1.dp, simorghOutline(LocalAppIsDarkTheme.current))
                        ) { Text("Delete", color = Color.White, fontSize = 9.sp, fontFamily = UiRegularFontFamily) }
                    }
                }
            }

            if (editingProfileId == selectedProfileId && selectedProfileId.isNotBlank()) {
                OutlinedTextField(
                    value = state.simpleCustomRemark,
                    onValueChange = onRemarkChanged,
                    label = { Text("Remark") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(fontSize = 12.sp, fontFamily = UiRegularFontFamily)
                )
                OutlinedTextField(
                    value = state.simpleCustomInput,
                    onValueChange = onInputChanged,
                    label = { Text("Sub link / config") },
                    placeholder = { Text("https://... or vless://...") },
                    minLines = 3,
                    maxLines = 7,
                    modifier = Modifier.fillMaxWidth().height(126.dp),
                    textStyle = TextStyle(fontSize = 12.sp, fontFamily = UiRegularFontFamily)
                )
                Button(
                    onClick = onUpdate,
                    enabled = state.simpleCustomInput.isNotBlank() && !state.simpleConnecting,
                    shape = RoundedCornerShape(999.dp),
                    modifier = Modifier.fillMaxWidth(),
                    colors = simorghAdaptiveButtonColors(),
                    border = BorderStroke(1.dp, simorghOutline(LocalAppIsDarkTheme.current))
                ) { Text("Update Link", color = Color.White, fontSize = 12.sp, fontFamily = UiRegularFontFamily) }
            }

            if (state.simpleCustomStatus.isNotBlank()) {
                Text(state.simpleCustomStatus.replace("Simple", "Free V2Ray"), color = Color.White.copy(alpha = .72f), fontSize = 11.sp, fontFamily = UiRegularFontFamily, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }

            Text("Config", color = Color.White.copy(alpha = .86f), fontSize = 12.sp, fontFamily = UiRegularFontFamily)
            if (state.simpleCustomConfigItems.isEmpty()) {
                Text("No detected configs yet.", color = Color.White.copy(alpha = .62f), fontSize = 11.sp, fontFamily = UiRegularFontFamily)
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().height(210.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(state.simpleCustomConfigItems, key = { it.index }) { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(simorghInsetSurface(LocalAppIsDarkTheme.current))
                                .border(1.dp, simorghOutline(LocalAppIsDarkTheme.current), RoundedCornerShape(16.dp))
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(item.label, color = Color.White.copy(alpha = .92f), fontSize = 12.sp, fontFamily = UiRegularFontFamily, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(item.pingLabel, color = Color.White.copy(alpha = .62f), fontSize = 10.sp, fontFamily = UiRegularFontFamily, maxLines = 1)
                            }
                            Button(
                                onClick = { onPingConfig(item.index) },
                                enabled = !state.simpleConnecting,
                                shape = RoundedCornerShape(999.dp),
                                contentPadding = PaddingValues(horizontal = 9.dp, vertical = 6.dp),
                                colors = simorghAdaptiveButtonColors(),
                                border = BorderStroke(1.dp, simorghOutline(LocalAppIsDarkTheme.current))
                            ) { Text("Ping", color = Color.White, fontSize = 10.sp, fontFamily = UiRegularFontFamily) }
                            Button(
                                onClick = { onConnectConfig(item.index) },
                                enabled = !state.simpleConnecting,
                                shape = RoundedCornerShape(999.dp),
                                contentPadding = PaddingValues(horizontal = 9.dp, vertical = 6.dp),
                                colors = simorghAdaptiveButtonColors(),
                                border = BorderStroke(1.dp, simorghOutline(LocalAppIsDarkTheme.current))
                            ) { Text("Connect", color = Color.White, fontSize = 10.sp, fontFamily = UiRegularFontFamily) }
                        }
                    }
                }
            }
        }
    }
}


private fun simpleConfigScanOnlyStatusText(state: SimorghPublicState): String {
    val raw = state.simpleStatus.trim()
    val scanned = Regex("""(\d+)\s*/\s*(\d+)\s*scanned\s*•\s*(\d+)\s*healthy""", RegexOption.IGNORE_CASE).find(raw)
    if (scanned != null) {
        val done = scanned.groupValues[1]
        val total = scanned.groupValues[2]
        val healthy = scanned.groupValues[3]
        val best = Regex("""best\s+([^•]+?)(?:\s*•\s*(\d+)ms)?(?:$|\s)""", RegexOption.IGNORE_CASE).find(raw)
        val bestText = best?.let { match ->
            val label = match.groupValues.getOrNull(1).orEmpty().trim().trimEnd('•')
            val ping = match.groupValues.getOrNull(2).orEmpty().trim()
            when {
                label.isNotBlank() && ping.isNotBlank() -> " • Best $label • ${ping}ms"
                label.isNotBlank() -> " • Best $label"
                else -> ""
            }
        }.orEmpty()
        return "Config scan: $done/$total scanned • $healthy healthy$bestText"
    }

    val configCountFromStatus = Regex("""(\d+)\s+configs?""", RegexOption.IGNORE_CASE).find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()
    val configCount = when {
        state.simpleConfigCount > 0 -> state.simpleConfigCount
        configCountFromStatus != null && configCountFromStatus > 0 -> configCountFromStatus
        else -> 0
    }
    val bestText = when {
        state.simpleBestPingMs >= 0L && state.simpleBestName.isNotBlank() -> " • Best ${state.simpleBestName} • ${state.simpleBestPingMs}ms"
        state.simpleBestPingMs >= 0L -> " • Best ${state.simpleBestPingMs}ms"
        else -> ""
    }

    return when {
        raw.contains("no healthy", true) -> "Config scan finished • $configCount configs • 0 healthy"
        raw.contains("no configs", true) -> "Config scan idle • no configs found"
        raw.contains("Ping All", true) && raw.contains("started", true) -> "Config scan: testing $configCount configs"
        raw.contains("Ping All", true) && raw.contains("finished", true) -> "Config scan finished • $configCount configs$bestText"
        state.simpleConnecting -> if (configCount > 0) "Config scan: testing $configCount configs$bestText" else "Config scan: loading configs"
        configCount > 0 -> "Config scan idle • $configCount configs$bestText"
        else -> "Config scan idle"
    }
}

@Composable
private fun SimpleConfigListPanel(
    state: SimorghPublicState,
    onPingAll: () -> Unit,
    onConnectConfig: (Int) -> Unit,
    onSelectConfig: (Int) -> Unit,
    title: String,
    showPingAll: Boolean,
    selectionOnly: Boolean,
    emptyMessage: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(simorghInsetSurface(LocalAppIsDarkTheme.current))
            .border(1.dp, simorghOutline(LocalAppIsDarkTheme.current), RoundedCornerShape(22.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontWeight = FontWeight.Normal, fontFamily = UiRegularFontFamily, fontSize = 14.sp)
            }
            if (showPingAll) {
                Button(
                    onClick = onPingAll,
                    enabled = !state.simpleConnecting && state.simpleConfigCount > 0,
                    shape = RoundedCornerShape(999.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    colors = simorghAdaptiveButtonColors(),
                    border = BorderStroke(1.dp, simorghOutline(LocalAppIsDarkTheme.current))
                ) {
                    Text("Ping All", color = Color.White, fontWeight = FontWeight.Normal, fontFamily = UiRegularFontFamily, fontSize = 11.sp)
                }
            }
        }
        if (state.simpleConfigItems.isEmpty()) {
            Text(emptyMessage, color = Color.White.copy(alpha = .70f), fontSize = 12.sp)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().height(260.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(state.simpleConfigItems, key = { it.index }) { item ->
                    val rowShape = RoundedCornerShape(16.dp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(rowShape)
                            .then(if (selectionOnly) Modifier.clickable(enabled = !state.simpleConnecting) { onSelectConfig(item.index) } else Modifier)
                            .background(if (item.selected) simorghSelectedSurface(LocalAppIsDarkTheme.current) else simorghControlSurface(LocalAppIsDarkTheme.current))
                            .border(1.dp, if (item.selected) LocalSectionAccent.current.copy(alpha = .56f) else simorghOutline(LocalAppIsDarkTheme.current), rowShape)
                            .padding(horizontal = 10.dp, vertical = 9.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(item.label, color = Color.White, fontWeight = FontWeight.Normal, fontFamily = UiRegularFontFamily, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(item.pingLabel, color = Color.White.copy(alpha = .82f), fontWeight = FontWeight.Normal, fontFamily = UiRegularFontFamily, fontSize = 12.sp, maxLines = 1)
                            if (selectionOnly) {
                                Text(
                                    if (item.selected) "SELECTED" else "SELECT",
                                    color = if (item.selected) Color(0xFF0E7490) else simorghSecondaryText(LocalAppIsDarkTheme.current),
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = UiBoldFontFamily,
                                    fontSize = 9.sp
                                )
                            } else {
                                Button(
                                    onClick = { onConnectConfig(item.index) },
                                    enabled = !state.simpleConnecting && state.simpleConfigCount > 0,
                                    shape = RoundedCornerShape(999.dp),
                                    contentPadding = PaddingValues(horizontal = 9.dp, vertical = 4.dp),
                                    colors = simorghAdaptiveButtonColors(),
                                    border = BorderStroke(1.dp, simorghOutline(LocalAppIsDarkTheme.current))
                                ) {
                                    Text("Connect", color = Color.White, fontWeight = FontWeight.Bold, fontFamily = UiBoldFontFamily, fontSize = 10.sp)
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
private fun ModeDebugLogCard(title: String, keywords: List<String>) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var logText by remember(title, keywords) { mutableStateOf(modeRelevantLogs(RKhVpnLogStore.readText(context), keywords)) }
    fun refreshLogs() { logText = modeRelevantLogs(RKhVpnLogStore.readText(context), keywords) }
    Spacer(Modifier.height(8.dp))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(simorghInsetSurface(LocalAppIsDarkTheme.current))
            .border(1.dp, simorghOutline(LocalAppIsDarkTheme.current), RoundedCornerShape(18.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, fontFamily = UiBoldFontFamily)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { refreshLogs() },
                    shape = RoundedCornerShape(999.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    colors = simorghAdaptiveButtonColors(),
                    border = BorderStroke(1.dp, simorghOutline(LocalAppIsDarkTheme.current))
                ) { Text("Refresh", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = UiBoldFontFamily) }
                Button(
                    onClick = {
                        RKhVpnLogStore.clear(context)
                        logText = ""
                    },
                    shape = RoundedCornerShape(999.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444).copy(alpha = .45f)),
                    border = BorderStroke(1.dp, simorghOutline(LocalAppIsDarkTheme.current))
                ) { Text("Clear", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = UiBoldFontFamily) }
                Button(
                    onClick = {
                        val fresh = modeRelevantLogs(RKhVpnLogStore.readText(context), keywords)
                        logText = fresh
                        clipboard.setText(AnnotatedString(fresh.ifBlank { "No $title entries yet." }))
                    },
                    shape = RoundedCornerShape(999.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = LocalSectionAccent.current.copy(alpha = .55f)),
                    border = BorderStroke(1.dp, simorghOutline(LocalAppIsDarkTheme.current))
                ) { Text("Copy", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = UiBoldFontFamily) }
            }
        }
        SelectionContainer {
            val horizontal = rememberScrollState()
            val vertical = rememberScrollState()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 86.dp, max = 150.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.Black.copy(alpha = .16f))
                    .border(1.dp, simorghOutline(LocalAppIsDarkTheme.current), RoundedCornerShape(14.dp))
                    .horizontalScroll(horizontal)
                    .padding(8.dp)
            ) {
                Text(
                    text = logText.ifBlank { "No diagnostic log yet. Tap Connect/Disconnect, then Refresh or Copy." },
                    color = Color.White.copy(alpha = .84f),
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    softWrap = false,
                    maxLines = 18,
                    overflow = TextOverflow.Visible
                )
            }
        }
    }
}

private fun modeRelevantLogs(raw: String, keywords: List<String>): String {
    if (raw.isBlank()) return ""
    val allKeywords = (keywords + listOf("tun2proxy", "libtun2proxy", "simorghtun2proxybridge", "CoreBin", "Safe service", "onStartCommand", "start failed", "exited immediately", "UnsatisfiedLinkError", "Exception", "Throwable", "crash", "failed", "error")).distinct()
    return raw.lineSequence()
        .filter { line -> allKeywords.any { key -> line.contains(key, ignoreCase = true) } }
        .toList()
        .takeLast(120)
        .joinToString("\n")
}

@Composable
private fun SimpleServerlessDropdown(enabled: Boolean, locked: Boolean, onChanged: (Boolean) -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(simorghInsetSurface(LocalAppIsDarkTheme.current))
            .border(1.dp, simorghOutline(LocalAppIsDarkTheme.current), shape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("ServerLess", color = Color.White.copy(alpha = .94f), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, fontFamily = UiBoldFontFamily)
            Text(if (enabled) "IRAN IPS • ON" else "IRAN IPS • OFF", color = Color.White.copy(alpha = .62f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, fontFamily = UiBoldFontFamily)
        }
        Box(
            modifier = Modifier
                .width(58.dp)
                .height(32.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(if (enabled) simorghSelectedSurface(LocalAppIsDarkTheme.current) else simorghControlSurface(LocalAppIsDarkTheme.current))
                .border(1.dp, if (enabled) LocalSectionAccent.current.copy(alpha = .56f) else simorghOutline(LocalAppIsDarkTheme.current), RoundedCornerShape(999.dp))
                .clickable(enabled = !locked) { onChanged(!enabled) }
                .padding(3.dp),
            contentAlignment = if (enabled) Alignment.CenterEnd else Alignment.CenterStart
        ) {
            Box(
                Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = if (enabled) .92f else .58f))
            )
        }
    }
}

@Composable
private fun StormDnsCard(
    state: SimorghPublicState,
    collapseScrollOffset: Int = 0,
    onConnect: () -> Unit,
    onFieldChanged: (String, String) -> Unit,
    onModeChanged: (String) -> Unit,
    onImportResolvers: () -> Unit,
    onSaveProfile: () -> Unit,
    onAddProfile: () -> Unit,
    onSelectProfile: (String) -> Unit,
    onDeleteProfile: () -> Unit,
    onSaveResolverProfile: () -> Unit,
    onAddResolverProfile: () -> Unit,
    onSelectResolverProfile: (String) -> Unit,
    onDeleteResolverProfile: () -> Unit,
    onRefreshLogs: () -> Unit,
    onClearLogs: () -> Unit
) {
    val accent = LocalSectionAccent.current
    var showProfiles by remember { mutableStateOf(false) }
    var editConfigForProfile by remember { mutableStateOf(false) }
    var showHealthyFromBubble by remember { mutableStateOf(false) }
    var showResolvers by remember { mutableStateOf(false) }
    var editResolverProfile by remember { mutableStateOf(false) }
    var showClientConfig by remember { mutableStateOf(false) }
    var showServerConfig by remember { mutableStateOf(false) }
    var showHealthyDns by remember { mutableStateOf(false) }
    var showLogs by remember { mutableStateOf(false) }
    var profilesOpenedAt by remember { mutableStateOf<Int?>(null) }
    var resolversOpenedAt by remember { mutableStateOf<Int?>(null) }
    var logsOpenedAt by remember { mutableStateOf<Int?>(null) }
    val locked = state.stormDnsConnecting || state.stormDnsConnected
    // Safe auto-hide: close only after the user scrolls far away from the offset where the panel was opened.
    // This prevents the previous open -> immediate hide loop.
    LaunchedEffect(collapseScrollOffset) {
        val closeDistance = 9000
        if (showProfiles && profilesOpenedAt?.let { kotlin.math.abs(collapseScrollOffset - it) > closeDistance } == true) {
            showProfiles = false
            editConfigForProfile = false
            profilesOpenedAt = null
        }
        if (showResolvers && resolversOpenedAt?.let { kotlin.math.abs(collapseScrollOffset - it) > closeDistance } == true) {
            showResolvers = false
            editResolverProfile = false
            resolversOpenedAt = null
        }
        if (showLogs && logsOpenedAt?.let { kotlin.math.abs(collapseScrollOffset - it) > closeDistance } == true) {
            showLogs = false
            logsOpenedAt = null
        }
    }
    val clipboard = LocalClipboardManager.current
    val stormDnsCopyLogText = remember(state.stormDnsLogLines, state.stormDnsStatus, state.stormDnsResolverScanStatus, state.stormDnsResolverValidCount, state.stormDnsSocksPort) {
        buildString {
            append("SIMORGH MasterDNS Logs\n")
            append("Status: ").append(masterDnsDisplayText(state.stormDnsStatus)).append('\n')
            append("Resolver Status: ").append(masterDnsDisplayText(state.stormDnsResolverScanStatus)).append('\n')
            append("Healthy DNS: ").append(state.stormDnsResolverValidCount).append('\n')
            append("Local SOCKS5: 127.0.0.1:").append(state.stormDnsSocksPort).append("\n\n")
            append(state.stormDnsLogLines.takeLast(6).joinToString("\n"))
        }
    }
    GlassCard {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                LiquidBubbleText("MasterDNS", big = true)
                Text(
                    if (state.stormDnsRunMode == "vpn") "VPN MODE" else "PROXY MODE",
                    color = Color.White.copy(alpha = .78f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold, fontFamily = UiBoldFontFamily,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(simorghInsetSurface(LocalAppIsDarkTheme.current))
                        .border(1.dp, simorghOutline(LocalAppIsDarkTheme.current), RoundedCornerShape(999.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
            SpeedMiniBubble(state)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                StormDnsModePill("Proxy", state.stormDnsRunMode != "vpn", Modifier.weight(1f)) { if (!locked) onModeChanged("proxy") }
                StormDnsModePill("VPN", state.stormDnsRunMode == "vpn", Modifier.weight(1f)) { if (!locked) onModeChanged("vpn") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                InfoText("Profile", state.stormDnsSelectedProfile.ifBlank { "Default" }, Modifier.weight(1f))
                InfoText("Local SOCKS5", "127.0.0.1:${state.stormDnsSocksPort}", Modifier.weight(1f))
            }
            val dnsBubbleText = if (state.stormDnsResolverScanning) {
                "DNS SCAN ${state.stormDnsResolverScanned}/${state.stormDnsResolverTotal} • HEALTHY ${state.stormDnsResolverValidCount}"
            } else {
                "HEALTHY DNS: ${state.stormDnsResolverValidCount}"
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(LocalSectionAccent.current.copy(alpha = .095f))
                    .border(1.dp, LocalSectionAccent.current.copy(alpha = .18f), RoundedCornerShape(999.dp))
                    .clickable { showHealthyFromBubble = !showHealthyFromBubble }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(dnsBubbleText, color = Color.White.copy(alpha = .92f), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, fontFamily = UiBoldFontFamily)
            }
            if (showHealthyFromBubble) {
                StormDnsPanel {
                    Text("DNS Healthy from MasterDNS logs", color = Color.White.copy(alpha = .88f), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, fontFamily = UiBoldFontFamily)
                    if (state.stormDnsHealthyResolvers.isEmpty()) {
                        LiquidBubbleParagraph("No healthy DNS accepted by MasterDNS logs yet.", modifier = Modifier.fillMaxWidth(), centered = false, maxLines = 2)
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxWidth().height(154.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(state.stormDnsHealthyResolvers.take(120)) { dns ->
                                val index = state.stormDnsHealthyResolvers.indexOf(dns) + 1
                                Text("$index. $dns", color = Color.White.copy(alpha = .82f), fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (state.stormDnsHealthyResolvers.size > 120) {
                                item { Text("+${state.stormDnsHealthyResolvers.size - 120} more...", color = Color.White.copy(alpha = .62f), fontSize = 10.sp) }
                            }
                        }
                    }
                }
            }

            StormDnsConnectOrb(state = state, color = accent, onConnect = onConnect)
            Spacer(Modifier.height(6.dp))
            val latestStormDnsLog = state.stormDnsLogLines.lastOrNull() ?: masterDnsDisplayText(state.stormDnsResolverScanStatus.ifBlank { state.stormDnsStatus })
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(simorghInsetSurface(LocalAppIsDarkTheme.current))
                    .border(1.dp, simorghOutline(LocalAppIsDarkTheme.current), RoundedCornerShape(999.dp))
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                val statusScroll = rememberScrollState()
                Text(
                    latestStormDnsLog,
                    color = Color.White.copy(alpha = .82f),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Left,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Visible,
                    modifier = Modifier.fillMaxWidth().horizontalScroll(statusScroll)
                )
            }

            NipoSectionToggle("Config Profile", showProfiles, Modifier.fillMaxWidth()) {
                if (showProfiles && editConfigForProfile) {
                    editConfigForProfile = false
                } else {
                    val next = !showProfiles
                    showProfiles = next
                    profilesOpenedAt = if (next) collapseScrollOffset else null
                    if (!next) editConfigForProfile = false
                }
            }
            if (showProfiles) {
                StormDnsPanel {
                    OutlinedTextField(
                        value = state.stormDnsProfileName,
                        onValueChange = { onFieldChanged("profileName", it) },
                        enabled = !locked,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Profile Name") },
                        textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp),
                        maxLines = 1
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StormDnsSmallButton("Add", !locked, Modifier.weight(1f), onAddProfile)
                        StormDnsSmallButton("Save", !locked, Modifier.weight(1f), onSaveProfile)
                        StormDnsSmallButton("Delete", !locked && state.stormDnsProfiles.isNotEmpty(), Modifier.weight(1f), onDeleteProfile)
                    }
                    state.stormDnsProfiles.forEach { profile ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) {
                                StormDnsListRow(
                                    text = profile,
                                    selected = profile == state.stormDnsSelectedProfile,
                                    locked = locked,
                                    onClick = { onSelectProfile(profile) }
                                )
                            }
                            StormDnsSmallButton("✎", !locked, Modifier.width(38.dp)) {
                                if (profile == state.stormDnsSelectedProfile) {
                                    editConfigForProfile = !editConfigForProfile
                                } else {
                                    editConfigForProfile = true
                                    onSelectProfile(profile)
                                }
                            }
                        }
                    }
                    if (editConfigForProfile) {
                        StormDnsClientConfigEditor(
                            config = state.stormDnsClientConfig,
                            locked = locked,
                            onConfigChanged = { onFieldChanged("clientConfig", it) }
                        )
                    } else {
                        LiquidBubbleParagraph(
                            "Tap ✎ next to a profile to edit that profile's client_config settings.",
                            modifier = Modifier.fillMaxWidth(),
                            centered = false,
                            maxLines = 2
                        )
                    }
                }
            }

            NipoSectionToggle("Resolver Profiles", showResolvers, Modifier.fillMaxWidth()) {
                val next = !showResolvers
                showResolvers = next
                resolversOpenedAt = if (next) collapseScrollOffset else null
                if (!next) editResolverProfile = false
            }
            if (showResolvers) {
                var resolverDraft by remember(state.stormDnsSelectedResolverProfile, state.stormDnsResolvers) { mutableStateOf(state.stormDnsResolvers) }
                StormDnsPanel {
                    OutlinedTextField(
                        value = state.stormDnsResolverProfileName,
                        onValueChange = { onFieldChanged("resolverProfileName", it) },
                        enabled = !locked,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Resolver Profile Name") },
                        textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp),
                        maxLines = 1
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StormDnsSmallButton("Import TXT", !locked, Modifier.weight(1.15f), onImportResolvers)
                        StormDnsSmallButton("Add", !locked, Modifier.weight(1f), onAddResolverProfile)
                        StormDnsSmallButton("Save", !locked, Modifier.weight(1f)) {
                            onFieldChanged("resolvers", resolverDraft)
                            onSaveResolverProfile()
                        }
                        StormDnsSmallButton("Delete", !locked && state.stormDnsResolverProfiles.isNotEmpty(), Modifier.weight(1f), onDeleteResolverProfile)
                    }
                    if (state.stormDnsResolverProfiles.isNotEmpty()) {
                        LazyColumn(modifier = Modifier.fillMaxWidth().height(132.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(state.stormDnsResolverProfiles) { profile ->
                                StormDnsListRow(
                                    text = profile,
                                    selected = profile == state.stormDnsSelectedResolverProfile,
                                    locked = locked,
                                    onClick = {
                                        editResolverProfile = false
                                        onSelectResolverProfile(profile)
                                    }
                                )
                            }
                        }
                    }
                    StormDnsSmallButton(if (editResolverProfile) "Hide Resolver Editor" else "Edit Resolvers", !locked, Modifier.fillMaxWidth()) {
                        resolverDraft = state.stormDnsResolvers
                        editResolverProfile = !editResolverProfile
                    }
                    if (editResolverProfile) {
                        OutlinedTextField(
                            value = resolverDraft,
                            onValueChange = { resolverDraft = it },
                            enabled = !locked,
                            modifier = Modifier.fillMaxWidth().height(112.dp),
                            label = { Text("Resolvers") },
                            placeholder = { Text("1.1.1.1:53\n8.8.8.8:53") },
                            textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                            maxLines = 5
                        )
                    } else {
                        LiquidBubbleParagraph(
                            "${state.stormDnsResolvers.lineSequence().count { it.trim().isNotBlank() }} resolvers loaded. Tap Edit Resolvers only when you need to change the list.",
                            modifier = Modifier.fillMaxWidth(),
                            centered = false,
                            maxLines = 2
                        )
                    }
                    LiquidBubbleParagraph(
                        masterDnsDisplayText(state.stormDnsResolverScanStatus),
                        modifier = Modifier.fillMaxWidth(),
                        centered = false,
                        maxLines = 2
                    )
                }
            }

            NipoSectionToggle("Logs", showLogs, Modifier.fillMaxWidth()) {
                val next = !showLogs
                showLogs = next
                logsOpenedAt = if (next) collapseScrollOffset else null
                if (next) onRefreshLogs()
            }
            if (showLogs) {
                StormDnsPanel {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StormDnsSmallButton("Refresh", true, Modifier.weight(1f), onRefreshLogs)
                        StormDnsSmallButton("Copy Logs", state.stormDnsLogLines.isNotEmpty(), Modifier.weight(1f)) {
                            clipboard.setText(AnnotatedString(stormDnsCopyLogText))
                        }
                        StormDnsSmallButton("Clear", true, Modifier.weight(1f), onClearLogs)
                    }
                    if (state.stormDnsLogLines.isEmpty()) {
                        LiquidBubbleParagraph("No MasterDNS logs yet.", modifier = Modifier.fillMaxWidth(), centered = false, maxLines = 1)
                    } else {
                        SelectionContainer {
                            val horizontalLogScroll = rememberScrollState()
                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 96.dp, max = 280.dp)
                                    .horizontalScroll(horizontalLogScroll)
                            ) {
                                items(state.stormDnsLogLines.takeLast(6)) { line ->
                                    Text(
                                        line,
                                        color = Color.White.copy(alpha = .76f),
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Visible
                                    )
                                }
                            }
                        }
                    }
                }
            }

            LiquidBubbleParagraph(masterDnsDisplayText(state.stormDnsStatus), modifier = Modifier.fillMaxWidth(), centered = false, maxLines = 3)
        }
    }
}


private fun masterDnsDisplayText(text: String): String {
    val upstreamBrand = "Storm" + "DNS"
    val mixedBrand = "storm" + "DNS"
    val removedBrand = "White" + "DNS"
    return text
        .replace(upstreamBrand, "MasterDNS")
        .replace(mixedBrand, "MasterDNS")
        .replace(removedBrand, "")
        .replace(Regex("\\s{2,}"), " ")
        .trim()
}

private data class StormDnsTomlField(
    val key: String,
    val value: String,
    val kind: String
)

private val stormDnsTomlOptions: Map<String, List<Pair<String, String>>> = mapOf(
    "DATA_ENCRYPTION_METHOD" to listOf(
        "0" to "0 • None",
        "1" to "1 • XOR",
        "2" to "2 • ChaCha20",
        "3" to "3 • AES-128-GCM",
        "4" to "4 • AES-192-GCM",
        "5" to "5 • AES-256-GCM"
    ),
    "RESOLVER_BALANCING_STRATEGY" to listOf(
        "1" to "1 • Random",
        "2" to "2 • Round Robin",
        "3" to "3 • Least Loss",
        "4" to "4 • Lowest Latency",
        "5" to "5 • Hybrid Score",
        "6" to "6 • Loss Then Latency",
        "7" to "7 • Least Loss Top Random",
        "8" to "8 • Least Loss Top Round Robin"
    ),
    "UPLOAD_COMPRESSION_TYPE" to listOf(
        "0" to "0 • OFF",
        "1" to "1 • ZSTD",
        "2" to "2 • LZ4",
        "3" to "3 • ZLIB"
    ),
    "DOWNLOAD_COMPRESSION_TYPE" to listOf(
        "0" to "0 • OFF",
        "1" to "1 • ZSTD",
        "2" to "2 • LZ4",
        "3" to "3 • ZLIB"
    )
)

private fun parseStormDnsTomlFields(toml: String): List<StormDnsTomlField> {
    return toml.lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() && !it.startsWith("#") && it.contains("=") }
        .mapNotNull { line ->
            val key = line.substringBefore("=").trim()
            val raw = line.substringAfter("=").trim()
            if (!Regex("^[A-Z0-9_]+$").matches(key)) return@mapNotNull null
            val kind = when {
                raw.startsWith("[") -> "array"
                raw.equals("true", ignoreCase = true) || raw.equals("false", ignoreCase = true) -> "bool"
                raw.startsWith("\"") && raw.endsWith("\"") -> "string"
                else -> "number"
            }
            val display = when (kind) {
                "string" -> raw.removePrefix("\"").removeSuffix("\"")
                "array" -> raw.removePrefix("[").removeSuffix("]")
                    .split(',')
                    .joinToString(", ") { it.trim().removePrefix("\"").removeSuffix("\"") }
                else -> raw
            }
            StormDnsTomlField(key, display, kind)
        }
        .toList()
}

private fun stormDnsTomlEscape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

private fun formatStormDnsTomlValue(kind: String, value: String): String {
    return when (kind) {
        "bool" -> if (value.equals("true", ignoreCase = true) || value == "1" || value.equals("yes", ignoreCase = true)) "true" else "false"
        "array" -> value.split(',', '\n', ';')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString(prefix = "[", postfix = "]") { "\"${stormDnsTomlEscape(it)}\"" }
        "string" -> "\"${stormDnsTomlEscape(value)}\""
        else -> value.trim()
    }
}

private fun updateStormDnsTomlValue(toml: String, key: String, kind: String, value: String): String {
    val formatted = formatStormDnsTomlValue(kind, value)
    val regex = Regex("(?m)^\\s*" + Regex.escape(key) + "\\s*=.*$")
    return if (regex.containsMatchIn(toml)) {
        regex.replace(toml, "$key = $formatted")
    } else {
        toml.trimEnd() + "\n$key = $formatted\n"
    }
}

@Composable
private fun StormDnsClientConfigEditor(
    config: String,
    locked: Boolean,
    onConfigChanged: (String) -> Unit
) {
    val fields = remember(config) { parseStormDnsTomlFields(config) }
    val primaryKeys = setOf("DOMAINS", "ENCRYPTION_KEY")
    val primaryFields = fields.filter { it.key in primaryKeys }.sortedBy { if (it.key == "DOMAINS") 0 else 1 }
    val secondaryRows = fields.filterNot { it.key in primaryKeys }.chunked(3)
    StormDnsPanel {
        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 360.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            if (primaryFields.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        Text("Primary Connection", color = Color.White.copy(alpha = .90f), fontSize = 10.sp, fontWeight = FontWeight.Black, fontFamily = UiBoldFontFamily)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.Top) {
                            primaryFields.forEach { field ->
                                StormDnsTomlFieldEditor(field, config, locked, Modifier.weight(1f), onConfigChanged, prominent = true)
                            }
                            repeat(2 - primaryFields.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
            items(secondaryRows) { rowFields ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
                    rowFields.forEach { field ->
                        StormDnsTomlFieldEditor(field, config, locked, Modifier.weight(1f), onConfigChanged)
                    }
                    repeat(3 - rowFields.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun StormDnsTomlFieldEditor(
    field: StormDnsTomlField,
    config: String,
    locked: Boolean,
    modifier: Modifier,
    onConfigChanged: (String) -> Unit,
    prominent: Boolean = false
) {
    val options = stormDnsTomlOptions[field.key]
    val minCellHeight = if (prominent) 88.dp else 46.dp
    val titleFont = if (prominent) 10.sp else 7.6.sp
    val titleLineHeight = if (prominent) 11.sp else 8.4.sp
    val valueFont = if (prominent) 10.sp else 8.8.sp
    val cellModifier = modifier
        .heightIn(min = minCellHeight)
        .clip(RoundedCornerShape(if (prominent) 18.dp else 14.dp))
        .background(simorghInsetSurface(LocalAppIsDarkTheme.current))
        .border(1.dp, simorghOutline(LocalAppIsDarkTheme.current), RoundedCornerShape(if (prominent) 18.dp else 14.dp))
        .padding(horizontal = if (prominent) 8.dp else 6.dp, vertical = if (prominent) 7.dp else 4.dp)
    if (options != null) {
        var expanded by remember(field.key, field.value) { mutableStateOf(false) }
        val selected = options.firstOrNull { it.first == field.value.trim() }?.second ?: field.value
        Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = cellModifier) {
            Text(field.key, color = Color.White.copy(alpha = .90f), fontSize = titleFont, fontWeight = FontWeight.Black, fontFamily = UiBoldFontFamily, maxLines = if (prominent) 2 else 4, overflow = TextOverflow.Visible, lineHeight = titleLineHeight)
            Box(Modifier.fillMaxWidth()) {
                Button(
                    onClick = { expanded = true },
                    enabled = !locked,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = if (prominent) 34.dp else 27.dp),
                    contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp),
                    colors = simorghAdaptiveButtonColors(),
                    border = BorderStroke(1.dp, simorghOutline(LocalAppIsDarkTheme.current))
                ) {
                    Text(selected, color = Color.White, fontWeight = FontWeight.Black, fontFamily = UiBoldFontFamily, fontSize = valueFont, lineHeight = if (prominent) 11.sp else 9.2.sp, maxLines = if (prominent) 2 else 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    options.forEach { (value, label) ->
                        DropdownMenuItem(
                            text = { Text(label, fontSize = 10.sp, lineHeight = 11.sp, maxLines = 4, overflow = TextOverflow.Visible) },
                            onClick = {
                                expanded = false
                                onConfigChanged(updateStormDnsTomlValue(config, field.key, field.kind, value))
                            }
                        )
                    }
                }
            }
        }
    } else if (field.kind == "bool") {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = cellModifier) {
            Text(field.key, color = Color.White.copy(alpha = .90f), fontSize = titleFont, fontWeight = FontWeight.Black, fontFamily = UiBoldFontFamily, maxLines = if (prominent) 2 else 4, lineHeight = titleLineHeight, overflow = TextOverflow.Visible)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                StormDnsTinyChoiceButton("true", !locked, Modifier.weight(1f)) { onConfigChanged(updateStormDnsTomlValue(config, field.key, field.kind, "true")) }
                StormDnsTinyChoiceButton("false", !locked, Modifier.weight(1f)) { onConfigChanged(updateStormDnsTomlValue(config, field.key, field.kind, "false")) }
            }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = cellModifier) {
            Text(field.key, color = Color.White.copy(alpha = .90f), fontSize = titleFont, fontWeight = FontWeight.Black, fontFamily = UiBoldFontFamily, maxLines = if (prominent) 2 else 4, lineHeight = titleLineHeight, overflow = TextOverflow.Visible)
            OutlinedTextField(
                value = field.value,
                onValueChange = { onConfigChanged(updateStormDnsTomlValue(config, field.key, field.kind, it)) },
                enabled = !locked,
                modifier = Modifier.fillMaxWidth().heightIn(min = if (prominent) 42.dp else if (field.kind == "array") 36.dp else 28.dp),
                textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = valueFont, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, lineHeight = if (prominent) 11.sp else 9.2.sp),
                singleLine = field.kind != "array",
                maxLines = if (field.kind == "array") 2 else 1
            )
        }
    }
}

@Composable
private fun StormDnsPanel(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(simorghInsetSurface(LocalAppIsDarkTheme.current))
            .border(1.dp, simorghOutline(LocalAppIsDarkTheme.current), RoundedCornerShape(24.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content
    )
}

@Composable
private fun StormDnsTinyChoiceButton(text: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(999.dp),
        modifier = modifier.height(28.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
        colors = simorghAdaptiveButtonColors(),
        border = BorderStroke(1.dp, simorghOutline(LocalAppIsDarkTheme.current))
    ) {
        Text(text, color = Color.White, fontWeight = FontWeight.Black, fontFamily = UiBoldFontFamily, fontSize = 8.6.sp, maxLines = 1, overflow = TextOverflow.Visible, textAlign = TextAlign.Center)
    }
}

@Composable
private fun StormDnsSmallButton(text: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(999.dp),
        modifier = modifier.height(38.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp),
        colors = simorghAdaptiveButtonColors(),
        border = BorderStroke(1.dp, simorghOutline(LocalAppIsDarkTheme.current))
    ) { Text(text, color = Color.White, fontWeight = FontWeight.Normal, fontFamily = UiRegularFontFamily, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
}

@Composable
private fun StormDnsListRow(text: String, selected: Boolean, locked: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) simorghSelectedSurface(LocalAppIsDarkTheme.current) else simorghControlSurface(LocalAppIsDarkTheme.current))
            .border(1.dp, if (selected) LocalSectionAccent.current.copy(alpha = .56f) else simorghOutline(LocalAppIsDarkTheme.current), RoundedCornerShape(16.dp))
            .clickable(enabled = !locked) { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, color = Color.White.copy(alpha = if (selected) .96f else .76f), fontSize = 10.sp, lineHeight = 11.sp, fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold, maxLines = 2, modifier = Modifier.weight(1f))
        if (selected) Text("ACTIVE", color = LocalSectionAccent.current, fontSize = 8.sp, fontWeight = FontWeight.Black, fontFamily = UiBoldFontFamily)
    }
}

@Composable
private fun StormDnsConnectOrb(state: SimorghPublicState, color: Color, onConnect: () -> Unit) {
    val active = state.stormDnsConnected || state.stormDnsConnecting
    val transition = rememberInfiniteTransition(label = "stormDnsConnectAnimation")
    val pulse by transition.animateFloat(
        initialValue = 0.82f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse),
        label = "stormDnsConnectPulseValue"
    )
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1350), RepeatMode.Restart),
        label = "stormDnsConnectSpinRing"
    )
    Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(132.dp)) {
            drawCircle(color = Color.White.copy(alpha = 0.08f), radius = size.minDimension / 2.18f, style = Stroke(width = 2.dp.toPx()))
            rotate(spin) {
                drawArc(color = color.copy(alpha = 0.92f), startAngle = -90f, sweepAngle = 108f, useCenter = false, topLeft = Offset(7.dp.toPx(), 7.dp.toPx()), size = Size(size.width - 14.dp.toPx(), size.height - 14.dp.toPx()), style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round))
                drawArc(color = Color.White.copy(alpha = 0.46f * pulse), startAngle = 45f, sweepAngle = 42f, useCenter = false, topLeft = Offset(13.dp.toPx(), 13.dp.toPx()), size = Size(size.width - 26.dp.toPx(), size.height - 26.dp.toPx()), style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        Box(
            modifier = Modifier
                .size(if (active) 112.dp else 122.dp)
                .shadow(12.dp, CircleShape, ambientColor = color.copy(alpha = .28f), spotColor = color.copy(alpha = .42f))
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(Color.White.copy(alpha = .30f), color.copy(alpha = if (active) .64f else .58f), Color.White.copy(alpha = .060f), color.copy(alpha = .36f))))
                .border(1.4.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = .62f), color.copy(alpha = .70f), Color.White.copy(alpha = .22f))), CircleShape)
                .clickable { onConnect() },
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
                        Text(if (active) "■" else "▶", color = Color.White, fontSize = if (active) 34.sp else 40.sp, fontWeight = FontWeight.ExtraBold, fontFamily = UiBoldFontFamily)
                        Spacer(Modifier.height(5.dp))
                        Text(
                            when {
                                state.stormDnsConnected -> "DISCONNECT"
                                state.stormDnsConnecting -> "CONNECTING"
                                else -> "CONNECT"
                            },
                            color = Color.White,
                            fontWeight = FontWeight.ExtraBold, fontFamily = UiBoldFontFamily,
                            fontSize = 9.sp,
                            letterSpacing = .30.sp,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StormDnsModePill(text: String, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (active) simorghSelectedSurface(LocalAppIsDarkTheme.current) else simorghControlSurface(LocalAppIsDarkTheme.current))
            .border(1.dp, if (active) LocalSectionAccent.current.copy(alpha = .56f) else simorghOutline(LocalAppIsDarkTheme.current), RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = Color.White.copy(alpha = if (active) .96f else .68f), fontSize = 12.sp, fontWeight = if (active) FontWeight.ExtraBold else FontWeight.SemiBold)
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
    val accent = LocalSectionAccent.current
    var showProfileOptions by remember { mutableStateOf(false) }
    var showProfileEditor by remember { mutableStateOf(false) }
    val nipoStatusText = if (state.nipoConnected) "NipoVPN connected" else state.nipoStatus
    GlassCard {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                LiquidBubbleText("NipoVPN", big = true)
                Text(
                    "VPN MODE",
                    color = Color.White.copy(alpha = .72f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold, fontFamily = UiBoldFontFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(simorghInsetSurface(LocalAppIsDarkTheme.current))
                        .border(1.dp, simorghOutline(LocalAppIsDarkTheme.current), RoundedCornerShape(999.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
            SpeedMiniBubble(state)
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
                textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                maxLines = 3
            )
            Button(
                onClick = onAddProfile,
                enabled = !state.nipoConnecting && !state.nipoConnected && state.nipoImportText.isNotBlank(),
                shape = RoundedCornerShape(999.dp),
                modifier = Modifier.fillMaxWidth(),
                colors = simorghAdaptiveButtonColors(),
                border = BorderStroke(1.dp, simorghOutline(LocalAppIsDarkTheme.current))
            ) { Text("Add Profile", color = Color.White, fontWeight = FontWeight.Normal, fontFamily = UiRegularFontFamily, fontSize = 12.sp) }

            NipoSectionToggle(
                label = "Profiles",
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
                        .background(simorghInsetSurface(LocalAppIsDarkTheme.current))
                        .border(1.dp, simorghOutline(LocalAppIsDarkTheme.current), RoundedCornerShape(24.dp))
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
                                .background(simorghInsetSurface(LocalAppIsDarkTheme.current))
                                .border(1.dp, simorghOutline(LocalAppIsDarkTheme.current), RoundedCornerShape(24.dp))
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
                                colors = simorghAdaptiveButtonColors(),
                                border = BorderStroke(1.dp, simorghOutline(LocalAppIsDarkTheme.current))
                            ) { Text("Save Profile", color = Color.White, fontWeight = FontWeight.Bold, fontFamily = UiBoldFontFamily, fontSize = 12.sp) }
                            Button(
                                onClick = {
                                    showProfileEditor = false
                                    onDeleteProfile()
                                },
                                enabled = !state.nipoConnecting && !state.nipoConnected && state.nipoSelectedProfile.isNotBlank(),
                                shape = RoundedCornerShape(999.dp),
                                modifier = Modifier.weight(1f),
                                colors = simorghAdaptiveButtonColors(),
                                border = BorderStroke(1.dp, simorghOutline(LocalAppIsDarkTheme.current))
                            ) { Text("Delete", color = Color.White.copy(alpha = .88f), fontWeight = FontWeight.Bold, fontFamily = UiBoldFontFamily, fontSize = 12.sp) }
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
                    colors = simorghAdaptiveButtonColors(),
                    border = BorderStroke(1.dp, simorghOutline(LocalAppIsDarkTheme.current))
                ) { Text("Ping", color = Color.White, fontWeight = FontWeight.Normal, fontFamily = UiRegularFontFamily) }
                Button(
                    onClick = onReset,
                    enabled = !state.nipoConnecting && !state.nipoConnected,
                    shape = RoundedCornerShape(999.dp),
                    modifier = Modifier.weight(1f),
                    colors = simorghAdaptiveButtonColors(),
                    border = BorderStroke(1.dp, simorghOutline(LocalAppIsDarkTheme.current))
                ) { Text("Reset", color = Color.White, fontWeight = FontWeight.Normal, fontFamily = UiRegularFontFamily) }
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
            containerColor = if (selected) simorghSelectedSurface(LocalAppIsDarkTheme.current) else simorghControlSurface(LocalAppIsDarkTheme.current),
            contentColor = simorghPrimaryText(LocalAppIsDarkTheme.current)
        ),
        border = BorderStroke(1.dp, if (selected) LocalSectionAccent.current.copy(alpha = .52f) else simorghOutline(LocalAppIsDarkTheme.current))
    ) {
        Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Normal, fontFamily = UiRegularFontFamily, maxLines = 1)
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(simorghInsetSurface(LocalAppIsDarkTheme.current))
            .border(1.dp, simorghOutline(LocalAppIsDarkTheme.current), RoundedCornerShape(24.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Text("Profiles", color = Color.White.copy(alpha = .82f), fontSize = 11.sp, fontWeight = FontWeight.Normal, fontFamily = UiRegularFontFamily)
        if (profiles.isEmpty()) {
            LiquidBubbleParagraph("No NipoVPN profiles yet. Paste a nipovpn:// link and tap Add Profile.", modifier = Modifier.fillMaxWidth(), centered = false, maxLines = 2)
        } else {
            profiles.take(12).forEach { profile ->
                val isSelected = profile == selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(if (isSelected) simorghSelectedSurface(LocalAppIsDarkTheme.current) else simorghInsetSurface(LocalAppIsDarkTheme.current))
                        .border(1.dp, if (isSelected) LocalSectionAccent.current.copy(alpha = .56f) else simorghOutline(LocalAppIsDarkTheme.current), RoundedCornerShape(18.dp))
                        .clickable(enabled = !locked) { onSelect(profile) }
                        .padding(start = 12.dp, end = 7.dp, top = 7.dp, bottom = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(profile, color = Color.White, fontWeight = FontWeight.Normal, fontFamily = UiRegularFontFamily, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                        if (isSelected) Text("SELECTED", color = Color.White.copy(alpha = .64f), fontSize = 8.sp, fontWeight = FontWeight.Normal, fontFamily = UiRegularFontFamily)
                    }
                    Button(
                        onClick = { onEdit(profile) },
                        enabled = !locked,
                        shape = RoundedCornerShape(999.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp),
                        colors = simorghAdaptiveButtonColors(),
                        border = BorderStroke(1.dp, simorghOutline(LocalAppIsDarkTheme.current))
                    ) {
                        Text("Edit", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Normal, fontFamily = UiRegularFontFamily)
                    }
                    Button(
                        onClick = {
                            val link = profileExports[profile].orEmpty()
                            clipboard.setText(AnnotatedString(link.ifBlank { "nipovpn://" }))
                        },
                        shape = RoundedCornerShape(999.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp),
                        colors = simorghAdaptiveButtonColors(),
                        border = BorderStroke(1.dp, simorghOutline(LocalAppIsDarkTheme.current))
                    ) {
                        Text("Copy", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Normal, fontFamily = UiRegularFontFamily)
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
        textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontFamily = if (multiLine) FontFamily.Monospace else UiRegularFontFamily),
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
            containerColor = if (value) simorghSelectedSurface(LocalAppIsDarkTheme.current) else simorghControlSurface(LocalAppIsDarkTheme.current),
            contentColor = simorghPrimaryText(LocalAppIsDarkTheme.current),
            disabledContainerColor = simorghControlSurface(LocalAppIsDarkTheme.current).copy(alpha = .62f),
            disabledContentColor = simorghSecondaryText(LocalAppIsDarkTheme.current).copy(alpha = .75f)
        ),
        border = BorderStroke(1.dp, if (value) LocalSectionAccent.current.copy(alpha = .52f) else simorghOutline(LocalAppIsDarkTheme.current))
    ) {
        val toggleText = if (value) "$label: ON" else "$label: OFF"
        Text(toggleText, color = Color.White, fontWeight = FontWeight.Bold, fontFamily = UiBoldFontFamily, fontSize = 11.sp, maxLines = 1)
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
                            Color.White.copy(alpha = .060f),
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
                    .background(Color.Black.copy(alpha = .075f))
                    .border(1.dp, Color.White.copy(alpha = .20f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                CompositionLocalProvider(LocalContentColor provides Color.White) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text(if (active) "■" else "▶", color = Color.White, fontSize = if (active) 34.sp else 40.sp, fontWeight = FontWeight.ExtraBold, fontFamily = UiBoldFontFamily)
                        Spacer(Modifier.height(5.dp))
                        Text(
                            when {
                                state.nipoConnected -> "DISCONNECT"
                                state.nipoConnecting -> "CONNECTING"
                                else -> "CONNECT"
                            },
                            color = Color.White,
                            fontWeight = FontWeight.ExtraBold, fontFamily = UiBoldFontFamily,
                            fontSize = 9.sp,
                            letterSpacing = .30.sp,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SimpleXrayConnectOrb(
    state: SimorghPublicState,
    color: Color,
    onConnect: () -> Unit,
    enabled: Boolean = true,
    selectionNeeded: Boolean = false
) {
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
                            Color.White.copy(alpha = .060f),
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
                .graphicsLayer { alpha = if (enabled) 1f else .62f }
                .clickable(enabled = enabled) { runCatching { onConnect() } },
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
                        Text(if (active) "■" else "▶", color = Color.White, fontSize = if (active) 34.sp else 40.sp, fontWeight = FontWeight.ExtraBold, fontFamily = UiBoldFontFamily)
                        Spacer(Modifier.height(5.dp))
                        Text(
                            when {
                                state.simpleConnected -> "DISCONNECT"
                                state.simpleConnecting -> "CONNECTING"
                                selectionNeeded -> "SELECT CONFIG"
                                else -> "CONNECT"
                            },
                            color = Color.White,
                            fontWeight = FontWeight.ExtraBold, fontFamily = UiBoldFontFamily,
                            fontSize = if (selectionNeeded) 8.sp else 9.sp,
                            letterSpacing = .30.sp,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}


@Composable
private fun PublicHeader(
    state: SimorghPublicState,
    currentPage: String,
    showSettingsButton: Boolean,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = LocalSectionAccent.current
    val shape = RoundedCornerShape(30.dp)
    Card(
        modifier = modifier.fillMaxWidth().shadow(4.dp, shape),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = simorghCardSurface(true))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 15.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                val simorghHeaderFont = FontFamily(Font(R.font.progress_personal_use))
                Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "SIMORGH",
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Normal,
                        fontFamily = simorghHeaderFont,
                        letterSpacing = 1.2.sp,
                        color = Color.White.copy(alpha = .98f)
                    )
                }
                if (currentPage != "home") Text(
                    text = when (currentPage) {
                        "simple" -> when {
                            state.simpleConnected -> "Free V2Ray Connected"
                            state.simpleConnecting -> "Free V2Ray Connecting"
                            else -> "Free V2Ray Disconnected"
                        }
                        "serverless" -> "ServerLess"
                        "nipo" -> when {
                            state.nipoConnected -> "NipoVPN Connected"
                            state.nipoConnecting -> "NipoVPN Connecting"
                            else -> "NipoVPN Disconnected"
                        }
                        "fragment" -> when {
                            state.fragmentConnected -> "Fragment Connected"
                            state.fragmentConnecting -> "Fragment Connecting"
                            else -> "Fragment Disconnected"
                        }
                        "stormdns" -> when {
                            state.stormDnsConnected -> "MasterDNS Connected"
                            state.stormDnsConnecting -> "MasterDNS Connecting"
                            else -> "MasterDNS Disconnected"
                        }
                        "desync" -> "Desync"
                        else -> when {
                            state.connected -> "MSP Connected"
                            state.connecting -> "MSP Connecting"
                            else -> "MSP Disconnected"
                        }
                    },
                    color = accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (showSettingsButton) HeaderRoundButton(text = "⚙", contentDescription = "Settings", onClick = onOpenSettings)
            }
        }
    }
}

@Composable
private fun MspSettingsShortcut(onClick: () -> Unit) {
    val accent = LocalSectionAccent.current
    val shape = RoundedCornerShape(22.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(2.dp, shape)
            .clip(shape)
            .background(simorghCardSurface(true))
            .border(1.dp, simorghOutline(true), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("⚙", color = accent, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text("MSP Configuration", color = simorghPrimaryText(true), fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = UiBoldFontFamily)
            Text("ISP • SNI • Scan • Proxy • Route", color = simorghSecondaryText(true), fontSize = 10.sp, fontFamily = UiRegularFontFamily)
        }
        Text("›", color = accent, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun HeaderRoundButton(text: String, contentDescription: String = text, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = CircleShape,
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier
            .size(48.dp)
            .shadow(8.dp, CircleShape, ambientColor = Color.White.copy(alpha = .06f), spotColor = Color.White.copy(alpha = .05f))
            .semantics { this.contentDescription = contentDescription },
        colors = ButtonDefaults.buttonColors(
            containerColor = simorghControlSurface(true),
            contentColor = simorghPrimaryText(true)
        ),
        border = BorderStroke(1.dp, simorghOutline(true))
    ) {
        Text(text, fontSize = if (text.length > 1) 16.sp else 21.sp, color = simorghPrimaryText(true), fontWeight = FontWeight.Black, fontFamily = UiBoldFontFamily)
    }
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
    val color = if (state.connected) Color(0xFF22C55E) else LocalSectionAccent.current
    GlassCard {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                LiquidBubbleText("MSP", big = true)
                LiquidBubbleText(if (state.selectedRunMode == "vpn") "VPN Mode" else "Proxy Mode", big = false)
            }
            Spacer(Modifier.height(8.dp))
            SpeedMiniBubble(state)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeButton("Proxy", state.selectedRunMode == "proxy", Modifier.weight(1f)) { onModeSelected("proxy") }
                ModeButton("VPN", state.selectedRunMode == "vpn", Modifier.weight(1f)) { onModeSelected("vpn") }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                InfoText(state.selectedProxyProtocol.uppercase(), "127.0.0.1:${state.proxyPort}", Modifier.fillMaxWidth(.64f))
            }
            Spacer(Modifier.height(8.dp))
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
                            Brush.linearGradient(
                                listOf(
                                    Color.White.copy(alpha = .62f),
                                    color.copy(alpha = .70f),
                                    Color.White.copy(alpha = .22f)
                                )
                            ),
                            CircleShape
                        )
                        .clickable { runCatching { if (active) onStop() else onConnect() } },
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
                                Text(if (active) "■" else "▶", color = Color.White, fontSize = if (active) 34.sp else 40.sp, fontWeight = FontWeight.ExtraBold, fontFamily = UiBoldFontFamily)
                                Spacer(Modifier.height(5.dp))
                                Text(
                                    when {
                                        state.connected -> "DISCONNECT"
                                        state.connecting -> "CONNECTING"
                                        state.selectedRunMode == "vpn" -> "CONNECT VPN"
                                        else -> "CONNECT PROXY"
                                    },
                                    color = Color.White,
                                    fontWeight = FontWeight.ExtraBold, fontFamily = UiBoldFontFamily,
                                    fontSize = 9.sp,
                                    letterSpacing = .30.sp,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
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
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) simorghSelectedSurface(LocalAppIsDarkTheme.current) else simorghControlSurface(LocalAppIsDarkTheme.current),
            contentColor = simorghPrimaryText(LocalAppIsDarkTheme.current)
        ),
        border = BorderStroke(1.dp, if (selected) LocalSectionAccent.current.copy(alpha = .52f) else simorghOutline(LocalAppIsDarkTheme.current)),
        contentPadding = PaddingValues(vertical = 11.dp)
    ) {
        Text(text, color = Color.White.copy(alpha = if (selected) .96f else .76f), fontWeight = FontWeight.ExtraBold, fontFamily = UiBoldFontFamily, letterSpacing = .2.sp)
    }
}

private fun mspPublicManualText(state: SimorghPublicState): String {
    val parts = mutableListOf<String>()
    // در نسخه Public فقط ورودی دستیِ فعال قابل نمایش واقعی است.
    // دیتاهای باقی‌مانده از نسخه‌های قدیمی یا IPهای اسکنر MSP نباید باعث نمایش IP خام شوند.
    if (state.manualIpMode && state.manualIpsText.trim().isNotBlank()) parts += state.manualIpsText.trim()
    if (state.ispManualRangeMode && state.ispManualRangeText.trim().isNotBlank()) parts += state.ispManualRangeText.trim()
    return parts.joinToString("\n")
}

private fun mspPublicHasManualInput(state: SimorghPublicState): Boolean {
    return mspPublicManualText(state).trim().isNotBlank()
}

private fun mspPublicIpAlias(index: Int): String = "IP-${index + 1}"

private fun mspPublicConfigAlias(index: Int): String = "CONFIG-${index + 1}"

private fun mspPublicIpv4ToLong(ip: String): Long? {
    val parts = ip.trim().split('.')
    if (parts.size != 4) return null
    var value = 0L
    for (part in parts) {
        val n = part.toIntOrNull() ?: return null
        if (n !in 0..255) return null
        value = (value shl 8) or n.toLong()
    }
    return value and 0xFFFFFFFFL
}

private fun mspPublicManualContainsIp(ip: String, state: SimorghPublicState): Boolean {
    val target = mspPublicIpv4ToLong(ip) ?: return false
    val text = mspPublicManualText(state)
    if (text.isBlank()) return false

    Regex("""\b((?:\d{1,3}\.){3}\d{1,3})\s*/\s*(\d{1,2})\b""").findAll(text).forEach { match ->
        val base = mspPublicIpv4ToLong(match.groupValues[1]) ?: return@forEach
        val prefix = match.groupValues[2].toIntOrNull() ?: return@forEach
        if (prefix !in 0..32) return@forEach
        val mask = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        if ((target and mask) == (base and mask)) return true
    }

    Regex("""\b((?:\d{1,3}\.){3}\d{1,3})\s*(?:-|–|—)\s*((?:\d{1,3}\.){3}\d{1,3})\b""").findAll(text).forEach { match ->
        val a = mspPublicIpv4ToLong(match.groupValues[1]) ?: return@forEach
        val b = mspPublicIpv4ToLong(match.groupValues[2]) ?: return@forEach
        val minIp = kotlin.math.min(a, b)
        val maxIp = kotlin.math.max(a, b)
        if (target in minIp..maxIp) return true
    }

    Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""").findAll(text).forEach { match ->
        if (mspPublicIpv4ToLong(match.value) == target) return true
    }

    return false
}

private fun mspPublicAliasFor(ip: String, ips: List<String>, state: SimorghPublicState, config: Boolean = false): String {
    if (mspPublicManualContainsIp(ip, state)) return ip
    val index = ips.indexOf(ip).takeIf { it >= 0 } ?: 0
    return if (config) mspPublicConfigAlias(index) else mspPublicIpAlias(index)
}

private fun mspPublicMaskText(text: String, ips: List<String>, state: SimorghPublicState, config: Boolean = false): String {
    var masked = text
    ips.forEachIndexed { index, ip ->
        if (ip.isNotBlank() && !mspPublicManualContainsIp(ip, state)) {
            masked = masked.replace(ip, if (config) mspPublicConfigAlias(index) else mspPublicIpAlias(index))
        }
    }
    val fallback = if (config) "CONFIG" else "IP"
    return Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""").replace(masked) { match ->
        if (mspPublicManualContainsIp(match.value, state)) match.value else fallback
    }
}

private fun mspPublicSortedMemoryIps(state: SimorghPublicState): List<String> {
    return state.savedCleanIps.sortedWith(
        compareBy<String> { ip -> state.savedCleanIpPings[ip]?.takeIf { it >= 0L } ?: Long.MAX_VALUE }
            .thenBy { it }
    )
}

private fun mspPublicScanOnlyStatusText(state: SimorghPublicState): String {
    val total = state.totalCandidates
    val scanned = state.scannedCount
    val clean = state.cleanIpCount
    val saved = state.savedCleanIps.size
    return when {
        total > 0 && scanned < total -> "Scan configs: $scanned/$total • Clean $clean • Saved $saved"
        total > 0 -> "Scan configs finished: $scanned/$total • Clean $clean • Saved $saved"
        clean > 0 || saved > 0 -> "Scan configs idle • Clean $clean • Saved $saved"
        else -> "Scan configs idle • no clean IPs yet"
    }
}

@Composable
private fun PublicStatusCard(state: SimorghPublicState, onNextRouteIp: () -> Unit, onClearSavedCleanIps: () -> Unit) {
    val selectedIspSet = state.selectedIsps.ifEmpty { setOf(state.selectedIsp) }
    val customIsp = selectedIspSet != setOf("AbrArvan CDN and IaaS")
    val customSni = state.selectedSnis != setOf("chatgpt.com")
    val hasCustomInfo = customIsp || customSni || state.manualIpMode || state.ispManualRangeMode
    val memoryAliasIps = remember(state.savedCleanIps, state.savedCleanIpPings) { mspPublicSortedMemoryIps(state) }
    GlassCard {
        LiquidBubbleText("MSP Route", big = true)
        if (hasCustomInfo) {
            Spacer(Modifier.height(8.dp))
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (customIsp) MspRouteBubble("ISP: ${selectedIspSet.joinToString(", ")}")
                if (customSni) MspRouteBubble("SNI: ${state.selectedSnis.joinToString(", ")}")
                if (state.manualIpMode) MspRouteBubble("Manual Clean IPs: ON • ${state.manualCandidateCount} candidates")
                if (state.ispManualRangeMode) MspRouteBubble("ISP Manual Range: ON • ${state.ispManualRangeCandidateCount} scan candidates")
            }
            Spacer(Modifier.height(8.dp))
        } else {
            LiquidBubbleText("Default SIMORGH route profile is active", big = false, fontFamily = UiRegularFontFamily, fontWeight = FontWeight.Normal)
            Spacer(Modifier.height(8.dp))
        }
        InfoText("Scanned", "${state.scannedCount}/${state.totalCandidates.takeIf { it > 0 } ?: "…"}", Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            InfoText("Clean", state.cleanIpCount.toString(), Modifier.weight(1f))
            InfoText("Saved", state.savedCleanIps.size.toString(), Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            InfoText("Active IP", (state.activeRouteIp.takeIf { it.isNotBlank() }?.let { ip -> mspPublicAliasFor(ip, memoryAliasIps, state) } ?: "—") + if (state.activeRoutePingMs >= 0L) " • ${state.activeRoutePingMs}ms" else "", Modifier.weight(1f))
            Button(
                onClick = onNextRouteIp,
                enabled = state.savedCleanIps.isNotEmpty() || state.cleanIpCount > 1,
                shape = RoundedCornerShape(999.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp),
                colors = simorghAdaptiveButtonColors(),
                border = BorderStroke(1.dp, simorghOutline(LocalAppIsDarkTheme.current))
            ) { Text("Next IP", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Normal, fontFamily = UiRegularFontFamily) }
            Button(
                onClick = onClearSavedCleanIps,
                enabled = state.savedCleanIps.isNotEmpty() || state.cleanIpCount > 0 || state.activeRouteIp.isNotBlank(),
                shape = RoundedCornerShape(999.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp),
                colors = simorghAdaptiveButtonColors(),
                border = BorderStroke(1.dp, simorghOutline(LocalAppIsDarkTheme.current))
            ) { Text("Clear IPs", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Normal, fontFamily = UiRegularFontFamily) }
        }
        Spacer(Modifier.height(8.dp))
        LiquidBubbleParagraph(mspPublicMaskText(state.status, memoryAliasIps, state), modifier = Modifier.fillMaxWidth(), centered = false, maxLines = 2)
    }
}

@Composable
private fun MspRouteBubble(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(999.dp))
            .background(simorghInsetSurface(LocalAppIsDarkTheme.current))
            .border(1.dp, simorghOutline(LocalAppIsDarkTheme.current), RoundedCornerShape(999.dp))
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(text, color = Color.White.copy(alpha = .82f), fontSize = 12.sp, fontWeight = FontWeight.Normal, fontFamily = UiRegularFontFamily, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PublicLiveSpeedCard(state: SimorghPublicState) {
    val sectionAccent = LocalSectionAccent.current
    val transition = rememberInfiniteTransition(label = "speedModernFlow")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1700), RepeatMode.Restart),
        label = "speedModernPhase"
    )
    val glow by transition.animateFloat(
        initialValue = .42f,
        targetValue = .92f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
        label = "speedModernGlow"
    )
    val downText = FormatUtils.kbps(state.downloadKbps)
    val upText = FormatUtils.kbps(state.uploadKbps)
    GlassCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Live Speed", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = UiBoldFontFamily)
                Text(
                    "Total device traffic monitor",
                    color = Color.White.copy(alpha = .66f),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("↓ $downText", color = sectionAccent, fontWeight = FontWeight.Normal, fontFamily = UiRegularFontFamily, fontSize = 12.sp, maxLines = 1)
                Text("↑ $upText", color = Color(0xFFFF7A2F), fontWeight = FontWeight.Normal, fontFamily = UiRegularFontFamily, fontSize = 12.sp, maxLines = 1)
            }
        }
        Spacer(Modifier.height(12.dp))
        Canvas(Modifier.fillMaxWidth().height(112.dp)) {
            val radius = androidx.compose.ui.geometry.CornerRadius(36f, 36f)
            drawRoundRect(brush = Brush.verticalGradient(listOf(Color.White.copy(.13f), Color.White.copy(.035f), Color.Transparent)), cornerRadius = radius)
            repeat(5) { i ->
                val yy = size.height * (i + 1) / 6f
                drawLine(Color.White.copy(alpha = 0.08f), Offset(0f, yy), Offset(size.width, yy), 1.1f)
            }
            val peak = maxOf(64f, state.downloadKbps.toFloat(), state.uploadKbps.toFloat())
            val downLevel = (state.downloadKbps.toFloat() / peak).coerceIn(0f, 1f)
            val upLevel = (state.uploadKbps.toFloat() / peak).coerceIn(0f, 1f)
            val bars = 32
            val gap = 3.5f
            val barW = ((size.width - gap * (bars - 1)) / bars).coerceAtLeast(3f)
            repeat(bars) { i ->
                val x = i * (barW + gap)
                val wave = ((sin((i / 3.2f + phase * 6.28f).toDouble()).toFloat() + 1f) / 2f)
                val downH = (12f + (size.height * .56f) * (downLevel * .72f + wave * .28f)).coerceIn(10f, size.height * .70f)
                val upH = (8f + (size.height * .32f) * (upLevel * .74f + (1f - wave) * .26f)).coerceIn(7f, size.height * .44f)
                val base = size.height * .82f
                drawRoundRect(
                    color = sectionAccent.copy(alpha = .30f + .38f * glow),
                    topLeft = Offset(x, base - downH),
                    size = Size(barW, downH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(barW, barW)
                )
                drawRoundRect(
                    color = Color(0xFFFF7A2F).copy(alpha = .18f + .30f * glow),
                    topLeft = Offset(x, base - upH * .45f),
                    size = Size(barW, upH * .45f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(barW, barW)
                )
            }
            val orbX = size.width * ((phase + .08f) % 1f)
            val orbY = size.height * (.26f + .10f * sin((phase * 6.28f).toDouble()).toFloat())
            drawCircle(Color.White.copy(alpha = .10f + .12f * glow), 20f, Offset(orbX, orbY))
            drawCircle(sectionAccent.copy(alpha = .35f * glow), 9f, Offset(orbX, orbY))
        }
    }
}

@Composable
private fun PublicCleanIpsCard(state: SimorghPublicState, onClearSavedCleanIps: () -> Unit, onPingSavedCleanIps: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val sortedMemoryIps = remember(state.savedCleanIps, state.savedCleanIpPings) { mspPublicSortedMemoryIps(state) }
    val savedText = sortedMemoryIps.mapIndexed { index, ip -> if (mspPublicManualContainsIp(ip, state)) ip else mspPublicIpAlias(index) }.joinToString("\n")
    var expanded by remember { mutableStateOf(false) }
    GlassCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                LiquidBubbleText("IP Memory", big = true)
                Spacer(Modifier.height(7.dp))
                LiquidBubbleText("${state.savedCleanIps.size} clean IPs • ping shown when available", big = false)
            }
            Button(onClick = { expanded = !expanded }, shape = RoundedCornerShape(999.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), colors = simorghAdaptiveButtonColors()) { Text(if (expanded) "Hide" else "Show", color = Color.White, fontSize = 12.sp) }
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPingSavedCleanIps, enabled = state.savedCleanIps.isNotEmpty(), shape = RoundedCornerShape(999.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), colors = ButtonDefaults.buttonColors(containerColor = LocalSectionAccent.current.copy(alpha = .90f))) { Text("Ping All", color = Color.White, fontSize = 12.sp) }
                Button(onClick = { clipboard.setText(AnnotatedString(savedText)) }, enabled = savedText.isNotBlank(), shape = RoundedCornerShape(999.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), colors = simorghAdaptiveButtonColors()) { Text("Copy", color = Color.White, fontSize = 12.sp) }
                Button(onClick = onClearSavedCleanIps, enabled = state.savedCleanIps.isNotEmpty(), shape = RoundedCornerShape(999.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935).copy(alpha = .82f))) { Text("Clear", color = Color.White, fontSize = 12.sp) }
            }
            Spacer(Modifier.height(8.dp))
            SelectionContainer {
                val sortedIps = sortedMemoryIps
                Column(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().background(simorghInsetSurface(LocalAppIsDarkTheme.current), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 7.dp)) {
                        Text("IP", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = UiBoldFontFamily, modifier = Modifier.weight(1f))
                        Text("Latency", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = UiBoldFontFamily, textAlign = TextAlign.End, modifier = Modifier.width(82.dp))
                    }
                    Spacer(Modifier.height(6.dp))
                    LazyColumn(modifier = Modifier.fillMaxWidth().height(170.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (sortedIps.isEmpty()) {
                            item { Text("No saved clean IPs yet.", color = Color.White.copy(alpha = .78f), fontSize = 11.sp) }
                        } else {
                            items(sortedIps.withIndex().toList(), key = { it.value }) { indexed ->
                                val ip = indexed.value
                                val ping = state.savedCleanIpPings[ip]
                                val displayIp = if (mspPublicManualContainsIp(ip, state)) ip else mspPublicIpAlias(indexed.index)
                                Row(Modifier.fillMaxWidth().background(simorghInsetSurface(LocalAppIsDarkTheme.current), RoundedCornerShape(9.dp)).padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(displayIp, color = Color.White.copy(alpha = .88f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
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

private data class TunnelAppUi(val label: String, val packageName: String)

@Composable
private fun AppSettingsDialog(
    onDismiss: () -> Unit,
    onOpenTunnelApps: () -> Unit,
    onCheckForUpdates: () -> Unit
) {
    MaterialTheme(typography = Typography()) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("App Settings", color = Color.White, fontWeight = FontWeight.Bold, fontFamily = UiBoldFontFamily) },
            containerColor = simorghCardSurface(LocalAppIsDarkTheme.current),
            titleContentColor = simorghPrimaryText(LocalAppIsDarkTheme.current),
            textContentColor = simorghPrimaryText(LocalAppIsDarkTheme.current),
            shape = RoundedCornerShape(30.dp),
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(25.dp))
                        .background(simorghInsetSurface(LocalAppIsDarkTheme.current))
                        .border(1.dp, simorghOutline(LocalAppIsDarkTheme.current), RoundedCornerShape(25.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    AppSettingsRow(
                        icon = "⛙",
                        title = "Tunnel Apps",
                        subtitle = "Choose which apps use this section's VPN tunnel",
                        onClick = onOpenTunnelApps
                    )
                    AppSettingsRow(
                        icon = "↻",
                        title = "Check for updates",
                        subtitle = "Look for the latest SIMORGH release on GitHub",
                        onClick = onCheckForUpdates
                    )
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = LocalSectionAccent.current) } }
        )
    }
}

@Composable
private fun AppSettingsRow(icon: String, title: String, subtitle: String, onClick: () -> Unit) {
    val accent = LocalSectionAccent.current
    val shape = RoundedCornerShape(19.dp)
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(simorghInsetSurface(LocalAppIsDarkTheme.current))
                .border(1.dp, simorghOutline(LocalAppIsDarkTheme.current), shape)
                .clickable(onClick = onClick)
                .padding(horizontal = 13.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(39.dp).clip(CircleShape).background(accent.copy(alpha = .14f)).border(1.dp, accent.copy(alpha = .25f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(icon, color = accent, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = UiBoldFontFamily)
                Spacer(Modifier.height(2.dp))
                Text(subtitle, color = Color.White.copy(alpha = .64f), fontSize = 10.sp, lineHeight = 14.sp, fontFamily = UiRegularFontFamily)
            }
            Spacer(Modifier.width(6.dp))
            Text("›", color = accent.copy(alpha = .9f), fontSize = 23.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun AppUpdateDialog(
    installedVersion: String,
    latestVersion: String,
    update: SimorghUpdate?,
    checking: Boolean,
    downloading: Boolean,
    downloadedApk: File?,
    message: String,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onOpenRelease: () -> Unit,
    onRetry: () -> Unit
) {
    val accent = LocalSectionAccent.current
    MaterialTheme(typography = Typography()) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("SIMORGH Update", color = Color.White, fontWeight = FontWeight.Bold, fontFamily = UiBoldFontFamily) },
            containerColor = simorghCardSurface(LocalAppIsDarkTheme.current),
            titleContentColor = simorghPrimaryText(LocalAppIsDarkTheme.current),
            textContentColor = simorghPrimaryText(LocalAppIsDarkTheme.current),
            shape = RoundedCornerShape(30.dp),
            text = {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
                        .background(simorghInsetSurface(LocalAppIsDarkTheme.current))
                        .border(1.dp, simorghOutline(LocalAppIsDarkTheme.current), RoundedCornerShape(24.dp))
                        .padding(15.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    when {
                        checking -> Text("Checking the latest GitHub release…", color = Color.White.copy(alpha = .86f), fontSize = 13.sp)
                        update != null -> {
                            Text("Version ${update.version} is available", color = accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            if (installedVersion.isNotBlank()) Text("Installed version: $installedVersion", color = Color.White.copy(alpha = .68f), fontSize = 11.sp)
                            Text("Download the APK, then confirm installation in Android's installer.", color = Color.White.copy(alpha = .78f), fontSize = 12.sp, lineHeight = 17.sp)
                        }
                        latestVersion.isNotBlank() -> Text("You're using the latest version ($latestVersion).", color = Color.White.copy(alpha = .86f), fontSize = 13.sp)
                        else -> Text("Check the SIMORGH GitHub releases for a newer version.", color = Color.White.copy(alpha = .86f), fontSize = 13.sp)
                    }
                    if (downloading) Text("Downloading and verifying the APK…", color = accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    else if (downloadedApk != null) Text("APK downloaded. Tap Install update when ready.", color = accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    if (message.isNotBlank()) Text(message, color = Color.White.copy(alpha = .70f), fontSize = 11.sp, lineHeight = 15.sp)
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Later", color = Color.White.copy(alpha = .78f)) } },
            confirmButton = {
                when {
                    checking || downloading -> TextButton(onClick = {}, enabled = false) { Text("Please wait", color = Color.White.copy(alpha = .45f)) }
                    downloadedApk != null -> Button(onClick = onInstall, shape = RoundedCornerShape(999.dp), colors = ButtonDefaults.buttonColors(containerColor = accent.copy(alpha = .78f))) { Text("Install update", color = Color.White, fontWeight = FontWeight.Bold) }
                    update?.downloadUrl != null -> Button(onClick = onDownload, shape = RoundedCornerShape(999.dp), colors = ButtonDefaults.buttonColors(containerColor = accent.copy(alpha = .78f))) { Text("Download & install", color = Color.White, fontWeight = FontWeight.Bold) }
                    update != null -> Button(onClick = onOpenRelease, shape = RoundedCornerShape(999.dp), colors = ButtonDefaults.buttonColors(containerColor = accent.copy(alpha = .78f))) { Text("Open release", color = Color.White, fontWeight = FontWeight.Bold) }
                    else -> TextButton(onClick = onRetry) { Text("Check again", color = accent) }
                }
            }
        )
    }
}

@Composable
private fun TunnelAppsDialog(
    state: SimorghPublicState,
    sectionKey: String,
    sectionTitle: String,
    onDismiss: () -> Unit,
    onModeChanged: (String, String) -> Unit,
    onTogglePackage: (String, String, String) -> Unit,
    onClear: (String, String) -> Unit
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<TunnelAppUi>>(emptyList()) }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            pm.getInstalledApplications(0)
                .asSequence()
                .filter { app -> pm.getLaunchIntentForPackage(app.packageName) != null }
                .map { app ->
                    val label = runCatching { pm.getApplicationLabel(app).toString() }.getOrDefault(app.packageName)
                    TunnelAppUi(label, app.packageName)
                }
                .sortedWith(compareBy<TunnelAppUi> { it.label.lowercase() }.thenBy { it.packageName })
                .toList()
        }
    }
    val sectionMode = state.tunnelAppModesBySection[sectionKey] ?: "all"
    val sectionPackages = if (sectionMode == "all") emptySet() else state.tunnelAppPackagesBySectionMode["$sectionKey:$sectionMode"] ?: emptySet()
    val filtered = remember(apps, query, sectionPackages) {
        val q = query.trim().lowercase()
        val base = if (q.isBlank()) apps else apps.filter { it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q) }
        base.sortedWith(
            compareByDescending<TunnelAppUi> { it.packageName in sectionPackages }
                .thenBy { it.label.lowercase() }
                .thenBy { it.packageName }
        )
    }
    MaterialTheme(typography = Typography()) {
        AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tunnel Apps • $sectionTitle", fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold, color = Color.White) },
        containerColor = simorghCardSurface(LocalAppIsDarkTheme.current),
        titleContentColor = simorghPrimaryText(LocalAppIsDarkTheme.current),
        textContentColor = simorghPrimaryText(LocalAppIsDarkTheme.current),
        shape = RoundedCornerShape(30.dp),
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("This rule is only for $sectionTitle. Selected apps are shown at the top.", color = Color.White.copy(alpha = .72f), fontSize = 11.sp, lineHeight = 15.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SettingTabButton("All", sectionMode == "all", Modifier.weight(1f)) { onModeChanged(sectionKey, "all") }
                    SettingTabButton("Exclude", sectionMode == "exclude", Modifier.weight(1f)) { onModeChanged(sectionKey, "exclude") }
                    SettingTabButton("Only", sectionMode == "only", Modifier.weight(1f)) { onModeChanged(sectionKey, "only") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Selected: ${sectionPackages.size}", color = Color.White.copy(alpha = .74f), fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Default)
                    Button(onClick = { onClear(sectionKey, sectionMode) }, enabled = sectionPackages.isNotEmpty(), shape = RoundedCornerShape(999.dp), modifier = Modifier.width(86.dp).height(38.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp), colors = simorghAdaptiveButtonColors(), border = BorderStroke(1.dp, simorghOutline(LocalAppIsDarkTheme.current))) { Text("Clear", color = Color.White, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Default, fontSize = 10.sp) }
                }
                OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Search apps") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                LazyColumn(modifier = Modifier.fillMaxWidth().height(330.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    items(filtered) { app ->
                        SelectableRow(
                            text = "${app.label}\n${app.packageName}",
                            selected = app.packageName in sectionPackages,
                            onClick = { onTogglePackage(sectionKey, sectionMode, app.packageName) }
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
        )
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
    onIspManualRangeModeChanged: (Boolean) -> Unit,
    onIspManualRangeChanged: (String) -> Unit,
    onMaxScanIpsChanged: (Int) -> Unit,
    onScanSpeedChanged: (String) -> Unit,
    onProxyProtocolChanged: (String) -> Unit,
    onRouteStrategyChanged: (String) -> Unit
) {
    var page by remember { mutableStateOf("ISP") }
    var ispSourceMode by remember(state.ispManualRangeMode) { mutableStateOf(if (state.ispManualRangeMode) "manual" else "isp") }
    var ispSearch by remember { mutableStateOf("") }
    var sniSearch by remember { mutableStateOf("") }
    var maxScanText by remember(state.maxScanIps) { mutableStateOf(state.maxScanIps.toString()) }
    val selectedIspSet = state.selectedIsps.ifEmpty { setOf(state.selectedIsp) }
    val filteredIsps = remember(state.ispOptions, selectedIspSet, ispSearch) {
        val q = ispSearch.trim().lowercase()
        if (q.isBlank()) state.ispOptions else state.ispOptions.filter { it.lowercase().contains(q) }
    }
    val filteredSnis = remember(state.sniOptions, state.selectedSnis, sniSearch) {
        val q = sniSearch.trim().lowercase()
        val base = if (q.isBlank()) state.sniOptions else state.sniOptions.filter { it.lowercase().contains(q) }
        val selected = state.selectedSnis.map { it.trim().lowercase() }.filter { it.isNotBlank() }
        val selectedTop = selected.filter { selectedName -> base.any { it.equals(selectedName, ignoreCase = true) } || selectedName.isNotBlank() }
        val rest = base.filterNot { option -> selected.any { it.equals(option, ignoreCase = true) } }
        (selectedTop + rest).distinctBy { it.lowercase() }
    }

    MaterialTheme(typography = Typography()) {
        AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings", fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold, color = Color.White) },
        containerColor = simorghCardSurface(LocalAppIsDarkTheme.current),
        titleContentColor = simorghPrimaryText(LocalAppIsDarkTheme.current),
        textContentColor = simorghPrimaryText(LocalAppIsDarkTheme.current),
        shape = RoundedCornerShape(30.dp),
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(26.dp))
                    .background(simorghInsetSurface(LocalAppIsDarkTheme.current))
                    .border(1.dp, simorghOutline(LocalAppIsDarkTheme.current), RoundedCornerShape(26.dp))
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
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SettingTabButton("Selected ISP", ispSourceMode == "isp", Modifier.weight(1f)) {
                                ispSourceMode = "isp"
                                onIspManualRangeModeChanged(false)
                            }
                            SettingTabButton("Manual IP/Range", ispSourceMode == "manual", Modifier.weight(1f)) {
                                ispSourceMode = "manual"
                                onIspManualRangeModeChanged(true)
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        if (ispSourceMode == "isp") {
                            Text("Selected ISPs", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Default)
                            Text(selectedIspSet.joinToString(", "), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            Text("Tap an ISP to select or deselect it. Selected ISPs stay at the top of the list.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 15.sp)
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(value = ispSearch, onValueChange = { ispSearch = it }, label = { Text("Search ISP") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(8.dp))
                            LazyColumn(modifier = Modifier.fillMaxWidth().height(300.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(items = filteredIsps, key = { it.lowercase() }) { isp -> SelectableRow(text = isp, selected = selectedIspSet.any { it.equals(isp, ignoreCase = true) }, onClick = { onIspSelected(isp) }) }
                            }
                        } else {
                            Text("Manual IP / Range scan", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Default)
                            Text("Add IPv4, CIDR or range. Examples: 1.2.3.4, 1.2.3.0/24, 1.2.3.10-50, 1.2.3.10-1.2.3.80", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 15.sp)
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = state.ispManualRangeText,
                                onValueChange = onIspManualRangeChanged,
                                label = { Text("Manual IP / CIDR / Range") },
                                placeholder = { Text("203.0.113.10\n198.51.100.0/24\n192.0.2.10-50") },
                                modifier = Modifier.fillMaxWidth().height(260.dp),
                                minLines = 8,
                                maxLines = 12
                            )
                            Spacer(Modifier.height(6.dp))
                            Text("MSP scan candidates: ${state.ispManualRangeCandidateCount}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    "SNI" -> Column(Modifier.fillMaxWidth()) {
                        Text("Selected SNI", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Default)
                        Text(state.selectedSnis.joinToString(", "), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("Selected SNI items stay pinned at the top of the list.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 15.sp)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(value = sniSearch, onValueChange = { sniSearch = it }, label = { Text("Search SNI") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        LazyColumn(modifier = Modifier.fillMaxWidth().height(300.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(items = filteredSnis, key = { it.lowercase() }) { sni -> SelectableRow(text = sni, selected = state.selectedSnis.any { it.equals(sni, ignoreCase = true) }, onClick = { onToggleSni(sni) }) }
                        }
                    }
                    "Manual" -> Column(Modifier.fillMaxWidth()) {
                        Text("Manual IPs", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Default)
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
                        Text("Scan Performance", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Default)
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
                        Text("Proxy Protocol", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Default)
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
                        Text("Routing Strategy", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Default)
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
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done", fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold) } }
        )
    }
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
            .background(if (selected) simorghSelectedSurface(LocalAppIsDarkTheme.current) else simorghControlSurface(LocalAppIsDarkTheme.current))
            .border(
                1.dp,
                if (selected) LocalSectionAccent.current.copy(alpha = .56f) else simorghOutline(LocalAppIsDarkTheme.current),
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
            fontFamily = FontFamily.Default,
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
            .background(if (selected) Color(0xFF00E676).copy(alpha = if (LocalAppIsDarkTheme.current) .18f else .14f) else simorghControlSurface(LocalAppIsDarkTheme.current))
            .border(
                1.dp,
                if (selected) Color(0xFF00A85A).copy(alpha = .75f) else simorghOutline(LocalAppIsDarkTheme.current),
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
            fontFamily = FontFamily.Default,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (selected) {
            Text("✓", color = Color.White.copy(alpha = .90f), fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Default)
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
            .background(simorghInsetSurface(LocalAppIsDarkTheme.current))
            .border(1.dp, simorghOutline(LocalAppIsDarkTheme.current), RoundedCornerShape(15.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            fontFamily = FontFamily.Default,
            color = Color.White.copy(alpha = .68f),
            fontSize = 11.sp,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            value,
            fontFamily = FontFamily.Default,
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
                .background(simorghInsetSurface(LocalAppIsDarkTheme.current))
                .border(1.dp, simorghOutline(LocalAppIsDarkTheme.current), RoundedCornerShape(999.dp))
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
                fontWeight = FontWeight.Normal,
                fontFamily = UiRegularFontFamily,
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
    onCfRealDelayUrlChanged: (String) -> Unit,
    onCfPingIp: (String) -> Unit,
    onCfPingAll: () -> Unit,
    onCfConnectIp: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var realDelayMenuExpanded by remember { mutableStateOf(false) }
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
    var cfEnabledUi by remember { mutableStateOf(state.cfEnabled) }
    LaunchedEffect(state.cfEnabled) {
        if (state.cfEnabled || !cfEnabledUi) cfEnabledUi = state.cfEnabled
    }
    GlassCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                LiquidBubbleText("CF Config", big = true)
                Spacer(Modifier.height(8.dp))
                LiquidBubbleText("CloudFlare Vless WS 443", big = false, fontFamily = UiRegularFontFamily, fontWeight = FontWeight.Normal)
                Spacer(Modifier.height(8.dp))
                CfLiquidToggle(
                    enabled = cfEnabledUi,
                    onToggle = {
                        val next = !cfEnabledUi
                        cfEnabledUi = next
                        onCfEnabledChange(next)
                    }
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onCfPingAll,
                    enabled = cfEnabledUi && validVless && cfIps.isNotEmpty(),
                    shape = RoundedCornerShape(999.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    colors = simorghAdaptiveButtonColors(),
                    border = BorderStroke(1.dp, simorghOutline(LocalAppIsDarkTheme.current))
                ) { Text("Ping", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Normal, fontFamily = UiRegularFontFamily) }
                Button(
                    onClick = { expanded = !expanded },
                    shape = RoundedCornerShape(999.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    colors = simorghAdaptiveButtonColors()
                ) { Text(if (expanded) "Hide" else "Show", color = Color.White, fontSize = 12.sp) }
            }
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            Text("Real Delay URL", color = Color.White.copy(alpha = .88f), fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = UiBoldFontFamily)
            Box {
                Button(
                    onClick = { realDelayMenuExpanded = true },
                    enabled = cfEnabledUi,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = simorghAdaptiveButtonColors(),
                    border = BorderStroke(1.dp, simorghOutline(LocalAppIsDarkTheme.current))
                ) {
                    Text(state.cfRealDelayUrl, color = Color.White, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                DropdownMenu(expanded = realDelayMenuExpanded, onDismissRequest = { realDelayMenuExpanded = false }) {
                    SimorghRealDelayTargets.OPTIONS.forEach { url ->
                        DropdownMenuItem(
                            text = { Text(url, fontSize = 12.sp) },
                            onClick = {
                                realDelayMenuExpanded = false
                                onCfRealDelayUrlChanged(url)
                            }
                        )
                    }
                }
            }
            Text("Ping checks this URL through the selected CF VLESS route.", color = Color.White.copy(alpha = .58f), fontSize = 10.sp, lineHeight = 13.sp)
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
                enabled = cfEnabledUi,
                textStyle = TextStyle(fontSize = 12.sp)
            )
            if (state.cfStatus.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                LiquidBubbleParagraph(
                    text = mspPublicMaskText(state.cfStatus, sortedIps, state, config = true),
                    modifier = Modifier.fillMaxWidth(),
                    centered = false,
                    maxLines = 2
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().background(simorghInsetSurface(LocalAppIsDarkTheme.current), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Clean IP", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = UiBoldFontFamily, modifier = Modifier.weight(1f))
                Text(
                    "Latency ↕",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold, fontFamily = UiBoldFontFamily,
                    textAlign = TextAlign.End,
                    modifier = Modifier
                        .width(76.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { sortByLatency = true }
                        .padding(vertical = 2.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("Action", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = UiBoldFontFamily, textAlign = TextAlign.Center, modifier = Modifier.width(132.dp))
            }
            Spacer(Modifier.height(6.dp))
            LazyColumn(modifier = Modifier.fillMaxWidth().height(220.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (sortedIps.isEmpty()) {
                    item { Text("No IP Memory yet. Run SIMORGH scanner or add Manual IPs first.", color = Color.White.copy(alpha = .76f), fontSize = 11.sp) }
                } else {
                    items(sortedIps.withIndex().toList(), key = { it.value }) { indexed ->
                        val ip = indexed.value
                        val pingText = state.cfPingResults[ip]
                        val displayIp = if (mspPublicManualContainsIp(ip, state)) ip else mspPublicConfigAlias(indexed.index)
                        Row(Modifier.fillMaxWidth().background(simorghInsetSurface(LocalAppIsDarkTheme.current), RoundedCornerShape(12.dp)).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(displayIp, color = Color.White.copy(alpha = .90f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
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
                                    enabled = cfEnabledUi && validVless,
                                    shape = RoundedCornerShape(999.dp),
                                    contentPadding = PaddingValues(horizontal = 9.dp, vertical = 6.dp),
                                    colors = simorghAdaptiveButtonColors(),
                                    border = BorderStroke(1.dp, simorghOutline(LocalAppIsDarkTheme.current))
                                ) { Text("Ping", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Normal, fontFamily = UiRegularFontFamily) }
                                Button(
                                    onClick = { runCatching { onCfConnectIp(ip) } },
                                    enabled = cfEnabledUi && validVless,
                                    shape = RoundedCornerShape(999.dp),
                                    contentPadding = PaddingValues(horizontal = 9.dp, vertical = 6.dp),
                                    colors = simorghAdaptiveButtonColors(),
                                    border = BorderStroke(1.dp, simorghOutline(LocalAppIsDarkTheme.current))
                                ) { Text("Connect", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Normal, fontFamily = UiRegularFontFamily) }
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
            .background(if (enabled) simorghSelectedSurface(LocalAppIsDarkTheme.current) else simorghControlSurface(LocalAppIsDarkTheme.current))
            .border(1.dp, if (enabled) LocalSectionAccent.current.copy(alpha = .56f) else simorghOutline(LocalAppIsDarkTheme.current), RoundedCornerShape(999.dp))
            .clickable { onToggle() }
            .padding(horizontal = 7.dp, vertical = 5.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                modifier = Modifier
                    .width(32.dp)
                    .height(18.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (enabled) LocalSectionAccent.current.copy(alpha = .34f) else simorghControlSurface(LocalAppIsDarkTheme.current))
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
                fontWeight = FontWeight.Bold, fontFamily = UiBoldFontFamily
            )
        }
    }
}

@Composable
private fun LiquidBubbleText(
    text: String,
    big: Boolean,
    fontFamily: FontFamily = UiBoldFontFamily,
    fontWeight: FontWeight? = null
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(simorghInsetSurface(LocalAppIsDarkTheme.current))
            .border(1.dp, simorghOutline(LocalAppIsDarkTheme.current), RoundedCornerShape(999.dp))
            .padding(horizontal = if (big) 13.dp else 10.dp, vertical = if (big) 7.dp else 5.dp)
    ) {
        Text(
            text = text,
            color = Color.White.copy(alpha = if (big) .98f else .78f),
            fontSize = if (big) 18.sp else 11.sp,
            fontFamily = fontFamily,
            fontWeight = fontWeight ?: if (big) FontWeight.ExtraBold else FontWeight.SemiBold,
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
            .background(simorghInsetSurface(LocalAppIsDarkTheme.current))
            .border(1.dp, Color.White.copy(alpha = .076f + .010f * glow), RoundedCornerShape(20.dp))
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

    GlassCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Clean Logs", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = UiBoldFontFamily)
                Text(if (logText.isBlank()) "No logs yet." else "${logText.lineSequence().count()} raw lines • grouped", color = Color.White.copy(alpha = .66f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Button(onClick = { refreshLogs(); showLogs = true }, shape = RoundedCornerShape(999.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), colors = ButtonDefaults.buttonColors(containerColor = LocalSectionAccent.current)) {
                Text("Logs", color = Color.White, fontWeight = FontWeight.ExtraBold, fontFamily = UiBoldFontFamily, fontSize = 12.sp)
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
private fun InfoText(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    labelTextAlign: TextAlign = TextAlign.Center,
    valueTextAlign: TextAlign = TextAlign.Center
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(simorghInsetSurface(LocalAppIsDarkTheme.current))
            .border(1.dp, simorghOutline(LocalAppIsDarkTheme.current), RoundedCornerShape(18.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text(label, color = Color.White.copy(alpha = .62f), fontSize = 10.sp, fontFamily = UiRegularFontFamily, fontWeight = FontWeight.Normal, textAlign = labelTextAlign, modifier = Modifier.fillMaxWidth(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(value, color = Color.White.copy(alpha = .92f), fontSize = 13.sp, fontFamily = UiRegularFontFamily, fontWeight = FontWeight.Normal, textAlign = valueTextAlign, modifier = Modifier.fillMaxWidth(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SpeedMiniBubble(state: SimorghPublicState, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        InfoText("Download", "↓ ${FormatUtils.kbps(state.downloadKbps)}", Modifier.weight(1f))
        InfoText("Upload", "↑ ${FormatUtils.kbps(state.uploadKbps)}", Modifier.weight(1f))
    }
}

@Composable
private fun GlassCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val darkTheme = LocalAppIsDarkTheme.current
    val shape = RoundedCornerShape(30.dp)
    Card(
        modifier = modifier.fillMaxWidth().shadow(4.dp, shape),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = simorghCardSurface(darkTheme)),
        border = BorderStroke(1.dp, simorghOutline(darkTheme))
    ) {
        CompositionLocalProvider(LocalContentColor provides simorghPrimaryText(darkTheme)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) { content() }
        }
    }
}

@Composable
private fun AnimatedSimorghBackground(darkTheme: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.background(simorghPageBackground(darkTheme)))
}


private fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
