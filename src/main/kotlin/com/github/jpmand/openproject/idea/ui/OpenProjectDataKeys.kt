package com.github.jpmand.openproject.idea.ui

import com.github.jpmand.openproject.idea.api.model.WorkPackage
import com.github.jpmand.openproject.idea.core.OpenProjectServer
import com.github.jpmand.openproject.idea.ui.list.WorkPackageListPanel
import com.intellij.openapi.actionSystem.DataKey

/** A work package together with the server it belongs to. */
data class WorkPackageRef(val server: OpenProjectServer, val workPackage: WorkPackage) {
    val webUrl: String get() = "${server.id}/work_packages/${workPackage.id}"
}

object OpenProjectDataKeys {
    /** The selected work package (list) or the one shown (work package tab, preview). */
    @JvmField
    val WORK_PACKAGE: DataKey<WorkPackageRef> = DataKey.create("OpenProject.WorkPackage")

    /** Asks the panel that provided the context to reload (e.g. after logging time or changing the status). */
    @JvmField
    val REFRESHER: DataKey<Runnable> = DataKey.create("OpenProject.Refresher")

    /** The work package list in context ("Open on the Right"). */
    @JvmField
    val LIST_PANEL: DataKey<WorkPackageListPanel> = DataKey.create("OpenProject.ListPanel")
}
