package com.github.jpmand.openproject.idea.ui.detail

import com.github.jpmand.openproject.idea.OpenProjectBundle
import com.github.jpmand.openproject.idea.api.model.WorkPackage
import com.github.jpmand.openproject.idea.core.OpenProjectProjectScope
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.Disposer
import com.intellij.ui.CollectionListModel
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.swing.JComponent
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent

/** Picks a relation type and the related work package (searched by id or subject among the server's candidates). */
class AddRelationDialog(project: Project, private val controller: WorkPackageDetailController) : DialogWrapper(project) {

    data class Result(val type: String, val target: WorkPackage, val description: String?, val lag: Int?)

    private val cs = OpenProjectProjectScope.getInstance(project).childScope("AddRelationDialog")
    private val typeCombo = ComboBox(RelationsPanel.TYPES.toTypedArray()).apply {
        renderer = textListCellRenderer { it?.let(RelationsPanel::label) ?: "" }
    }
    private val searchField = JBTextField().apply { emptyText.text = OpenProjectBundle.message("detail.relations.search.hint") }
    private val candidates = CollectionListModel<WorkPackage>()
    private val candidateList = JBList(candidates).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = textListCellRenderer { "${it?.presentableId}  ${it?.subject}" }
        emptyText.text = OpenProjectBundle.message("detail.relations.search.empty")
    }
    private val descriptionField = JBTextField()
    private val lagField = JBTextField("0", 4)
    private var searchJob: Job? = null

    init {
        title = OpenProjectBundle.message("detail.relations.add.title")
        Disposer.register(disposable) { cs.cancel() }
        searchField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = search()
        })
        typeCombo.addActionListener {
            lagField.isEnabled = typeCombo.selectedItem in setOf("precedes", "follows")
            search()
        }
        lagField.isEnabled = false
        init()
        search()
    }

    override fun createCenterPanel(): JComponent = panel {
        row(OpenProjectBundle.message("detail.relations.type")) { cell(typeCombo) }
        row(OpenProjectBundle.message("detail.relations.work.package")) { cell(searchField).align(Align.FILL) }
        row { cell(ScrollPaneFactory.createScrollPane(candidateList).apply { preferredSize = JBUI.size(460, 200) }).align(Align.FILL) }
        row(OpenProjectBundle.message("detail.relations.description")) { cell(descriptionField).align(Align.FILL) }
        row(OpenProjectBundle.message("detail.relations.lag")) { cell(lagField) }
    }

    override fun getPreferredFocusedComponent(): JComponent = searchField

    override fun doValidate(): ValidationInfo? = when {
        candidateList.selectedValue == null -> ValidationInfo(OpenProjectBundle.message("detail.relations.choose"), searchField)
        lagField.isEnabled && lagField.text.isNotBlank() && lagField.text.trim().toIntOrNull()?.takeIf { it >= 0 } == null ->
            ValidationInfo(OpenProjectBundle.message("detail.relations.lag.invalid"), lagField)
        else -> null
    }

    fun result(): Result = Result(
        type = typeCombo.selectedItem as String,
        target = candidateList.selectedValue,
        description = descriptionField.text.trim().ifEmpty { null },
        lag = if (lagField.isEnabled) lagField.text.trim().toIntOrNull() else null,
    )

    private fun search() {
        searchJob?.cancel()
        val query = searchField.text.trim()
        val type = typeCombo.selectedItem as String
        searchJob = cs.launch {
            delay(250)
            val found = runCatching { controller.relationCandidates(query, type) }.getOrDefault(emptyList())
            withContext(Dispatchers.EDT) {
                val selected = candidateList.selectedValue?.id
                candidates.replaceAll(found)
                found.indexOfFirst { it.id == selected }.takeIf { it >= 0 }?.let(candidateList::setSelectedIndex)
                if (candidateList.selectedIndex < 0 && found.size == 1) candidateList.selectedIndex = 0
            }
        }
    }
}
