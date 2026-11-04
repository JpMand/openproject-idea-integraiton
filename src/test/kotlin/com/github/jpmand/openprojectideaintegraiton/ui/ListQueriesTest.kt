package com.github.jpmand.openprojectideaintegraiton.ui

import com.github.jpmand.openprojectideaintegraiton.api.FilterSpec
import com.github.jpmand.openprojectideaintegraiton.api.Filters
import com.github.jpmand.openprojectideaintegraiton.api.SortSpec
import com.github.jpmand.openprojectideaintegraiton.ui.list.ListQueries
import com.github.jpmand.openprojectideaintegraiton.ui.list.ListViewState
import com.github.jpmand.openprojectideaintegraiton.ui.list.StatusMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListQueriesTest {
    private val initial = ListViewState()

    @Test
    fun `default view lists open work packages recently updated first`() {
        val query = ListQueries.toQuery(initial)
        assertEquals(listOf(Filters.openStatus()), query.filters)
        assertEquals(listOf(SortSpec("updatedAt", ascending = false)), query.sortBy)
        assertEquals(1, query.page)
        assertEquals(StatusMode.OPEN, ListQueries.statusMode(initial))
    }

    @Test
    fun `search text becomes a global search or an id filter`() {
        assertEquals(Filters.search("login page"), ListQueries.toQuery(initial.copy(search = " login page ")).filters.last())
        assertEquals(Filters.ids(42), ListQueries.toQuery(initial.copy(search = "#42")).filters.last())
        assertEquals(listOf(Filters.openStatus()), ListQueries.toQuery(initial.copy(search = "  ")).filters)
    }

    @Test
    fun `status quick filter replaces only the status filter`() {
        val withType = initial.copy(filters = initial.filters + FilterSpec("type", "=", listOf("1")), page = 3)
        val closed = ListQueries.withStatusMode(withType, StatusMode.CLOSED)
        assertEquals(listOf(Filters.closedStatus(), FilterSpec("type", "=", listOf("1"))), closed.filters)
        assertEquals(1, closed.page)
        val all = ListQueries.withStatusMode(closed, StatusMode.ALL)
        assertEquals(listOf(FilterSpec("type", "=", listOf("1"))), all.filters)
        assertEquals(StatusMode.ALL, ListQueries.statusMode(all))
        val custom = all.copy(filters = listOf(FilterSpec("status", "=", listOf("1", "7"))))
        assertEquals(StatusMode.CUSTOM, ListQueries.statusMode(custom))
        assertEquals(custom, ListQueries.withStatusMode(custom, StatusMode.CUSTOM))
    }

    @Test
    fun `assigned to me uses the me value`() {
        val mine = ListQueries.withAssignedToMe(initial, true)
        assertTrue(ListQueries.isAssignedToMe(mine))
        assertEquals(FilterSpec("assignee", "=", listOf("me")), mine.filters.last())
        assertFalse(ListQueries.isAssignedToMe(ListQueries.withAssignedToMe(mine, false)))
    }

    @Test
    fun `header clicks toggle and add sort criteria`() {
        val bySubject = ListQueries.toggleSort(initial, "subject", additive = false)
        assertEquals(listOf(SortSpec("subject", true)), bySubject.sortBy)
        assertEquals(listOf(SortSpec("subject", false)), ListQueries.toggleSort(bySubject, "subject", false).sortBy)
        // dates sort newest first on the first click
        assertEquals(listOf(SortSpec("dueDate", false)), ListQueries.toggleSort(initial, "dueDate", false).sortBy)
        val twoKeys = ListQueries.toggleSort(bySubject, "status", additive = true)
        assertEquals(listOf(SortSpec("subject", true), SortSpec("status", true)), twoKeys.sortBy)
        assertEquals(listOf(SortSpec("subject", true), SortSpec("status", false)), ListQueries.toggleSort(twoKeys, "status", true).sortBy)
    }

    @Test
    fun `changing filters leaves the saved query and resets paging`() {
        val fromQuery = initial.copy(savedQueryId = 9, page = 4)
        val changed = ListQueries.withFilters(fromQuery, listOf(Filters.anyStatus()))
        assertEquals(0L, changed.savedQueryId)
        assertEquals(1, changed.page)
        assertEquals(fromQuery, ListQueries.withFilters(fromQuery, fromQuery.filters))
    }

    @Test
    fun `columns and pages`() {
        assertEquals(listOf("id", "subject"), ListQueries.withColumns(initial, emptyList()).columns)
        assertEquals(listOf("subject", "id"), ListQueries.withColumns(initial, listOf("subject", "id", "subject")).columns)
        assertEquals(1, ListQueries.pageCount(0, 50))
        assertEquals(3, ListQueries.pageCount(101, 50))
    }
}
