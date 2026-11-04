package com.github.jpmand.openprojectideaintegraiton.api.hal

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * An OpenProject collection. Note that [offset] is the 1-based **page number**, not an item offset.
 */
@Serializable
data class HalCollection<T>(
    @SerialName("_type") val type: String? = null,
    val total: Int = 0,
    val count: Int = 0,
    val pageSize: Int? = null,
    val offset: Int? = null,
    @SerialName("_embedded") val embedded: Embedded<T> = Embedded(),
    @SerialName("_links") val links: HalLinks = HalLinks.EMPTY,
) {
    val elements: List<T> get() = embedded.elements

    @Serializable
    data class Embedded<T>(
        val elements: List<T> = emptyList(),
        /** Work package collections embed the schemas of the listed (project, type) pairs. */
        val schemas: JsonObject? = null,
    )
}

/** Formattable text: only [raw] is writable, [html] is rendered by the server. */
@Serializable
data class Formattable(
    val format: String? = null,
    val raw: String? = null,
    val html: String? = null,
)
