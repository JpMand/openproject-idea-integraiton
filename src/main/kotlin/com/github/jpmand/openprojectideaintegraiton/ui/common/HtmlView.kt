package com.github.jpmand.openprojectideaintegraiton.ui.common

import com.intellij.ide.BrowserUtil
import com.intellij.ui.HyperlinkAdapter
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import javax.swing.JEditorPane
import javax.swing.event.HyperlinkEvent

/**
 * Read-only view of server-rendered HTML (descriptions, comments, activity details). Links to work packages of
 * the same server are handed to [openWorkPackage]; everything else opens in the browser.
 */
class HtmlView(
    private val baseUrl: () -> String?,
    private val openWorkPackage: (Long) -> Unit,
) : JEditorPane() {

    init {
        editorKit = HTMLEditorKitBuilder().withWordWrapViewFactory().build()
        isEditable = false
        isOpaque = false
        border = JBUI.Borders.empty()
        putClientProperty(HONOR_DISPLAY_PROPERTIES, true)
        font = UIUtil.getLabelFont()
        addHyperlinkListener(object : HyperlinkAdapter() {
            override fun hyperlinkActivated(e: HyperlinkEvent) {
                open(e.description ?: e.url?.toString() ?: return)
            }
        })
    }

    fun setHtml(html: String?) {
        text = "<html><body>${html.orEmpty()}</body></html>"
        caretPosition = 0
    }

    private fun open(href: String) {
        val base = baseUrl()
        val absolute = when {
            href.startsWith("/") && base != null -> base.substringBefore("://") + "://" + base.substringAfter("://").substringBefore('/') + href
            else -> href
        }
        workPackageId(absolute, base)?.let { return openWorkPackage(it) }
        BrowserUtil.browse(absolute)
    }

    companion object {
        private val workPackageLink = Regex("""/work_packages/(\d+)(?:[/?#].*)?$""")

        fun workPackageId(url: String, baseUrl: String?): Long? {
            if (baseUrl == null || !url.startsWith(baseUrl)) return null
            return workPackageLink.find(url.removePrefix(baseUrl))?.groupValues?.get(1)?.toLongOrNull()
        }
    }
}
