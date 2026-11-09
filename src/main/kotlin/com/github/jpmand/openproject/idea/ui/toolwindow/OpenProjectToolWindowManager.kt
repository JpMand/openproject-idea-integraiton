package com.github.jpmand.openproject.idea.ui.toolwindow

import com.github.jpmand.openproject.idea.OpenProjectBundle
import com.github.jpmand.openproject.idea.core.OpenProjectServers
import com.github.jpmand.openproject.idea.ui.detail.WorkPackageDetailPanel
import com.github.jpmand.openproject.idea.ui.list.ListViewState
import com.github.jpmand.openproject.idea.ui.list.WorkPackageListPanel
import com.github.jpmand.openproject.idea.ui.list.WorkPackageListViews
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowEx
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XCollection
import org.jetbrains.annotations.TestOnly
import java.awt.BorderLayout
import java.util.UUID
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The tabs of the OpenProject tool window: the main work package list (always first), further lists ("+" or
 * "Open as New Tab") and work package tabs. Open tabs and their list views are remembered in the workspace.
 */
@Service(Service.Level.PROJECT)
@State(name = "OpenProjectToolWindow", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class OpenProjectToolWindowManager(private val project: Project) : PersistentStateComponent<OpenProjectToolWindowManager.TabsState> {

    class TabsState {
        @get:XCollection
        var tabs: MutableList<TabBean> = ArrayList()
        var selected: Int = 0
    }

    @Tag("tab")
    class TabBean {
        /** `list` or `workPackage`. */
        var kind: String = LIST
        var serverId: String? = null
        var workPackageId: Long = 0
        var viewKey: String? = null
        var title: String? = null
    }

    /** What a tab shows; kept on its [Content]. */
    data class TabInfo(
        val kind: String,
        val serverId: String?,
        val workPackageId: Long = 0,
        val viewKey: String? = null,
        val main: Boolean = false,
    )

    private var state = TabsState()
    private var restoring = false

    override fun getState(): TabsState = state

    override fun loadState(state: TabsState) {
        this.state = state
    }

    private fun toolWindow(): ToolWindow? = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)

    /** Called once by the tool window factory: the main list and the tabs open before the IDE was closed. */
    fun install(toolWindow: ToolWindow) {
        (toolWindow as? ToolWindowEx)?.setTabActions(ActionManager.getInstance().getAction("OpenProject.NewListTab"))
        val contents = toolWindow.contentManager
        restoring = true
        try {
            contents.addContent(listContent(TabInfo(LIST, null, main = true), OpenProjectBundle.message("toolwindow.tab.list")))
            val saved = state.tabs.toList()
            saved.forEach { bean ->
                when (bean.kind) {
                    LIST -> bean.serverId?.let { contents.addContent(listContent(TabInfo(LIST, it, viewKey = bean.viewKey), bean.title ?: listTitle(null))) }
                    WORK_PACKAGE -> bean.serverId?.takeIf { bean.workPackageId > 0 }?.let {
                        contents.addContent(workPackageContent(it, bean.workPackageId, bean.title))
                    }
                }
            }
            contents.getContent(state.selected.coerceIn(0, contents.contentCount - 1))?.let { contents.setSelectedContent(it) }
        } finally {
            restoring = false
        }
        realize(contents.selectedContent)
        contents.addContentManagerListener(object : ContentManagerListener {
            override fun contentAdded(event: ContentManagerEvent) = save()

            override fun contentRemoved(event: ContentManagerEvent) {
                // Contents also go away when the project closes; only tabs the user closes forget their list view.
                if (contents.isDisposed || project.isDisposed) return
                val info = info(event.content)
                if (info?.kind == LIST && info.viewKey != null) WorkPackageListViews.getInstance(project).remove(info.viewKey)
                save()
            }

            override fun selectionChanged(event: ContentManagerEvent) {
                if (event.operation == ContentManagerEvent.ContentOperation.add) realize(event.content)
                save()
            }
        })
    }

    /** Shows the tool window with the main work package list. */
    fun showList() {
        val toolWindow = toolWindow() ?: return
        val contents = toolWindow.contentManager
        contents.contents.firstOrNull { info(it)?.main == true }?.let { contents.setSelectedContent(it) }
        toolWindow.activate(null)
    }

    /**
     * Opens a new list tab of [serverId] showing [view] ("Open as New Tab"), or a new default list ("+": [view] null).
     */
    fun openList(serverId: String, view: ListViewState? = null, title: String? = null) {
        val toolWindow = toolWindow() ?: return
        val viewKey = "$serverId#${UUID.randomUUID()}"
        val views = WorkPackageListViews.getInstance(project)
        val initial = view ?: run {
            // A fresh list keeps the column layout of the server's main list.
            val main = views.get(serverId, ListViewState())
            val projectId = OpenProjectServers.getInstance(project).find(serverId)?.repository?.projectId ?: 0
            ListViewState.defaults(projectId).copy(columns = main.columns, widths = main.widths, sortBy = main.sortBy, pageSize = main.pageSize)
        }
        views.put(viewKey, initial)
        val content = listContent(TabInfo(LIST, serverId, viewKey = viewKey), title ?: listTitle(toolWindow))
        toolWindow.contentManager.addContent(content)
        toolWindow.contentManager.setSelectedContent(content, true)
        toolWindow.activate(null)
    }

    /** "+": a new list, asking for the server when several are configured. */
    fun newListTab(anchor: JComponent?) {
        val servers = OpenProjectServers.getInstance(project).servers()
        when (servers.size) {
            0 -> OpenProjectServers.getInstance(project).openServerSettings()
            1 -> openList(servers.single().id)
            else -> {
                val popup = JBPopupFactory.getInstance().createListPopup(object : BaseListPopupStep<String>(
                    OpenProjectBundle.message("action.choose.server"), servers.map { it.id },
                ) {
                    override fun getTextFor(value: String) = servers.first { it.id == value }.displayName
                    override fun onChosen(selectedValue: String, finalChoice: Boolean): PopupStep<*>? {
                        openList(selectedValue)
                        return FINAL_CHOICE
                    }
                })
                if (anchor != null) popup.showUnderneathOf(anchor) else popup.showCenteredInCurrentWindow(project)
            }
        }
    }

    /** Opens (or selects) the tab of a work package. */
    fun openWorkPackage(serverId: String, workPackageId: Long, focus: Boolean = true) {
        val toolWindow = toolWindow() ?: return
        val contents = toolWindow.contentManager
        val content = contents.contents.firstOrNull { content ->
            info(content)?.let { it.kind == WORK_PACKAGE && it.serverId == serverId && it.workPackageId == workPackageId } == true
        } ?: workPackageContent(serverId, workPackageId, null).also(contents::addContent)
        contents.setSelectedContent(content, focus)
        if (focus) toolWindow.activate(null) else toolWindow.show(null)
    }

    private fun listContent(info: TabInfo, title: String): Content =
        content(LazyTab(info) { WorkPackageListPanel(project, info.serverId, info.viewKey) }, title).apply {
            isCloseable = !info.main
            isPinnable = false
        }

    private fun workPackageContent(serverId: String, workPackageId: Long, title: String?): Content {
        val info = TabInfo(WORK_PACKAGE, serverId, workPackageId)
        lateinit var content: Content
        content = content(LazyTab(info) {
            WorkPackageDetailPanel(project, serverId, workPackageId, WorkPackageDetailPanel.Layout.TAB, onTitle = { text ->
                content.displayName = if (text.length > TITLE_LENGTH) text.take(TITLE_LENGTH - 1) + "…" else text
                content.description = text
                save()
            })
        }, title ?: "#$workPackageId")
        return content
    }

    private fun content(tab: LazyTab, title: String): Content =
        ContentFactory.getInstance().createContent(tab, title, false).apply {
            setDisposer(tab)
            preferredFocusableComponent = tab
        }

    private fun info(content: Content): TabInfo? = (content.component as? LazyTab)?.info

    /** Creates the panel of a tab when it is first shown, so restored tabs do not all load at once. */
    private fun realize(content: Content?) {
        (content?.component as? LazyTab)?.realize()
    }

    private fun listTitle(toolWindow: ToolWindow?): String {
        val count = (toolWindow ?: toolWindow())?.contentManager?.contents?.count { info(it)?.kind == LIST } ?: 1
        return OpenProjectBundle.message("toolwindow.tab.list.numbered", count + 1)
    }

    private fun save() {
        if (restoring) return
        val contents = toolWindow()?.contentManager ?: return
        if (contents.isDisposed) return
        state.tabs = contents.contents.mapNotNull { content ->
            val info = info(content) ?: return@mapNotNull null
            if (info.main) return@mapNotNull null
            TabBean().apply {
                kind = info.kind
                serverId = info.serverId
                workPackageId = info.workPackageId
                viewKey = info.viewKey
                title = content.description ?: content.displayName
            }
        }.toMutableList()
        state.selected = contents.selectedContent?.let(contents::getIndexOfContent)?.coerceAtLeast(0) ?: 0
    }

    /** The panel of a tab, created on first [realize]. */
    private class LazyTab(val info: TabInfo, private val create: () -> JComponent) : JPanel(BorderLayout()), Disposable {
        private var panel: JComponent? = null

        fun realize(): JComponent = panel ?: create().also { created ->
            panel = created
            (created as? Disposable)?.let { Disposer.register(this, it) }
            add(created, BorderLayout.CENTER)
            revalidate()
        }

        override fun addNotify() {
            super.addNotify()
            realize()
        }

        override fun dispose() = Unit
    }

    @TestOnly
    internal fun tabs(): List<TabInfo> = toolWindow()?.contentManager?.contents?.mapNotNull(::info).orEmpty()

    @TestOnly
    internal fun selectedPanel(): JComponent? = (toolWindow()?.contentManager?.selectedContent?.component as? LazyTab)?.realize()

    companion object {
        const val TOOL_WINDOW_ID = "OpenProject"
        const val LIST = "list"
        const val WORK_PACKAGE = "workPackage"
        private const val TITLE_LENGTH = 40

        fun getInstance(project: Project): OpenProjectToolWindowManager = project.service()
    }
}
