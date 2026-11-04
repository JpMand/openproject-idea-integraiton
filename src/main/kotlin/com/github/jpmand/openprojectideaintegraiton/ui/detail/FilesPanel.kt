package com.github.jpmand.openprojectideaintegraiton.ui.detail

import com.github.jpmand.openprojectideaintegraiton.OpenProjectBundle
import com.github.jpmand.openprojectideaintegraiton.api.model.Attachment
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.table.TableView
import com.intellij.util.text.DateFormatUtil
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import java.awt.BorderLayout
import java.awt.datatransfer.DataFlavor
import java.io.File
import java.time.Instant
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.TransferHandler

/** Attachments of a work package; files can also be dropped onto the table to upload them. */
class FilesPanel(
    private val onUpload: () -> Unit,
    private val onUploadFiles: (List<File>) -> Unit,
    private val onDownload: (Attachment) -> Unit,
    private val onOpen: (Attachment) -> Unit,
    private val onDelete: (Attachment) -> Unit,
) : JPanel(BorderLayout()) {

    private val model = ListTableModel<Attachment>(
        column(OpenProjectBundle.message("detail.files.name")) { it.fileName },
        column(OpenProjectBundle.message("detail.files.size")) { StringUtil.formatFileSize(it.fileSize) },
        column(OpenProjectBundle.message("detail.files.author")) { it.links.title("author").orEmpty() },
        column(OpenProjectBundle.message("detail.files.date")) { attachment ->
            attachment.createdAt?.let { runCatching { DateFormatUtil.formatPrettyDateTime(Instant.parse(it).toEpochMilli()) }.getOrNull() }.orEmpty()
        },
        column(OpenProjectBundle.message("detail.files.description")) { it.description?.raw.orEmpty() },
    )
    private val table = TableView(model).apply {
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        setShowGrid(false)
        emptyText.text = OpenProjectBundle.message("detail.files.empty")
    }
    private var canAttach = false

    init {
        isOpaque = false
        val decorated = ToolbarDecorator.createDecorator(table)
            .setAddAction { onUpload() }
            .setAddActionName(OpenProjectBundle.message("detail.files.upload"))
            .setAddActionUpdater { canAttach }
            .setRemoveAction { table.selectedObject?.let(::confirmDelete) }
            .setRemoveActionUpdater { table.selectedObject?.links?.has("delete") == true }
            .addExtraAction(object : DumbAwareAction(OpenProjectBundle.message("detail.files.download"), null, AllIcons.Actions.Download) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = table.selectedObject != null
                }
                override fun actionPerformed(e: AnActionEvent) {
                    table.selectedObject?.let(onDownload)
                }
            })
            .addExtraAction(object : DumbAwareAction(OpenProjectBundle.message("detail.files.open"), null, AllIcons.Actions.MenuOpen) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = table.selectedObject != null
                }
                override fun actionPerformed(e: AnActionEvent) {
                    table.selectedObject?.let(onOpen)
                }
            })
            .disableUpDownActions()
            .createPanel()
        decorated.preferredSize = JBUI.size(600, 220)
        add(decorated, BorderLayout.CENTER)
        val dropHandler = object : TransferHandler() {
            override fun canImport(support: TransferSupport): Boolean = canAttach && support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)

            override fun importData(support: TransferSupport): Boolean {
                if (!canImport(support)) return false
                @Suppress("UNCHECKED_CAST")
                val files = support.transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<File> ?: return false
                onUploadFiles(files.filter(File::isFile))
                return true
            }
        }
        table.transferHandler = dropHandler
        table.dragEnabled = false
        (table.parent?.parent as? JComponent)?.transferHandler = dropHandler
    }

    fun show(attachments: List<Attachment>, canAttach: Boolean) {
        this.canAttach = canAttach
        model.items = attachments
    }

    private fun confirmDelete(attachment: Attachment) {
        val confirmed = Messages.showYesNoDialog(
            this,
            OpenProjectBundle.message("detail.files.delete.confirm", attachment.fileName),
            OpenProjectBundle.message("detail.files.delete"),
            null,
        ) == Messages.YES
        if (confirmed) onDelete(attachment)
    }

    private fun column(name: String, value: (Attachment) -> String) = object : ColumnInfo<Attachment, String>(name) {
        override fun valueOf(item: Attachment): String = value(item)
    }
}
