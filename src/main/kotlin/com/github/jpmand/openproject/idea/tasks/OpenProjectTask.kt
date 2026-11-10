package com.github.jpmand.openproject.idea.tasks

import com.github.jpmand.openproject.idea.OpenProjectIcons
import com.github.jpmand.openproject.idea.api.model.WorkPackage
import com.intellij.tasks.Comment
import com.intellij.tasks.Task
import com.intellij.tasks.TaskRepository
import com.intellij.tasks.TaskState
import com.intellij.tasks.TaskType
import java.time.Instant
import java.util.Date
import javax.swing.Icon

/** A work package seen through the Tasks API. [getId] is the numeric work package id. */
class OpenProjectTask(
    val workPackage: WorkPackage,
    private val repository: OpenProjectRepository?,
    private val baseUrl: String,
    private val closed: Boolean,
) : Task() {

    override fun getId(): String = workPackage.id.toString()

    override fun getPresentableId(): String = workPackage.presentableId

    override fun getNumber(): String = workPackage.id.toString()

    override fun getProject(): String? = workPackage.displayId?.substringBeforeLast('-')

    override fun getSummary(): String = workPackage.subject

    override fun getDescription(): String? = workPackage.description?.raw

    override fun getComments(): Array<Comment> = Comment.EMPTY_ARRAY

    override fun getIcon(): Icon = OpenProjectIcons.OpenProject

    override fun getType(): TaskType = typeOf(workPackage.typeTitle)

    override fun getUpdated(): Date? = parseDate(workPackage.updatedAt)

    override fun getCreated(): Date? = parseDate(workPackage.createdAt)

    override fun isClosed(): Boolean = closed

    override fun isIssue(): Boolean = true

    override fun getIssueUrl(): String = "$baseUrl/work_packages/${workPackage.id}"

    override fun getRepository(): TaskRepository? = repository

    override fun getState(): TaskState? = if (closed) TaskState.RESOLVED else null

    companion object {
        fun typeOf(typeName: String?): TaskType {
            val name = typeName?.lowercase().orEmpty()
            return when {
                "bug" in name || "defect" in name -> TaskType.BUG
                "feature" in name || "epic" in name || "story" in name -> TaskType.FEATURE
                else -> TaskType.OTHER
            }
        }

        private fun parseDate(value: String?): Date? = value?.let { runCatching { Date.from(Instant.parse(it)) }.getOrNull() }
    }
}
