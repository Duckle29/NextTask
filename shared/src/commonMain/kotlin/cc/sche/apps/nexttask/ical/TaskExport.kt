package cc.sche.apps.nexttask.ical

import cc.sche.apps.nexttask.model.Task
import cc.sche.apps.nexttask.tree.CompletedOrder
import cc.sche.apps.nexttask.tree.TaskTree

/**
 * Writes tasks as a standard iCalendar (.ics) file: one VCALENDAR holding a VTODO per task, with subtask
 * relations as `RELATED-TO;RELTYPE=PARENT`. Nextcloud, Thunderbird and most calendar apps can import it,
 * so the file keeps the user's tasks usable without this app or the server.
 */
object TaskExport {

    fun toIcs(tasks: List<Task>, calendarName: String): String {
        // Parents before their children, in the order the app shows them.
        val ordered = TaskTree.build(tasks, CompletedOrder.Off).flatMap { root -> root.flatten().map { it.task } }

        // Time zone definitions are shared by all tasks of a calendar; write each one once.
        val timeZones = ordered
            .flatMap { it.calendarComponents }
            .filter { it.name == "VTIMEZONE" }
            .distinctBy { it.property("TZID")?.value }

        val calendar = Component(
            name = "VCALENDAR",
            properties = buildList {
                add(Property("VERSION", "2.0"))
                add(Property("PRODID", "-//NextTask//EN"))
                if (calendarName.isNotEmpty()) add(Property("X-WR-CALNAME", ICalText.escape(calendarName)))
            },
            components = timeZones + ordered.map(TaskMapper::toVTodo),
        )
        return ICalWriter.write(calendar)
    }
}
