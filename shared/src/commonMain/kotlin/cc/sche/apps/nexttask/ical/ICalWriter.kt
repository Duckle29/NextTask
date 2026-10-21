package cc.sche.apps.nexttask.ical

/** Serializes a [Component] tree to RFC 5545 text with CRLF line endings and 75-octet folding. */
object ICalWriter {

    private const val MAX_OCTETS = 75

    fun write(component: Component): String = buildString {
        writeComponent(component)
    }

    private fun StringBuilder.writeComponent(component: Component) {
        appendFolded("BEGIN:${component.name}")
        component.properties.forEach { appendFolded(format(it)) }
        component.components.forEach { writeComponent(it) }
        appendFolded("END:${component.name}")
    }

    private fun format(property: Property): String = buildString {
        append(property.name)
        for ((key, value) in property.params) {
            append(';').append(key).append('=')
            if (value.any { it == ';' || it == ':' || it == ',' }) append('"').append(value).append('"') else append(value)
        }
        append(':').append(property.value)
    }

    private fun StringBuilder.appendFolded(line: String) {
        // A line break inside a value would end the content line early and let the rest pass as other properties.
        require('\r' !in line && '\n' !in line) { "Line break in content line ${line.substringBefore(':')}" }
        var octets = 0
        for (ch in line.codePointsCompat()) {
            val width = utf8Width(ch)
            if (octets + width > MAX_OCTETS) {
                append("\r\n ")
                octets = 1
            }
            appendCodePointCompat(ch)
            octets += width
        }
        append("\r\n")
    }

    private fun utf8Width(codePoint: Int): Int = when {
        codePoint < 0x80 -> 1
        codePoint < 0x800 -> 2
        codePoint < 0x10000 -> 3
        else -> 4
    }

    private fun String.codePointsCompat(): List<Int> {
        val result = mutableListOf<Int>()
        var i = 0
        while (i < length) {
            val c = this[i]
            if (c.isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()) {
                result += 0x10000 + ((c.code - 0xD800) shl 10) + (this[i + 1].code - 0xDC00)
                i += 2
            } else {
                result += c.code
                i++
            }
        }
        return result
    }

    private fun StringBuilder.appendCodePointCompat(codePoint: Int) {
        if (codePoint < 0x10000) {
            append(codePoint.toChar())
        } else {
            val offset = codePoint - 0x10000
            append((0xD800 + (offset shr 10)).toChar())
            append((0xDC00 + (offset and 0x3FF)).toChar())
        }
    }
}

/** Escaping for iCalendar TEXT values (RFC 5545 section 3.3.11). */
object ICalText {

    fun escape(text: String): String = buildString {
        for (c in text) {
            when (c) {
                '\\' -> append("\\\\")
                ';' -> append("\\;")
                ',' -> append("\\,")
                '\n' -> append("\\n")
                '\r' -> Unit
                else -> append(c)
            }
        }
    }

    fun unescape(text: String): String = buildString {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) {
                when (val next = text[i + 1]) {
                    'n', 'N' -> append('\n')
                    else -> append(next)
                }
                i += 2
            } else {
                append(c)
                i++
            }
        }
    }
}
