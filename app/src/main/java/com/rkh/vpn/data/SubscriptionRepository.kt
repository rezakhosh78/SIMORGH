package com.rkh.vpn.data

import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

class SubscriptionRepository {
    companion object {
        const val PRIMARY_BASE = "https://sub.iranclude.ir:8080/sub/"
        const val PREMIUM_BASE = "https://sub6.iranclude.ir:2096/sub/"
    }

    data class Result(val usage: UsageInfo, val servers: List<ServerConfig>)

    fun fetch(base: String, token: String): Result {
        val cleanToken = token.trim()
        if (cleanToken.isBlank()) throw IllegalArgumentException("Code after /sub/ is empty")

        val encodedToken = URLEncoder.encode(cleanToken, "UTF-8").replace("+", "%20")
        val url = URL(base + encodedToken)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 12000
            readTimeout = 20000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "RKhVPN/0.2.8")
            setRequestProperty("Accept", "text/plain, */*")
        }

        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty().trim()
            val header = conn.getHeaderField("subscription-userinfo").orEmpty()

            if (code !in 200..299) {
                val detail = body.take(180).ifBlank { conn.responseMessage ?: "No response body" }
                throw IllegalStateException("HTTP $code: $detail")
            }
            if (body.isBlank()) {
                throw IllegalStateException("Subscription response is empty")
            }

            val servers = parseConfigs(body)
            if (servers.isEmpty()) {
                throw IllegalStateException("No supported configs found in subscription")
            }

            Result(parseUsage(header), servers)
        } finally {
            conn.disconnect()
        }
    }

    fun parseUsage(h: String): UsageInfo {
        var up = 0L
        var down = 0L
        var total = 0L
        h.split(';').map { it.trim() }.forEach { p ->
            val kv = p.split('=', limit = 2)
            if (kv.size == 2) {
                when (kv[0].lowercase()) {
                    "upload" -> up = kv[1].toLongOrNull() ?: 0L
                    "download" -> down = kv[1].toLongOrNull() ?: 0L
                    "total" -> total = kv[1].toLongOrNull() ?: 0L
                }
            }
        }
        return UsageInfo(up + down, total)
    }

    fun parseConfigs(text: String): List<ServerConfig> {
        val decoded = decodeSubscription(text)
        val clean = normalizeConfigText(decoded)
        parseFullXrayJson(clean)?.let { return listOf(it) }
        return clean
            .lineSequence()
            .map { it.trim() }
            .filter { raw ->
                raw.startsWith("vmess://") ||
                    raw.startsWith("vless://") ||
                    raw.startsWith("trojan://") ||
                    raw.startsWith("ss://")
            }
            .mapIndexed { idx, raw -> toServer(idx, raw) }
            .toList()
    }

    private fun decodeSubscription(text: String): String {
        val clean = normalizeConfigText(text)
        if (clean.startsWith("{") || clean.startsWith("[")) return clean
        if (clean.contains("://")) return clean
        return runCatching {
            val normalized = clean.replace("\n", "").replace("\r", "")
            String(Base64.decode(normalized, Base64.DEFAULT))
        }.getOrElse { clean }
    }

    private fun parseFullXrayJson(raw: String): ServerConfig? = runCatching {
        val clean = normalizeConfigText(raw)
        if (!clean.startsWith("{")) return@runCatching null
        val o = JSONObject(clean)
        if (!o.has("outbounds") || !o.has("inbounds")) return@runCatching null
        val name = o.optString("remarks")
            .ifBlank { o.optString("name") }
            .ifBlank { "ServerLess" }
        ServerConfig(sha(clean), name, clean, null, null)
    }.getOrNull()

    private fun normalizeConfigText(text: String): String = text
        .replace("\uFEFF", "")
        .trim()

    private fun toServer(i: Int, raw: String): ServerConfig {
        val name = raw
            .substringAfter('#', "Server ${i + 1}")
            .replace('+', ' ')
            .let { java.net.URLDecoder.decode(it, "UTF-8") }
        val hp = parseHostPort(raw)
        return ServerConfig(sha(raw), name.ifBlank { "Server ${i + 1}" }, raw, hp?.first, hp?.second)
    }

    private fun parseHostPort(raw: String): Pair<String, Int>? = runCatching {
        if (raw.startsWith("vmess://")) {
            val json = String(Base64.decode(raw.removePrefix("vmess://").substringBefore('#'), Base64.DEFAULT))
            val o = JSONObject(json)
            return@runCatching o.optString("add") to o.optInt("port")
        }
        val uri = URI(raw.substringBefore('#'))
        uri.host?.let { it to uri.port }
    }.getOrNull()

    private fun sha(s: String): String = MessageDigest
        .getInstance("SHA-1")
        .digest(s.toByteArray())
        .joinToString("") { "%02x".format(it) }
        .take(12)
}
