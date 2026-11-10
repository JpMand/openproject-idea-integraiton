package com.github.jpmand.openproject.idea.ui.detail

import com.github.jpmand.openproject.idea.OpenProjectBundle
import com.github.jpmand.openproject.idea.actions.notifyError
import com.github.jpmand.openproject.idea.api.hal.HalLink
import com.github.jpmand.openproject.idea.api.model.Attachment
import com.github.jpmand.openproject.idea.api.model.WorkPackage
import com.github.jpmand.openproject.idea.core.ActiveWorkPackageTracker
import com.github.jpmand.openproject.idea.core.OpenProjectProjectScope
import com.github.jpmand.openproject.idea.core.describeError
import com.github.jpmand.openproject.idea.ui.OpenProjectDataKeys
import com.github.jpmand.openproject.idea.ui.common.HtmlView
import com.github.jpmand.openproject.idea.ui.common.MarkdownField
import com.github.jpmand.openproject.idea.ui.common.StatusBadge
import com.github.jpmand.openproject.idea.ui.common.StatusPanel
import com.github.jpmand.openproject.idea.ui.common.swatchListRenderer
import com.github.jpmand.openproject.idea.ui.list.WorkPackageCells
import com.github.jpmand.openproject.idea.ui.toolwindow.OpenProjectToolWindowManager
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.InplaceButton
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.WrapLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.TestOnly
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.net.URLConnection
import java.util.concurrent.ConcurrentHashMap
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

/**
 * One work package: editable subject, status, description and attributes, plus its activity (with a comment box),
 * relations and files. [Layout.PREVIEW] stacks everything vertically (the preview next to the work package list);
 * [Layout.TAB] puts subject, description and properties on the left and the activity/relations/files tabs on the
 * right (a tool window tab of its own).
 */
