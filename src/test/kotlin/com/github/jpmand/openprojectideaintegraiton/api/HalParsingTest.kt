package com.github.jpmand.openprojectideaintegraiton.api

import com.github.jpmand.openprojectideaintegraiton.api.hal.HalCollection
import com.github.jpmand.openprojectideaintegraiton.api.hal.HalJson
import com.github.jpmand.openprojectideaintegraiton.api.hal.HalLink
import com.github.jpmand.openprojectideaintegraiton.api.hal.HalLinks
import com.github.jpmand.openprojectideaintegraiton.api.model.Activity
import com.github.jpmand.openprojectideaintegraiton.api.model.Configuration
import com.github.jpmand.openprojectideaintegraiton.api.model.FilterInstanceSchema
import com.github.jpmand.openprojectideaintegraiton.api.model.Query
import com.github.jpmand.openprojectideaintegraiton.api.model.Status
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HalParsingTest {

    @Test
    fun `work package collection parses with page metadata and embedded schemas`() {
        val collection = HalJson.read.decodeFromString(HalCollection.serializer(JsonObject.serializer()), Fixtures.text("work_package_collection.json"))
        assertEquals("WorkPackageCollection", collection.type)
        assertTrue(collection.total > 0)
        assertEquals(1, collection.offset)
        assertTrue(collection.links["jumpTo"]!!.templated)

        val workPackages = collection.elements.map(OpenProjectApi::decodeWorkPackage)
        assertEquals(3, workPackages.size)
        val first = workPackages.first()
        assertTrue(first.id > 0)
        assertTrue(first.subject.isNotBlank())
        assertNotNull(first.statusTitle)
        assertEquals(first.id, (first.raw["id"] as JsonPrimitive).content.toLong())

        val schemas = OpenProjectApi.schemasOf(collection.embedded.schemas)
        assertEquals(1, schemas.size)
        assertEquals("Formattable", schemas.single()["description"]?.type)
        assertTrue(schemas.single()["status"]!!.isLink)
    }

    @Test
    fun `links normalise single objects and arrays and keep null hrefs`() {
        val workPackage = OpenProjectApi.decodeWorkPackage(Fixtures.json("work_package.json"))
        val links = workPackage.links
        assertTrue(links.has("self"))
        // "assignee": {"href": null} is present but not set
        assertNotNull(links["assignee"])
        assertNull(links.href("assignee"))
        assertFalse(links.has("assignee"))
        // "targetVersions" and "ancestors" are arrays in the response
        assertEquals(emptyList<Any>(), links.list("ancestors"))
        assertEquals("1", HalLinks(mapOf("status" to listOf(HalLink("/api/v3/statuses/1"))))["status"]!!.id)
    }

    @Test
    fun `links serialize back to single objects and arrays`() {
        val links = HalLinks(mapOf("self" to listOf(HalLink("/a")), "children" to listOf(HalLink("/b"), HalLink("/c"))))
        val json = HalJson.read.encodeToString(HalLinks.serializer(), links)
        assertEquals("""{"self":{"href":"/a"},"children":[{"href":"/b"},{"href":"/c"}]}""", json)
        assertEquals(links, HalJson.read.decodeFromString(HalLinks.serializer(), json))
    }

    @Test
    fun `display id and lock version are read`() {
        val workPackage = OpenProjectApi.decodeWorkPackage(Fixtures.json("work_package.json"))
        assertEquals("AGILE-1", workPackage.displayId)
        assertEquals("AGILE-1", workPackage.presentableId)
        assertTrue(workPackage.lockVersion > 0)
        assertEquals("markdown", workPackage.description?.format)
    }

    @Test
    fun `activities distinguish comments from changes`() {
        val activities = HalJson.read.decodeFromString(HalCollection.serializer(Activity.serializer()), Fixtures.text("activities.json")).elements
        assertTrue(activities.any { it.isComment })
        assertTrue(activities.any { it.type == "Activity" })
        assertTrue(activities.filter { it.type == "Activity" }.all { it.details.isNotEmpty() })
    }

    @Test
    fun `statuses expose closed flag`() {
        val statuses = HalJson.read.decodeFromString(HalCollection.serializer(Status.serializer()), Fixtures.text("statuses.json")).elements
        assertTrue(statuses.any { it.isClosed })
        assertTrue(statuses.any { !it.isClosed })
    }

    @Test
    fun `configuration reads page size limits`() {
        val configuration = HalJson.read.decodeFromString(Configuration.serializer(), Fixtures.text("configuration.json"))
        assertTrue(configuration.maximumAPIV3PageSize >= 100)
        assertTrue(configuration.perPageOptions.isNotEmpty())
    }

    @Test
    fun `filter instance schemas expose operators and value types`() {
        val schemas = FilterInstanceSchema.listFrom(Fixtures.json("filter_instance_schemas.json")).associateBy { it.id }
        val status = schemas.getValue("status")
        assertTrue("o" in status.operatorValueTypes)
        assertNull(status.operatorValueTypes["o"])
        assertEquals("[]Status", status.operatorValueTypes["="])
        assertEquals("[]User", schemas.getValue("assignee").operatorValueTypes["="])
        assertEquals("[1]String", schemas.getValue("search").operatorValueTypes["**"])
    }

    @Test
    fun `default query filters convert to filter specs`() {
        val query = HalJson.read.decodeFromString(Query.serializer(), Fixtures.text("query_default.json"))
        val filters = Filters.fromQueryFilters(query.filters)
        assertEquals(listOf(FilterSpec("status", "o")), filters)
        val columns = query.links.list("columns").mapNotNull { it.id }
        assertTrue("subject" in columns)
        assertEquals("id-asc", query.links.list("sortBy").single().id)
    }

    @Test
    fun `error responses are mapped`() {
        val single = OpenProjectApiException.from(400, Fixtures.text("error_invalid_query.json"), null)
        assertEquals(OpenProjectApiException.INVALID_QUERY, single.errorIdentifier)
        assertEquals("Assignee filter has invalid values.", single.message)

        val multiple = OpenProjectApiException.from(422, Fixtures.text("error_multiple.json"), null)
        assertTrue(multiple.isConstraintViolation)
        assertEquals(listOf("subject", "startDate"), multiple.erroneousFields)
        assertTrue(multiple.message!!.contains("Subject can't be blank."))

        val conflict = OpenProjectApiException.from(409, """{"_type":"Error","errorIdentifier":"urn:openproject-org:api:v3:errors:UpdateConflict","message":"Conflict"}""", null)
        assertTrue(conflict.isConflict)

        val html = OpenProjectApiException.from(502, "<html>Bad gateway</html>", null)
        assertEquals("OpenProject request failed with HTTP 502.", html.message)
    }
}
