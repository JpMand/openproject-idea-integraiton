package com.github.jpmand.openprojectideaintegraiton.it

import com.github.jpmand.openprojectideaintegraiton.api.Filters
import com.github.jpmand.openprojectideaintegraiton.api.OpenProjectApi
import com.github.jpmand.openprojectideaintegraiton.api.OpenProjectClientConfig
import com.github.jpmand.openprojectideaintegraiton.api.OpenProjectHttpClient
import com.github.jpmand.openprojectideaintegraiton.api.WorkPackageQuery
import com.github.jpmand.openprojectideaintegraiton.api.model.Project
import com.github.jpmand.openprojectideaintegraiton.api.model.WorkPackage
import com.github.jpmand.openprojectideaintegraiton.api.model.WorkPackageType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assume

/**
 * Access to the OpenProject instance used by the `*IntegrationTest` classes, configured through
 * `OPENPROJECT_IT_URL` and `OPENPROJECT_IT_TOKEN` (see scripts/openproject-it.sh). Tests are skipped when unset.
 */
object OpenProjectIntegration {
    val url: String? = System.getenv("OPENPROJECT_IT_URL")?.takeIf { it.isNotBlank() }
    val token: String? = System.getenv("OPENPROJECT_IT_TOKEN")?.takeIf { it.isNotBlank() }

    fun assumeConfigured() {
        Assume.assumeTrue("OPENPROJECT_IT_URL / OPENPROJECT_IT_TOKEN not set; run scripts/openproject-it.sh test", url != null && token != null)
    }

    fun newApi(): OpenProjectApi = OpenProjectApi(OpenProjectHttpClient.create(OpenProjectClientConfig(url!!, token)), url)

    /** A project of the seeded demo data where work packages can be created, and its default "Task"-like type. */
    suspend fun demoProject(api: OpenProjectApi): Pair<Project, WorkPackageType> {
        val project = api.projects().firstOrNull { it.identifier == "demo-project" } ?: api.projects().first()
        val types = api.types()
        val type = types.firstOrNull { it.name.equals("Task", ignoreCase = true) } ?: types.first { !it.isMilestone }
        return project to type
    }

    suspend fun createWorkPackage(api: OpenProjectApi, subject: String): WorkPackage {
        val (project, type) = demoProject(api)
        return api.createWorkPackage(buildJsonObject {
            put("subject", subject)
            putJsonObject("description") { put("raw", "Created by the **integration tests**.") }
            putJsonObject("_links") {
                putJsonObject("project") { put("href", "/api/v3/projects/${project.id}") }
                putJsonObject("type") { put("href", "/api/v3/types/${type.id}") }
            }
        })
    }

    /** Deletes work packages left over by earlier runs. */
    fun cleanUp(api: OpenProjectApi, prefix: String) = runBlocking {
        api.workPackages(WorkPackageQuery(filters = listOf(Filters.anyStatus(), Filters.search(prefix)), pageSize = 200))
            .elements.filter { it.subject.startsWith(prefix) }
            .forEach { runCatching { api.deleteWorkPackage(it) } }
    }
}
