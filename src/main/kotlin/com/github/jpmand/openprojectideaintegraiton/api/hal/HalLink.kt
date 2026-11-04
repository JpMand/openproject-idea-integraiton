package com.github.jpmand.openprojectideaintegraiton.api.hal

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/**
 * A HAL link object. `href == null` means "not set" (e.g. no assignee), not "missing".
 * `method` is lowercase in OpenProject responses ("patch", "post", "delete").
 */
@Serializable
data class HalLink(
    val href: String? = null,
    val title: String? = null,
    val templated: Boolean = false,
    val method: String? = null,
) {
    /** Last path segment of [href], e.g. "7" for "/api/v3/statuses/7". */
    val id: String? get() = href?.substringBefore('?')?.trimEnd('/')?.substringAfterLast('/')
}

/**
 * The `_links` object of a HAL resource. OpenProject renders some relations as a single link object and
 * others (multi-value custom fields, `ancestors`, `customActions`, ...) as arrays; both are normalised to lists.
 */
@Serializable(with = HalLinksSerializer::class)
class HalLinks(val all: Map<String, List<HalLink>>) {

    operator fun get(rel: String): HalLink? = all[rel]?.firstOrNull()

    fun list(rel: String): List<HalLink> = all[rel].orEmpty()

    fun href(rel: String): String? = get(rel)?.href

    fun title(rel: String): String? = get(rel)?.title

    /** True when the relation exists and points somewhere; action links are only present when permitted. */
    fun has(rel: String): Boolean = all[rel]?.any { it.href != null } == true

    override fun equals(other: Any?): Boolean = other is HalLinks && other.all == all

    override fun hashCode(): Int = all.hashCode()

    override fun toString(): String = "HalLinks($all)"

    companion object {
        val EMPTY = HalLinks(emptyMap())
    }
}

object HalLinksSerializer : KSerializer<HalLinks> {
    private val delegate = MapSerializer(String.serializer(), ListSerializer(HalLink.serializer()))

    override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

    override fun deserialize(decoder: Decoder): HalLinks {
        val input = decoder as JsonDecoder
        val normalized = input.decodeJsonElement().jsonObject.mapValues { (_, value) ->
            when (value) {
                is JsonArray -> value
                is JsonNull -> JsonArray(emptyList())
                else -> JsonArray(listOf(value))
            }
        }
        return HalLinks(input.json.decodeFromJsonElement(delegate, JsonObject(normalized)))
    }

    override fun serialize(encoder: Encoder, value: HalLinks) {
        val output = encoder as JsonEncoder
        output.encodeJsonElement(buildJsonObject {
            value.all.forEach { (rel, links) ->
                val elements = links.map { output.json.encodeToJsonElement(HalLink.serializer(), it) }
                put(rel, if (elements.size == 1) elements.single() else JsonArray(elements))
            }
        })
    }
}
