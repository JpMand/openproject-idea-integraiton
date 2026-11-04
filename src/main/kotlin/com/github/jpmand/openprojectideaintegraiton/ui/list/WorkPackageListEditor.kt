package com.github.jpmand.openprojectideaintegraiton.ui.list

import com.github.jpmand.openprojectideaintegraiton.OpenProjectBundle
import com.github.jpmand.openprojectideaintegraiton.api.Filters
import com.github.jpmand.openprojectideaintegraiton.api.OpenProjectApiException
import com.github.jpmand.openprojectideaintegraiton.api.SortSpec
import com.github.jpmand.openprojectideaintegraiton.api.WorkPackagePage
import com.github.jpmand.openprojectideaintegraiton.api.WorkPackageQuery
import com.github.jpmand.openprojectideaintegraiton.api.model.Query
import com.github.jpmand.openprojectideaintegraiton.api.model.WorkPackage
import com.github.jpmand.openprojectideaintegraiton.core.ActiveWorkPackage
import com.github.jpmand.openprojectideaintegraiton.core.ActiveWorkPackageTracker
import com.github.jpmand.openprojectideaintegraiton.core.OpenProjectConnection
import com.github.jpmand.openprojectideaintegraiton.core.OpenProjectServer
import com.github.jpmand.openprojectideaintegraiton.core.connect
import com.github.jpmand.openprojectideaintegraiton.core.describeError
import com.github.jpmand.openprojectideaintegraiton.ui.OpenProjectDataKeys
import com.github.jpmand.openprojectideaintegraiton.ui.OpenProjectFileEditor
import com.github.jpmand.openprojectideaintegraiton.ui.WorkPackageRef
import com.github.jpmand.openprojectideaintegraiton.ui.common.StatusPanel
import com.github.jpmand.openprojectideaintegraiton.vfs.OpenProjectFilesManager
import com.github.jpmand.openprojectideaintegraiton.vfs.WorkPackageListVirtualFile
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.JBColor
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.TableSpeedSearch
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.TestOnly
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.event.ChangeEvent
import javax.swing.event.DocumentEvent
import javax.swing.event.ListSelectionEvent
import javax.swing.event.TableColumnModelEvent
import javax.swing.event.TableColumnModelListener
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellRenderer

/**
 * The work package table of a server, modelled on OpenProject's own table: configurable columns (header drag,
 * Configure View), server-side sorting by header click, global search, quick filters, the full filter bar,
 * saved OpenProject queries and paging. The active task's work package is highlighted and shown in a banner.
 */
@OptIn(FlowPreview::class)
class WorkPackageListEditor(project: Project, private val file: WorkPackageListVirtualFile) : OpenProjectFileEditor(project, file) {

    private data class SavedView(val id: Long, val name: String) {
        override fun toString() = name
    }

    private inner class Column(val id: String, title: String) : ColumnInfo<WorkPackage, String>(title) {
        override fun valueOf(item: WorkPackage): String = WorkPackageCells.text(item, id)
        override fun getRenderer(item: WorkPackage?): TableCellRenderer = cellRenderer
    }

    private val views = WorkPackageListViews.getInstance(project)
    private val viewState = MutableStateFlow(views.get(file.serverId).copy(page = 1))

    @Volatile private var server: OpenProjectServer? = null
    @Volatile private var connection: OpenProjectConnection? = null
    @Volatile private var metadata: ListMetadata = ListMetadata.FALLBACK
    @Volatile private var active: ActiveWorkPackage? = null
    private var columnIds: List<String> = emptyList()
    private var updatingColumns = false
    private var columnsDirty = false
    private var syncing = false
    private var savedViewsLoaded = false
    private var lastPage: WorkPackagePage? = null
    @Volatile private var pageSizes: List<Int> = listOf(20, 50, 100)

    private val model = ListTableModel<WorkPackage>()
    private val table = TableView(model).apply {
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        setShowGrid(false)
        tableHeader.reorderingAllowed = true
        autoResizeMode = JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS
    }
    private val cellRenderer = ActiveAwareRenderer()

