package com.rkh.vpn.core

import org.json.JSONArray
import org.json.JSONObject

object XrayBinaryConfigBuilder {
    fun socksConfigFromRaw(raw: String, socksPort: Int = 10808, forceGoogleDns: Boolean = false): String {
        // Build from the normal parsed outbound config, but replace the Android-only TUN
        // inbound with local SOCKS/HTTP inbounds consumed by external tun2socks.
        // Full Xray JSON configs, such as ServerLess, are also supported: their
        // outbounds/routing/DNS are preserved and only the local inbound is normalized.
        val cleanRaw = raw.replace("﻿", "").trim()
        val fullJsonInput = cleanRaw.startsWith("{")
        val base = if (fullJsonInput) JSONObject(cleanRaw) else JSONObject(XrayConfigBuilder.configFromRaw(raw))
        val serverLessInput = fullJsonInput && looksLikeServerLess(base)

        if (serverLessInput) {
            // z26 ServerLess: follow the upstream patterniha/v2rayNG profile instead
            // of trying to reinterpret it. The project README explicitly requires
            // v2rayNG's HEV TUN feature and port 10808; that architecture is:
            // Android VpnService TUN -> tun2socks/HEV -> Xray mixed inbound :10808.
            // Therefore keep FakeDNS, Cloudflare DoH, routing, finalmask and outbounds
            // as-authored. Only remove a stray native-tun inbound (if our bundled asset
            // has one) and expose the mixed inbound on 127.0.0.1:socksPort.
            base.put("inbounds", serverLessMixedInbounds(base, socksPort))
            normalizeServerLessHevTun2SocksDns(base)
        } else {
            val socksInbound = JSONObject().apply {
                put("tag", "socks-in")
                put("listen", "127.0.0.1")
                put("port", socksPort)
                put("protocol", "socks")
                put("settings", JSONObject().apply {
                    put("udp", true)
                    put("auth", "noauth")
                })
                put("sniffing", JSONObject().apply {
                    put("enabled", true)
                    put("destOverride", JSONArray(listOf("fakedns", "http", "tls", "quic")))
                    put("routeOnly", false)
                })
            }

            val httpInbound = JSONObject().apply {
                put("tag", "http-in")
                put("listen", "127.0.0.1")
                put("port", socksPort + 1)
                put("protocol", "http")
                put("settings", JSONObject())
            }

            base.put("inbounds", JSONArray().put(socksInbound).put(httpInbound))

            // For binary-core mode, tun2socks already forwards all device traffic into the
            // local SOCKS inbound. We make the first outbound (proxy) the default path and
            // remove the forced routing block that sometimes produced:
            // "default outbound handler not exist" when the earlier config was malformed.
            if (!fullJsonInput) {
                base.put("routing", JSONObject().apply {
                    put("domainStrategy", "IPIfNonMatch")
                    put("rules", JSONArray())
                })
            }
        }

        if (forceGoogleDns && !serverLessInput) {
            forceGoogleDnsForSimpleNormal(base)
        }

        return base.toString(2)
    }


