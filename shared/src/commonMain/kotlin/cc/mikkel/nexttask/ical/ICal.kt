package cc.mikkel.nexttask.ical

/** A single iCalendar content line, e.g. `RELATED-TO;RELTYPE=PARENT:abc`. */
data class Property(
    val name: String,
    val value: String,
    val params: Map<String, String> = emptyMap(),
)

/** A component such as VCALENDAR, VTODO or VTIMEZONE. */
data class Component(
    val name: String,
    val properties: List<Property> = emptyList(),
    val components: List<Component> = emptyList(),
) {
    fun property(name: String): Property? = properties.firstOrNull { it.name == name }
    fun properties(name: String): List<Property> = properties.filter { it.name == name }
    fun components(name: String): List<Component> = components.filter { it.name == name }
}

class ICalParseException(message: String) : IllegalArgumentException(message)
