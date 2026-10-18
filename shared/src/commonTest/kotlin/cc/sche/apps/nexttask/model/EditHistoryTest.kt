package cc.sche.apps.nexttask.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditHistoryTest {

    private fun edit(name: String) = Edit(
        before = listOf(Task(uid = name, summary = "before")),
        after = listOf(Task(uid = name, summary = "after")),
    )

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
        assertEquals("b", history.undo()!!.before.single().uid)
        assertEquals("a", history.undo()!!.before.single().uid)
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

        assertEquals("c", history.undo()!!.before.single().uid)
        assertEquals("b", history.undo()!!.before.single().uid)
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