    private val searchField = SearchTextField(false).apply {
        textEditor.emptyText.text = OpenProjectBundle.message("list.search.hint")
        textEditor.columns = 22
    }
    private val statusCombo = ComboBox(DefaultComboBoxModel(StatusMode.entries.toTypedArray())).apply {
        renderer = textListCellRenderer { it?.let { mode -> OpenProjectBundle.message("list.status.${mode.name.lowercase()}") } ?: "" }
    }
    private val assignedToMe = JBCheckBox(OpenProjectBundle.message("list.assigned.to.me"))
    private val viewsCombo = ComboBox(DefaultComboBoxModel(arrayOf(defaultView()))).apply {
        toolTipText = OpenProjectBundle.message("list.views.tooltip")
    }

    private val filterBar = FilterBar(cs, { metadata }, ::loadOptions) { filters ->
        viewState.update { ListQueries.withFilters(it, filters) }
    }
    private var showFilters = false
    private val filterPanel = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.customLineBottom(JBColor.border())
        add(filterBar, BorderLayout.CENTER)
        isVisible = false
    }
    private val queryError = JBLabel().apply {
        foreground = JBColor.RED
        border = JBUI.Borders.empty(4, 8)
        isVisible = false
    }
    private val banner = ActiveBanner()
    private val status = StatusPanel()
    private val center = JPanel(BorderLayout())
    private val footerLabel = JBLabel()
    private val loadingLabel = JBLabel(AnimatedIcon.Default.INSTANCE).apply { isVisible = false }
    private val previousLink = ActionLink(OpenProjectBundle.message("list.page.previous")) { viewState.update { it.copy(page = (it.page - 1).coerceAtLeast(1)) } }
    private val nextLink = ActionLink(OpenProjectBundle.message("list.page.next")) { viewState.update { it.copy(page = it.page + 1) } }

    private val root: JComponent = UiDataProvider.wrapComponent(JPanel(BorderLayout()).apply {
        add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(header())
            add(filterPanel)
            add(queryError)
            add(banner)
        }, BorderLayout.NORTH)
        add(center, BorderLayout.CENTER)
        add(footer(), BorderLayout.SOUTH)
    }) { sink ->
        val selected = table.selectedObject
        val server = server
        sink[OpenProjectDataKeys.WORK_PACKAGE] = if (selected != null && server != null) WorkPackageRef(server, selected) else null
        sink[OpenProjectDataKeys.REFRESHER] = Runnable { reload() }
    }

    init {
        installTableBehaviour()
        installControls()
        rebuildColumns(viewState.value)
        showFilters = viewState.value.filters.any { it.name != "status" }
        filterPanel.isVisible = showFilters
        center.add(status, BorderLayout.CENTER)
        status.loading()

        cs.launch {
            viewState.map(ListQueries::toQuery).distinctUntilChanged().debounce(250).collectLatest { query -> load(query) }
        }
        cs.launch(Dispatchers.EDT) {
            viewState.collect { state ->
                views.put(file.serverId, state)
                syncControls(state)
                if (state.columns != columnIds) rebuildColumns(state)
                table.tableHeader.repaint()
            }
        }
        cs.launch {
            ActiveWorkPackageTracker.getInstance(project).active.collect { value ->
                active = value?.takeIf { it.serverId == file.serverId }
                withContext(Dispatchers.EDT) {
                    banner.show(active)
                    table.repaint()
                }
            }
        }
    }

    fun reload() {
        connection?.invalidate()
        cs.launch { load(ListQueries.toQuery(viewState.value)) }
    }

    // ---- loading ----

    private suspend fun load(query: WorkPackageQuery) {
        withContext(Dispatchers.EDT) { loadingLabel.isVisible = true }
        try {
            val (server, connection) = connect(project, file.serverId)
            this.server = server
            this.connection = connection
            val loadedMetadata = ListMetadata.load(connection)
            runCatching { connection.configuration().perPageOptions }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { pageSizes = it }
            val metadataChanged = loadedMetadata !== metadata
            metadata = loadedMetadata
            val page = connection.api.workPackages(query)
            withContext(Dispatchers.EDT) {
                if (metadataChanged) {
                    filterBar.refreshMetadata()
                    rebuildColumns(viewState.value)
                }
                if (!savedViewsLoaded) loadSavedViews(connection)
                show(page)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: OpenProjectApiException) {
            withContext(Dispatchers.EDT) {
                if (e.errorIdentifier == OpenProjectApiException.INVALID_QUERY || e.status == 400) {
                    queryError.text = e.message
                    queryError.isVisible = true
                } else {
                    showStatus { status.error(project, e) { reload() } }
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.EDT) { showStatus { status.error(project, e) { reload() } } }
        } finally {
            withContext(Dispatchers.EDT + NonCancellable) { loadingLabel.isVisible = false }
        }
    }

    private fun show(page: WorkPackagePage) {
        lastPage = page
        queryError.isVisible = false
        val selectedId = table.selectedObject?.id
        model.items = page.elements
        selectedId?.let { id -> model.items.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { table.selectionModel.setSelectionInterval(it, it) } }
        val pages = ListQueries.pageCount(page.total, page.pageSize)
        val from = if (page.total == 0) 0 else (page.page - 1) * page.pageSize + 1
        val to = ((page.page - 1) * page.pageSize + page.elements.size)
        footerLabel.text = OpenProjectBundle.message("list.page.summary", from, to, page.total)
        previousLink.isVisible = pages > 1
        nextLink.isVisible = pages > 1
        previousLink.isEnabled = page.page > 1
        nextLink.isEnabled = page.page < pages
        if (page.elements.isEmpty()) showStatus { status.empty(OpenProjectBundle.message("list.empty")) } else showTable()
    }

    private suspend fun loadOptions(filter: FilterMeta, operator: String, elementType: String): List<ValueOption> {
        val connection = connection ?: connect(project, file.serverId).second
        fun named(id: Long, name: String) = ValueOption(id.toString(), name)
        return when (elementType) {
            "Status" -> connection.statuses().map { named(it.id, it.name) }
            "Type" -> connection.types().map { named(it.id, it.name) }
            "Priority" -> connection.priorities().map { named(it.id, it.name) }
            "Project" -> connection.projects().map { named(it.id, it.name) }
            "Version" -> connection.cached("versions") { connection.api.versions() }.map { named(it.id, it.name) }
            "User", "Principal", "Group", "PlaceholderUser" ->
                listOf(ValueOption("me", OpenProjectBundle.message("filter.me"))) +
                    connection.cached("principals") { connection.api.principals(pageSize = 500) }.map { named(it.id, it.name) }
            else -> filter.allowedValues[operator].orEmpty().mapNotNull { link -> link.id?.let { ValueOption(it, link.title ?: it) } }
        }
    }

    private fun loadSavedViews(connection: OpenProjectConnection) {
        savedViewsLoaded = true
        cs.launch {
            val queries = runCatching { connection.api.queries() }.getOrNull() ?: return@launch
            val items = listOf(defaultView()) + queries.filter { it.id != null && !it.hidden }
                .sortedWith(compareByDescending<Query> { it.starred }.thenBy { it.name?.lowercase() })
                .map { SavedView(it.id!!, (if (it.starred) "★ " else "") + it.name.orEmpty()) }
            withContext(Dispatchers.EDT) {
                syncing = true
                viewsCombo.model = DefaultComboBoxModel(items.toTypedArray())
                viewsCombo.selectedItem = items.firstOrNull { it.id == viewState.value.savedQueryId } ?: items.first()
                syncing = false
            }
        }
    }

    private fun applySavedView(view: SavedView) {
        if (view.id == 0L) {
            viewState.update { ListViewState(widths = it.widths, pageSize = it.pageSize) }
            return
        }
        cs.launch {
            try {
                val connection = connection ?: connect(project, file.serverId).second
                val query = connection.api.query(view.id)
                val columns = query.links.list("columns").mapNotNull { it.id }
                val sortBy = query.links.list("sortBy").mapNotNull { it.id }.map { SortSpec(it.substringBeforeLast('-'), !it.endsWith("-desc")) }
                viewState.update {
                    it.copy(
                        columns = columns.ifEmpty { it.columns },
                        sortBy = sortBy.ifEmpty { ListViewState.DEFAULT_SORT },
                        filters = Filters.fromQueryFilters(query.filters),
                        search = "",
                        page = 1,
                        savedQueryId = view.id,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.EDT) {
                    queryError.text = describeError(e)
                    queryError.isVisible = true
                }
            }
        }
    }

    // ---- UI ----

    private fun header(): JComponent {
        val group = DefaultActionGroup(
            ActionManager.getInstance().getAction("OpenProject.Refresh"),
            object : DumbAwareAction(OpenProjectBundle.message("list.configure"), null, AllIcons.General.GearPlain) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun actionPerformed(e: AnActionEvent) = configureView()
            },
            object : DumbAwareToggleAction(OpenProjectBundle.message("list.filters"), null, AllIcons.General.Filter) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun isSelected(e: AnActionEvent) = showFilters
                override fun setSelected(e: AnActionEvent, state: Boolean) {
                    showFilters = state
                    filterPanel.isVisible = state
                }
                override fun update(e: AnActionEvent) {
                    super.update(e)
                    val count = viewState.value.filters.size
                    e.presentation.text = if (count == 0) OpenProjectBundle.message("list.filters") else OpenProjectBundle.message("list.filters.count", count)
                }
            },
            object : DumbAwareAction(OpenProjectBundle.message("list.reset"), null, AllIcons.General.Reset) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun actionPerformed(e: AnActionEvent) = viewState.update { ListViewState(widths = it.widths, pageSize = it.pageSize) }
            },
            Separator.getInstance(),
            ActionManager.getInstance().getAction("OpenProject.LogTime"),
        )
        val toolbar = ActionManager.getInstance().createActionToolbar(ActionPlaces.EDITOR_TOOLBAR, group, true)
        toolbar.targetComponent = table
        val controls = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), JBUI.scale(2))).apply {
            isOpaque = false
            add(searchField)
            add(statusCombo)
            add(assignedToMe)
            add(JBLabel(OpenProjectBundle.message("list.views")))
            add(viewsCombo)
        }
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.customLineBottom(JBColor.border())
            add(toolbar.component, BorderLayout.WEST)
            add(controls, BorderLayout.CENTER)
        }
    }

    private fun footer(): JComponent = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(10), JBUI.scale(4))).apply {
        border = JBUI.Borders.customLineTop(JBColor.border())
        add(footerLabel)
        add(previousLink)
        add(nextLink)
        add(loadingLabel)
    }

    private fun installControls() {
        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                if (!syncing) viewState.update { ListQueries.withSearch(it, searchField.text) }
            }
        })
        statusCombo.addActionListener {
            if (!syncing) (statusCombo.selectedItem as? StatusMode)?.let { mode -> viewState.update { ListQueries.withStatusMode(it, mode) } }
        }
        assignedToMe.addActionListener {
            if (!syncing) viewState.update { ListQueries.withAssignedToMe(it, assignedToMe.isSelected) }
        }
        viewsCombo.addActionListener {
            if (!syncing) (viewsCombo.selectedItem as? SavedView)?.let(::applySavedView)
        }
    }

    private fun syncControls(state: ListViewState) {
        syncing = true
        try {
            if (searchField.text != state.search && !searchField.textEditor.hasFocus()) searchField.text = state.search
            statusCombo.selectedItem = ListQueries.statusMode(state)
            assignedToMe.isSelected = ListQueries.isAssignedToMe(state)
            if (state.savedQueryId == 0L && (viewsCombo.selectedItem as? SavedView)?.id != 0L) viewsCombo.selectedIndex = 0
            filterBar.setFilters(state.filters)
        } finally {
            syncing = false
        }
    }

    private fun installTableBehaviour() {
        TableSpeedSearch.installOn(table)
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean = openSelected()
        }.installOn(table)
        table.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER && openSelected()) e.consume()
            }
        })
        PopupHandler.installPopupMenu(table, "OpenProject.WorkPackagePopup", "OpenProject.WorkPackageList")

        val header = table.tableHeader
        val original = header.defaultRenderer
        header.defaultRenderer = TableCellRenderer { tbl, value, selected, focused, row, column ->
            val component = original.getTableCellRendererComponent(tbl, value, selected, focused, row, column)
            (component as? JLabel)?.let { label ->
                val id = columnIds.getOrNull(tbl.convertColumnIndexToModel(column))
                val sorts = viewState.value.sortBy
                val index = sorts.indexOfFirst { it.attribute == id }
                label.icon = when {
                    index < 0 -> null
                    sorts[index].ascending -> AllIcons.General.ArrowUp
                    else -> AllIcons.General.ArrowDown
                }
                label.horizontalTextPosition = JLabel.LEFT
                if (index > 0) label.text = "${label.text} ${index + 1}"
                label.toolTipText = if (id != null && metadata.isSortable(id)) OpenProjectBundle.message("list.sort.tooltip") else null
            }
            component
        }
        header.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(e) || e.clickCount != 1 || header.resizingColumn != null) return
                val viewColumn = header.columnAtPoint(e.point).takeIf { it >= 0 } ?: return
                val id = columnIds.getOrNull(table.convertColumnIndexToModel(viewColumn)) ?: return
                if (metadata.isSortable(id)) viewState.update { ListQueries.toggleSort(it, id, additive = e.isShiftDown) }
            }

            override fun mouseReleased(e: MouseEvent) {
                if (columnsDirty) commitColumnLayout()
            }
        })
        table.columnModel.addColumnModelListener(object : TableColumnModelListener {
            override fun columnMoved(e: TableColumnModelEvent) {
                if (!updatingColumns && e.fromIndex != e.toIndex) columnsDirty = true
            }

            override fun columnMarginChanged(e: ChangeEvent) {
                if (!updatingColumns && header.resizingColumn != null) columnsDirty = true
            }

            override fun columnAdded(e: TableColumnModelEvent) = Unit
            override fun columnRemoved(e: TableColumnModelEvent) = Unit
            override fun columnSelectionChanged(e: ListSelectionEvent) = Unit
        })
        PopupHandler.installPopupMenu(header, DefaultActionGroup(
            object : DumbAwareAction(OpenProjectBundle.message("list.column.hide")) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun actionPerformed(e: AnActionEvent) {
                    val point = header.mousePosition ?: return
                    val id = columnIds.getOrNull(table.convertColumnIndexToModel(header.columnAtPoint(point))) ?: return
                    viewState.update { ListQueries.withColumns(it, it.columns - id) }
                }
            },
            object : DumbAwareAction(OpenProjectBundle.message("list.configure")) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun actionPerformed(e: AnActionEvent) = configureView()
            },
        ), "OpenProject.WorkPackageListHeader")
    }

    /** Persists column order and widths after the user dragged or resized header columns. */
    private fun commitColumnLayout() {
        columnsDirty = false
        val order = (0 until table.columnCount).mapNotNull { columnIds.getOrNull(table.convertColumnIndexToModel(it)) }
        val widths = (0 until table.columnCount).associate { view ->
            columnIds[table.convertColumnIndexToModel(view)] to table.columnModel.getColumn(view).width
        }
        viewState.update { it.copy(columns = order, widths = it.widths + widths) }
    }

    private fun rebuildColumns(state: ListViewState) {
        updatingColumns = true
        try {
            columnIds = state.columns
            model.columnInfos = state.columns.map { Column(it, metadata.columnTitle(it)) }.toTypedArray()
            table.setModelAndUpdateColumns(model)
            state.columns.forEachIndexed { index, id ->
                val column = table.columnModel.getColumn(index)
                column.preferredWidth = state.widths[id] ?: defaultWidth(id)
            }
        } finally {
            updatingColumns = false
        }
    }

    private fun defaultWidth(column: String): Int = JBUI.scale(
        when (column) {
            "id" -> 70
            "subject" -> 360
            "percentageDone" -> 60
            else -> 120
        },
    )

    private fun configureView() {
        val dialog = ConfigureViewDialog(project, metadata, viewState.value, pageSizes)
        if (dialog.showAndGet()) viewState.value = dialog.result().copy(savedQueryId = 0)
    }

    private fun openSelected(): Boolean {
        val selected = table.selectedObject ?: return false
        OpenProjectFilesManager.getInstance(project).openWorkPackage(file.serverId, selected.id)
        return true
    }

    private fun locate(workPackageId: Long) {
        val index = model.items.indexOfFirst { it.id == workPackageId }
        if (index >= 0) {
            val row = table.convertRowIndexToView(index)
            table.selectionModel.setSelectionInterval(row, row)
            table.scrollRectToVisible(table.getCellRect(row, 0, true))
            table.requestFocusInWindow()
        } else {
            viewState.update { it.copy(search = "#$workPackageId", page = 1) }
        }
    }

    private fun showTable() {
        if (center.componentCount == 1 && center.getComponent(0) !== status) return
        center.removeAll()
        center.add(ScrollPaneFactory.createScrollPane(table), BorderLayout.CENTER)
        center.revalidate()
        center.repaint()
    }

    private fun showStatus(update: () -> Unit) {
        update()
        if (status.parent != center) {
            center.removeAll()
            center.add(status, BorderLayout.CENTER)
            center.revalidate()
            center.repaint()
        }
    }

    private fun defaultView() = SavedView(0, OpenProjectBundle.message("list.views.default"))

    /** Bold rows and a marker for the work package of the active task. */
    private inner class ActiveAwareRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(table: JTable, value: Any?, selected: Boolean, focused: Boolean, row: Int, column: Int): Component {
            super.getTableCellRendererComponent(table, value, selected, false, row, column)
            val item = model.items.getOrNull(table.convertRowIndexToModel(row))
            val isActive = item != null && item.id == active?.workPackageId
            font = if (isActive) font.deriveFont(Font.BOLD) else font.deriveFont(Font.PLAIN)
            icon = if (isActive && table.convertColumnIndexToModel(column) == 0) AllIcons.Vcs.History else null
            border = JBUI.Borders.empty(0, 6)
            return this
        }
    }

    /** "Working on #12 Subject" with shortcuts, shown while the active task is a work package of this server. */
    private inner class ActiveBanner : JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(10), JBUI.scale(4))) {
        private val label = JBLabel(AllIcons.Vcs.History)

        init {
            background = JBUI.CurrentTheme.Banner.INFO_BACKGROUND
            border = JBUI.Borders.customLineBottom(JBUI.CurrentTheme.Banner.INFO_BORDER_COLOR)
            add(label)
            add(ActionLink(OpenProjectBundle.message("list.active.open")) {
                active?.let { OpenProjectFilesManager.getInstance(project).openWorkPackage(it.serverId, it.workPackageId) }
            })
            add(ActionLink(OpenProjectBundle.message("list.active.log.time")) { e ->
                val action = ActionManager.getInstance().getAction("OpenProject.LogTime")
                ActionManager.getInstance().tryToExecute(action, null, e.source as JComponent, ActionPlaces.EDITOR_TOOLBAR, true)
            })
            add(ActionLink(OpenProjectBundle.message("list.active.locate")) { active?.let { locate(it.workPackageId) } })
            isVisible = false
        }

        fun show(active: ActiveWorkPackage?) {
            isVisible = active != null
            if (active != null) {
                label.text = OpenProjectBundle.message("list.active.banner", active.presentableId, active.summary)
                label.foreground = UIUtil.getLabelForeground()
            }
        }
    }

    @TestOnly
    internal fun loadedWorkPackages(): List<WorkPackage> = model.items

    @TestOnly
    internal fun viewState(): ListViewState = viewState.value

    @TestOnly
    internal fun updateViewState(transform: (ListViewState) -> ListViewState) = viewState.update(transform)

    @TestOnly
    internal fun visibleColumnTitles(): List<String> = (0 until table.columnCount).map { table.getColumnName(it) }

    override fun getComponent(): JComponent = root

    override fun getPreferredFocusedComponent(): JComponent = table

    override fun getName(): String = OpenProjectBundle.message("list.editor.name")
}
