package com.rkh.vpn.core

import android.util.Base64
import org.json.JSONObject
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class SimorghWarpAccount(
    val privateKey: String,
    val publicKey: String,
    val localAddresses: List<String>,
    val peerPublicKey: String,
    val endpointHost: String,
    val endpointPort: Int,
    val reserved: List<Int>,
    val mtu: Int
)

/** Cloudflare WARP registration and X25519 key generation adapted from PingNG. */
object WarpAccountRegistration {
    private const val API = "https://api.cloudflareclient.com/v0a1922/reg"
    private val random = SecureRandom()
    private val prime = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    private val a24 = BigInteger.valueOf(121665)

    fun register(): SimorghWarpAccount {
        val privateBytes = ByteArray(32).also(random::nextBytes)
        val publicBytes = scalarMult(privateBytes, ByteArray(32).also { it[0] = 9 })
        val privateKey = Base64.encodeToString(privateBytes, Base64.NO_WRAP)
        val publicKey = Base64.encodeToString(publicBytes, Base64.NO_WRAP)
        val request = JSONObject()
            .put("key", publicKey)
            .put("install_id", "")
            .put("fcm_token", "")
            .put("tos", timestamp())
            .put("model", "SIMORGH Android")
            .put("locale", "en_US")
            .put("type", "Android")
        val connection = URL(API).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 20_000
        connection.readTimeout = 25_000
        connection.doOutput = true
        connection.setRequestProperty("User-Agent", "okhttp/3.12.1")
        connection.setRequestProperty("CF-Client-Version", "a-6.3-1922")
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        try {
            connection.outputStream.use { it.write(request.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            check(status in 200..299) { "WARP registration failed ($status): ${body.take(180)}" }
            val root = JSONObject(body)
            val config = root.optJSONObject("config") ?: error("WARP registration returned no config")
            val iface = config.optJSONObject("interface") ?: error("WARP registration returned no interface")
            val addresses = iface.optJSONObject("addresses") ?: error("WARP registration returned no addresses")
            val localAddresses = buildList {
                addresses.optString("v4").removeSuffix("/32").takeIf(String::isNotBlank)?.let { add("$it/32") }
                addresses.optString("v6").removeSuffix("/128").takeIf(String::isNotBlank)?.let { add("$it/128") }
            }
            check(localAddresses.isNotEmpty()) { "WARP registration returned no tunnel addresses" }
            val peer = config.optJSONArray("peers")?.optJSONObject(0)
                ?: error("WARP registration returned no peer")
            val peerKey = peer.optString("public_key")
            check(peerKey.isNotBlank()) { "WARP registration returned no peer key" }
            val endpoint = peer.opt("endpoint")
            val endpointText = when (endpoint) {
                is String -> endpoint
                is JSONObject -> endpoint.optString("v4").ifBlank { endpoint.optString("v6") }
                else -> ""
            }.trim()
            val split = splitEndpoint(endpointText)
            val port = peer.optInt("port", split.second ?: 2408).takeIf { it in 1..65535 } ?: 2408
            val host = split.first.removePrefix("[").removeSuffix("]")
            check(host.isNotBlank()) { "WARP registration returned an invalid endpoint" }
            val clientId = config.optString("client_id").ifBlank { root.optString("client_id") }
            return SimorghWarpAccount(
                privateKey = privateKey,
                publicKey = publicKey,
                localAddresses = localAddresses,
                peerPublicKey = peerKey,
                endpointHost = host,
                endpointPort = port,
                reserved = reservedBytes(clientId),
                mtu = iface.optInt("mtu", 1280).takeIf { it in 576..9000 } ?: 1280,
            )
        } finally {
            connection.disconnect()
        }
    }

    fun endpointHostPort(host: String, port: Int): String =
        if (host.contains(':') && !host.startsWith('[')) "[$host]:$port" else "$host:$port"

    fun resolveEndpoint(raw: String): String {
        val text = raw.trim()
        val host: String
        val port: Int
        if (text.startsWith("[")) {
            val close = text.indexOf(']')
            require(close > 1 && text.getOrNull(close + 1) == ':') { "WARP endpoint must be host:port" }
            host = text.substring(1, close)
            port = text.substring(close + 2).toIntOrNull() ?: 0
        } else {
            val split = text.lastIndexOf(':')
            require(split > 0 && split == text.indexOf(':')) { "WARP endpoint must be host:port; use [IPv6]:port for IPv6" }
            host = text.substring(0, split)
            port = text.substring(split + 1).toIntOrNull() ?: 0
        }
        require(port in 1..65535 && host.isNotBlank()) { "WARP endpoint must have a valid host and port" }
        val address = java.net.InetAddress.getAllByName(host).firstOrNull { it.address.size == 4 }
            ?: java.net.InetAddress.getAllByName(host).firstOrNull()
            ?: error("Could not resolve WARP endpoint")
        return endpointHostPort(address.hostAddress.substringBefore('%'), port)
    }

    fun keyToHex(value: String): String = Base64.decode(value, Base64.DEFAULT)
        .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }

    private fun splitEndpoint(value: String): Pair<String, Int?> {
        val text = value.trim()
        if (text.startsWith("[")) {
            val close = text.indexOf(']')
            if (close > 0) return text.substring(0, close + 1) to text.substring(close + 1).removePrefix(":").toIntOrNull()
        }
        val split = text.lastIndexOf(':')
        if (split > 0 && text.indexOf(':') == split) return text.substring(0, split) to text.substring(split + 1).toIntOrNull()
        return text to null
    }

    private fun reservedBytes(clientId: String): List<Int> {
        val decoded = sequenceOf(
            Base64.DEFAULT,
            Base64.NO_WRAP,
            Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING,
        ).mapNotNull { flags -> runCatching { Base64.decode(clientId, flags) }.getOrNull() }
            .firstOrNull { it.size >= 3 }
        if (decoded != null) return decoded.take(3).map { it.toInt() and 0xff }
        val hex = clientId.replace("-", "")
        if (hex.length >= 6 && hex.take(6).all { it in "0123456789abcdefABCDEF" }) {
            return listOf(0, 2, 4).map { hex.substring(it, it + 2).toInt(16) }
        }
        error("WARP registration returned an invalid client id")
    }

    private fun timestamp() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())

