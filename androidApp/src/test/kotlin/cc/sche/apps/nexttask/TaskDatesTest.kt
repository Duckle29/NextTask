package cc.sche.apps.nexttask

import cc.sche.apps.nexttask.ical.ICalDate
import cc.sche.apps.nexttask.model.Task
import cc.sche.apps.nexttask.model.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

class TaskDatesTest {

    private val copenhagen = ZoneId.of("Europe/Copenhagen")
    private val now = ZonedDateTime.of(2026, 10, 19, 12, 0, 0, 0, copenhagen) // a Monday

    @Test
    fun resolvesWholeDays() {
        assertEquals(TaskDate.AllDay(LocalDate.of(2026, 10, 20), copenhagen), ICalDate.ofDate(2026, 10, 20).resolve(copenhagen))
        assertEquals(TaskDate.AllDay(LocalDate.of(2026, 10, 20), copenhagen), ICalDate("20261020").resolve(copenhagen))
    }

    @Test
    fun resolvesUtcZonedAndFloatingTimesIntoTheDeviceZone() {
        val expected = TaskDate.At(ZonedDateTime.of(2026, 10, 20, 12, 0, 0, 0, copenhagen))
        assertEquals(expected, ICalDate("20261020T100000Z").resolve(copenhagen))
        assertEquals(expected, ICalDate("20261020T060000", mapOf("TZID" to "America/New_York")).resolve(copenhagen))
        assertEquals(expected, ICalDate("20261020T120000").resolve(copenhagen))
        // A zone name Java doesn't know (e.g. an Outlook one) is read as local time.
        assertEquals(expected, ICalDate("20261020T120000", mapOf("TZID" to "W. Europe Standard Time")).resolve(copenhagen))
    }

    @Test
    fun unreadableDatesAreNull() {
        assertNull(ICalDate("tomorrow").resolve(copenhagen))
        assertNull(ICalDate("20261320").resolve(copenhagen))
    }

    @Test
    fun pickedDatesAreStoredAsWholeDaysOrUtc() {
        assertEquals(ICalDate("20261020", mapOf("VALUE" to "DATE")), dueDate(LocalDate.of(2026, 10, 20), null, copenhagen))
        assertEquals(ICalDate("20261020T120000Z"), dueDate(LocalDate.of(2026, 10, 20), LocalTime.of(14, 0), copenhagen))
    }

    @Test
    fun overdueMeansOpenAndPast() {
        val task = Task(uid = "t", summary = "t")
        assertFalse(task.isOverdue(now))
        assertTrue(task.copy(due = ICalDate.ofDate(2026, 10, 18)).isOverdue(now))
        assertFalse("due today is not overdue yet", task.copy(due = ICalDate.ofDate(2026, 10, 19)).isOverdue(now))
        assertTrue(task.copy(due = ICalDate("20261019T090000Z")).isOverdue(now))
        assertFalse(task.copy(due = ICalDate("20261019T110000Z")).isOverdue(now))
        assertFalse(task.copy(due = ICalDate.ofDate(2026, 10, 18), status = TaskStatus.Completed).isOverdue(now))
    }

    @Test
    fun labelsSayWhenInPlainWords() {
        fun label(due: ICalDate) = dueLabel(due.resolve(copenhagen)!!, now, Locale.UK)
        assertEquals("Due today", label(ICalDate.ofDate(2026, 10, 19)))
        assertEquals("Due tomorrow 14:00", label(ICalDate("20261020T120000Z")))
        assertEquals("Due yesterday", label(ICalDate.ofDate(2026, 10, 18)))
        assertEquals("Due Fri 23 Oct", label(ICalDate.ofDate(2026, 10, 23)))
        assertEquals("Due Tue 5 Jan 2027", label(ICalDate.ofDate(2027, 1, 5)))
    }
}
