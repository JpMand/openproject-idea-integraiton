package com.github.jpmand.openproject.idea.vfs

import com.github.jpmand.openproject.idea.core.OpenProjectServers
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap

/** Creates and caches the editor tab files, so each list or work package has exactly one tab. */
@Service(Service.Level.PROJECT)
class OpenProjectFilesManager(private val project: Project) {

    private val files = ConcurrentHashMap<OpenProjectFilePath, OpenProjectVirtualFile>()

    fun findOrCreate(path: OpenProjectFilePath): OpenProjectVirtualFile = files.computeIfAbsent(path) {
        if (path.workPackageId == null) {
            val name = OpenProjectServers.getInstance(project).find(path.serverId)?.displayName ?: path.serverId.substringAfter("://")
            WorkPackageListVirtualFile(project, path, name)
        } else {
            WorkPackageVirtualFile(project, path)
        }
    }

    fun listFile(serverId: String): WorkPackageListVirtualFile = findOrCreate(path(serverId, null)) as WorkPackageListVirtualFile

    fun workPackageFile(serverId: String, workPackageId: Long): WorkPackageVirtualFile =
        findOrCreate(path(serverId, workPackageId)) as WorkPackageVirtualFile

    fun openList(serverId: String, focus: Boolean = true) {
        OpenFileDescriptor(project, listFile(serverId)).navigate(focus)
    }

    fun openWorkPackage(serverId: String, workPackageId: Long, focus: Boolean = true) {
        OpenFileDescriptor(project, workPackageFile(serverId, workPackageId)).navigate(focus)
    }

    private fun path(serverId: String, workPackageId: Long?) = OpenProjectFilePath(
        sessionId = OpenProjectVirtualFileSystem.SESSION_ID,
        projectHash = project.locationHash,
        serverId = serverId,
        workPackageId = workPackageId,
    )

    companion object {
        fun getInstance(project: Project): OpenProjectFilesManager = project.service()
    }
}
