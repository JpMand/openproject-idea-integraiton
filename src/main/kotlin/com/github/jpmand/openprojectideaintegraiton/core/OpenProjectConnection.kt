package com.github.jpmand.openprojectideaintegraiton.core

import com.github.jpmand.openprojectideaintegraiton.api.OpenProjectApi
import com.github.jpmand.openprojectideaintegraiton.api.model.Configuration
import com.github.jpmand.openprojectideaintegraiton.api.model.FilterInstanceSchema
import com.github.jpmand.openprojectideaintegraiton.api.model.Principal
import com.github.jpmand.openprojectideaintegraiton.api.model.Priority
import com.github.jpmand.openprojectideaintegraiton.api.model.Project
import com.github.jpmand.openprojectideaintegraiton.api.model.Status
import com.github.jpmand.openprojectideaintegraiton.api.model.WorkPackageType
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

/**
 * An [OpenProjectApi] for one server and token, plus short-lived caches of the reference data that lists,
 * filters and the Tasks connector need over and over (statuses, types, the current user, ...).
 */
class OpenProjectConnection(val api: OpenProjectApi) : AutoCloseable {

    private class Entry(val value: Any?, val loadedAt: TimeSource.Monotonic.ValueTimeMark)

    private val cache = ConcurrentHashMap<String, Entry>()

    val baseUrl: String get() = api.baseUrl

    suspend fun me(): Principal = cached("me", 60.minutes) { api.me() }

    suspend fun configuration(): Configuration = cached("configuration", 60.minutes) { api.configuration() }

    suspend fun statuses(): List<Status> = cached("statuses") { api.statuses() }

    suspend fun types(): List<WorkPackageType> = cached("types") { api.types() }

    suspend fun priorities(): List<Priority> = cached("priorities") { api.priorities() }

    suspend fun projects(): List<Project> = cached("projects") { api.projects() }

    suspend fun filterSchemas(projectId: Long?): List<FilterInstanceSchema> =
        cached("filterSchemas:$projectId", 60.minutes) { api.filterInstanceSchemas(projectId) }

    /** Status by href, e.g. to decide whether a work package is closed. */
    suspend fun statusByHref(href: String?): Status? = href?.let { h -> statuses().firstOrNull { it.links.href("self") == h || h.endsWith("/statuses/${it.id}") } }

    fun invalidate() = cache.clear()

    @Suppress("UNCHECKED_CAST")
    suspend fun <T> cached(key: String, ttl: Duration = 10.minutes, load: suspend () -> T): T {
        cache[key]?.takeIf { it.loadedAt.elapsedNow() < ttl }?.let { return it.value as T }
        val value = load()
        cache[key] = Entry(value, TimeSource.Monotonic.markNow())
        return value
    }

    override fun close() {
        cache.clear()
        api.close()
    }
}
