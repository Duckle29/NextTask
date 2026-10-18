package cc.sche.apps.nexttask

import cc.sche.apps.nexttask.ical.Component
import cc.sche.apps.nexttask.ical.ICalDate
import cc.sche.apps.nexttask.ical.Property
import cc.sche.apps.nexttask.model.Task
import cc.sche.apps.nexttask.model.TaskStatus
import cc.sche.apps.nexttask.sync.AccountSnapshot
import cc.sche.apps.nexttask.sync.CalendarSnapshot
import cc.sche.apps.nexttask.sync.StoredTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class RemindersTest {

    private val zone = ZoneId.of("Europe/Copenhagen")

    private fun alarm(trigger: String, vararg params: Pair<String, String>) =
        Component("VALARM", listOf(Property("ACTION", "DISPLAY"), Property("TRIGGER", trigger, params.toMap())))

    private fun task(vararg alarms: Component, due: ICalDate? = null, start: String? = null) = Task(
        uid = "t",
        summary = "Call the dentist",
        due = due,
        unmapped = listOfNotNull(start?.let { Property("DTSTART", it) }),
        subcomponents = alarms.toList(),
    )

    @Test
    fun readsIcalDurations() {
        assertEquals(Duration.ofMinutes(-15), parseDuration("-PT15M"))
        assertEquals(Duration.ofDays(1), parseDuration("P1D"))
        assertEquals(Duration.ofDays(-1).minusHours(2), parseDuration("-P1DT2H"))
        assertEquals(Duration.ofDays(14), parseDuration("+P2W"))
        assertEquals(Duration.ZERO, parseDuration("PT0S"))
        assertNull(parseDuration("P"))
        assertNull(parseDuration("15 minutes"))
    }

    @Test
    fun placesRemindersAtAMomentOrRelativeToStartOrDue() {
        val t = task(
            alarm("20261020T080000Z", "VALUE" to "DATE-TIME"),
            alarm("-PT15M", "RELATED" to "END"),
            alarm("-PT1H"),
            due = ICalDate("20261020T120000Z"),
            start = "20261019T100000Z",
        )
        assertEquals(
            listOf(Instant.parse("2026-10-20T08:00:00Z"), Instant.parse("2026-10-20T11:45:00Z"), Instant.parse("2026-10-19T09:00:00Z")),
            t.reminderTimes(zone),
        )
    }

    @Test
    fun aReminderOnAWholeDayCountsFromItsStart() {
        val t = task(alarm("-PT15M", "RELATED" to "END"), due = ICalDate.ofDate(2026, 10, 20))
        assertEquals(listOf(Instant.parse("2026-10-19T21:45:00Z")), t.reminderTimes(zone)) // 23:45 the evening before, local
    }

    @Test
    fun remindersThatCannotBePlacedAreLeftOut() {
        val t = task(alarm("-PT15M"), alarm("-PT15M", "RELATED" to "END"), alarm("soon"))
        assertEquals(emptyList<Instant>(), t.reminderTimes(zone))
        assertTrue(t.hasReminders)
        assertFalse(task().hasReminders)
    }

    @Test
    fun theAccountsRemindersCoverOpenTasksOnlyEarliestFirst() {
        fun stored(task: Task, deleted: Boolean = false) = StoredTask("/c/${task.uid}.ics", "e", task, isDeleted = deleted)
        val late = task(alarm("20261021T080000Z", "VALUE" to "DATE-TIME")).copy(uid = "late", summary = "Late")
        val early = task(alarm("20261020T080000Z", "VALUE" to "DATE-TIME")).copy(uid = "early", summary = "Early")
        val done = early.copy(uid = "done", status = TaskStatus.Completed)
        val deleted = early.copy(uid = "deleted")
        val snapshot = AccountSnapshot(
            "https://cloud.example.com", "u",
            listOf(
                CalendarSnapshot("/a/", "Home", tasks = mapOf("late" to stored(late), "done" to stored(done))),
                CalendarSnapshot("/b/", "Work", tasks = mapOf("early" to stored(early), "deleted" to stored(deleted, deleted = true))),
            ),
        )

        assertEquals(
            listOf(
                Reminder("/b/", "Work", "early", "Early", Instant.parse("2026-10-20T08:00:00Z")),
                Reminder("/a/", "Home", "late", "Late", Instant.parse("2026-10-21T08:00:00Z")),
            ),
            snapshot.reminders(zone),
        )
    }
}
