package com.github.jpmand.openprojectideaintegraiton.api.hal

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Minimal expansion of the simple `{name}` templates used by OpenProject's templated links
 * (e.g. `jumpTo` -> `...offset=%7Boffset%7D...`). The placeholders arrive percent-encoded inside query strings.
 */
object UriTemplate {
    private val placeholder = Regex("""\{([A-Za-z0-9_]+)}|%7B([A-Za-z0-9_]+)%7D""", RegexOption.IGNORE_CASE)

    fun expand(template: String, values: Map<String, Any?>): String =
        placeholder.replace(template) { match ->
            val name = match.groupValues[1].ifEmpty { match.groupValues[2] }
            values[name]?.let { URLEncoder.encode(it.toString(), StandardCharsets.UTF_8) } ?: ""
        }
}
