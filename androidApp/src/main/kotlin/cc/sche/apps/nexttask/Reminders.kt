package cc.sche.apps.nexttask

import cc.sche.apps.nexttask.ical.ICalDate
import cc.sche.apps.nexttask.model.Task
import cc.sche.apps.nexttask.model.TaskStatus
import cc.sche.apps.nexttask.sync.AccountSnapshot
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** A reminder of a task: at [at], about the task [uid] in the list [listHref]. */
data class Reminder(val listHref: String, val listName: String, val uid: String, val summary: String, val at: Instant)

/** True if the task carries reminders (`VALARM`s), whether or not they can be shown. */
val Task.hasReminders: Boolean get() = subcomponents.any { it.name == VALARM }

/**
 * When the task's reminders go off. A `TRIGGER` is either a moment (`VALUE=DATE-TIME`, in UTC) or an offset
 * from the task's start (`DTSTART`, the default) or, with `RELATED=END`, from its due date; a whole-day date
 * counts from the start of that day. Reminders that can't be placed, e.g. relative to a start the task
 * doesn't have, are left out.
 */
fun Task.reminderTimes(zone: ZoneId): List<Instant> = subcomponents.filter { it.name == VALARM }.mapNotNull { alarm ->
    val trigger = alarm.property("TRIGGER") ?: return@mapNotNull null
    if (trigger.params["VALUE"]?.uppercase() == "DATE-TIME") {
        return@mapNotNull ICalDate(trigger.value, trigger.params - "VALUE").resolve(zone)?.start
    }
    val offset = parseDuration(trigger.value) ?: return@mapNotNull null
    val anchor = if (trigger.params["RELATED"]?.uppercase() == "END") {
        due
    } else {
        unmapped.firstOrNull { it.name == "DTSTART" }?.let { ICalDate(it.value, it.params) }
    }
    anchor?.resolve(zone)?.start?.plus(offset)
}

/** Every reminder of the open tasks in [snapshot], earliest first. */
fun AccountSnapshot.reminders(zone: ZoneId): List<Reminder> =
    calendars.flatMap { list ->
        list.currentTasks
            .filter { it.status != TaskStatus.Completed && it.status != TaskStatus.Cancelled }
            .flatMap { task -> task.reminderTimes(zone).map { Reminder(list.href, list.name, task.uid, task.summary, it) } }
    }.sortedBy { it.at }

/** An iCalendar duration such as `-PT15M`, `P1D` or `-P1DT2H`; null if it isn't one. */
internal fun parseDuration(value: String): Duration? {
    val match = DURATION.matchEntire(value.trim().uppercase()) ?: return null
    val (sign, weeks, days, hours, minutes, seconds) = match.destructured
    val parts = listOf(weeks, days, hours, minutes, seconds)
    if (parts.all { it.isEmpty() }) return null
    val (w, d, h, m, s) = parts.map { it.toLongOrNull() ?: 0 }
    val duration = Duration.ofDays(w * DAYS_PER_WEEK + d).plusHours(h).plusMinutes(m).plusSeconds(s)
    return if (sign == "-") duration.negated() else duration
}

private const val VALARM = "VALARM"
private const val DAYS_PER_WEEK = 7
private val DURATION = Regex("""([+-])?P(?:(\d+)W)?(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?""")
