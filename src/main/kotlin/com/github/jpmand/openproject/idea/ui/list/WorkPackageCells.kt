package com.github.jpmand.openproject.idea.ui.list

import com.github.jpmand.openproject.idea.api.Durations
import com.github.jpmand.openproject.idea.api.hal.HalJson
import com.github.jpmand.openproject.idea.api.hal.HalLinks
import com.github.jpmand.openproject.idea.api.model.WorkPackage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Display values of work package attributes for table columns. Columns are OpenProject query column ids, which
 * match the attribute names of the work package resource (`status`, `assignee`, `dueDate`, `customField7`, ...):
 * plain properties are formatted by type, linked resources show their titles.
 */
object WorkPackageCells {
    private val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    private val dateTimeFormat = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
    private val isoDate = Regex("""^\d{4}-\d{2}-\d{2}$""")
    private val isoDateTime = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}.*Z$""")
    private val isoDuration = Regex("""^P(?:\d+D)?(?:T(?:\d+H)?(?:\d+M)?(?:\d+(?:\.\d+)?S)?)?$""")

    fun text(workPackage: WorkPackage, column: String, zone: ZoneId = ZoneId.systemDefault()): String {
        when (column) {
            "id" -> return workPackage.presentableId
            "subject" -> return workPackage.subject
            "percentageDone", "derivedPercentageDone" ->
                return (workPackage.raw[column] as? JsonPrimitive)?.contentOrNull?.let { "$it%" }.orEmpty()
        }
        val property = workPackage.raw[column]
        if (property != null && property !is JsonNull) return format(property, zone)
        val links = workPackage.links.list(column)
        return links.mapNotNull { it.title }.joinToString(", ")
    }

    /** Sortable/comparable raw value, used for highlighting and tests. */
    fun format(value: kotlinx.serialization.json.JsonElement, zone: ZoneId = ZoneId.systemDefault()): String = when (value) {
        is JsonNull -> ""
        is JsonPrimitive -> formatPrimitive(value, zone)
        is JsonObject -> (value["raw"] as? JsonPrimitive)?.contentOrNull // Formattable
            ?: (value["_links"] as? JsonObject)?.let { HalJson.read.decodeFromJsonElement(HalLinks.serializer(), it).title("self") }
            ?: ""
        is JsonArray -> value.joinToString(", ") { format(it, zone) }
    }

    private fun formatPrimitive(value: JsonPrimitive, zone: ZoneId): String {
        value.booleanOrNull?.let { return if (it) "✓" else "" }
        val text = value.contentOrNull ?: return ""
        if (!value.isString) return text
        return when {
            isoDate.matches(text) -> runCatching { LocalDate.parse(text).format(dateFormat) }.getOrDefault(text)
            isoDateTime.matches(text) -> runCatching { Instant.parse(text).atZone(zone).format(dateTimeFormat) }.getOrDefault(text)
            isoDuration.matches(text) && text.length > 1 -> Durations.formatHuman(Durations.parseIso(text)).ifEmpty { text }
            else -> text
        }
    }
}
