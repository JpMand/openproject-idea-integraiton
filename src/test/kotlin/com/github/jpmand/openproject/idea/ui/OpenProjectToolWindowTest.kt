package com.github.jpmand.openproject.idea.ui

import com.github.jpmand.openproject.idea.api.Filters
import com.github.jpmand.openproject.idea.api.Fixtures
import com.github.jpmand.openproject.idea.api.OpenProjectApi
import com.github.jpmand.openproject.idea.core.ActiveWorkPackageTracker
import com.github.jpmand.openproject.idea.core.OpenProjectServers
import com.github.jpmand.openproject.idea.tasks.OpenProjectRepository
import com.github.jpmand.openproject.idea.tasks.OpenProjectRepositoryType
import com.github.jpmand.openproject.idea.ui.detail.WorkPackageDetailPanel
import com.github.jpmand.openproject.idea.ui.list.ListViewState
import com.github.jpmand.openproject.idea.ui.list.WorkPackageListPanel
import com.github.jpmand.openproject.idea.ui.list.WorkPackageListViews
import com.github.jpmand.openproject.idea.ui.toolwindow.OpenProjectToolWindowFactory
import com.github.jpmand.openproject.idea.ui.toolwindow.OpenProjectToolWindowManager
import com.github.jpmand.openproject.idea.ui.toolwindow.OpenProjectToolWindowManager.Companion.LIST
import com.github.jpmand.openproject.idea.ui.toolwindow.OpenProjectToolWindowManager.Companion.TOOL_WINDOW_ID
import com.github.jpmand.openproject.idea.ui.toolwindow.OpenProjectToolWindowManager.Companion.WORK_PACKAGE
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.tasks.TaskManager
import com.intellij.tasks.TaskRepositoryType
import com.intellij.tasks.impl.TaskManagerImpl
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.xmlb.XmlSerializer

class OpenProjectToolWindowTest : BasePlatformTestCase() {

    private fun configureServer(url: String = "https://op.example.com", projectId: Long = 0): OpenProjectRepository {
        val type = TaskRepositoryType.getRepositoryTypes().filterIsInstance<OpenProjectRepositoryType>().single()
        val repository = (type.createRepository() as OpenProjectRepository).apply {
            this.url = url
            password = "token"
            this.projectId = projectId
        }
        (TaskManager.getManager(project) as TaskManagerImpl).setRepositories(listOf(repository))
        return repository
    }

    private fun toolWindow(): ToolWindow {
        val manager = ToolWindowManager.getInstance(project)
        val toolWindow = manager.getToolWindow(TOOL_WINDOW_ID) ?: manager.registerToolWindow(TOOL_WINDOW_ID) {
            anchor = ToolWindowAnchor.BOTTOM
            canCloseContent = true
        }
        if (toolWindow.contentManager.contentCount == 0) OpenProjectToolWindowFactory().createToolWindowContent(project, toolWindow)
        return toolWindow
    }

    override fun tearDown() {
        try {
            ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.contentManager?.removeAllContents(true)
            (TaskManager.getManager(project) as TaskManagerImpl).setRepositories(emptyList<OpenProjectRepository>())
        } finally {
            super.tearDown()
        }
    }

    fun `test task servers are the plugin's servers`() {
        configureServer("https://op.example.com/")
        val servers = OpenProjectServers.getInstance(project).servers()
        assertEquals(listOf("https://op.example.com"), servers.map { it.id })
        assertEquals("op.example.com", servers.single().displayName)
        assertNull(OpenProjectServers.getInstance(project).find("https://other.example.com"))
        assertEquals(servers.single(), OpenProjectServers.getInstance(project).find(null))
    }

    fun `test panels without a configured server show an error instead of failing`() {
        val list = WorkPackageListPanel(project, null, null)
        val detail = WorkPackageDetailPanel(project, "https://op.example.com", 3, WorkPackageDetailPanel.Layout.TAB)
        try {
            assertNull(list.serverId)
            assertNull(list.preview())
            assertFalse(detail.isPreview())
        } finally {
            Disposer.dispose(list)
            Disposer.dispose(detail)
        }
    }

