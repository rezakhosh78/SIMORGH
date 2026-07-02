package com.rkh.vpn.core

import android.content.Context
import com.rkh.vpn.data.RKhVpnLogStore
import com.rkh.vpn.data.ServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

class PingEngine(private val context: Context) {
    private val nativeBinaryManager = NativeBinaryManager(context)

    suspend fun ping(server: ServerConfig): ServerConfig = withContext(Dispatchers.IO) {
        if (server.raw.isBlank()) return@withContext server.copy(pingMs = null, error = "Empty config")
        runCatching { realXrayLatency(server) }
            .getOrElse { server.copy(pingMs = null, error = (it.message ?: it.javaClass.simpleName).take(120)) }
    }

    suspend fun pingQuick(server: ServerConfig): ServerConfig = withContext(Dispatchers.IO) {
        if (server.raw.isBlank()) return@withContext server.copy(pingMs = null, error = "Empty config")
        withTimeoutOrNull(6200L) {
            runCatching { realXrayLatency(server, bootTimeoutMs = 2200L, requestTimeoutMs = 2400) }
                .getOrElse { server.copy(pingMs = null, error = (it.message ?: it.javaClass.simpleName).take(120)) }
        } ?: server.copy(pingMs = null, error = "Quick ping timeout")
    }

    suspend fun pingStrict3(server: ServerConfig): ServerConfig = withContext(Dispatchers.IO) {
        if (server.raw.isBlank()) return@withContext server.copy(pingMs = null, error = "Empty config")
        val samples = ArrayList<Long>(3)
        var lastError: String? = null
        for (attempt in 1..3) {
            val tested = runCatching { realXrayLatency(server, bootTimeoutMs = 3200L, requestTimeoutMs = 3600) }
                .getOrElse { e ->
                    lastError = (e.message ?: e.javaClass.simpleName).take(140)
                    null
                }
            val ping = tested?.pingMs
            if (ping == null || ping <= 0L) {
                log("Strict 3x Xray ping failed for ${server.name} at attempt $attempt/3: ${lastError ?: tested?.error ?: "no ping"}")
                return@withContext server.copy(
                    pingMs = null,
                    error = "3x Xray ping failed at $attempt/3: ${lastError ?: tested?.error ?: "no ping"}".take(160)
                )
            }
            samples += ping
        }
        val finalPing = samples.average().toLong().coerceAtLeast(1L)
        log("Strict 3x Xray ping OK for ${server.name}: ${samples.joinToString("/")}ms → ${finalPing}ms")
        server.copy(pingMs = finalPing, error = null)
    }
    suspend fun pingStrict3WithSni(server: ServerConfig, sniHost: String = "google.com"): ServerConfig = withContext(Dispatchers.IO) {
        if (server.raw.isBlank()) return@withContext server.copy(pingMs = null, error = "Empty config")
        val samples = ArrayList<Long>(3)
        var lastError: String? = null
        for (attempt in 1..3) {
            val tested = runCatching { realXrayLatency(server, bootTimeoutMs = 3200L, requestTimeoutMs = 4200, sniHost = sniHost) }
                .getOrElse { e ->
                    lastError = (e.message ?: e.javaClass.simpleName).take(140)
                    null
                }
            val ping = tested?.pingMs
            if (ping == null || ping <= 0L) {
                log("Strict 3x Xray SNI ping failed for ${server.name} at attempt $attempt/3: ${lastError ?: tested?.error ?: "no ping"}")
                return@withContext server.copy(
                    pingMs = null,
                    error = "3x Xray SNI($sniHost) failed at $attempt/3: ${lastError ?: tested?.error ?: "no ping"}".take(180)
                )
            }
            samples += ping
        }
        val finalPing = samples.average().toLong().coerceAtLeast(1L)
        log("Strict 3x Xray SNI($sniHost) OK for ${server.name}: ${samples.joinToString("/")}ms → ${finalPing}ms")
        server.copy(pingMs = finalPing, error = null)
    }


    suspend fun pingAll(list: List<ServerConfig>): List<ServerConfig> = withContext(Dispatchers.IO) {
        val result = ArrayList<ServerConfig>(list.size)
        for (server in list) result += ping(server)
        result.sortedWith(compareBy<ServerConfig> { it.pingMs ?: Long.MAX_VALUE }.thenBy { it.name })
    }

    suspend fun findBestFast(list: List<ServerConfig>, parallelism: Int = 8): ServerConfig? = coroutineScope {
        val safeParallelism = parallelism.coerceIn(1, 8)
        for (batch in list.chunked(safeParallelism)) {
            val tested = batch.map { server ->
                async(Dispatchers.IO) { pingQuick(server) }
            }.awaitAll()
            val best = tested.filter { it.pingMs != null }.minByOrNull { it.pingMs!! }
            if (best != null) return@coroutineScope best
        }
        null
    }

    fun best(list: List<ServerConfig>) = list.filter { it.pingMs != null }.minByOrNull { it.pingMs!! }

