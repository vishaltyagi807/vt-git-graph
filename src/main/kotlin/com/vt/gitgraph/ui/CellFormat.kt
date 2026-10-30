package com.vt.gitgraph.ui

import java.awt.FontMetrics
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object CellFormat {
    private val absolute = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
    private val full = DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss xxx").withZone(ZoneId.systemDefault())

    fun absolute(epochSeconds: Long): String = absolute.format(Instant.ofEpochSecond(epochSeconds))

    fun full(epochSeconds: Long): String = full.format(Instant.ofEpochSecond(epochSeconds))

    fun relative(epochSeconds: Long, now: Long = System.currentTimeMillis() / 1000): String {
        val delta = (now - epochSeconds).coerceAtLeast(0)
        return when {
            delta < 60 -> "just now"
            delta < 3600 -> plural(delta / 60, "minute")
            delta < 86400 -> plural(delta / 3600, "hour")
            delta < 86400 * 7 -> plural(delta / 86400, "day")
            delta < 86400 * 30 -> plural(delta / (86400 * 7), "week")
            delta < 86400 * 365 -> plural(delta / (86400 * 30), "month")
            else -> plural(delta / (86400 * 365), "year")
        }
    }

    private fun plural(value: Long, unit: String): String = if (value == 1L) "1 $unit ago" else "$value ${unit}s ago"

    fun initials(name: String): String {
        val parts = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return when {
            parts.isEmpty() -> "?"
            parts.size == 1 -> parts[0].take(2).uppercase()
            else -> (parts.first().take(1) + parts.last().take(1)).uppercase()
        }
    }

    fun fit(text: String, metrics: FontMetrics, width: Int): String {
        if (width <= 0) return ""
        if (metrics.stringWidth(text) <= width) return text
        val ellipsis = "…"
        val available = width - metrics.stringWidth(ellipsis)
        if (available <= 0) return ""
        var low = 0
        var high = text.length
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (metrics.stringWidth(text.substring(0, middle)) <= available) low = middle else high = middle - 1
        }
        return text.substring(0, low) + ellipsis
    }
}
