package cc.mikkel.nexttask.xml

import nl.adaptivity.xmlutil.EventType
import nl.adaptivity.xmlutil.XmlReader
import nl.adaptivity.xmlutil.core.KtXmlReader

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
        fun parse(xml: String): XmlNode {
            val reader = KtXmlReader(xml)
            try {
                while (reader.hasNext()) {
                    if (reader.next() == EventType.START_ELEMENT) return readElement(reader)
                }
            } finally {
                reader.close()
            }
            throw IllegalArgumentException("No root element")
        }

        private fun readElement(reader: XmlReader): XmlNode {
            val namespace = reader.namespaceURI
            val name = reader.localName
            val attributes = (0 until reader.attributeCount).associate { reader.getAttributeLocalName(it) to reader.getAttributeValue(it) }
            val text = StringBuilder()
            val children = mutableListOf<XmlNode>()
            while (reader.hasNext()) {
                when (reader.next()) {
                    EventType.START_ELEMENT -> children += readElement(reader)
                    EventType.TEXT, EventType.CDSECT, EventType.ENTITY_REF -> text.append(reader.text)
                    EventType.END_ELEMENT -> return XmlNode(namespace, name, text.toString().trim(), children, attributes)
                    else -> Unit
                }
            }
            throw IllegalArgumentException("Unterminated element <$name>")
        }
    }
}
