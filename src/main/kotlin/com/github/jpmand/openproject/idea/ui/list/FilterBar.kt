package com.github.jpmand.openproject.idea.ui.list

import com.github.jpmand.openproject.idea.OpenProjectBundle
import com.github.jpmand.openproject.idea.api.FilterSpec
import com.github.jpmand.openproject.idea.api.Filters
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.EDT
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.CheckBoxList
import com.intellij.ui.InplaceButton
import com.intellij.ui.ListSpeedSearch
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FlowLayout
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/**
 * OpenProject-style filter rows: "<filter> <operator> <values> ×" plus "+ Add filter". Rows may be incomplete
 * while being edited; only complete filters are reported through [onChange].
 */
class FilterBar(
    private val cs: CoroutineScope,
    private val metadata: () -> ListMetadata,
    /** Loads the selectable values for a filter whose values are resources (statuses, users, ...). */
    private val loadOptions: suspend (filter: FilterMeta, operator: String, elementType: String) -> List<ValueOption>,
    private val onChange: (List<FilterSpec>) -> Unit,
) : JPanel() {

    private var rows: List<FilterSpec> = emptyList()
    private var lastReported: List<FilterSpec> = emptyList()

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(4, 8)
        rebuild()
    }

    /** Shows [filters] unless they are what the bar itself reported last (keeps half-edited rows). */
    fun setFilters(filters: List<FilterSpec>) {
        if (filters == lastReported) return
        rows = filters
        lastReported = filters
        rebuild()
    }

    /** Re-renders with the current metadata (titles, operators) once it is loaded. */
    fun refreshMetadata() = rebuild()

    private fun complete(filter: FilterSpec): Boolean =
        filter.operator in Filters.VALUELESS_OPERATORS || valueType(filter) == null || filter.values.any { it.isNotBlank() }

    private fun valueType(filter: FilterSpec): String? = metadata().filter(filter.name)?.operators?.get(filter.operator)
        ?: if (filter.operator in Filters.VALUELESS_OPERATORS) null else "[]String"

    private fun report() {
        val completed = rows.filter(::complete)
        if (completed != lastReported) {
            lastReported = completed
            onChange(completed)
        }
    }

    private fun update(index: Int, filter: FilterSpec, rebuildRow: Boolean) {
        rows = rows.toMutableList().also { it[index] = filter }
        if (rebuildRow) rebuild()
        report()
    }

    private fun rebuild() {
        removeAll()
        rows.forEachIndexed { index, filter -> add(row(index, filter)) }
        add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            add(ActionLink(OpenProjectBundle.message("filter.add")) { e -> chooseFilter(e.source as JComponent) }.apply {
                icon = AllIcons.General.Add
            })
        })
        revalidate()
        repaint()
    }

    private fun row(index: Int, filter: FilterSpec): JComponent {
        val meta = metadata().filter(filter.name)
        val operators = meta?.operators?.keys?.toList()?.ifEmpty { null } ?: listOf(filter.operator)
        val panel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(2))).apply { isOpaque = false }
        panel.add(JBLabel(meta?.title ?: Labels.filterName(filter.name)).apply { preferredSize = JBUI.size(160, preferredSize.height) })
        val operatorCombo = ComboBox(DefaultComboBoxModel(operators.toTypedArray())).apply {
            renderer = textListCellRenderer { it?.let(Labels::operator) ?: "" }
            selectedItem = filter.operator
            addActionListener {
                val operator = selectedItem as? String ?: return@addActionListener
                if (operator == filter.operator) return@addActionListener
                val keepValues = valueType(filter) == valueType(filter.copy(operator = operator))
                update(index, filter.copy(operator = operator, values = if (keepValues) filter.values else emptyList()), rebuildRow = true)
            }
        }
        panel.add(operatorCombo)
        valueEditor(index, filter, meta)?.let(panel::add)
        panel.add(InplaceButton(OpenProjectBundle.message("filter.remove"), AllIcons.Actions.Close) {
            rows = rows.filterIndexed { i, _ -> i != index }
            rebuild()
            report()
        })
        return panel
    }

    private fun valueEditor(index: Int, filter: FilterSpec, meta: FilterMeta?): JComponent? {
        val type = valueType(filter) ?: return null
        val element = type.substringAfter(']')
        val multi = type.startsWith("[]")
        val count = Regex("""^\[(\d+)]""").find(type)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        return when {
            element in RESOURCE_TYPES || meta?.allowedValues?.get(filter.operator)?.isNotEmpty() == true ->
                if (meta != null) valuesLink(index, filter, meta, element, multi) else textField(index, filter, 1)
            element == "Boolean" -> ComboBox(arrayOf("t", "f")).apply {
                renderer = textListCellRenderer { if (it == "f") OpenProjectBundle.message("filter.no") else OpenProjectBundle.message("filter.yes") }
                selectedItem = filter.values.firstOrNull() ?: "t"
                addActionListener { update(index, filter.copy(values = listOf(selectedItem as String)), rebuildRow = false) }
            }
            else -> textField(index, filter, count, hint = if (element == "Date" || element == "DateTime") "yyyy-mm-dd" else null)
        }
    }

    private fun textField(index: Int, filter: FilterSpec, count: Int, hint: String? = null): JComponent {
        val panel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply { isOpaque = false }
        val fields = (0 until count).map { position ->
            JBTextField(filter.values.getOrNull(position).orEmpty(), if (count > 1) 10 else 18).apply {
                hint?.let { emptyText.text = it }
            }
        }
        fun commit() {
            // A single field may hold several comma-separated values (ids, list custom fields).
            val values = if (count == 1) fields[0].text.split(',') else fields.map { it.text }
            val normalized = values.map(String::trim).filter(String::isNotEmpty)
            val current = rows.getOrNull(index) ?: return
            if (normalized != current.values) update(index, current.copy(values = normalized), rebuildRow = false)
        }
        fields.forEach { field ->
            field.addActionListener { commit() }
            field.addFocusListener(object : FocusAdapter() {
                override fun focusLost(e: FocusEvent) = commit()
            })
            panel.add(field)
        }
        return panel
    }

    private fun valuesLink(index: Int, filter: FilterSpec, meta: FilterMeta, element: String, multi: Boolean): JComponent {
        val link = ActionLink(describe(filter.values, emptyList())) {}
        // Show titles instead of ids once the options are loaded.
        cs.launch {
            val options = runCatching { loadOptions(meta, filter.operator, element) }.getOrNull() ?: return@launch
            withContext(Dispatchers.EDT) { link.text = describe(filter.values, options) }
        }
        link.addActionListener {
            cs.launch {
                val options = try {
                    loadOptions(meta, filter.operator, element)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    emptyList()
                }
                withContext(Dispatchers.EDT) { chooseValues(link, index, options, multi) }
            }
        }
        return link
    }

    private fun describe(values: List<String>, options: List<ValueOption>): String = when {
        values.isEmpty() -> OpenProjectBundle.message("filter.choose")
        else -> values.joinToString(", ") { value -> options.firstOrNull { it.id == value }?.title ?: if (value == "me") OpenProjectBundle.message("filter.me") else "#$value" }
    }

    private fun chooseValues(anchor: JComponent, index: Int, options: List<ValueOption>, multi: Boolean) {
        val current = rows.getOrNull(index) ?: return
        if (!multi) {
            JBPopupFactory.getInstance().createPopupChooserBuilder(options)
                .setRenderer(textListCellRenderer { it?.title ?: "" })
                .setNamerForFiltering { it.title }
                .setItemChosenCallback { update(index, current.copy(values = listOf(it.id)), rebuildRow = true) }
                .createPopup()
                .showUnderneathOf(anchor)
            return
        }
        val list = CheckBoxList<ValueOption>()
        options.forEach { list.addItem(it, it.title, it.id in current.values) }
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        ListSpeedSearch.installOn(list) { it.text }
        JBPopupFactory.getInstance()
            .createComponentPopupBuilder(ScrollPaneFactory.createScrollPane(list).apply { preferredSize = JBUI.size(280, 300) }, list)
            .setRequestFocus(true)
            .setResizable(true)
            .setCancelOnClickOutside(true)
            .setCancelCallback {
                val selected = options.filter { list.isItemSelected(it) }.map { it.id }
                if (selected != current.values) update(index, current.copy(values = selected), rebuildRow = true)
                true
            }
            .createPopup()
            .showUnderneathOf(anchor)
    }

    private fun chooseFilter(anchor: JComponent) {
        val used = rows.map { it.name }.toSet()
        val available = metadata().addableFilters.filter { it.id !in used }
        if (available.isEmpty()) return
        JBPopupFactory.getInstance().createPopupChooserBuilder(available)
            .setRenderer(textListCellRenderer { it?.title ?: "" })
            .setNamerForFiltering { it.title }
            .setItemChosenCallback { meta ->
                val operator = meta.operators.keys.firstOrNull() ?: "="
                val defaults = if (meta.operators[operator]?.endsWith("Boolean") == true) listOf("t") else emptyList()
                rows = rows + FilterSpec(meta.id, operator, defaults)
                rebuild()
                report()
            }
            .createPopup()
            .showUnderneathOf(anchor)
    }

    companion object {
        val RESOURCE_TYPES = setOf("Status", "Type", "Priority", "User", "Principal", "Group", "PlaceholderUser", "Project", "Version", "CustomOption", "Category")
    }
}
