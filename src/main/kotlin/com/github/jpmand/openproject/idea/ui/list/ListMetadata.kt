package com.github.jpmand.openproject.idea.ui.list

import com.github.jpmand.openproject.idea.OpenProjectBundle
import com.github.jpmand.openproject.idea.api.hal.HalLink
import com.github.jpmand.openproject.idea.api.model.FilterInstanceSchema
import com.github.jpmand.openproject.idea.core.OpenProjectConnection
import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.coroutines.CancellationException
import kotlin.time.Duration.Companion.minutes

data class ColumnMeta(val id: String, val title: String, val sortable: Boolean)

data class FilterMeta(
    val id: String,
    val title: String,
    /** Operator -> value type (`[]Status`, `[1]String`, ...; null when the operator takes no values). */
    val operators: Map<String, String?>,
    val allowedValues: Map<String, List<HalLink>> = emptyMap(),
)

data class ValueOption(val id: String, val title: String, val color: java.awt.Color? = null) {
    override fun toString(): String = title
}

/**
 * What the server offers for the table: columns (with titles and whether they can be sorted) from the query
 * form, and filters with their operators from the filter instance schemas. Falls back to a fixed set of
 * core attributes when the server does not answer.
 */
class ListMetadata(val columns: Map<String, ColumnMeta>, val filters: List<FilterMeta>) {

    fun columnTitle(id: String): String = columns[id]?.title ?: Labels.humanize(id)

    fun isSortable(id: String): Boolean = columns[id]?.sortable ?: (id in FALLBACK_SORTABLE)

    fun filter(id: String): FilterMeta? = filters.firstOrNull { it.id == id }

    /** Filters the user can add (internal and duplicate-of-search ones hidden). */
    val addableFilters: List<FilterMeta> get() = filters.filter { it.id !in HIDDEN_FILTERS }.sortedBy { it.title.lowercase() }

    companion object {
        private val HIDDEN_FILTERS = setOf("typeahead", "subjectOr", "subjectOrId", "manualSort", "search")
        private val UNSUPPORTED_COLUMNS = Regex("^(relationsToType|relationsOfType|relations|bcfThumbnail|childRelations).*")
        private val FALLBACK_SORTABLE = setOf(
            "id", "subject", "type", "status", "priority", "assignee", "responsible", "author", "project",
            "startDate", "dueDate", "createdAt", "updatedAt", "percentageDone", "estimatedTime", "version", "category",
        )

        val FALLBACK = ListMetadata(
            columns = (ListViewState.DEFAULT_COLUMNS + listOf("project", "author", "responsible", "startDate", "dueDate", "estimatedTime", "percentageDone", "createdAt", "version", "category"))
                .associateWith { ColumnMeta(it, Labels.humanize(it), it in FALLBACK_SORTABLE) },
            filters = emptyList(),
        )

        suspend fun load(connection: OpenProjectConnection): ListMetadata = connection.cached("listMetadata", 30.minutes) {
            val columns = try {
                val schema = connection.api.queryForm().schema
                val sortable = schema["sortBy"]?.allowedOptions.orEmpty()
                    .mapNotNull { it.href?.substringAfterLast('/')?.substringBeforeLast('-') }.toSet()
                schema["columns"]?.allowedOptions.orEmpty()
                    .mapNotNull { option ->
                        val id = option.href?.substringAfterLast('/') ?: return@mapNotNull null
                        if (UNSUPPORTED_COLUMNS.matches(id)) null else ColumnMeta(id, option.title ?: Labels.humanize(id), id in sortable)
                    }
                    .associateBy { it.id }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                thisLogger().info("Query form unavailable, using default columns", e)
                emptyMap()
            }.ifEmpty { FALLBACK.columns }
            val filters = try {
                connection.filterSchemas(null).map { schema -> toFilterMeta(schema, columns) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                thisLogger().info("Filter schemas unavailable", e)
                emptyList()
            }
            ListMetadata(columns, filters)
        }

        private fun toFilterMeta(schema: FilterInstanceSchema, columns: Map<String, ColumnMeta>) = FilterMeta(
            id = schema.id,
            title = schema.displayName ?: columns[schema.id]?.title ?: Labels.filterName(schema.id),
            operators = schema.operatorValueTypes,
            allowedValues = schema.operatorAllowedValues,
        )
    }
}

/** Display names for filters and operators the server does not name. */
object Labels {
    fun humanize(id: String): String = OpenProjectBundle.messageOrNull("column.$id")
        ?: id.replace(Regex("([a-z])([A-Z])"), "$1 $2").replaceFirstChar { it.uppercase() }

    fun filterName(id: String): String = OpenProjectBundle.messageOrNull("filter.$id") ?: humanize(id)

    fun operator(operator: String): String = OpenProjectBundle.messageOrNull("operator.${operatorKey(operator)}") ?: operator

    private fun operatorKey(operator: String): String = when (operator) {
        "=" -> "is"
        "!" -> "not"
        "*" -> "any"
        "!*" -> "none"
        "~" -> "contains"
        "!~" -> "not.contains"
        "**" -> "search"
        ">=" -> "gte"
        "<=" -> "lte"
        "<t+" -> "in.less.than"
        ">t+" -> "in.more.than"
        "t+" -> "in"
        ">t-" -> "less.than.ago"
        "<t-" -> "more.than.ago"
        "t-" -> "ago"
        "=d" -> "on"
        "<>d" -> "between"
        "&=" -> "all.of"
        else -> operator
    }
}
