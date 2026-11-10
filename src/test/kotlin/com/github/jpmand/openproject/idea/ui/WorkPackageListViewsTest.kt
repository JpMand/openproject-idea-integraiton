package com.github.jpmand.openproject.idea.ui

import com.github.jpmand.openproject.idea.api.FilterSpec
import com.github.jpmand.openproject.idea.api.SortSpec
import com.github.jpmand.openproject.idea.ui.list.ListViewState
import com.github.jpmand.openproject.idea.ui.list.WorkPackageListViews
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.xmlb.XmlSerializer

class WorkPackageListViewsTest : BasePlatformTestCase() {

    fun `test views survive serialization`() {
        val views = WorkPackageListViews()
        val view = ListViewState(
            columns = listOf("id", "subject", "customField3"),
            widths = mapOf("subject" to 400),
            sortBy = listOf(SortSpec("status"), SortSpec("updatedAt", false)),
            filters = listOf(FilterSpec("status", "o"), FilterSpec("assignee", "=", listOf("me"))),
            search = "login",
            page = 3,
            pageSize = 100,
            savedQueryId = 12,
        )
        views.put("https://op.example.com", view)

        val element = XmlSerializer.serialize(views.state)
        val restored = WorkPackageListViews()
        restored.loadState(XmlSerializer.deserialize(element, WorkPackageListViews.ViewsState::class.java))

        // the page is not persisted
        assertEquals(view.copy(page = 1), restored.get("https://op.example.com"))
        assertEquals(ListViewState(), restored.get("https://other.example.com"))
        assertEquals(ListViewState.defaults(4), restored.get("https://other.example.com#tab", ListViewState.defaults(4)))
        restored.remove("https://op.example.com")
        assertFalse(restored.contains("https://op.example.com"))
    }
}
