package cc.sche.apps.nexttask.ical

import cc.sche.apps.nexttask.model.Task
import cc.sche.apps.nexttask.model.TaskStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TaskExportTest {

    private val timeZone = Component("VTIMEZONE", listOf(Property("TZID", "Europe/Copenhagen")))

    private fun task(uid: String, summary: String = uid, parent: String? = null, extra: List<Property> = emptyList()) =
        Task(uid = uid, summary = summary, parentUid = parent, unmapped = extra, calendarComponents = listOf(timeZone))

    @Test
    fun writesOneCalendarWithAVTodoPerTask() {
        val out = TaskExport.toIcs(listOf(task("child", parent = "root"), task("root")), "My tasks")
        val calendar = ICalParser.parse(out)

        assertEquals("VCALENDAR", calendar.name)
        assertEquals("My tasks", calendar.property("X-WR-CALNAME")?.value)
        assertEquals(listOf("root", "child"), calendar.components("VTODO").map { it.property("UID")?.value })
        assertEquals("root", calendar.components("VTODO")[1].property("RELATED-TO")?.value)
    }

    @Test
    fun writesEachTimeZoneOnce() {
        val calendar = ICalParser.parse(TaskExport.toIcs(listOf(task("a"), task("b"), task("c")), ""))
        assertEquals(1, calendar.components("VTIMEZONE").size)
    }

    @Test
    fun keepsPropertiesTheAppDoesNotModel() {
        val due = Property("DUE", "20261020T120000", mapOf("TZID" to "Europe/Copenhagen"))
        val out = TaskExport.toIcs(listOf(task("a", extra = listOf(due))), "")
        val vtodo = ICalParser.parse(out).components("VTODO").single()

        assertEquals(due, vtodo.property("DUE"))
    }

    @Test
    fun includesCompletedTasksAndEscapesText() {
        val done = task("a", summary = "Milk, eggs; bread").copy(status = TaskStatus.Completed, completed = "20261018T100000Z")
        val out = TaskExport.toIcs(listOf(done), "")
        val vtodo = ICalParser.parse(out).components("VTODO").single()

        assertEquals("COMPLETED", vtodo.property("STATUS")?.value)
        assertTrue("SUMMARY:Milk\\, eggs\\; bread" in out)
    }

    @Test
    fun canBeReadBackByTheMapperPerTask() {
        val original = task("a", summary = "Round trip")
        val vtodo = ICalParser.parse(TaskExport.toIcs(listOf(original), "")).components("VTODO").single()
        val single = ICalWriter.write(Component("VCALENDAR", original.calendarProperties, original.calendarComponents + vtodo))

        assertEquals(original, TaskMapper.fromIcs(single))
    }

    @Test
    fun emptyListStillProducesAValidCalendar() {
        val calendar = ICalParser.parse(TaskExport.toIcs(emptyList(), ""))
        assertEquals(0, calendar.components("VTODO").size)
    }
}
