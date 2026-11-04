package com.github.jpmand.openproject.idea.vfs

import com.github.jpmand.openproject.idea.OpenProjectBundle
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFilePathWrapper
import com.intellij.openapi.vfs.VirtualFileSystem
import com.intellij.openapi.vfs.VirtualFileWithoutContent
import com.intellij.testFramework.LightVirtualFileBase
import java.io.InputStream
import java.io.OutputStream

/** A content-less editor tab file of the plugin; equality is by [filePath]. */
sealed class OpenProjectVirtualFile(val project: Project, val filePath: OpenProjectFilePath, name: String) :
    LightVirtualFileBase(name, null, 0), VirtualFileWithoutContent, VirtualFilePathWrapper {

    init {
        isWritable = false
    }

    val serverId: String get() = filePath.serverId

    @Volatile
    private var title: String = name

    /** Updates the tab title (e.g. once the work package subject is known). */
    fun updateTitle(newTitle: String) {
        if (newTitle == title) return
        title = newTitle
        if (!project.isDisposed) FileEditorManagerEx.getInstanceEx(project).updateFilePresentation(this)
    }

    override fun getPresentableName(): String = title

    override fun getPresentablePath(): String = title

    override fun enforcePresentableName(): Boolean = true

    override fun getFileSystem(): VirtualFileSystem = OpenProjectVirtualFileSystem.getInstance()

    override fun getPath(): String = OpenProjectVirtualFileSystem.getInstance().getPath(filePath)

    override fun getFileType(): FileType = UnknownFileType.INSTANCE

    override fun isValid(): Boolean = !project.isDisposed

    override fun getLength(): Long = 0

    override fun contentsToByteArray(): ByteArray = throw UnsupportedOperationException()

    override fun getInputStream(): InputStream = throw UnsupportedOperationException()

    override fun getOutputStream(requestor: Any?, newModificationStamp: Long, newTimeStamp: Long): OutputStream =
        throw UnsupportedOperationException()

    override fun equals(other: Any?): Boolean = other is OpenProjectVirtualFile && other.filePath == filePath

    override fun hashCode(): Int = filePath.hashCode()
}

/** The work package table of a server. */
class WorkPackageListVirtualFile(project: Project, path: OpenProjectFilePath, serverName: String) :
    OpenProjectVirtualFile(project, path, OpenProjectBundle.message("file.list.title", serverName))

/** A single work package. */
class WorkPackageVirtualFile(project: Project, path: OpenProjectFilePath) :
    OpenProjectVirtualFile(project, path, "#${path.workPackageId}") {
    val workPackageId: Long get() = filePath.workPackageId!!
}
