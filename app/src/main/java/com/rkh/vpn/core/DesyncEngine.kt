package com.rkh.vpn.core

import android.content.Context
import com.rkh.vpn.data.RKhVpnLogStore
import java.net.ServerSocket

object DesyncEngine {
    const val PROFILE_OFF = "Off"
    const val PROFILE_LIGHT = "Light"
    const val PROFILE_BALANCED = "Balanced"
    const val PROFILE_SEVERE = "Severe"
    const val PROFILE_ADAPTIVE = "Adaptive"
    const val PROFILE_CUSTOM = "Custom"

    @Volatile private var proxy: DesyncProxy? = null
    @Volatile private var worker: Thread? = null
    @Volatile private var activePort: Int = 0

    fun port(): Int = activePort

    @Synchronized
    fun start(context: Context, profile: String, customArguments: String): Int {
        stop(context)
        val normalized = profile.trim().ifBlank { PROFILE_OFF }
        check(normalized != PROFILE_OFF) { "Desync profile is Off" }
        val port = ServerSocket(0).use { it.localPort }
        val args = mutableListOf<String>().apply {
            addAll(commandArguments(normalized, customArguments))
            addAll(listOf("--ip", "127.0.0.1", "--port", port.toString(), "--debug", "1"))
        }
        val engine = DesyncProxy()
        engine.prepare(args.toTypedArray())
        proxy = engine
        activePort = port
        worker = Thread({
            val result = runCatching { engine.runLoop() }.getOrElse {
                RKhVpnLogStore.append(context, "Desync", "PingNG loop failed", it)
                -1
            }
            RKhVpnLogStore.append(context, "Desync", "PingNG stopped with code=$result")
            synchronized(this) {
                if (proxy === engine) {
                    proxy = null
                    worker = null
                    activePort = 0
                }
            }
        }, "simorgh-desync").apply {
            isDaemon = true
            start()
        }
        RKhVpnLogStore.append(context, "Desync", "PingNG started on 127.0.0.1:$port profile=$normalized")
        return port
    }

    @Synchronized
    fun stop(context: Context) {
        val old = proxy
        proxy = null
        worker = null
        activePort = 0
        if (old != null) {
            runCatching { old.stop() }
                .onFailure { RKhVpnLogStore.append(context, "Desync", "PingNG stop failed", it) }
            RKhVpnLogStore.append(context, "Desync", "PingNG stopped")
        }
    }

    private fun commandArguments(profile: String, custom: String): List<String> = when (profile) {
        PROFILE_LIGHT -> listOf("--proto", "tls", "--split", "1+s", "--tlsrec", "1+s")
        PROFILE_BALANCED -> listOf("--proto", "tls", "--disorder", "1", "--tlsrec", "1+s")
        PROFILE_SEVERE -> listOf("--proto", "tls", "--split", "1+s", "--disorder", "3+s", "--fake", "-1", "--ttl", "8")
        PROFILE_ADAPTIVE -> listOf("--proto", "tls", "--disorder", "1", "--auto=torst,ssl_err", "--timeout", "3", "--tlsrec", "3+s")
        PROFILE_CUSTOM -> shellSplit(custom).also { check(it.isNotEmpty()) { "Custom Desync arguments are empty" } }
        else -> error("Unsupported Desync profile: $profile")
    }

    private fun shellSplit(input: String): List<String> {
        val result = mutableListOf<String>()
        val token = StringBuilder()
        var quote: Char? = null
        var escaped = false
        fun flush() { if (token.isNotEmpty()) { result += token.toString(); token.clear() } }
        input.forEach { c ->
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
        return result
    }
}
