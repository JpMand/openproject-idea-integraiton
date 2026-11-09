package com.github.jpmand.openproject.idea.ui.list

import com.github.jpmand.openproject.idea.OpenProjectBundle
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
 * Cells of the work package table: the active task's row in bold with a marker, percentage columns
 * (`percentageDone`, `derivedPercentageDone`) with a progress bar behind the value, and the spent time in red when
 * it is more than the work (possible overtime).
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
    /** The cell being painted is the spent time of a work package with more time spent than planned. */
    private var overtime = false
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
        overtime = item != null && columnId == WorkPackageCells.SPENT_TIME && WorkPackageCells.isOvertime(item)
        horizontalAlignment = if (progress != null) SwingConstants.CENTER else SwingConstants.LEADING
        toolTipText = if (overtime && item != null) {
            OpenProjectBundle.message("list.overtime.tooltip", WorkPackageCells.text(item, WorkPackageCells.SPENT_TIME), WorkPackageCells.text(item, WorkPackageCells.WORK))
        } else {
            null
        }
        // Bars and highlights are painted between the background and the text, so the label must not fill the
        // background itself. (setBackground would change the background of every later cell of this renderer.)
        isOpaque = progress == null && !overtime
        return this
    }

    override fun paintComponent(g: Graphics) {
        if (!isOpaque) {
            g.color = if (overtime && !selected) OVERTIME else background
            g.fillRect(0, 0, width, height)
            val inset = JBUI.scale(2)
            progress?.let { progress ->
                val barWidth = ((width - 2 * inset) * progress.coerceIn(0, 100) / 100.0).roundToInt()
                if (barWidth > 0) {
                    g.color = barColor()
                    g.fillRect(inset, inset, barWidth, height - 2 * inset)
                }
            }
            if (overtime && selected) {
                // The selection background stays; a red edge keeps the warning visible.
                g.color = OVERTIME_EDGE
                g.fillRect(0, 0, JBUI.scale(3), height)
            }
        }
        super.paintComponent(g)
    }

    private fun barColor(): Color = if (selected) ColorUtil.withAlpha(foreground, 0.25) else BAR

    companion object {
        /** OpenProject's green progress bar, toned down to stay readable behind the text. */
        val BAR: Color = JBColor(Color(0xBFE3C4), Color(0x34553A))

        /** Background of a spent time cell with possible overtime. */
        val OVERTIME: Color = JBColor(Color(0xF8C9C9), Color(0x6A2E30))

        /** Left edge of that cell on a selected row. */
        val OVERTIME_EDGE: Color = JBColor(Color(0xE55765), Color(0xDB5C5C))
    }
}
