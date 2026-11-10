package com.github.jpmand.openproject.idea.ui.list

import com.github.jpmand.openproject.idea.api.model.WorkPackage
import com.intellij.ui.table.TableView
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import kotlinx.serialization.json.JsonPrimitive
import java.awt.FontMetrics
import javax.swing.JViewport

/** What a column holds, for its minimum width. */
enum class ColumnKind { ID, PERCENT, DURATION, DATE, DATE_TIME, NUMBER, TEXT }

/** Minimum column widths that keep typical values readable (`100%`, `999h 59m`, dates). */
object ColumnWidths {
    private val PERCENT = setOf("percentageDone", "derivedPercentageDone")
    private val DURATION = setOf("estimatedTime", "remainingTime", "spentTime", "derivedEstimatedTime", "derivedRemainingTime", "duration")
    private val DATE = setOf("startDate", "dueDate", "date", "derivedStartDate", "derivedDueDate")
    private val DATE_TIME = setOf("createdAt", "updatedAt")

    /** The kind of [column]; [schemaType] is its type in the work package schema (custom fields). */
    fun kind(column: String, schemaType: String?): ColumnKind = when {
        column == "id" -> ColumnKind.ID
        column in PERCENT -> ColumnKind.PERCENT
        column in DURATION -> ColumnKind.DURATION
        column in DATE -> ColumnKind.DATE
        column in DATE_TIME -> ColumnKind.DATE_TIME
        else -> when (schemaType) {
            "Duration" -> ColumnKind.DURATION
            "Date" -> ColumnKind.DATE
            "DateTime" -> ColumnKind.DATE_TIME
            "Integer", "Float" -> ColumnKind.NUMBER
            else -> ColumnKind.TEXT
        }
    }

    /** Widest typical value of a kind, as the table shows it. */
    fun sample(kind: ColumnKind): String = when (kind) {
        ColumnKind.ID -> "ABCD-9999"
        ColumnKind.PERCENT -> "100%"
        ColumnKind.DURATION -> "999h 59m"
        ColumnKind.DATE -> WorkPackageCells.format(JsonPrimitive("2026-12-31"))
        ColumnKind.DATE_TIME -> WorkPackageCells.format(JsonPrimitive("2026-12-31T22:59:00Z"))
        ColumnKind.NUMBER -> "99999"
        ColumnKind.TEXT -> ""
    }

    fun minWidth(kind: ColumnKind, metrics: FontMetrics): Int {
        val text = sample(kind)
        // cell padding (renderer border) plus room for the progress bar inset and the header's sort arrow
        return if (text.isEmpty()) JBUI.scale(48) else metrics.stringWidth(text) + JBUI.scale(28)
    }
}

/**
 * The work package table. Columns keep their widths: while they fit, the table fills the visible width; with more
 * columns than fit, it scrolls horizontally instead of squeezing them.
 */
class WorkPackageTable(model: ListTableModel<WorkPackage>) : TableView<WorkPackage>(model) {
    override fun getScrollableTracksViewportWidth(): Boolean {
        val viewport = parent as? JViewport ?: return super.getScrollableTracksViewportWidth()
        return preferredSize.width < viewport.width
    }
}