    fun `test the main list is the first tab and work packages open once`() {
        configureServer()
        val toolWindow = toolWindow()
        val manager = OpenProjectToolWindowManager.getInstance(project)
        assertEquals(listOf(LIST), manager.tabs().map { it.kind })
        assertTrue(manager.tabs().single().main)
        assertFalse(toolWindow.contentManager.getContent(0)!!.isCloseable)

        manager.openWorkPackage("https://op.example.com", 42)
        manager.openWorkPackage("https://op.example.com", 7)
        manager.openWorkPackage("https://op.example.com", 42)
        assertEquals(listOf(LIST, WORK_PACKAGE, WORK_PACKAGE), manager.tabs().map { it.kind })
        assertEquals(listOf(0L, 42L, 7L), manager.tabs().map { it.workPackageId })
        val selected = manager.selectedPanel()
        assertInstanceOf(selected, WorkPackageDetailPanel::class.java)
        assertEquals(42L, (selected as WorkPackageDetailPanel).workPackageId)
        assertFalse(selected.isPreview())
    }

    fun `test lists open in new tabs with their own view`() {
        configureServer(projectId = 5)
        val toolWindow = toolWindow()
        val manager = OpenProjectToolWindowManager.getInstance(project)
        val views = WorkPackageListViews.getInstance(project)

        // "+": a default list of the Task Server's project
        manager.newListTab(null)
        val added = manager.tabs().last()
        assertEquals(LIST, added.kind)
        assertEquals(listOf(Filters.project(5), Filters.openStatus()), views.get(added.viewKey!!).filters)
        assertInstanceOf(manager.selectedPanel(), WorkPackageListPanel::class.java)

        // "Open as New Tab": the given view
        val view = ListViewState(search = "login", filters = listOf(Filters.closedStatus()))
        manager.openList("https://op.example.com", view, "login")
        val copy = manager.tabs().last()
        assertEquals(view, views.get(copy.viewKey!!))
        assertEquals("login", toolWindow.contentManager.selectedContent!!.displayName)

        // closing a list forgets its view
        toolWindow.contentManager.removeContent(toolWindow.contentManager.selectedContent!!, true)
        assertFalse(views.contains(copy.viewKey!!))
        assertTrue(views.contains(added.viewKey!!))
    }

    fun `test open tabs are remembered`() {
        configureServer()
        toolWindow()
        val manager = OpenProjectToolWindowManager.getInstance(project)
        manager.openWorkPackage("https://op.example.com", 42)
        manager.openList("https://op.example.com", ListViewState(search = "x"), "x")

        val element = XmlSerializer.serialize(manager.state)
        val restored = XmlSerializer.deserialize(element, OpenProjectToolWindowManager.TabsState::class.java)
        assertEquals(listOf(WORK_PACKAGE, LIST), restored.tabs.map { it.kind })
        assertEquals(42L, restored.tabs[0].workPackageId)
        assertEquals("https://op.example.com", restored.tabs[1].serverId)
        assertEquals(2, restored.selected)
    }

    fun `test new lists filter by the Task Server's project`() {
        configureServer(projectId = 9)
        val list = WorkPackageListPanel(project, null, null)
        try {
            assertEquals("https://op.example.com", list.serverId)
            assertEquals(listOf(Filters.project(9), Filters.openStatus()), list.viewState().filters)
            // project, type, status, priority, assignee ("#9" until the project names are loaded)
            PlatformTestUtil.waitWithEventsDispatching("quick filters", { list.quickFilterTexts()[2] == "Open" }, 10)
            assertEquals(listOf("#9", "Any", "Open", "Any", "Anyone"), list.quickFilterTexts())
        } finally {
            Disposer.dispose(list)
        }
    }

    fun `test active task tracking follows task activation`() {
        val repository = configureServer()
        val server = OpenProjectServers.getInstance(project).servers().single()
        val tracker = ActiveWorkPackageTracker.getInstance(project)
        val workPackage = OpenProjectApi.decodeWorkPackage(Fixtures.json("work_package.json"))

        tracker.activate(server, workPackage)
        val active = tracker.active.value
        assertNotNull(active)
        assertEquals(workPackage.id, active!!.workPackageId)
        assertEquals("https://op.example.com", active.serverId)
        assertTrue(tracker.isActive(server.id, workPackage.id))
        assertSame(repository, server.repository)

        val widget = ActiveWorkPackageWidget(project)
        try {
            assertEquals("OP AGILE-1", widget.getText())
            assertTrue(widget.getTooltipText()!!.contains(workPackage.subject))
        } finally {
            Disposer.dispose(widget)
        }

        TaskManager.getManager(project).activateTask(TaskManager.getManager(project).localTasks.first { it.isDefault }, false)
        assertNull(tracker.active.value)
        assertEquals("", ActiveWorkPackageWidget(project).also { Disposer.dispose(it) }.getText())
    }
}
