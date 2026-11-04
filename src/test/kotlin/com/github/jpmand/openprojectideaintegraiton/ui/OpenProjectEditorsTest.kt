package com.github.jpmand.openprojectideaintegraiton.ui

import com.github.jpmand.openprojectideaintegraiton.api.Fixtures
import com.github.jpmand.openprojectideaintegraiton.api.OpenProjectApi
import com.github.jpmand.openprojectideaintegraiton.core.ActiveWorkPackageTracker
import com.github.jpmand.openprojectideaintegraiton.core.OpenProjectServers
import com.github.jpmand.openprojectideaintegraiton.tasks.OpenProjectRepository
import com.github.jpmand.openprojectideaintegraiton.tasks.OpenProjectRepositoryType
import com.github.jpmand.openprojectideaintegraiton.ui.detail.WorkPackageDetailEditor
import com.github.jpmand.openprojectideaintegraiton.ui.list.WorkPackageListEditor
import com.github.jpmand.openprojectideaintegraiton.vfs.OpenProjectFilesManager
import com.github.jpmand.openprojectideaintegraiton.vfs.OpenProjectVirtualFileSystem
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.tasks.TaskManager
import com.intellij.tasks.TaskRepositoryType
import com.intellij.tasks.impl.TaskManagerImpl
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class OpenProjectEditorsTest : BasePlatformTestCase() {

    private fun configureServer(url: String = "https://op.example.com"): OpenProjectRepository {
        val type = TaskRepositoryType.getRepositoryTypes().filterIsInstance<OpenProjectRepositoryType>().single()
        val repository = (type.createRepository() as OpenProjectRepository).apply { this.url = url; password = "token" }
        (TaskManager.getManager(project) as TaskManagerImpl).setRepositories(listOf(repository))
        return repository
    }

    override fun tearDown() {
        try {
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

    fun `test editor files round-trip through the openproject file system`() {
        val files = OpenProjectFilesManager.getInstance(project)
        val list = files.listFile("https://op.example.com")
        val workPackage = files.workPackageFile("https://op.example.com", 42)
        assertSame(workPackage, files.workPackageFile("https://op.example.com", 42))
        assertEquals("#42", workPackage.presentableName)

        val fileSystem = VirtualFileManager.getInstance().getFileSystem(OpenProjectVirtualFileSystem.PROTOCOL)
        assertInstanceOf(fileSystem, OpenProjectVirtualFileSystem::class.java)
        assertSame(workPackage, fileSystem.findFileByPath(workPackage.path))
        assertSame(list, fileSystem.findFileByPath(list.path))
        assertTrue(workPackage.url.startsWith("openproject://"))
    }

    fun `test editor providers accept only their files`() {
        val files = OpenProjectFilesManager.getInstance(project)
        val list = files.listFile("https://op.example.com")
        val workPackage = files.workPackageFile("https://op.example.com", 7)
        assertTrue(WorkPackageListEditorProvider().accept(project, list))
        assertFalse(WorkPackageListEditorProvider().accept(project, workPackage))
        assertTrue(WorkPackageDetailEditorProvider().accept(project, workPackage))
        assertFalse(WorkPackageDetailEditorProvider().accept(project, myFixture.configureByText("a.txt", "x").virtualFile))
    }

    fun `test editors without a configured server show an error instead of failing`() {
        val files = OpenProjectFilesManager.getInstance(project)
        val listEditor = WorkPackageListEditor(project, files.listFile("https://op.example.com"))
        val detailEditor = WorkPackageDetailEditor(project, files.workPackageFile("https://op.example.com", 3))
        try {
            assertNotNull(listEditor.component)
            assertNotNull(detailEditor.component)
        } finally {
            Disposer.dispose(listEditor)
            Disposer.dispose(detailEditor)
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
