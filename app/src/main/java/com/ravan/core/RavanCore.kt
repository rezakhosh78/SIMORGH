package com.ravan.core

import java.util.UUID
import kotlin.math.max

/**
 * Small, dependency-free control plane for multi-hop tunnel engines.
 *
 * RavanCore deliberately does not embed a copied protocol implementation. A
 * backend owns crypto, packet I/O and native processes; this class owns safe
 * lifecycle ordering, validation, route selection, failover and observability.
 */
class RavanCore(
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val listener: RavanEventListener? = null
) {
    private val backends = LinkedHashMap<RavanTransport, RavanBackend>()
    private var snapshot = RavanSnapshot()
    private var activeHandles = emptyList<Pair<RavanHop, RavanBackendHandle>>()

    @Synchronized
    fun register(transport: RavanTransport, backend: RavanBackend) {
        require(backend.supports(transport)) { "Backend does not support ${transport.name}" }
        backends[transport] = backend
    }

    @Synchronized
    fun snapshot(): RavanSnapshot = snapshot

    @Synchronized
    fun start(profile: RavanProfile): RavanSnapshot {
        require(snapshot.state == RavanState.IDLE || snapshot.state == RavanState.STOPPED || snapshot.state == RavanState.FAILED) {
            "RavanCore is already active: ${snapshot.state}"
        }
        validate(profile)
        val sessionId = "ravan-" + UUID.randomUUID().toString().replace("-", "").take(16)
        transition(RavanState.STARTING)
        snapshot = snapshot.copy(
            sessionId = sessionId,
            profileId = profile.id,
            activeRoute = profile.primary,
            startedAtMs = clockMs(),
            failures = 0,
            lastError = null,
            lastProbe = null
        )
        emit(RavanEvent.RouteSelected(profile.primary, "primary route"))
        return runRoute(profile.primary, sessionId)
    }

    @Synchronized
    fun reportProbe(probe: RavanProbe): RavanSnapshot {
        snapshot = snapshot.copy(lastProbe = probe)
        emit(RavanEvent.HealthChanged(probe))
        if (snapshot.state == RavanState.RUNNING || snapshot.state == RavanState.DEGRADED) {
            transition(if (probe.healthy) RavanState.RUNNING else RavanState.DEGRADED)
        }
        return snapshot
    }

    @Synchronized
    fun stop(): RavanSnapshot {
        if (snapshot.state == RavanState.IDLE || snapshot.state == RavanState.STOPPED) return snapshot
        transition(RavanState.STOPPING)
        activeHandles.asReversed().forEach { (hop, handle) ->
            runCatching { backends.getValue(hop.transport).stop(handle) }
                .onFailure { emit(RavanEvent.Error("Failed to stop ${hop.name}", it)) }
            emit(RavanEvent.HopStopped(hop))
        }
        activeHandles = emptyList()
        transition(RavanState.STOPPED)
        snapshot = snapshot.copy(activeRoute = null, lastProbe = null)
        return snapshot
    }

    /** Switches to the first route that can be started after the current route fails. */
    @Synchronized
    fun failover(profile: RavanProfile): RavanSnapshot {
        require(snapshot.state == RavanState.DEGRADED || snapshot.state == RavanState.FAILED) {
            "Failover requires DEGRADED or FAILED state"
        }
        val sessionId = snapshot.sessionId ?: "ravan-" + UUID.randomUUID().toString().take(16)
        val routes = profile.failover
        var last: Throwable? = null
        routes.forEachIndexed { index, route ->
            runCatching {
                stop()
                validateRoute(route)
                transition(RavanState.STARTING)
                snapshot = snapshot.copy(sessionId = sessionId, profileId = profile.id, activeRoute = route)
                emit(RavanEvent.RouteSelected(route, "failover #${index + 1}"))
                return runRoute(route, sessionId)
            }.onFailure { last = it }
        }
        transition(RavanState.FAILED)
        snapshot = snapshot.copy(failures = snapshot.failures + 1, lastError = last?.message ?: "all failover routes failed")
        emit(RavanEvent.Error(snapshot.lastError ?: "all failover routes failed", last))
        return snapshot
    }

    private fun runRoute(route: RavanRoute, sessionId: String): RavanSnapshot {
        val started = ArrayList<Pair<RavanHop, RavanBackendHandle>>(route.hops.size)
        try {
            route.hops.forEach { hop ->
                val backend = backends[hop.transport] ?: throw RavanUnsupportedTransportException(hop.transport)
                val handle = backend.start(hop, sessionId)
                started += hop to handle
                emit(RavanEvent.HopStarted(hop))
            }
            activeHandles = started
            transition(RavanState.RUNNING)
            return snapshot
        } catch (error: Throwable) {
            started.asReversed().forEach { (hop, handle) -> runCatching { backends.getValue(hop.transport).stop(handle) } }
            activeHandles = emptyList()
            transition(RavanState.FAILED)
            snapshot = snapshot.copy(failures = snapshot.failures + 1, lastError = error.message ?: error.javaClass.simpleName)
            emit(RavanEvent.Error(snapshot.lastError ?: "route start failed", error))
            throw error
        }
    }

    private fun validate(profile: RavanProfile) {
        require(profile.id.isNotBlank()) { "profile id is empty" }
        require(profile.name.isNotBlank()) { "profile name is empty" }
        validateRoute(profile.primary)
        profile.failover.forEach(::validateRoute)
    }

    private fun validateRoute(route: RavanRoute) {
        require(route.hops.isNotEmpty()) { "route must contain at least one hop" }
        require(route.hops.size <= 4) { "Ravan routes are limited to four hops" }
        route.hops.forEach { hop ->
            require(hop.endpoint.host.isNotBlank()) { "${hop.name}: endpoint host is empty" }
            require(hop.endpoint.port in 1..65535) { "${hop.name}: endpoint port is invalid" }
            require(hop.mtu in 576..1500) { "${hop.name}: MTU must be between 576 and 1500" }
            if (hop.transport == RavanTransport.MASQUE || hop.transport == RavanTransport.WARP) {
                require(hop.endpoint.secure) { "${hop.name}: secure endpoint is required" }
            }
        }
        val nestedCount = route.hops.count { it.transport == RavanTransport.NESTED_WARP }
        require(nestedCount <= 1) { "only one nested WARP coordinator is allowed per route" }
    }

    private fun transition(next: RavanState) {
        val previous = snapshot.state
        if (previous != next) {
            snapshot = snapshot.copy(state = next)
            emit(RavanEvent.StateChanged(previous, next))
        }
    }

    private fun emit(event: RavanEvent) = runCatching { listener?.onEvent(event) }
}

/** Score-based route choice used by UI and future probing jobs. */
class RavanRoutePlanner {
    data class Candidate(val route: RavanRoute, val probe: RavanProbe, val reliability: Int = 50)

    fun choose(candidates: List<Candidate>): RavanRoute? = candidates
        .filter { it.probe.healthy }
        .maxWithOrNull(compareBy<Candidate> {
            it.reliability.coerceIn(0, 100)
        }.thenBy {
            -max(0L, it.probe.latencyMs ?: Long.MAX_VALUE)
        }.thenByDescending { it.route.hops.size })
        ?.route
}
