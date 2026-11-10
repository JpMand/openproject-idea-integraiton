package com.github.jpmand.openproject.idea.ui.common

import com.github.jpmand.openproject.idea.OpenProjectBundle
import com.github.jpmand.openproject.idea.core.OpenProjectServers
import com.github.jpmand.openproject.idea.core.describeError
import com.github.jpmand.openproject.idea.core.needsServerSettings
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JPanel

/** Centered loading / empty / error message with an optional action link. */
class StatusPanel : JPanel(GridBagLayout()) {
    private val label = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val link = ActionLink("") {}

    init {
        isOpaque = false
        val constraints = GridBagConstraints().apply { gridx = 0; gridy = 0 }
        add(label, constraints)
        constraints.gridy = 1
        constraints.insets = JBUI.insetsTop(8)
        add(link, constraints)
    }

    fun loading() = show(OpenProjectBundle.message("status.loading"), null, null).also { label.icon = AnimatedIcon.Default.INSTANCE }

    fun empty(message: String) = show(message, null, null)

    fun error(message: String, retry: () -> Unit) = show(message, OpenProjectBundle.message("status.retry"), retry)

    /** Shows [error]; configuration problems link to the Task Server settings instead of offering a retry. */
    fun error(project: Project, error: Throwable, retry: () -> Unit) {
        if (needsServerSettings(error)) {
            show(describeError(error), OpenProjectBundle.message("error.configure.server")) { OpenProjectServers.getInstance(project).openServerSettings() }
        } else {
            error(describeError(error), retry)
        }
    }

    fun show(message: String, actionText: String?, action: (() -> Unit)?) {
        label.icon = null
        val escaped = StringUtil.escapeXmlEntities(message).replace("\n", "<br>")
        label.text = "<html><div style='text-align:center'>$escaped</div></html>"
        link.isVisible = actionText != null
        link.text = actionText.orEmpty()
        link.actionListeners.forEach(link::removeActionListener)
        if (action != null) link.addActionListener { action() }
        revalidate()
        repaint()
    }
}
