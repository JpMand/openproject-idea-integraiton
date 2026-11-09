package com.github.jpmand.openproject.idea.ui.list

import com.github.jpmand.openproject.idea.OpenProjectBundle
import com.github.jpmand.openproject.idea.api.model.WorkPackage
import com.github.jpmand.openproject.idea.ui.common.StatusColors
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
 * Cells of the work package table: the active task's row in bold with a marker, the status in its OpenProject
 * color, percentage columns (`percentageDone`, `derivedPercentageDone`) with a progress bar behind the value, and
 * the spent time in red when it is more than the work (possible overtime). On a selected row the selection color
 * stays and a colored left edge marks status and overtime cells.
 */
class WorkPackageCellRenderer(
    /** The work package shown in a view row. */
    private val itemAt: (table: JTable, row: Int) -> WorkPackage?,
    /** The column id of a view column. */
    private val columnAt: (table: JTable, column: Int) -> String?,
    private val isActive: (WorkPackage) -> Boolean,
    /** The color of a work package's status (null: none known). */
    private val statusColor: (WorkPackage) -> Color? = { null },
) : DefaultTableCellRenderer() {

    /** Progress of the cell being painted (0..100), null for other columns. */
    private var progress: Int? = null
    /** Background painted instead of the table's (unselected cells only). */
    private var cellBackground: Color? = null
    /** Left edge painted on a selected cell. */
    private var edge: Color? = null
    private var selected = false

    override fun getTableCellRendererComponent(table: JTable, value: Any?, selected: Boolean, focused: Boolean, row: Int, column: Int): Component {
        // DefaultTableCellRenderer remembers colors set on it for every later cell; start from the table's colors.
        setForeground(null)
        setBackground(null)
        super.getTableCellRendererComponent(table, value, selected, false, row, column)
        val item = itemAt(table, row)
        val columnId = columnAt(table, column)
        val active = item != null && isActive(item)
        this.selected = selected
        font = if (active) font.deriveFont(Font.BOLD) else font.deriveFont(Font.PLAIN)
        icon = if (active && table.convertColumnIndexToModel(column) == 0) AllIcons.Vcs.History else null
        border = JBUI.Borders.empty(0, 6)
        progress = if (item != null && columnId != null && WorkPackageCells.isPercentage(columnId)) WorkPackageCells.percentage(item, columnId) ?: 0 else null
        val overtime = item != null && columnId == WorkPackageCells.SPENT_TIME && WorkPackageCells.isOvertime(item)
        val status = if (item != null && columnId == "status") statusColor(item) else null
        val highlight = status ?: if (overtime) OVERTIME else null
        cellBackground = highlight.takeIf { !selected }
        edge = if (selected) status ?: if (overtime) OVERTIME_EDGE else null else null
        if (status != null && !selected) foreground = StatusColors.textOn(status)
        horizontalAlignment = if (progress != null) SwingConstants.CENTER else SwingConstants.LEADING
        toolTipText = if (overtime && item != null) {
            OpenProjectBundle.message("list.overtime.tooltip", WorkPackageCells.text(item, WorkPackageCells.SPENT_TIME), WorkPackageCells.text(item, WorkPackageCells.WORK))
        } else {
            null
        }
        // Colors, bars and edges are painted between the background and the text, so the label must not fill the
        // background itself.
        isOpaque = progress == null && cellBackground == null && edge == null
        return this
    }

    override fun paintComponent(g: Graphics) {
        if (!isOpaque) {
            g.color = cellBackground ?: background
            g.fillRect(0, 0, width, height)
            val inset = JBUI.scale(2)
            progress?.let { progress ->
                val barWidth = ((width - 2 * inset) * progress.coerceIn(0, 100) / 100.0).roundToInt()
                if (barWidth > 0) {
                    g.color = barColor()
                    g.fillRect(inset, inset, barWidth, height - 2 * inset)
                }
            }
            edge?.let {
                g.color = it
                g.fillRect(0, 0, JBUI.scale(4), height)
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
