package com.github.jpmand.openproject.idea.ui

import com.github.jpmand.openproject.idea.core.OpenProjectProjectScope
import com.github.jpmand.openproject.idea.vfs.OpenProjectVirtualFile
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import java.beans.PropertyChangeListener

/** Base of the plugin's editors: content-less files, a coroutine scope that ends with the editor. */
abstract class OpenProjectFileEditor(protected val project: Project, private val file: OpenProjectVirtualFile) :
    UserDataHolderBase(), FileEditor {

    protected val cs: CoroutineScope = OpenProjectProjectScope.getInstance(project).childScope(javaClass.simpleName)

    override fun getFile(): OpenProjectVirtualFile = file

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = file.isValid

    override fun setState(state: FileEditorState) = Unit

    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit

    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit

    override fun dispose() {
        cs.cancel()
    }
}
