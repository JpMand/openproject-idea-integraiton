package com.github.jpmand.openproject.idea.ui

import com.github.jpmand.openproject.idea.OpenProjectIcons
import com.github.jpmand.openproject.idea.ui.detail.WorkPackageDetailEditor
import com.github.jpmand.openproject.idea.ui.list.WorkPackageListEditor
import com.github.jpmand.openproject.idea.vfs.OpenProjectVirtualFile
import com.github.jpmand.openproject.idea.vfs.WorkPackageListVirtualFile
import com.github.jpmand.openproject.idea.vfs.WorkPackageVirtualFile
import com.intellij.ide.FileIconProvider
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import javax.swing.Icon

class WorkPackageListEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean = file is WorkPackageListVirtualFile

    override fun acceptRequiresReadAction(): Boolean = false

    override fun createEditor(project: Project, file: VirtualFile): FileEditor =
        WorkPackageListEditor(project, file as WorkPackageListVirtualFile)

    override fun getEditorTypeId(): String = "OpenProject.WorkPackageList"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

class WorkPackageDetailEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean = file is WorkPackageVirtualFile

    override fun acceptRequiresReadAction(): Boolean = false

    override fun createEditor(project: Project, file: VirtualFile): FileEditor =
        WorkPackageDetailEditor(project, file as WorkPackageVirtualFile)

    override fun getEditorTypeId(): String = "OpenProject.WorkPackage"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

class OpenProjectFileIconProvider : FileIconProvider {
    override fun getIcon(file: VirtualFile, flags: Int, project: Project?): Icon? =
        if (file is OpenProjectVirtualFile) OpenProjectIcons.OpenProject else null
}
