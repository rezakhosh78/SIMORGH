package com.rkh.vpn.core

import android.content.Context
import android.system.Os
import android.system.OsConstants
import com.rkh.vpn.data.RKhVpnLogStore
import com.rkh.vpn.data.MasterDnsRuntimeLog
import java.io.File
import java.io.FileDescriptor
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

class ProcessCoreManager(private val context: Context) {
    private val io = Executors.newFixedThreadPool(4)
    private var xrayProcess: Process? = null
    private var tun2socksProcess: Process? = null
    private var nipoProcess: Process? = null
    private var masterDnsProcess: Process? = null
    private var masterDnsResolversFile: File? = null
    private val bin = NativeBinaryManager(context)

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
        val socksPort = inboundPort ?: Regex("""\"port\"\s*:\s*(\d+)""").find(configJson)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 10808
        if (inheritedTunFdNumber != null) log("Xray binary started with Android TUN fd remapped to stdin/env 0 from source fd=${inheritedTunFdNumber} and local inbound port=$socksPort") else log("Xray binary started on SOCKS 127.0.0.1:$socksPort")
        return socksPort
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


    fun startMasterDnsAgent(configToml: String, serverConfigToml: String = "", resolversText: String = "", socksPort: Int = 9993): Int {
        val coreFile = runCatching { bin.prepare("masterdns") }
            .recoverCatching { bin.prepare("stormdns") }
            .getOrElse { e ->
                throw IllegalStateException(
                    "Missing MasterDNS native core. Put the Android core as libmasterdns.so or libstormdns.so in app/src/main/jniLibs/<abi>/ before building.",
                    e
                )
            }
        val workDir = File(context.filesDir, "masterdns-runtime").apply { mkdirs() }
        val runtimeConfigToml = forceMasterDnsMtuExport(configToml)
        val configFile = File(workDir, "client_config.toml").apply { writeText(runtimeConfigToml) }
        val resolversFile = File(workDir, "client_resolvers.txt").apply { writeText(resolversText.ifBlank { extractMasterDnsResolvers(runtimeConfigToml) }) }
        masterDnsResolversFile = resolversFile
        log("MasterDNS runtime files ready • client=${configFile.absolutePath}(${configFile.length()}b) • resolvers=${resolversFile.absolutePath}(${resolversFile.length()}b)")
        val candidates = listOf(
            listOf(coreFile.absolutePath, "-config", configFile.absolutePath, "-resolvers", resolversFile.absolutePath)
        )

        var lastError: Throwable? = null
        var lastExit: Int? = null
        for (cmd in candidates) {
            try {
                log("Starting MasterDNS core: ${cmd.joinToString(" ")} • socks5=127.0.0.1:$socksPort")
                masterDnsProcess = ProcessBuilder(cmd)
                    .directory(workDir)
                    .redirectErrorStream(true)
                    .start()
                pump("MasterDNS", masterDnsProcess!!)
                Thread.sleep(1000)
                val exit = runCatching { masterDnsProcess?.exitValue() }.getOrNull()
                if (exit == null) {
                    log("MasterDNS core is alive; waiting for local SOCKS5 port 127.0.0.1:$socksPort to become ready")
                    if (!waitForMasterDnsSocksPortDuringLongMtuScan(socksPort)) {
                        runCatching { masterDnsProcess?.destroy() }
                        throw IllegalStateException("MasterDNS core stayed alive but local SOCKS5 port 127.0.0.1:$socksPort was not ready after long MTU/DNS scan wait")
                    }
                    log("MasterDNS SOCKS5 is ready on 127.0.0.1:$socksPort")
                    return socksPort
                }
                lastExit = exit
                log("MasterDNS candidate exited immediately: $exit")
            } catch (e: Throwable) {
                lastError = e
                log("MasterDNS candidate failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        throw IllegalStateException("Could not start MasterDNS core. Last exit=$lastExit, last error=${lastError?.message}", lastError)
    }


    private fun waitForMasterDnsSocksPortDuringLongMtuScan(socksPort: Int): Boolean {
        val maxWaitMs = 30L * 60L * 1000L
        val startedAt = System.currentTimeMillis()
        var lastProgressLogAt = 0L
        var lastProgressText = ""
        while (System.currentTimeMillis() - startedAt < maxWaitMs) {
            if (waitForLocalPort(socksPort, 1_000L)) return true
            val exitNow = runCatching { masterDnsProcess?.exitValue() }.getOrNull()
            if (exitNow != null) {
                log("MasterDNS core exited while waiting for SOCKS5 port: $exitNow")
                return false
            }
            val now = System.currentTimeMillis()
            if (now - lastProgressLogAt >= 15_000L) {
                val lines = MasterDnsRuntimeLog.read()
                val progress = masterDnsLatestMtuProgress(lines)
                val healthy = masterDnsAcceptedCount(lines)
                val text = if (progress.second > 0) "${progress.first}/${progress.second}" else "running"
                if (text != lastProgressText || now - lastProgressLogAt >= 30_000L) {
                    log("MasterDNS SOCKS5 not ready yet; MTU/DNS scan=$text • healthy=$healthy • waiting without killing core")
                    lastProgressText = text
                }
                lastProgressLogAt = now
            }
        }
        return false
    }

    private fun masterDnsLatestMtuProgress(lines: List<String>): Pair<Int, Int> {
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

    private fun masterDnsAcceptedCount(lines: List<String>): Int {
        val resolvers = masterDnsResolversFile?.takeIf { it.exists() }?.readLines()
            ?.mapNotNull { raw -> raw.trim().takeIf { it.isNotBlank() && !it.startsWith("#") }?.substringBefore("#")?.trim() }
            ?.map { it.removePrefix("[").substringBefore("]").substringBefore(":").trim() }
            ?.filter { it.isNotBlank() }
            ?.toSet()
            .orEmpty()
        val acceptedLine = Regex("(?i)✅\\s*accepted.*?\\bvia\\s+([^\\s|]+)")
        return lines.mapNotNull { line ->
            val dns = acceptedLine.find(line)?.groupValues?.getOrNull(1)
                ?.trim()
                ?.removePrefix("[")
                ?.substringBefore("]")
                ?.substringBefore(":")
            dns?.takeIf { resolvers.isEmpty() || resolvers.contains(it) }
        }.distinct().size
    }


    private fun forceMasterDnsMtuExport(configToml: String): String {
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
        // Keep the MasterDNS local DNS listener enabled so Xray can route port 53 to it.
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

    private fun defaultMasterDnsServerConfig(): String = """
        # SIMORGH MasterDNS server profile
        [server]
        listen = "0.0.0.0:53"
        protocol = "udp"
        mtu = 1232

        [dns]
        upstream = ["1.1.1.1:53", "8.8.8.8:53"]

        [runtime]
        log_level = "info"
    """.trimIndent()

    fun updateMasterDnsResolvers(resolversText: String) {
        val file = masterDnsResolversFile ?: File(context.filesDir, "masterdns-runtime/client_resolvers.txt")
        file.parentFile?.mkdirs()
        file.writeText(resolversText)
        log("MasterDNS client_resolvers.txt updated • ${file.length()}b • lines=${resolversText.lineSequence().count { it.trim().isNotBlank() }}")
    }


    private fun extractMasterDnsResolvers(configToml: String): String {
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
        val savedStdin = Os.dup(FileDescriptor.`in`)
        try {
            val tunFileDescriptor = fileDescriptorFromInt(tunFd)
            Os.dup2(tunFileDescriptor, 0)
            Os.fcntlInt(FileDescriptor.`in`, OsConstants.F_SETFD, 0)
            log("Prepared child stdin fd=0 for $label from Android TUN source fd=$tunFd")
            return ProcessBuilder(cmd)
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

    private fun fileDescriptorFromInt(fd: Int): FileDescriptor {
        val descriptor = FileDescriptor()
        val field = FileDescriptor::class.java.declaredFields.firstOrNull { it.name == "descriptor" || it.name == "fd" }
            ?: error("Could not access FileDescriptor integer field on this Android runtime")
        field.isAccessible = true
        field.setInt(descriptor, fd)
        return descriptor
    }

    fun stop() {
        log("Stopping binary core processes")
        val oldTun2Socks = tun2socksProcess
        val oldXray = xrayProcess
        val oldNipo = nipoProcess
        val oldMasterDns = masterDnsProcess
        tun2socksProcess = null
        xrayProcess = null
        nipoProcess = null
        masterDnsProcess = null
        stopProcessGracefully("tun2socks", oldTun2Socks)
        stopProcessGracefully("xray", oldXray)
        stopProcessGracefully("nipovpn", oldNipo)
        stopProcessGracefully("masterdns", oldMasterDns)
        cleanupSharedGeoAssets()
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

    private fun pump(tag: String, process: Process) {
        io.submit {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (line.isNotBlank()) {
                        val msg = "[$tag] $line"
                        if (tag.equals("MasterDNS", ignoreCase = true)) MasterDnsRuntimeLog.append(msg) else log(msg)
                    }
                }
            }
        }
    }

    private fun log(message: String) {
        if (message.contains("MasterDNS", ignoreCase = true)) {
            MasterDnsRuntimeLog.append(message)
        } else {
            RKhVpnLogStore.append(context, "CoreBin", message, null)
        }
    }
}
