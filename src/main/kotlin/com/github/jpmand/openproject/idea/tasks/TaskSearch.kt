package com.github.jpmand.openproject.idea.tasks

import com.github.jpmand.openproject.idea.api.FilterSpec
import com.github.jpmand.openproject.idea.api.Filters

/** Translates the Tasks "Open Task" search (text, include closed, paging) into OpenProject filters. */
object TaskSearch {
    private val numericId = Regex("""^#?(\d+)$""")

    /** OpenProject 17+ project-based ids such as "PROJ-12". */
    val displayId = Regex("""^[A-Za-z][A-Za-z0-9_]*-\d+$""")

    data class Options(
        val projectId: Long? = null,
        val assignedToMeOnly: Boolean = false,
        val fullTextSearch: Boolean = false,
        /** Filters of the saved query configured for the server, if any. */
        val baseFilters: List<FilterSpec> = emptyList(),
    )

    fun filters(query: String?, withClosed: Boolean, meId: Long?, options: Options): List<FilterSpec> {
        val filters = options.baseFilters.filter { it.name != "status" || withClosed }.toMutableList()
        if (!withClosed && filters.none { it.name == "status" }) filters += Filters.openStatus()
        if (options.projectId != null && filters.none { it.name == "project" }) filters += Filters.project(options.projectId)
        if (options.assignedToMeOnly && meId != null) {
            filters.removeAll { it.name == "assignee" || it.name == "assigneeOrGroup" }
            filters += Filters.assignee(meId)
        }
        val text = query?.trim().orEmpty()
        if (text.isNotEmpty()) {
            val id = numericId.matchEntire(text)?.groupValues?.get(1)?.toLong()
            filters += when {
                id != null -> Filters.ids(id)
                options.fullTextSearch -> Filters.search(text)
                // "typeahead" matches ids, display ids ("PROJ-12") and subjects, like OpenProject's own autocompleters.
                else -> FilterSpec("typeahead", "**", listOf(text))
            }
        }
        return filters
    }

    /**
     * Tasks asks for an item [offset] and [limit]; OpenProject pages by number. Returns the page to request, the page
     * size and how many leading items of that page to skip.
     */
    fun page(offset: Int, limit: Int): Triple<Int, Int, Int> {
        val pageSize = limit.coerceIn(1, 1000)
        val start = offset.coerceAtLeast(0)
        return Triple(start / pageSize + 1, pageSize, start % pageSize)
    }

    /** Normalizes "#12" to "12"; keeps display ids; returns null for anything that is not a work package id. */
    fun normalizeId(text: String): String? {
        val trimmed = text.trim()
        numericId.matchEntire(trimmed)?.let { return it.groupValues[1] }
        return trimmed.takeIf { displayId.matches(it) }?.uppercase()
    }
}
