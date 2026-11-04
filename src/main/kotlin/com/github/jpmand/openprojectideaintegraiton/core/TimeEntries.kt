package com.github.jpmand.openprojectideaintegraiton.core

import com.github.jpmand.openprojectideaintegraiton.OpenProjectBundle
import com.github.jpmand.openprojectideaintegraiton.api.Durations
import com.github.jpmand.openprojectideaintegraiton.api.OpenProjectApi
import com.github.jpmand.openprojectideaintegraiton.api.hal.HalLink
import com.github.jpmand.openprojectideaintegraiton.api.model.Form
import com.github.jpmand.openprojectideaintegraiton.api.model.TimeEntry
import com.github.jpmand.openprojectideaintegraiton.api.model.WorkPackage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.Duration
import java.time.LocalDate

class TimeEntryValidationException(val errors: Map<String, String>) :
    Exception(errors.values.joinToString("\n").ifEmpty { "The time entry is not valid." })

/**
 * Logs time on work packages via OpenProject's time entry form, which reports the allowed activities and
 * validation errors before anything is saved. Newer servers link the work package as `entity`, older ones
 * (OpenProject 15/16) as `workPackage`; the form schema tells which.
 */
object TimeEntries {

    data class Draft(
        val duration: Duration,
        val spentOn: LocalDate = LocalDate.now(),
        val comment: String? = null,
        val activityHref: String? = null,
    )

    /** The allowed activities and the server's default for [workPackage]. */
    suspend fun activities(api: OpenProjectApi, workPackage: WorkPackage): Pair<List<HalLink>, String?> {
        val form = form(api, workPackage, null)
        val field = form.schema["activity"]
        val default = form.payload["_links"]?.let { (it as? JsonObject)?.get("activity") as? JsonObject }
            ?.get("href")?.toString()?.trim('"')?.takeIf { it != "null" }
        return (field?.allowedOptions.orEmpty()) to default
    }

    /** OpenProject offers `logTime` only where time tracking is enabled for the project and permitted for the user. */
    fun canLogTime(workPackage: WorkPackage): Boolean = workPackage.links.has("logTime")

    suspend fun log(api: OpenProjectApi, workPackage: WorkPackage, draft: Draft): TimeEntry {
        if (!canLogTime(workPackage)) {
            throw TimeEntryValidationException(mapOf("logTime" to OpenProjectBundle.message("time.tracking.unavailable", workPackage.presentableId)))
        }
        val form = form(api, workPackage, draft)
        if (form.validationErrors.isNotEmpty()) throw TimeEntryValidationException(form.validationErrors)
        return api.createTimeEntry(form.payload)
    }

    private suspend fun form(api: OpenProjectApi, workPackage: WorkPackage, draft: Draft?): Form {
        val form = api.timeEntryForm(payload(workPackage, draft, ENTITY))
        val schema = form.schema
        return if (schema[ENTITY] == null && schema[WORK_PACKAGE] != null) {
            api.timeEntryForm(payload(workPackage, draft, WORK_PACKAGE))
        } else {
            form
        }
    }

    private fun payload(workPackage: WorkPackage, draft: Draft?, workPackageLink: String): JsonObject = buildJsonObject {
        if (draft != null) {
            put("hours", Durations.toIso(draft.duration))
            put("spentOn", draft.spentOn.toString())
            if (!draft.comment.isNullOrBlank()) putJsonObject("comment") { put("raw", draft.comment) }
        }
        putJsonObject("_links") {
            val href = workPackage.links.href("self") ?: "/api/v3/work_packages/${workPackage.id}"
            putJsonObject(workPackageLink) { put("href", href) }
            workPackage.links.href("project")?.let { project -> putJsonObject("project") { put("href", project) } }
            draft?.activityHref?.let { activity -> putJsonObject("activity") { put("href", activity) } }
        }
    }

    private const val ENTITY = "entity"
    private const val WORK_PACKAGE = "workPackage"
}
