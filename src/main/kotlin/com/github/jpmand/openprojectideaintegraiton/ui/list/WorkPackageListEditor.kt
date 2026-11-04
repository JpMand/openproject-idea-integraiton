package com.github.jpmand.openprojectideaintegraiton.ui.list

import com.github.jpmand.openprojectideaintegraiton.OpenProjectBundle
import com.github.jpmand.openprojectideaintegraiton.api.Filters
import com.github.jpmand.openprojectideaintegraiton.api.SortSpec
import com.github.jpmand.openprojectideaintegraiton.api.WorkPackageQuery
import com.github.jpmand.openprojectideaintegraiton.api.model.WorkPackage
import com.github.jpmand.openprojectideaintegraiton.core.OpenProjectServer
import com.github.jpmand.openprojectideaintegraiton.core.connect
import com.github.jpmand.openprojectideaintegraiton.core.describeError
import com.github.jpmand.openprojectideaintegraiton.ui.OpenProjectDataKeys
import com.github.jpmand.openprojectideaintegraiton.ui.OpenProjectFileEditor
import com.github.jpmand.openprojectideaintegraiton.ui.WorkPackageRef
import com.github.jpmand.openprojectideaintegraiton.ui.common.StatusPanel
import com.github.jpmand.openprojectideaintegraiton.vfs.OpenProjectFilesManager
import com.github.jpmand.openprojectideaintegraiton.vfs.WorkPackageListVirtualFile
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.TableSpeedSearch
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.ListTableModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.TestOnly
import java.awt.BorderLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/** The work package table of a server. */
class WorkPackageListEditor(project: Project, private val file: WorkPackageListVirtualFile) : OpenProjectFileEditor(project, file) {

    private class Column(val id: String, title: String) : ColumnInfo<WorkPackage, String>(title) {
        override fun valueOf(item: WorkPackage): String = WorkPackageCells.text(item, id)
    }

    @Volatile
    private var server: OpenProjectServer? = null
    private var loadJob: Job? = null

    private val model = ListTableModel<WorkPackage>(*DEFAULT_COLUMNS.map { (id, key) -> Column(id, OpenProjectBundle.message(key)) }.toTypedArray())
    private val table = TableView(model).apply {
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        setShowGrid(false)
        tableHeader.reorderingAllowed = true
    }
    private val status = StatusPanel()
    private val center = JPanel(BorderLayout())

    private val root: JComponent = UiDataProvider.wrapComponent(JPanel(BorderLayout()).apply {
        add(toolbar(), BorderLayout.NORTH)
        add(center, BorderLayout.CENTER)
    }) { sink ->
        val selected = table.selectedObject
        val server = server
        sink[OpenProjectDataKeys.WORK_PACKAGE] = if (selected != null && server != null) WorkPackageRef(server, selected) else null
        sink[OpenProjectDataKeys.REFRESHER] = Runnable { reload() }
    }

    init {
        TableSpeedSearch.installOn(table)
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean = openSelected()
        }.installOn(table)
        table.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER && openSelected()) e.consume()
            }
        })
        PopupHandler.installPopupMenu(table, "OpenProject.WorkPackagePopup", "OpenProject.WorkPackageList")
        reload()
    }

    fun reload() {
        loadJob?.cancel()
        loadJob = cs.launch {
            withContext(Dispatchers.EDT) { showStatus { status.loading() } }
            try {
                val (server, connection) = connect(project, file.serverId)
                this@WorkPackageListEditor.server = server
                val page = connection.api.workPackages(
                    WorkPackageQuery(
                        filters = listOf(Filters.openStatus()),
                        sortBy = listOf(SortSpec("updatedAt", ascending = false)),
                        pageSize = 100,
                    ),
                )
                withContext(Dispatchers.EDT) {
                    model.items = page.elements
                    if (page.elements.isEmpty()) showStatus { status.empty(OpenProjectBundle.message("list.empty")) } else showTable()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.EDT) { showStatus { status.error(describeError(e)) { reload() } } }
            }
        }
    }

    @TestOnly
    internal fun loadedWorkPackages(): List<WorkPackage> = model.items

    private fun openSelected(): Boolean {
        val selected = table.selectedObject ?: return false
        OpenProjectFilesManager.getInstance(project).openWorkPackage(file.serverId, selected.id)
        return true
    }

    private fun showTable() {
        center.removeAll()
        center.add(ScrollPaneFactory.createScrollPane(table), BorderLayout.CENTER)
        center.revalidate()
        center.repaint()
    }

    private fun showStatus(update: () -> Unit) {
        update()
        if (status.parent != center) {
            center.removeAll()
            center.add(status, BorderLayout.CENTER)
            center.revalidate()
            center.repaint()
        }
    }

    private fun toolbar(): JComponent {
        val group = ActionManager.getInstance().getAction("OpenProject.WorkPackageListToolbar") as ActionGroup
        val toolbar = ActionManager.getInstance().createActionToolbar("OpenProject.WorkPackageList", group, true)
        toolbar.targetComponent = center
        return toolbar.component
    }

    override fun getComponent(): JComponent = root

    override fun getPreferredFocusedComponent(): JComponent = table

    override fun getName(): String = OpenProjectBundle.message("list.editor.name")

    private companion object {
        val DEFAULT_COLUMNS = listOf(
            "id" to "column.id",
            "subject" to "column.subject",
            "type" to "column.type",
            "status" to "column.status",
            "assignee" to "column.assignee",
            "updatedAt" to "column.updatedAt",
        )
    }
}
