package cc.sche.apps.nexttask.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditHistoryTest {

    private fun edit(name: String) = Edit(
        listOf(TaskChange(before = Task(uid = name, summary = "before"), after = Task(uid = name, summary = "after"))),
    )

    private fun Edit.uid() = changes.single().before!!.uid

    @Test
    fun startsEmpty() {
        val history = EditHistory()
        assertFalse(history.canUndo)
        assertFalse(history.canRedo)
        assertNull(history.undo())
        assertNull(history.redo())
    }

    @Test
    fun undoThenRedoReturnsTheSameEdit() {
        val history = EditHistory()
        val a = edit("a")
        history.record(a)

        assertEquals(a, history.undo())
        assertFalse(history.canUndo)
        assertTrue(history.canRedo)
        assertEquals(a, history.redo())
        assertTrue(history.canUndo)
        assertFalse(history.canRedo)
    }

    @Test
    fun undoGoesBackNewestFirst() {
        val history = EditHistory()
        history.record(edit("a"))
        history.record(edit("b"))
        assertEquals("b", history.undo()!!.uid())
        assertEquals("a", history.undo()!!.uid())
    }

    @Test
    fun recordingClearsRedo() {
        val history = EditHistory()
        history.record(edit("a"))
        history.undo()
        history.record(edit("b"))
        assertFalse(history.canRedo)
    }

    @Test
    fun oldestEditsDropOutPastTheLimit() {
        val history = EditHistory(limit = 2)
        listOf("a", "b", "c").forEach { history.record(edit(it)) }

        assertEquals("c", history.undo()!!.uid())
        assertEquals("b", history.undo()!!.uid())
        assertNull(history.undo())
    }

    @Test
    fun clearForgetsEverything() {
        val history = EditHistory()
        history.record(edit("a"))
        history.undo()
        history.clear()
        assertFalse(history.canUndo)
        assertFalse(history.canRedo)
    }
}

class EditTest {

    private val created = Task.create("n", "New", parentUid = null, now = "20261018T100000Z")
    private val existing = Task(uid = "e", summary = "Existing")

    @Test
    fun inverseSwapsEverySide() {
        val edit = Edit(listOf(TaskChange(null, created), TaskChange(existing, null), TaskChange(existing, existing.copy(summary = "Renamed"))))

        assertEquals(
            Edit(listOf(TaskChange(created, null), TaskChange(null, existing), TaskChange(existing.copy(summary = "Renamed"), existing))),
            edit.inverse(),
        )
        assertEquals(edit, edit.inverse().inverse())
    }

    @Test
    fun stampingMarksOnlyTheResultingTasks() {
        val stamped = Edit(listOf(TaskChange(null, created), TaskChange(existing, null))).stamped("20261019T120000Z")

        assertEquals("20261019T120000Z", stamped.changes[0].after!!.unmapped.single { it.name == "LAST-MODIFIED" }.value)
        assertNull(stamped.changes[1].after)
        assertEquals(existing, stamped.changes[1].before)
    }

    @Test
    fun aNewTaskIsOpenAndCarriesWhatOtherClientsExpect() {
        assertEquals(TaskStatus.NeedsAction, created.status)
        assertNull(created.sortOrder)
        assertEquals(listOf("DTSTAMP", "CREATED", "LAST-MODIFIED"), created.unmapped.map { it.name })
        assertEquals(setOf("20261018T100000Z"), created.unmapped.map { it.value }.toSet())
    }
}
