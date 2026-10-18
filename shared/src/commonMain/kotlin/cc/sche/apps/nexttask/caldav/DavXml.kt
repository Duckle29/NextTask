package cc.sche.apps.nexttask.caldav

import cc.sche.apps.nexttask.xml.XmlNode

internal object Dav {
    const val NS = "DAV:"
    const val CALDAV = "urn:ietf:params:xml:ns:caldav"
    const val APPLE = "http://apple.com/ns/ical/"
}

/** One `<response>` of a multistatus: [status] is set for response-level status (e.g. 404 for deleted members). */
internal data class DavResponse(
    val href: String,
    val status: Int?,
    /** Merged `<prop>` elements of all successful propstats. */
    val props: List<XmlNode>,
) {
    fun prop(namespace: String, name: String): XmlNode? =
        props.firstOrNull { it.namespace == namespace && it.name == name }
}

internal data class Multistatus(val responses: List<DavResponse>, val syncToken: String?)

internal object DavXml {

    fun parseMultistatus(xml: String): Multistatus {
        val root = XmlNode.parse(xml)
        require(root.namespace == Dav.NS && root.name == "multistatus") { "Not a multistatus response" }
        val responses = root.children(Dav.NS, "response").map { response ->
            val props = response.children(Dav.NS, "propstat")
                .filter { statusCode(it.child(Dav.NS, "status")?.text)?.let { code -> code in 200..299 } ?: true }
                .flatMap { it.child(Dav.NS, "prop")?.children.orEmpty() }
            DavResponse(
                href = response.child(Dav.NS, "href")?.text.orEmpty(),
                status = statusCode(response.child(Dav.NS, "status")?.text),
                props = props,
            )
        }
        return Multistatus(responses, root.child(Dav.NS, "sync-token")?.text)
    }

    /** `HTTP/1.1 404 Not Found` -> 404 */
    private fun statusCode(statusLine: String?): Int? = statusLine?.split(' ')?.getOrNull(1)?.toIntOrNull()

    private const val HEADER = """<?xml version="1.0" encoding="utf-8"?>"""
    private const val NAMESPACES = """xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav" xmlns:a="http://apple.com/ns/ical/""""

    fun propfind(vararg props: String): String =
        "$HEADER<d:propfind $NAMESPACES><d:prop>${props.joinToString("") { "<$it/>" }}</d:prop></d:propfind>"

    fun syncCollection(syncToken: String?): String =
        "$HEADER<d:sync-collection $NAMESPACES>" +
            "<d:sync-token>${escape(syncToken.orEmpty())}</d:sync-token>" +
            "<d:sync-level>1</d:sync-level><d:prop><d:getetag/></d:prop></d:sync-collection>"

    fun calendarMultiget(hrefs: List<String>): String =
        "$HEADER<c:calendar-multiget $NAMESPACES><d:prop><d:getetag/><c:calendar-data/></d:prop>" +
            hrefs.joinToString("") { "<d:href>${escape(it)}</d:href>" } +
            "</c:calendar-multiget>"

    /** Creates a calendar named [displayName] that accepts tasks only. */
    fun mkTaskCalendar(displayName: String): String =
        "$HEADER<c:mkcalendar $NAMESPACES><d:set><d:prop>" +
            "<d:displayname>${escape(displayName)}</d:displayname>" +
            "<c:supported-calendar-component-set><c:comp name=\"VTODO\"/></c:supported-calendar-component-set>" +
            "</d:prop></d:set></c:mkcalendar>"

    private fun escape(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
