package com.github.jpmand.openprojectideaintegraiton.ui.detail

import com.github.jpmand.openprojectideaintegraiton.OpenProjectBundle
import com.github.jpmand.openprojectideaintegraiton.api.model.Activity
import com.github.jpmand.openprojectideaintegraiton.ui.common.HtmlView
import com.github.jpmand.openprojectideaintegraiton.ui.common.MarkdownField
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.text.DateFormatUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.time.Instant
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/** The activity of a work package (changes and comments, oldest first) with a comment box at the bottom. */
class ActivityPanel(
    private val project: Project,
    private val html: () -> HtmlView,
    private val onComment: (text: String, internal: Boolean, done: (Boolean) -> Unit) -> Unit,
) : JPanel(BorderLayout()) {

    private val items = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }
    private val composer = MarkdownField(project, "", 4)
    private val internalBox = JBCheckBox(OpenProjectBundle.message("detail.comment.internal")).apply { isVisible = false }
    private val submit = JButton(OpenProjectBundle.message("detail.comment.submit"))

    init {
        isOpaque = false
        add(items, BorderLayout.CENTER)
        add(JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(12)
            add(JBLabel(OpenProjectBundle.message("detail.comment.title")).apply { border = JBUI.Borders.emptyBottom(4) }, BorderLayout.NORTH)
            add(composer, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0)).apply {
                isOpaque = false
                add(internalBox)
                add(submit)
            }, BorderLayout.SOUTH)
        }, BorderLayout.SOUTH)
        submit.addActionListener {
            val text = composer.text.trim()
            if (text.isEmpty()) return@addActionListener
            submit.isEnabled = false
            onComment(text, internalBox.isSelected) { success ->
                submit.isEnabled = true
                if (success) {
                    composer.text = ""
                    internalBox.isSelected = false
                }
            }
        }
    }

    fun show(activities: List<Activity>, userNames: Map<String, String>, canComment: Boolean, internalCommentsAvailable: Boolean) {
        items.removeAll()
        if (activities.isEmpty()) items.add(JBLabel(OpenProjectBundle.message("detail.activity.empty")).apply { foreground = UIUtil.getContextHelpForeground() })
        activities.forEach { items.add(item(it, userNames)) }
        composer.isEnabled = canComment
        submit.isEnabled = canComment
        internalBox.isVisible = canComment && internalCommentsAvailable
        items.revalidate()
        items.repaint()
    }

    private fun item(activity: Activity, userNames: Map<String, String>): JComponent {
        val panel = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.compound(JBUI.Borders.customLineTop(JBColor.border()), JBUI.Borders.empty(8, 0))
            alignmentX = 0f
        }
        val user = activity.links.href("user")?.let(userNames::get) ?: activity.links.title("user") ?: OpenProjectBundle.message("detail.activity.someone")
        val time = activity.createdAt?.let { runCatching { DateFormatUtil.formatPrettyDateTime(Instant.parse(it).toEpochMilli()) }.getOrNull() }.orEmpty()
        val header = buildString {
            append("<b>").append(StringUtil.escapeXmlEntities(user)).append("</b>")
            if (time.isNotEmpty()) append("&nbsp;&nbsp;<span>").append(time).append("</span>")
            if (activity.internal == true) append("&nbsp;&nbsp;").append(OpenProjectBundle.message("detail.comment.internal.marker"))
        }
        panel.add(JBLabel("<html>$header</html>"), BorderLayout.NORTH)
        val body = buildString {
            activity.comment?.html?.takeIf { activity.isComment }?.let(::append)
            if (activity.details.isNotEmpty()) {
                append("<ul>")
                activity.details.forEach { detail -> append("<li>").append(detail.html ?: detail.raw.orEmpty()).append("</li>") }
                append("</ul>")
            }
        }
        if (body.isNotEmpty()) panel.add(html().apply { setHtml(body) }, BorderLayout.CENTER)
        return panel
    }
}
