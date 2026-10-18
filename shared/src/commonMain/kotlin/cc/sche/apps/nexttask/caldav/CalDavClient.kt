package cc.sche.apps.nexttask.caldav

import cc.sche.apps.nexttask.auth.Credentials
import cc.sche.apps.nexttask.ical.ICalParseException
import cc.sche.apps.nexttask.ical.TaskMapper
import cc.sche.apps.nexttask.model.Task
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.http.encodedPath
import io.ktor.http.isSuccess
import io.ktor.http.takeFrom
import kotlin.uuid.Uuid

/**
 * A calendar collection that can hold tasks. [href] is the server-absolute path, ending in `/`.
 * With [acceptsEvents] it is also a calendar for events (like Nextcloud's default "Personal").
 */
data class TaskCalendar(
    val href: String,
    val displayName: String,
    val color: String?,
    val syncToken: String?,
    val acceptsEvents: Boolean = false,
)

/** Where the user's calendars live ([href]), and the ones among them that accept tasks. */
data class CalendarHome(val href: String, val calendars: List<TaskCalendar>)

/**
 * A fresh place for a new calendar in the calendar home [homeHref]. The path segment is random rather than
 * derived from the name: names may be anything, and a list can be renamed without moving.
 */
fun newCalendarHref(homeHref: String): String = homeHref.trimEnd('/') + "/" + Uuid.random() + "/"

/** The resource a task is stored in. The UID is escaped as one path segment, so it can never leave the calendar. */
internal fun taskHref(calendarHref: String, uid: String): String =
    calendarHref.trimEnd('/') + "/" + uid.encodeURLPathPart() + ".ics"

/** A task as stored on the server. [etag] must be sent back on update/delete to detect concurrent edits. */
data class RemoteTask(val href: String, val etag: String, val task: Task)

data class SyncResult(
    /** Pass to the next [CalDavClient.sync] call to receive only newer changes. */
    val syncToken: String,
    val changed: List<RemoteTask>,
    val deletedHrefs: List<String>,
    /** Resources that exist but could not be mapped to a single task (e.g. recurring tasks with overrides). */
    val unsupportedHrefs: List<String>,
)

open class DavException(val status: Int, message: String) : Exception(message)
class UnauthorizedException : DavException(401, "Invalid or revoked credentials")

/** The resource changed on the server since [CalDavClient] last saw its ETag (or already exists). */
class ConflictException(status: Int) : DavException(status, "Resource was modified on the server")

/**
 * CalDAV access to the account in [credentials]. Every request goes to its server URL, with its scheme:
 * hrefs from server responses only ever replace the path, so they cannot move requests (and the
 * credentials sent with them) to another host or to plain HTTP.
 */
