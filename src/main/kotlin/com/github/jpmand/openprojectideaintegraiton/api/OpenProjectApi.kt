package com.github.jpmand.openprojectideaintegraiton.api

import com.github.jpmand.openprojectideaintegraiton.api.hal.HalCollection
import com.github.jpmand.openprojectideaintegraiton.api.hal.HalJson
import com.github.jpmand.openprojectideaintegraiton.api.hal.HalLink
import com.github.jpmand.openprojectideaintegraiton.api.model.Activity
import com.github.jpmand.openprojectideaintegraiton.api.model.Attachment
import com.github.jpmand.openprojectideaintegraiton.api.model.Configuration
import com.github.jpmand.openprojectideaintegraiton.api.model.FilterInstanceSchema
import com.github.jpmand.openprojectideaintegraiton.api.model.Form
import com.github.jpmand.openprojectideaintegraiton.api.model.Principal
import com.github.jpmand.openprojectideaintegraiton.api.model.Priority
import com.github.jpmand.openprojectideaintegraiton.api.model.Project
import com.github.jpmand.openprojectideaintegraiton.api.model.Query
import com.github.jpmand.openprojectideaintegraiton.api.model.Relation
import com.github.jpmand.openprojectideaintegraiton.api.model.Root
import com.github.jpmand.openprojectideaintegraiton.api.model.Schema
import com.github.jpmand.openprojectideaintegraiton.api.model.Status
import com.github.jpmand.openprojectideaintegraiton.api.model.TimeEntry
import com.github.jpmand.openprojectideaintegraiton.api.model.Version
import com.github.jpmand.openprojectideaintegraiton.api.model.WorkPackage
import com.github.jpmand.openprojectideaintegraiton.api.model.WorkPackageType
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.URLBuilder
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.http.takeFrom
import io.ktor.utils.io.jvm.javaio.copyTo
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.OutputStream

/** One page of work packages plus the schemas OpenProject embeds for the listed (project, type) pairs. */
data class WorkPackagePage(
    val elements: List<WorkPackage>,
    val total: Int,
    val page: Int,
    val pageSize: Int,
    val schemas: List<Schema>,
)

/**
 * Typed facade over the OpenProject API v3. All calls are suspending and throw [OpenProjectApiException] for
 * error responses. Whenever a resource offers an action link (`addComment`, `addAttachment`, `logTime`, ...)
 * the link is followed instead of building the URL, so permissions and server versions are respected.
 */
class OpenProjectApi(private val http: HttpClient, baseUrl: String) : AutoCloseable {

    val baseUrl: String = OpenProjectUrls.normalizeBaseUrl(baseUrl)

    fun apiUrl(path: String): String = "${this.baseUrl}/api/v3" + if (path.isEmpty()) "" else "/${path.trimStart('/')}"

    /** Resolves a HAL `href` (an absolute path like "/api/v3/statuses/1", or a full URL) against the installation. */
    fun resolve(href: String): String = URLBuilder(baseUrl).apply { takeFrom(href) }.buildString()

    fun workPackageWebUrl(id: Long): String = "$baseUrl/work_packages/$id"

    // ---- generic requests ----

