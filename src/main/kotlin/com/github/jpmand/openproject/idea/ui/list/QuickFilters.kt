package com.github.jpmand.openproject.idea.ui.list

import com.github.jpmand.openproject.idea.OpenProjectBundle
import com.github.jpmand.openproject.idea.api.FilterSpec
import com.github.jpmand.openproject.idea.api.Filters
import com.github.jpmand.openproject.idea.ui.common.swatchListRenderer
import com.intellij.openapi.application.EDT
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FlowLayout
import javax.swing.JPanel

/** One entry of a quick filter dropdown; a null [filter] removes the filter ("Any"). [color]: e.g. a status color. */
data class QuickChoice(val title: String, val filter: FilterSpec?, val color: java.awt.Color? = null)

/** A dropdown above the table that sets one filter ([name]) to a single value, like the issue trackers' filter rows. */
data class QuickFilterDef(
    val name: String,
    val label: String,
    /** Choices offered before the server's values. The first one is shown when the filter is not set. */
    val specials: List<QuickChoice>,
    /** Resource type of the values (`Status`, `User`, ...), loaded from the server. */
    val elementType: String,
) {
    /** The choice matching [filters] (the filters named [name]), or null when they are something else (custom). */
    fun selected(filters: List<FilterSpec>): QuickChoice? = when {
        filters.isEmpty() -> specials.firstOrNull { it.filter == null }
        filters.size > 1 -> null
        else -> specials.firstOrNull { it.filter == filters[0] }
    }

    fun valueChoice(option: ValueOption) = QuickChoice(option.title, FilterSpec(name, "=", listOf(option.id)), option.color)

    companion object {
        private fun message(key: String) = OpenProjectBundle.message(key)

        val PROJECT = QuickFilterDef("project", message("quick.project"), listOf(QuickChoice(message("quick.project.all"), null)), "Project")
        val TYPE = QuickFilterDef("type", message("quick.type"), listOf(QuickChoice(message("quick.any"), null)), "Type")
        val STATUS = QuickFilterDef(
            "status", message("quick.status"),
            listOf(
                QuickChoice(message("list.status.open"), Filters.openStatus()),
                QuickChoice(message("list.status.closed"), Filters.closedStatus()),
                QuickChoice(message("list.status.all"), null),
            ),
            "Status",
        )
        val PRIORITY = QuickFilterDef("priority", message("quick.priority"), listOf(QuickChoice(message("quick.any"), null)), "Priority")
        val ASSIGNEE = QuickFilterDef(
            "assignee", message("quick.assignee"),
            listOf(
                QuickChoice(message("quick.anyone"), null),
                QuickChoice(message("filter.me"), FilterSpec("assignee", "=", listOf("me"))),
                QuickChoice(message("quick.unassigned"), FilterSpec("assignee", "!*")),
            ),
            "User",
        )

        val ALL = listOf(PROJECT, TYPE, STATUS, PRIORITY, ASSIGNEE)
    }
}

/** "Type: Bug ▾": shows the current value of a quick filter and lets the user pick another one. */
class QuickFilterLink(
    private val def: QuickFilterDef,
    private val cs: CoroutineScope,
    private val loadOptions: suspend (elementType: String) -> List<ValueOption>,
    private val onChange: (FilterSpec?) -> Unit,
) : JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)) {

    private val link = ActionLink("") { choose() }.apply { setDropDownLinkIcon() }
    private var current: List<FilterSpec> = emptyList()
    private var options: List<ValueOption>? = null

    init {
        isOpaque = false
        add(JBLabel(def.label).apply {
            foreground = UIUtil.getContextHelpForeground()
            border = JBUI.Borders.emptyRight(4)
        })
        add(link)
        render()
    }

    /** Shows the filters named after this quick filter. */
    fun show(filters: List<FilterSpec>) {
        val own = filters.filter { it.name == def.name }
        if (own == current && link.text.isNotEmpty()) return
        current = own
        render()
        // Value ids ("12") are shown with their titles once known.
        if (options == null && own.any { filter -> filter.values.any { it != "me" } }) {
            cs.launch {
                val loaded = runCatching { loadOptions(def.elementType) }.getOrNull() ?: return@launch
                withContext(Dispatchers.EDT) {
                    options = loaded
                    render()
                }
            }
        }
    }

    fun text(): String = link.text

    private fun render() {
        link.text = def.selected(current)?.title ?: describe(current)
        link.toolTipText = "${def.label} ${link.text}"
    }

    private fun describe(filters: List<FilterSpec>): String = filters.joinToString("; ") { filter ->
        val values = filter.values.joinToString(", ") { value ->
            options?.firstOrNull { it.id == value }?.title ?: if (value == "me") OpenProjectBundle.message("filter.me") else "#$value"
        }
        val text = if (filter.operator == "=") values else "${Labels.operator(filter.operator)} $values".trim()
        text.ifEmpty { Labels.operator(filter.operator) }
    }.let { if (it.length > 40) it.take(37) + "…" else it }

    private fun choose() {
        cs.launch {
            val loaded = try {
                loadOptions(def.elementType)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            withContext(Dispatchers.EDT) {
                options = loaded
                val specialIds = def.specials.flatMap { it.filter?.values.orEmpty() }.toSet()
                val choices = def.specials + loaded.filter { it.id !in specialIds }.map(def::valueChoice)
                val selected = def.selected(current) ?: choices.firstOrNull { current.singleOrNull() == it.filter }
                JBPopupFactory.getInstance().createPopupChooserBuilder(choices)
                    .setRenderer(swatchListRenderer<QuickChoice>({ it.title }, { it.color }))
                    .setNamerForFiltering { it.title }
                    .also { builder -> selected?.let { builder.setSelectedValue(it, true) } }
                    .setItemChosenCallback { choice ->
                        current = listOfNotNull(choice.filter)
                        render()
                        onChange(choice.filter)
                    }
                    .createPopup()
                    .showUnderneathOf(link)
            }
        }
    }
}
