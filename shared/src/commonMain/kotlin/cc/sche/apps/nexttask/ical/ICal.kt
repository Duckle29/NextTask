package cc.sche.apps.nexttask.ical

import kotlinx.serialization.Serializable

/** A single iCalendar content line, e.g. `RELATED-TO;RELTYPE=PARENT:abc`. */
@Serializable
data class Property(
    val name: String,
    val value: String,
    val params: Map<String, String> = emptyMap(),
)

/** A component such as VCALENDAR, VTODO or VTIMEZONE. */
@Serializable
data class Component(
    val name: String,
    val properties: List<Property> = emptyList(),
    val components: List<Component> = emptyList(),
) {
    fun property(name: String): Property? = properties.firstOrNull { it.name == name }
    fun properties(name: String): List<Property> = properties.filter { it.name == name }
    fun components(name: String): List<Component> = components.filter { it.name == name }
}

/**
 * An iCalendar DATE or DATE-TIME as written, e.g. `20261020` with `VALUE=DATE`, `20261020T120000` with
 * `TZID=Europe/Copenhagen`, or `20261020T100000Z` in UTC. Resolving it to a moment needs time zone data,
 * which is left to the platform.
 */
@Serializable
data class ICalDate(val value: String, val params: Map<String, String> = emptyMap()) {
    /** A whole day rather than a moment. */
    val isDate: Boolean get() = params["VALUE"]?.uppercase() == "DATE" || value.length == DATE_LENGTH

    companion object {
        private const val DATE_LENGTH = 8

        /** A whole day; [month] is 1-based. */
        fun ofDate(year: Int, month: Int, day: Int) = ICalDate(
            "${year.toString().padStart(4, '0')}${month.toString().padStart(2, '0')}${day.toString().padStart(2, '0')}",
            mapOf("VALUE" to "DATE"),
        )

        /** A moment in UTC, from [ICalDateTime.format] (`20261020T100000Z`). */
        fun ofUtc(value: String) = ICalDate(value)
    }
}

class ICalParseException(message: String) : IllegalArgumentException(message)
