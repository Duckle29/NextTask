package cc.sche.apps.nexttask.sync

import cc.sche.apps.nexttask.auth.Credentials
import cc.sche.apps.nexttask.caldav.CalDavClient
import cc.sche.apps.nexttask.ical.TaskMapper
import cc.sche.apps.nexttask.model.Task
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf

/**
 * A small in-memory CalDAV server with task calendars: sync tokens, ETags, conditional writes and deletions
 * behave like Nextcloud's. [offline] makes every request fail like a missing connection.
 *
 * The first calendar, [calendarHref], is what the single-list helpers (`write`, `task`, `delete`, ...) use.
 */
class FakeCalDavServer {

    private class Resource(var etag: String, var ics: String, var changedAt: Int)

    private class Calendar(val href: String, var name: String) {
        val resources = linkedMapOf<String, Resource>() // by href
        val deletions = mutableListOf<Pair<String, Int>>() // href, version
        var version = 1
    }

    private val calendars = linkedMapOf<String, Calendar>()

    var offline = false

    /** Called just before a PUT is applied, to simulate another client writing in between. */
    var beforePut: (() -> Unit)? = null

    /** Tokens older than this are answered like an expired token. */
    var oldestValidToken = 0

    /** Every request the server received, as `METHOD /path`. */
    val requests = mutableListOf<String>()

    val calendarHref = "/remote.php/dav/calendars/u/tasks/"
    val credentials = Credentials("https://cloud.example.com", "u", "pw")

    init {
        addCalendar("tasks", "Tasks")
    }

    fun client() = CalDavClient(HttpClient(MockEngine { handle(it) }), credentials)

    /** Adds a task list and returns its href. */
    fun addCalendar(id: String, name: String): String {
        val href = "/remote.php/dav/calendars/u/$id/"
        calendars[href] = Calendar(href, name)
        return href
    }

    fun renameCalendar(href: String, name: String) {
        calendars.getValue(href).name = name
    }

    fun removeCalendar(href: String) {
        calendars.remove(href)
    }

    private fun hrefOf(calendar: String, uid: String) = calendar + uid + ".ics"

    /** Writes a task as another client would. */
    fun write(task: Task, calendar: String = calendarHref) {
        val cal = calendars.getValue(calendar)
        cal.version++
        cal.resources[hrefOf(calendar, task.uid)] = Resource("\"e${cal.version}\"", TaskMapper.toIcs(task), cal.version)
    }

    fun delete(uid: String, calendar: String = calendarHref) {
        val cal = calendars.getValue(calendar)
        val href = hrefOf(calendar, uid)
        if (cal.resources.remove(href) != null) {
            cal.version++
            cal.deletions += href to cal.version
        }
    }

    fun task(uid: String, calendar: String = calendarHref): Task? =
        calendars[calendar]?.resources?.get(hrefOf(calendar, uid))?.let { TaskMapper.fromIcs(it.ics) }

    fun hasTask(uid: String, calendar: String = calendarHref) = calendars[calendar]?.resources?.containsKey(hrefOf(calendar, uid)) == true

    private fun ms(body: String) =
        """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav" xmlns:a="http://apple.com/ns/ical/">$body</d:multistatus>"""

    private fun MockRequestHandleScope.xml(body: String): HttpResponseData =
        respond(ms(body), HttpStatusCode.MultiStatus, headersOf(HttpHeaders.ContentType, "application/xml"))

    private fun ok200(props: String) = "<d:propstat><d:prop>$props</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>"

    private fun calendarFor(path: String): Calendar? = calendars.values.firstOrNull { path.startsWith(it.href) }

