package com.github.jpmand.openproject.idea.actions

import com.github.jpmand.openproject.idea.OpenProjectBundle
import com.github.jpmand.openproject.idea.core.ActiveWorkPackageTracker
import com.github.jpmand.openproject.idea.core.OpenProjectProjectScope
import com.github.jpmand.openproject.idea.core.OpenProjectServers
import com.github.jpmand.openproject.idea.core.TimeEntries
import com.github.jpmand.openproject.idea.core.connect
import com.github.jpmand.openproject.idea.core.describeError
import com.github.jpmand.openproject.idea.core.needsServerSettings
import com.github.jpmand.openproject.idea.ui.OpenProjectDataKeys
import com.github.jpmand.openproject.idea.ui.WorkPackageRef
import com.github.jpmand.openproject.idea.ui.toolwindow.OpenProjectToolWindowManager
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.datatransfer.StringSelection
import javax.swing.JComponent

internal fun notifyError(project: Project, message: String, cause: Throwable? = null) {
    val notification = NotificationGroupManager.getInstance().getNotificationGroup("OpenProject")
        .createNotification(message, NotificationType.ERROR)
    if (cause != null && needsServerSettings(cause)) {
        notification.addAction(NotificationAction.createSimpleExpiring(OpenProjectBundle.message("error.configure.server")) {
            OpenProjectServers.getInstance(project).openServerSettings()
        })
    }
    notification.notify(project)
}

/** "+" after the tool window tabs: a new work package list. */
class NewWorkPackageListAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        OpenProjectToolWindowManager.getInstance(project).newListTab(e.inputEvent?.component as? JComponent)
    }
}

/** Opens the tab of the active task's work package. */
class OpenActiveWorkPackageAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.let { ActiveWorkPackageTracker.getInstance(it).active.value } != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val active = ActiveWorkPackageTracker.getInstance(project).active.value ?: return
        OpenProjectToolWindowManager.getInstance(project).openWorkPackage(active.serverId, active.workPackageId)
    }
}

/** Logs time on the work package in context, or on the active task's work package. */
class LogTimeAction : DumbAwareAction(AllIcons.Vcs.History) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val ref = e.getData(OpenProjectDataKeys.WORK_PACKAGE)
        e.presentation.isEnabled = when {
            project == null -> false
            ref != null -> TimeEntries.canLogTime(ref.workPackage)
            else -> ActiveWorkPackageTracker.getInstance(project).active.value != null
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val contextRef = e.getData(OpenProjectDataKeys.WORK_PACKAGE)
        val refresher = e.getData(OpenProjectDataKeys.REFRESHER)
        val active = ActiveWorkPackageTracker.getInstance(project).active.value
        OpenProjectProjectScope.getInstance(project).cs.launch {
            try {
                val serverId = contextRef?.server?.id ?: active?.serverId ?: return@launch
                val (server, connection) = connect(project, serverId)
                val workPackage = connection.api.workPackage(contextRef?.workPackage?.id ?: active!!.workPackageId)
                withContext(Dispatchers.EDT) {
                    if (!TimeEntries.canLogTime(workPackage)) {
                        notifyError(project, OpenProjectBundle.message("time.tracking.unavailable", workPackage.presentableId))
                    } else if (LogTimeDialog(project, WorkPackageRef(server, workPackage), connection).showAndGet()) {
                        refresher?.run()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.EDT) { notifyError(project, describeError(e), e) }
            }
        }
    }
}

/** Base for actions on the work package in context. */
abstract class WorkPackageAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && e.getData(OpenProjectDataKeys.WORK_PACKAGE) != null
    }

    final override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val ref = e.getData(OpenProjectDataKeys.WORK_PACKAGE) ?: return
        perform(project, ref, e)
    }

    abstract fun perform(project: Project, ref: WorkPackageRef, e: AnActionEvent)
}

/** Opens the work package in a tool window tab of its own. */
class OpenInNewTabAction : WorkPackageAction() {
    override fun perform(project: Project, ref: WorkPackageRef, e: AnActionEvent) =
        OpenProjectToolWindowManager.getInstance(project).openWorkPackage(ref.server.id, ref.workPackage.id)
}

/** Shows the selected work package next to the list. */
class OpenOnTheRightAction : WorkPackageAction() {
    override fun update(e: AnActionEvent) {
        super.update(e)
        if (e.getData(OpenProjectDataKeys.LIST_PANEL) == null) e.presentation.isEnabledAndVisible = false
    }

    override fun perform(project: Project, ref: WorkPackageRef, e: AnActionEvent) {
        e.getData(OpenProjectDataKeys.LIST_PANEL)?.showPreview(ref.workPackage.id)
    }
}

class OpenWorkPackageInBrowserAction : WorkPackageAction() {
    override fun perform(project: Project, ref: WorkPackageRef, e: AnActionEvent) = BrowserUtil.browse(ref.webUrl)
}

class CopyWorkPackageLinkAction : WorkPackageAction() {
    override fun perform(project: Project, ref: WorkPackageRef, e: AnActionEvent) =
        CopyPasteManager.getInstance().setContents(StringSelection(ref.webUrl))
}

/** Makes the work package the active task (Tools | Tasks & Contexts). */
class StartWorkingAction : WorkPackageAction() {
    override fun update(e: AnActionEvent) {
        super.update(e)
        val project = e.project ?: return
        val ref = e.getData(OpenProjectDataKeys.WORK_PACKAGE) ?: return
        e.presentation.isEnabled = !ActiveWorkPackageTracker.getInstance(project).isActive(ref.server.id, ref.workPackage.id)
    }

    override fun perform(project: Project, ref: WorkPackageRef, e: AnActionEvent) {
        ActiveWorkPackageTracker.getInstance(project).activate(ref.server, ref.workPackage)
        e.getData(OpenProjectDataKeys.REFRESHER)?.run()
    }
}

/** Reloads the editor in context. */
class RefreshAction : DumbAwareAction(AllIcons.Actions.Refresh) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.getData(OpenProjectDataKeys.REFRESHER) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        e.getData(OpenProjectDataKeys.REFRESHER)?.run()
    }
}
