package com.github.jpmand.openprojectideaintegraiton.it

import com.github.jpmand.openprojectideaintegraiton.tasks.OpenProjectRepository
import com.github.jpmand.openprojectideaintegraiton.tasks.OpenProjectRepositoryType
import com.github.jpmand.openprojectideaintegraiton.tasks.OpenProjectTask
import com.intellij.tasks.TaskRepositoryType
import com.intellij.tasks.impl.LocalTaskImpl
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking

/** The Tasks connector against a real OpenProject (scripts/openproject-it.sh). */
class OpenProjectRepositoryIntegrationTest : BasePlatformTestCase() {

    private lateinit var repository: OpenProjectRepository

    override fun setUp() {
        super.setUp()
        OpenProjectIntegration.assumeConfigured()
        val type = TaskRepositoryType.getRepositoryTypes().filterIsInstance<OpenProjectRepositoryType>().single()
        repository = type.createRepository() as OpenProjectRepository
        repository.url = OpenProjectIntegration.url
        repository.password = OpenProjectIntegration.token
        OpenProjectIntegration.newApi().use { OpenProjectIntegration.cleanUp(it, PREFIX) }
    }

    override fun tearDown() {
        try {
            if (OpenProjectIntegration.url != null) OpenProjectIntegration.newApi().use { OpenProjectIntegration.cleanUp(it, PREFIX) }
        } finally {
            super.tearDown()
        }
    }

    private fun create(subject: String) = OpenProjectIntegration.newApi().use { api ->
        runBlocking { OpenProjectIntegration.createWorkPackage(api, PREFIX + subject) }
    }

    fun `test connection succeeds with the token and fails without`() {
        assertNull(repository.createCancellableConnection().call())
        val wrong = repository.clone().apply { password = "not-a-token" }
        assertNotNull(wrong.createCancellableConnection().call())
    }

    fun `test search open and closed tasks`() {
        val workPackage = create("search me")
        val found = repository.getIssues("search me", 0, 20, false)
        val task = found.filterIsInstance<OpenProjectTask>().single { it.id == workPackage.id.toString() }
        assertEquals(PREFIX + "search me", task.summary)
        assertFalse(task.isClosed)
        assertTrue(task.issueUrl.endsWith("/work_packages/${workPackage.id}"))

        val byId = repository.getIssues("#${workPackage.id}", 0, 20, true)
        assertEquals(listOf(workPackage.id.toString()), byId.map { it.id })

        // close it and check the "include closed" flag
        val closedState = repository.getAvailableTaskStates(task).first { it.presentableName.equals("Closed", ignoreCase = true) }
        repository.setTaskState(task, closedState)
        assertTrue(repository.getIssues("#${workPackage.id}", 0, 20, false).isEmpty())
        val closed = repository.getIssues("#${workPackage.id}", 0, 20, true).single()
        assertTrue(closed.isClosed)
    }

    fun `test assigned to me filter and find task`() {
        val workPackage = create("mine")
        val api = OpenProjectIntegration.newApi()
        api.use {
            runBlocking {
                val me = api.me()
                api.updateWorkPackage(workPackage, com.github.jpmand.openprojectideaintegraiton.api.WorkPackagePatch(workPackage.lockVersion).link("assignee", "/api/v3/users/${me.id}"))
            }
        }
        val other = create("not mine")
        repository.assignedToMeOnly = true
        val ids = repository.getIssues(PREFIX, 0, 50, false).map { it.id }
        assertTrue(workPackage.id.toString() in ids)
        assertFalse(other.id.toString() in ids)

        assertEquals(workPackage.id.toString(), repository.findTask(workPackage.id.toString())?.id)
        assertEquals(workPackage.id.toString(), repository.findTask("#${workPackage.id}")?.id)
        assertNull(repository.findTask("999999999"))
    }

    fun `test paging through results`() {
        repeat(3) { create("paged $it") }
        val first = repository.getIssues(PREFIX + "paged", 0, 2, false)
        val second = repository.getIssues(PREFIX + "paged", 2, 2, false)
        assertEquals(2, first.size)
        assertTrue(second.isNotEmpty())
        assertTrue(first.map { it.id }.intersect(second.map { it.id }.toSet()).isEmpty())
    }

    fun `test time spent is posted as a time entry`() {
        val workPackage = create("time")
        val localTask = LocalTaskImpl(OpenProjectTask(workPackage, repository, OpenProjectIntegration.url!!, false))
        repository.updateTimeSpent(localTask, "1h 15m", "Posted by the Time Tracking plugin")
        val entries = OpenProjectIntegration.newApi().use { api ->
            runBlocking { OpenProjectIntegration.timeEntriesOf(api, workPackage.id) }
        }
        assertTrue(entries.any { it.hours == "PT1H15M" && it.comment?.raw == "Posted by the Time Tracking plugin" })
    }

    private companion object {
        const val PREFIX = "IT-tasks "
    }
}
