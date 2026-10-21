package cc.sche.apps.nexttask.xml

import nl.adaptivity.xmlutil.EventType
import nl.adaptivity.xmlutil.XmlReader
import nl.adaptivity.xmlutil.newGenericReader
import nl.adaptivity.xmlutil.xmlStreaming

/** Minimal namespace-aware XML element tree, enough for WebDAV multistatus bodies. */
data class XmlNode(
    val namespace: String,
    val name: String,
    val text: String,
    val children: List<XmlNode>,
    val attributes: Map<String, String> = emptyMap(),
) {
    fun child(namespace: String, name: String): XmlNode? =
        children.firstOrNull { it.namespace == namespace && it.name == name }

    fun children(namespace: String, name: String): List<XmlNode> =
        children.filter { it.namespace == namespace && it.name == name }

    companion object {
        /**
         * Parses [xml] without recursion, so no nesting depth can exhaust the call stack. Documents with a
         * DTD are refused: WebDAV never needs one, and its entity declarations could expand to gigabytes.
         */
        fun parse(xml: String): XmlNode {
            // The generic reader is the same parser on every platform, so DTDs are reported (and refused) everywhere.
            val reader = xmlStreaming.newGenericReader(xml)
            // The elements opened but not yet closed, innermost last.
            val open = ArrayDeque<Builder>()
            try {
                while (reader.hasNext()) {
                    when (reader.next()) {
                        EventType.DOCDECL -> throw IllegalArgumentException("Document type declarations are not accepted")
                        EventType.START_ELEMENT -> open.addLast(Builder(reader))
                        EventType.TEXT, EventType.CDSECT, EventType.ENTITY_REF -> open.lastOrNull()?.text?.append(reader.text)
                        EventType.END_ELEMENT -> {
                            val element = open.removeLast().build()
                            val parent = open.lastOrNull() ?: return element
                            parent.children += element
                        }
                        else -> Unit
                    }
                }
            } finally {
                reader.close()
            }
            throw IllegalArgumentException(open.lastOrNull()?.let { "Unterminated element <${it.name}>" } ?: "No root element")
        }
    }

    private class Builder(reader: XmlReader) {
        val namespace: String = reader.namespaceURI
        val name: String = reader.localName
        val attributes = (0 until reader.attributeCount).associate { reader.getAttributeLocalName(it) to reader.getAttributeValue(it) }
        val text = StringBuilder()
        val children = mutableListOf<XmlNode>()

        fun build() = XmlNode(namespace, name, text.toString().trim(), children, attributes)
    }
}
