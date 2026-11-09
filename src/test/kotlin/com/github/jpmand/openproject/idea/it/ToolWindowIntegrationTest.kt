package com.github.jpmand.openproject.idea.it

import com.github.jpmand.openproject.idea.core.ActiveWorkPackageTracker
import com.github.jpmand.openproject.idea.core.OpenProjectServers
import com.github.jpmand.openproject.idea.core.TimeEntries
import com.github.jpmand.openproject.idea.tasks.OpenProjectRepository
import com.github.jpmand.openproject.idea.tasks.OpenProjectRepositoryType
import com.github.jpmand.openproject.idea.ui.detail.WorkPackageDetailPanel
import com.github.jpmand.openproject.idea.ui.list.WorkPackageListPanel
import com.github.jpmand.openproject.idea.ui.list.WorkPackageListViews
import com.intellij.openapi.util.Disposer
import com.intellij.tasks.TaskManager
import com.intellij.tasks.TaskRepositoryType
import com.intellij.tasks.impl.TaskManagerImpl
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking
import java.time.Duration

/** The tool window panels and shared services against a real OpenProject configured as a Task Server. */
class ToolWindowIntegrationTest : BasePlatformTestCase() {

    private lateinit var serverId: String

    override fun setUp() {
        super.setUp()
        OpenProjectIntegration.assumeConfigured()
        val type = TaskRepositoryType.getRepositoryTypes().filterIsInstance<OpenProjectRepositoryType>().single()
        val repository = (type.createRepository() as OpenProjectRepository).apply {
            url = OpenProjectIntegration.url
            password = OpenProjectIntegration.token
        }
        (TaskManager.getManager(project) as TaskManagerImpl).setRepositories(listOf(repository))
        serverId = OpenProjectServers.getInstance(project).servers().single().id
        // The light project is shared by the test classes; start from a default list.
        WorkPackageListViews.getInstance(project).remove(serverId)
        OpenProjectIntegration.newApi().use { OpenProjectIntegration.cleanUp(it, PREFIX) }
    }

    override fun tearDown() {
        try {
            if (OpenProjectIntegration.url != null) {
                OpenProjectIntegration.newApi().use { OpenProjectIntegration.cleanUp(it, PREFIX) }
                (TaskManager.getManager(project) as TaskManagerImpl).setRepositories(emptyList<OpenProjectRepository>())
            }
        } finally {
            super.tearDown()
        }
    }

    private fun create(subject: String) = OpenProjectIntegration.newApi().use { api ->
        runBlocking { OpenProjectIntegration.createWorkPackage(api, PREFIX + subject) }
    }

    fun `test work package tab loads the work package`() {
        val workPackage = create("detail panel")
        var title = ""
        val panel = WorkPackageDetailPanel(project, serverId, workPackage.id, WorkPackageDetailPanel.Layout.TAB, onTitle = { title = it })
        try {
            PlatformTestUtil.waitWithEventsDispatching("work package did not load", { title.contains("detail panel") }, 60)
            assertEquals("${workPackage.presentableId} ${workPackage.subject}", title)
        } finally {
            Disposer.dispose(panel)
        }
    }

    fun `test list loads open work packages and previews a click`() {
        val workPackage = create("list panel")
        val panel = WorkPackageListPanel(project, serverId, null)
        try {
            PlatformTestUtil.waitWithEventsDispatching("list did not load", { panel.loadedWorkPackages().any { it.id == workPackage.id } }, 60)
            assertNotNull("status color", panel.statusColor(panel.loadedWorkPackages().first { it.id == workPackage.id }))
            assertTrue(panel.columnMinimumWidths().getValue("updatedAt") > panel.columnMinimumWidths().getValue("subject"))
            panel.showPreview(workPackage.id)
            val preview = panel.preview()!!
            assertTrue(preview.isPreview())
            PlatformTestUtil.waitWithEventsDispatching("preview did not load", { preview.details()?.workPackage?.id == workPackage.id }, 60)
            // a row click while the preview is open follows the selection
            panel.select(panel.loadedWorkPackages().first { it.id != workPackage.id }.id)
            assertNotSame(preview, panel.preview())
            panel.closePreview()
            assertNull(panel.preview())
        } finally {
            Disposer.dispose(panel)
        }
    }

    fun `test activating a work package and logging time`() {
        val workPackage = create("active")
        val server = OpenProjectServers.getInstance(project).servers().single()
        ActiveWorkPackageTracker.getInstance(project).activate(server, workPackage)
        assertEquals(workPackage.id, ActiveWorkPackageTracker.getInstance(project).active.value?.workPackageId)

        val connection = server.repository.connection()
        val entry = runBlocking {
            val fresh = connection.api.workPackage(workPackage.id)
            TimeEntries.log(connection.api, fresh, TimeEntries.Draft(Duration.ofMinutes(20), comment = "from the tool window"))
        }
        assertEquals("PT20M", entry.hours)
        TaskManager.getManager(project).activateTask(TaskManager.getManager(project).localTasks.first { it.isDefault }, false)
    }

    private companion object {
        const val PREFIX = "IT-toolwindow "
    }
}
