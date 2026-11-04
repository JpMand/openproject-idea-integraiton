package com.github.jpmand.openprojectideaintegraiton.core

import com.github.jpmand.openprojectideaintegraiton.api.OpenProjectUrls
import com.github.jpmand.openprojectideaintegraiton.api.model.WorkPackage
import com.github.jpmand.openprojectideaintegraiton.tasks.OpenProjectRepository
import com.github.jpmand.openprojectideaintegraiton.tasks.OpenProjectTask
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.tasks.LocalTask
import com.intellij.tasks.TaskListenerAdapter
import com.intellij.tasks.TaskManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The work package behind the active task of Tools | Tasks & Contexts, if it is an OpenProject one. */
data class ActiveWorkPackage(val serverId: String, val workPackageId: Long, val presentableId: String, val summary: String)

/** Follows task activation so editors and the status bar can show what is being worked on. */
@Service(Service.Level.PROJECT)
class ActiveWorkPackageTracker(private val project: Project) : Disposable {

    private val _active = MutableStateFlow<ActiveWorkPackage?>(null)
    val active: StateFlow<ActiveWorkPackage?> = _active.asStateFlow()

    init {
        TaskManager.getManager(project).addTaskListener(object : TaskListenerAdapter() {
            override fun taskActivated(task: LocalTask) = refresh()
            override fun taskDeactivated(task: LocalTask) = refresh()
            override fun taskRemoved(task: LocalTask) = refresh()
        }, this)
        refresh()
    }

    fun refresh() {
        _active.value = toActive(TaskManager.getManager(project).activeTask)
    }

    fun isActive(serverId: String, workPackageId: Long): Boolean =
        _active.value?.let { it.serverId == serverId && it.workPackageId == workPackageId } == true

    /** Makes the work package the active task (switching changelists/branches as configured in Tasks). */
    fun activate(server: OpenProjectServer, workPackage: WorkPackage, closed: Boolean = false) {
        val task = OpenProjectTask(workPackage, server.repository, server.id, closed)
        TaskManager.getManager(project).activateTask(task, false)
        refresh()
    }

    private fun toActive(task: LocalTask): ActiveWorkPackage? {
        if (task.isDefault) return null
        val id = task.id.toLongOrNull() ?: return null
        val servers = OpenProjectServers.getInstance(project).servers()
        val server = (task.repository as? OpenProjectRepository)
            ?.let { repository -> servers.firstOrNull { it.id == OpenProjectUrls.normalizeBaseUrl(repository.url) } }
            ?: task.issueUrl?.let { url -> servers.firstOrNull { url.startsWith(it.id + "/work_packages/") } }
            ?: return null
        return ActiveWorkPackage(server.id, id, task.presentableId, task.summary)
    }

    override fun dispose() = Unit

    companion object {
        fun getInstance(project: Project): ActiveWorkPackageTracker = project.service()
    }
}
