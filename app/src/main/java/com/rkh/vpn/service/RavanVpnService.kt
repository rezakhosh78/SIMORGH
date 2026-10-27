package com.rkh.vpn.service

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import com.ravan.core.RavanCommandBackend
import com.ravan.core.RavanCore
import com.ravan.core.RavanEvent
import com.ravan.core.RavanEventListener
import com.ravan.core.RavanProbe
import com.ravan.core.RavanProfiles
import com.ravan.core.RavanTransport
import com.ravan.core.RavanWarpPlus
import com.rkh.vpn.core.Tun2ProxyBridge
import com.rkh.vpn.core.ProcessCoreManager
import com.rkh.vpn.core.SimorghDnsTunnelConfig
import com.rkh.vpn.core.SimorghWarpAccount
import com.rkh.vpn.core.SimorghWarpTunnelConfig
import com.rkh.vpn.core.WarpAccountRegistration
import com.rkh.vpn.data.RKhVpnLogStore
import org.amnezia.awg.GoBackend
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/** Separate Ravan tunnel service. It does not share the five legacy sections. */
class RavanVpnService : VpnService() {
    companion object {
        const val ACTION_START = "com.rkh.simorgh.RAVAN_START"
        const val ACTION_STOP = "com.rkh.simorgh.RAVAN_STOP"
        const val EXTRA_NESTED = "ravan_nested"
        const val EXTRA_TRANSPORT = "ravan_transport"
        private const val STATE_PREFS = "simorgh_ravan_state"
        private const val WARP_SOCKS_PORT = 18086
        private const val SOAR_SOCKS_PORT = 18087
        private const val DNSTT_SOCKS_PORT = 18088
    }

