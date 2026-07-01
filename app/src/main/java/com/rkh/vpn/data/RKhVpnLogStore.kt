package com.rkh.vpn.data

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.io.File

object RKhVpnLogStore {
    private const val PREF = "rkh_vpn_debug_logs"
    private const val KEY = "lines"
    private const val MAX_LINES = 320
    private const val FILE_NAME = "rkh_vpn_debug_logs.txt"

    fun append(context: Context, source: String, message: String, throwable: Throwable? = null) {
        appendInternal(context, source, message, throwable, sync = false)
    }

    /**
     * Synchronous crash/exit logging. Used from fatal handlers where SharedPreferences.apply()
     * may be lost because Android is about to kill the process.
     */
    fun appendSync(context: Context, source: String, message: String, throwable: Throwable? = null) {
        appendInternal(context, source, message, throwable, sync = true)
    }

    private fun appendInternal(context: Context, source: String, message: String, throwable: Throwable?, sync: Boolean) {
        val line = buildString {
            append(SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()))
            append(" [")
            append(source)
            append("] ")
            append(message)
            if (throwable != null) {
                append(" • ")
                append(throwable.javaClass.name)
                throwable.message?.takeIf { it.isNotBlank() }?.let {
                    append(": ")
                    append(it)
                }
                val trace = throwable.stackTraceToString()
                    .lineSequence()
                    .drop(1)
                    .take(if (source.equals("CrashGuard", ignoreCase = true)) 10 else 3)
                    .joinToString(" | ") { it.trim().take(260) }
                if (trace.isNotBlank()) {
                    append(" • trace=")
                    append(trace)
                }
            }
        }.sanitizeForDisplay()

        synchronized(this) {
            val app = context.applicationContext
            val file = File(app.filesDir, FILE_NAME)
            val old = runCatching { file.readText() }.getOrDefault("")
                .lineSequence()
                .filter { it.isNotBlank() }
                .map { it.sanitizeForDisplay() }
                .toMutableList()
            old += line
            val kept = old.takeLast(MAX_LINES).joinToString("\n")
            runCatching { file.writeText(kept) }
            val prefs = app.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val editor = prefs.edit().putString(KEY, kept)
            if (sync) editor.commit() else editor.apply()
        }
        android.util.Log.d("RKhVPN-AppLog", line)
    }

    fun read(context: Context): List<String> {
        val app = context.applicationContext
        val fileText = runCatching { File(app.filesDir, FILE_NAME).readText() }.getOrDefault("")
        val prefsText = app.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
        val text = if (fileText.isNotBlank()) fileText else prefsText
        return text
            .lineSequence()
            .filter { it.isNotBlank() }
            .map { it.sanitizeForDisplay() }
            .toList()
    }

    fun readText(context: Context): String = read(context).joinToString("\n")

    fun clear(context: Context) {
        val app = context.applicationContext
        runCatching { File(app.filesDir, FILE_NAME).delete() }
        app.getSharedPreferences(PREF, Context.MODE_PRIVATE)
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
            .take(2_400)
    }
}
