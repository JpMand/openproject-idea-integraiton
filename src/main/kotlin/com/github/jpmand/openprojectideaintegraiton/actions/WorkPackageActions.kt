package com.github.jpmand.openprojectideaintegraiton.actions

import com.github.jpmand.openprojectideaintegraiton.OpenProjectBundle
import com.github.jpmand.openprojectideaintegraiton.core.ActiveWorkPackageTracker
import com.github.jpmand.openprojectideaintegraiton.core.OpenProjectProjectScope
import com.github.jpmand.openprojectideaintegraiton.core.OpenProjectServers
import com.github.jpmand.openprojectideaintegraiton.core.TimeEntries
import com.github.jpmand.openprojectideaintegraiton.core.connect
import com.github.jpmand.openprojectideaintegraiton.core.describeError
import com.github.jpmand.openprojectideaintegraiton.ui.OpenProjectDataKeys
import com.github.jpmand.openprojectideaintegraiton.ui.WorkPackageRef
import com.github.jpmand.openprojectideaintegraiton.vfs.OpenProjectFilesManager
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.datatransfer.StringSelection

internal fun notifyError(project: Project, message: String) {
    NotificationGroupManager.getInstance().getNotificationGroup("OpenProject")
        .createNotification(message, NotificationType.ERROR).notify(project)
}

/** Opens the work package table, asking which server when several are configured. */
class OpenWorkPackagesAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val servers = OpenProjectServers.getInstance(project).servers()
        when (servers.size) {
            0 -> OpenProjectServers.getInstance(project).openServerSettings()
            1 -> OpenProjectFilesManager.getInstance(project).openList(servers.single().id)
            else -> JBPopupFactory.getInstance().createListPopup(object : BaseListPopupStep<String>(
                OpenProjectBundle.message("action.choose.server"), servers.map { it.id },
            ) {
                override fun getTextFor(value: String) = servers.first { it.id == value }.displayName
                override fun onChosen(selectedValue: String, finalChoice: Boolean): PopupStep<*>? {
                    OpenProjectFilesManager.getInstance(project).openList(selectedValue)
                    return FINAL_CHOICE
                }
            }).showCenteredInCurrentWindow(project)
        }
    }
}

/** Opens the page of the active task's work package. */
class OpenActiveWorkPackageAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.let { ActiveWorkPackageTracker.getInstance(it).active.value } != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val active = ActiveWorkPackageTracker.getInstance(project).active.value ?: return
        OpenProjectFilesManager.getInstance(project).openWorkPackage(active.serverId, active.workPackageId)
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
                withContext(Dispatchers.EDT) { notifyError(project, describeError(e)) }
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

class OpenWorkPackageAction : WorkPackageAction() {
    override fun perform(project: Project, ref: WorkPackageRef, e: AnActionEvent) =
        OpenProjectFilesManager.getInstance(project).openWorkPackage(ref.server.id, ref.workPackage.id)
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