    private suspend fun MockRequestHandleScope.handle(request: HttpRequestData): HttpResponseData {
        if (offline) throw RuntimeException("Unable to resolve host")
        val path = request.url.encodedPath
        requests += "${request.method.value} $path"
        val body = request.body.toByteArray().decodeToString()
        return when (request.method.value) {
            "PROPFIND" -> when (path) {
                "/remote.php/dav/" -> xml("<d:response><d:href>$path</d:href>${ok200("<d:current-user-principal><d:href>/principals/u/</d:href></d:current-user-principal>")}</d:response>")
                "/principals/u/" -> xml("<d:response><d:href>$path</d:href>${ok200("<c:calendar-home-set><d:href>/remote.php/dav/calendars/u/</d:href></c:calendar-home-set>")}</d:response>")
                else -> xml(
                    calendars.values.joinToString("") { cal ->
                        "<d:response><d:href>${cal.href}</d:href>" + ok200(
                            "<d:resourcetype><d:collection/><c:calendar/></d:resourcetype><d:displayname>${cal.name}</d:displayname>" +
                                "<a:calendar-color>#0082c9</a:calendar-color>" +
                                "<c:supported-calendar-component-set><c:comp name=\"VTODO\"/></c:supported-calendar-component-set>",
                        ) + "</d:response>"
                    },
                )
            }
            "REPORT" -> {
                val cal = calendarFor(path) ?: return respond("", HttpStatusCode.NotFound)
                if ("sync-collection" in body) syncCollection(cal, body) else multiget(cal, body)
            }
            "PUT" -> put(request, body)
            "DELETE" -> delete(request)
            else -> error("Unexpected ${request.method} $path")
        }
    }

    private fun MockRequestHandleScope.syncCollection(cal: Calendar, body: String): HttpResponseData {
        val since = Regex("<d:sync-token>(.*?)</d:sync-token>").find(body)?.groupValues?.get(1).orEmpty()
        val sinceVersion = since.removePrefix("tok-").toIntOrNull()
        if (since.isNotEmpty() && (sinceVersion == null || sinceVersion < oldestValidToken)) {
            return respond("", HttpStatusCode.Forbidden)
        }
        val from = sinceVersion ?: 0
        val changed = cal.resources.filter { it.value.changedAt > from }.map { (href, r) ->
            "<d:response><d:href>$href</d:href>${ok200("<d:getetag>${r.etag.replace("\"", "&quot;")}</d:getetag>")}</d:response>"
        }
        val gone = if (sinceVersion == null) emptyList() else cal.deletions.filter { it.second > from }.map { (href, _) ->
            "<d:response><d:href>$href</d:href><d:status>HTTP/1.1 404 Not Found</d:status></d:response>"
        }
        return xml((changed + gone).joinToString("") + "<d:sync-token>tok-${cal.version}</d:sync-token>")
    }

    private fun MockRequestHandleScope.multiget(cal: Calendar, body: String): HttpResponseData {
        val hrefs = Regex("<d:href>(.*?)</d:href>").findAll(body).map { it.groupValues[1] }
        return xml(
            hrefs.mapNotNull { href -> cal.resources[href]?.let { href to it } }.joinToString("") { (href, r) ->
                "<d:response><d:href>$href</d:href>" +
                    ok200("<d:getetag>${r.etag.replace("\"", "&quot;")}</d:getetag><c:calendar-data>${r.ics}</c:calendar-data>") +
                    "</d:response>"
            },
        )
    }

    private fun MockRequestHandleScope.delete(request: HttpRequestData): HttpResponseData {
        val href = request.url.encodedPath
        val cal = calendarFor(href) ?: return respond("", HttpStatusCode.NotFound)
        val existing = cal.resources[href] ?: return respond("", HttpStatusCode.NotFound)
        val ifMatch = request.headers[HttpHeaders.IfMatch]
        if (ifMatch != null && existing.etag != ifMatch) return respond("", HttpStatusCode.PreconditionFailed)
        cal.resources.remove(href)
        cal.version++
        cal.deletions += href to cal.version
        return respond("", HttpStatusCode.NoContent)
    }

    private fun MockRequestHandleScope.put(request: HttpRequestData, body: String): HttpResponseData {
        beforePut?.also { beforePut = null }?.invoke()
        val href = request.url.encodedPath
        val cal = calendarFor(href) ?: return respond("", HttpStatusCode.NotFound)
        val existing = cal.resources[href]
        val ifMatch = request.headers[HttpHeaders.IfMatch]
        val ifNoneMatch = request.headers[HttpHeaders.IfNoneMatch]
        if ((ifMatch != null && existing?.etag != ifMatch) || (ifNoneMatch == "*" && existing != null)) {
            return respond("", HttpStatusCode.PreconditionFailed)
        }
        cal.version++
        val etag = "\"e${cal.version}\""
        cal.resources[href] = Resource(etag, body, cal.version)
        return respond("", if (existing == null) HttpStatusCode.Created else HttpStatusCode.NoContent, headersOf(HttpHeaders.ETag, etag))
    }
}

/** The only list of an account that has just one. */
fun AccountSnapshot.only(): CalendarSnapshot = calendars.single()