    fun hiddifyLikeBalancedSocksConfigFromRawList(raws: List<String>, socksPort: Int = 10808, forceGoogleDns: Boolean = true): String {
        // Hiddify-like Simple normal mode: one Xray process, one local mixed/SOCKS inbound,
        // many proxy outbounds, and Xray's own routing balancer + observatory chooses the path.
        // This removes the old app-side loop of starting Xray repeatedly for each config.
        val proxyOutbounds = JSONArray()
        raws.forEachIndexed { index, raw ->
            val clean = raw.replace("﻿", "").trim()
            if (clean.isBlank() || clean.startsWith("{")) return@forEachIndexed
            val parsed = runCatching { JSONObject(XrayConfigBuilder.configFromRaw(clean)) }.getOrNull() ?: return@forEachIndexed
            val outs = parsed.optJSONArray("outbounds") ?: return@forEachIndexed
            var proxy: JSONObject? = null
            for (i in 0 until outs.length()) {
                val item = outs.optJSONObject(i) ?: continue
                if (item.optString("tag", "").equals("proxy", ignoreCase = true)) {
                    proxy = JSONObject(item.toString())
                    break
                }
            }
            if (proxy == null) proxy = outs.optJSONObject(0)?.let { JSONObject(it.toString()) }
            val outbound = proxy ?: return@forEachIndexed
            outbound.put("tag", "proxy-$index")
            proxyOutbounds.put(outbound)
        }
        if (proxyOutbounds.length() == 0) error("No valid Simple configs for balancer")

        val socksInbound = JSONObject().apply {
            put("tag", "socks-in")
            put("listen", "127.0.0.1")
            put("port", socksPort)
            put("protocol", "socks")
            put("settings", JSONObject().apply {
                put("udp", true)
                put("auth", "noauth")
            })
            put("sniffing", JSONObject().apply {
                put("enabled", true)
                put("destOverride", JSONArray(listOf("fakedns", "http", "tls", "quic")))
                put("routeOnly", false)
            })
        }

        val httpInbound = JSONObject().apply {
            put("tag", "http-in")
            put("listen", "127.0.0.1")
            put("port", socksPort + 1)
            put("protocol", "http")
            put("settings", JSONObject())
        }

        val outbounds = JSONArray()
        for (i in 0 until proxyOutbounds.length()) outbounds.put(proxyOutbounds.getJSONObject(i))
        outbounds.put(JSONObject().apply { put("tag", "direct"); put("protocol", "freedom") })
        outbounds.put(JSONObject().apply { put("tag", "block"); put("protocol", "blackhole") })

        return JSONObject().apply {
            put("log", JSONObject().apply { put("loglevel", "info") })
            put("dns", JSONObject().apply {
                put("servers", JSONArray(listOf("8.8.8.8", "8.8.4.4", "1.1.1.1")))
                put("queryStrategy", "UseIPv4")
                put("useSystemHosts", false)
                put("disableCache", false)
            })
            put("stats", JSONObject())
            put("policy", JSONObject().apply {
                put("levels", JSONObject().apply {
                    put("0", JSONObject().apply {
                        put("statsUserUplink", true)
                        put("statsUserDownlink", true)
                    })
                })
                put("system", JSONObject().apply {
                    put("statsOutboundUplink", true)
                    put("statsOutboundDownlink", true)
                })
            })
            put("observatory", JSONObject().apply {
                put("subjectSelector", JSONArray(listOf("proxy-")))
                put("probeUrl", "https://www.gstatic.com/generate_204")
                put("probeInterval", "10s")
            })
            put("inbounds", JSONArray().put(socksInbound).put(httpInbound))
            put("outbounds", outbounds)
            put("routing", JSONObject().apply {
                put("domainStrategy", "IPIfNonMatch")
                put("rules", JSONArray()
                    .put(JSONObject().apply {
                        put("type", "field")
                        put("outboundTag", "direct")
                        put("network", "udp")
                        put("port", 53)
                    })
                    .put(JSONObject().apply {
                        put("type", "field")
                        put("outboundTag", "block")
                        put("network", "udp")
                        put("port", "443")
                    })
                    .put(JSONObject().apply {
                        put("type", "field")
                        put("balancerTag", "simple-hiddify-balancer")
                        put("network", "tcp,udp")
                    })
                )
                put("balancers", JSONArray().put(JSONObject().apply {
                    put("tag", "simple-hiddify-balancer")
                    put("selector", JSONArray(listOf("proxy-")))
                    put("fallbackTag", "proxy-0")
                    put("strategy", JSONObject().apply { put("type", "leastPing") })
                }))
            })
        }.toString(2)
    }

    fun serverLessTunConfigFromRaw(raw: String): String {
        // ServerLess works in v2rayNG with the bundled full Xray JSON. For SIMORGH
        // ServerLess mode keep that JSON as close as possible to v2rayNG behavior and
        // only normalize the Xray TUN inbound so the Android VpnService fd can be used.
        // Do not rewrite DNS, routing, outbounds, FakeDNS, or fragmentation rules here.
        val cleanRaw = raw.replace("﻿", "").trim()
        val base = JSONObject(cleanRaw)
        val log = base.optJSONObject("log") ?: JSONObject().also { base.put("log", it) }
        log.put("loglevel", "info")
        applyV2rayNgCompatibleServerLessTunPatch(base)
        applyServerLessMinimalDoHToLocalhostPatch(base)
        return base.toString(2)
    }


