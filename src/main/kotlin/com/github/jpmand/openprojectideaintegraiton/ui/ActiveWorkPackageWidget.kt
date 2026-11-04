package com.github.jpmand.openprojectideaintegraiton.ui

import com.github.jpmand.openprojectideaintegraiton.OpenProjectBundle
import com.github.jpmand.openprojectideaintegraiton.core.ActiveWorkPackageTracker
import com.github.jpmand.openprojectideaintegraiton.core.OpenProjectProjectScope
import com.github.jpmand.openprojectideaintegraiton.vfs.OpenProjectFilesManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.util.Consumer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.event.MouseEvent

/** Shows the work package of the active task in the status bar; a click opens its page. */
class ActiveWorkPackageWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = ActiveWorkPackageWidget.ID

    override fun getDisplayName(): String = OpenProjectBundle.message("widget.name")

    override fun createWidget(project: Project): StatusBarWidget = ActiveWorkPackageWidget(project)
}

class ActiveWorkPackageWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.TextPresentation {

    private val cs = OpenProjectProjectScope.getInstance(project).childScope("ActiveWorkPackageWidget")
    private val tracker get() = ActiveWorkPackageTracker.getInstance(project)

    override fun ID(): String = ID

    override fun install(statusBar: StatusBar) {
        cs.launch {
            tracker.active.collect {
                withContext(Dispatchers.EDT) { statusBar.updateWidget(ID) }
            }
        }
    }

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun getText(): String = tracker.active.value?.let { OpenProjectBundle.message("widget.text", it.presentableId) }.orEmpty()

    override fun getTooltipText(): String? = tracker.active.value?.let { OpenProjectBundle.message("widget.tooltip", it.presentableId, it.summary) }

    override fun getAlignment(): Float = 0f

    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer {
        tracker.active.value?.let { OpenProjectFilesManager.getInstance(project).openWorkPackage(it.serverId, it.workPackageId) }
    }

    override fun dispose() {
        cs.cancel()
    }

    companion object {
        const val ID = "OpenProject.ActiveWorkPackage"
    }
}
