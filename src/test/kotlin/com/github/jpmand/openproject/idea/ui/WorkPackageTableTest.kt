package com.github.jpmand.openproject.idea.ui

import com.github.jpmand.openproject.idea.api.model.WorkPackage
import com.github.jpmand.openproject.idea.ui.list.ColumnKind
import com.github.jpmand.openproject.idea.ui.list.ColumnWidths
import com.github.jpmand.openproject.idea.ui.list.WorkPackageTable
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.ListTableModel

class WorkPackageTableTest : BasePlatformTestCase() {

    fun `test column kinds come from known attributes and schema types`() {
        assertEquals(ColumnKind.PERCENT, ColumnWidths.kind("percentageDone", null))
        assertEquals(ColumnKind.DURATION, ColumnWidths.kind("spentTime", null))
        assertEquals(ColumnKind.DURATION, ColumnWidths.kind("estimatedTime", "Duration"))
        assertEquals(ColumnKind.DATE, ColumnWidths.kind("dueDate", null))
        assertEquals(ColumnKind.DATE_TIME, ColumnWidths.kind("createdAt", null))
        assertEquals(ColumnKind.ID, ColumnWidths.kind("id", null))
        // custom fields by their schema type
        assertEquals(ColumnKind.DURATION, ColumnWidths.kind("customField5", "Duration"))
        assertEquals(ColumnKind.DATE, ColumnWidths.kind("customField6", "Date"))
        assertEquals(ColumnKind.NUMBER, ColumnWidths.kind("customField7", "Float"))
        assertEquals(ColumnKind.TEXT, ColumnWidths.kind("customField8", "String"))
        assertEquals(ColumnKind.TEXT, ColumnWidths.kind("subject", null))
    }

    fun `test minimum widths fit typical values`() {
        val table = WorkPackageTable(ListTableModel())
        val metrics = table.getFontMetrics(table.font)
        for (kind in ColumnKind.entries - ColumnKind.TEXT) {
            assertTrue(kind.name, ColumnWidths.minWidth(kind, metrics) > metrics.stringWidth(ColumnWidths.sample(kind)))
        }
        assertTrue(ColumnWidths.minWidth(ColumnKind.DURATION, metrics) > metrics.stringWidth("100h 30m"))
        assertTrue(ColumnWidths.minWidth(ColumnKind.TEXT, metrics) > 0)
    }

    private fun table(columns: Int, width: Int): WorkPackageTable {
        val model = ListTableModel<WorkPackage>(*Array<ColumnInfo<WorkPackage, String>>(columns) { index ->
            object : ColumnInfo<WorkPackage, String>("c$index") {
                override fun valueOf(item: WorkPackage) = ""
            }
        })
        val table = WorkPackageTable(model)
        (0 until columns).forEach { table.columnModel.getColumn(it).preferredWidth = 100 }
        JBScrollPane(table).apply { setSize(width, 200); doLayout() }
        return table
    }

    fun `test the table fills the width while its columns fit and scrolls otherwise`() {
        assertTrue(table(columns = 3, width = 600).scrollableTracksViewportWidth)
        assertFalse(table(columns = 10, width = 600).scrollableTracksViewportWidth)
    }
}