    private fun forceGoogleDnsForSimpleNormal(base: JSONObject) {
        // Simple mode without ServerLess should use Google DNS only, as requested.
        // This applies at runtime only when RkhVpnService calls socksConfigFromRaw(..., forceGoogleDns=true).
        base.put("dns", JSONObject().apply {
            put("servers", JSONArray(listOf("8.8.8.8", "8.8.4.4")))
            put("queryStrategy", "UseIPv4")
            put("useSystemHosts", false)
            put("disableCache", false)
        })
    }


    private fun normalizeServerLessHevTun2SocksDns(base: JSONObject) {
        // Keep the original DNS strategy from patterniha's Serverless profile.
        // Do not replace Cloudflare DoH with localhost: local/ISP DNS can return
        // filtering sinkholes such as 10.10.34.x for blocked domains. Also keep the
        // fakedns server because routing depends on mixed destOverride=fakedns.
        val dns = base.optJSONObject("dns") ?: return
        val servers = dns.optJSONArray("servers") ?: return
        var hasFakeDns = false
        var hasCloudflareDoh = false
        for (i in 0 until servers.length()) {
            val server = servers.optJSONObject(i) ?: continue
            val address = server.optString("address", "")
            if (address.equals("fakedns", ignoreCase = true)) hasFakeDns = true
            if (address.contains("cloudflare-dns.com", ignoreCase = true)) {
                hasCloudflareDoh = true
                if (!server.has("tag")) server.put("tag", "no-filter-dns")
                if (!server.has("timeoutMs")) server.put("timeoutMs", 12000)
                server.put("finalQuery", true)
            }
        }
        if (!hasFakeDns) {
            servers.put(0, JSONObject().apply { put("address", "fakedns") })
        }
        if (!hasCloudflareDoh) {
            servers.put(JSONObject().apply {
                put("tag", "no-filter-dns")
                put("address", "https://cloudflare-dns.com/dns-query")
                put("timeoutMs", 12000)
                put("finalQuery", true)
            })
        }
        dns.put("queryStrategy", dns.optString("queryStrategy", "UseSystem").ifBlank { "UseSystem" })
        dns.put("useSystemHosts", true)
        if (!dns.has("serveStale")) dns.put("serveStale", true)
    }

    private fun applyServerLessMinimalDoHToLocalhostPatch(base: JSONObject) {
        // Do not rewrite ServerLess routing/outbounds/FakeDNS/fragmentation anymore.
        // The last real-device logs showed Xray can open TCP connections, but the bundled
        // DoH resolver (cloudflare-dns.com/dns-query) repeatedly timed out and then routing
        // fell through to the final block rule for normal sites. Keep the profile v2rayNG-like,
        // only replace the remote DoH DNS server with Android/system localhost. This is used
        // only by Simple > ServerLess.
        val dns = base.optJSONObject("dns") ?: return
        val servers = dns.optJSONArray("servers") ?: return
        var replaced = 0
        for (i in 0 until servers.length()) {
            val server = servers.optJSONObject(i) ?: continue
            val address = server.optString("address", "")
            if (address.startsWith("https://", ignoreCase = true) || address.contains("cloudflare-dns.com", ignoreCase = true)) {
                server.put("address", "localhost")
                server.put("finalQuery", true)
                // Keep tag/domains/timeoutMs fields if present so the rest of the profile
                // shape remains stable, but remove DoH-only behavior.
                if (server.has("timeoutMs")) server.remove("timeoutMs")
                replaced++
            }
        }
        if (replaced > 0) {
            dns.put("useSystemHosts", true)
            if (!dns.has("queryStrategy")) dns.put("queryStrategy", "UseSystem")
        }
    }

