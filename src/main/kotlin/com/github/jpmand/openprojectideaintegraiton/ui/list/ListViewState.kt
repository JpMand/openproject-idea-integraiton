package com.github.jpmand.openprojectideaintegraiton.ui.list

import com.github.jpmand.openprojectideaintegraiton.api.FilterSpec
import com.github.jpmand.openprojectideaintegraiton.api.Filters
import com.github.jpmand.openprojectideaintegraiton.api.SortSpec
import com.github.jpmand.openprojectideaintegraiton.api.WorkPackageQuery

/** What the work package table shows: columns, sorting, filters, search and paging. */
data class ListViewState(
    val columns: List<String> = DEFAULT_COLUMNS,
    val widths: Map<String, Int> = emptyMap(),
    val sortBy: List<SortSpec> = DEFAULT_SORT,
    /** All filters, including the status and "assigned to me" quick filters. */
    val filters: List<FilterSpec> = listOf(Filters.openStatus()),
    val search: String = "",
    val page: Int = 1,
    val pageSize: Int = 50,
    /** Saved OpenProject query the view was loaded from (0 = none). */
    val savedQueryId: Long = 0,
) {
    companion object {
        val DEFAULT_COLUMNS = listOf("id", "subject", "type", "status", "assignee", "priority", "updatedAt")
        val DEFAULT_SORT = listOf(SortSpec("updatedAt", ascending = false))
    }
}

enum class StatusMode { OPEN, CLOSED, ALL, CUSTOM }

/** Pure transformations of [ListViewState]; the editor only wires them to the UI. */
object ListQueries {
    private const val STATUS = "status"
    private const val ASSIGNEE = "assignee"
    private const val ME = "me"
    private val numericId = Regex("""^#?(\d+)$""")

    fun toQuery(state: ListViewState): WorkPackageQuery {
        val text = state.search.trim()
        val searchFilter = when {
            text.isEmpty() -> null
            numericId.matches(text) -> Filters.ids(numericId.matchEntire(text)!!.groupValues[1].toLong())
            else -> Filters.search(text)
        }
        return WorkPackageQuery(
            filters = state.filters + listOfNotNull(searchFilter),
            sortBy = state.sortBy.ifEmpty { ListViewState.DEFAULT_SORT },
            page = state.page,
            pageSize = state.pageSize,
        )
    }

    fun statusMode(state: ListViewState): StatusMode {
        val status = state.filters.filter { it.name == STATUS }
        return when {
            status.isEmpty() -> StatusMode.ALL
            status.size > 1 -> StatusMode.CUSTOM
            status[0].operator == "o" -> StatusMode.OPEN
            status[0].operator == "c" -> StatusMode.CLOSED
            status[0].operator == "*" -> StatusMode.ALL
            else -> StatusMode.CUSTOM
        }
    }

    fun withStatusMode(state: ListViewState, mode: StatusMode): ListViewState {
        if (mode == StatusMode.CUSTOM) return state
        val others = state.filters.filter { it.name != STATUS }
        val status = when (mode) {
            StatusMode.OPEN -> Filters.openStatus()
            StatusMode.CLOSED -> Filters.closedStatus()
            else -> null
        }
        return state.copy(filters = listOfNotNull(status) + others, page = 1)
    }

    fun isAssignedToMe(state: ListViewState): Boolean =
        state.filters.any { it.name == ASSIGNEE && it.operator == "=" && it.values == listOf(ME) }

    fun withAssignedToMe(state: ListViewState, enabled: Boolean): ListViewState {
        val others = state.filters.filterNot { it.name == ASSIGNEE && it.operator == "=" && it.values == listOf(ME) }
        return state.copy(filters = if (enabled) others + FilterSpec(ASSIGNEE, "=", listOf(ME)) else others, page = 1)
    }

    fun withFilters(state: ListViewState, filters: List<FilterSpec>): ListViewState =
        if (filters == state.filters) state else state.copy(filters = filters, page = 1, savedQueryId = 0)

    fun withSearch(state: ListViewState, search: String): ListViewState =
        if (search == state.search) state else state.copy(search = search, page = 1)

    /**
     * Header click: sorts by [column], toggling the direction when it already is the primary sort. With [additive]
     * (Shift+click) the column is added as / toggled within the secondary criteria instead.
     */
    fun toggleSort(state: ListViewState, column: String, additive: Boolean): ListViewState {
        val current = state.sortBy
        val existing = current.firstOrNull { it.attribute == column }
        val sortBy = if (additive) {
            if (existing == null) current + SortSpec(column) else current.map { if (it.attribute == column) it.copy(ascending = !it.ascending) else it }
        } else {
            val ascending = if (current.firstOrNull()?.attribute == column) !current.first().ascending else defaultAscending(column)
            listOf(SortSpec(column, ascending))
        }
        return state.copy(sortBy = sortBy.take(3), page = 1)
    }

    fun withColumns(state: ListViewState, columns: List<String>): ListViewState =
        state.copy(columns = columns.distinct().ifEmpty { listOf("id", "subject") })

    fun pageCount(total: Int, pageSize: Int): Int = if (total <= 0) 1 else (total + pageSize - 1) / pageSize

    /** Dates are most useful newest first. */
    private fun defaultAscending(column: String): Boolean = !(column.endsWith("At") || column.endsWith("Date") || column == "date")
}
