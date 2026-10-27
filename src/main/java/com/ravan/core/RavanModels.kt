package com.ravan.core

/** Transports understood by the RavanCore orchestration layer. */
enum class RavanTransport {
    SIMORGH_MSP,
    WARP,
    WIREGUARD,
    MASQUE,
    TOR,
    NESTED_WARP,
    DIRECT
}

enum class RavanState { IDLE, STARTING, RUNNING, DEGRADED, STOPPING, STOPPED, FAILED }

data class RavanEndpoint(
    val host: String,
    val port: Int,
    val sni: String = "",
    val secure: Boolean = true
)

data class RavanHop(
    val transport: RavanTransport,
    val endpoint: RavanEndpoint,
    val name: String = transport.name,
    val mtu: Int = 1280,
    val options: Map<String, String> = emptyMap()
)

data class RavanRoute(
    val hops: List<RavanHop>,
    val label: String = "Ravan route"
)

data class RavanProfile(
    val id: String,
    val name: String,
    val primary: RavanRoute,
    val failover: List<RavanRoute> = emptyList(),
    val dnsServers: List<String> = listOf("1.1.1.1", "9.9.9.9"),
    val metadata: Map<String, String> = emptyMap()
)

data class RavanBackendHandle(
    val sessionId: String,
    val transport: RavanTransport,
    val token: String = sessionId
)

data class RavanProbe(
    val healthy: Boolean,
    val latencyMs: Long? = null,
    val detail: String = ""
)

data class RavanSnapshot(
    val sessionId: String? = null,
    val profileId: String? = null,
    val state: RavanState = RavanState.IDLE,
    val activeRoute: RavanRoute? = null,
    val startedAtMs: Long? = null,
    val failures: Int = 0,
    val lastError: String? = null,
    val lastProbe: RavanProbe? = null
)

sealed class RavanEvent {
    data class StateChanged(val from: RavanState, val to: RavanState) : RavanEvent()
    data class RouteSelected(val route: RavanRoute, val reason: String) : RavanEvent()
    data class HopStarted(val hop: RavanHop) : RavanEvent()
    data class HopStopped(val hop: RavanHop) : RavanEvent()
    data class HealthChanged(val probe: RavanProbe) : RavanEvent()
    data class Error(val message: String, val cause: Throwable? = null) : RavanEvent()
}

fun interface RavanEventListener {
    fun onEvent(event: RavanEvent)
}

/** A protocol implementation lives outside the orchestration layer. */
interface RavanBackend {
    fun supports(transport: RavanTransport): Boolean
    fun start(hop: RavanHop, sessionId: String): RavanBackendHandle
    fun stop(handle: RavanBackendHandle)
    fun probe(handle: RavanBackendHandle): RavanProbe = RavanProbe(true, detail = "backend did not provide a probe")
}

class RavanUnsupportedTransportException(transport: RavanTransport) :
    IllegalArgumentException("No Ravan backend registered for ${transport.name}")
