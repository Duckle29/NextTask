package cc.sche.apps.nexttask.caldav

import cc.sche.apps.nexttask.auth.Credentials
import cc.sche.apps.nexttask.model.Task
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CalDavClientTest {

    private val credentials = Credentials("https://cloud.example.com", "mikkel", "pw")
    private val xml = headersOf(HttpHeaders.ContentType, "application/xml")
    private val multistatus = HttpStatusCode.MultiStatus

    private fun client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        CalDavClient(HttpClient(MockEngine(handler)), credentials)

    private fun ms(body: String) =
        """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav" xmlns:a="http://apple.com/ns/ical/" xmlns:s="http://sabredav.org/ns">$body</d:multistatus>"""

    private fun ics(uid: String, summary: String, parent: String? = null) = buildString {
        append("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VTODO\r\nUID:$uid\r\nSUMMARY:$summary\r\n")
        if (parent != null) append("RELATED-TO;RELTYPE=PARENT:$parent\r\n")
        append("END:VTODO\r\nEND:VCALENDAR\r\n")
    }

    @Test
    fun discoversOnlyCalendarsSupportingTodos() = runTest {
        val client = client { request ->
            assertEquals("Basic bWlra2VsOnB3", request.headers[HttpHeaders.Authorization])
            when (request.url.encodedPath) {
                "/remote.php/dav/" -> respond(
                    ms("""<d:response><d:href>/remote.php/dav/</d:href><d:propstat><d:prop>
                        <d:current-user-principal><d:href>/remote.php/dav/principals/users/mikkel/</d:href></d:current-user-principal>
                        </d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"""),
                    multistatus, xml,
                )
                "/remote.php/dav/principals/users/mikkel/" -> respond(
                    ms("""<d:response><d:href>/remote.php/dav/principals/users/mikkel/</d:href><d:propstat><d:prop>
                        <c:calendar-home-set><d:href>/remote.php/dav/calendars/mikkel/</d:href></c:calendar-home-set>
                        </d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"""),
                    multistatus, xml,
                )
                "/remote.php/dav/calendars/mikkel/" -> respond(
                    ms("""
                    <d:response><d:href>/remote.php/dav/calendars/mikkel/</d:href><d:propstat><d:prop>
                      <d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
                    <d:response><d:href>/remote.php/dav/calendars/mikkel/personal/</d:href><d:propstat><d:prop>
                      <d:resourcetype><d:collection/><c:calendar/></d:resourcetype><d:displayname>Personal</d:displayname>
                      <c:supported-calendar-component-set><c:comp name="VEVENT"/></c:supported-calendar-component-set>
                      </d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
                    <d:response><d:href>/remote.php/dav/calendars/mikkel/tasks/</d:href><d:propstat><d:prop>
                      <d:resourcetype><d:collection/><c:calendar/></d:resourcetype><d:displayname>Tasks</d:displayname>
                      <d:sync-token>http://sabre.io/ns/sync/42</d:sync-token><a:calendar-color>#0082c9</a:calendar-color>
                      <c:supported-calendar-component-set><c:comp name="VTODO"/></c:supported-calendar-component-set>
                      </d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"""),
                    multistatus, xml,
                )
                else -> error("Unexpected request ${request.method} ${request.url}")
            }
        }

        val calendars = client.discoverTaskCalendars()

        assertEquals(
            listOf(TaskCalendar("/remote.php/dav/calendars/mikkel/tasks/", "Tasks", "#0082c9", "http://sabre.io/ns/sync/42")),
            calendars,
        )
    }

    @Test
    fun syncFetchesChangedTasksAndReportsDeletions() = runTest {
        val reports = mutableListOf<String>()
        val client = client { request ->
            assertEquals("REPORT", request.method.value)
            val body = request.body.toByteArray().decodeToString()
            reports += body
            if ("sync-collection" in body) {
                respond(
                    ms("""
                    <d:response><d:href>/remote.php/dav/calendars/mikkel/tasks/a.ics</d:href><d:propstat><d:prop><d:getetag>"e1"</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
                    <d:response><d:href>/remote.php/dav/calendars/mikkel/tasks/b.ics</d:href><d:propstat><d:prop><d:getetag>"e2"</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
                    <d:response><d:href>/remote.php/dav/calendars/mikkel/tasks/gone.ics</d:href><d:status>HTTP/1.1 404 Not Found</d:status></d:response>
                    <d:sync-token>http://sabre.io/ns/sync/43</d:sync-token>"""),
                    multistatus, xml,
                )
            } else {
                respond(
                    ms("""
                    <d:response><d:href>/remote.php/dav/calendars/mikkel/tasks/a.ics</d:href><d:propstat><d:prop><d:getetag>"e1"</d:getetag>
                      <c:calendar-data>${ics("a", "Parent")}</c:calendar-data></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
                    <d:response><d:href>/remote.php/dav/calendars/mikkel/tasks/b.ics</d:href><d:propstat><d:prop><d:getetag>"e2"</d:getetag>
                      <c:calendar-data>${ics("b", "Child", parent = "a")}</c:calendar-data></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"""),
                    multistatus, xml,
                )
            }
        }

        val result = client.sync("/remote.php/dav/calendars/mikkel/tasks/", syncToken = null)

        assertEquals("http://sabre.io/ns/sync/43", result.syncToken)
        assertEquals(listOf("/remote.php/dav/calendars/mikkel/tasks/gone.ics"), result.deletedHrefs)
        assertEquals(listOf("a", "b"), result.changed.map { it.task.uid })
        assertEquals("a", result.changed[1].task.parentUid)
        assertEquals("\"e2\"", result.changed[1].etag)
        assertTrue("<d:sync-token></d:sync-token>" in reports.first())
        assertTrue("<d:href>/remote.php/dav/calendars/mikkel/tasks/a.ics</d:href>" in reports.last())
    }

    @Test
    fun syncSkipsEventsAndFlagsUnparseableTodos() = runTest {
        val twoTodos = ics("x", "Recurring").replace("END:VCALENDAR", "BEGIN:VTODO\r\nUID:x\r\nSUMMARY:Override\r\nEND:VTODO\r\nEND:VCALENDAR")
        val event = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:ev\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        val client = client { request ->
            val body = request.body.toByteArray().decodeToString()
            if ("sync-collection" in body) {
                respond(
                    ms("""<d:response><d:href>/c/x.ics</d:href><d:propstat><d:prop><d:getetag>"1"</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
                    <d:response><d:href>/c/ev.ics</d:href><d:propstat><d:prop><d:getetag>"2"</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
                    <d:sync-token>t</d:sync-token>"""),
                    multistatus, xml,
                )
            } else {
                respond(
                    ms("""<d:response><d:href>/c/x.ics</d:href><d:propstat><d:prop><d:getetag>"1"</d:getetag><c:calendar-data>$twoTodos</c:calendar-data></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
                    <d:response><d:href>/c/ev.ics</d:href><d:propstat><d:prop><d:getetag>"2"</d:getetag><c:calendar-data>$event</c:calendar-data></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"""),
                    multistatus, xml,
                )
            }
        }

        val result = client.sync("/c/", null)

        assertTrue(result.changed.isEmpty())
        assertEquals(listOf("/c/x.ics"), result.unsupportedHrefs)
    }

    @Test
    fun invalidSyncTokenIsReported() = runTest {
        val client = client { respond("", HttpStatusCode.Forbidden) }
        assertFailsWith<CalDavClient.SyncTokenInvalidException> { client.sync("/c/", "stale") }
    }

    @Test
    fun createUsesIfNoneMatchAndReturnsEtag() = runTest {
        var request: HttpRequestData? = null
        var body = ""
        val client = client { r ->
            request = r
            body = r.body.toByteArray().decodeToString()
            respond("", HttpStatusCode.Created, headersOf(HttpHeaders.ETag, "\"new\""))
        }

        val result = client.put("/c/", Task(uid = "uid 1", summary = "Hello"))

        assertEquals(HttpMethod.Put, request!!.method)
        assertEquals("/c/uid%201.ics", request!!.url.encodedPath)
        assertEquals("*", request!!.headers[HttpHeaders.IfNoneMatch])
        assertEquals(null, request!!.headers[HttpHeaders.IfMatch])
        assertTrue("SUMMARY:Hello" in body)
        assertEquals(CalDavClient.PutResult("/c/uid%201.ics", "\"new\""), result)
    }

    @Test
    fun createEscapesSlashesInUid() = runTest {
        var path = ""
        val client = client { r ->
            path = r.url.encodedPath
            respond("", HttpStatusCode.Created)
        }

        client.put("/c/", Task(uid = "../../other/x", summary = "S"))

        assertEquals("/c/..%2F..%2Fother%2Fx.ics", path)
    }

    @Test
    fun updateUsesIfMatchAndConflictIsReported() = runTest {
        var ifMatch: String? = null
        val client = client { r ->
            ifMatch = r.headers[HttpHeaders.IfMatch]
            respond("", HttpStatusCode.PreconditionFailed)
        }

        assertFailsWith<ConflictException> {
            client.put("/c/", Task(uid = "u", summary = "S"), href = "/c/u.ics", etag = "\"old\"")
        }
        assertEquals("\"old\"", ifMatch)
    }

    @Test
    fun deleteSendsIfMatchAndToleratesMissing() = runTest {
        var ifMatch: String? = null
        val client = client { r ->
            ifMatch = r.headers[HttpHeaders.IfMatch]
            respond("", HttpStatusCode.NotFound)
        }
        client.delete("/c/u.ics", "\"e\"")
        assertEquals("\"e\"", ifMatch)
    }

    @Test
    fun unauthorizedIsDistinguished() = runTest {
        val client = client { respond("", HttpStatusCode.Unauthorized) }
        assertFailsWith<UnauthorizedException> { client.discoverTaskCalendars() }
    }
}
