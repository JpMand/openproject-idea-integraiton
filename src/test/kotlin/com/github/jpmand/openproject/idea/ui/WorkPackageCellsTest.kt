package com.github.jpmand.openproject.idea.ui

import com.github.jpmand.openproject.idea.api.Fixtures
import com.github.jpmand.openproject.idea.api.OpenProjectApi
import com.github.jpmand.openproject.idea.ui.common.HtmlView
import com.github.jpmand.openproject.idea.ui.list.WorkPackageCells
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
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

    @Test
    fun `links to work packages of the same server are recognized`() {
        assertEquals(12L, HtmlView.workPackageId("https://op.example.com/work_packages/12/activity", "https://op.example.com"))
        assertEquals(12L, HtmlView.workPackageId("https://example.com/op/work_packages/12", "https://example.com/op"))
        assertNull(HtmlView.workPackageId("https://other.example.com/work_packages/12", "https://op.example.com"))
        assertNull(HtmlView.workPackageId("https://op.example.com/projects/demo", "https://op.example.com"))
    }
}
