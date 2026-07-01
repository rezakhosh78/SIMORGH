package com.rkh.vpn.data

import android.content.Context
import java.io.File
import java.util.Locale

object StormDnsRuntimeLog {
    private const val MAX_LINES = 180
    private const val FILE_NAME = "stormdns_runtime_logs.txt"
    private val lines = ArrayDeque<String>()
    private val acceptedResolvers = LinkedHashSet<String>()
    private var scannedProgress = 0
    private var totalProgress = 0
    private var socksListening = false
    private val progressPattern = Regex("""\((\d+)\s*/\s*(\d+)\)""")
    private val acceptedPattern = Regex("""(?i)(?:✅\s*)?accepted.*?\bvia\s+(\[[^\]]+\]:\d+|[^\s|),;]+)""")
    private val reactivatedPattern = Regex("""(?i)dns\s+resolver\s+reactivated:\s+(\[[^\]]+\]:\d+|[^\s|),;]+)""")
    private val validTableResolverPattern = Regex("""(?i)(?:^|\s)(\d{1,3}(?:\.\d{1,3}){3}:\d+|\[[0-9a-f:]+\]:\d+)\s+\d+\s+\d+\s+(?:\d+(?:ms|s)|\d+\.\d+s|[0-9.]+s)\s+\S+""")
    private val endpointLikePattern = Regex("""(?i)(?:resolver|dns)\D+(\d{1,3}(?:\.\d{1,3}){3}:\d+|\[[0-9a-f:]+\]:\d+)""")

    @Synchronized
    fun append(line: String) {
        appendInternal(context = null, line = line)
    }

    @Synchronized
    fun append(context: Context, line: String) {
        appendInternal(context = context.applicationContext, line = line)
    }

    private fun appendInternal(context: Context?, line: String) {
        val clean = line.take(1200)
        if (context == null) {
            lines.add(clean)
            while (lines.size > MAX_LINES) lines.removeFirst()
            parseLine(clean)
            return
        }

        val kept = (readFileLines(context) + clean)
            .filter { it.isNotBlank() }
            .takeLast(MAX_LINES)
        writeFileLines(context, kept)
        resetMemoryFrom(kept)
    }

    private fun logFile(context: Context): File = File(context.applicationContext.filesDir, FILE_NAME)

    private fun readFileLines(context: Context): List<String> {
        return runCatching {
            logFile(context).readText()
                .lineSequence()
                .filter { it.isNotBlank() }
                .map { it.take(1200) }
                .toList()
        }.getOrDefault(emptyList())
    }

    private fun writeFileLines(context: Context, kept: List<String>) {
        runCatching { logFile(context).writeText(kept.joinToString("\n")) }
    }

    private fun resetMemoryFrom(source: List<String>) {
        lines.clear()
        acceptedResolvers.clear()
        scannedProgress = 0
        totalProgress = 0
        socksListening = false
        source.takeLast(MAX_LINES).forEach { line ->
            lines.add(line)
            parseLine(line)
        }
    }

    private fun normalizeEndpoint(raw: String): String {
        val clean = raw.trim().trim(',', ';', ')', ']', '[', ' ')
        if (clean.isBlank()) return ""
        if (raw.trim().startsWith("[") && raw.contains("]:")) {
            val host = raw.substringAfter("[").substringBefore("]").trim()
            val port = raw.substringAfter("]:", "53").takeWhile { it.isDigit() }.ifBlank { "53" }
            return "[$host]:$port"
        }
        val withoutBrackets = clean.removePrefix("[").substringBefore("]")
        return when {
            Regex("""^\d{1,3}(?:\.\d{1,3}){3}:\d+$""").matches(withoutBrackets) -> withoutBrackets
            Regex("""^\d{1,3}(?:\.\d{1,3}){3}$""").matches(withoutBrackets) -> "$withoutBrackets:53"
            withoutBrackets.contains(":") -> withoutBrackets
            else -> withoutBrackets
        }
    }

    private fun addAcceptedEndpoint(raw: String?) {
        val endpoint = normalizeEndpoint(raw.orEmpty())
        if (endpoint.isNotBlank()) acceptedResolvers += endpoint
    }

    private fun parseLine(line: String) {
        val lower = line.lowercase(Locale.US)
        if (!socksListening && (
                lower.contains("socks5 proxy server is listening on") ||
                    (lower.contains("socks5") && lower.contains("listening") && lower.contains("18000")) ||
                    (lower.contains("proxy server") && lower.contains("listening") && lower.contains("18000"))
            )
        ) socksListening = true

        progressPattern.findAll(line).forEach { match ->
            val scanned = match.groupValues.getOrNull(1)?.toIntOrNull() ?: 0
            val total = match.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
            if (scanned > scannedProgress) scannedProgress = scanned
            if (total > totalProgress) totalProgress = total
        }

        val looksHealthy = (lower.contains("accepted") || lower.contains("dns resolver reactivated") || lower.contains("valid connections after mtu testing")) &&
            !lower.contains("rejected") && !lower.contains("timeout") && !lower.contains("fail")
        if (looksHealthy) {
            acceptedPattern.find(line)?.groupValues?.getOrNull(1)?.let { addAcceptedEndpoint(it) }
            reactivatedPattern.find(line)?.groupValues?.getOrNull(1)?.let { addAcceptedEndpoint(it) }
            endpointLikePattern.find(line)?.groupValues?.getOrNull(1)?.let { addAcceptedEndpoint(it) }
        }

        validTableResolverPattern.find(line)?.groupValues?.getOrNull(1)?.let { addAcceptedEndpoint(it) }
        // Do not use "Total Active" from reactivation logs as scan progress.
        // It is an active resolver pool size, not the MTU scan index/total.
    }

    @Synchronized
    fun read(): List<String> = lines.toList()

    @Synchronized
    fun read(context: Context): List<String> {
        val disk = readFileLines(context.applicationContext)
        if (disk.isNotEmpty()) resetMemoryFrom(disk)
        return if (disk.isNotEmpty()) disk else lines.toList()
    }

    @Synchronized
    fun readRecent(maxLines: Int): List<String> = lines.takeLast(maxLines)

    @Synchronized
    fun readRecent(context: Context, maxLines: Int): List<String> = read(context).takeLast(maxLines)

    @Synchronized
    fun acceptedResolversSnapshot(): List<String> = acceptedResolvers.toList()

    @Synchronized
    fun acceptedResolversSnapshot(context: Context): List<String> {
        read(context)
        return acceptedResolvers.toList()
    }

    @Synchronized
    fun progressSnapshot(): Pair<Int, Int> = scannedProgress to totalProgress

    @Synchronized
    fun progressSnapshot(context: Context): Pair<Int, Int> {
        read(context)
        return scannedProgress to totalProgress
    }

    @Synchronized
    fun isSocksListeningDetected(): Boolean = socksListening

    @Synchronized
    fun isSocksListeningDetected(context: Context): Boolean {
        read(context)
        return socksListening
    }

    @Synchronized
    fun clear() {
        lines.clear()
        acceptedResolvers.clear()
        scannedProgress = 0
        totalProgress = 0
        socksListening = false
    }

    @Synchronized
    fun clear(context: Context) {
        clear()
        runCatching { logFile(context.applicationContext).delete() }
    }
}
