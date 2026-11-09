package com.github.jpmand.openproject.idea.it

import com.github.jpmand.openproject.idea.api.FilterSpec
import com.github.jpmand.openproject.idea.api.Filters
import com.github.jpmand.openproject.idea.api.SortSpec
import com.github.jpmand.openproject.idea.api.WorkPackagePatch
import com.github.jpmand.openproject.idea.api.model.WorkPackage
import com.github.jpmand.openproject.idea.core.OpenProjectServers
import com.github.jpmand.openproject.idea.tasks.OpenProjectRepository
import com.github.jpmand.openproject.idea.tasks.OpenProjectRepositoryType
import com.github.jpmand.openproject.idea.ui.list.ListQueries
import com.github.jpmand.openproject.idea.ui.list.ListViewState
import com.github.jpmand.openproject.idea.ui.list.StatusMode
import com.github.jpmand.openproject.idea.ui.list.WorkPackageListPanel
import com.github.jpmand.openproject.idea.ui.list.WorkPackageListViews
import com.intellij.openapi.util.Disposer
import com.intellij.tasks.TaskManager
import com.intellij.tasks.TaskRepositoryType
import com.intellij.tasks.impl.TaskManagerImpl
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking

/** The work package table against a real OpenProject: search, filters, sorting, paging and columns. */
class ListPanelIntegrationTest : BasePlatformTestCase() {

    private lateinit var serverId: String
    private lateinit var editor: WorkPackageListPanel
    private val created = mutableListOf<WorkPackage>()

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
            runBlocking {
                val me = api.me()
                repeat(3) { index ->
                    val workPackage = OpenProjectIntegration.createWorkPackage(api, "$PREFIX$index")
                    created += if (index == 0) {
                        api.updateWorkPackage(workPackage, WorkPackagePatch(workPackage.lockVersion).link("assignee", "/api/v3/users/${me.id}"))
                    } else {
                        workPackage
                    }
                }
            }
        }
        WorkPackageListViews.getInstance(project).put(serverId, ListViewState(search = PREFIX))
        editor = WorkPackageListPanel(project, serverId, null)
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

    private fun waitFor(message: String, condition: () -> Boolean) =
        PlatformTestUtil.waitWithEventsDispatching(message, condition, 60)

    private fun loadedIds() = editor.loadedWorkPackages().map { it.id }.toSet()

    fun `test search sort filters and paging`() {
        val ids = created.map { it.id }.toSet()
        waitFor("search results") { loadedIds() == ids }

        // server titles replace the fallback column titles once the query form is loaded
        waitFor("column titles") { "Subject" in editor.visibleColumnTitles() }

        editor.updateViewState { ListQueries.toggleSort(it.copy(sortBy = listOf(SortSpec("updatedAt"))), "id", additive = false) }
        waitFor("ascending ids") { editor.loadedWorkPackages().map { it.id } == created.map { it.id }.sorted() }
        editor.updateViewState { ListQueries.toggleSort(it, "id", additive = false) }
        waitFor("descending ids") { editor.loadedWorkPackages().map { it.id } == created.map { it.id }.sortedDescending() }

        editor.updateViewState { ListQueries.withAssignedToMe(it, true) }
        waitFor("assigned to me") { loadedIds() == setOf(created[0].id) }
        editor.updateViewState { ListQueries.withAssignedToMe(it, false) }

        val typeHref = created[1].links.href("type")!!
        editor.updateViewState { ListQueries.withFilters(it, it.filters + FilterSpec("id", "=", listOf(created[1].id.toString()))) }
        waitFor("id filter") { loadedIds() == setOf(created[1].id) }
        assertEquals(typeHref, editor.loadedWorkPackages().single().links.href("type"))
        editor.updateViewState { ListQueries.withFilters(it, it.filters.filter { filter -> filter.name != "id" }) }

        // quick filter dropdowns: the work packages' project, then another one
        val projectId = created[0].links["project"]!!.id!!.toLong()
        editor.updateViewState { ListQueries.withQuickFilter(it, "project", Filters.project(projectId)) }
        waitFor("project filter") { loadedIds() == ids }
        editor.updateViewState { ListQueries.withQuickFilter(it, "project", Filters.project(projectId + 1000)) }
        waitFor("other project") { editor.loadedWorkPackages().isEmpty() }
        editor.updateViewState { ListQueries.withQuickFilter(it, "project", null) }
        waitFor("all projects") { loadedIds() == ids }
        editor.updateViewState { ListQueries.withQuickFilter(it, "assignee", FilterSpec("assignee", "!*")) }
        waitFor("unassigned") { loadedIds() == ids - created[0].id }
        editor.updateViewState { ListQueries.withQuickFilter(it, "assignee", null) }

        editor.updateViewState { ListQueries.withStatusMode(it, StatusMode.CLOSED) }
        waitFor("no closed work packages") { editor.loadedWorkPackages().isEmpty() }
        editor.updateViewState { ListQueries.withStatusMode(it, StatusMode.OPEN) }

        editor.updateViewState { it.copy(pageSize = 2, page = 1) }
        waitFor("first page") { editor.loadedWorkPackages().size == 2 }
        editor.updateViewState { it.copy(page = 2) }
        waitFor("second page") { editor.loadedWorkPackages().size == 1 }

        // the view is remembered for the next time the list is shown
        assertEquals(2, WorkPackageListViews.getInstance(project).get(serverId).pageSize)
    }

    private companion object {
        const val PREFIX = "IT-list "
    }
}
