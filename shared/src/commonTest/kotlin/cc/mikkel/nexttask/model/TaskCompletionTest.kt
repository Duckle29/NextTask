package cc.mikkel.nexttask.model

import cc.mikkel.nexttask.ical.ICalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class TaskCompletionTest {

    private val now = "20261018T120000Z"

    private fun task(uid: String, parent: String? = null, done: Boolean = false) = Task(
        uid = uid,
        summary = uid,
        parentUid = parent,
        status = if (done) TaskStatus.Completed else TaskStatus.NeedsAction,
        completed = if (done) "20260101T000000Z" else null,
    )

    @Test
    fun completingCascadesToOpenDescendants() {
        val tasks = listOf(task("a"), task("b", "a"), task("c", "b"), task("d", "a", done = true), task("other"))
        val changed = TaskCompletion.complete(tasks, "a", now)

        assertEquals(setOf("a", "b", "c"), changed.map { it.uid }.toSet())
        changed.forEach {
            assertEquals(TaskStatus.Completed, it.status)
            assertEquals(100, it.percentComplete)
            assertEquals(now, it.completed)
            assertEquals(now, it.unmapped.single { p -> p.name == "LAST-MODIFIED" }.value)
        }
    }

    @Test
    fun completingLeavesSiblingsAndParentsAlone() {
        val tasks = listOf(task("a"), task("b", "a"), task("c", "a"))
        assertEquals(listOf("b"), TaskCompletion.complete(tasks, "b", now).map { it.uid })
    }

    @Test
    fun reopeningAlsoReopensCompletedAncestors() {
        val tasks = listOf(task("a", done = true), task("b", "a", done = true), task("c", "b", done = true))
        val changed = TaskCompletion.reopen(tasks, "c", now)

        assertEquals(setOf("a", "b", "c"), changed.map { it.uid }.toSet())
        changed.forEach {
            assertEquals(TaskStatus.NeedsAction, it.status)
            assertNull(it.completed)
        }
    }

    @Test
    fun reopeningDoesNotTouchCompletedChildren() {
        val tasks = listOf(task("a", done = true), task("b", "a", done = true))
        assertEquals(listOf("a"), TaskCompletion.reopen(tasks, "a", now).map { it.uid })
    }

    @Test
    fun lastModifiedIsReplacedNotDuplicated() {
        val first = TaskCompletion.complete(listOf(task("a")), "a", "20260101T000000Z").single()
        val second = TaskCompletion.reopen(listOf(first), "a", now).single()
        assertEquals(listOf(now), second.unmapped.filter { it.name == "LAST-MODIFIED" }.map { it.value })
    }

    @Test
    fun formatsUtcDateTimes() {
        assertEquals("19700101T000000Z", ICalDateTime.format(Instant.fromEpochSeconds(0)))
        assertEquals("20000101T000001Z", ICalDateTime.format(Instant.fromEpochSeconds(946_684_801)))
        assertEquals("2026-08-14", ICalDateTime.dateOf("20260814T101500Z"))
        assertNull(ICalDateTime.dateOf("garbage"))
        assertTrue(ICalDateTime.nowUtc().matches(Regex("""\d{8}T\d{6}Z""")))
    }
}
