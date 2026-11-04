package com.github.jpmand.openprojectideaintegraiton.ui

import com.github.jpmand.openprojectideaintegraiton.api.model.WorkPackage
import com.github.jpmand.openprojectideaintegraiton.core.OpenProjectServer
import com.intellij.openapi.actionSystem.DataKey

/** A work package together with the server it belongs to. */
data class WorkPackageRef(val server: OpenProjectServer, val workPackage: WorkPackage) {
    val webUrl: String get() = "${server.id}/work_packages/${workPackage.id}"
}

object OpenProjectDataKeys {
    /** The work package under the caret/selection in OpenProject editors. */
    @JvmField
    val WORK_PACKAGE: DataKey<WorkPackageRef> = DataKey.create("OpenProject.WorkPackage")

    /** Asks the editor that provided the context to reload (e.g. after logging time or changing the status). */
    @JvmField
    val REFRESHER: DataKey<Runnable> = DataKey.create("OpenProject.Refresher")
}
