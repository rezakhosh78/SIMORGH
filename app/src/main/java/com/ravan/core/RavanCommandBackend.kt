package com.ravan.core

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import java.io.File

/**
 * Generic process backend for independently licensed engines.
 *
 * It never constructs a shell command: arguments are passed as an argv list,
 * which avoids shell injection when a profile contains a hostname or key.
 */
class RavanCommandBackend(
    private val transports: Set<RavanTransport>,
    private val commandFactory: (RavanHop, String) -> List<String>,
    private val environment: Map<String, String> = emptyMap(),
    private val workingDirectory: File? = null,
    private val log: (String) -> Unit = {}
) : RavanBackend {
    private val processes = ConcurrentHashMap<String, Process>()

    override fun supports(transport: RavanTransport): Boolean = transport in transports

    override fun start(hop: RavanHop, sessionId: String): RavanBackendHandle {
        require(hop.transport in transports) { "Command backend does not support ${hop.transport}" }
        val argv = commandFactory(hop, sessionId).filter { it.isNotBlank() }
        require(argv.isNotEmpty()) { "Command backend produced an empty command" }
        val process = ProcessBuilder(argv).apply {
            workingDirectory?.let { directory(it) }
            environment().putAll(environment)
            redirectErrorStream(true)
        }.start()
        val token = "proc-" + UUID.randomUUID().toString().replace("-", "").take(16)
        processes[token] = process
        thread(name = "ravan-output-$token", isDaemon = true) {
            runCatching {
                process.inputStream.bufferedReader().useLines { lines -> lines.forEach { log("$token: $it") } }
            }
        }
        log("started ${hop.name} with ${argv.first()} (session=$sessionId)")
        return RavanBackendHandle(sessionId, hop.transport, token)
    }

    override fun stop(handle: RavanBackendHandle) {
        val process = processes.remove(handle.token) ?: return
        process.destroy()
        if (!process.waitFor(750, java.util.concurrent.TimeUnit.MILLISECONDS)) process.destroyForcibly()
        log("stopped ${handle.transport} (session=${handle.sessionId})")
    }

    override fun probe(handle: RavanBackendHandle): RavanProbe =
        RavanProbe(processes[handle.token]?.isAlive == true, detail = "process backend")
}
