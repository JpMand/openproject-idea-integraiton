package com.github.jpmand.openproject.idea.ui.toolwindow

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory

/** The OpenProject tool window (bottom): work package lists and work package tabs. */
// No compatibility bridges: they would override ToolWindowFactory's deprecated and experimental defaults.
@JvmDefaultWithoutCompatibility
class OpenProjectToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        OpenProjectToolWindowManager.getInstance(project).install(toolWindow)
    }
}
