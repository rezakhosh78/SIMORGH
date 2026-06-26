package com.rkh.vpn.data

import java.util.Locale

object MasterDnsRuntimeLog {
    private const val MAX_LINES = 80
    private val lines = ArrayDeque<String>()
    private val acceptedResolvers = LinkedHashSet<String>()
    private var scannedProgress = 0
    private var totalProgress = 0
    private var socksListening = false
    private val progressPattern = Regex("""\((\d+)\s*/\s*(\d+)\)""")
    private val acceptedPattern = Regex("""(?i)✅\s*accepted.*?via\s+([^\s|]+)""")

    @Synchronized
    fun append(line: String) {
        val clean = line.take(1200)
        lines.addLast(clean)
        while (lines.size > MAX_LINES) lines.removeFirst()
        parseLine(clean)
    }

    private fun parseLine(line: String) {
        val lower = line.lowercase(Locale.US)
        if (!socksListening && lower.contains("socks5 proxy server is listening on")) socksListening = true
        progressPattern.findAll(line).forEach { match ->
            val scanned = match.groupValues.getOrNull(1)?.toIntOrNull() ?: 0
            val total = match.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
            if (scanned > scannedProgress) scannedProgress = scanned
            if (total > totalProgress) totalProgress = total
        }
        if (lower.contains("accepted") && !lower.contains("rejected") && !lower.contains("timeout") && !lower.contains("fail")) {
            acceptedPattern.find(line)?.groupValues?.getOrNull(1)?.trim()?.let { endpoint ->
                if (endpoint.isNotBlank()) acceptedResolvers += endpoint
            }
        }
    }

    @Synchronized
    fun read(): List<String> = lines.toList()

    @Synchronized
    fun readRecent(maxLines: Int): List<String> = lines.takeLast(maxLines)

    @Synchronized
    fun acceptedResolversSnapshot(): List<String> = acceptedResolvers.toList()

    @Synchronized
    fun progressSnapshot(): Pair<Int, Int> = scannedProgress to totalProgress

    @Synchronized
    fun isSocksListeningDetected(): Boolean = socksListening

    @Synchronized
    fun clear() {
        lines.clear()
        acceptedResolvers.clear()
        scannedProgress = 0
        totalProgress = 0
        socksListening = false
    }
}
