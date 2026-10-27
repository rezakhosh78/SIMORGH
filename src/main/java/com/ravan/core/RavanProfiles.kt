package com.ravan.core

/** Safe profile constructors for SIMORGH integration and examples. */
object RavanProfiles {
    fun warpPlus(localPort: Int = 8086, nested: Boolean = false, transport: RavanTransport = if (nested) RavanTransport.NESTED_WARP else RavanTransport.WARP): RavanProfile = RavanProfile(
        id = "warp-plus-${transport.name.lowercase()}",
        name = "SIMORGH ${transport.name}",
        primary = RavanRoute(
            label = if (nested) "warp-plus --gool" else "warp-plus",
            hops = listOf(
                RavanHop(
                    transport = if (nested) RavanTransport.NESTED_WARP else transport,
                    endpoint = RavanEndpoint("engage.cloudflareclient.com", 443, sni = "engage.cloudflareclient.com"),
                    name = if (nested) "WARP-in-WARP" else transport.name,
                    options = mapOf("localSocksPort" to localPort.toString())
                )
            )
        ),
        metadata = mapOf("backend" to "warp-plus", "transport" to transport.name, "localSocksPort" to localPort.toString())
    )

    fun simorghMsp(host: String = "127.0.0.1", port: Int = 9990): RavanProfile = RavanProfile(
        id = "simorgh-msp-compat",
        name = "SIMORGH MSP compatibility",
        primary = RavanRoute(
            label = "SIMORGH MSP → Ravan lifecycle",
            hops = listOf(
                RavanHop(
                    transport = RavanTransport.SIMORGH_MSP,
                    endpoint = RavanEndpoint(host, port, secure = false),
                    name = "SIMORGH MSP"
                )
            )
        ),
        metadata = mapOf("owner" to "SIMORGH", "mode" to "compatibility")
    )

    fun nestedWarp(warpHost: String, wireGuardHost: String): RavanProfile = RavanProfile(
        id = "nested-warp-wireguard",
        name = "Ravan nested WARP",
        primary = RavanRoute(
            label = "WARP → WireGuard",
            hops = listOf(
                RavanHop(RavanTransport.WARP, RavanEndpoint(warpHost, 443, sni = warpHost)),
                RavanHop(RavanTransport.WIREGUARD, RavanEndpoint(wireGuardHost, 51820, secure = true))
            )
        ),
        failover = listOf(
            RavanRoute(
                label = "MASQUE → WireGuard",
                hops = listOf(
                    RavanHop(RavanTransport.MASQUE, RavanEndpoint(warpHost, 443, sni = warpHost)),
                    RavanHop(RavanTransport.WIREGUARD, RavanEndpoint(wireGuardHost, 51820, secure = true))
                )
            )
        ),
        metadata = mapOf("experimental" to "true")
    )
}