    private fun realXrayLatency(server: ServerConfig, bootTimeoutMs: Long = 5000L, requestTimeoutMs: Int = 5000, sniHost: String? = null): ServerConfig {
        val xray = nativeBinaryManager.prepare("xray")
        cleanupOldPingRuntimeDirs()
        val runtimeDir = File(context.cacheDir, "xray-ping/${server.id}_${System.currentTimeMillis()}").apply { mkdirs() }
        val socksPort = findFreePort()
        val configFile = File(runtimeDir, "config.json")
        val configJson = XrayBinaryConfigBuilder.socksConfigFromRaw(server.raw, socksPort)
        configFile.writeText(configJson)
        val assetDir = prepareXrayAssetDir(configJson)
        var process: Process? = null
        val logLines = mutableListOf<String>()
        try {
            log("Starting real Xray latency test for ${server.name} on port $socksPort")
            process = ProcessBuilder(xray.absolutePath, "run", "-config", configFile.absolutePath)
                .directory(runtimeDir)
                .apply {
                    environment()["XRAY_LOCATION_ASSET"] = assetDir.absolutePath
                    environment()["V2RAY_LOCATION_ASSET"] = assetDir.absolutePath
                }
                .redirectErrorStream(true)
                .start()
            val proc = process!!
            val gobbler = Thread {
                runCatching {
                    proc.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            synchronized(logLines) {
                                if (logLines.size < 18) logLines += line.take(180)
                            }
                        }
                    }
                }
            }
            gobbler.isDaemon = true
            gobbler.start()

            if (!waitForLocalPort(socksPort, bootTimeoutMs)) {
                val details = synchronized(logLines) { logLines.joinToString(" | ") }
                error("Xray SOCKS port did not open. ${details.ifBlank { "No Xray output" }}")
            }

            val avg = if (sniHost.isNullOrBlank()) {
                measureProxyRoundTrip(socksPort, requestTimeoutMs)
            } else {
                measureProxySniRoundTrip(socksPort, sniHost, requestTimeoutMs)
            }.coerceAtLeast(1L)
            log("Real latency for ${server.name}${sniHost?.let { " via SNI $it" } ?: ""}: ${avg}ms")
            return server.copy(pingMs = avg, error = null)
        } finally {
            process?.destroy()
            runCatching {
                if (process?.waitFor(700, TimeUnit.MILLISECONDS) != true) {
                    process?.destroyForcibly()
                    process?.waitFor(700, TimeUnit.MILLISECONDS)
                }
            }
            runtimeDir.deleteRecursively()
        }
    }



    private fun cleanupOldPingRuntimeDirs() {
        val parent = File(context.cacheDir, "xray-ping")
        if (!parent.exists()) return
        val now = System.currentTimeMillis()
        parent.listFiles()?.forEach { child ->
            if (now - child.lastModified() > PING_RUNTIME_KEEP_MS) {
                runCatching { if (child.isDirectory) child.deleteRecursively() else child.delete() }
            }
        }
        val children = parent.listFiles().orEmpty().sortedByDescending { it.lastModified() }
        if (children.size > PING_RUNTIME_MAX_DIRS) {
            children.drop(PING_RUNTIME_MAX_DIRS).forEach { old ->
                runCatching { if (old.isDirectory) old.deleteRecursively() else old.delete() }
            }
        }
    }

    private fun prepareXrayAssetDir(configJson: String): File {
        if (!configJson.contains("geosite:") && !configJson.contains("geoip:")) {
            return File(context.cacheDir, "xray-empty-assets").apply { mkdirs() }
        }
        return File(context.filesDir, "xray-assets").apply {
            mkdirs()
            ensureXrayGeoAssets(this)
        }
    }

    private fun ensureXrayGeoAssets(assetDir: File) {
        listOf("geosite.dat", "geoip.dat").forEach { assetName ->
            val outFile = File(assetDir, assetName)
            if (outFile.exists() && outFile.length() > 1024L) return@forEach
            runCatching {
                context.assets.open("xray/$assetName").use { input ->
                    outFile.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }

    private fun measureProxyRoundTrip(port: Int, timeoutMs: Int = 5000): Long {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
        return measureTimeMillis {
            Socket(proxy).use { socket ->
                socket.soTimeout = timeoutMs
                socket.connect(InetSocketAddress.createUnresolved("connectivitycheck.gstatic.com", 80), timeoutMs)
                val out = socket.getOutputStream()
                out.write("GET /generate_204 HTTP/1.1\r\nHost: connectivitycheck.gstatic.com\r\nConnection: close\r\n\r\n".toByteArray())
                out.flush()
                val input = socket.getInputStream()
                val buf = ByteArray(128)
                val n = input.read(buf)
                if (n <= 0) error("No response from test target")
            }
        }
    }


    private fun measureProxySniRoundTrip(port: Int, host: String, timeoutMs: Int = 5000): Long {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
        val started = System.currentTimeMillis()
        Socket(proxy).use { raw ->
            raw.soTimeout = timeoutMs
            raw.connect(InetSocketAddress.createUnresolved(host, 443), timeoutMs)
            val ssl = SSLContext.getDefault().socketFactory.createSocket(raw, host, 443, true) as SSLSocket
            ssl.use { socket ->
                socket.soTimeout = timeoutMs
                socket.sslParameters = socket.sslParameters.apply {
                    serverNames = listOf(SNIHostName(host))
                    endpointIdentificationAlgorithm = "HTTPS"
                }
                socket.startHandshake()
                val out = socket.getOutputStream()
                out.write("HEAD / HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n".toByteArray())
                out.flush()
                val input = socket.getInputStream()
                val buf = ByteArray(128)
                val n = input.read(buf)
                if (n <= 0) error("No TLS/SNI response from $host")
            }
        }
        return (System.currentTimeMillis() - started).coerceAtLeast(1L)
    }

    private fun waitForLocalPort(port: Int, timeoutMs: Long): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress("127.0.0.1", port), 180)
                    return true
                }
            } catch (_: Throwable) {
                Thread.sleep(70)
            }
        }
        return false
    }

    private fun findFreePort(): Int = ServerSocket(0).use { it.localPort }

    private fun log(message: String, throwable: Throwable? = null) =
        RKhVpnLogStore.append(context, "Ping", message, throwable)

    private companion object {
        private const val PING_RUNTIME_KEEP_MS = 30_000L
        private const val PING_RUNTIME_MAX_DIRS = 1
    }
}
