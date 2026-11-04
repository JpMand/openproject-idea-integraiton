package com.github.jpmand.openproject.idea.it

import com.github.jpmand.openproject.idea.api.FilterSpec
import com.github.jpmand.openproject.idea.api.Filters
import com.github.jpmand.openproject.idea.api.OpenProjectApi
import com.github.jpmand.openproject.idea.api.OpenProjectApiException
import com.github.jpmand.openproject.idea.api.SortSpec
import com.github.jpmand.openproject.idea.api.WorkPackagePatch
import com.github.jpmand.openproject.idea.api.WorkPackageQuery
import com.github.jpmand.openproject.idea.api.model.WorkPackage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.LocalDate

/** Exercises [OpenProjectApi] against a real OpenProject (scripts/openproject-it.sh). */
class OpenProjectApiIntegrationTest {

    companion object {
        private const val PREFIX = "IT-api "
        private lateinit var api: OpenProjectApi

        @JvmStatic
        @BeforeClass
        fun setUp() {
            OpenProjectIntegration.assumeConfigured()
            api = OpenProjectIntegration.newApi()
            OpenProjectIntegration.cleanUp(api, PREFIX)
        }

        @JvmStatic
        @AfterClass
        fun tearDown() {
            if (::api.isInitialized) {
                OpenProjectIntegration.cleanUp(api, PREFIX)
                api.close()
            }
        }

        private fun note(message: String) = println("[openproject-it] $message")
    }

    private fun create(subject: String): WorkPackage = runBlocking { OpenProjectIntegration.createWorkPackage(api, PREFIX + subject) }

    @Test
    fun `instance metadata and reference data`() = runBlocking {
        val me = api.me()
        assertEquals("admin", me.login)
        val root = api.root()
        note("instance=${root.instanceName} coreVersion=${root.coreVersion}")
        assertTrue(api.statuses().any { it.isClosed })
        assertTrue(api.types().isNotEmpty())
        assertTrue(api.priorities().isNotEmpty())
        assertTrue(api.configuration().maximumAPIV3PageSize > 0)
        assertTrue(api.projects().isNotEmpty())
    }

    @Test
    fun `create read and find work packages`() = runBlocking {
        val created = create("findable")
        val read = api.workPackage(created.id)
        assertEquals(created.subject, read.subject)
        assertEquals("markdown", read.description?.format)
        assertTrue(read.description?.html.orEmpty().contains("<strong>"))

        read.displayId?.let { displayId ->
            note("displayId=$displayId")
            assertEquals(read.id, api.workPackage("/api/v3/work_packages/$displayId").id)
        } ?: note("server has no displayId")

        val byId = api.workPackages(WorkPackageQuery(filters = listOf(Filters.ids(created.id))))
        assertEquals(listOf(created.id), byId.elements.map { it.id })

        val byTypeahead = api.workPackages(WorkPackageQuery(filters = listOf(FilterSpec("typeahead", "**", listOf("findable")))))
        assertTrue(byTypeahead.elements.any { it.id == created.id })

        val bySearch = api.workPackages(WorkPackageQuery(filters = listOf(Filters.search("findable"))))
        assertTrue(bySearch.elements.any { it.id == created.id })
        assertTrue(bySearch.schemas.isNotEmpty())
    }

    @Test
    fun `filters by assignee using the current user id and the me value`() = runBlocking {
        val me = api.me()
        val created = create("assigned")
        api.updateWorkPackage(created, WorkPackagePatch(created.lockVersion).link("assignee", "/api/v3/users/${me.id}"))

        val byId = api.workPackages(WorkPackageQuery(filters = listOf(Filters.openStatus(), Filters.assignee(me.id), Filters.ids(created.id))))
        assertEquals(1, byId.total)

        val byMe = runCatching { api.workPackages(WorkPackageQuery(filters = listOf(FilterSpec("assignee", "=", listOf("me")), Filters.ids(created.id)))) }
        note("assignee=me supported: ${byMe.isSuccess && byMe.getOrNull()?.total == 1}")
    }

    @Test
    fun `sorting and paging`() = runBlocking {
        repeat(3) { create("page $it") }
        val filters = listOf(Filters.search(PREFIX + "page"))
        val ascending = api.workPackages(WorkPackageQuery(filters = filters, sortBy = listOf(SortSpec("id")), pageSize = 2))
        assertTrue(ascending.total >= 3)
        assertEquals(2, ascending.elements.size)
        assertTrue(ascending.elements[0].id < ascending.elements[1].id)
        val secondPage = api.workPackages(WorkPackageQuery(filters = filters, sortBy = listOf(SortSpec("id")), page = 2, pageSize = 2))
        assertTrue(secondPage.elements.first().id > ascending.elements.last().id)
        val descending = api.workPackages(WorkPackageQuery(filters = filters, sortBy = listOf(SortSpec("id", ascending = false)), pageSize = 2))
        assertTrue(descending.elements[0].id > descending.elements[1].id)
    }

    @Test
    fun `update with lock version and detect conflicts`() = runBlocking {
        val created = create("update me")
        val updated = api.updateWorkPackage(created, WorkPackagePatch(created.lockVersion).subject(PREFIX + "updated").description("New *text*"))
        assertEquals(PREFIX + "updated", updated.subject)
        assertEquals("New *text*", updated.description?.raw)
        assertTrue(updated.lockVersion > created.lockVersion)
        try {
            api.updateWorkPackage(created, WorkPackagePatch(created.lockVersion).subject(PREFIX + "stale"))
            fail("expected a conflict for a stale lockVersion")
        } catch (e: OpenProjectApiException) {
            assertTrue("status ${e.status} ${e.errorIdentifier}", e.isConflict)
        }
    }

