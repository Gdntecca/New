package com.superdownloader.app.engine

import java.io.IOException
import java.util.Locale

class HttpStatusException(val code: Int, msg: String? = null) : IOException(msg ?: "HTTP $code") {
    val retryable: Boolean get() = code == 408 || code == 429 || code >= 500
}

object Fmt {
    fun bytes(b: Long): String {
        if (b < 0) return "?"
        if (b < 1024) return "$b B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = b / 1024.0
        var i = 0
        while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
        return String.format(Locale.US, if (v >= 100) "%.0f %s" else "%.1f %s", v, units[i])
    }

    fun speed(bps: Long): String = bytes(bps) + "/s"

    fun duration(sec: Long): String {
        if (sec < 0) return "--"
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m ${s}s"
            else -> "${s}s"
        }
    }
}

fun extractUrls(text: String): List<String> =
    Regex("(https?://[^\\s\"'<>]+)", RegexOption.IGNORE_CASE).findAll(text).map { it.value.trimEnd('.', ',', ')', ']') }.distinct().toList()
