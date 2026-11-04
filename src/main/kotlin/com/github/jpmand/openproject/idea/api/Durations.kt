package com.github.jpmand.openproject.idea.api

import java.time.Duration

/** Parsing and formatting of the ISO-8601 durations OpenProject uses (`PT2H30M`, `P1DT18H`). */
object Durations {
    private val hoursMinutes = Regex("""^\s*(?:(\d+(?:[.,]\d+)?)\s*h)?\s*(?:(\d+)\s*m(?:in)?)?\s*$""", RegexOption.IGNORE_CASE)
    private val clock = Regex("""^\s*(\d+):(\d{1,2})\s*$""")
    private val decimalHours = Regex("""^\s*(\d+(?:[.,]\d+)?)\s*$""")

    fun parseIso(value: String?): Duration? =
        value?.takeIf { it.isNotBlank() }?.let { runCatching { Duration.parse(it) }.getOrNull() }

    /**
     * Parses user input: "1h 30m", "1h", "45m", "1.5" or "1,5" (hours), "1:30" or ISO "PT1H30M".
     * The Tasks time tracking format "Xh Ym" is a subset.
     */
    fun parseHuman(value: String): Duration? {
        val text = value.trim()
        if (text.isEmpty()) return null
        if (text.startsWith("P", ignoreCase = true)) return parseIso(text.uppercase())
        clock.matchEntire(text)?.let { m ->
            return Duration.ofHours(m.groupValues[1].toLong()).plusMinutes(m.groupValues[2].toLong())
        }
        decimalHours.matchEntire(text)?.let { m -> return fromHours(m.groupValues[1].replace(',', '.').toDouble()) }
        val m = hoursMinutes.matchEntire(text) ?: return null
        val hours = m.groupValues[1].takeIf { it.isNotEmpty() }?.replace(',', '.')?.toDouble() ?: 0.0
        val minutes = m.groupValues[2].takeIf { it.isNotEmpty() }?.toLong() ?: 0L
        if (m.groupValues[1].isEmpty() && m.groupValues[2].isEmpty()) return null
        return fromHours(hours).plusMinutes(minutes)
    }

    fun formatHuman(duration: Duration?): String {
        if (duration == null) return ""
        val totalMinutes = duration.toMinutes()
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours == 0L -> "${minutes}m"
            minutes == 0L -> "${hours}h"
            else -> "${hours}h ${minutes}m"
        }
    }

    /** ISO form accepted by OpenProject, e.g. "PT1H30M". */
    fun toIso(duration: Duration): String = duration.toString()

    private fun fromHours(hours: Double): Duration = Duration.ofSeconds(Math.round(hours * 3600))
}
