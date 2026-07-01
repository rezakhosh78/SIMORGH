@file:Suppress("DEPRECATION")

package com.rkh.vpn.core

import android.content.Context
import android.os.Build
import android.system.Os
import android.system.OsConstants
import com.rkh.vpn.data.RKhVpnLogStore
import com.rkh.vpn.data.StormDnsRuntimeLog
import java.io.File
import java.io.FileDescriptor
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class ProcessCoreManager(private val context: Context) {
    private val io = Executors.newFixedThreadPool(4)
    private var xrayProcess: Process? = null
    private var tun2socksProcess: Process? = null
    private var tun2proxyThread: Thread? = null
    // Raw detached Android TUN fd currently owned by ProcessCoreManager for tun2proxy fd-run.
    private var tun2proxyTunFd: Int? = null
    private var nipoProcess: Process? = null
    private var stormDnsProcess: Process? = null
    private var stormDnsResolversFile: File? = null
    @Volatile private var stormDnsLastSessionAttemptResolver: String? = null
    private val bin = NativeBinaryManager(context)

    companion object {
        private val inheritedStdinLaunchLock = Any()
    }

    fun startXray(configJson: String, inheritedTunFdNumber: Int? = null): Int {
        val xray = bin.prepare("xray")
        val workDir = File(context.filesDir, "xray-bin-runtime").apply { mkdirs() }
        val configFile = File(workDir, "config.json").apply { writeText(configJson) }
        val assetDir = prepareXrayAssetDir(configJson, workDir)
        val cmd = listOf(xray.absolutePath, "run", "-config", configFile.absolutePath)
        log("Starting Xray binary: ${cmd.joinToString(" ")}")
        val xrayEnv = linkedMapOf(
            "XRAY_LOCATION_ASSET" to assetDir.absolutePath,
            "V2RAY_LOCATION_ASSET" to assetDir.absolutePath
        )
        xrayProcess = if (inheritedTunFdNumber != null) {
            // Android's ProcessBuilder does not reliably expose arbitrary inherited fd
            // numbers (for example 200) to a native child. z19 proved that direct-env
            // fd mode can make Xray fail with: "bad file descriptor".
            // Keep the stable approach used by tun2socks: temporarily dup the Android
            // VPN TUN fd to stdin before launching Xray, and tell Xray to read fd 0.
            val fdText = "0"
            xrayEnv["XRAY_TUN_FD"] = fdText
            xrayEnv["xray.tun.fd"] = fdText
            log("Xray Android TUN fd env prepared: XRAY_TUN_FD=$fdText (stdin remap from source fd=$inheritedTunFdNumber)")
            startProcessWithTunOnStdin(cmd, inheritedTunFdNumber, env = xrayEnv, workingDir = workDir, label = "xray-tun")
        } else {
            ProcessBuilder(cmd)
                .directory(workDir)
                .apply { environment().putAll(xrayEnv) }
                .redirectErrorStream(true)
                .start()
        }
        pump("Xray", xrayProcess!!)
        Thread.sleep(700)
        val exit = runCatching { xrayProcess?.exitValue() }.getOrNull()
        if (exit != null) error("Xray exited immediately with code $exit. Check Xray logs above.")
        val inboundPort = Regex("""(?s)\"inbounds\"\s*:\s*\[.*?\"port\"\s*:\s*(\d+)""")
            .find(configJson)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        val socksPort = inboundPort ?: Regex("""\"port\"\s*:\s*(\d+)""").find(configJson)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 18188
        if (inheritedTunFdNumber != null) log("Xray binary started with Android TUN fd remapped to stdin/env 0 from source fd=${inheritedTunFdNumber} and local inbound port=$socksPort") else log("Xray binary started on SOCKS 127.0.0.1:$socksPort")
        return socksPort
    }

    @Synchronized
    fun switchXrayOnly(configJson: String, inheritedTunFdNumber: Int? = null): Int {
        // Simple v1.2.4: keep the established Android VPN TUN and the
        // tun2proxy native fd-run loop alive. Only replace the Xray process
        // behind the same local SOCKS port so rapid Simple config changes do
        // not reopen TUN/tun2proxy and do not hit native code -4.
        val oldXray = xrayProcess
        xrayProcess = null
        stopProcessGracefully("xray-switch", oldXray)
        val port = startXray(configJson, inheritedTunFdNumber)
        log("Xray-only switch completed; VPN TUN/tun2proxy kept alive and SOCKS port=$port")
        return port
    }

    private fun prepareXrayAssetDir(configJson: String, fallbackDir: File): File {
        if (!configJson.contains("geosite:") && !configJson.contains("geoip:")) {
            runCatching { File(fallbackDir, "geosite.dat").delete() }
            runCatching { File(fallbackDir, "geoip.dat").delete() }
            log("Xray geo assets not needed for this config; using lightweight runtime asset dir")
            return fallbackDir
        }
        return File(context.filesDir, "xray-assets").apply {
            mkdirs()
            ensureXrayGeoAssets(this)
            log("Xray shared asset env prepared: XRAY_LOCATION_ASSET=${absolutePath}")
        }
    }

    private fun ensureXrayGeoAssets(assetDir: File) {
        listOf("geosite.dat", "geoip.dat").forEach { assetName ->
            val outFile = File(assetDir, assetName)
            if (outFile.exists() && outFile.length() > 1024L) {
                log("Xray shared geo asset reused: ${outFile.absolutePath} (${outFile.length()} bytes)")
                return@forEach
            }
            val copied = runCatching {
                context.assets.open("xray/$assetName").use { input ->
                    outFile.outputStream().use { output -> input.copyTo(output) }
                }
                true
            }.getOrElse { e ->
                log("Xray geo asset missing or could not be copied: assets/xray/$assetName • ${e.javaClass.simpleName}: ${e.message}")
                false
            }
            if (copied) {
                log("Xray shared geo asset ready: ${outFile.absolutePath} (${outFile.length()} bytes)")
            }
        }
    }

    fun startTun2ProxyBridge(tunFd: Int, socksPort: Int, mtu: Int = 1500, dnsStrategy: Int = Tun2ProxyBridge.DNS_DIRECT) {
        val proxy = "socks5://127.0.0.1:$socksPort"
        val dnsStrategyName = when (dnsStrategy) {
            Tun2ProxyBridge.DNS_VIRTUAL -> "virtual"
            Tun2ProxyBridge.DNS_OVER_TCP -> "over-tcp"
            Tun2ProxyBridge.DNS_DIRECT -> "direct"
            else -> "custom-$dnsStrategy"
        }
        // A reconnect can arrive while the previous service instance is still unwinding
        // in the shared :vpncore process. Never leave the native bridge armed for process
        // exit when this manager is about to start a fresh in-process run.
        runCatching { Tun2ProxyBridge.disarmExitOnReturn() }
            .onFailure { log("tun2proxy exit-on-return disarm before start failed safely", it) }

        // Defensive cleanup: if this manager recorded a stale fd-run loop, close its
        // detached TUN fd first. The actual native singleton is also handled below by
        // retrying when libtun2proxy reports that a previous loop is still alive (-4).
        tun2proxyTunFd?.let { oldFd ->
            log("Closing stale tun2proxy detached TUN fd before new start: fd=$oldFd")
            runCatching { Tun2ProxyBridge.closeRawFd(oldFd) }
                .onFailure { log("Stale tun2proxy fd close failed safely", it) }
            tun2proxyTunFd = null
        }
        tun2proxyTunFd = tunFd
        // From this point ProcessCoreManager owns the detached fd, even if native prep fails.
        // A later stop()/failure cleanup will close it through closeRawFd().
        // Validate that Gradle packaged the official tun2proxy Android native library.
        bin.prepare("tun2proxy")
        val tun2proxyLib = File(context.applicationInfo.nativeLibraryDir, "libtun2proxy.so")
        var lastImmediateCode = Int.MIN_VALUE
        var lastNativeError = ""
        val maxAttempts = 6
        for (attempt in 1..maxAttempts) {
            val result = AtomicInteger(Int.MIN_VALUE)
            val thread = Thread({
                try {
                    log("tun2proxy fd-run loader: preparing native run • attempt=$attempt/$maxAttempts • proxy=$proxy • fd=$tunFd • mtu=$mtu • dns=$dnsStrategyName • lib=${tun2proxyLib.absolutePath}")
                    val code = Tun2ProxyBridge.runWithFd(
                        proxyUrl = proxy,
                        tunFd = tunFd,
                        // ProcessCoreManager owns this detached fd and closes it from JNI on disconnect.
                        // Do not let libtun2proxy close it too; this avoids native stop/fd teardown races.
                        closeFdOnDrop = false,
                        tunMtu = mtu,
                        dnsStrategy = dnsStrategy,
                        verbosity = Tun2ProxyBridge.VERBOSITY_INFO
                    )
                    result.set(code)
                    val nativeError = runCatching { Tun2ProxyBridge.lastNativeError() }.getOrDefault("")
                    log("tun2proxy JNI bridge returned: $code${if (nativeError.isNotBlank()) " • native=$nativeError" else ""}")
                } catch (t: Throwable) {
                    result.set(Int.MIN_VALUE + 1)
                    log("tun2proxy JNI bridge crashed: ${t.javaClass.simpleName}: ${t.message}", t)
                }
            }, "simorgh-tun2proxy-jni-$attempt")
            // Keep this as a normal worker thread like the official Android example.
            // The native function blocks while the VPN is active and returns on stop/error.
            tun2proxyThread = thread
            log("Starting tun2proxy JNI bridge with direct Android TUN fd=$tunFd, proxy=$proxy, mtu=$mtu, dns=$dnsStrategyName, loader=fd-run-v0.7.20, attempt=$attempt/$maxAttempts")
            log("tun2proxy diagnostics: requested socksPort=$socksPort tunFd=$tunFd mtu=$mtu nativeLibraryDir=${context.applicationInfo.nativeLibraryDir} supportedAbis=${Build.SUPPORTED_ABIS.joinToString()}")
            log("tun2proxy diagnostics: lib path=${tun2proxyLib.absolutePath} exists=${tun2proxyLib.exists()} bytes=${tun2proxyLib.length()} canRead=${tun2proxyLib.canRead()} canExecute=${tun2proxyLib.canExecute()}")
            thread.start()
            // tun2proxy_with_fd_run is a blocking native run loop when it starts
            // successfully. Do not wait for it to return; returning means failure/stop.
            // Keep the startup wait short so Simple/Fragment/MSP/Master can mark the
            // VPN connected as soon as the native thread is alive.
            Thread.sleep(650)
            if (thread.isAlive) {
                log("tun2proxy native run loop is alive after startup check. Traffic path: Android VpnService TUN fd=$tunFd → libtun2proxy.so fd-run ABI (safe fd-close stop, dns=$dnsStrategyName) → $proxy")
                return
            }

            lastImmediateCode = result.get()
            lastNativeError = runCatching { Tun2ProxyBridge.lastNativeError() }.getOrDefault("")
            if (lastImmediateCode == -4 && attempt < maxAttempts) {
                // -4 means the process-wide native singleton still sees the previous
                // fd-run loop. Keep the new TUN fd open and retry; closing it here would
                // turn a recoverable one-click reconnect into a failed first tap.
                log("tun2proxy native singleton still busy (-4); waiting before retry $attempt/$maxAttempts${if (lastNativeError.isNotBlank()) " • native=$lastNativeError" else ""}")
                runCatching { thread.join(120L) }
                Thread.sleep(750L)
                continue
            }
            break
        }

        val failedFd = tun2proxyTunFd
        tun2proxyTunFd = null
        if (failedFd != null) {
            log("Closing tun2proxy detached TUN fd after immediate native exit: fd=$failedFd")
            runCatching { Tun2ProxyBridge.closeRawFd(failedFd) }
                .onFailure { log("Immediate-exit detached fd close failed safely", it) }
        }
        val detail = when (lastImmediateCode) {
            -1 -> " • tun2proxy native loader failed${if (lastNativeError.isNotBlank()) ": $lastNativeError" else ""}"
            -2 -> " • tun2proxy native entrypoint failed${if (lastNativeError.isNotBlank()) ": $lastNativeError" else ""}"
            -3 -> " • proxy string could not be passed to JNI"
            -4 -> " • previous native loop did not stop after retry window${if (lastNativeError.isNotBlank()) ": $lastNativeError" else ""}"
            else -> if (lastNativeError.isNotBlank()) " • native=$lastNativeError" else ""
        }
        throw IllegalStateException("tun2proxy JNI bridge exited immediately with code $lastImmediateCode for proxy=$proxy$detail")
    }


    fun startTun2Socks(tunFd: Int, socksPort: Int) {
        val t2s = bin.prepare("tun2socks")
        val proxy = "socks5://127.0.0.1:$socksPort"

        // ProcessBuilder on Android does not reliably inherit arbitrary fd numbers like 179/200.
        // xjasonlyu tun2socks fdbased driver reads a fd that exists in the child process.
        // So we temporarily duplicate the Android TUN fd to this app process stdin (fd 0),
        // start tun2socks with inherited stdin, then restore stdin immediately.
        // The child sees the TUN as fd://0.
        val candidates = listOf(
            listOf(t2s.absolutePath, "-device", "fd://0", "-proxy", proxy, "-loglevel", "info"),
            listOf(t2s.absolutePath, "--device", "fd://0", "--proxy", proxy, "--loglevel", "info"),
            listOf(t2s.absolutePath, "-device", "fd://0?offset=0", "-proxy", proxy, "-loglevel", "info"),
            listOf(t2s.absolutePath, "--device", "fd://0?offset=0", "--proxy", proxy, "--loglevel", "info")
        )
        var last: Throwable? = null
        var lastExit: Int? = null
        for (cmd in candidates) {
            try {
                log("Starting tun2socks with TUN fd inherited as stdin: ${cmd.joinToString(" ")} (sourceTunFd=$tunFd)")
                tun2socksProcess = startProcessWithTunOnStdin(cmd, tunFd, label = "tun2socks")
                pump("Tun2Socks", tun2socksProcess!!)
                Thread.sleep(1500)
                val exit = runCatching { tun2socksProcess?.exitValue() }.getOrNull()
                if (exit == null) {
                    log("tun2socks started successfully with command: ${cmd.drop(1).joinToString(" ")}")
                    log("tun2socks attached to Android TUN through inherited stdin fd=0 and proxy=$proxy")
                    return
                }
                lastExit = exit
                log("tun2socks candidate exited immediately: $exit")
            } catch (e: Throwable) {
                last = e
                log("tun2socks candidate failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        throw IllegalStateException("Could not start tun2socks with inherited stdin fd. Expected: -device fd://0 -proxy $proxy. Last exit=$lastExit, last error=${last?.message}", last)
    }


    private fun extractStormDnsTomlInt(config: String, key: String): Int? {
        return Regex("""(?m)^\s*""" + Regex.escape(key) + """\s*=\s*(\d+)""").find(config)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun setStormDnsTomlValue(config: String, key: String, value: String): String {
        val pattern = Regex("(?m)^\\s*" + Regex.escape(key) + "\\s*=\\s*.*$")
        return if (pattern.containsMatchIn(config)) {
            pattern.replace(config, "$key = $value")
        } else {
            config.trimEnd() + "\n$key = $value"
        }
    }

    private fun hardenStormDnsRuntimeConfig(configToml: String): String {
        // Keep StormDNS runtime config byte-for-byte compatible with Termux/Windows usage.
        // Do not force session racing, duplication, retry, or resolver lifecycle values here.
        // Only strip BOM/trailing spaces so Android does not change the user's working config.
        val clean = configToml.replace("\uFEFF", "").trim()
        return clean + "\n"
    }

    fun startStormDnsAgent(configToml: String, serverConfigToml: String = "", resolversText: String = "", socksPort: Int = 18000): Int {
        val coreFile = runCatching { bin.prepare("stormdns") }
            .getOrElse { e ->
                throw IllegalStateException(
                    "Missing StormDNS native core. Put the Android core as libstormdns.so in app/src/main/jniLibs/<abi>/ before building.",
                    e
                )
            }
        val workDir = File(context.filesDir, "stormdns-runtime").apply { mkdirs() }
        val runtimeConfigToml = hardenStormDnsRuntimeConfig(configToml)
        val actualSocksPort = (extractStormDnsTomlInt(runtimeConfigToml, "LISTEN_PORT") ?: socksPort).coerceIn(1024, 65535)
        log("StormDNS runtime: resolver parity active; config/resolvers are not normalized or reordered by SIMORGH; waiting for 127.0.0.1:$actualSocksPort")
        val configFile = File(workDir, "client_config.toml").apply { writeText(runtimeConfigToml) }
        val runtimeResolversText = resolversText.ifBlank { extractStormDnsResolvers(runtimeConfigToml) }.replace("﻿", "").trimEnd().let { if (it.isBlank()) "" else it + "\n" }
        val resolversFile = File(workDir, "client_resolvers.txt").apply { writeText(runtimeResolversText) }
        stormDnsResolversFile = resolversFile
        log("StormDNS runtime files ready • client=${configFile.absolutePath}(${configFile.length()}b) • resolvers=${resolversFile.absolutePath}(${resolversFile.length()}b) • listenPort=$actualSocksPort")
        val candidates = listOf(
            listOf(coreFile.absolutePath, "--config", configFile.absolutePath, "--resolvers", resolversFile.absolutePath),
            listOf(coreFile.absolutePath, "-config", configFile.absolutePath, "-resolvers", resolversFile.absolutePath)
        )

        var lastError: Throwable? = null
        var lastExit: Int? = null
        for (cmd in candidates) {
            try {
                val tmpDir = File(workDir, "tmp").apply { mkdirs() }
                val homeDir = File(workDir, "home").apply { mkdirs() }
                val termuxLikeEnv = linkedMapOf(
                    "HOME" to homeDir.absolutePath,
                    "TMPDIR" to tmpDir.absolutePath,
                    "TMP" to tmpDir.absolutePath,
                    "TEMP" to tmpDir.absolutePath,
                    "PATH" to "/system/bin:/system/xbin:/vendor/bin",
                    "LANG" to "C.UTF-8",
                    "LC_ALL" to "C.UTF-8",
                    "TERM" to "xterm-256color"
                )
                log("Starting StormDNS core with Termux-like env: ${cmd.joinToString(" ")} • socks5=127.0.0.1:$actualSocksPort • HOME=${homeDir.absolutePath} • TMPDIR=${tmpDir.absolutePath}")
                stormDnsProcess = ProcessBuilder(cmd)
                    .directory(workDir)
                    .apply { environment().putAll(termuxLikeEnv) }
                    .redirectErrorStream(true)
                    .start()
                pump("StormDNS", stormDnsProcess!!)
                Thread.sleep(1000)
                val exit = runCatching { stormDnsProcess?.exitValue() }.getOrNull()
                if (exit == null) {
                    log("StormDNS core is alive; waiting for local SOCKS5 port 127.0.0.1:$actualSocksPort to become ready • own UID must be excluded from VPN routes")
                    if (!waitForStormDnsSocksPortDuringLongMtuScan(actualSocksPort)) {
                        runCatching { stormDnsProcess?.destroy() }
                        throw IllegalStateException("StormDNS core stayed alive but did not create local SOCKS5 127.0.0.1:$actualSocksPort. StormDNS default core scan did not finish/open SOCKS in time; check server/domain/encryption/resolver list.")
                    }
                    log("StormDNS SOCKS5 is ready on 127.0.0.1:$actualSocksPort")
                    return actualSocksPort
                }
                lastExit = exit
                log("StormDNS candidate exited immediately: $exit")
            } catch (e: Throwable) {
                lastError = e
                log("StormDNS candidate failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        throw IllegalStateException("Could not start StormDNS core. Last exit=$lastExit, last error=${lastError?.message}", lastError)
    }


    private fun waitForStormDnsSocksPortDuringLongMtuScan(socksPort: Int): Boolean {
        val maxWaitMs = 45L * 60L * 1000L
        val startedAt = System.currentTimeMillis()
        var lastProgressLogAt = 0L
        var lastProgressText = ""
        while (System.currentTimeMillis() - startedAt < maxWaitMs) {
            if (waitForLocalPort(socksPort, 1_000L)) return true
            val exitNow = runCatching { stormDnsProcess?.exitValue() }.getOrNull()
            if (exitNow != null) {
                log("StormDNS core exited while waiting for SOCKS5 port: $exitNow")
                return false
            }
            val now = System.currentTimeMillis()
            if (now - lastProgressLogAt >= 15_000L) {
                val lines = StormDnsRuntimeLog.read()
                val progress = stormDnsLatestMtuProgress(lines)
                val healthy = stormDnsAcceptedCount(lines)
                val text = if (progress.second > 0) "${progress.first}/${progress.second}" else "running"
                if (text != lastProgressText || now - lastProgressLogAt >= 30_000L) {
                    log("StormDNS default core scan still running; SOCKS5 127.0.0.1:$socksPort not ready yet • progress=$text • healthy=$healthy • keeping core alive")
                    lastProgressText = text
                }
                lastProgressLogAt = now
            }
        }
        return false
    }

    private fun stormDnsLatestMtuProgress(lines: List<String>): Pair<Int, Int> {
        val progressPattern = Regex("""\((\d+)\s*/\s*(\d+)\)""")
        var current = 0
        var total = 0
        lines.forEach { line ->
            progressPattern.findAll(line).forEach { match ->
                val a = match.groupValues.getOrNull(1)?.toIntOrNull() ?: 0
                val b = match.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
                if (b > 0 && (b > total || a >= current)) {
                    current = a
                    total = b
                }
            }
        }
        return current to total
    }

    private fun stormDnsAcceptedCount(lines: List<String>): Int {
        val out = linkedSetOf<String>()
        fun normalize(raw: String): String {
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
        fun add(raw: String?) {
            val endpoint = normalize(raw.orEmpty())
            if (endpoint.isNotBlank()) out += endpoint
        }
        StormDnsRuntimeLog.acceptedResolversSnapshot().forEach { add(it) }
        val acceptedPattern = Regex("""(?i)(?:✅\s*)?accepted.*?\bvia\s+(\[[^\]]+\]:\d+|[^\s|),;]+)""")
        val reactivatedPattern = Regex("""(?i)dns\s+resolver\s+reactivated:\s+(\[[^\]]+\]:\d+|[^\s|),;]+)""")
        val validTablePattern = Regex("""(?i)(?:^|\s)(\d{1,3}(?:\.\d{1,3}){3}:\d+|\[[0-9a-f:]+\]:\d+)\s+\d+\s+\d+\s+(?:\d+(?:ms|s)|\d+\.\d+s|[0-9.]+s)\s+\S+""")
        lines.forEach { line ->
            val lower = line.lowercase()
            if (!lower.contains("rejected") && !lower.contains("timeout") && !lower.contains("fail")) {
                if (lower.contains("accepted")) acceptedPattern.find(line)?.groupValues?.getOrNull(1)?.let { add(it) }
                if (lower.contains("dns resolver reactivated")) reactivatedPattern.find(line)?.groupValues?.getOrNull(1)?.let { add(it) }
                validTablePattern.find(line)?.groupValues?.getOrNull(1)?.let { add(it) }
            }
        }
        return out.size
    }


    private fun forceStormDnsMtuExport(configToml: String): String {
        var clean = configToml.replace("﻿", "").trim()
        fun setBool(key: String, value: Boolean) {
            val pattern = Regex("(?m)^\\s*" + Regex.escape(key) + "\\s*=\\s*.*$")
            clean = if (pattern.containsMatchIn(clean)) {
                pattern.replace(clean, "$key = $value")
            } else {
                clean + "\n$key = $value"
            }
        }
        fun setString(key: String, value: String) {
            val pattern = Regex("(?m)^\\s*" + Regex.escape(key) + "\\s*=\\s*.*$")
            clean = if (pattern.containsMatchIn(clean)) {
                pattern.replace(clean, "$key = \"$value\"")
            } else {
                clean + "\n$key = \"$value\""
            }
        }
        setBool("SAVE_MTU_SERVERS_TO_FILE", true)
        // In VPN mode the Android side cannot set a DNS port in VpnService.
        // Keep the StormDNS local DNS listener enabled so Xray can route port 53 to it.
        setBool("LOCAL_DNS_ENABLED", true)
        setString("LOCAL_DNS_IP", "127.0.0.1")
        // Port 53 is privileged on Android and causes: bind permission denied.
        // Use 5353 internally; Xray bridge still catches DNS traffic on port 53 and forwards it here.
        val dnsPortPattern = Regex("(?m)^\\s*LOCAL_DNS_PORT\\s*=\\s*\\d+\\s*$")
        clean = if (dnsPortPattern.containsMatchIn(clean)) {
            dnsPortPattern.replace(clean, "LOCAL_DNS_PORT = 5353")
        } else {
            clean + "\nLOCAL_DNS_PORT = 5353"
        }
        return clean + "\n"
    }

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

    fun updateStormDnsResolvers(resolversText: String) {
        val file = stormDnsResolversFile ?: File(context.filesDir, "stormdns-runtime/client_resolvers.txt")
        file.parentFile?.mkdirs()
        file.writeText(resolversText)
        log("StormDNS client_resolvers.txt updated • ${file.length()}b • lines=${resolversText.lineSequence().count { it.trim().isNotBlank() }}")
    }


    private fun extractStormDnsResolvers(configToml: String): String {
        val matches = Regex("servers\\s*=\\s*\\[([^]]*)]").find(configToml)?.groupValues?.getOrNull(1).orEmpty()
        return matches.split(',', '\n')
            .map { it.trim().trim('"', '\'', ' ') }
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .ifBlank { "1.1.1.1:53\n8.8.8.8:53" }
    }

    fun startNipoAgent(configYaml: String, socksPort: Int = 9992): Int {
        val nipo = bin.prepare("nipovpn")
        val workDir = File(context.filesDir, "nipo-runtime").apply { mkdirs() }
        val logFile = File(workDir, "nipovpn.log")
        val configFile = File(workDir, "config.yaml")
        val patchedConfig = patchNipoAgentConfig(configYaml, socksPort, logFile)
        configFile.writeText(patchedConfig)
        log("NipoVPN runtime ready • binary=${nipo.absolutePath} • exists=${nipo.exists()} • exec=${nipo.canExecute()} • size=${nipo.length()} • workDir=${workDir.absolutePath} • config=${configFile.absolutePath}(${configFile.length()}b) • logFile=${logFile.absolutePath}")
        val cmd = listOf(nipo.absolutePath, "agent", configFile.absolutePath)
        log("Starting NipoVPN agent: ${cmd.joinToString(" ")} • socks5=127.0.0.1:$socksPort")
        nipoProcess = try {
            ProcessBuilder(cmd)
                .directory(workDir)
                .redirectErrorStream(true)
                .start()
        } catch (e: Throwable) {
            log("NipoVPN ProcessBuilder.start failed: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
        pump("NipoVPN", nipoProcess!!)
        Thread.sleep(800)
        val exit = runCatching { nipoProcess?.exitValue() }.getOrNull()
        if (exit != null) error("NipoVPN agent exited immediately with code $exit. Check NipoVPN logs above.")
        if (!waitForLocalPort(socksPort, 4200L)) {
            log("NipoVPN agent process is still alive but local SOCKS5 port 127.0.0.1:$socksPort was not ready yet")
        } else {
            log("NipoVPN agent SOCKS5 is ready on 127.0.0.1:$socksPort")
        }
        return socksPort
    }

    private fun waitForLocalPort(port: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", port), 250)
                    return true
                }
            } catch (_: Throwable) {
                Thread.sleep(120)
            }
        }
        return false
    }

    private fun patchNipoAgentConfig(rawYaml: String, socksPort: Int, logFile: File): String {
        val source = rawYaml.replace("﻿", "").ifBlank { defaultNipoConfig() }
        val out = mutableListOf<String>()
        var block = ""
        var sawProtocol = false
        var sawAgentListenIp = false
        var sawAgentListenPort = false
        var sawLogFile = false
        var sanitizedAndroidTlsFiles = false
        for (line in source.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.endsWith(":") && !line.startsWith(" ") && !line.startsWith("\t")) {
                block = trimmed.removeSuffix(":")
            }
            when {
                block == "general" && trimmed.startsWith("protocol:") -> {
                    out += "  protocol: socks5"
                    sawProtocol = true
                }
                block == "agent" && trimmed.startsWith("listenIp:") -> {
                    out += "  listenIp: \"127.0.0.1\""
                    sawAgentListenIp = true
                }
                block == "agent" && trimmed.startsWith("listenPort:") -> {
                    out += "  listenPort: $socksPort"
                    sawAgentListenPort = true
                }
                block == "log" && trimmed.startsWith("logFile:") -> {
                    out += "  logFile: \"${logFile.absolutePath.replace("\\", "\\\\").replace("\"", "\\\"")}\""
                    sawLogFile = true
                }
                block == "general" && (trimmed.startsWith("tlsCertFile:") || trimmed.startsWith("tlsKeyFile:") || trimmed.startsWith("tlsCaFile:")) -> {
                    val key = trimmed.substringBefore(":").trim()
                    out += "  $key: \"\""
                    sanitizedAndroidTlsFiles = true
                }
                else -> out += line
            }
        }
        val text = out.joinToString("\n").trimEnd()
        val suffix = StringBuilder()
        val hasGeneralBlock = Regex("(?m)^general\\s*:").containsMatchIn(text)
        val hasLogBlock = Regex("(?m)^log\\s*:").containsMatchIn(text)
        val hasServerBlock = Regex("(?m)^server\\s*:").containsMatchIn(text)
        val hasAgentBlock = Regex("(?m)^agent\\s*:").containsMatchIn(text)
        if (!sawProtocol && hasGeneralBlock) suffix.append("\n# SIMORGH runtime override\ngeneral:\n  protocol: socks5")
        if (!hasLogBlock) suffix.append("\nlog:\n  logLevel: \"DEBUG\"\n  logFile: \"${logFile.absolutePath.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        if (!hasServerBlock) {
            suffix.append("\nserver:\n  threads: 8\n  listenIp: \"0.0.0.0\"\n  listenPort: 80")
        }
        if (!hasAgentBlock) {
            suffix.append("\nagent:\n  threads: 8\n  listenIp: \"127.0.0.1\"\n  listenPort: $socksPort\n  serverIp: \"127.0.0.1\"\n  serverPort: 9992\n  httpVersion: \"1.1\"\n  userAgent : \"SIMORGH-NipoVPN/1.0\"")
        } else if (!sawAgentListenIp || !sawAgentListenPort) {
            log("NipoVPN config has agent block; runtime forced listenIp/listenPort only when keys already exist. If SOCKS5 does not bind, re-save the profile once.")
        }
        val finalHasServerBlock = hasServerBlock || suffix.contains("server:")
        val finalHasAgentBlock = hasAgentBlock || suffix.contains("agent:")
        val finalConfig = (text + suffix.toString() + "\n").replace("\r\n", "\n")
        if (sanitizedAndroidTlsFiles) log("NipoVPN Android runtime sanitized TLS file paths to avoid missing /etc certificate file crash; TLS enable flag was not changed")
        log("NipoVPN config validation shape • general=$hasGeneralBlock • log=$hasLogBlock • server=$finalHasServerBlock • agent=$finalHasAgentBlock")
        return finalConfig
    }

    private fun defaultNipoConfig(): String = """
        ---
        general:
          token: "af445adb-2434-4975-9445-2c1b2231"
          protocol: socks5
          fakeUrls:
            - google.com
            - cloudflare.com
          methods:
            - GET
            - POST
          endPoints:
            - api
            - login
          timeout: 10
          pullTimeout: 50
          tunnelEnable: false
          connectionReuse: true
          tlsEnable: false
          tlsVerifyPeer: false
          tlsCertFile: ""
          tlsKeyFile: ""
          tlsCaFile: ""
        log:
          logLevel: "DEBUG"
          logFile: "nipovpn.log"
        server:
          threads: 8
          listenIp: "0.0.0.0"
          listenPort: 80
        agent:
          threads: 8
          listenIp: "127.0.0.1"
          listenPort: 9992
          serverIp: "127.0.0.1"
          serverPort: 9992
          httpVersion: "1.1"
          userAgent : "SIMORGH-NipoVPN/1.0"
    """.trimIndent()

    private fun startProcessWithTunOnStdin(
        cmd: List<String>,
        tunFd: Int,
        env: Map<String, String> = emptyMap(),
        workingDir: File? = null,
        label: String = "native-child"
    ): Process {
        return synchronized(inheritedStdinLaunchLock) {
            val savedStdin = Os.dup(FileDescriptor.`in`)
            try {
                val tunFileDescriptor = fileDescriptorFromInt(tunFd)
                // Fail here with a clear message if a stale concurrent start has already
                // closed the duplicated Android TUN fd. This prevents a confusing native
                // EBADF after stdin has been remapped.
                Os.fcntlInt(tunFileDescriptor, OsConstants.F_GETFD, 0)
                Os.dup2(tunFileDescriptor, 0)
                Os.fcntlInt(FileDescriptor.`in`, OsConstants.F_SETFD, 0)
                log("Prepared child stdin fd=0 for $label from Android TUN source fd=$tunFd")
                ProcessBuilder(cmd)
                    .apply {
                        if (workingDir != null) directory(workingDir)
                        environment().putAll(env)
                    }
                    .redirectInput(ProcessBuilder.Redirect.INHERIT)
                    .redirectErrorStream(true)
                    .start()
            } finally {
                runCatching { Os.dup2(savedStdin, 0) }
                runCatching { Os.close(savedStdin) }
                log("Restored app stdin after launching $label")
            }
        }
    }

    private fun fileDescriptorFromInt(fd: Int): FileDescriptor {
        val descriptor = FileDescriptor()
        val field = FileDescriptor::class.java.declaredFields.firstOrNull { it.name == "descriptor" || it.name == "fd" }
            ?: error("Could not access FileDescriptor integer field on this Android runtime")
        field.isAccessible = true
        field.setInt(descriptor, fd)
        return descriptor
    }

    fun stop(allowProcessExitOnTun2ProxyReturn: Boolean = true, waitForTun2ProxyExitMs: Long = 0L) {
        log("Stopping binary core processes")
        val oldTun2Socks = tun2socksProcess
        val oldTun2ProxyThread = tun2proxyThread
        val oldTun2ProxyFd = tun2proxyTunFd
        val oldXray = xrayProcess
        val oldNipo = nipoProcess
        val oldStormDns = stormDnsProcess
        tun2socksProcess = null
        tun2proxyThread = null
        tun2proxyTunFd = null
        xrayProcess = null
        nipoProcess = null
        stormDnsProcess = null

        // Stop external child cores before touching tun2proxy. The copied device log
        // proved libtun2proxy can SIGSEGV a few seconds after its run loop returns.
        // Therefore tun2proxy fd-close is the last native action, and the isolated
        // VPN service process exits immediately when/after the native loop returns.
        stopProcessGracefully("tun2socks", oldTun2Socks)
        stopProcessGracefully("xray", oldXray)
        stopProcessGracefully("nipovpn", oldNipo)
        stopProcessGracefully("stormdns", oldStormDns)
        cleanupSharedGeoAssets()

        if (oldTun2ProxyThread != null) {
            if (allowProcessExitOnTun2ProxyReturn) {
                log("Arming tun2proxy return to exit isolated VPN service process; prevents post-disconnect native SIGSEGV")
                runCatching { Tun2ProxyBridge.armExitOnReturn() }
                    .onFailure { log("tun2proxy exit-on-return arm failed safely", it) }
            } else {
                log("Disarming tun2proxy process-exit for in-process reconnect cleanup; waiting for native singleton to clear")
                runCatching { Tun2ProxyBridge.disarmExitOnReturn() }
                    .onFailure { log("tun2proxy exit-on-return disarm failed safely", it) }
            }
            if (oldTun2ProxyFd != null) {
                log("Closing tun2proxy detached TUN fd as final native stop action: fd=$oldTun2ProxyFd")
                runCatching { Tun2ProxyBridge.closeRawFd(oldTun2ProxyFd) }
                    .onSuccess { rc -> log("tun2proxy detached fd close result: $rc") }
                    .onFailure { log("tun2proxy detached fd close failed safely", it) }
            } else {
                log("tun2proxy stop: no detached TUN fd recorded; no native stop call will be made")
            }
            if (!allowProcessExitOnTun2ProxyReturn && waitForTun2ProxyExitMs > 0L) {
                val deadline = System.currentTimeMillis() + waitForTun2ProxyExitMs
                while (oldTun2ProxyThread.isAlive && System.currentTimeMillis() < deadline) {
                    val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(1L)
                    runCatching { oldTun2ProxyThread.join(remaining.coerceAtMost(250L)) }
                }
                if (oldTun2ProxyThread.isAlive) {
                    log("tun2proxy JNI bridge still alive after in-process reconnect wait ${waitForTun2ProxyExitMs}ms; next start will retry native -4 instead of failing first tap")
                } else {
                    log("tun2proxy JNI bridge stopped before in-process reconnect")
                }
            } else {
                log("tun2proxy JNI bridge thread will not be joined after disconnect; isolated VPN service process exits instead of letting libtun2proxy teardown crash the UI")
            }
        }
    }

    private fun stopProcessGracefully(name: String, process: Process?) {
        if (process == null) return
        runCatching { process.destroy() }
            .onFailure { log("$name destroy failed: ${it.javaClass.simpleName}: ${it.message}") }
        repeat(8) {
            val exited = runCatching { process.exitValue(); true }.getOrDefault(false)
            if (exited) return
            runCatching { Thread.sleep(60L) }
        }
        val stillAlive = runCatching { process.exitValue(); false }.getOrDefault(true)
        if (stillAlive) {
            log("$name did not exit after graceful stop; forcing destroy")
            runCatching { process.destroyForcibly() }
                .onFailure { log("$name destroyForcibly failed: ${it.javaClass.simpleName}: ${it.message}") }
            repeat(4) {
                val exited = runCatching { process.exitValue(); true }.getOrDefault(false)
                if (exited) return
                runCatching { Thread.sleep(50L) }
            }
        }
    }

    private fun cleanupSharedGeoAssets() {
        runCatching { File(context.filesDir, "xray-assets").deleteRecursively() }
        runCatching { File(context.filesDir, "xray-bin-runtime/geosite.dat").delete() }
        runCatching { File(context.filesDir, "xray-bin-runtime/geoip.dat").delete() }
    }



    private fun normalizeStormDnsEndpoint(raw: String): String {
        val value = raw.trim().trim(',', ';', ')', ']', '[', ' ')
        if (value.isBlank()) return ""
        if (raw.trim().startsWith("[") && raw.contains("]:")) {
            val host = raw.substringAfter("[").substringBefore("]").trim()
            val port = raw.substringAfter("]:", "53").takeWhile { it.isDigit() }.ifBlank { "53" }
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

    private fun persistStormDnsSuccessfulSessionResolver(endpoint: String) {
        val normalized = normalizeStormDnsEndpoint(endpoint)
        if (normalized.isBlank()) return
        val prefs = context.getSharedPreferences("simorgh_public_state", Context.MODE_PRIVATE or Context.MODE_MULTI_PROCESS)
        val existing = prefs.getString("stormDnsSessionSuccessResolvers", "").orEmpty()
            .lineSequence()
            .map { normalizeStormDnsEndpoint(it) }
            .filter { it.isNotBlank() && it != normalized }
            .toMutableList()
        val updated = (listOf(normalized) + existing).take(24)
        prefs.edit()
            .putString("stormDnsLastSessionResolver", normalized)
            .putString("stormDnsSessionSuccessResolvers", updated.joinToString("\n"))
            .apply()
        runCatching {
            val runtimeDir = File(context.filesDir, "stormdns-runtime").apply { mkdirs() }
            File(runtimeDir, "session_success_resolvers.txt").writeText(updated.joinToString("\n") + "\n")
        }
        log("StormDNS session success resolver observed: $normalized (resolver file order unchanged)")
    }

    private fun trackStormDnsSessionResolver(message: String) {
        val attempt = Regex("""(?i)session\s+init\s+attempt.*?\bresolver\s+(\[[^\]]+\]:\d+|\d{1,3}(?:\.\d{1,3}){3}:\d+|[^\s|),;]+)""")
            .find(message)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { normalizeStormDnsEndpoint(it) }
        if (!attempt.isNullOrBlank()) {
            stormDnsLastSessionAttemptResolver = attempt
        }
        if (message.contains("Session Initialized Successfully", ignoreCase = true)) {
            stormDnsLastSessionAttemptResolver?.let { persistStormDnsSuccessfulSessionResolver(it) }
        }
    }

    private fun softenStormDnsTransientSessionLine(message: String): String {
        val lower = message.lowercase()
        val isTransientSessionInit = lower.contains("session initialization failed") ||
            lower.contains("session init failed") ||
            lower.contains("session init attempt") && lower.contains("failed")
        if (!isTransientSessionInit || StormDnsRuntimeLog.isSocksListeningDetected()) return message

        return message
            .replace("[ERROR]", "[INFO]")
            .replace("❌ Session initialization failed: session init failed", "⏳ Session init warm-up retry: racing next resolver batch")
            .replace("Session initialization failed: session init failed", "Session init warm-up retry: racing next resolver batch")
            .replace("session initialization failed", "session init warm-up retry", ignoreCase = true)
            .replace("session init failed", "session init retry", ignoreCase = true)
            .replace("failed", "retrying", ignoreCase = true)
    }


    private fun pump(tag: String, process: Process) {
        io.submit {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (line.isNotBlank()) {
                        val msg = "[$tag] $line"
                        if (tag.equals("StormDNS", ignoreCase = true)) {
                            trackStormDnsSessionResolver(msg)
                            StormDnsRuntimeLog.append(context, msg)
                        } else {
                            log(msg)
                        }
                    }
                }
            }
        }
    }

    private fun sanitizeCoreBrand(message: String): String {
        val legacyUpper = "Master" + "DNS"
        val legacyLower = "master" + "dns"
        val legacyMixed = "Master" + "Dns"
        return message
            .replace(legacyUpper, "StormDNS")
            .replace(legacyLower, "stormdns")
            .replace(legacyMixed, "StormDns")
    }

    private fun log(message: String, throwable: Throwable? = null) {
        val sanitizedMessage = sanitizeCoreBrand(message)
        val finalMessage = if (throwable != null) {
            "$sanitizedMessage • ${throwable.javaClass.simpleName}: ${throwable.message ?: "no message"}"
        } else {
            sanitizedMessage
        }
        if (finalMessage.contains("StormDNS", ignoreCase = true)) {
            StormDnsRuntimeLog.append(context, finalMessage)
        } else {
            RKhVpnLogStore.append(context, "CoreBin", finalMessage, throwable)
        }
    }
}
