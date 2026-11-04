package com.github.jpmand.openprojectideaintegraiton.tasks

import com.github.jpmand.openprojectideaintegraiton.api.FilterSpec
import com.github.jpmand.openprojectideaintegraiton.api.Filters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration

class TaskSearchTest {

    @Test
    fun `open tasks only unless closed are requested`() {
        assertEquals(listOf(Filters.openStatus()), TaskSearch.filters(null, withClosed = false, meId = null, TaskSearch.Options()))
        assertEquals(emptyList<FilterSpec>(), TaskSearch.filters("", withClosed = true, meId = null, TaskSearch.Options()))
    }

    @Test
    fun `project and assignee restrictions`() {
        val filters = TaskSearch.filters(null, withClosed = false, meId = 7, TaskSearch.Options(projectId = 3, assignedToMeOnly = true))
        assertEquals(listOf(Filters.openStatus(), Filters.project(3), Filters.assignee(7)), filters)
        // without a known user id the assignee filter is skipped
        assertEquals(listOf(Filters.openStatus()), TaskSearch.filters(null, false, null, TaskSearch.Options(assignedToMeOnly = true)))
    }

    @Test
    fun `text becomes id typeahead or full text filter`() {
        val options = TaskSearch.Options()
        assertEquals(Filters.ids(42), TaskSearch.filters("#42", true, null, options).single())
        assertEquals(Filters.ids(42), TaskSearch.filters(" 42 ", true, null, options).single())
        assertEquals(FilterSpec("typeahead", "**", listOf("login bug")), TaskSearch.filters("login bug", true, null, options).single())
        assertEquals(Filters.search("login bug"), TaskSearch.filters("login bug", true, null, options.copy(fullTextSearch = true)).single())
    }

    @Test
    fun `saved query filters are kept and status follows the closed flag`() {
        val base = listOf(FilterSpec("status", "=", listOf("1", "7")), FilterSpec("type", "=", listOf("2")))
        val options = TaskSearch.Options(baseFilters = base)
        assertEquals(listOf(FilterSpec("type", "=", listOf("2")), Filters.openStatus()), TaskSearch.filters(null, false, null, options))
        assertEquals(listOf(FilterSpec("type", "=", listOf("2"))), TaskSearch.filters(null, true, null, options).filter { it.name != "status" })
    }

    @Test
    fun `item offsets map to pages`() {
        assertEquals(Triple(1, 20, 0), TaskSearch.page(0, 20))
        assertEquals(Triple(2, 20, 0), TaskSearch.page(20, 20))
        assertEquals(Triple(1, 20, 5), TaskSearch.page(5, 20))
        assertEquals(Triple(1, 1000, 0), TaskSearch.page(0, 5000))
    }

    @Test
    fun `ids are normalized`() {
        assertEquals("12", TaskSearch.normalizeId("#12"))
        assertEquals("PROJ-12", TaskSearch.normalizeId("proj-12"))
        assertNull(TaskSearch.normalizeId("fix the login"))
    }

    @Test
    fun `time spent from the time tracking plugin`() {
        assertEquals(Duration.ofMinutes(90), OpenProjectRepository.parseTimeSpent("1h 30m"))
        assertEquals(Duration.ZERO, OpenProjectRepository.parseTimeSpent("0h 0m"))
        assertEquals(Duration.ofMinutes(45), OpenProjectRepository.parseTimeSpent("45m"))
    }

    @Test
    fun `task types are guessed from the work package type`() {
        assertEquals(com.intellij.tasks.TaskType.BUG, OpenProjectTask.typeOf("Bug"))
        assertEquals(com.intellij.tasks.TaskType.FEATURE, OpenProjectTask.typeOf("User story"))
        assertEquals(com.intellij.tasks.TaskType.OTHER, OpenProjectTask.typeOf("Task"))
    }
}
