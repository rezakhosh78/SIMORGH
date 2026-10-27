package com.rkh.vpn.core

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

object SimorghDnsTunnelConfig {
    fun soar(zone: String, serverKey: String, resolvers: String, port: Int, cacheDir: String): JSONObject {
        val cleanZone = zone.trim().trimEnd('.')
        require(cleanZone.isNotBlank() && cleanZone.split('.').all { it.isNotBlank() && it.length <= 63 }) {
            "Enter a valid SoarDNS zone"
        }
        require(serverKey.trim().isNotBlank()) { "Enter the SoarDNS server public key" }
        val resolverList = resolvers.lineSequence().map(String::trim)
            .filter { it.isNotBlank() && !it.startsWith('#') }
            .map(::normalizeResolver).toList()
        require(resolverList.isNotEmpty()) { "Enter at least one DNS resolver for SoarDNS" }
        return JSONObject()
            .put("zone", cleanZone)
            .put("serverKey", serverKey.trim())
            .put("resolvers", JSONArray(resolverList))
            .put("discoveryEnabled", false)
            .put("discoveryCountry", "")
            .put("discoveryCandidates", JSONArray())
            .put("discoverySampleRanges", 0)
            .put("discoveryWant", 1)
            .put("discoveryConcurrency", 128)
            .put("discoveryPaceMs", 1)
            .put("discoveryCacheEnabled", true)
            .put("discoveryCachePath", "$cacheDir/resolvers.txt")
            .put("tcp", "auto")
            .put("listen", "127.0.0.1:$port")
            .put("safeQName", 101)
            .put("maxQName", 253)
            .put("holdMs", 500)
            .put("maxInflight", 128)
            .put("maxPathWindow", 32)
            .put("maxAnswer", 1232)
            .put("streamWindowKB", 256)
            .put("sendBufferKB", 64)
            .put("maxStreams", 64)
            .put("sessionIdleSeconds", 90)
    }

    fun dnstt(binary: String, transport: String, resolver: String, publicKey: String, domain: String, port: Int): List<String> {
        require(transport in setOf("udp", "doh", "dot")) { "Choose UDP, DoH or DoT for DNSTT" }
        val key = publicKey.trim().removePrefix("0x")
        require(key.matches(Regex("[0-9a-fA-F]{64}"))) { "DNSTT public key must be 64 hexadecimal characters" }
        val cleanDomain = domain.trim().trimEnd('.')
        require(cleanDomain.isNotBlank() && cleanDomain.none { it.isWhitespace() || it == '/' || it == '\\' }) {
            "Enter a valid DNSTT tunnel domain"
        }
        val rawResolver = resolver.trim()
        require(rawResolver.isNotBlank() && !rawResolver.startsWith('-') && !rawResolver.any(Char::isWhitespace)) {
            "Enter one DNS resolver for DNSTT"
        }
        if (transport == "doh") {
            val uri = runCatching { URI(rawResolver) }.getOrNull()
            require(uri?.scheme.equals("https", true) && uri?.host != null && uri.userInfo == null) {
                "DNSTT DoH resolver must be an HTTPS URL"
            }
        }
        val address = when {
            transport == "doh" -> rawResolver
            rawResolver.startsWith("[") && rawResolver.endsWith("]") -> "$rawResolver:${if (transport == "udp") 53 else 853}"
            rawResolver.count { it == ':' } > 1 && !rawResolver.startsWith("[") -> "[$rawResolver]:${if (transport == "udp") 53 else 853}"
            !rawResolver.contains(':') -> "$rawResolver:${if (transport == "udp") 53 else 853}"
            else -> rawResolver
        }
        return listOf(binary, "-$transport", address, "-pubkey", key, cleanDomain, "127.0.0.1:$port")
    }

    private fun normalizeResolver(value: String): String {
        val resolver = value.trim()
        require(!resolver.startsWith('-') && !resolver.any(Char::isWhitespace)) { "Invalid SoarDNS resolver: $resolver" }
        if (resolver.startsWith('[')) {
            val close = resolver.indexOf(']')
            require(close > 1) { "Invalid SoarDNS resolver: $resolver" }
            return when {
                resolver.length == close + 1 -> "$resolver:53"
                resolver.getOrNull(close + 1) == ':' -> resolver
                else -> error("Invalid SoarDNS resolver: $resolver")
            }
        }
        return when (resolver.count { it == ':' }) {
            0 -> "$resolver:53"
            1 -> resolver
            else -> "[$resolver]:53"
        }
    }
}

object SimorghWarpTunnelConfig {
    fun xray(account: SimorghWarpAccount, endpoint: String, socksPort: Int): String {
        val peer = JSONObject()
            .put("publicKey", account.peerPublicKey)
            .put("endpoint", endpoint)
            .put("keepAlive", 25)
        val outbound = JSONObject()
            .put("tag", "proxy")
            .put("protocol", "wireguard")
            .put("settings", JSONObject()
                .put("secretKey", account.privateKey)
                .put("address", JSONArray(account.localAddresses))
                .put("peers", JSONArray().put(peer))
                .put("mtu", account.mtu)
                .put("reserved", JSONArray(account.reserved))
                .put("remoteDNS", JSONArray(listOf("1.1.1.1", "1.0.0.1")))
            )
        val inbounds = JSONArray().put(JSONObject()
            .put("tag", "socks-in")
            .put("listen", "127.0.0.1")
            .put("port", socksPort)
            .put("protocol", "socks")
            .put("settings", JSONObject().put("auth", "noauth").put("udp", true)))
        return JSONObject()
            .put("log", JSONObject().put("loglevel", "warning"))
            .put("inbounds", inbounds)
            .put("outbounds", JSONArray().put(outbound).put(JSONObject().put("tag", "direct").put("protocol", "freedom")))
            .put("routing", JSONObject().put("domainStrategy", "IPIfNonMatch").put("rules", JSONArray()))
            .toString(2)
    }

    fun awgUapi(account: SimorghWarpAccount, endpoint: String, awg: Boolean): String = buildString {
        appendLine("private_key=${WarpAccountRegistration.keyToHex(account.privateKey)}")
        if (awg) {
            appendLine("jc=5")
            appendLine("jmin=10")
            appendLine("jmax=40")
            appendLine("s1=0")
            appendLine("s2=0")
            appendLine("h1=1")
            appendLine("h2=2")
            appendLine("h3=3")
            appendLine("h4=4")
        }
        appendLine("replace_peers=true")
        appendLine("public_key=${WarpAccountRegistration.keyToHex(account.peerPublicKey)}")
        appendLine("allowed_ip=0.0.0.0/0")
        appendLine("allowed_ip=::/0")
        appendLine("endpoint=$endpoint")
        appendLine("persistent_keepalive_interval=25")
    }
}
