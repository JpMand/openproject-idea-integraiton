package com.github.jpmand.openprojectideaintegraiton.it

import com.github.jpmand.openprojectideaintegraiton.core.ActiveWorkPackageTracker
import com.github.jpmand.openprojectideaintegraiton.core.OpenProjectServers
import com.github.jpmand.openprojectideaintegraiton.core.TimeEntries
import com.github.jpmand.openprojectideaintegraiton.tasks.OpenProjectRepository
import com.github.jpmand.openprojectideaintegraiton.tasks.OpenProjectRepositoryType
import com.github.jpmand.openprojectideaintegraiton.ui.detail.WorkPackageDetailEditor
import com.github.jpmand.openprojectideaintegraiton.ui.list.WorkPackageListEditor
import com.github.jpmand.openprojectideaintegraiton.vfs.OpenProjectFilesManager
import com.intellij.openapi.util.Disposer
import com.intellij.tasks.TaskManager
import com.intellij.tasks.TaskRepositoryType
import com.intellij.tasks.impl.TaskManagerImpl
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking
import java.time.Duration

/** The editors and shared services against a real OpenProject configured as a Task Server. */
class EditorsIntegrationTest : BasePlatformTestCase() {

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

    fun `test detail editor loads the work package`() {
        val workPackage = create("detail editor")
        val file = OpenProjectFilesManager.getInstance(project).workPackageFile(serverId, workPackage.id)
        val editor = WorkPackageDetailEditor(project, file)
        try {
            PlatformTestUtil.waitWithEventsDispatching("work package did not load", { file.presentableName.contains("detail editor") }, 60)
            assertEquals("${workPackage.presentableId} ${workPackage.subject}", file.presentableName)
        } finally {
            Disposer.dispose(editor)
        }
    }

    fun `test list editor loads open work packages`() {
        val workPackage = create("list editor")
        val editor = WorkPackageListEditor(project, OpenProjectFilesManager.getInstance(project).listFile(serverId))
        try {
            PlatformTestUtil.waitWithEventsDispatching("list did not load", { editor.loadedWorkPackages().isNotEmpty() }, 60)
            assertTrue(editor.loadedWorkPackages().any { it.id == workPackage.id })
        } finally {
            Disposer.dispose(editor)
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
            TimeEntries.log(connection.api, fresh, TimeEntries.Draft(Duration.ofMinutes(20), comment = "from the editor"))
        }
        assertEquals("PT20M", entry.hours)
        TaskManager.getManager(project).activateTask(TaskManager.getManager(project).localTasks.first { it.isDefault }, false)
    }

    private companion object {
        const val PREFIX = "IT-editors "
    }
}
