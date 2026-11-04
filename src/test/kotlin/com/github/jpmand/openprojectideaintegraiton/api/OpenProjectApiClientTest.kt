package com.github.jpmand.openprojectideaintegraiton.api

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64

class OpenProjectApiClientTest {

    private val requests = mutableListOf<HttpRequestData>()
    private val bodies = mutableListOf<String>()

    private fun api(token: String? = "secret", handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): OpenProjectApi {
        val engine = MockEngine { request ->
            requests += request
            bodies += when (val body = request.body) {
                is OutgoingContent.ByteArrayContent -> body.bytes().decodeToString()
                is OutgoingContent.NoContent -> ""
                else -> body.toByteArray().decodeToString()
            }
            handler(request)
        }
        val config = OpenProjectClientConfig(baseUrl = "https://op.example.com", apiToken = token)
        return OpenProjectApi(OpenProjectHttpClient.create(config, engine), config.baseUrl)
    }

    private fun MockRequestHandleScope.hal(fixture: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(Fixtures.text(fixture), status, headersOf(HttpHeaders.ContentType, "application/hal+json; charset=utf-8"))

    @Test
    fun `requests carry api key and accept HAL`() = runBlocking {
        val api = api { hal("statuses.json") }
        val statuses = api.statuses()
        assertTrue(statuses.isNotEmpty())
        val request = requests.single()
        assertEquals("https://op.example.com/api/v3/statuses", request.url.toString())
        val expected = "Basic " + Base64.getEncoder().encodeToString("apikey:secret".toByteArray())
        assertEquals(expected, request.headers[HttpHeaders.Authorization])
        assertTrue(request.headers[HttpHeaders.Accept].orEmpty().contains("application/hal+json"))
    }

    @Test
    fun `api key is not sent to other hosts`() = runBlocking {
        val api = api { respond("file-content", HttpStatusCode.OK) }
        val out = ByteArrayOutputStream()
        api.download("https://bucket.s3.example.org/attachment?signature=x", out)
        assertEquals("file-content", out.toString())
        assertNull(requests.single().headers[HttpHeaders.Authorization])
    }

    @Test
    fun `work package list sends filters sort and page`() = runBlocking {
        val api = api { hal("work_package_collection.json") }
        val page = api.workPackages(
            WorkPackageQuery(
                filters = listOf(Filters.openStatus(), Filters.search("login")),
                sortBy = listOf(SortSpec("id")),
                page = 2,
                pageSize = 25,
            ),
        )
        assertEquals(3, page.elements.size)
        val url = requests.single().url
        assertEquals("/api/v3/work_packages", url.encodedPath)
        assertEquals("""[{"status":{"operator":"o","values":[]}},{"search":{"operator":"**","values":["login"]}}]""", url.parameters["filters"])
        assertEquals("""[["id","asc"]]""", url.parameters["sortBy"])
        assertEquals("2", url.parameters["offset"])
        assertEquals("25", url.parameters["pageSize"])
    }

    @Test
    fun `update sends lock version and maps conflicts`() = runBlocking {
        val api = api {
            respond(
                """{"_type":"Error","errorIdentifier":"urn:openproject-org:api:v3:errors:UpdateConflict","message":"Couldn't update the resource because of conflicting modifications."}""",
                HttpStatusCode.Conflict,
                headersOf(HttpHeaders.ContentType, "application/hal+json"),
            )
        }
        val workPackage = OpenProjectApi.decodeWorkPackage(Fixtures.json("work_package.json"))
        try {
            api.updateWorkPackage(workPackage, WorkPackagePatch(workPackage.lockVersion).subject("Renamed"))
            fail("expected conflict")
        } catch (e: OpenProjectApiException) {
            assertTrue(e.isConflict)
        }
        val request = requests.single()
        assertEquals(HttpMethod.Patch, request.method)
        val body = Json.parseToJsonElement(bodies.single()).jsonObject
        assertEquals(workPackage.lockVersion.toString(), body["lockVersion"].toString())
        assertEquals("\"Renamed\"", body["subject"].toString())
        assertTrue(request.body.contentType.toString().startsWith("application/json"))
    }

    @Test
    fun `unauthorized maps to exception`() = runBlocking {
        val api = api(token = "wrong") {
            respond(
                """{"_type":"Error","errorIdentifier":"urn:openproject-org:api:v3:errors:Unauthenticated","message":"You need to be authenticated to access this resource."}""",
                HttpStatusCode.Unauthorized,
                headersOf(HttpHeaders.ContentType, "application/hal+json"),
            )
        }
        try {
            api.me()
            fail("expected 401")
        } catch (e: OpenProjectApiException) {
            assertTrue(e.isUnauthorized)
            assertEquals("You need to be authenticated to access this resource.", e.message)
        }
    }

    @Test
    fun `comment uses the addComment link`() = runBlocking {
        val workPackage = OpenProjectApi.decodeWorkPackage(Fixtures.json("work_package.json"))
        val api = api {
            respond("""{"_type":"Activity::Comment","id":1,"comment":{"raw":"Hi"}}""", HttpStatusCode.Created, headersOf(HttpHeaders.ContentType, "application/hal+json"))
        }
        val activity = api.addComment(workPackage, "Hi")
        assertTrue(activity.isComment)
        assertEquals("/api/v3/work_packages/${workPackage.id}/activities", requests.single().url.encodedPath)
        assertEquals("""{"comment":{"raw":"Hi"}}""", bodies.single())
    }

    @Test
    fun `attachments upload as multipart with metadata part`() = runBlocking {
        val workPackage = OpenProjectApi.decodeWorkPackage(Fixtures.json("work_package.json"))
        val api = api {
            respond("""{"_type":"Attachment","id":5,"fileName":"notes.txt","fileSize":5}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/hal+json"))
        }
        val attachment = api.uploadAttachment(workPackage, "notes.txt", "text/plain", "hello".toByteArray())
        assertEquals("notes.txt", attachment.fileName)
        val request = requests.single()
        assertTrue(request.body.contentType.toString().startsWith("multipart/form-data"))
        val body = bodies.single()
        assertTrue(body.contains("name=metadata") || body.contains("name=\"metadata\""))
        assertTrue(body.contains("""{"fileName":"notes.txt"}"""))
        assertTrue(body.contains("hello"))
    }

    @Test
    fun `relations are listed via the involved filter`() = runBlocking {
        val workPackage = OpenProjectApi.decodeWorkPackage(Fixtures.json("work_package.json"))
        val api = api {
            respond("""{"_type":"Collection","total":0,"count":0,"_embedded":{"elements":[]}}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/hal+json"))
        }
        assertEquals(0, api.relations(workPackage).size)
        assertEquals(
            """[{"involved":{"operator":"=","values":["${workPackage.id}"]}}]""",
            requests.single().url.parameters["filters"],
        )
    }

    @Test
    fun `hrefs resolve against installations in a sub path`() {
        val api = OpenProjectApi(OpenProjectHttpClient.create(OpenProjectClientConfig("https://example.com/openproject/", null), MockEngine { error("unused") }), "https://example.com/openproject/")
        assertEquals("https://example.com/openproject/api/v3/statuses", api.apiUrl("statuses"))
        assertEquals("https://example.com/openproject/api/v3/statuses/1", api.resolve("/openproject/api/v3/statuses/1"))
        assertEquals("https://example.com/openproject/work_packages/5", api.workPackageWebUrl(5))
    }
}
