package com.github.jpmand.openproject.idea.ui

import com.github.jpmand.openproject.idea.api.Fixtures
import com.github.jpmand.openproject.idea.api.OpenProjectApi
import com.github.jpmand.openproject.idea.ui.common.HtmlView
import com.github.jpmand.openproject.idea.ui.list.WorkPackageCells
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class WorkPackageCellsTest {
    private val workPackage = OpenProjectApi.decodeWorkPackage(Fixtures.json("work_package.json"))

    @Test
    fun `plain and linked attributes are displayed`() {
        assertEquals("AGILE-1", WorkPackageCells.text(workPackage, "id"))
        assertEquals(workPackage.subject, WorkPackageCells.text(workPackage, "subject"))
        assertEquals(workPackage.statusTitle, WorkPackageCells.text(workPackage, "status"))
        assertEquals(workPackage.links.title("project"), WorkPackageCells.text(workPackage, "project"))
        assertEquals("", WorkPackageCells.text(workPackage, "assignee"))
        assertEquals("", WorkPackageCells.text(workPackage, "noSuchColumn"))
    }

    @Test
    fun `dates durations and booleans are formatted`() {
        assertTrue(WorkPackageCells.text(workPackage, "createdAt", ZoneOffset.UTC).contains("2017"))
        assertEquals("1h 30m", WorkPackageCells.format(JsonPrimitive("PT1H30M")))
        assertEquals("✓", WorkPackageCells.format(JsonPrimitive(true)))
        assertEquals("", WorkPackageCells.format(JsonPrimitive(false)))
        assertEquals("42", WorkPackageCells.format(JsonPrimitive(42)))
    }

    private fun withProgress(done: JsonPrimitive?, derived: JsonPrimitive? = null) = OpenProjectApi.decodeWorkPackage(
        JsonObject(Fixtures.json("work_package.json") + listOfNotNull(done?.let { "percentageDone" to it }, derived?.let { "derivedPercentageDone" to it })),
    )

    @Test
    fun `percentages show 0 percent in the table when unset`() {
        assertEquals("0%", WorkPackageCells.tableText(workPackage, "percentageDone"))
        assertEquals("0%", WorkPackageCells.tableText(workPackage, "derivedPercentageDone"))
        assertNull(WorkPackageCells.percentage(workPackage, "percentageDone"))
        // the work package page still leaves an unset value out
        assertEquals("", WorkPackageCells.text(workPackage, "percentageDone"))

        val halfDone = withProgress(JsonPrimitive(50), JsonPrimitive(33.4))
        assertEquals(50, WorkPackageCells.percentage(halfDone, "percentageDone"))
        assertEquals("50%", WorkPackageCells.tableText(halfDone, "percentageDone"))
        assertEquals("33%", WorkPackageCells.tableText(halfDone, "derivedPercentageDone"))
        assertEquals(100, WorkPackageCells.percentage(withProgress(JsonPrimitive(130)), "percentageDone"))
        assertEquals("AGILE-1", WorkPackageCells.tableText(halfDone, "id"))
        assertTrue(WorkPackageCells.isPercentage("derivedPercentageDone"))
        assertFalse(WorkPackageCells.isPercentage("storyPoints"))
    }

    private fun withTimes(spent: String?, work: String?) = OpenProjectApi.decodeWorkPackage(
        JsonObject(Fixtures.json("work_package.json") + listOfNotNull(spent?.let { "spentTime" to JsonPrimitive(it) }, work?.let { "estimatedTime" to JsonPrimitive(it) })),
    )

    @Test
    fun `spent time over the work is possible overtime`() {
        assertTrue(WorkPackageCells.isOvertime(withTimes("PT2H30M", "PT2H")))
        assertTrue(WorkPackageCells.isOvertime(withTimes("P1DT1H", "PT8H")))
        assertFalse(WorkPackageCells.isOvertime(withTimes("PT2H", "PT2H")))
        assertFalse(WorkPackageCells.isOvertime(withTimes("PT1H", "PT2H")))
        // without planned work (or without time spent) there is nothing to compare
        assertFalse(WorkPackageCells.isOvertime(withTimes("PT5H", null)))
        assertFalse(WorkPackageCells.isOvertime(withTimes(null, "PT2H")))
        assertFalse(WorkPackageCells.isOvertime(workPackage))
    }

    @Test
    fun `links to work packages of the same server are recognized`() {
        assertEquals(12L, HtmlView.workPackageId("https://op.example.com/work_packages/12/activity", "https://op.example.com"))
        assertEquals(12L, HtmlView.workPackageId("https://example.com/op/work_packages/12", "https://example.com/op"))
        assertNull(HtmlView.workPackageId("https://other.example.com/work_packages/12", "https://op.example.com"))
        assertNull(HtmlView.workPackageId("https://op.example.com/projects/demo", "https://op.example.com"))
    }
}