    @PublishedApi
    internal suspend fun execute(
        method: HttpMethod,
        url: String,
        body: Any? = null,
        configure: HttpRequestBuilder.() -> Unit = {},
    ): HttpResponse {
        val response = http.request(url) {
            this.method = method
            when (body) {
                null -> Unit
                // The static type matters: as `Any`, Ktor would guess serializers per element and fail.
                is JsonObject -> {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
                else -> setBody(body)
            }
            configure()
        }
        if (!response.status.isSuccess()) {
            throw OpenProjectApiException.from(response.status.value, runCatching { response.bodyAsText() }.getOrNull(), url)
        }
        return response
    }

    suspend inline fun <reified T> get(url: String, noinline configure: HttpRequestBuilder.() -> Unit = {}): T =
        execute(HttpMethod.Get, url, null, configure).body()

    suspend inline fun <reified T> send(method: HttpMethod, url: String, body: JsonObject? = null): T =
        execute(method, url, body).body()

    /** Follows an action link with the method it declares (default GET). */
    suspend fun follow(link: HalLink, body: JsonObject? = null): JsonObject {
        val href = link.href ?: throw IllegalArgumentException("Link has no href")
        val method = link.method?.let { HttpMethod.parse(it.uppercase()) } ?: HttpMethod.Get
        val response = execute(method, resolve(href), body)
        return if (response.status.value == 204) JsonObject(emptyMap()) else response.body()
    }

    // ---- instance ----

    suspend fun root(): Root = get(apiUrl(""))

    suspend fun me(): Principal = get(apiUrl("users/me"))

    suspend fun configuration(): Configuration = get(apiUrl("configuration"))

    suspend fun statuses(): List<Status> = get<HalCollection<Status>>(apiUrl("statuses")).elements

    suspend fun types(): List<WorkPackageType> = get<HalCollection<WorkPackageType>>(apiUrl("types")).elements

    suspend fun priorities(): List<Priority> = get<HalCollection<Priority>>(apiUrl("priorities")).elements

    suspend fun projects(): List<Project> =
        get<HalCollection<Project>>(apiUrl("projects")) {
            parameter("pageSize", 1000)
            parameter("filters", Filters.toJson(listOf(FilterSpec("active", "=", listOf("t")))))
            parameter("sortBy", Filters.sortToJson(listOf(SortSpec("name"))))
        }.elements

    suspend fun principals(filters: List<FilterSpec> = emptyList(), pageSize: Int = 200): List<Principal> =
        get<HalCollection<Principal>>(apiUrl("principals")) {
            parameter("pageSize", pageSize)
            if (filters.isNotEmpty()) parameter("filters", Filters.toJson(filters))
        }.elements

    suspend fun versions(): List<Version> = get<HalCollection<Version>>(apiUrl("versions")).elements

    /** Fetches any resource by its HAL href as raw JSON. */
    suspend fun getJson(href: String): JsonObject = get<JsonObject>(resolve(href))

    // ---- work packages ----

    suspend fun workPackages(query: WorkPackageQuery): WorkPackagePage {
        val collection = get<HalCollection<JsonObject>>(apiUrl("work_packages")) {
            parameter("filters", Filters.toJson(query.filters))
            parameter("sortBy", Filters.sortToJson(query.sortBy))
            parameter("offset", query.page)
            parameter("pageSize", query.pageSize)
            parameter("includeSubprojects", query.includeSubprojects)
        }
        return WorkPackagePage(
            elements = collection.elements.map(::decodeWorkPackage),
            total = collection.total,
            page = collection.offset ?: query.page,
            pageSize = collection.pageSize ?: query.pageSize,
            schemas = schemasOf(collection.embedded.schemas),
        )
    }

    suspend fun workPackage(id: Long): WorkPackage = decodeWorkPackage(get(apiUrl("work_packages/$id")))

    suspend fun workPackage(href: String): WorkPackage = decodeWorkPackage(get(resolve(href)))

    /** Creates a work package; [payload] needs at least `subject` and `_links.project` (and usually `_links.type`). */
    suspend fun createWorkPackage(payload: JsonObject, notify: Boolean = false): WorkPackage =
        decodeWorkPackage(execute(HttpMethod.Post, apiUrl("work_packages")) {
            contentType(ContentType.Application.Json)
            setBody(payload)
            parameter("notify", notify)
        }.body())

    /** Validates a change and returns the form, including the schema with allowed values (e.g. statuses). */
    suspend fun workPackageForm(workPackage: WorkPackage, patch: WorkPackagePatch): Form =
        send(HttpMethod.Post, apiUrl("work_packages/${workPackage.id}/form"), patch.build())

    suspend fun updateWorkPackage(workPackage: WorkPackage, patch: WorkPackagePatch, notify: Boolean = true): WorkPackage {
        val href = workPackage.links.href("updateImmediately") ?: workPackage.links.href("self") ?: apiUrl("work_packages/${workPackage.id}")
        return decodeWorkPackage(execute(HttpMethod.Patch, resolve(href)) {
            contentType(ContentType.Application.Json)
            setBody(patch.build())
            parameter("notify", notify)
        }.body())
    }

    /** Users the work package can be assigned to. */
    suspend fun availableAssignees(workPackage: WorkPackage): List<Principal> =
        get<HalCollection<Principal>>(apiUrl("work_packages/${workPackage.id}/available_assignees")) { parameter("pageSize", 500) }.elements

    suspend fun deleteWorkPackage(workPackage: WorkPackage) {
        execute(HttpMethod.Delete, resolve(workPackage.links.href("self") ?: apiUrl("work_packages/${workPackage.id}")))
    }

    suspend fun workPackageSchema(href: String): Schema = Schema(get(resolve(href)))

    /** Candidates for a new relation of [type] ("relates", "follows", ...) matching [query] (id or subject). */
    suspend fun relationCandidates(workPackage: WorkPackage, query: String, type: String, pageSize: Int = 20): List<WorkPackage> =
        get<HalCollection<JsonObject>>(apiUrl("work_packages/${workPackage.id}/available_relation_candidates")) {
            parameter("query", query)
            parameter("type", type)
            parameter("pageSize", pageSize)
        }.elements.map(::decodeWorkPackage)

    // ---- activity ----

    suspend fun activities(workPackage: WorkPackage): List<Activity> {
        val href = workPackage.links.href("activities") ?: apiUrl("work_packages/${workPackage.id}/activities")
        return get<HalCollection<Activity>>(resolve(href)).elements
    }

    suspend fun addComment(workPackage: WorkPackage, raw: String, internal: Boolean = false, notify: Boolean = true): Activity {
        val href = workPackage.links.href("addComment") ?: apiUrl("work_packages/${workPackage.id}/activities")
        val body = buildJsonObject {
            putJsonObject("comment") { put("raw", raw) }
            if (internal) put("internal", true)
        }
        return execute(HttpMethod.Post, resolve(href)) {
            contentType(ContentType.Application.Json)
            setBody(body)
            parameter("notify", notify)
        }.body()
    }

    suspend fun updateComment(activity: Activity, raw: String): Activity {
        val href = activity.links.href("update") ?: activity.links.href("self") ?: error("Activity cannot be updated")
        return send(HttpMethod.Patch, resolve(href), buildJsonObject { putJsonObject("comment") { put("raw", raw) } })
    }

    // ---- attachments ----

    suspend fun attachments(workPackage: WorkPackage): List<Attachment> {
        val href = workPackage.links.href("attachments") ?: apiUrl("work_packages/${workPackage.id}/attachments")
        return get<HalCollection<Attachment>>(resolve(href)).elements
    }

    /** Uploads via the `addAttachment` link (multipart: a `metadata` JSON part and a `file` part). */
    suspend fun uploadAttachment(
        workPackage: WorkPackage,
        fileName: String,
        contentType: String?,
        bytes: ByteArray,
        description: String? = null,
    ): Attachment {
        val href = workPackage.links.href("addAttachment") ?: apiUrl("work_packages/${workPackage.id}/attachments")
        val metadata = buildJsonObject {
            put("fileName", fileName)
            if (!description.isNullOrBlank()) putJsonObject("description") { put("raw", description) }
        }
        val form = MultiPartFormDataContent(formData {
            append("metadata", metadata.toString(), Headers.build {
                append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            })
            append("file", bytes, Headers.build {
                append(HttpHeaders.ContentType, contentType ?: ContentType.Application.OctetStream.toString())
                append(HttpHeaders.ContentDisposition, "form-data; name=\"file\"; filename=\"${fileName.replace("\"", "")}\"")
            })
        })
        return execute(HttpMethod.Post, resolve(href), form) {
            timeout { requestTimeoutMillis = 10 * 60_000L }
        }.body()
    }

    suspend fun deleteAttachment(attachment: Attachment) {
        val href = attachment.links.href("delete") ?: attachment.links.href("self") ?: apiUrl("attachments/${attachment.id}")
        execute(HttpMethod.Delete, resolve(href))
    }

    /** Streams the file behind [href] (e.g. an attachment's `downloadLocation`) into [sink]. */
    suspend fun download(href: String, sink: OutputStream) {
        execute(HttpMethod.Get, resolve(href)) {
            timeout { requestTimeoutMillis = 10 * 60_000L }
        }.bodyAsChannel().copyTo(sink)
    }

    // ---- relations ----

    suspend fun relations(workPackage: WorkPackage): List<Relation> =
        get<HalCollection<Relation>>(apiUrl("relations")) {
            parameter("filters", Filters.toJson(listOf(FilterSpec("involved", "=", listOf(workPackage.id.toString())))))
            parameter("pageSize", 1000)
        }.elements

    suspend fun createRelation(workPackage: WorkPackage, type: String, toHref: String, description: String? = null, lag: Int? = null): Relation {
        val href = workPackage.links.href("addRelation") ?: apiUrl("work_packages/${workPackage.id}/relations")
        val body = buildJsonObject {
            put("type", type)
            if (!description.isNullOrBlank()) put("description", description)
            if (lag != null) put("lag", lag)
            putJsonObject("_links") { putJsonObject("to") { put("href", toHref) } }
        }
        return send(HttpMethod.Post, resolve(href), body)
    }

    suspend fun deleteRelation(relation: Relation) {
        val href = relation.links.href("delete") ?: relation.links.href("self") ?: apiUrl("relations/${relation.id}")
        execute(HttpMethod.Delete, resolve(href))
    }

    // ---- queries ----

    suspend fun queries(): List<Query> = get<HalCollection<Query>>(apiUrl("queries")) { parameter("pageSize", 500) }.elements

    suspend fun query(id: Long): Query = get(apiUrl("queries/$id"))

    suspend fun defaultQuery(projectId: Long? = null): Query =
        get(if (projectId == null) apiUrl("queries/default") else apiUrl("projects/$projectId/queries/default"))

    /** The query form's schema lists the columns and sort criteria the server allows. */
    suspend fun queryForm(payload: JsonObject = JsonObject(emptyMap())): Form = send(HttpMethod.Post, apiUrl("queries/form"), payload)

    suspend fun filterInstanceSchemas(projectId: Long? = null): List<FilterInstanceSchema> {
        val url = if (projectId == null) apiUrl("queries/filter_instance_schemas") else apiUrl("projects/$projectId/queries/filter_instance_schemas")
        return FilterInstanceSchema.listFrom(get(url))
    }

    // ---- time entries ----

    suspend fun timeEntryForm(payload: JsonObject): Form = send(HttpMethod.Post, apiUrl("time_entries/form"), payload)

    suspend fun createTimeEntry(payload: JsonObject): TimeEntry = send(HttpMethod.Post, apiUrl("time_entries"), payload)

    suspend fun timeEntries(filters: List<FilterSpec>): List<TimeEntry> =
        get<HalCollection<TimeEntry>>(apiUrl("time_entries")) {
            parameter("filters", Filters.toJson(filters))
            parameter("pageSize", 200)
        }.elements

    suspend fun deleteTimeEntry(entry: TimeEntry) {
        execute(HttpMethod.Delete, resolve(entry.links.href("delete") ?: entry.links.href("self") ?: apiUrl("time_entries/${entry.id}")))
    }

    // ---- markup ----

    /** Renders Markdown as OpenProject would, using the work package's `previewMarkup` link when available. */
    suspend fun renderMarkdown(raw: String, workPackage: WorkPackage? = null): String {
        val href = workPackage?.links?.href("previewMarkup") ?: apiUrl("render/markdown")
        val response = execute(HttpMethod.Post, resolve(href), raw) { contentType(ContentType.Text.Plain) }
        val text = response.bodyAsText()
        if (!text.trimStart().startsWith("{")) return text
        val json = HalJson.read.parseToJsonElement(text) as? JsonObject ?: return text
        return (json["html"] as? JsonPrimitive)?.contentOrNull ?: text
    }

    override fun close() = http.close()

    companion object {
        fun decodeWorkPackage(json: JsonObject): WorkPackage =
            HalJson.read.decodeFromJsonElement(WorkPackage.serializer(), json).copy(raw = json)

        fun schemasOf(collection: JsonObject?): List<Schema> {
            val elements = (collection?.get("_embedded") as? JsonObject)?.get("elements") as? kotlinx.serialization.json.JsonArray
            return elements?.mapNotNull { (it as? JsonObject)?.let(::Schema) }.orEmpty()
        }
    }
}
