package com.github.jpmand.openproject.idea.api

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Builds a work package PATCH (or form) body: only the changed properties and links plus `lockVersion`,
 * which OpenProject requires for optimistic locking. Linked values go under `_links`; `null` unsets them.
 */
class WorkPackagePatch(private val lockVersion: Int?) {
    private val properties = linkedMapOf<String, JsonElement>()
    private val links = linkedMapOf<String, JsonElement>()

    fun subject(subject: String) = property("subject", JsonPrimitive(subject))

    fun description(raw: String) = property("description", buildJsonObject { put("raw", raw) })

    fun property(name: String, value: JsonElement) = apply { properties[name] = value }

    /** Sets a linked attribute (status, assignee, priority, type, version, parent, ...); `null` unsets it. */
    fun link(name: String, href: String?) = apply {
        links[name] = buildJsonObject { put("href", href?.let(::JsonPrimitive) ?: JsonNull) }
    }

    val isEmpty: Boolean get() = properties.isEmpty() && links.isEmpty()

    fun build(): JsonObject = buildJsonObject {
        lockVersion?.let { put("lockVersion", it) }
        properties.forEach { (key, value) -> put(key, value) }
        if (links.isNotEmpty()) put("_links", JsonObject(links))
    }
}
