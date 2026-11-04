package com.github.jpmand.openprojectideaintegraiton.ui.detail

import com.github.jpmand.openprojectideaintegraiton.OpenProjectBundle
import com.github.jpmand.openprojectideaintegraiton.api.model.WorkPackage
import com.github.jpmand.openprojectideaintegraiton.core.ActiveWorkPackageTracker
import com.github.jpmand.openprojectideaintegraiton.core.OpenProjectConnection
import com.github.jpmand.openprojectideaintegraiton.core.connect
import com.github.jpmand.openprojectideaintegraiton.core.describeError
import com.github.jpmand.openprojectideaintegraiton.ui.OpenProjectDataKeys
import com.github.jpmand.openprojectideaintegraiton.ui.OpenProjectFileEditor
import com.github.jpmand.openprojectideaintegraiton.ui.WorkPackageRef
import com.github.jpmand.openprojectideaintegraiton.ui.common.HtmlView
import com.github.jpmand.openprojectideaintegraiton.ui.common.StatusPanel
import com.github.jpmand.openprojectideaintegraiton.ui.list.WorkPackageCells
import com.github.jpmand.openprojectideaintegraiton.vfs.OpenProjectFilesManager
import com.github.jpmand.openprojectideaintegraiton.vfs.WorkPackageVirtualFile
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/** The page of one work package: header, attributes and description. */
class WorkPackageDetailEditor(project: Project, private val file: WorkPackageVirtualFile) : OpenProjectFileEditor(project, file) {

    @Volatile
    private var ref: WorkPackageRef? = null
    private var connection: OpenProjectConnection? = null
    private var loadJob: Job? = null

    private val status = StatusPanel()
    private val content = JPanel(BorderLayout())
    private val activeBadge = JBLabel(OpenProjectBundle.message("detail.active.task")).apply {
        foreground = JBUI.CurrentTheme.Link.Foreground.ENABLED
        isVisible = false
    }
    private val root: JComponent = UiDataProvider.wrapComponent(JPanel(BorderLayout()).apply {
        add(toolbar(), BorderLayout.NORTH)
        add(content, BorderLayout.CENTER)
    }) { sink ->
        sink[OpenProjectDataKeys.WORK_PACKAGE] = ref
        sink[OpenProjectDataKeys.REFRESHER] = Runnable { reload() }
    }

    init {
        content.add(status, BorderLayout.CENTER)
        reload()
        cs.launch {
            ActiveWorkPackageTracker.getInstance(project).active.collect { active ->
                withContext(Dispatchers.EDT) {
                    activeBadge.isVisible = active?.serverId == file.serverId && active.workPackageId == file.workPackageId
                }
            }
        }
    }

    fun reload() {
        loadJob?.cancel()
        loadJob = cs.launch {
            if (ref == null) withContext(Dispatchers.EDT) { status.loading() }
            try {
                val (server, connection) = connect(project, file.serverId)
                val workPackage = connection.api.workPackage(file.workPackageId)
                this@WorkPackageDetailEditor.connection = connection
                ref = WorkPackageRef(server, workPackage)
                withContext(Dispatchers.EDT) { show(workPackage, connection.baseUrl) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.EDT) {
                    content.removeAll()
                    content.add(status, BorderLayout.CENTER)
                    status.error(describeError(e)) { reload() }
                    content.revalidate()
                }
            }
        }
    }

    private fun show(workPackage: WorkPackage, baseUrl: String) {
        file.updateTitle("${workPackage.presentableId} ${workPackage.subject}")
        val page = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(12, 16)
            isOpaque = false
            add(JBLabel(workPackage.presentableId).apply { foreground = UIUtil.getContextHelpForeground() })
            add(JBLabel(workPackage.subject).apply { font = JBFont.h2().asBold() })
            add(JBLabel(attributeLine(workPackage)).apply { border = JBUI.Borders.emptyTop(4) })
            add(activeBadge)
            add(JBLabel(OpenProjectBundle.message("detail.description")).apply {
                font = JBFont.h4().asBold()
                border = JBUI.Borders.emptyTop(16)
            })
            add(HtmlView({ baseUrl }) { id -> OpenProjectFilesManager.getInstance(project).openWorkPackage(file.serverId, id) }.apply {
                setHtml(workPackage.description?.html)
                alignmentX = 0f
            })
        }
        page.components.forEach { (it as? JComponent)?.alignmentX = 0f }
        content.removeAll()
        content.add(JBScrollPane(page).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
        content.revalidate()
        content.repaint()
    }

    private fun attributeLine(workPackage: WorkPackage): String =
        listOf("type", "status", "assignee", "priority", "project", "updatedAt")
            .mapNotNull { column -> WorkPackageCells.text(workPackage, column).takeIf { it.isNotBlank() } }
            .joinToString("  ·  ")

    private fun toolbar(): JComponent {
        val group = ActionManager.getInstance().getAction("OpenProject.WorkPackageToolbar") as ActionGroup
        val toolbar = ActionManager.getInstance().createActionToolbar("OpenProject.WorkPackage", group, true)
        toolbar.targetComponent = content
        return toolbar.component
    }

    override fun getComponent(): JComponent = root

    override fun getPreferredFocusedComponent(): JComponent = content

    override fun getName(): String = OpenProjectBundle.message("detail.editor.name")
}
