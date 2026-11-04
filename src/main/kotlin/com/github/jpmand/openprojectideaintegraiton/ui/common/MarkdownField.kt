package com.github.jpmand.openprojectideaintegraiton.ui.common

import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.openapi.project.Project
import com.intellij.ui.EditorTextField
import com.intellij.util.ui.JBUI

/**
 * Multi-line editor for OpenProject's Markdown (descriptions, comments). Uses the Markdown file type when the
 * Markdown plugin is installed, plain text otherwise; no compile-time dependency on the plugin.
 */
class MarkdownField(project: Project, text: String, rows: Int) :
    EditorTextField(EditorFactory.getInstance().createDocument(text.replace("\r\n", "\n")), project, markdownFileType(), false, false) {

    init {
        setPreferredWidth(JBUI.scale(480))
        preferredSize = JBUI.size(480, rows * 20 + 12)
        addSettingsProvider { editor: EditorEx ->
            editor.settings.isUseSoftWraps = true
            editor.settings.isLineNumbersShown = false
            editor.settings.isFoldingOutlineShown = false
            editor.settings.additionalLinesCount = 0
            editor.setVerticalScrollbarVisible(true)
            editor.setBorder(JBUI.Borders.empty(4))
        }
    }

    companion object {
        fun markdownFileType(): FileType =
            FileTypeManager.getInstance().getFileTypeByExtension("md").takeIf { it !is UnknownFileType } ?: PlainTextFileType.INSTANCE
    }
}
