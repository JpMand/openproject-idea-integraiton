package com.github.jpmand.openproject.idea.api.model

import com.github.jpmand.openproject.idea.api.hal.Formattable
import com.github.jpmand.openproject.idea.api.hal.HalLinks
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonObject

/**
 * A work package. Typed properties cover what the plugin reads directly; [raw] keeps the full JSON so that
 * table columns and custom fields (`customFieldNN`, both as properties and in `_links`) can be read generically.
 */
@Serializable
data class WorkPackage(
    val id: Long,
    /** Project-based identifier such as "PROJ-12" (newer OpenProject versions only). */
    val displayId: String? = null,
    val lockVersion: Int = 0,
    val subject: String = "",
    val description: Formattable? = null,
    val startDate: String? = null,
    val dueDate: String? = null,
    /** Single date of milestones. */
    val date: String? = null,
    val estimatedTime: String? = null,
    val remainingTime: String? = null,
    val spentTime: String? = null,
    val percentageDone: Int? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val readonly: Boolean? = null,
    @SerialName("_links") val links: HalLinks = HalLinks.EMPTY,
    @SerialName("_embedded") val embedded: JsonObject? = null,
    @Transient val raw: JsonObject = JsonObject(emptyMap()),
) {
    /** "PROJ-12" when the server uses project-based ids, otherwise "#12" (servers without them send the plain id). */
    val presentableId: String get() = displayId?.takeIf { it != id.toString() } ?: "#$id"

    val statusTitle: String? get() = links.title("status")
    val typeTitle: String? get() = links.title("type")
    val projectTitle: String? get() = links.title("project")
    val assigneeTitle: String? get() = links.title("assignee")
}
