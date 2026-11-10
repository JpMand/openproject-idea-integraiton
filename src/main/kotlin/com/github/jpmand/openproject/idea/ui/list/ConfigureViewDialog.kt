package com.github.jpmand.openproject.idea.ui.list

import com.github.jpmand.openproject.idea.OpenProjectBundle
import com.github.jpmand.openproject.idea.api.SortSpec
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBList
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.util.ui.JBUI
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent

/** "Configure view": visible columns and their order, sort criteria and page size. */
class ConfigureViewDialog(
    project: Project,
    private val metadata: ListMetadata,
    private val initial: ListViewState,
    pageSizes: List<Int>,
) : DialogWrapper(project) {

    private val columnsModel = CollectionListModel(initial.columns)
    private val columnsList = JBList(columnsModel).apply {
        cellRenderer = textListCellRenderer { it?.let(metadata::columnTitle) ?: "" }
    }

    private val sortable: List<String?> = listOf<String?>(null) + metadata.columns.values.filter { it.sortable }.map { it.id }
        .sortedBy { metadata.columnTitle(it).lowercase() }
    private val sortRows = (0 until 3).map { position ->
        val current = initial.sortBy.getOrNull(position)
        val column = ComboBox(DefaultComboBoxModel(sortable.toTypedArray())).apply {
            renderer = textListCellRenderer { it?.let(metadata::columnTitle) ?: OpenProjectBundle.message("configure.sort.none") }
            selectedItem = current?.attribute?.takeIf { it in sortable }
        }
        val direction = ComboBox(arrayOf(true, false)).apply {
            renderer = textListCellRenderer {
                if (it == false) OpenProjectBundle.message("configure.sort.descending") else OpenProjectBundle.message("configure.sort.ascending")
            }
            selectedItem = current?.ascending ?: true
        }
        column to direction
    }
    private val pageSizeCombo = ComboBox((pageSizes + initial.pageSize).distinct().sorted().toTypedArray()).apply {
        selectedItem = initial.pageSize
    }

    init {
        title = OpenProjectBundle.message("configure.title")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val columnsPanel = ToolbarDecorator.createDecorator(columnsList)
            .setAddAction { button -> chooseColumn(button.contextComponent) }
            .setRemoveActionUpdater { columnsModel.size > 1 }
            .createPanel()
            .apply { preferredSize = JBUI.size(320, 300) }
        return panel {
            group(OpenProjectBundle.message("configure.columns")) {
                row { cell(columnsPanel).align(Align.FILL) }.resizableRow()
            }.resizableRow()
            group(OpenProjectBundle.message("configure.sort")) {
                sortRows.forEachIndexed { index, (column, direction) ->
                    row(if (index == 0) OpenProjectBundle.message("configure.sort.by") else OpenProjectBundle.message("configure.sort.then")) {
                        cell(column)
                        cell(direction)
                    }
                }
            }
            row(OpenProjectBundle.message("configure.page.size")) { cell(pageSizeCombo) }
        }
    }

    private fun chooseColumn(anchor: JComponent) {
        val available = metadata.columns.keys.filter { it !in columnsModel.items }.sortedBy { metadata.columnTitle(it).lowercase() }
        if (available.isEmpty()) return
        JBPopupFactory.getInstance().createPopupChooserBuilder(available)
            .setRenderer(textListCellRenderer { it?.let(metadata::columnTitle) ?: "" })
            .setNamerForFiltering(metadata::columnTitle)
            .setItemChosenCallback { column ->
                columnsModel.add(column)
                columnsList.selectedIndex = columnsModel.size - 1
            }
            .createPopup()
            .showUnderneathOf(anchor)
    }

    fun result(): ListViewState {
        val sortBy = sortRows.mapNotNull { (column, direction) ->
            (column.selectedItem as? String)?.let { SortSpec(it, direction.selectedItem as? Boolean ?: true) }
        }.distinctBy { it.attribute }
        return initial.copy(
            columns = columnsModel.items.toList(),
            sortBy = sortBy.ifEmpty { ListViewState.DEFAULT_SORT },
            pageSize = pageSizeCombo.selectedItem as? Int ?: initial.pageSize,
            page = 1,
        )
    }
}