class CalDavClient(
    private val client: HttpClient,
    private val credentials: Credentials,
) {

    /** Finds the user's calendar home and all calendars in it that accept VTODO components. */
    suspend fun discoverCalendarHome(): CalendarHome {
        val home = calendarHome()
        val calendars = propfind(
            url = resolve(home),
            depth = 1,
            body = DavXml.propfind(
                "d:resourcetype", "d:displayname", "d:sync-token",
                "c:supported-calendar-component-set", "a:calendar-color",
            ),
        ).responses.mapNotNull { response ->
            val isCalendar = response.prop(Dav.NS, "resourcetype")?.child(Dav.CALDAV, "calendar") != null
            val components = response.prop(Dav.CALDAV, "supported-calendar-component-set")
                ?.children(Dav.CALDAV, "comp")
                ?.mapNotNull { it.attributes["name"] }
                .orEmpty()
            if (!isCalendar || "VTODO" !in components) return@mapNotNull null
            TaskCalendar(
                href = response.href,
                displayName = response.prop(Dav.NS, "displayname")?.text.orEmpty(),
                color = response.prop(Dav.APPLE, "calendar-color")?.text?.takeIf { it.isNotEmpty() },
                syncToken = response.prop(Dav.NS, "sync-token")?.text?.takeIf { it.isNotEmpty() },
                acceptsEvents = "VEVENT" in components,
            )
        }
        return CalendarHome(home, calendars)
    }

    /**
     * Creates a calendar that only holds tasks, named [displayName], at [calendarHref] (see [newCalendarHref]).
     * Fails if something exists there already.
     */
    suspend fun createTaskCalendar(calendarHref: String, displayName: String) {
        require(calendarHref.endsWith("/")) { "Not a calendar: $calendarHref" }
        request(MKCALENDAR, resolve(calendarHref), DavXml.mkTaskCalendar(displayName), depth = null).requireSuccess()
    }

    /** Deletes the calendar at [calendarHref] with everything in it. */
    suspend fun deleteCalendar(calendarHref: String) {
        require(calendarHref.endsWith("/")) { "Not a calendar: $calendarHref" }
        delete(calendarHref, etag = null)
    }

    /** The collection the user's calendars live in. */
    private suspend fun calendarHome(): String {
        val principal = propfind(
            url = "${credentials.serverUrl}/remote.php/dav/",
            depth = 0,
            body = DavXml.propfind("d:current-user-principal"),
        ).responses.firstNotNullOfOrNull { it.prop(Dav.NS, "current-user-principal")?.child(Dav.NS, "href")?.text }
            ?: throw DavException(0, "Server did not report a user principal")

        return propfind(
            url = resolve(principal),
            depth = 0,
            body = DavXml.propfind("c:calendar-home-set"),
        ).responses.firstNotNullOfOrNull { it.prop(Dav.CALDAV, "calendar-home-set")?.child(Dav.NS, "href")?.text }
            ?: throw DavException(0, "Server did not report a calendar home")
    }

    /**
     * Fetches changes in [calendarHref] since [syncToken]; with a null token, returns every task.
     * An expired token (HTTP 403/409/410 from the server) is reported as [SyncTokenInvalidException]:
     * retry with null to resync from scratch.
     */
    suspend fun sync(calendarHref: String, syncToken: String?): SyncResult {
        val response = request(
            method = REPORT,
            url = resolve(calendarHref),
            body = DavXml.syncCollection(syncToken),
            depth = null,
        )
        if (response.status.value in INVALID_SYNC_TOKEN_STATUSES && syncToken != null) {
            throw SyncTokenInvalidException()
        }
        val multistatus = parse(response)

        val deleted = multistatus.responses.filter { it.status == 404 }.map { it.href }
        val changedHrefs = multistatus.responses
            .filter { it.status != 404 && !it.href.isCollection(calendarHref) }
            .map { it.href }

        val newToken = multistatus.syncToken ?: throw DavException(0, "Server did not return a sync token")
        val (tasks, unsupported) = if (changedHrefs.isEmpty()) emptyList<RemoteTask>() to emptyList() else multiget(calendarHref, changedHrefs)
        return SyncResult(newToken, tasks, deleted, unsupported)
    }

    /**
     * Creates ([etag] == null) or updates a task. Returns the new ETag if the server sent one.
     * Throws [ConflictException] if the task exists already (create) or changed on the server (update).
     */
    suspend fun put(calendarHref: String, task: Task, href: String? = null, etag: String? = null): PutResult {
        val target = href ?: taskHref(calendarHref, task.uid)
        val response = client.put(resolve(target)) {
            authorize()
            contentType(ICS)
            if (etag == null) header(HttpHeaders.IfNoneMatch, "*") else header(HttpHeaders.IfMatch, etag)
            setBody(TaskMapper.toIcs(task))
        }
        response.requireSuccess()
        return PutResult(target, response.headers[HttpHeaders.ETag])
    }

    /**
     * Deletes the task at [href]. With an [etag] the server refuses (HTTP 412, [ConflictException]) if the
     * task changed since; without one, which only happens if the server never reported the ETag, it is
     * deleted whatever its state.
     */
    suspend fun delete(href: String, etag: String?) {
        val response = client.delete(resolve(href)) {
            authorize()
            if (etag != null) header(HttpHeaders.IfMatch, etag)
        }
        // 404: already gone, which is what the caller wanted.
        if (response.status != HttpStatusCode.NotFound) response.requireSuccess()
    }

    data class PutResult(val href: String, val etag: String?)

    class SyncTokenInvalidException : DavException(410, "Sync token is no longer valid")

    private suspend fun multiget(calendarHref: String, hrefs: List<String>): Pair<List<RemoteTask>, List<String>> {
        val tasks = mutableListOf<RemoteTask>()
        val unsupported = mutableListOf<String>()
        // Keep request sizes bounded for large calendars.
        for (batch in hrefs.chunked(MULTIGET_BATCH)) {
            val response = request(REPORT, resolve(calendarHref), DavXml.calendarMultiget(batch), depth = 1)
            for (item in parse(response).responses) {
                val ics = item.prop(Dav.CALDAV, "calendar-data")?.text ?: continue
                val etag = item.prop(Dav.NS, "getetag")?.text.orEmpty()
                if ("BEGIN:VTODO" !in ics) continue // events in a mixed calendar
                try {
                    tasks += RemoteTask(item.href, etag, TaskMapper.fromIcs(ics))
                } catch (_: ICalParseException) {
                    unsupported += item.href
                }
            }
        }
        return tasks to unsupported
    }

    private suspend fun propfind(url: String, depth: Int, body: String): Multistatus =
        parse(request(PROPFIND, url, body, depth))

    private suspend fun request(method: HttpMethod, url: String, body: String, depth: Int?): HttpResponse =
        client.request(url) {
            this.method = method
            authorize()
            depth?.let { header("Depth", it) }
            contentType(XML)
            setBody(body)
        }

    private suspend fun parse(response: HttpResponse): Multistatus {
        response.requireSuccess()
        return DavXml.parseMultistatus(response.bodyAsText())
    }

    private suspend fun HttpResponse.requireSuccess() {
        when {
            status == HttpStatusCode.Unauthorized -> throw UnauthorizedException()
            status == HttpStatusCode.PreconditionFailed -> throw ConflictException(status.value)
            !status.isSuccess() -> throw DavException(status.value, "WebDAV request failed: HTTP ${status.value}")
        }
    }

    private fun HttpRequestBuilder.authorize() {
        header(HttpHeaders.Authorization, credentials.basicAuthHeader)
    }

    /** Turns a server-absolute path (as found in `<href>`) into a full URL on the configured server. */
    private fun resolve(href: String): String =
        URLBuilder().takeFrom(credentials.serverUrl).apply { encodedPath = href }.buildString()

    private fun String.isCollection(calendarHref: String) = trimEnd('/') == calendarHref.trimEnd('/')

    private companion object {
        val PROPFIND = HttpMethod("PROPFIND")
        val MKCALENDAR = HttpMethod("MKCALENDAR")
        val REPORT = HttpMethod("REPORT")
        val XML = ContentType.Application.Xml
        val ICS = ContentType("text", "calendar")
        const val MULTIGET_BATCH = 100
        val INVALID_SYNC_TOKEN_STATUSES = setOf(403, 409, 410)
    }
}
