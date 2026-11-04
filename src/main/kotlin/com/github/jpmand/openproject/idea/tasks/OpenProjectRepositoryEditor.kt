package com.github.jpmand.openproject.idea.tasks

import com.github.jpmand.openproject.idea.OpenProjectBundle
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.runBlockingMaybeCancellable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.tasks.config.BaseRepositoryEditor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.Consumer
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent

/**
 * Settings of an OpenProject Task Server: URL, API token (the password field) and how tasks are searched.
 * Projects and saved queries are loaded from the server once the URL and token work.
 */
class OpenProjectRepositoryEditor(
    project: Project,
    repository: OpenProjectRepository,
    changeListener: Consumer<in OpenProjectRepository>,
) : BaseRepositoryEditor<OpenProjectRepository>(project, repository, changeListener) {

    private data class Item(val id: Long, val name: String) {
        override fun toString(): String = name
    }

    // Created in createCustomPanel(), which the base constructor calls before this class is initialized.
    private lateinit var projectCombo: ComboBox<Item>
    private lateinit var queryCombo: ComboBox<Item>
    private lateinit var assignedToMeCheckBox: JBCheckBox
    private lateinit var fullTextCheckBox: JBCheckBox

    @Volatile
    private var disposed = false

    init {
        myUsernameLabel.isVisible = false
        myUserNameText.isVisible = false
        myUseHttpAuthenticationCheckBox.isVisible = false
        myPasswordLabel.text = OpenProjectBundle.message("repository.editor.token")
        myPasswordText.toolTipText = OpenProjectBundle.message("repository.editor.token.hint")
        myURLText.toolTipText = OpenProjectBundle.message("repository.editor.url.hint")
        installListener(projectCombo)
        installListener(queryCombo)
        installListener(assignedToMeCheckBox)
        installListener(fullTextCheckBox)
        reloadServerData()
    }

    override fun createCustomPanel(): JComponent {
        val repository = myRepository
        projectCombo = ComboBox(DefaultComboBoxModel(arrayOf(allProjects(), currentProject(repository)).distinct().toTypedArray())).apply {
            selectedItem = currentProject(repository)
        }
        queryCombo = ComboBox(DefaultComboBoxModel(arrayOf(noQuery(), currentQuery(repository)).distinct().toTypedArray())).apply {
            selectedItem = currentQuery(repository)
        }
        assignedToMeCheckBox = JBCheckBox(OpenProjectBundle.message("repository.editor.assigned.to.me"), repository.assignedToMeOnly)
        fullTextCheckBox = JBCheckBox(OpenProjectBundle.message("repository.editor.full.text"), repository.fullTextSearch)
        return panel {
            row(OpenProjectBundle.message("repository.editor.project")) { cell(projectCombo) }
            row(OpenProjectBundle.message("repository.editor.query")) { cell(queryCombo) }
            row { cell(assignedToMeCheckBox) }
            row { cell(fullTextCheckBox).comment(OpenProjectBundle.message("repository.editor.full.text.comment")) }
        }
    }

    override fun apply() {
        val project = projectCombo.selectedItem as? Item
        val query = queryCombo.selectedItem as? Item
        myRepository.projectId = project?.id ?: 0
        myRepository.projectName = project?.takeIf { it.id > 0 }?.name
        myRepository.savedQueryId = query?.id ?: 0
        myRepository.assignedToMeOnly = assignedToMeCheckBox.isSelected
        myRepository.fullTextSearch = fullTextCheckBox.isSelected
        myRepository.username = OpenProjectRepository.API_KEY_USER
        super.apply()
    }

    override fun afterTestConnection(connectionSuccessful: Boolean) {
        super.afterTestConnection(connectionSuccessful)
        if (connectionSuccessful) reloadServerData()
    }

    override fun dispose() {
        disposed = true
        super.dispose()
    }

    /** Loads projects and saved queries in the background; failures just leave the combos as they are. */
    private fun reloadServerData() {
        val repository = myRepository.clone()
        ApplicationManager.getApplication().executeOnPooledThread {
            if (!repository.isConfigured) return@executeOnPooledThread
            val loaded = runCatching {
                runBlockingMaybeCancellable {
                    val api = repository.connection().api
                    api.projects().map { Item(it.id, it.name) } to api.queries().filter { it.id != null }.map { Item(it.id!!, it.name.orEmpty()) }
                }
            }.onFailure { thisLogger().info("Could not load OpenProject projects and queries", it) }.getOrNull() ?: return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater({
                if (disposed) return@invokeLater
                fill(projectCombo, listOf(allProjects()) + loaded.first, myRepository.projectId)
                fill(queryCombo, listOf(noQuery()) + loaded.second, myRepository.savedQueryId)
            }, ModalityState.any())
        }
    }

    private fun fill(combo: ComboBox<Item>, items: List<Item>, selectedId: Long) {
        val selected = (combo.selectedItem as? Item)?.id ?: selectedId
        combo.model = DefaultComboBoxModel(items.toTypedArray())
        combo.selectedItem = items.firstOrNull { it.id == selected } ?: items.first()
    }

    private fun allProjects() = Item(0, OpenProjectBundle.message("repository.editor.project.all"))

    private fun noQuery() = Item(0, OpenProjectBundle.message("repository.editor.query.none"))

    private fun currentProject(repository: OpenProjectRepository) =
        if (repository.projectId > 0) Item(repository.projectId, repository.projectName ?: "#${repository.projectId}") else allProjects()

    private fun currentQuery(repository: OpenProjectRepository) =
        if (repository.savedQueryId > 0) Item(repository.savedQueryId, "#${repository.savedQueryId}") else noQuery()
}
