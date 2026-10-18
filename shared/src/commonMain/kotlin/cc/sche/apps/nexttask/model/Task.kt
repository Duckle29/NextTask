package cc.sche.apps.nexttask.model

import cc.sche.apps.nexttask.ical.Component
import cc.sche.apps.nexttask.ical.Property
import kotlinx.serialization.Serializable

@Serializable
enum class TaskStatus(val ical: String) {
    NeedsAction("NEEDS-ACTION"),
    InProcess("IN-PROCESS"),
    Completed("COMPLETED"),
    Cancelled("CANCELLED");

    companion object {
        fun fromIcal(value: String?): TaskStatus =
            entries.firstOrNull { it.ical == value?.uppercase() } ?: NeedsAction
    }
}

/**
 * A VTODO as exposed by Nextcloud Tasks over CalDAV.
 *
 * Properties this app does not model are kept verbatim in [unmapped], [calendarProperties] and
 * [calendarComponents] so that a read-modify-write never drops data written by other clients.
 */
@Serializable
data class Task(
    val uid: String,
    val summary: String,
    val description: String = "",
    val status: TaskStatus = TaskStatus.NeedsAction,
    val priority: Int = 0,
    val percentComplete: Int? = null,
    /** Raw iCalendar UTC date-time (`20261018T101500Z`), set while [status] is completed. */
    val completed: String? = null,
    /** UID of the parent task (`RELATED-TO;RELTYPE=PARENT`), or null for a top-level task. */
    val parentUid: String? = null,
    /** `X-APPLE-SORT-ORDER`, which Nextcloud Tasks uses for manual ordering among siblings. */
    val sortOrder: Int? = null,
    val unmapped: List<Property> = emptyList(),
    val calendarProperties: List<Property> = DEFAULT_CALENDAR_PROPERTIES,
    val calendarComponents: List<Component> = emptyList(),
) {
    val isCompleted: Boolean get() = status == TaskStatus.Completed

    companion object {
        val DEFAULT_CALENDAR_PROPERTIES = listOf(
            Property("VERSION", "2.0"),
            Property("PRODID", "-//NextTask//EN"),
        )
    }
}

/** Returns a copy stamped with `LAST-MODIFIED:[now]`, which other CalDAV clients use to tell which edit is newer. */
fun Task.withLastModified(now: String): Task =
    copy(unmapped = unmapped.filterNot { it.name == "LAST-MODIFIED" } + Property("LAST-MODIFIED", now))