    private fun applyV2rayNgCompatibleServerLessTunPatch(base: JSONObject) {
        // Keep original ServerLess DNS/routing/outbounds exactly like the asset, because
        // the same profile works in v2rayNG. Only make sure the tun inbound exists and is
        // suitable for Android's provided TUN fd. This function is used only in Simple > ServerLess.
        val inbounds = base.optJSONArray("inbounds") ?: JSONArray().also { base.put("inbounds", it) }
        var tun: JSONObject? = null
        for (i in 0 until inbounds.length()) {
            val inbound = inbounds.optJSONObject(i) ?: continue
            if (inbound.optString("protocol", "").equals("tun", ignoreCase = true)) {
                tun = inbound
                break
            }
        }
        if (tun == null) {
            tun = JSONObject().apply {
                put("tag", "tun")
                put("protocol", "tun")
                put("settings", JSONObject())
                put("sniffing", JSONObject())
            }
            inbounds.put(tun)
        }
        tun.put("tag", tun.optString("tag", "tun"))
        tun.put("protocol", "tun")
        val settings = tun.optJSONObject("settings") ?: JSONObject().also { tun.put("settings", it) }
        // Match the bundled config/v2rayNG profile. The Android VpnService Builder must use
        // the same MTU, otherwise packets can be accepted by Xray but pages may still stall.
        if (!settings.has("mtu")) settings.put("mtu", 1500)
        if (!settings.has("name")) settings.put("name", "xray0")
        if (!settings.has("userLevel")) settings.put("userLevel", 8)
        val sniffing = tun.optJSONObject("sniffing") ?: JSONObject().also { tun.put("sniffing", it) }
        sniffing.put("enabled", true)
        // Preserve existing values, but ensure FakeDNS is understood by Xray TUN when the
        // bundled DNS returns synthetic addresses.
        val old = sniffing.optJSONArray("destOverride")
        val items = linkedSetOf<String>()
        if (old != null) for (i in 0 until old.length()) old.optString(i, "").takeIf { it.isNotBlank() }?.let(items::add)
        listOf("fakedns", "http", "tls", "quic").forEach(items::add)
        sniffing.put("destOverride", JSONArray(items.toList()))
        if (!sniffing.has("routeOnly")) sniffing.put("routeOnly", false)
    }

    private fun applyServerLessTun2SocksRealDnsPatch(base: JSONObject) {
        // Only for Simple > ServerLess when running through tun2socks. FakeDNS works in
        // native Xray TUN/v2rayNG, but with external tun2socks the Android app receives
        // synthetic 198.18/198.19 destinations before Xray's mixed inbound can reliably
        // remap them. Answer DNS with Android/system localhost real A/AAAA records instead.
        val dns = base.optJSONObject("dns") ?: JSONObject().also { base.put("dns", it) }
        dns.remove("hosts")
        dns.put("servers", JSONArray().put(JSONObject().apply {
            put("address", "localhost")
            put("finalQuery", true)
        }))
        dns.put("queryStrategy", "UseIPv4")
        dns.put("useSystemHosts", true)
        dns.put("disableCache", false)
        if (dns.has("serveStale")) dns.remove("serveStale")
    }

    private fun looksLikeServerLess(base: JSONObject): Boolean {
        val remarks = base.optString("remarks", "")
        val dns = base.optJSONObject("dns")
        val servers = dns?.optJSONArray("servers")
        if (remarks.contains("serverless", ignoreCase = true)) return true
        if (servers != null) {
            for (i in 0 until servers.length()) {
                val item = servers.opt(i)
                if (item != null && item.toString().contains("cloudflare-dns.com", ignoreCase = true)) return true
            }
        }
        return false
    }

    private fun serverLessMixedInbounds(base: JSONObject, socksPort: Int): JSONArray {
        val oldInbounds = base.optJSONArray("inbounds")
        var mixed: JSONObject? = null
        if (oldInbounds != null) {
            for (i in 0 until oldInbounds.length()) {
                val inbound = oldInbounds.optJSONObject(i) ?: continue
                val protocol = inbound.optString("protocol", "")
                if (protocol.equals("mixed", ignoreCase = true) || protocol.equals("socks", ignoreCase = true)) {
                    mixed = JSONObject(inbound.toString())
                    break
                }
            }
        }

        val inbound = mixed ?: JSONObject().apply {
            put("protocol", "mixed")
            put("sniffing", JSONObject().apply {
                put("enabled", true)
                put("destOverride", JSONArray(listOf("tls", "http", "quic")))
                put("routeOnly", false)
            })
            put("settings", JSONObject())
        }

        inbound.put("tag", inbound.optString("tag", "mixed-in"))
        inbound.put("listen", "127.0.0.1")
        inbound.put("port", socksPort)
        inbound.put("protocol", "mixed")
        val settings = inbound.optJSONObject("settings") ?: JSONObject().also { inbound.put("settings", it) }
        settings.put("udp", true)
        settings.put("ip", "127.0.0.1")
        settings.put("auth", "noauth")
        val sniffing = inbound.optJSONObject("sniffing") ?: JSONObject().also { inbound.put("sniffing", it) }
        sniffing.put("enabled", true)
        // Upstream Serverless-for-Iran relies on FakeDNS + mixed inbound
        // destOverride=fakedns. v2rayNG works this way with HEV TUN. Keep that behavior:
        // DNS requests may return 198.18/198.19 fake IPs, and Xray's mixed inbound must
        // map those fake destinations back to the original domain before routing.
        val oldDestOverride = sniffing.optJSONArray("destOverride")
        val destOverrides = linkedSetOf<String>()
        if (oldDestOverride != null) {
            for (i in 0 until oldDestOverride.length()) {
                val item = oldDestOverride.optString(i, "")
                if (item.isNotBlank()) destOverrides.add(item)
            }
        }
        listOf("fakedns", "tls", "http", "quic").forEach(destOverrides::add)
        sniffing.put("destOverride", JSONArray(destOverrides.toList()))
        sniffing.put("routeOnly", false)

        return JSONArray().put(inbound)
    }

