package com.github.jpmand.openproject.idea.api.model

import com.github.jpmand.openproject.idea.api.hal.Formattable
import com.github.jpmand.openproject.idea.api.hal.HalLinks
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Serializable
data class Root(
    val instanceName: String? = null,
    /** Only visible to administrators. */
    val coreVersion: String? = null,
    @SerialName("_links") val links: HalLinks = HalLinks.EMPTY,
)

/** A user, group or placeholder user (`_type` tells which). */
@Serializable
data class Principal(
    @SerialName("_type") val type: String? = null,
    val id: Long = 0,
    val name: String = "",
    val login: String? = null,
    val email: String? = null,
    val admin: Boolean? = null,
    @SerialName("_links") val links: HalLinks = HalLinks.EMPTY,
)

@Serializable
data class Status(
    val id: Long,
    val name: String,
    val isClosed: Boolean = false,
    val isDefault: Boolean = false,
    val isReadonly: Boolean = false,
    val color: String? = null,
    val position: Int = 0,
    @SerialName("_links") val links: HalLinks = HalLinks.EMPTY,
)

@Serializable
data class WorkPackageType(
    val id: Long,
    val name: String,
    val color: String? = null,
    val position: Int = 0,
    val isDefault: Boolean = false,
    val isMilestone: Boolean = false,
    @SerialName("_links") val links: HalLinks = HalLinks.EMPTY,
)

@Serializable
data class Priority(
    val id: Long,
    val name: String,
    val position: Int = 0,
    val color: String? = null,
    val isDefault: Boolean = false,
    @SerialName("_links") val links: HalLinks = HalLinks.EMPTY,
)

@Serializable
data class Project(
    val id: Long,
    val identifier: String = "",
    val name: String = "",
    val active: Boolean = true,
    @SerialName("_links") val links: HalLinks = HalLinks.EMPTY,
)

@Serializable
data class Version(
    val id: Long,
    val name: String = "",
    val status: String? = null,
    @SerialName("_links") val links: HalLinks = HalLinks.EMPTY,
)

@Serializable
data class Configuration(
    val maximumAPIV3PageSize: Int = 1000,
    val perPageOptions: List<Int> = listOf(20, 50, 100),
    val hoursPerDay: Double? = null,
    val durationFormat: String? = null,
    val availableFeatures: List<String> = emptyList(),
    val activeFeatureFlags: List<String> = emptyList(),
    @SerialName("_links") val links: HalLinks = HalLinks.EMPTY,
)

@Serializable
data class Attachment(
    val id: Long,
    val fileName: String = "",
    val fileSize: Long = 0,
    val contentType: String? = null,
    val description: Formattable? = null,
    val createdAt: String? = null,
    @SerialName("_links") val links: HalLinks = HalLinks.EMPTY,
)

@Serializable
data class Relation(
    val id: Long,
    val name: String? = null,
    /** Relation type seen from `from`: relates, duplicates, duplicated, blocks, blocked, precedes, follows, ... */
    val type: String = "",
    val reverseType: String? = null,
    val description: String? = null,
    val lag: Int? = null,
    @SerialName("_links") val links: HalLinks = HalLinks.EMPTY,
)

/** A journal entry: `_type` is "Activity" for changes and "Activity::Comment" for comments. */
@Serializable
data class Activity(
    @SerialName("_type") val type: String = "Activity",
    val id: Long = 0,
    val version: Int? = null,
    val comment: Formattable? = null,
    val details: List<Formattable> = emptyList(),
    val internal: Boolean? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    @SerialName("_links") val links: HalLinks = HalLinks.EMPTY,
) {
    val isComment: Boolean get() = type == "Activity::Comment" && !comment?.raw.isNullOrBlank()
}

@Serializable
data class TimeEntry(
    val id: Long = 0,
    val hours: String? = null,
    val spentOn: String? = null,
    val comment: Formattable? = null,
    val ongoing: Boolean? = null,
    @SerialName("_links") val links: HalLinks = HalLinks.EMPTY,
)

@Serializable
data class QueryFilterInstance(
    @SerialName("_type") val type: String? = null,
    val name: String? = null,
    /** Values of string/integer/date filters; resource values are in `_links.values`. */
    val values: List<JsonElement> = emptyList(),
    @SerialName("_links") val links: HalLinks = HalLinks.EMPTY,
)

/** A saved OpenProject query ("view"). The default query embeds its results. */
@Serializable
data class Query(
    val id: Long? = null,
    val name: String? = null,
    val starred: Boolean = false,
    val public: Boolean = false,
    val hidden: Boolean = false,
    val includeSubprojects: Boolean = true,
    val filters: List<QueryFilterInstance> = emptyList(),
    @SerialName("_links") val links: HalLinks = HalLinks.EMPTY,
    @SerialName("_embedded") val embedded: JsonObject? = null,
)

/** A `Form` response: the edited [payload], the (possibly changed) [schema] and per-property [validationErrors]. */
@Serializable
data class Form(
    @SerialName("_embedded") val embedded: Embedded = Embedded(),
    @SerialName("_links") val links: HalLinks = HalLinks.EMPTY,
) {
    val payload: JsonObject get() = embedded.payload
    val schema: Schema get() = Schema(embedded.schema)
    val validationErrors: Map<String, String>
        get() = embedded.validationErrors.mapValues { (_, error) ->
            ((error as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull ?: error.toString()
        }

    @Serializable
    data class Embedded(
        val payload: JsonObject = JsonObject(emptyMap()),
        val schema: JsonObject = JsonObject(emptyMap()),
        val validationErrors: JsonObject = JsonObject(emptyMap()),
    )
}
