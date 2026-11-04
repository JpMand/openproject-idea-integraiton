package com.github.jpmand.openprojectideaintegraiton.actions

import com.github.jpmand.openprojectideaintegraiton.OpenProjectBundle
import com.github.jpmand.openprojectideaintegraiton.api.Durations
import com.github.jpmand.openprojectideaintegraiton.api.hal.HalLink
import com.github.jpmand.openprojectideaintegraiton.core.OpenProjectConnection
import com.github.jpmand.openprojectideaintegraiton.core.TimeEntries
import com.github.jpmand.openprojectideaintegraiton.core.TimeEntryValidationException
import com.github.jpmand.openprojectideaintegraiton.core.describeError
import com.github.jpmand.openprojectideaintegraiton.ui.WorkPackageRef
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.progress.runBlockingMaybeCancellable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.rows
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import java.time.LocalDate
import java.time.format.DateTimeParseException
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent

/** Logs time on a work package through OpenProject's `logTime` action (no Time Tracking plugin needed). */
class LogTimeDialog(
    private val project: Project,
    private val ref: WorkPackageRef,
    private val connection: OpenProjectConnection,
    initialDuration: String = "",
) : DialogWrapper(project) {

    private val dateField = JBTextField(LocalDate.now().toString(), 10)
    private val durationField = JBTextField(initialDuration, 10)
    private val activityCombo = ComboBox(DefaultComboBoxModel(arrayOf(DEFAULT_ACTIVITY))).apply {
        renderer = textListCellRenderer { it?.title ?: OpenProjectBundle.message("log.time.activity.default") }
    }
    private val commentArea = JBTextArea(3, 40).apply { lineWrap = true; wrapStyleWord = true }

    init {
        title = OpenProjectBundle.message("log.time.title", ref.workPackage.presentableId)
        init()
        loadActivities()
    }

    override fun createCenterPanel(): JComponent = panel {
        row { label("${ref.workPackage.presentableId}  ${ref.workPackage.subject}").bold() }
        row(OpenProjectBundle.message("log.time.date")) { cell(dateField) }
        row(OpenProjectBundle.message("log.time.duration")) {
            cell(durationField).comment(OpenProjectBundle.message("log.time.duration.comment"))
        }
        row(OpenProjectBundle.message("log.time.activity")) { cell(activityCombo) }
        row(OpenProjectBundle.message("log.time.comment")) { cell(commentArea).align(AlignX.FILL).rows(3) }
    }

    override fun getPreferredFocusedComponent(): JComponent = durationField

    override fun doValidateAll(): List<ValidationInfo> = buildList {
        if (parseDate() == null) add(ValidationInfo(OpenProjectBundle.message("log.time.date.invalid"), dateField))
        val duration = Durations.parseHuman(durationField.text)
        if (duration == null || duration.isZero || duration.isNegative) add(ValidationInfo(OpenProjectBundle.message("log.time.duration.invalid"), durationField))
    }

    override fun doOKAction() {
        val draft = TimeEntries.Draft(
            duration = Durations.parseHuman(durationField.text) ?: return,
            spentOn = parseDate() ?: return,
            comment = commentArea.text.trim().ifEmpty { null },
            activityHref = (activityCombo.selectedItem as? HalLink)?.href,
        )
        val error = runWithModalProgressBlocking(project, OpenProjectBundle.message("log.time.progress")) {
            runCatching { TimeEntries.log(connection.api, ref.workPackage, draft) }.exceptionOrNull()
        }
        when (error) {
            null -> super.doOKAction()
            is TimeEntryValidationException -> setErrorText(error.message)
            else -> setErrorText(describeError(error))
        }
    }

    private fun parseDate(): LocalDate? = try {
        LocalDate.parse(dateField.text.trim())
    } catch (_: DateTimeParseException) {
        null
    }

    private fun loadActivities() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val (activities, default) = runCatching {
                runBlockingMaybeCancellable { TimeEntries.activities(connection.api, ref.workPackage) }
            }.getOrNull() ?: return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater({
                if (isDisposed) return@invokeLater
                activityCombo.model = DefaultComboBoxModel((listOf(DEFAULT_ACTIVITY) + activities).toTypedArray())
                activityCombo.selectedItem = activities.firstOrNull { it.href == default } ?: DEFAULT_ACTIVITY
            }, ModalityState.any())
        }
    }

    private companion object {
        /** Let the server pick its default activity. */
        val DEFAULT_ACTIVITY = HalLink(href = null, title = null)
    }
}
