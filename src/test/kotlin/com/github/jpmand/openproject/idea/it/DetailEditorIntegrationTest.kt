package com.github.jpmand.openproject.idea.it

import com.github.jpmand.openproject.idea.api.WorkPackagePatch
import com.github.jpmand.openproject.idea.api.model.WorkPackage
import com.github.jpmand.openproject.idea.core.OpenProjectServers
import com.github.jpmand.openproject.idea.tasks.OpenProjectRepository
import com.github.jpmand.openproject.idea.tasks.OpenProjectRepositoryType
import com.github.jpmand.openproject.idea.ui.detail.ConcurrentChangeException
import com.github.jpmand.openproject.idea.ui.detail.RelationsPanel
import com.github.jpmand.openproject.idea.ui.detail.WorkPackageDetailEditor
import com.github.jpmand.openproject.idea.vfs.OpenProjectFilesManager
import com.intellij.openapi.util.Disposer
import com.intellij.tasks.TaskManager
import com.intellij.tasks.TaskRepositoryType
import com.intellij.tasks.impl.TaskManagerImpl
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream

/** The work package page against a real OpenProject: every edit it offers. */
class DetailEditorIntegrationTest : BasePlatformTestCase() {

    private lateinit var serverId: String
    private lateinit var workPackage: WorkPackage
    private lateinit var editor: WorkPackageDetailEditor

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
        OpenProjectIntegration.newApi().use { api ->
            OpenProjectIntegration.cleanUp(api, PREFIX)
            workPackage = runBlocking { OpenProjectIntegration.createWorkPackage(api, PREFIX + "page") }
        }
        editor = WorkPackageDetailEditor(project, OpenProjectFilesManager.getInstance(project).workPackageFile(serverId, workPackage.id))
        PlatformTestUtil.waitWithEventsDispatching("details", { editor.details() != null }, 60)
    }

    override fun tearDown() {
        try {
            if (::editor.isInitialized) Disposer.dispose(editor)
            if (OpenProjectIntegration.url != null) {
                OpenProjectIntegration.newApi().use { OpenProjectIntegration.cleanUp(it, PREFIX) }
                (TaskManager.getManager(project) as TaskManagerImpl).setRepositories(emptyList<OpenProjectRepository>())
            }
        } finally {
            super.tearDown()
        }
    }

    private val controller get() = editor.controller

    fun `test edit subject description status assignee and priority`() = runBlocking {
        controller.update { it.subject(PREFIX + "renamed") }
        assertEquals(PREFIX + "renamed", controller.details.value!!.workPackage.subject)

        controller.update { it.description("Updated **from the IDE**") }
        assertEquals("Updated **from the IDE**", controller.details.value!!.workPackage.description?.raw)
        assertTrue(controller.renderMarkdown("**x**").contains("<strong>x</strong>"))

        val status = controller.allowedStatuses().first { it.href != controller.details.value!!.workPackage.links.href("status") }
        controller.update { it.link("status", status.href) }
        assertEquals(status.href, controller.details.value!!.workPackage.links.href("status"))

        val me = controller.availableAssignees().first()
        controller.update { it.link("assignee", me.links.href("self")) }
        assertEquals(me.name, controller.details.value!!.workPackage.assigneeTitle)
        controller.update { it.link("assignee", null) }
        assertNull(controller.details.value!!.workPackage.links.href("assignee"))

        val priority = controller.allowedPriorities().first { it.href != controller.details.value!!.workPackage.links.href("priority") }
        controller.update { it.link("priority", priority.href) }
        assertEquals(priority.href, controller.details.value!!.workPackage.links.href("priority"))
    }

    fun `test conflicting change reloads and can be reapplied`() = runBlocking {
        OpenProjectIntegration.newApi().use { api ->
            val fresh = api.workPackage(workPackage.id)
            api.updateWorkPackage(fresh, WorkPackagePatch(fresh.lockVersion).subject(PREFIX + "changed elsewhere"))
        }
        try {
            controller.update { it.subject(PREFIX + "mine") }
            fail("expected a conflict")
        } catch (_: ConcurrentChangeException) {
            assertEquals(PREFIX + "changed elsewhere", controller.details.value!!.workPackage.subject)
        }
        controller.update { it.subject(PREFIX + "mine") }
        assertEquals(PREFIX + "mine", controller.details.value!!.workPackage.subject)
    }

    fun `test comment files and relations`() = runBlocking {
        controller.addComment("Looks good **to me**", internal = false)
        assertTrue(controller.details.value!!.activities.any { it.isComment && it.comment?.raw == "Looks good **to me**" })

        val attachment = controller.upload("notes.txt", "text/plain", "attachment body".toByteArray())
        assertTrue(controller.details.value!!.attachments.any { it.id == attachment.id })
        val out = ByteArrayOutputStream()
        controller.download(controller.details.value!!.attachments.first { it.id == attachment.id }, out)
        assertEquals("attachment body", out.toString(Charsets.UTF_8))
        controller.deleteAttachment(controller.details.value!!.attachments.first { it.id == attachment.id })
        assertFalse(controller.details.value!!.attachments.any { it.id == attachment.id })

        val other = OpenProjectIntegration.newApi().use { OpenProjectIntegration.createWorkPackage(it, PREFIX + "other") }
        val candidate = controller.relationCandidates(PREFIX + "other", "blocks").first { it.id == other.id }
        val relation = controller.addRelation("blocks", candidate, "needed first", null)
        val related = RelationsPanel.related(controller.details.value!!.workPackage, controller.details.value!!.relations)
        assertTrue(related.any { it.relation?.id == relation.id && it.label == RelationsPanel.label("blocks") && it.target.id == other.id.toString() })
        controller.deleteRelation(controller.details.value!!.relations.first { it.id == relation.id })
        assertTrue(controller.details.value!!.relations.none { it.id == relation.id })
    }

    private companion object {
        const val PREFIX = "IT-detail "
    }
}
