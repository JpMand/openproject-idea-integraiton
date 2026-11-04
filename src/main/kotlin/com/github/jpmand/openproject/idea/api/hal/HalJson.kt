package com.github.jpmand.openproject.idea.api.hal

import io.ktor.http.ContentType
import kotlinx.serialization.json.Json

/** JSON settings and media types for the OpenProject HAL+JSON API. */
object HalJson {
    /** Lenient reader: OpenProject adds properties over time and omits unset ones. */
    val read: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    /** Media type of all OpenProject API responses. */
    val contentType: ContentType = ContentType("application", "hal+json")
}