    private fun scalarMult(scalar: ByteArray, uBytes: ByteArray): ByteArray {
        val k = scalar.copyOf().also {
            it[0] = (it[0].toInt() and 248).toByte()
            it[31] = (it[31].toInt() and 127 or 64).toByte()
        }
        val x1 = fromLittleEndian(uBytes)
        var x2 = BigInteger.ONE; var z2 = BigInteger.ZERO; var x3 = x1; var z3 = BigInteger.ONE; var swap = 0
        for (t in 254 downTo 0) {
            val bit = (k[t shr 3].toInt() ushr (t and 7)) and 1
            swap = swap xor bit
            if (swap == 1) { val tx = x2; x2 = x3; x3 = tx; val tz = z2; z2 = z3; z3 = tz }
            swap = bit
            val a = mod(x2 + z2); val aa = mod(a * a); val b = mod(x2 - z2); val bb = mod(b * b)
            val e = mod(aa - bb); val c = mod(x3 + z3); val d = mod(x3 - z3); val da = mod(d * a); val cb = mod(c * b)
            x3 = mod((da + cb) * (da + cb)); z3 = mod(x1 * (da - cb) * (da - cb)); x2 = mod(aa * bb); z2 = mod(e * (aa + a24 * e))
        }
        if (swap == 1) { val tx = x2; x2 = x3; x3 = tx; val tz = z2; z2 = z3; z3 = tz }
        return toLittleEndian(mod(x2 * z2.modInverse(prime)))
    }

    private fun mod(value: BigInteger) = value.mod(prime)
    private fun fromLittleEndian(bytes: ByteArray) = BigInteger(1, bytes.reversedArray())
    private fun toLittleEndian(value: BigInteger): ByteArray {
        val source = value.toByteArray()
        return ByteArray(32).also { result -> for (i in result.indices) { val index = source.size - 1 - i; if (index >= 0) result[i] = source[index] } }
    }
}