    private val prefs by lazy { getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE) }
    @Volatile private var ravan: RavanCore? = null
    @Volatile private var backend: RavanCommandBackend? = null
    @Volatile private var processCoreManager: ProcessCoreManager? = null
    @Volatile private var externalProcess: Process? = null
    @Volatile private var awgHandle: Int = 0
    @Volatile private var generatedConfigFile: File? = null
    private var tun: ParcelFileDescriptor? = null
    private var detachedTunFd: Int? = null
    private var tunThread: Thread? = null
    @Volatile private var startupThread: Thread? = null
    private var startupGeneration = 0L
    @Volatile private var running = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return runCatching {
            when (intent?.action) {
                ACTION_START -> startTunnel(intent.getStringExtra(EXTRA_TRANSPORT).orEmpty().ifBlank { if (intent.getBooleanExtra(EXTRA_NESTED, false)) "warp_in_warp" else "warp" })
                ACTION_STOP -> stopTunnel()
            }
            START_STICKY
        }.getOrElse { error ->
            record("FAILED", error.message ?: error.javaClass.simpleName)
            log("Ravan service failed safely", error)
            stopTunnel()
            START_NOT_STICKY
        }
    }

    @Synchronized
    private fun startTunnel(requestedPage: String) {
        if (running || ravan != null || tunThread != null || startupThread != null) {
            log("Ignoring duplicate Ravan start request while a transport is already starting/running")
            return
        }
        val generation = ++startupGeneration
        val worker = thread(name = "ravan-start", isDaemon = true, start = false) {
            runCatching { startTunnelInternal(requestedPage, generation) }
                .onFailure { error ->
                    val current = synchronized(this) { generation == startupGeneration }
                    if (current && error.message != "Ravan start cancelled") {
                        record("FAILED", error.message ?: error.javaClass.simpleName)
                        log("Ravan service failed safely", error)
                        stopTunnel()
                    }
                }
        }
        startupThread = worker
        worker.start()
    }

    private fun startTunnelInternal(requestedPage: String, generation: Long) {
        when (requestedPage) {
            "warp_wireguard" -> {
                startWarpWireGuard(generation)
                return
            }
            "warp_awg" -> {
                startWarpAwg(generation)
                return
            }
            "soardns" -> {
                startDnsProxy("soardns", generation)
                return
            }
            "dnstt" -> {
                startDnsProxy("dnstt", generation)
                return
            }
        }
        val nested = requestedPage == "warp_in_warp"
        val transport = when (requestedPage) {
            "warp_in_warp" -> RavanTransport.NESTED_WARP
            else -> RavanTransport.WARP
        }
        val label = when (requestedPage) {
            "warp_in_warp" -> "WARP-in-WARP"
            "warp" -> "WARP Plus"
            else -> "WARP Plus"
        }
        startForeground(2201, NotificationHelper.publicVpn(this, label, "Starting independent transport", connected = false))
        val binary = prepareWarpPlusBinary()
        val localPort = WARP_SOCKS_PORT
        val runtimeDir = File(filesDir, "ravan/runtime").apply { mkdirs() }
        check(runtimeDir.isDirectory && runtimeDir.canWrite()) {
            "Ravan runtime directory is not writable: ${runtimeDir.absolutePath}"
        }
        val cacheDir = File(runtimeDir, ".cache/warp-plus").apply { mkdirs() }
        check(cacheDir.isDirectory && cacheDir.canWrite()) {
            "Ravan cache directory is not writable: ${cacheDir.absolutePath}"
        }
        log("Ravan runtime prepared • cwd=${runtimeDir.absolutePath} • cache=${cacheDir.absolutePath}")
        val key = prefs.getString("ravanWarpKey", "").orEmpty()
        val requestedEndpoint = prefs.getString("ravanWarpEndpoint", "engage.cloudflareclient.com:443").orEmpty()
        val endpoint = normalizeWarpEndpoint(requestedEndpoint)
        if (requestedEndpoint.isNotBlank() && endpoint == null) {
            log("Configured WARP endpoint is not a usable IP endpoint; warp-plus will select a fresh endpoint")
        }
        record("STARTING", "$label requested; endpointConfigured=${endpoint != null}; keyProvided=${key.isNotBlank()}")

        val commandBackend = RavanCommandBackend(
            transports = setOf(RavanTransport.WARP, RavanTransport.NESTED_WARP),
            commandFactory = { hop, _ ->
                RavanWarpPlus.command(
                    executable = binary.absolutePath,
                    localSocksPort = localPort,
                    warpKey = key,
                    endpoint = endpoint,
                    nested = nested,
                    cacheDir = cacheDir.absolutePath
                )
            },
            environment = mapOf(
                "HOME" to runtimeDir.absolutePath,
                "XDG_CACHE_HOME" to File(runtimeDir, ".cache").absolutePath,
                "TMPDIR" to File(runtimeDir, "tmp").apply { mkdirs() }.absolutePath
            ),
            workingDirectory = runtimeDir,
            log = ::log
        )
        val core = RavanCore(listener = RavanEventListener { event ->
            when (event) {
                is RavanEvent.StateChanged -> record(event.to.name, "${event.from} -> ${event.to}")
                is RavanEvent.Error -> record("FAILED", event.message)
                else -> log("Transport event: $event")
            }
        })
        core.register(transport, commandBackend)
        synchronized(this) {
            if (generation != startupGeneration) error("Ravan start cancelled")
            ravan = core
            backend = commandBackend
        }
        core.start(RavanProfiles.warpPlus(localPort, nested, transport))
        waitForLocalPort(localPort, 20_000L, generation)
        synchronized(this) {
            if (generation != startupGeneration) error("Ravan start cancelled")
        }
        core.reportProbe(RavanProbe(true, detail = "$label SOCKS5 ready"))

        val established = Builder()
            .setSession(label)
            .addAddress("172.31.0.2", 30)
            .addDnsServer("1.1.1.1")
            .addDnsServer("9.9.9.9")
            .addRoute("0.0.0.0", 0)
            .allowFamily(OsConstants.AF_INET)
            .setMtu(1280)
            .also { builder -> TunnelAppPolicy.apply(this, builder) }
            .establish() ?: error("Ravan VPN permission is missing")
        synchronized(this) {
            if (generation != startupGeneration) {
                runCatching { established.close() }
                error("Ravan start cancelled")
            }
        }
        val fd = established.detachFd()
        detachedTunFd = fd
        running = true
        record("RUNNING", "$label connected; TUN → SOCKS5 127.0.0.1:$localPort")
        startForeground(2201, NotificationHelper.publicVpn(this, label, "Transport connected", connected = true))
        tunThread = thread(name = "ravan-tun", isDaemon = false) {
            runCatching {
                Tun2ProxyBridge.runWithFd("socks5://127.0.0.1:$localPort", fd, closeFdOnDrop = false, tunMtu = 1280)
            }.onFailure { error ->
                log("Ravan tun2proxy loop failed", error)
                record("FAILED", error.message ?: error.javaClass.simpleName)
            }.also {
                detachedTunFd = null
                if (running) {
                    running = false
                    runCatching { ravan?.reportProbe(RavanProbe(false, detail = "TUN loop stopped")) }
                }
            }
        }
        synchronized(this) {
            if (generation == startupGeneration && startupThread === Thread.currentThread()) {
                startupThread = null
            }
        }
    }

    private fun startWarpWireGuard(generation: Long) {
        val label = "WARP WireGuard"
        startForeground(2201, NotificationHelper.publicVpn(this, label, "Registering WARP account", connected = false))
        record("STARTING", "$label requested")
        val account = getOrRegisterWarpAccount()
        val requestedEndpoint = prefs.getString("ravanWarpEndpoint", "engage.cloudflareclient.com:443").orEmpty().trim()
        val endpoint = resolveRegisteredWarpEndpoint(account, requestedEndpoint)
        check(generation == synchronized(this) { startupGeneration }) { "Ravan start cancelled" }
        val core = ProcessCoreManager(this)
        processCoreManager = core
        val socksPort = core.startXray(SimorghWarpTunnelConfig.xray(account, endpoint, WARP_SOCKS_PORT))
        waitForLocalPort(socksPort, 20_000L, generation)
        establishProxyTunnel(label, socksPort, account.mtu, generation)
        log("WARP WireGuard outbound is active through Xray; endpoint=$endpoint; reserved=${account.reserved.joinToString()}")
    }

    private fun startWarpAwg(generation: Long) {
        val label = "WARP AWG"
        startForeground(2201, NotificationHelper.publicVpn(this, label, "Registering WARP account", connected = false))
        record("STARTING", "$label requested")
        val account = getOrRegisterWarpAccount()
        val requestedEndpoint = prefs.getString("ravanWarpEndpoint", "engage.cloudflareclient.com:443").orEmpty().trim()
        val endpoint = resolveRegisteredWarpEndpoint(account, requestedEndpoint)
        check(generation == synchronized(this) { startupGeneration }) { "Ravan start cancelled" }

        val builder = Builder()
            .setSession(label)
            .setMtu(account.mtu)
            .addDnsServer("1.1.1.1")
            .addDnsServer("1.0.0.1")
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .allowFamily(OsConstants.AF_INET)
            .allowFamily(OsConstants.AF_INET6)
        account.localAddresses.forEach { cidr ->
            val split = cidr.split('/', limit = 2)
            val prefix = split.getOrNull(1)?.toIntOrNull() ?: error("Invalid WARP interface address")
            builder.addAddress(split[0], prefix)
        }
        TunnelAppPolicy.apply(this, builder)
        val established = builder.establish() ?: error("Ravan VPN permission is missing")
        val lifecycleTun = runCatching { ParcelFileDescriptor.dup(established.fileDescriptor) }.getOrElse { error ->
            runCatching { established.close() }
            throw error
        }
        tun = lifecycleTun
        val engineFd = established.detachFd()
        val handle = try {
            GoBackend.awgTurnOn("awg0", engineFd, SimorghWarpTunnelConfig.awgUapi(account, endpoint, awg = true))
        } catch (error: Throwable) {
            runCatching { ParcelFileDescriptor.adoptFd(engineFd).close() }
            runCatching { lifecycleTun.close() }
            tun = null
            throw error
        }
        if (handle < 0) {
            runCatching { ParcelFileDescriptor.adoptFd(engineFd).close() }
            runCatching { lifecycleTun.close() }
            tun = null
            error("AmneziaWG could not start the WARP tunnel")
        }
        awgHandle = handle
        try {
            listOf(GoBackend.awgGetSocketV4(handle), GoBackend.awgGetSocketV6(handle))
                .filter { it >= 0 }.distinct().forEach { socketFd ->
                    check(protect(socketFd)) { "Android could not protect the WARP AWG socket from its own VPN" }
                }
        } catch (error: Throwable) {
            runCatching { GoBackend.awgTurnOff(handle) }
            awgHandle = 0
            runCatching { lifecycleTun.close() }
            tun = null
            throw error
        }
        running = true
        record("RUNNING", "$label connected; AmneziaWG Go TUN active; endpoint=$endpoint")
        startForeground(2201, NotificationHelper.publicVpn(this, label, "Transport connected", connected = true))
        finishStartupWorker(generation)
    }

    private fun startDnsProxy(page: String, generation: Long) {
        val soar = page == "soardns"
        val label = if (soar) "SoarDNS" else "DNSTT"
        val port = if (soar) SOAR_SOCKS_PORT else DNSTT_SOCKS_PORT
        startForeground(2201, NotificationHelper.publicVpn(this, label, "Starting DNS tunnel", connected = false))
        record("STARTING", "$label requested")
        val runtimeDir = File(filesDir, "ravan/${page}").apply { mkdirs() }
        check(runtimeDir.isDirectory && runtimeDir.canWrite()) { "$label runtime directory is not writable" }
        val binaryName = if (soar) "libsoar.so" else "libdnstt.so"
        val binary = File(applicationInfo.nativeLibraryDir, binaryName)
        check(binary.isFile && binary.canExecute() && binary.length() > 1_000_000L) {
            "$label native core is missing for this APK ABI: $binaryName"
        }
        val command = if (soar) {
            val config = SimorghDnsTunnelConfig.soar(
                zone = prefs.getString("soarZone", "").orEmpty(),
                serverKey = prefs.getString("soarServerKey", "").orEmpty(),
                resolvers = prefs.getString("soarResolvers", "1.1.1.1\n8.8.8.8").orEmpty(),
                port = port,
                cacheDir = File(runtimeDir, "cache").apply { mkdirs() }.absolutePath,
            )
            File(runtimeDir, "client.json").apply {
                writeText(config.toString())
                generatedConfigFile = this
            }
            listOf(binary.absolutePath, "--config", File(runtimeDir, "client.json").absolutePath)
        } else {
            val transport = prefs.getString("dnsttTransport", "udp").orEmpty()
            val resolver = prefs.getString("dnsttResolver", "1.1.1.1").orEmpty()
            val key = prefs.getString("dnsttPublicKey", "").orEmpty()
            val domain = prefs.getString("dnsttDomain", "").orEmpty()
            SimorghDnsTunnelConfig.dnstt(binary.absolutePath, transport, resolver, key, domain, port)
        }
        val child = ProcessBuilder(command)
            .directory(runtimeDir)
            .redirectErrorStream(true)
            .start()
        externalProcess = child
        thread(name = "ravan-$page-log", isDaemon = true) {
            runCatching {
                child.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (line.isNotBlank()) RKhVpnLogStore.append(this, label, line.take(1_000))
                    }
                }
            }
        }
        waitForLocalPort(port, 30_000L, generation, child)
        check(generation == synchronized(this) { startupGeneration }) { "Ravan start cancelled" }
        record("STARTING", "$label local SOCKS5 ready at 127.0.0.1:$port")
        establishProxyTunnel(label, port, 1280, generation)
    }

    private fun establishProxyTunnel(label: String, socksPort: Int, mtu: Int, generation: Long) {
        val established = Builder()
            .setSession(label)
            .addAddress("172.31.0.2", 30)
            .addDnsServer("1.1.1.1")
            .addDnsServer("9.9.9.9")
            .addRoute("0.0.0.0", 0)
            .allowFamily(OsConstants.AF_INET)
            .setMtu(mtu.coerceIn(576, 1500))
            .also { builder -> TunnelAppPolicy.apply(this, builder) }
            .establish() ?: error("Ravan VPN permission is missing")
        check(generation == synchronized(this) { startupGeneration }) {
            runCatching { established.close() }
            "Ravan start cancelled"
        }
        val fd = established.detachFd()
        detachedTunFd = fd
        running = true
        record("RUNNING", "$label connected; Android TUN → SOCKS5 127.0.0.1:$socksPort")
        startForeground(2201, NotificationHelper.publicVpn(this, label, "Transport connected", connected = true))
        tunThread = thread(name = "ravan-tun", isDaemon = false) {
            runCatching {
                Tun2ProxyBridge.runWithFd("socks5://127.0.0.1:$socksPort", fd, closeFdOnDrop = false, tunMtu = mtu.coerceIn(576, 1500))
            }.onFailure { error ->
                log("Ravan tun2proxy loop failed", error)
                record("FAILED", error.message ?: error.javaClass.simpleName)
            }.also {
                detachedTunFd = null
                if (running) running = false
            }
        }
        finishStartupWorker(generation)
    }

    private fun finishStartupWorker(generation: Long) {
        synchronized(this) {
            if (generation == startupGeneration && startupThread === Thread.currentThread()) startupThread = null
        }
    }

    private fun getOrRegisterWarpAccount(): SimorghWarpAccount {
        val cached = prefs.getString("warpRegisteredAccount", "").orEmpty()
        if (cached.isNotBlank()) runCatching { decodeWarpAccount(cached) }.getOrNull()?.let { return it }
        record("STARTING", "Registering a Cloudflare WARP account")
        val account = WarpAccountRegistration.register()
        prefs.edit().putString("warpRegisteredAccount", encodeWarpAccount(account)).apply()
        return account
    }

    private fun resolveRegisteredWarpEndpoint(account: SimorghWarpAccount, requested: String): String =
        if (requested.isBlank() || requested.equals("engage.cloudflareclient.com:443", true)) {
            WarpAccountRegistration.resolveEndpoint(WarpAccountRegistration.endpointHostPort(account.endpointHost, account.endpointPort))
        } else WarpAccountRegistration.resolveEndpoint(requested)

    private fun encodeWarpAccount(account: SimorghWarpAccount): String = JSONObject()
        .put("privateKey", account.privateKey)
        .put("publicKey", account.publicKey)
        .put("localAddresses", JSONArray(account.localAddresses))
        .put("peerPublicKey", account.peerPublicKey)
        .put("endpointHost", account.endpointHost)
        .put("endpointPort", account.endpointPort)
        .put("reserved", JSONArray(account.reserved))
        .put("mtu", account.mtu)
        .toString()

    private fun decodeWarpAccount(raw: String): SimorghWarpAccount {
        val obj = JSONObject(raw)
        fun strings(key: String): List<String> {
            val values = obj.optJSONArray(key) ?: JSONArray()
            return (0 until values.length()).mapNotNull { values.optString(it).takeIf(String::isNotBlank) }
        }
        val reservedArray = obj.optJSONArray("reserved") ?: JSONArray()
        val reserved = (0 until reservedArray.length()).mapNotNull { reservedArray.optInt(it).takeIf { value -> value in 0..255 } }
        return SimorghWarpAccount(
            privateKey = obj.getString("privateKey"),
            publicKey = obj.getString("publicKey"),
            localAddresses = strings("localAddresses"),
            peerPublicKey = obj.getString("peerPublicKey"),
            endpointHost = obj.getString("endpointHost"),
            endpointPort = obj.getInt("endpointPort"),
            reserved = reserved,
            mtu = obj.optInt("mtu", 1280),
        ).also { account ->
            require(account.localAddresses.isNotEmpty() && account.reserved.size == 3 && account.endpointPort in 1..65535)
        }
    }

    @Synchronized
    private fun stopTunnel() {
        startupGeneration++
        val starting = startupThread
        startupThread = null
        val hadState = running || ravan != null || detachedTunFd != null || starting != null ||
            processCoreManager != null || externalProcess != null || awgHandle != 0 || tun != null
        running = false
        val oldAwgHandle = awgHandle
        awgHandle = 0
        if (oldAwgHandle > 0) runCatching { GoBackend.awgTurnOff(oldAwgHandle) }
        if (tunThread != null) runCatching { Tun2ProxyBridge.requestStop() }
        tunThread?.let { thread -> runCatching { thread.join(1_000L) } }
        tunThread = null
        detachedTunFd?.let { fd -> runCatching { ParcelFileDescriptor.adoptFd(fd).close() } }
        detachedTunFd = null
        runCatching { tun?.close() }
        tun = null
        runCatching { processCoreManager?.stop(allowProcessExitOnTun2ProxyReturn = false) }
        processCoreManager = null
        externalProcess?.let { process ->
            runCatching { process.destroy() }
            if (!runCatching { process.waitFor(750L, java.util.concurrent.TimeUnit.MILLISECONDS) }.getOrDefault(false)) {
                runCatching { process.destroyForcibly() }
            }
        }
        externalProcess = null
        runCatching { generatedConfigFile?.delete() }
        generatedConfigFile = null
        runCatching { ravan?.stop() }
        ravan = null
        backend = null
        if (starting != null && starting !== Thread.currentThread()) {
            runCatching { starting.join(1_000L) }
        }
        if (hadState) record("STOPPED", "Ravan tunnel stopped")
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
            else @Suppress("DEPRECATION") stopForeground(true)
        }
    }

    private fun prepareWarpPlusBinary(): File {
        val packaged = File(applicationInfo.nativeLibraryDir, "libwarpplus.so")
        if (packaged.exists() && packaged.length() >= 1_000_000L && packaged.canExecute()) {
            log("Using executable warp-plus from nativeLibraryDir: ${packaged.absolutePath} (${packaged.length()} bytes)")
            return packaged
        }
        if (packaged.exists()) {
            log("Packaged warp-plus is present but not executable: ${packaged.absolutePath} (${packaged.length()} bytes)")
        }
        val dir = File(filesDir, "ravan").apply { mkdirs() }
        val binary = File(dir, "warp-plus")
        if (!binary.exists() || binary.length() < 1_000_000L) {
            assets.open("ravan/warp-plus").use { input -> binary.outputStream().use { output -> input.copyTo(output) } }
        }
        check(binary.length() >= 1_000_000L) { "Ravan warp-plus asset is missing or incomplete" }
        if (!binary.setExecutable(true, false) && !binary.canExecute()) {
            error("warp-plus binary cannot be executed from app files directory")
        }
        log("Using fallback warp-plus from filesDir: ${binary.absolutePath} (${binary.length()} bytes)")
        return binary
    }

    private fun waitForLocalPort(port: Int, timeoutMs: Long, generation: Long, process: Process? = null) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            if (generation != synchronized(this) { startupGeneration }) {
                error("Ravan start cancelled")
            }
            if (process != null && !process.isAlive) {
                val exit = runCatching { process.exitValue() }.getOrNull()
                error("Native tunnel core exited before opening 127.0.0.1:$port (exit=${exit ?: "unknown"})")
            }
            runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", port), 250)
                }
                return
            }.onFailure { last = it }
            Thread.sleep(200L)
        }
        error("SOCKS5 listener did not open on 127.0.0.1:$port (${last?.message ?: "timeout"})")
    }

    private fun normalizeWarpEndpoint(raw: String): String? {
        val value = raw.trim()
        if (value.isBlank() || value.equals("engage.cloudflareclient.com:443", ignoreCase = true)) return null
        val split = if (value.startsWith("[")) {
            val close = value.indexOf(']')
            if (close <= 1 || value.getOrNull(close + 1) != ':') return null
            value.substring(1, close) to value.substring(close + 2)
        } else {
            val separator = value.lastIndexOf(':')
            if (separator <= 0 || separator != value.indexOf(':')) return null
            value.substring(0, separator) to value.substring(separator + 1)
        }
        val port = split.second.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val addresses = runCatching { InetAddress.getAllByName(split.first) }.getOrNull() ?: return null
        val address = addresses.firstOrNull { it.address.size == 4 } ?: addresses.firstOrNull() ?: return null
        val host = address.hostAddress?.substringBefore('%') ?: return null
        return if (host.contains(':')) "[$host]:$port" else "$host:$port"
    }

    private fun record(state: String, detail: String) {
        prefs.edit().putString("state", state).putString("detail", detail).putLong("updatedAt", System.currentTimeMillis()).apply()
        RKhVpnLogStore.append(this, "Ravan", "$state • $detail")
    }

    private fun log(message: String, error: Throwable? = null) {
        RKhVpnLogStore.append(this, "Ravan", message, error)
    }

    override fun onRevoke() {
        stopTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopTunnel()
        super.onDestroy()
    }
}
