package com.github.jpmand.openproject.idea.api

import com.github.jpmand.openproject.idea.api.hal.HalJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A failed OpenProject API call, carrying the server's `Error` resource when there is one. */
class OpenProjectApiException(
    val status: Int,
    val errorIdentifier: String?,
    message: String,
    val errors: List<OpenProjectError> = emptyList(),
    val url: String? = null,
) : Exception(message) {

    val isUnauthorized: Boolean get() = status == 401
    val isMissingPermission: Boolean get() = status == 403
    val isNotFound: Boolean get() = status == 404
    val isConflict: Boolean get() = status == 409 || errorIdentifier == UPDATE_CONFLICT
    val isConstraintViolation: Boolean get() = status == 422

    /** Property names the server rejected (from `erroneousField` details). */
    val erroneousFields: List<String> get() = errors.mapNotNull { it.erroneousField }

    companion object {
        private const val PREFIX = "urn:openproject-org:api:v3:errors:"
        const val UPDATE_CONFLICT = PREFIX + "UpdateConflict"
        const val MULTIPLE_ERRORS = PREFIX + "MultipleErrors"
        const val INVALID_QUERY = PREFIX + "InvalidQuery"

        fun from(status: Int, body: String?, url: String?): OpenProjectApiException {
            val error: ErrorResponse? = body?.takeIf { it.trimStart().startsWith("{") }?.let { text ->
                runCatching { HalJson.read.decodeFromString(ErrorResponse.serializer(), text) }.getOrNull()
            }
            if (error == null || error.type != "Error") {
                val text = body?.trim()?.takeIf { it.isNotEmpty() && it.length < 300 && !it.startsWith("<") }
                return OpenProjectApiException(status, null, text ?: defaultMessage(status), url = url)
            }
            val nested = error.embedded?.errors.orEmpty().map { it.toError() }
            val errors = if (error.errorIdentifier == MULTIPLE_ERRORS) nested else listOf(error.toError())
            val message = if (error.errorIdentifier == MULTIPLE_ERRORS && nested.isNotEmpty()) {
                nested.joinToString("\n") { it.message }
            } else {
                error.message ?: defaultMessage(status)
            }
            return OpenProjectApiException(status, error.errorIdentifier, message, errors, url)
        }

        private fun defaultMessage(status: Int): String = when (status) {
            401 -> "Authentication failed. Check the OpenProject API token."
            403 -> "You are not allowed to perform this action in OpenProject."
            404 -> "The OpenProject resource was not found."
            409 -> "The work package was changed by someone else in the meantime."
            else -> "OpenProject request failed with HTTP $status."
        }
    }
}

data class OpenProjectError(val errorIdentifier: String?, val message: String, val erroneousField: String?)

@Serializable
private data class ErrorResponse(
    @SerialName("_type") val type: String? = null,
    val errorIdentifier: String? = null,
    val message: String? = null,
    @SerialName("_embedded") val embedded: Embedded? = null,
) {
    @Serializable
    data class Embedded(val errors: List<ErrorResponse> = emptyList(), val details: JsonObject? = null)

    fun toError() = OpenProjectError(
        errorIdentifier = errorIdentifier,
        message = message.orEmpty(),
        // The docs name it "erroneousField"; servers also send "attribute".
        erroneousField = (embedded?.details?.get("erroneousField") as? JsonPrimitive)?.contentOrNull
            ?: (embedded?.details?.get("attribute") as? JsonPrimitive)?.contentOrNull,
    )
}
