package com.rkh.vpn.core

import android.util.Base64
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

object XrayConfigBuilder {
    fun configFromRaw(raw: String): String {
        val outbound = when {
            raw.startsWith("vmess://") -> vmessOutbound(raw)
            raw.startsWith("vless://") -> vlessOutbound(raw)
            raw.startsWith("trojan://") -> trojanOutbound(raw)
            raw.startsWith("ss://") -> shadowsocksOutbound(raw)
            else -> error("Unsupported config format")
        }

        return """
        {
          "log": { "loglevel": "debug" },
          "dns": {
            "servers": ["1.1.1.1", "8.8.8.8"],
            "queryStrategy": "UseIP"
          },
          "stats": {},
          "policy": {
            "levels": { "0": { "statsUserUplink": true, "statsUserDownlink": true } },
            "system": { "statsOutboundUplink": true, "statsOutboundDownlink": true }
          },
          "inbounds": [
            {
              "tag": "tun-in",
              "protocol": "tun",
              "settings": {
                "name": "rkh0",
                "MTU": 1500,
                "userLevel": 0
              },
              "sniffing": {
                "enabled": true,
                "destOverride": ["http", "tls", "quic"],
                "routeOnly": false
              }
            }
          ],
          "outbounds": [
            $outbound,
            { "tag": "direct", "protocol": "freedom" },
            { "tag": "block", "protocol": "blackhole" }
          ],
          "routing": {
            "domainStrategy": "IPIfNonMatch",
            "rules": [
              { "type": "field", "outboundTag": "proxy", "network": "tcp,udp" }
            ]
          }
        }
        """.trimIndent()
    }

    private fun vmessOutbound(raw: String): String {
        val body = raw.removePrefix("vmess://").substringBefore('#')
        val json = String(Base64.decode(body, Base64.DEFAULT))
        val o = JSONObject(json)
        val address = o.optString("add")
        val port = o.optInt("port", 443)
        val id = o.optString("id")
        val aid = o.optString("aid", "0").toIntOrNull() ?: 0
        val net = o.optString("net", "tcp").ifBlank { "tcp" }
        val tls = o.optString("tls")
        val host = o.optString("host")
        val path = o.optString("path")
        val sni = o.optString("sni", host)
        return """
        {
          "tag": "proxy",
          "protocol": "vmess",
          "settings": {
            "vnext": [{
              "address": "${j(address)}",
              "port": $port,
              "users": [{ "id": "${j(id)}", "alterId": $aid, "security": "auto" }]
            }]
          },
          ${streamSettings(net, tls, host, path, sni, emptyMap())}
        }
        """.trimIndent()
    }

    private fun vlessOutbound(raw: String): String {
        val uri = URI(raw.substringBefore('#'))
        val query = query(uri.rawQuery)
        val id = uri.userInfo.orEmpty()
        val net = query["type"] ?: "tcp"
        val security = query["security"].orEmpty()
        val sni = query["sni"] ?: query["host"].orEmpty()
        val host = query["host"].orEmpty()
        val path = query["path"].orEmpty()
        val flow = query["flow"].orEmpty()
        val user = if (flow.isNotBlank()) "{ \"id\": \"${j(id)}\", \"encryption\": \"none\", \"flow\": \"${j(flow)}\" }" else "{ \"id\": \"${j(id)}\", \"encryption\": \"none\" }"
        return """
        {
          "tag": "proxy",
          "protocol": "vless",
          "settings": {
            "vnext": [{
              "address": "${j(uri.host.orEmpty())}",
              "port": ${if (uri.port > 0) uri.port else 443},
              "users": [$user]
            }]
          },
          ${streamSettings(net, security, host, path, sni, query)}
        }
        """.trimIndent()
    }

    private fun trojanOutbound(raw: String): String {
        val uri = URI(raw.substringBefore('#'))
        val query = query(uri.rawQuery)
        val net = query["type"] ?: "tcp"
        val security = query["security"].ifBlankOrNull("tls")
        val sni = query["sni"] ?: query["host"].orEmpty()
        val host = query["host"].orEmpty()
        val path = query["path"].orEmpty()
        return """
        {
          "tag": "proxy",
          "protocol": "trojan",
          "settings": {
            "servers": [{
              "address": "${j(uri.host.orEmpty())}",
              "port": ${if (uri.port > 0) uri.port else 443},
              "password": "${j(uri.userInfo.orEmpty())}"
            }]
          },
          ${streamSettings(net, security, host, path, sni, query)}
        }
        """.trimIndent()
    }

    private fun shadowsocksOutbound(raw: String): String {
        val uriText = raw.substringBefore('#')
        val after = uriText.removePrefix("ss://")
        val decoded = runCatching { String(Base64.decode(after.substringBefore('@'), Base64.DEFAULT)) }.getOrElse { after.substringBefore('@') }
        val full = if ('@' in after) "$decoded@${after.substringAfter('@')}" else decoded
        val method = full.substringBefore(':')
        val password = full.substringAfter(':').substringBefore('@')
        val hostPort = full.substringAfter('@')
        val host = hostPort.substringBefore(':')
        val port = hostPort.substringAfter(':', "8388").toIntOrNull() ?: 8388
        return """
        {
          "tag": "proxy",
          "protocol": "shadowsocks",
          "settings": {
            "servers": [{
              "address": "${j(host)}",
              "port": $port,
              "method": "${j(method)}",
              "password": "${j(password)}"
            }]
          }
        }
        """.trimIndent()
    }

    private fun streamSettings(network: String, securityRaw: String, host: String, pathRaw: String, sni: String, params: Map<String, String>): String {
        val security = when (securityRaw.lowercase()) {
            "tls", "reality" -> securityRaw.lowercase()
            else -> "none"
        }
        val path = decode(pathRaw)
        val networkJson = when (network.lowercase()) {
            "ws" -> "\"wsSettings\": { \"path\": \"${j(path.ifBlank { "/" })}\", \"headers\": { \"Host\": \"${j(host)}\" } }"
            "grpc" -> "\"grpcSettings\": { \"serviceName\": \"${j(path)}\" }"
            else -> "\"tcpSettings\": {}"
        }
        val securityJson = when (security) {
            "tls" -> ", \"tlsSettings\": { \"serverName\": \"${j(sni)}\", \"allowInsecure\": false }"
            "reality" -> {
                val fp = params["fp"] ?: params["fingerprint"] ?: "chrome"
                val pbk = params["pbk"] ?: params["publicKey"].orEmpty()
                val sid = params["sid"] ?: params["shortId"].orEmpty()
                val spx = params["spx"] ?: params["spiderX"] ?: "/"
                ", \"realitySettings\": { \"serverName\": \"${j(sni)}\", \"fingerprint\": \"${j(fp)}\", \"publicKey\": \"${j(pbk)}\", \"shortId\": \"${j(sid)}\", \"spiderX\": \"${j(spx)}\" }"
            }
            else -> ""
        }
        return "\"streamSettings\": { \"network\": \"${j(network)}\", \"security\": \"$security\", $networkJson$securityJson }"
    }

    private fun query(raw: String?): Map<String, String> = raw.orEmpty()
        .split('&')
        .filter { it.contains('=') }
        .associate { it.substringBefore('=') to decode(it.substringAfter('=')) }

    private fun decode(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
    private fun j(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
    private fun String?.ifBlankOrNull(default: String): String = if (this.isNullOrBlank()) default else this
}