class WorkPackageDetailPanel(
    private val project: Project,
    val serverId: String,
    val workPackageId: Long,
    private val layout: Layout,
    /** Called with "#12 Subject" whenever the work package is (re)loaded. */
    private val onTitle: (String) -> Unit = {},
    /** Shows a close button in the toolbar (the preview). */
    private val onClose: (() -> Unit)? = null,
) : JPanel(BorderLayout()), Disposable {

    enum class Layout { PREVIEW, TAB }

    private val cs = OpenProjectProjectScope.getInstance(project).childScope("WorkPackageDetailPanel")

    val controller = WorkPackageDetailController(project, serverId, workPackageId)

    private var loadJob: Job? = null
    private var editingDescription = false
    private var internalCommentsAvailable = false
    private val imageDirectory by lazy { FileUtil.createTempDirectory("openproject-images", null, true) }
    private val images = ConcurrentHashMap<String, File>()

    private val status = StatusPanel()
    private val content = JPanel(BorderLayout())

    // header
    private val idLabel = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val subjectLabel = JBLabel().apply { font = JBFont.h2().asBold(); border = JBUI.Borders.emptyRight(6) }
    private val subjectEdit = InplaceButton(OpenProjectBundle.message("detail.subject.edit"), AllIcons.Actions.Edit) { editSubject() }
    private val subjectRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { isOpaque = false }
    private val statusBadge = StatusBadge().apply {
        icon = AllIcons.General.ChevronDown
        horizontalTextPosition = SwingConstants.LEFT
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (controller.details.value?.canEdit == true && SwingUtilities.isLeftMouseButton(e)) chooseStatus(this@apply)
            }
        })
    }
    private val attributeLine = JBLabel()
    private val activeBadge = JBLabel(OpenProjectBundle.message("detail.active.task"), AllIcons.Vcs.History, JBLabel.LEFT).apply {
        foreground = JBUI.CurrentTheme.Link.Foreground.ENABLED
        isVisible = false
    }

    // description
    private val descriptionView = html()
    private val descriptionEditLink = ActionLink(OpenProjectBundle.message("detail.description.edit")) { editDescription() }
    private val descriptionPanel = JPanel(BorderLayout()).apply { isOpaque = false }

    // tabs and properties
    private val activityPanel = ActivityPanel(project, ::html) { text, internal, done -> comment(text, internal, done) }
    private val relationsPanel = RelationsPanel(
        onOpen = { id -> OpenProjectToolWindowManager.getInstance(project).openWorkPackage(serverId, id) },
        onDelete = { relation -> perform { controller.deleteRelation(relation) } },
        onAdd = ::addRelation,
    )
    private val filesPanel = FilesPanel(
        onUpload = ::chooseAndUpload,
        onUploadFiles = ::upload,
        onDownload = ::download,
        onOpen = ::openAttachment,
        onDelete = { attachment -> perform { controller.deleteAttachment(attachment) } },
    )
    private val tabs = JBTabbedPane()
    private val properties = PropertiesPanel(::chooseAssignee, ::choosePriority)

    init {
        add(UiDataProvider.wrapComponent(JPanel(BorderLayout()).apply {
            add(toolbar(), BorderLayout.NORTH)
            add(content, BorderLayout.CENTER)
        }) { sink ->
            sink[OpenProjectDataKeys.WORK_PACKAGE] = controller.details.value?.ref
            sink[OpenProjectDataKeys.REFRESHER] = Runnable { reload() }
        }, BorderLayout.CENTER)
    }

    init {
        content.add(status, BorderLayout.CENTER)
        tabs.addTab(OpenProjectBundle.message("detail.tab.activity"), scrolled(activityPanel))
        tabs.addTab(OpenProjectBundle.message("detail.tab.relations"), scrolled(relationsPanel))
        tabs.addTab(OpenProjectBundle.message("detail.tab.files"), filesPanel.apply { border = JBUI.Borders.empty(8, 12) })
        reload()
        cs.launch {
            controller.details.collect { details ->
                if (details != null) withContext(Dispatchers.EDT) { show(details) }
            }
        }
        cs.launch {
            ActiveWorkPackageTracker.getInstance(project).active.collect { active ->
                withContext(Dispatchers.EDT) {
                    activeBadge.isVisible = active?.serverId == serverId && active.workPackageId == workPackageId
                }
            }
        }
    }

    fun reload() {
        loadJob?.cancel()
        loadJob = cs.launch {
            if (controller.details.value == null) withContext(Dispatchers.EDT) { status.loading() }
            try {
                controller.load()
                internalCommentsAvailable = runCatching { "internal_comments" in controller.connection().configuration().availableFeatures }.getOrDefault(false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.EDT) {
                    if (controller.details.value == null) {
                        content.removeAll()
                        content.add(status, BorderLayout.CENTER)
                        status.error(project, e) { reload() }
                        content.revalidate()
                    } else {
                        notifyError(project, describeError(e), e)
                    }
                }
            }
        }
    }

    // ---- rendering ----

    private fun show(details: WorkPackageDetails) {
        val workPackage = details.workPackage
        onTitle("${workPackage.presentableId} ${workPackage.subject}")
        if (content.getComponent(0) === status) {
            content.removeAll()
            content.add(page(), BorderLayout.CENTER)
        }
        idLabel.text = "${workPackage.typeTitle.orEmpty()}  ${workPackage.presentableId}".trim()
        showSubject(workPackage)
        statusBadge.text = workPackage.statusTitle ?: "?"
        statusBadge.color = details.statusColor
        statusBadge.icon = if (details.canEdit) AllIcons.General.ChevronDown else null
        statusBadge.cursor = if (details.canEdit) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
        statusBadge.toolTipText = if (details.canEdit) OpenProjectBundle.message("detail.status.change") else null
        attributeLine.text = listOf("assignee", "priority", "project", "updatedAt")
            .mapNotNull { WorkPackageCells.text(workPackage, it).takeIf(String::isNotBlank) }
            .joinToString("  ·  ")
        if (!editingDescription) showDescription(workPackage)
        descriptionEditLink.isVisible = details.canEdit && !editingDescription
        activityPanel.show(details.activities, details.userNames, details.canComment, internalCommentsAvailable)
        relationsPanel.show(workPackage, details.relations, details.canRelate)
        filesPanel.show(details.attachments, details.canAttach)
        tabs.setTitleAt(0, OpenProjectBundle.message("detail.tab.activity.count", details.activities.count { it.isComment }))
        tabs.setTitleAt(1, OpenProjectBundle.message("detail.tab.relations.count", RelationsPanel.related(workPackage, details.relations).size))
        tabs.setTitleAt(2, OpenProjectBundle.message("detail.tab.files.count", details.attachments.size))
        properties.show(details)
        content.revalidate()
        content.repaint()
    }

    private fun page(): JComponent {
        val main = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(10, 14)
            add(idLabel)
            add(subjectRow)
            add(row(JBLabel(OpenProjectBundle.message("detail.status")), statusBadge, attributeLine, activeBadge).apply {
                border = JBUI.Borders.emptyTop(4)
            })
            add(row(JBLabel(OpenProjectBundle.message("detail.description")).apply { font = JBFont.h4().asBold() }, descriptionEditLink).apply {
                border = JBUI.Borders.emptyTop(14)
            })
            add(descriptionPanel)
            add(properties.apply { border = JBUI.Borders.emptyTop(14) })
        }
        main.components.forEach { (it as? JComponent)?.alignmentX = 0f }
        // Top-aligned: the column keeps its height instead of spreading over the visible area.
        val left = JBScrollPane(JPanel(BorderLayout()).apply { add(main, BorderLayout.NORTH) }).apply { border = JBUI.Borders.empty() }
        return when (layout) {
            Layout.PREVIEW -> OnePixelSplitter(true, "OpenProject.Preview.Split", 0.55f).apply {
                firstComponent = left
                secondComponent = tabs
            }
            Layout.TAB -> OnePixelSplitter(false, "OpenProject.WorkPackageTab.Split", 0.55f).apply {
                firstComponent = left
                secondComponent = tabs
            }
        }
    }

    private fun scrolled(component: JComponent): JComponent =
        JBScrollPane(JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8, 12)
            add(component, BorderLayout.NORTH)
        }).apply { border = JBUI.Borders.empty() }

    /** Left-aligned components with a gap after each (no leading gap, unlike FlowLayout's hgap); wraps when narrow. */
    private fun row(vararg components: JComponent): JPanel = JPanel(WrapLayout(FlowLayout.LEFT, 0, 0)).apply {
        isOpaque = false
        // Gaps as spacers: the components keep their own borders (the status badge paints its padding).
        components.forEach {
            add(it)
            add(Box.createRigidArea(Dimension(JBUI.scale(12), 0)))
        }
    }

    private fun showSubject(workPackage: WorkPackage) {
        subjectRow.removeAll()
        subjectLabel.text = workPackage.subject
        subjectRow.add(subjectLabel)
        if (controller.details.value?.canEdit == true) subjectRow.add(subjectEdit)
        subjectRow.revalidate()
    }

    private fun showDescription(workPackage: WorkPackage) {
        descriptionPanel.removeAll()
        val html = workPackage.description?.html
        if (html.isNullOrBlank()) {
            descriptionPanel.add(JBLabel(OpenProjectBundle.message("detail.description.empty")).apply { foreground = UIUtil.getContextHelpForeground() })
        } else {
            descriptionView.setHtml(withoutImages(html))
            descriptionPanel.add(descriptionView)
            cs.launch {
                val resolved = runCatching { resolveImages(html) }.getOrNull() ?: return@launch
                if (resolved != html) withContext(Dispatchers.EDT) { if (!editingDescription) descriptionView.setHtml(resolved) }
            }
        }
        descriptionPanel.revalidate()
    }

    // ---- editing ----

    private fun editSubject() {
        val workPackage = controller.details.value?.workPackage ?: return
        val field = JBTextField(workPackage.subject).apply { font = JBFont.h3() }
        subjectRow.removeAll()
        subjectRow.add(field)
        subjectRow.revalidate()
        field.requestFocusInWindow()
        field.selectAll()
        fun finish(save: Boolean) {
            val text = field.text.trim()
            if (save && text.isNotEmpty() && text != workPackage.subject) {
                perform { controller.update { it.subject(text) } }
            }
            showSubject(controller.details.value?.workPackage ?: workPackage)
        }
        field.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_ENTER -> finish(save = true)
                    KeyEvent.VK_ESCAPE -> finish(save = false)
                }
            }
        })
    }

    private fun chooseStatus(anchor: JComponent) {
        perform {
            val statuses = controller.allowedStatuses()
            val colors = controller.details.value?.statusColors.orEmpty()
            withContext(Dispatchers.EDT) {
                choose(anchor, statuses, color = { it.id?.toLongOrNull()?.let(colors::get) }) { status ->
                    perform { controller.update { it.link("status", status.href) } }
                }
            }
        }
    }

    private fun chooseAssignee(anchor: JComponent) {
        perform {
            val users = controller.availableAssignees().map { HalLink(href = it.links.href("self") ?: "/api/v3/users/${it.id}", title = it.name) }
            val options = listOf(HalLink(href = null, title = OpenProjectBundle.message("detail.unassigned"))) + users
            withContext(Dispatchers.EDT) {
                choose(anchor, options) { user -> perform { controller.update { it.link("assignee", user.href) } } }
            }
        }
    }

    private fun choosePriority(anchor: JComponent) {
        perform {
            val priorities = controller.allowedPriorities()
            withContext(Dispatchers.EDT) {
                choose(anchor, priorities) { priority -> perform { controller.update { it.link("priority", priority.href) } } }
            }
        }
    }

    private fun choose(anchor: JComponent, options: List<HalLink>, color: (HalLink) -> Color? = { null }, onChosen: (HalLink) -> Unit) {
        JBPopupFactory.getInstance().createPopupChooserBuilder(options)
            .setRenderer(swatchListRenderer<HalLink>({ it.title ?: it.href.orEmpty() }, color))
            .setNamerForFiltering { it.title.orEmpty() }
            .setItemChosenCallback(onChosen)
            .createPopup()
            .showUnderneathOf(anchor)
    }

    private fun editDescription() {
        val workPackage = controller.details.value?.workPackage ?: return
        editingDescription = true
        descriptionEditLink.isVisible = false
        val field = MarkdownField(project, workPackage.description?.raw.orEmpty(), 12)
        val preview = html()
        val previewPanel = JPanel(BorderLayout()).apply { isVisible = false; add(preview) }
        val previewLink = ActionLink(OpenProjectBundle.message("detail.description.preview")) {}
        previewLink.addActionListener {
            if (previewPanel.isVisible) {
                previewPanel.isVisible = false
                field.isVisible = true
                previewLink.text = OpenProjectBundle.message("detail.description.preview")
            } else {
                perform {
                    val rendered = controller.renderMarkdown(field.text)
                    withContext(Dispatchers.EDT) {
                        preview.setHtml(rendered)
                        field.isVisible = false
                        previewPanel.isVisible = true
                        previewLink.text = OpenProjectBundle.message("detail.description.write")
                    }
                }
            }
        }
        val save = JButton(OpenProjectBundle.message("detail.save"))
        val cancel = JButton(OpenProjectBundle.message("detail.cancel"))
        fun close() {
            editingDescription = false
            controller.details.value?.let { show(it) }
        }
        save.addActionListener {
            save.isEnabled = false
            val text = field.text
            perform(onDone = { success -> save.isEnabled = true; if (success) close() }) { controller.update { it.description(text) } }
        }
        cancel.addActionListener { close() }
        descriptionPanel.removeAll()
        descriptionPanel.add(JPanel(BorderLayout()).apply {
            isOpaque = false
            add(previewLink, BorderLayout.NORTH)
            add(JPanel(BorderLayout()).apply { add(field, BorderLayout.CENTER); add(previewPanel, BorderLayout.SOUTH) }, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(4))).apply { add(save); add(cancel) }, BorderLayout.SOUTH)
        })
        descriptionPanel.revalidate()
        field.requestFocusInWindow()
    }

    private fun comment(text: String, internal: Boolean, done: (Boolean) -> Unit) {
        perform(onDone = done) { controller.addComment(text, internal) }
    }

    private fun addRelation() {
        val dialog = AddRelationDialog(project, controller)
        if (!dialog.showAndGet()) return
        val result = dialog.result()
        perform { controller.addRelation(result.type, result.target, result.description, result.lag) }
    }

    // ---- files ----

    private fun chooseAndUpload() {
        val files = FileChooser.chooseFiles(FileChooserDescriptorFactory.createMultipleFilesNoJarsDescriptor(), project, null)
        upload(files.map { File(it.path) })
    }

    private fun upload(files: List<File>) {
        if (files.isEmpty()) return
        perform {
            files.forEach { file ->
                controller.upload(file.name, URLConnection.guessContentTypeFromName(file.name), file.readBytes())
            }
        }
    }

    private fun download(attachment: Attachment) {
        val target = FileChooserFactory.getInstance()
            .createSaveFileDialog(FileSaverDescriptor(OpenProjectBundle.message("detail.files.download"), ""), project)
            .save(attachment.fileName)?.file ?: return
        perform {
            target.outputStream().use { controller.download(attachment, it) }
            withContext(Dispatchers.EDT) { notify(OpenProjectBundle.message("detail.files.downloaded", attachment.fileName), NotificationType.INFORMATION) }
        }
    }

    private fun openAttachment(attachment: Attachment) {
        perform {
            val directory = FileUtil.createTempDirectory("openproject-attachment", null, true)
            val target = File(directory, attachment.fileName.replace(Regex("[\\\\/:]"), "_"))
            target.outputStream().use { controller.download(attachment, it) }
            withContext(Dispatchers.EDT) {
                LocalFileSystem.getInstance().refreshAndFindFileByIoFile(target)?.let { OpenFileDescriptor(project, it).navigate(true) }
            }
        }
    }

    // ---- helpers ----

    /** Runs [action] in the background, reporting failures; a conflicting edit offers to reapply it. */
    private fun perform(onDone: ((Boolean) -> Unit)? = null, action: suspend () -> Unit) {
        cs.launch {
            val success = try {
                action()
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: ConcurrentChangeException) {
                withContext(Dispatchers.EDT) {
                    notify(OpenProjectBundle.message("detail.conflict"), NotificationType.WARNING, retry = { perform(onDone, action) })
                }
                false
            } catch (e: Exception) {
                withContext(Dispatchers.EDT) { notifyError(project, describeError(e), e) }
                false
            }
            if (onDone != null) withContext(Dispatchers.EDT) { onDone(success) }
        }
    }

    private fun notify(message: String, type: NotificationType, retry: (() -> Unit)? = null) {
        val notification = NotificationGroupManager.getInstance().getNotificationGroup("OpenProject").createNotification(message, type)
        if (retry != null) {
            notification.addAction(NotificationAction.createSimpleExpiring(OpenProjectBundle.message("detail.conflict.reapply")) { retry() })
        }
        notification.notify(project)
    }

    private fun html(): HtmlView = HtmlView({ controller.details.value?.server?.id }) { id ->
        OpenProjectToolWindowManager.getInstance(project).openWorkPackage(serverId, id)
    }

    private val imageTag = Regex("""<img\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val imageSource = Regex("""\bsrc="([^"]+)"""", RegexOption.IGNORE_CASE)

    /** Images are attachments that need the API key; until they are downloaded they are left out. */
    private fun withoutImages(html: String): String = imageTag.replace(html) { "" }

    private suspend fun resolveImages(html: String): String {
        val base = controller.details.value?.server?.id ?: return html
        val origin = base.substringBefore("://") + "://" + base.substringAfter("://").substringBefore('/')
        return imageTag.findAll(html).take(30).fold(html) { result, tag ->
            val src = imageSource.find(tag.value)?.groupValues?.get(1) ?: return@fold result
            val absolute = if (src.startsWith("/")) origin + src else src
            if (!absolute.startsWith(origin)) return@fold result
            val local = images[absolute] ?: File(imageDirectory, "img${images.size}").also { target ->
                target.outputStream().use { controller.connection().api.download(absolute, it) }
                images[absolute] = target
            }
            result.replace(tag.value, tag.value.replace(src, local.toURI().toString()))
        }
    }

    private fun toolbar(): JComponent {
        val actions = ActionManager.getInstance()
        val group = when (layout) {
            Layout.TAB -> actions.getAction("OpenProject.WorkPackageToolbar") as ActionGroup
            Layout.PREVIEW -> DefaultActionGroup().apply {
                add(actions.getAction("OpenProject.OpenInNewTab"))
                add(actions.getAction("OpenProject.WorkPackageToolbar") as ActionGroup)
                onClose?.let { close ->
                    add(Separator.getInstance())
                    add(object : DumbAwareAction(OpenProjectBundle.message("preview.close"), null, AllIcons.Actions.Close) {
                        override fun getActionUpdateThread() = ActionUpdateThread.EDT
                        override fun actionPerformed(e: AnActionEvent) = close()
                    })
                }
            }
        }
        val toolbar = actions.createActionToolbar("OpenProject.WorkPackage", group, true)
        toolbar.targetComponent = content
        return toolbar.component.apply { border = JBUI.Borders.customLineBottom(JBColor.border()) }
    }

    @TestOnly
    internal fun details(): WorkPackageDetails? = controller.details.value

    @TestOnly
    internal fun isPreview(): Boolean = layout == Layout.PREVIEW

    override fun dispose() {
        cs.cancel()
    }
}
