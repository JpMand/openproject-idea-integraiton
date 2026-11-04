package com.github.jpmand.openprojectideaintegraiton.ui.detail

import com.github.jpmand.openprojectideaintegraiton.OpenProjectBundle
import com.github.jpmand.openprojectideaintegraiton.api.hal.HalLink
import com.github.jpmand.openprojectideaintegraiton.api.model.Relation
import com.github.jpmand.openprojectideaintegraiton.api.model.WorkPackage
import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.Messages
import com.intellij.ui.InplaceButton
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.FlowLayout
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/** One side of a relation as seen from the work package on the page. */
data class RelatedWorkPackage(val label: String, val target: HalLink, val relation: Relation?)

/** Relations grouped by type ("Blocks", "Follows", ...), plus parent and children. */
class RelationsPanel(
    private val onOpen: (Long) -> Unit,
    private val onDelete: (Relation) -> Unit,
    private val onAdd: () -> Unit,
) : JPanel() {

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }

    fun show(workPackage: WorkPackage, relations: List<Relation>, canRelate: Boolean) {
        removeAll()
        val related = related(workPackage, relations)
        if (related.isEmpty()) add(JBLabel(OpenProjectBundle.message("detail.relations.empty")).apply { foreground = UIUtil.getContextHelpForeground() })
        related.groupBy { it.label }.forEach { (label, entries) ->
            add(JBLabel(label).apply {
                font = JBFont.label().asBold()
                border = JBUI.Borders.emptyTop(8)
                alignmentX = 0f
            })
            entries.forEach { add(row(it)) }
        }
        if (canRelate) {
            add(JPanel(FlowLayout(FlowLayout.LEFT, 0, JBUI.scale(8))).apply {
                isOpaque = false
                alignmentX = 0f
                add(ActionLink(OpenProjectBundle.message("detail.relations.add")) { onAdd() }.apply { icon = AllIcons.General.Add })
            })
        }
        revalidate()
        repaint()
    }

    private fun row(entry: RelatedWorkPackage): JComponent = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(1))).apply {
        isOpaque = false
        alignmentX = 0f
        val id = entry.target.id?.toLongOrNull()
        add(ActionLink("#${entry.target.id}  ${entry.target.title.orEmpty()}") { id?.let(onOpen) })
        entry.relation?.description?.takeIf { it.isNotBlank() }?.let { add(JBLabel("— $it").apply { foreground = UIUtil.getContextHelpForeground() }) }
        val relation = entry.relation
        if (relation != null && (relation.links.has("delete") || relation.links.has("self"))) {
            add(InplaceButton(OpenProjectBundle.message("detail.relations.delete"), AllIcons.Actions.Close) {
                val confirmed = Messages.showYesNoDialog(
                    this,
                    OpenProjectBundle.message("detail.relations.delete.confirm", entry.label.lowercase(), "#${entry.target.id}"),
                    OpenProjectBundle.message("detail.relations.delete"),
                    null,
                ) == Messages.YES
                if (confirmed) onDelete(relation)
            })
        }
    }

    companion object {
        /** Relation types and their labels, from the point of view of the work package on the page. */
        val TYPES = listOf("relates", "duplicates", "duplicated", "blocks", "blocked", "precedes", "follows", "includes", "partof", "requires", "required")

        fun label(type: String): String = OpenProjectBundle.messageOrNull("relation.$type") ?: type

        fun related(workPackage: WorkPackage, relations: List<Relation>): List<RelatedWorkPackage> {
            val self = workPackage.links.href("self") ?: "/api/v3/work_packages/${workPackage.id}"
            val fromRelations = relations.mapNotNull { relation ->
                val from = relation.links["from"] ?: return@mapNotNull null
                val to = relation.links["to"] ?: return@mapNotNull null
                if (from.href == self || from.id == workPackage.id.toString()) {
                    RelatedWorkPackage(label(relation.type), to, relation)
                } else {
                    RelatedWorkPackage(label(relation.reverseType ?: relation.type), from, relation)
                }
            }
            val parent = workPackage.links["parent"]?.takeIf { it.href != null }?.let { RelatedWorkPackage(label("parent"), it, null) }
            val children = workPackage.links.list("children").filter { it.href != null }.map { RelatedWorkPackage(label("child"), it, null) }
            return listOfNotNull(parent) + children + fromRelations
        }
    }
}
