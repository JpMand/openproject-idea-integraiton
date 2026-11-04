package com.github.jpmand.openproject.idea.vfs

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.vcs.editor.ComplexPathVirtualFileSystem
import com.intellij.vcs.editor.GsonComplexPathSerializer

/**
 * Identifies an OpenProject editor tab: the work package list of a server or a single work package.
 * Serialized into the file path, so open tabs are restored after an IDE restart.
 */
data class OpenProjectFilePath(
    override val sessionId: String,
    override val projectHash: String,
    /** Normalized base URL of the server (see OpenProjectServers). */
    val serverId: String,
    /** Null for the work package list. */
    val workPackageId: Long? = null,
) : ComplexPathVirtualFileSystem.ComplexPath

/** The `openproject://` file system behind the OpenProject editor tabs. */
class OpenProjectVirtualFileSystem : ComplexPathVirtualFileSystem<OpenProjectFilePath>(
    GsonComplexPathSerializer(OpenProjectFilePath::class.java),
) {
    override fun getProtocol(): String = PROTOCOL

    override fun findOrCreateFile(project: Project, path: OpenProjectFilePath): VirtualFile =
        OpenProjectFilesManager.getInstance(project).findOrCreate(path)

    companion object {
        const val PROTOCOL = "openproject"

        /** Constant, so tabs of earlier IDE sessions are restored too. */
        const val SESSION_ID = "openproject"

        fun getInstance(): OpenProjectVirtualFileSystem =
            VirtualFileManager.getInstance().getFileSystem(PROTOCOL) as OpenProjectVirtualFileSystem
    }
}
