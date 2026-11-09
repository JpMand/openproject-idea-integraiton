package com.github.jpmand.openproject.idea.ui.detail

import com.github.jpmand.openproject.idea.OpenProjectBundle
import com.github.jpmand.openproject.idea.ui.list.Labels
import com.github.jpmand.openproject.idea.ui.list.WorkPackageCells
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JComponent
import javax.swing.JPanel

/** The attributes of a work package (incl. custom fields) as label/value pairs; assignee and priority can be changed. */
class PropertiesPanel(
    private val onChangeAssignee: (JComponent) -> Unit,
    private val onChangePriority: (JComponent) -> Unit,
) : JPanel(GridBagLayout()) {

    init {
        border = JBUI.Borders.empty(12)
    }

    fun show(details: WorkPackageDetails) {
        removeAll()
        val workPackage = details.workPackage
        val schemaFields = details.schema?.fields.orEmpty()
        val attributes = (PREFERRED + schemaFields.keys.filter { it.startsWith("customField") })
            .filter { it !in EXCLUDED }
            .distinct()
        val constraints = GridBagConstraints().apply {
            gridy = 0
            anchor = GridBagConstraints.NORTHWEST
            insets = JBUI.insets(2, 0, 2, 12)
        }
        add(JBLabel(OpenProjectBundle.message("detail.properties")).apply { font = JBFont.h4().asBold() }, constraints.apply { gridx = 0; gridwidth = 2 })
        constraints.gridwidth = 1
        attributes.forEach { attribute ->
            val value = WorkPackageCells.text(workPackage, attribute)
            val editable = details.canEdit && (attribute == "assignee" || attribute == "priority")
            if (value.isBlank() && !editable) return@forEach
            constraints.gridy++
            val name = schemaFields[attribute]?.name ?: Labels.humanize(attribute)
            add(JBLabel(name).apply { foreground = UIUtil.getContextHelpForeground() }, constraints.apply { gridx = 0; weightx = 0.0 })
            val valueComponent: JComponent = if (editable) {
                val text = value.ifBlank { OpenProjectBundle.message("detail.unassigned") }
                ActionLink(text) { e ->
                    val anchor = e.source as JComponent
                    if (attribute == "assignee") onChangeAssignee(anchor) else onChangePriority(anchor)
                }.apply { setDropDownLinkIcon() }
            } else {
                JBLabel("<html>${StringUtil.escapeXmlEntities(value)}</html>")
            }
            add(valueComponent, constraints.apply { gridx = 1; weightx = 1.0 })
        }
        // Takes the remaining height only; the extra width belongs to the value column.
        constraints.gridy++
        add(JPanel().apply { isOpaque = false }, constraints.apply { gridx = 0; gridwidth = 2; weightx = 0.0; weighty = 1.0 })
        revalidate()
        repaint()
    }

    private companion object {
        val PREFERRED = listOf(
            "type", "status", "priority", "assignee", "responsible", "project", "version", "category", "parent",
            "startDate", "dueDate", "date", "duration", "estimatedTime", "remainingTime", "spentTime", "percentageDone",
            "storyPoints", "author", "createdAt", "updatedAt",
        )
        val EXCLUDED = setOf("id", "subject", "description", "lockVersion", "attachments")
    }
}
