package cc.sche.apps.nexttask.ical

import cc.sche.apps.nexttask.model.Task
import cc.sche.apps.nexttask.model.TaskStatus

/** Converts between CalDAV resource bodies (VCALENDAR containing one VTODO) and [Task]. */
object TaskMapper {

    private const val UID = "UID"
    private const val SUMMARY = "SUMMARY"
    private const val DESCRIPTION = "DESCRIPTION"
    private const val STATUS = "STATUS"
    private const val PRIORITY = "PRIORITY"
    private const val PERCENT_COMPLETE = "PERCENT-COMPLETE"
    private const val COMPLETED = "COMPLETED"
    private const val RELATED_TO = "RELATED-TO"
    private const val SORT_ORDER = "X-APPLE-SORT-ORDER"

    private val MAPPED = setOf(
        UID, SUMMARY, DESCRIPTION, STATUS, PRIORITY, PERCENT_COMPLETE, COMPLETED, SORT_ORDER,
    )

    fun fromIcs(ics: String): Task {
        val calendar = ICalParser.parse(ics)
        val todo = calendar.components("VTODO").singleOrNull()
            ?: throw ICalParseException("Expected exactly one VTODO")

        // RELTYPE defaults to PARENT. Only the first parent is modelled; extras stay in `unmapped`.
        val parent = todo.properties(RELATED_TO).firstOrNull { it.isParentRelation() }

        return Task(
            uid = todo.property(UID)?.value ?: throw ICalParseException("VTODO without UID"),
            summary = todo.property(SUMMARY)?.value?.let(ICalText::unescape).orEmpty(),
            description = todo.property(DESCRIPTION)?.value?.let(ICalText::unescape).orEmpty(),
            status = TaskStatus.fromIcal(todo.property(STATUS)?.value),
            priority = todo.property(PRIORITY)?.value?.toIntOrNull() ?: 0,
            percentComplete = todo.property(PERCENT_COMPLETE)?.value?.toIntOrNull(),
            completed = todo.property(COMPLETED)?.value,
            parentUid = parent?.value,
            sortOrder = todo.property(SORT_ORDER)?.value?.toIntOrNull(),
            unmapped = todo.properties.filter { it.name !in MAPPED && it !== parent },
            calendarProperties = calendar.properties,
            calendarComponents = calendar.components.filter { it.name != "VTODO" },
        )
    }

    fun toIcs(task: Task): String {
        val calendar = Component(
            name = "VCALENDAR",
            properties = task.calendarProperties,
            components = task.calendarComponents + toVTodo(task),
        )
        return ICalWriter.write(calendar)
    }

    /** The VTODO component for [task], with the properties this app does not model written back unchanged. */
    fun toVTodo(task: Task): Component {
        val properties = buildList {
            add(Property(UID, task.uid))
            add(Property(SUMMARY, ICalText.escape(task.summary)))
            if (task.description.isNotEmpty()) add(Property(DESCRIPTION, ICalText.escape(task.description)))
            add(Property(STATUS, task.status.ical))
            if (task.priority != 0) add(Property(PRIORITY, task.priority.toString()))
            task.percentComplete?.let { add(Property(PERCENT_COMPLETE, it.toString())) }
            task.completed?.let { add(Property(COMPLETED, it)) }
            task.parentUid?.let { add(Property(RELATED_TO, it, mapOf("RELTYPE" to "PARENT"))) }
            task.sortOrder?.let { add(Property(SORT_ORDER, it.toString())) }
            addAll(task.unmapped)
        }
        return Component("VTODO", properties)
    }

    private fun Property.isParentRelation(): Boolean =
        params["RELTYPE"]?.uppercase().let { it == null || it == "PARENT" }
}
