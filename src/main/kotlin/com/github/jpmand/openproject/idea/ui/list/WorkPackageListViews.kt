package com.github.jpmand.openproject.idea.ui.list

import com.github.jpmand.openproject.idea.api.Filters
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XMap

/** Remembers the configuration of each work package list (per tool window tab) in the workspace file (not shared through VCS). */
@Service(Service.Level.PROJECT)
@State(name = "OpenProjectWorkPackageViews", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class WorkPackageListViews : PersistentStateComponent<WorkPackageListViews.ViewsState> {

    class ViewsState {
        @get:XMap
        var views: MutableMap<String, ViewBean> = LinkedHashMap()
    }

    @Tag("view")
    class ViewBean {
        var columns: String = ""
        var widths: String = ""
        var sortBy: String = ""
        var filters: String = ""
        var search: String = ""
        var pageSize: Int = 50
        var savedQueryId: Long = 0
    }

    private var state = ViewsState()

    override fun getState(): ViewsState = state

    override fun loadState(state: ViewsState) {
        this.state = state
    }

    fun contains(key: String): Boolean = key in state.views

    /** The view stored under [key] (the server id for a server's main list, see the tool window tabs). */
    fun get(key: String, defaults: ListViewState = ListViewState()): ListViewState {
        val bean = state.views[key] ?: return defaults
        return ListViewState(
            columns = bean.columns.split(',').map(String::trim).filter(String::isNotEmpty).ifEmpty { ListViewState.DEFAULT_COLUMNS },
            widths = bean.widths.split(',').mapNotNull { entry ->
                val (column, width) = entry.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
                width.toIntOrNull()?.let { column to it }
            }.toMap(),
            sortBy = Filters.sortFromJson(bean.sortBy).ifEmpty { ListViewState.DEFAULT_SORT },
            filters = if (bean.filters.isBlank()) defaults.filters else Filters.fromJson(bean.filters),
            search = bean.search,
            pageSize = bean.pageSize.coerceIn(1, 1000),
            savedQueryId = bean.savedQueryId,
        )
    }

    fun remove(key: String) {
        state.views.remove(key)
    }

    fun put(key: String, view: ListViewState) {
        state.views[key] = ViewBean().apply {
            columns = view.columns.joinToString(",")
            widths = view.widths.entries.joinToString(",") { "${it.key}=${it.value}" }
            sortBy = Filters.sortToJson(view.sortBy)
            filters = Filters.toJson(view.filters)
            search = view.search
            pageSize = view.pageSize
            savedQueryId = view.savedQueryId
        }
    }

    companion object {
        fun getInstance(project: Project): WorkPackageListViews = project.service()
    }
}
