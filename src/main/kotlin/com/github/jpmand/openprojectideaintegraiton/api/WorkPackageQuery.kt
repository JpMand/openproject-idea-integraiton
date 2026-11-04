package com.github.jpmand.openprojectideaintegraiton.api

import com.github.jpmand.openprojectideaintegraiton.api.model.QueryFilterInstance
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** One entry of OpenProject's `filters` parameter, e.g. `{"status": {"operator": "o", "values": []}}`. */
data class FilterSpec(val name: String, val operator: String, val values: List<String> = emptyList())

/** One entry of OpenProject's `sortBy` parameter, e.g. `["updatedAt", "desc"]`. */
data class SortSpec(val attribute: String, val ascending: Boolean = true)

data class WorkPackageQuery(
    val filters: List<FilterSpec> = emptyList(),
    val sortBy: List<SortSpec> = listOf(SortSpec("updatedAt", ascending = false)),
    /** 1-based page number (OpenProject's `offset`). */
    val page: Int = 1,
    val pageSize: Int = 50,
    val includeSubprojects: Boolean = true,
)

/** Builders and (de)serialization for OpenProject's `filters` and `sortBy` parameters. */
object Filters {
    /** Operators that take no values. */
    val VALUELESS_OPERATORS = setOf("o", "c", "*", "!*", "t", "w", "ow")

    fun openStatus() = FilterSpec("status", "o")
    fun closedStatus() = FilterSpec("status", "c")
    fun anyStatus() = FilterSpec("status", "*")
    fun assignee(userId: Long) = FilterSpec("assignee", "=", listOf(userId.toString()))
    fun author(userId: Long) = FilterSpec("author", "=", listOf(userId.toString()))
    fun watcher(userId: Long) = FilterSpec("watcher", "=", listOf(userId.toString()))
    fun project(projectId: Long) = FilterSpec("project", "=", listOf(projectId.toString()))
    fun ids(vararg ids: Long) = FilterSpec("id", "=", ids.map(Long::toString))

    /** OpenProject's global search across subject, description and comments. */
    fun search(text: String) = FilterSpec("search", "**", listOf(text))

    /** Matches the subject or the id; used for task lookups. */
    fun subjectOrId(text: String) = FilterSpec("subjectOrId", "**", listOf(text))

    fun updatedWithinDays(days: Int) = FilterSpec("updatedAt", ">t-", listOf(days.toString()))

    fun toJson(filters: List<FilterSpec>): String = buildJsonArray {
        filters.forEach { filter ->
            addJsonObject {
                putJsonObject(filter.name) {
                    put("operator", filter.operator)
                    putJsonArray("values") { filter.values.forEach { add(JsonPrimitive(it)) } }
                }
            }
        }
    }.toString()

    fun fromJson(json: String): List<FilterSpec> = parseArray(json).mapNotNull { entry ->
        val (name, spec) = (entry as? JsonObject)?.entries?.singleOrNull() ?: return@mapNotNull null
        val body = spec as? JsonObject ?: return@mapNotNull null
        val operator = (body["operator"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        val values = (body["values"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        FilterSpec(name, operator, values)
    }

    fun sortToJson(sortBy: List<SortSpec>): String = buildJsonArray {
        sortBy.forEach { sort ->
            addJsonArray {
                add(JsonPrimitive(sort.attribute))
                add(JsonPrimitive(if (sort.ascending) "asc" else "desc"))
            }
        }
    }.toString()

    fun sortFromJson(json: String): List<SortSpec> = parseArray(json).mapNotNull { entry ->
        val pair = (entry as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: return@mapNotNull null
        if (pair.isEmpty()) null else SortSpec(pair[0], pair.getOrNull(1) != "desc")
    }

    /** Converts the `filters` of a saved query (`_links.filter/operator/values` plus plain `values`). */
    fun fromQueryFilters(filters: List<QueryFilterInstance>): List<FilterSpec> = filters.mapNotNull { instance ->
        val name = instance.links["filter"]?.id ?: return@mapNotNull null
        val operator = instance.links["operator"]?.id
            ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8) } ?: return@mapNotNull null
        val linkValues = instance.links.list("values").mapNotNull { it.id }
        val plainValues = instance.values.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        FilterSpec(name, operator, linkValues + plainValues)
    }

    private fun parseArray(json: String): JsonArray =
        runCatching { Json.parseToJsonElement(json) as? JsonArray }.getOrNull() ?: JsonArray(emptyList())
}