    private fun applyAndroidServerLessPatch(base: JSONObject) {
        // ServerLess is the only mode that needs this runtime conversion. The bundled
        // JSON was written as a standalone Xray TUN profile, but SIMORGH binary-core
        // mode is: Android VpnService TUN -> tun2socks -> Xray local mixed/SOCKS inbound.
        // In that model FakeDNS IPs (198.18/198.19) do not travel as reliably as they do
        // in the original Xray TUN profile, so ServerLess uses real DNS answers here and
        // routes all TCP through the fragmentation outbound. This patch is only applied
        // when the bundled ServerLess JSON is selected.
        patchServerLessDnsForAndroid(base)
        forceServerLessIpv4Outbounds(base)
        rewriteServerLessRoutingForTun2Socks(base)
    }

    private fun patchServerLessDnsForAndroid(base: JSONObject) {
        // ServerLess native-TUN should answer Android DNS requests immediately with
        // FakeDNS, then let the TUN inbound convert that FakeIP back to the original
        // domain through destOverride=fakedns. Public UDP DNS (1.1.1.1/8.8.8.8/9.9.9.9)
        // produced repeated "record not found" in user logs, so the only real resolver
        // fallback is Android/system localhost.
        val dnsServers = JSONArray()
        dnsServers.put(JSONObject().apply {
            put("address", "fakedns")
            put("domains", JSONArray(listOf("regexp:.*")))
        })
        dnsServers.put(JSONObject().apply {
            put("address", "localhost")
            put("finalQuery", true)
        })

        base.put("dns", JSONObject().apply {
            put("servers", dnsServers)
            put("queryStrategy", "UseIPv4")
            put("useSystemHosts", true)
            put("disableCache", false)
        })
    }

    private fun findExistingFakeDnsDomains(servers: JSONArray?): JSONArray {
        val domains = JSONArray()
        if (servers == null) return domains
        for (i in 0 until servers.length()) {
            val server = servers.optJSONObject(i) ?: continue
            if (!server.optString("address", "").equals("fakedns", ignoreCase = true)) continue
            val oldDomains = server.optJSONArray("domains") ?: continue
            for (j in 0 until oldDomains.length()) {
                val item = oldDomains.optString(j, "")
                if (item.isNotBlank() && item != "regexp:.*") domains.put(item)
            }
        }
        return domains
    }

    private fun rewriteServerLessRoutingForTun2Socks(base: JSONObject) {
        val rules = JSONArray()

        // DNS requests from Android enter Xray as UDP/53 through tun2socks. Let Xray DNS
        // answer them with real IPv4 records. Do not return FakeDNS addresses in this mode.
        rules.put(JSONObject().apply {
            put("outboundTag", "dns-out")
            put("port", 53)
        })

        // QUIC/UDP 443 often makes browsers look connected but never load pages with this
        // ServerLess profile behind tun2socks. Blocking it forces Chrome/WebView to fall
        // back to TCP, where the ServerLess fragmentation rules actually work.
        rules.put(JSONObject().apply {
            put("outboundTag", "block")
            put("network", "udp")
            put("port", "443")
        })

        // Keep non-QUIC UDP possible, but force IPv4 on the outbound itself.
        rules.put(JSONObject().apply {
            put("outboundTag", "udp-direct")
            put("network", "udp")
            put("ip", JSONArray(listOf("0.0.0.0/0")))
        })

        // Route every TCP connection through the ServerLess TCP fragment outbound. This is
        // intentionally IP-based, not domain/FakeDNS-based, because tun2socks gives Xray IP
        // destinations for device traffic.
        rules.put(JSONObject().apply {
            put("outboundTag", "tcp-fragment")
            put("network", "tcp")
            put("ip", JSONArray(listOf("0.0.0.0/0")))
        })

        base.put("routing", JSONObject().apply {
            put("domainStrategy", "IPIfNonMatch")
            put("rules", rules)
        })
    }

