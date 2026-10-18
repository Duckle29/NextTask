package cc.sche.apps.nexttask

import cc.sche.apps.nexttask.ical.ICalDate
import cc.sche.apps.nexttask.model.Task
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/** A task date resolved for showing and scheduling: a whole day, or a moment in the device's time zone. */
sealed interface TaskDate {
    /** When the date begins, e.g. for a reminder relative to it. */
    val start: Instant

    data class AllDay(val date: LocalDate, val zone: ZoneId) : TaskDate {
        override val start: Instant get() = date.atStartOfDay(zone).toInstant()
    }

    data class At(val time: ZonedDateTime) : TaskDate {
        override val start: Instant get() = time.toInstant()
    }
}

/**
 * Resolves an iCalendar date in [zone] (normally the device's): UTC and `TZID` times are converted, floating
 * times are taken as local. An unknown `TZID` is taken as [zone] too. Null if the value can't be read.
 */
fun ICalDate.resolve(zone: ZoneId): TaskDate? = try {
    when {
        isDate -> TaskDate.AllDay(LocalDate.parse(value.take(DATE_LENGTH), BASIC_DATE), zone)
        value.endsWith("Z") -> TaskDate.At(LocalDateTime.parse(value.dropLast(1), BASIC_DATE_TIME).atZone(ZoneOffset.UTC).withZoneSameInstant(zone))
        else -> {
            val source = params["TZID"]?.let(::zoneOrNull) ?: zone
            TaskDate.At(LocalDateTime.parse(value, BASIC_DATE_TIME).atZone(source).withZoneSameInstant(zone))
        }
    }
} catch (_: DateTimeParseException) {
    null
}

private fun zoneOrNull(id: String): ZoneId? = try {
    ZoneId.of(id)
} catch (_: DateTimeException) {
    null
}

/** A due date the user picked: a whole day, or a moment, which is stored in UTC. */
fun dueDate(date: LocalDate, time: LocalTime?, zone: ZoneId): ICalDate =
    if (time == null) {
        ICalDate.ofDate(date.year, date.monthValue, date.dayOfMonth)
    } else {
        ICalDate.ofUtc(date.atTime(time).atZone(zone).withZoneSameInstant(ZoneOffset.UTC).format(BASIC_UTC))
    }

/** True for an open task whose due date has passed: a due day once it is over, a due time once it is reached. */
fun Task.isOverdue(now: ZonedDateTime): Boolean {
    if (isCompleted) return false
    return when (val due = due?.resolve(now.zone)) {
        null -> false
        is TaskDate.AllDay -> due.date < now.toLocalDate()
        is TaskDate.At -> due.time <= now
    }
}

/** "Due today", "Due tomorrow 14:00", "Due Tue 20 Oct", with the year only when it isn't this year's. */
fun dueLabel(due: TaskDate, now: ZonedDateTime, locale: Locale = Locale.getDefault()): String {
    val (date, time) = when (due) {
        is TaskDate.AllDay -> due.date to null
        is TaskDate.At -> due.time.toLocalDate() to due.time.toLocalTime()
    }
    val today = now.toLocalDate()
    val day = when (ChronoUnit.DAYS.between(today, date)) {
        0L -> "today"
        1L -> "tomorrow"
        -1L -> "yesterday"
        else -> date.format(DateTimeFormatter.ofPattern(if (date.year == today.year) "EEE d MMM" else "EEE d MMM yyyy", locale))
    }
    val clock = time?.let { " " + it.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)) }.orEmpty()
    return "Due $day$clock"
}

private const val DATE_LENGTH = 8
private val BASIC_DATE = DateTimeFormatter.ofPattern("uuuuMMdd")
private val BASIC_DATE_TIME = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss")
private val BASIC_UTC = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'")
