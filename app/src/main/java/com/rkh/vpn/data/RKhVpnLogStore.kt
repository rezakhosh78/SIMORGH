package com.rkh.vpn.data

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object RKhVpnLogStore {
    private const val PREF = "rkh_vpn_debug_logs"
    private const val KEY = "lines"
    private const val MAX_LINES = 60

    fun append(context: Context, source: String, message: String, throwable: Throwable? = null) {
        val line = buildString {
            append(SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()))
            append(" [")
            append(source)
            append("] ")
            append(message)
            if (throwable != null) {
                append(" • ")
                append(throwable.javaClass.simpleName)
                throwable.message?.takeIf { it.isNotBlank() }?.let {
                    append(": ")
                    append(it)
                }
                val trace = throwable.stackTraceToString()
                    .lineSequence()
                    .drop(1)
                    .take(2)
                    .joinToString(" | ") { it.trim().take(240) }
                if (trace.isNotBlank()) {
                    append(" • trace=")
                    append(trace)
                }
            }
        }.sanitizeForDisplay()

        synchronized(this) {
            val prefs = context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val old = prefs.getString(KEY, "").orEmpty()
                .lineSequence()
                .filter { it.isNotBlank() }
                .map { it.sanitizeForDisplay() }
                .toMutableList()
            old += line
            val kept = old.takeLast(MAX_LINES).joinToString("\n")
            prefs.edit().putString(KEY, kept).apply()
        }
        android.util.Log.d("RKhVPN-AppLog", line)
    }

    fun read(context: Context): List<String> {
        val prefs = context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return prefs.getString(KEY, "").orEmpty()
            .lineSequence()
            .filter { it.isNotBlank() }
            .map { it.sanitizeForDisplay() }
            .toList()
    }

    fun readText(context: Context): String = read(context).joinToString("\n")

    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY)
            .apply()
    }

    private fun String.sanitizeForDisplay(): String {
        return this
            .replace(Regex("https?://\\S+"), "[hidden-url]")
            .replace(Regex("\\b[a-zA-Z0-9-]+(?:\\.[a-zA-Z0-9-]+)+\\b")) { match ->
                val value = match.value
                val ipv4 = value.split('.').size == 4 && value.split('.').all { it.toIntOrNull()?.let { part -> part in 0..255 } == true }
                if (value.contains("/") || ipv4 || value == "127.0.0.1") value else "[hidden-host]"
            }
            .take(1_200)
    }
}
