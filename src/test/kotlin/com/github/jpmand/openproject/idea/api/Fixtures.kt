package com.github.jpmand.openproject.idea.api

import com.github.jpmand.openproject.idea.api.hal.HalJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** JSON captured from community.openproject.org (trimmed) and the OpenProject API specification. */
object Fixtures {
    fun text(name: String): String =
        requireNotNull(Fixtures::class.java.getResource("/fixtures/$name")) { "Missing fixture $name" }.readText()

    fun json(name: String): JsonObject = HalJson.read.parseToJsonElement(text(name)).jsonObject
}
