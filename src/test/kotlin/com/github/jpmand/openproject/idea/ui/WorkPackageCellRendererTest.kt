package com.github.jpmand.openproject.idea.ui

import com.github.jpmand.openproject.idea.api.Fixtures
import com.github.jpmand.openproject.idea.api.OpenProjectApi
import com.github.jpmand.openproject.idea.api.model.WorkPackage
import com.github.jpmand.openproject.idea.ui.list.WorkPackageCellRenderer
import com.github.jpmand.openproject.idea.ui.list.WorkPackageCells
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.table.JBTable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.awt.Color
import java.awt.image.BufferedImage
import javax.swing.JLabel
import javax.swing.table.DefaultTableModel

class WorkPackageCellRendererTest : BasePlatformTestCase() {

    private fun workPackage(percentageDone: Int?): WorkPackage {
        val json = Fixtures.json("work_package.json")
        return OpenProjectApi.decodeWorkPackage(JsonObject(json + listOfNotNull(percentageDone?.let { "percentageDone" to JsonPrimitive(it) })))
    }

    /** Paints the "% Complete" cell of [item] (200 x 20 px) and returns the image. */
    private fun paint(item: WorkPackage, columnId: String = "percentageDone"): Pair<BufferedImage, JLabel> {
        val table = JBTable(DefaultTableModel(arrayOf(arrayOf<Any>(WorkPackageCells.tableText(item, columnId))), arrayOf("% Complete")))
        val renderer = WorkPackageCellRenderer({ _, _ -> item }, { _, _ -> columnId }, { false })
        val label = renderer.getTableCellRendererComponent(table, WorkPackageCells.tableText(item, columnId), false, false, 0, 0) as JLabel
        label.setSize(200, 20)
        val image = BufferedImage(200, 20, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        label.paint(graphics)
        graphics.dispose()
        return image to label
    }

    private fun isBar(rgb: Int) = Color(rgb) == WorkPackageCellRenderer.BAR

    fun `test percentage cells fill a bar behind the value`() {
        val (image, label) = paint(workPackage(50))
        assertEquals("50%", label.text)
        assertEquals(JLabel.CENTER, label.horizontalAlignment)
        // left half filled (sampled at the top edge of the bar, clear of the text), right half not
        assertTrue(isBar(image.getRGB(10, 3)))
        assertTrue(isBar(image.getRGB(90, 3)))
        assertFalse(isBar(image.getRGB(110, 3)))
        assertFalse(isBar(image.getRGB(190, 3)))
    }

    fun `test unset percentage shows 0 percent without a bar`() {
        val (image, label) = paint(workPackage(null))
        assertEquals("0%", label.text)
        assertFalse(isBar(image.getRGB(10, 3)))
    }

    fun `test other columns have no bar`() {
        val (image, label) = paint(workPackage(80), columnId = "subject")
        assertEquals(JLabel.LEADING, label.horizontalAlignment)
        assertFalse(isBar(image.getRGB(10, 3)))
    }
}
