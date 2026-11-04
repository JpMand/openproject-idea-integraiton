package com.github.jpmand.openproject.idea.api

import com.github.jpmand.openproject.idea.api.hal.UriTemplate
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

class QueryBuildingTest {

    @Test
    fun `filters serialize to OpenProject JSON and back`() {
        val filters = listOf(Filters.openStatus(), Filters.assignee(42), Filters.search("login page"))
        val json = Filters.toJson(filters)
        assertEquals(
            """[{"status":{"operator":"o","values":[]}},{"assignee":{"operator":"=","values":["42"]}},{"search":{"operator":"**","values":["login page"]}}]""",
            json,
        )
        assertEquals(filters, Filters.fromJson(json))
        assertEquals(emptyList<FilterSpec>(), Filters.fromJson("not json"))
    }

    @Test
    fun `sort criteria serialize to pairs`() {
        val sort = listOf(SortSpec("status"), SortSpec("updatedAt", ascending = false))
        val json = Filters.sortToJson(sort)
        assertEquals("""[["status","asc"],["updatedAt","desc"]]""", json)
        assertEquals(sort, Filters.sortFromJson(json))
    }

    @Test
    fun `patch keeps lock version and explicit null links`() {
        val body = WorkPackagePatch(lockVersion = 7).subject("New title").link("assignee", null).link("status", "/api/v3/statuses/2").build()
        assertEquals(JsonPrimitive(7), body["lockVersion"])
        assertEquals(JsonPrimitive("New title"), body["subject"])
        val links = body["_links"] as JsonObject
        assertEquals(JsonNull, (links["assignee"] as JsonObject)["href"])
        assertEquals(JsonPrimitive("/api/v3/statuses/2"), (links["status"] as JsonObject)["href"])
        assertEquals("""{"lockVersion":7,"description":{"raw":"x"}}""", WorkPackagePatch(7).description("x").build().toString())
    }

    @Test
    fun `durations parse user input and ISO`() {
        assertEquals(Duration.ofMinutes(90), Durations.parseHuman("1h 30m"))
        assertEquals(Duration.ofMinutes(90), Durations.parseHuman("1.5"))
        assertEquals(Duration.ofMinutes(90), Durations.parseHuman("1,5"))
        assertEquals(Duration.ofMinutes(90), Durations.parseHuman("1:30"))
        assertEquals(Duration.ofMinutes(45), Durations.parseHuman("45m"))
        assertEquals(Duration.ofHours(2), Durations.parseHuman("2h"))
        assertEquals(Duration.ofMinutes(90), Durations.parseHuman("PT1H30M"))
        assertNull(Durations.parseHuman("soon"))
        assertNull(Durations.parseHuman(""))
        assertEquals(Duration.ofHours(42), Durations.parseIso("P1DT18H"))
        assertEquals("1h 30m", Durations.formatHuman(Duration.ofMinutes(90)))
        assertEquals("PT1H30M", Durations.toIso(Duration.ofMinutes(90)))
    }

    @Test
    fun `templated links expand encoded placeholders`() {
        val template = "/api/v3/work_packages?offset=%7Boffset%7D&pageSize=%7Bsize%7D&sortBy=x"
        assertEquals("/api/v3/work_packages?offset=3&pageSize=20&sortBy=x", UriTemplate.expand(template, mapOf("offset" to 3, "size" to 20)))
        assertEquals("/a/7", UriTemplate.expand("/a/{id}", mapOf("id" to 7)))
    }

    @Test
    fun `base urls are normalized`() {
        assertEquals("https://op.example.com", OpenProjectUrls.normalizeBaseUrl("op.example.com/"))
        assertEquals("https://example.com/openproject", OpenProjectUrls.normalizeBaseUrl("https://example.com/openproject/api/v3"))
        assertTrue(OpenProjectUrls.normalizeBaseUrl("http://localhost:8080").startsWith("http://"))
    }
}
