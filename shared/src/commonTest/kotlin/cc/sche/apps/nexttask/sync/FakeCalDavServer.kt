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
 * A small in-memory CalDAV server with one task calendar: sync tokens, ETags, conditional writes and
 * deletions behave like Nextcloud's. [offline] makes every request fail like a missing connection.
 */
class FakeCalDavServer {

    private class Resource(var etag: String, var ics: String, var changedAt: Int)

    private val resources = linkedMapOf<String, Resource>() // by href
    private val deletions = mutableListOf<Pair<String, Int>>() // href, version
    private var version = 1

    var offline = false

    /** Called just before a PUT is applied, to simulate another client writing in between. */
    var beforePut: (() -> Unit)? = null

    /** Tokens older than this are answered like an expired token. */
    var oldestValidToken = 0

    val calendarHref = "/remote.php/dav/calendars/u/tasks/"
    val credentials = Credentials("https://cloud.example.com", "u", "pw")

    fun client() = CalDavClient(HttpClient(MockEngine { handle(it) }), credentials)

    private fun hrefOf(uid: String) = calendarHref + uid + ".ics"

    /** Writes a task as another client would. */
    fun write(task: Task) {
        val href = hrefOf(task.uid)
        version++
        resources[href] = Resource("\"e$version\"", TaskMapper.toIcs(task), version)
    }

    fun delete(uid: String) {
        val href = hrefOf(uid)
        if (resources.remove(href) != null) {
            version++
            deletions += href to version
        }
    }

    fun task(uid: String): Task? = resources[hrefOf(uid)]?.let { TaskMapper.fromIcs(it.ics) }

    fun hasTask(uid: String) = hrefOf(uid) in resources

    private fun token() = "tok-$version"

    private fun ms(body: String) =
        """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav" xmlns:a="http://apple.com/ns/ical/">$body</d:multistatus>"""

    private fun MockRequestHandleScope.xml(body: String): HttpResponseData =
        respond(ms(body), HttpStatusCode.MultiStatus, headersOf(HttpHeaders.ContentType, "application/xml"))

    private fun ok200(props: String) = "<d:propstat><d:prop>$props</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>"

    private suspend fun MockRequestHandleScope.handle(request: HttpRequestData): HttpResponseData {
        if (offline) throw RuntimeException("Unable to resolve host")
        val path = request.url.encodedPath
        val body = request.body.toByteArray().decodeToString()
        return when (request.method.value) {
            "PROPFIND" -> when (path) {
                "/remote.php/dav/" -> xml("<d:response><d:href>$path</d:href>${ok200("<d:current-user-principal><d:href>/principals/u/</d:href></d:current-user-principal>")}</d:response>")
                "/principals/u/" -> xml("<d:response><d:href>$path</d:href>${ok200("<c:calendar-home-set><d:href>/remote.php/dav/calendars/u/</d:href></c:calendar-home-set>")}</d:response>")
                else -> xml(
                    "<d:response><d:href>$calendarHref</d:href>" + ok200(
                        "<d:resourcetype><d:collection/><c:calendar/></d:resourcetype><d:displayname>Tasks</d:displayname>" +
                            "<c:supported-calendar-component-set><c:comp name=\"VTODO\"/></c:supported-calendar-component-set>",
                    ) + "</d:response>",
                )
            }
            "REPORT" -> if ("sync-collection" in body) syncCollection(body) else multiget(body)
            "PUT" -> put(request, body)
            else -> error("Unexpected ${request.method} $path")
        }
    }

    private fun MockRequestHandleScope.syncCollection(body: String): HttpResponseData {
        val since = Regex("<d:sync-token>(.*?)</d:sync-token>").find(body)?.groupValues?.get(1).orEmpty()
        val sinceVersion = since.removePrefix("tok-").toIntOrNull()
        if (since.isNotEmpty() && (sinceVersion == null || sinceVersion < oldestValidToken)) {
            return respond("", HttpStatusCode.Forbidden)
        }
        val from = sinceVersion ?: 0
        val changed = resources.filter { it.value.changedAt > from }.map { (href, r) ->
            "<d:response><d:href>$href</d:href>${ok200("<d:getetag>${r.etag.replace("\"", "&quot;")}</d:getetag>")}</d:response>"
        }
        val gone = if (sinceVersion == null) emptyList() else deletions.filter { it.second > from }.map { (href, _) ->
            "<d:response><d:href>$href</d:href><d:status>HTTP/1.1 404 Not Found</d:status></d:response>"
        }
        return xml((changed + gone).joinToString("") + "<d:sync-token>${token()}</d:sync-token>")
    }

    private fun MockRequestHandleScope.multiget(body: String): HttpResponseData {
        val hrefs = Regex("<d:href>(.*?)</d:href>").findAll(body).map { it.groupValues[1] }
        return xml(
            hrefs.mapNotNull { href -> resources[href]?.let { href to it } }.joinToString("") { (href, r) ->
                "<d:response><d:href>$href</d:href>" +
                    ok200("<d:getetag>${r.etag.replace("\"", "&quot;")}</d:getetag><c:calendar-data>${r.ics}</c:calendar-data>") +
                    "</d:response>"
            },
        )
    }

    private fun MockRequestHandleScope.put(request: HttpRequestData, body: String): HttpResponseData {
        beforePut?.also { beforePut = null }?.invoke()
        val href = request.url.encodedPath
        val existing = resources[href]
        val ifMatch = request.headers[HttpHeaders.IfMatch]
        val ifNoneMatch = request.headers[HttpHeaders.IfNoneMatch]
        if ((ifMatch != null && existing?.etag != ifMatch) || (ifNoneMatch == "*" && existing != null)) {
            return respond("", HttpStatusCode.PreconditionFailed)
        }
        version++
        val etag = "\"e$version\""
        resources[href] = Resource(etag, body, version)
        return respond("", if (existing == null) HttpStatusCode.Created else HttpStatusCode.NoContent, headersOf(HttpHeaders.ETag, etag))
    }
}
