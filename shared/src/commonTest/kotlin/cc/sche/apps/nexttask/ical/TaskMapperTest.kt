package cc.sche.apps.nexttask.ical

import cc.sche.apps.nexttask.model.TaskStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskMapperTest {

    private val sample = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//Nextcloud Tasks//EN
        BEGIN:VTODO
        UID:child-1
        SUMMARY:Buy milk\, eggs
        DESCRIPTION:Line one\nLine two
        STATUS:NEEDS-ACTION
        PRIORITY:5
        RELATED-TO;RELTYPE=PARENT:parent-1
        X-APPLE-SORT-ORDER:3
        DUE;TZID=Europe/Copenhagen:20261020T120000
        END:VTODO
        END:VCALENDAR
    """.trimIndent()

    @Test
    fun parsesMappedFields() {
        val task = TaskMapper.fromIcs(sample)
        assertEquals("child-1", task.uid)
        assertEquals("Buy milk, eggs", task.summary)
        assertEquals("Line one\nLine two", task.description)
        assertEquals(TaskStatus.NeedsAction, task.status)
        assertEquals(5, task.priority)
        assertEquals("parent-1", task.parentUid)
        assertEquals(3, task.sortOrder)
    }

    @Test
    fun relatedToWithoutReltypeIsParent() {
        val task = TaskMapper.fromIcs(sample.replace(";RELTYPE=PARENT", ""))
        assertEquals("parent-1", task.parentUid)
    }

    @Test
    fun childRelationIsNotTreatedAsParent() {
        val task = TaskMapper.fromIcs(sample.replace("RELTYPE=PARENT", "RELTYPE=CHILD"))
        assertNull(task.parentUid)
        assertTrue(task.unmapped.any { it.name == "RELATED-TO" })
    }

    @Test
    fun roundTripKeepsUnmappedProperties() {
        val task = TaskMapper.fromIcs(sample)
        val reparsed = TaskMapper.fromIcs(TaskMapper.toIcs(task))
        assertEquals(task, reparsed)
        assertEquals(ICalDate("20261020T120000", mapOf("TZID" to "Europe/Copenhagen")), reparsed.due)
        assertTrue(reparsed.unmapped.none { it.name == "DUE" })
    }

    @Test
    fun writesDueDates() {
        val allDay = TaskMapper.toIcs(TaskMapper.fromIcs(sample).copy(due = ICalDate.ofDate(2026, 3, 7)))
        assertTrue("DUE;VALUE=DATE:20260307" in allDay, allDay)
        assertEquals(1, Regex("DUE").findAll(allDay).count())

        val utc = TaskMapper.toIcs(TaskMapper.fromIcs(sample).copy(due = ICalDate.ofUtc("20261020T100000Z")))
        assertTrue("DUE:20261020T100000Z" in utc, utc)

        val none = TaskMapper.toIcs(TaskMapper.fromIcs(sample).copy(due = null))
        assertTrue("DUE" !in none, none)
    }

    @Test
    fun keepsTimezoneComponent() {
        val withTz = sample.replace(
            "BEGIN:VTODO",
            "BEGIN:VTIMEZONE\nTZID:Europe/Copenhagen\nEND:VTIMEZONE\nBEGIN:VTODO",
        )
        val out = TaskMapper.toIcs(TaskMapper.fromIcs(withTz))
        assertTrue("BEGIN:VTIMEZONE" in out)
    }

    @Test
    fun unfoldsFoldedLines() {
        val folded = sample.replace("SUMMARY:Buy milk\\, eggs", "SUMMARY:Buy milk\\,\n  eggs")
        assertEquals("Buy milk, eggs", TaskMapper.fromIcs(folded).summary)
    }

    @Test
    fun foldsLongLinesAtSeventyFiveOctets() {
        val task = TaskMapper.fromIcs(sample).copy(summary = "ø".repeat(100))
        val out = TaskMapper.toIcs(task)
        for (line in out.split("\r\n")) {
            assertTrue(line.encodeToByteArray().size <= 75, "line too long: $line")
        }
        assertEquals(task.summary, TaskMapper.fromIcs(out).summary)
    }

    @Test
    fun rejectsMissingUid() {
        assertFailsWith<ICalParseException> {
            TaskMapper.fromIcs(sample.replace("UID:child-1\n", ""))
        }
    }

    @Test
    fun rejectsUnbalancedComponents() {
        assertFailsWith<ICalParseException> {
            TaskMapper.fromIcs(sample.replace("END:VCALENDAR", ""))
        }
    }

    @Test
    fun keepsRemindersAndOtherNestedComponentsThroughAnEdit() {
        val withReminder = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VTODO
            UID:t-1
            SUMMARY:Call the dentist
            DUE;VALUE=DATE:20261020
            BEGIN:VALARM
            ACTION:DISPLAY
            DESCRIPTION:Reminder
            TRIGGER;RELATED=START:-PT15M
            END:VALARM
            END:VTODO
            END:VCALENDAR
        """.trimIndent()

        val edited = TaskMapper.fromIcs(withReminder).copy(status = TaskStatus.Completed)
        val written = TaskMapper.toIcs(edited)

        assertTrue("BEGIN:VALARM\r\nACTION:DISPLAY\r\nDESCRIPTION:Reminder\r\nTRIGGER;RELATED=START:-PT15M\r\nEND:VALARM" in written, written)
        assertTrue("DUE;VALUE=DATE:20261020" in written, written)
        assertTrue(written.indexOf("BEGIN:VALARM") < written.indexOf("END:VTODO"), "the reminder stays inside the task: $written")
        assertEquals(TaskMapper.fromIcs(written), edited)
    }
}
