package cc.sche.apps.nexttask.caldav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DavXmlTest {

    @Test
    fun etagEntitiesAreDecoded() {
        val xml = """<d:multistatus xmlns:d="DAV:"><d:response><d:href>/a.ics</d:href><d:propstat><d:prop>
            <d:getetag>&quot;abc123&quot;</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>"""

        val etag = DavXml.parseMultistatus(xml).responses.single().prop(Dav.NS, "getetag")?.text

        assertEquals("\"abc123\"", etag)
    }
}

class DavXmlEntityTest {

    @Test
    fun allPredefinedEntitiesAndNumericReferencesAreDecoded() {
        val xml = """<d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"><d:response><d:href>/a.ics</d:href><d:propstat><d:prop>
            <c:calendar-data>Tom &amp; Jerry &lt;3 &gt; &quot;x&quot; &apos;y&apos; &#233;&#x20AC;</c:calendar-data></d:prop>
            <d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>"""

        val data = DavXml.parseMultistatus(xml).responses.single().prop(Dav.CALDAV, "calendar-data")?.text

        assertEquals("Tom & Jerry <3 > \"x\" 'y' é€", data)
    }
}

class DavXmlHostileInputTest {

    @Test
    fun deeplyNestedXmlIsParsedWithoutRecursion() {
        val depth = 200_000
        val xml = """<d:multistatus xmlns:d="DAV:"><d:response><d:href>/a.ics</d:href><d:propstat><d:prop>""" +
            "<x>".repeat(depth) + "</x>".repeat(depth) +
            "</d:prop></d:propstat></d:response></d:multistatus>"

        assertEquals("/a.ics", DavXml.parseMultistatus(xml).responses.single().href)
    }

    @Test
    fun documentTypeDeclarationsAreRefused() {
        val entities = (1..9).joinToString("") { level -> """<!ENTITY e$level "${"&e${level - 1};".repeat(10)}">""" }
        val xml = """<?xml version="1.0"?><!DOCTYPE d:multistatus [<!ENTITY e0 "lol">$entities]>""" +
            """<d:multistatus xmlns:d="DAV:"><d:response><d:href>&e9;</d:href></d:response></d:multistatus>"""

        assertFailsWith<IllegalArgumentException> { DavXml.parseMultistatus(xml) }
    }
}
