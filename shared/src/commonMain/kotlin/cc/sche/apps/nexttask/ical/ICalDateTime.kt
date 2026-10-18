package cc.sche.apps.nexttask.ical

import kotlin.time.Clock
import kotlin.time.Instant

/** UTC date-times in iCalendar basic format, e.g. `20261018T114640Z`. They sort chronologically as plain strings. */
object ICalDateTime {

    fun nowUtc(): String = format(Clock.System.now())

    fun format(instant: Instant): String =
        instant.toString().take(ISO_SECONDS_LENGTH).replace("-", "").replace(":", "") + "Z"

    /** `20260814T101500Z` -> `2026-08-14`; null if [raw] does not start with a date. */
    fun dateOf(raw: String): String? {
        if (raw.length < 8 || !raw.take(8).all { it.isDigit() }) return null
        return "${raw.substring(0, 4)}-${raw.substring(4, 6)}-${raw.substring(6, 8)}"
    }

    private const val ISO_SECONDS_LENGTH = "2026-10-18T11:46:40".length
}
