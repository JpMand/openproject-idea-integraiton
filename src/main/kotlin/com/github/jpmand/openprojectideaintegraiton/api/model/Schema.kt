package com.github.jpmand.openprojectideaintegraiton.api.model

import com.github.jpmand.openprojectideaintegraiton.api.hal.HalJson
import com.github.jpmand.openprojectideaintegraiton.api.hal.HalLink
import com.github.jpmand.openprojectideaintegraiton.api.hal.HalLinks
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** A HAL `Schema` resource: one [SchemaField] per attribute (`subject`, `status`, `customField12`, ...). */
class Schema(val json: JsonObject) {

    val fields: Map<String, SchemaField> = json
        .filter { (key, value) -> !key.startsWith("_") && value is JsonObject && "type" in value }
        .mapValues { (key, value) -> SchemaField.from(key, value.jsonObject) }

    operator fun get(attribute: String): SchemaField? = fields[attribute]

    val links: HalLinks get() = json["_links"]?.let { HalJson.read.decodeFromJsonElement(HalLinks.serializer(), it) } ?: HalLinks.EMPTY
}

data class SchemaField(
    val attribute: String,
    /** e.g. "String", "Formattable", "Date", "Duration", "Status", "User", "[]User", "CustomOption". */
    val type: String,
    val name: String,
    val required: Boolean,
    val writable: Boolean,
    val hasDefault: Boolean,
    /** "_links" for linked resources, absent for plain properties. */
    val location: String?,
    val allowedValueLinks: List<HalLink>,
    val embeddedAllowedValues: List<JsonObject>,
) {
    val isLink: Boolean get() = location == "_links"
    val isMultiValue: Boolean get() = type.startsWith("[]")
    val elementType: String get() = type.removePrefix("[]")

    /** Allowed values as (href, title), from either `_links.allowedValues` or embedded resources. */
    val allowedOptions: List<HalLink>
        get() = allowedValueLinks.ifEmpty {
            embeddedAllowedValues.mapNotNull { value ->
                val links = value["_links"]?.let { HalJson.read.decodeFromJsonElement(HalLinks.serializer(), it) } ?: return@mapNotNull null
                val name = (value["name"] as? JsonPrimitive)?.contentOrNull ?: links.title("self")
                links.href("self")?.let { HalLink(href = it, title = name) }
            }
        }

    companion object {
        fun from(attribute: String, json: JsonObject): SchemaField {
            fun str(key: String) = (json[key] as? JsonPrimitive)?.contentOrNull
            fun bool(key: String) = (json[key] as? JsonPrimitive)?.booleanOrNull ?: false
            val links = json["_links"]?.let { HalJson.read.decodeFromJsonElement(HalLinks.serializer(), it) } ?: HalLinks.EMPTY
            val embedded = (json["_embedded"] as? JsonObject)?.get("allowedValues") as? JsonArray
            return SchemaField(
                attribute = attribute,
                type = str("type").orEmpty(),
                name = str("name") ?: attribute,
                required = bool("required"),
                writable = bool("writable"),
                hasDefault = bool("hasDefault"),
                location = str("location"),
                allowedValueLinks = links.list("allowedValues"),
                embeddedAllowedValues = embedded?.mapNotNull { it as? JsonObject }.orEmpty(),
            )
        }
    }
}

/**
 * A `QueryFilterInstanceSchema`: which operators a filter supports and, per operator, the type of its values
 * (`[]Status`, `[]User`, `[1]String`, `[1]Integer`, `[2]Date`, ...). Operators without values map to `null`.
 */
data class FilterInstanceSchema(
    /** Filter id as used in the `filters` parameter, e.g. "status", "assignee", "customField12". */
    val id: String,
    /** Display name when the server provides one (via the `filter` allowed values), otherwise null. */
    val displayName: String?,
    val operatorValueTypes: Map<String, String?>,
    /** Allowed values per operator when the server lists them (e.g. custom field options). */
    val operatorAllowedValues: Map<String, List<HalLink>> = emptyMap(),
) {
    companion object {
        private const val OPERATOR_PREFIX = "/api/v3/queries/operators/"

        fun from(json: JsonObject): FilterInstanceSchema {
            val schema = Schema(json)
            val id = schema.links["self"]?.id.orEmpty()
            val displayName = schema["filter"]?.allowedValueLinks?.firstOrNull()?.title
            val operators = linkedMapOf<String, String?>()
            val allowed = linkedMapOf<String, List<HalLink>>()
            (json["_dependencies"] as? JsonArray)?.forEach { dependency ->
                val dependencies = (dependency as? JsonObject)?.get("dependencies") as? JsonObject ?: return@forEach
                dependencies.forEach { (operatorHref, valuesSchema) ->
                    val operator = URLDecoder.decode(operatorHref.substringAfter(OPERATOR_PREFIX), StandardCharsets.UTF_8)
                    val values = (valuesSchema as? JsonObject)?.get("values") as? JsonObject
                    operators[operator] = (values?.get("type") as? JsonPrimitive)?.contentOrNull
                    values?.let { SchemaField.from("values", it).allowedOptions }?.takeIf { it.isNotEmpty() }?.let { allowed[operator] = it }
                }
            }
            return FilterInstanceSchema(id, displayName, operators, allowed)
        }

        fun listFrom(collection: JsonObject): List<FilterInstanceSchema> =
            ((collection["_embedded"] as? JsonObject)?.get("elements")?.jsonArray ?: JsonArray(emptyList()))
                .mapNotNull { (it as? JsonObject)?.let(::from) }
    }
}
