package com.github.jpmand.openprojectideaintegraiton.tasks

import com.github.jpmand.openprojectideaintegraiton.OpenProjectIcons
import com.intellij.openapi.project.Project
import com.intellij.tasks.TaskRepository
import com.intellij.tasks.config.TaskRepositoryEditor
import com.intellij.tasks.impl.BaseRepositoryType
import com.intellij.util.Consumer
import javax.swing.Icon

/** The "OpenProject" entry of Settings | Tools | Tasks | Servers. */
class OpenProjectRepositoryType : BaseRepositoryType<OpenProjectRepository>() {

    override fun getName(): String = "OpenProject"

    override fun getIcon(): Icon = OpenProjectIcons.OpenProject

    override fun createRepository(): TaskRepository = OpenProjectRepository(this)

    override fun getRepositoryClass(): Class<OpenProjectRepository> = OpenProjectRepository::class.java

    override fun createEditor(repository: OpenProjectRepository, project: Project, changeListener: Consumer<in OpenProjectRepository>): TaskRepositoryEditor =
        OpenProjectRepositoryEditor(project, repository, changeListener)
}
