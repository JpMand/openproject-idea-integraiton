package com.github.jpmand.openprojectideaintegraiton.core

import com.github.jpmand.openprojectideaintegraiton.api.OpenProjectUrls
import com.github.jpmand.openprojectideaintegraiton.tasks.OpenProjectRepository
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.tasks.TaskManager
import com.intellij.tasks.config.TaskRepositoriesConfigurable
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread

/** An OpenProject server configured as a Task Server; [id] is its normalized base URL. */
data class OpenProjectServer(val id: String, val displayName: String, val repository: OpenProjectRepository)

/**
 * The OpenProject servers of a project: the Task Servers of type "OpenProject" (Settings | Tools | Tasks | Servers).
 * That configuration is the single source of URL and API token for every feature of the plugin.
 */
@Service(Service.Level.PROJECT)
class OpenProjectServers(private val project: Project) {

    fun servers(): List<OpenProjectServer> =
        TaskManager.getManager(project).allRepositories
            .filterIsInstance<OpenProjectRepository>()
            .filter { it.url.isNotBlank() }
            .map { OpenProjectServer(OpenProjectUrls.normalizeBaseUrl(it.url), it.presentableName, it) }
            .distinctBy { it.id }

    fun find(serverId: String?): OpenProjectServer? =
        servers().let { all -> all.firstOrNull { it.id == serverId } ?: all.firstOrNull().takeIf { serverId == null } }

    /** Reads the token from the password safe, so it must not run on the EDT. */
    @RequiresBackgroundThread
    fun connection(server: OpenProjectServer): OpenProjectConnection = server.repository.connection()

    /** Opens Settings | Tools | Tasks | Servers. */
    fun openServerSettings() {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, TaskRepositoriesConfigurable::class.java)
    }

    companion object {
        fun getInstance(project: Project): OpenProjectServers = project.service()
    }
}
