package cc.sche.apps.nexttask.ical

/** Parses RFC 5545 text into a [Component] tree. */
object ICalParser {

    fun parse(text: String): Component {
        val root = Builder("ROOT")
        val stack = ArrayDeque<Builder>().apply { addLast(root) }

        for (line in unfold(text)) {
            val property = parseLine(line)
            when (property.name) {
                "BEGIN" -> stack.addLast(Builder(property.value.uppercase()))
                "END" -> {
                    val finished = stack.removeLast()
                    if (stack.isEmpty() || finished.name != property.value.uppercase()) {
                        throw ICalParseException("Unbalanced END:${property.value}")
                    }
                    stack.last().components += finished.build()
                }
                else -> stack.last().properties += property
            }
        }
        if (stack.size != 1) throw ICalParseException("Missing END:${stack.last().name}")
        return root.components.singleOrNull()
            ?: throw ICalParseException("Expected exactly one top-level component")
    }

    /**
     * Joins folded lines (continuations start with a space or tab) and drops blank lines. CRLF, LF and a
     * lone CR all end a line, so no value ever holds a line break that would be written back unescaped.
     */
    internal fun unfold(text: String): List<String> {
        val lines = mutableListOf<StringBuilder>()
        for (raw in text.lines()) {
            if (raw.isEmpty()) continue
            if ((raw[0] == ' ' || raw[0] == '\t') && lines.isNotEmpty()) {
                lines.last().append(raw, 1, raw.length)
            } else {
                lines += StringBuilder(raw)
            }
        }
        return lines.map { it.toString() }
    }

    internal fun parseLine(line: String): Property {
        var inQuotes = false
        var nameEnd = -1
        var valueStart = -1
        for ((i, c) in line.withIndex()) {
            when {
                c == '"' -> inQuotes = !inQuotes
                inQuotes -> Unit
                c == ';' && nameEnd == -1 -> nameEnd = i
                c == ':' -> {
                    valueStart = i + 1
                    if (nameEnd == -1) nameEnd = i
                    break
                }
            }
        }
        if (valueStart == -1) throw ICalParseException("Malformed content line: $line")

        val name = line.substring(0, nameEnd).uppercase()
        val paramText = if (nameEnd + 1 < valueStart - 1) line.substring(nameEnd + 1, valueStart - 1) else ""
        return Property(name, line.substring(valueStart), parseParams(paramText))
    }

    private fun parseParams(text: String): Map<String, String> {
        if (text.isEmpty()) return emptyMap()
        val params = linkedMapOf<String, String>()
        for (part in splitOutsideQuotes(text, ';')) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            params[part.substring(0, eq).uppercase()] = part.substring(eq + 1).trim('"')
        }
        return params
    }

    private fun splitOutsideQuotes(text: String, separator: Char): List<String> {
        val parts = mutableListOf<String>()
        var inQuotes = false
        var start = 0
        for ((i, c) in text.withIndex()) {
            if (c == '"') inQuotes = !inQuotes
            else if (c == separator && !inQuotes) {
                parts += text.substring(start, i)
                start = i + 1
            }
        }
        parts += text.substring(start)
        return parts
    }

    private class Builder(val name: String) {
        val properties = mutableListOf<Property>()
        val components = mutableListOf<Component>()
        fun build() = Component(name, properties, components)
    }
}