    private fun forceServerLessIpv4Outbounds(base: JSONObject) {
        val outbounds = base.optJSONArray("outbounds") ?: return
        for (i in 0 until outbounds.length()) {
            val outbound = outbounds.optJSONObject(i) ?: continue
            val tag = outbound.optString("tag", "")
            if (!tag.contains("direct", ignoreCase = true) && !tag.contains("fragment", ignoreCase = true) && !tag.contains("noise", ignoreCase = true)) continue

            outbound.optJSONObject("settings")?.let { settings ->
                if (settings.has("targetStrategy")) settings.put("targetStrategy", "ForceIPv4")
            }

            val streamSettings = outbound.optJSONObject("streamSettings") ?: continue
            val sockopt = streamSettings.optJSONObject("sockopt") ?: continue
            if (sockopt.has("domainStrategy")) sockopt.put("domainStrategy", "ForceIPv4")
            sockopt.optJSONObject("happyEyeballs")?.let { happyEyeballs ->
                happyEyeballs.put("prioritizeIPv6", false)
                if (happyEyeballs.optInt("maxConcurrentTry", 0) > 8) happyEyeballs.put("maxConcurrentTry", 8)
            }
        }
    }

    fun nipoBridgeConfig(localSocksPort: Int = 10808, nipoSocksPort: Int = 9992): String {
        val socksInbound = JSONObject().apply {
            put("tag", "socks-in")
            put("listen", "127.0.0.1")
            put("port", localSocksPort)
            put("protocol", "socks")
            put("settings", JSONObject().apply {
                put("auth", "noauth")
                put("udp", true)
            })
            put("sniffing", JSONObject().apply {
                put("enabled", true)
                put("destOverride", JSONArray(listOf("http", "tls", "quic")))
                put("routeOnly", false)
            })
        }
        val httpInbound = JSONObject().apply {
            put("tag", "http-in")
            put("listen", "127.0.0.1")
            put("port", localSocksPort + 1)
            put("protocol", "http")
            put("settings", JSONObject())
        }
        val nipoOutbound = JSONObject().apply {
            put("tag", "nipo-socks5")
            put("protocol", "socks")
            put("settings", JSONObject().apply {
                put("servers", JSONArray().put(JSONObject().apply {
                    put("address", "127.0.0.1")
                    put("port", nipoSocksPort)
                }))
            })
        }
        return JSONObject().apply {
            put("log", JSONObject().apply { put("loglevel", "debug") })
            put("dns", JSONObject().apply {
                put("servers", JSONArray(listOf("8.8.8.8", "8.8.4.4", "1.1.1.1")))
                put("queryStrategy", "UseIPv4")
            })
            put("inbounds", JSONArray().put(socksInbound).put(httpInbound))
            put("outbounds", JSONArray().put(nipoOutbound).put(JSONObject().apply { put("tag", "direct"); put("protocol", "freedom") }).put(JSONObject().apply { put("tag", "block"); put("protocol", "blackhole") }))
            put("routing", JSONObject().apply {
                put("domainStrategy", "IPIfNonMatch")
                put("rules", JSONArray()
                    .put(JSONObject().apply {
                        put("type", "field")
                        put("outboundTag", "direct")
                        put("network", "udp")
                        put("port", 53)
                    })
                    .put(JSONObject().apply {
                        put("type", "field")
                        put("outboundTag", "block")
                        put("network", "udp")
                        put("port", "443")
                    })
                    .put(JSONObject().apply {
                        put("type", "field")
                        put("outboundTag", "nipo-socks5")
                        put("network", "tcp,udp")
                    })
                )
            })
        }.toString(2)
    }

}