    @Test
    fun `change status to an allowed value from the form`() = runBlocking {
        val created = create("status")
        val form = api.workPackageForm(created, WorkPackagePatch(created.lockVersion))
        val allowed = form.schema["status"]!!.allowedOptions
        note("allowed statuses: ${allowed.map { it.title }}")
        val target = allowed.first { it.href != created.links.href("status") }
        val updated = api.updateWorkPackage(created, WorkPackagePatch(created.lockVersion).link("status", target.href))
        assertEquals(target.href, updated.links.href("status"))
    }

    @Test
    fun `comment and read activities`() = runBlocking {
        val created = create("comments")
        val comment = api.addComment(created, "Hello from the **IDE**", notify = false)
        assertTrue(comment.isComment)
        val activities = api.activities(created)
        assertTrue(activities.any { it.isComment && it.comment?.raw == "Hello from the **IDE**" })
        assertTrue(activities.first { it.isComment }.comment?.html.orEmpty().contains("<strong>"))
    }

    @Test
    fun `upload download and delete attachments`() = runBlocking {
        val created = api.workPackage(create("files").id)
        val uploaded = api.uploadAttachment(created, "notes.txt", "text/plain", "hello attachment".toByteArray(), "A note")
        assertEquals("notes.txt", uploaded.fileName)
        assertTrue(api.attachments(created).any { it.id == uploaded.id })
        val out = ByteArrayOutputStream()
        api.download(uploaded.links.href("downloadLocation")!!, out)
        assertEquals("hello attachment", out.toString(Charsets.UTF_8))
        api.deleteAttachment(uploaded)
        assertFalse(api.attachments(created).any { it.id == uploaded.id })
    }

    @Test
    fun `create list and delete relations`() = runBlocking {
        val first = api.workPackage(create("relation A").id)
        val second = create("relation B")
        val candidates = api.relationCandidates(first, PREFIX + "relation B", "follows")
        assertTrue(candidates.any { it.id == second.id })
        val relation = api.createRelation(first, "follows", "/api/v3/work_packages/${second.id}", "needs B first")
        assertEquals("follows", relation.type)
        assertTrue(api.relations(first).any { it.id == relation.id })
        api.deleteRelation(relation)
        assertFalse(api.relations(first).any { it.id == relation.id })
    }

    @Test
    fun `log time through the time entry form`() = runBlocking {
        val created = api.workPackage(create("time").id)
        assertTrue(created.links.has("logTime"))
        fun payload(linkName: String): JsonObject = buildJsonObject {
            put("hours", "PT1H30M")
            put("spentOn", LocalDate.now().toString())
            putJsonObject("comment") { put("raw", "IT time") }
            putJsonObject("_links") { putJsonObject(linkName) { put("href", created.links.href("self")!!) } }
        }
        var form = api.timeEntryForm(payload("entity"))
        val linkName = if (form.schema["entity"] == null && form.schema["workPackage"] != null) "workPackage" else "entity"
        if (linkName != "entity") form = api.timeEntryForm(payload(linkName))
        note("time entry work package link: $linkName; activity allowed=${form.schema["activity"]?.allowedOptions?.map { it.title }}; errors=${form.validationErrors}")
        val activity = form.schema["activity"]?.allowedOptions?.firstOrNull()
        val payload = if (form.validationErrors.containsKey("activity") && activity != null) {
            val withActivity = buildJsonObject {
                payload(linkName).forEach { (key, value) -> if (key != "_links") put(key, value) }
                putJsonObject("_links") {
                    putJsonObject(linkName) { put("href", created.links.href("self")!!) }
                    putJsonObject("activity") { put("href", activity.href!!) }
                }
            }
            api.timeEntryForm(withActivity).payload
        } else {
            form.payload
        }
        val entry = api.createTimeEntry(payload)
        assertEquals("PT1H30M", entry.hours)
        val entries = OpenProjectIntegration.timeEntriesOf(api, created.id)
        assertTrue(entries.any { it.id == entry.id })
        api.deleteTimeEntry(entry)
    }

    @Test
    fun `queries columns and filter schemas`() = runBlocking {
        val default = api.defaultQuery()
        assertTrue(default.links.list("columns").isNotEmpty())
        val form = api.queryForm()
        val columns = form.schema["columns"]?.allowedOptions.orEmpty()
        val sortBys = form.schema["sortBy"]?.allowedOptions.orEmpty()
        note("query form: ${columns.size} allowed columns, ${sortBys.size} sortBys; sample=${columns.take(8).map { it.href?.substringAfterLast('/') }}")
        val schemas = api.filterInstanceSchemas()
        assertTrue(schemas.any { it.id == "status" && "o" in it.operatorValueTypes })
        note("filters with names: ${schemas.count { it.displayName != null }}/${schemas.size}")
    }

    @Test
    fun `render markdown`() = runBlocking {
        val created = api.workPackage(create("markdown").id)
        val html = api.renderMarkdown("Some **bold** text", created)
        note("render markdown -> ${html.take(120)}")
        assertTrue(html.contains("<strong>bold</strong>"))
    }

    @Test
    fun `missing resources and invalid filters map to errors`() = runBlocking {
        try {
            api.workPackage(999_999_999)
            fail("expected 404")
        } catch (e: OpenProjectApiException) {
            assertTrue(e.isNotFound)
        }
        try {
            api.workPackages(WorkPackageQuery(filters = listOf(FilterSpec("doesNotExist", "=", listOf("1")))))
            fail("expected an invalid query error")
        } catch (e: OpenProjectApiException) {
            assertNotEquals(200, e.status)
            assertEquals(OpenProjectApiException.INVALID_QUERY, e.errorIdentifier)
        }
    }
}
