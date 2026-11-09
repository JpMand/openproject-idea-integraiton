package com.github.jpmand.openproject.idea.ui.list

import com.github.jpmand.openproject.idea.api.model.WorkPackage
import com.intellij.icons.AllIcons
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.awt.Graphics
import javax.swing.JTable
import javax.swing.SwingConstants
import javax.swing.table.DefaultTableCellRenderer
import kotlin.math.roundToInt

/**
 * Cells of the work package table: the active task's row in bold with a marker, and percentage columns
 * (`percentageDone`, `derivedPercentageDone`) with a progress bar behind the value.
 */
class WorkPackageCellRenderer(
    /** The work package shown in a view row. */
    private val itemAt: (table: JTable, row: Int) -> WorkPackage?,
    /** The column id of a view column. */
    private val columnAt: (table: JTable, column: Int) -> String?,
    private val isActive: (WorkPackage) -> Boolean,
) : DefaultTableCellRenderer() {

    /** Progress of the cell being painted (0..100), null for other columns. */
    private var progress: Int? = null
    private var selected = false

    override fun getTableCellRendererComponent(table: JTable, value: Any?, selected: Boolean, focused: Boolean, row: Int, column: Int): Component {
        super.getTableCellRendererComponent(table, value, selected, false, row, column)
        val item = itemAt(table, row)
        val columnId = columnAt(table, column)
        val active = item != null && isActive(item)
        this.selected = selected
        font = if (active) font.deriveFont(Font.BOLD) else font.deriveFont(Font.PLAIN)
        icon = if (active && table.convertColumnIndexToModel(column) == 0) AllIcons.Vcs.History else null
        border = JBUI.Borders.empty(0, 6)
        progress = if (item != null && columnId != null && WorkPackageCells.isPercentage(columnId)) WorkPackageCells.percentage(item, columnId) ?: 0 else null
        horizontalAlignment = if (progress != null) SwingConstants.CENTER else SwingConstants.LEADING
        // The bar is painted between the background and the text, so the label must not fill the background itself.
        isOpaque = progress == null
        return this
    }

    override fun paintComponent(g: Graphics) {
        val progress = progress
        if (progress != null) {
            g.color = background
            g.fillRect(0, 0, width, height)
            val inset = JBUI.scale(2)
            val barWidth = ((width - 2 * inset) * progress.coerceIn(0, 100) / 100.0).roundToInt()
            if (barWidth > 0) {
                g.color = barColor()
                g.fillRect(inset, inset, barWidth, height - 2 * inset)
            }
        }
        super.paintComponent(g)
    }

    private fun barColor(): Color = if (selected) ColorUtil.withAlpha(foreground, 0.25) else BAR

    companion object {
        /** OpenProject's green progress bar, toned down to stay readable behind the text. */
        val BAR: Color = JBColor(Color(0xBFE3C4), Color(0x34553A))
    }
}
