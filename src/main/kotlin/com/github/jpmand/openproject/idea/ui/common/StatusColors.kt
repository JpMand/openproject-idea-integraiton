package com.github.jpmand.openproject.idea.ui.common

import com.github.jpmand.openproject.idea.api.model.Status
import com.github.jpmand.openproject.idea.api.model.WorkPackage
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.ColorIcon
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon
import javax.swing.JList
import javax.swing.ListCellRenderer

/** The colors OpenProject assigns to statuses (`Status.color`, e.g. `#C3FAE8`) and readable text on them. */
object StatusColors {
    private val DARK_TEXT = Color(0x1E1F22)

    /** Parses `#RRGGBB` / `#RGB`; null when unset or malformed. */
    fun parse(hex: String?): Color? {
        val digits = hex?.trim()?.removePrefix("#") ?: return null
        val full = when (digits.length) {
            3 -> digits.map { "$it$it" }.joinToString("")
            6 -> digits
            else -> return null
        }
        return full.toIntOrNull(16)?.let(::Color)
    }

    /** Status id -> color, for the statuses that have one. */
    fun of(statuses: List<Status>): Map<Long, Color> = statuses.mapNotNull { status -> parse(status.color)?.let { status.id to it } }.toMap()

    /** The color of a work package's status in [colors]. */
    fun of(workPackage: WorkPackage, colors: Map<Long, Color>): Color? =
        workPackage.links["status"]?.id?.toLongOrNull()?.let(colors::get)

    /** Dark text on light colors, white text on dark ones. */
    fun textOn(background: Color): Color = if (luminance(background) > 0.5) DARK_TEXT else Color.WHITE

    fun swatch(color: Color?): Icon? = color?.let { ColorIcon(JBUI.scale(12), it, true) }

    /** Relative luminance (WCAG). */
    fun luminance(color: Color): Double {
        fun channel(value: Int): Double {
            val c = value / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }
}

/** A status as a rounded colored badge, like OpenProject's status bubble. Without a color it is a plain label. */
class StatusBadge(text: String = "", color: Color? = null) : JBLabel(text) {

    var color: Color? = color
        set(value) {
            field = value
            updateColors()
            repaint()
        }

    init {
        border = JBUI.Borders.empty(1, 8)
        updateColors()
    }

    private fun updateColors() {
        isOpaque = false
        foreground = color?.let(StatusColors::textOn) ?: UIUtil.getLabelForeground()
    }

    /** Theme changes reset the foreground; also runs from the JLabel constructor, while [color] is still null. */
    override fun updateUI() {
        super.updateUI()
        updateColors()
    }

    override fun paintComponent(g: Graphics) {
        val color = color
        if (color != null) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = color
                val arc = JBUI.scale(10)
                g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
            } finally {
                g2.dispose()
            }
        }
        super.paintComponent(g)
    }
}

/** List rows "■ title" with an optional color swatch (statuses in choosers and filter dropdowns). */
fun <T> swatchListRenderer(text: (T) -> String, color: (T) -> Color?): ListCellRenderer<T> = object : ColoredListCellRenderer<T>() {
    override fun customizeCellRenderer(list: JList<out T>, value: T?, index: Int, selected: Boolean, hasFocus: Boolean) {
        if (value == null) return
        icon = StatusColors.swatch(color(value))
        append(text(value))
    }
}
