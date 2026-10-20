package cc.sche.apps.nexttask

import cc.sche.apps.nexttask.model.Task
import cc.sche.apps.nexttask.tree.TaskNode
import org.junit.Assert.assertEquals
import org.junit.Test

class DropTargetTest {

    // a
    //   a1
    //   a2
    // b
    private val rows = listOf("a" to 0, "a1" to 1, "a2" to 1, "b" to 0).map { (uid, depth) ->
        VisibleRow(TaskNode(Task(uid = uid, summary = uid), emptyList()), depth)
    }

    @Test
    fun keepsTheWantedDepthWhereItFits() {
        assertEquals(DropTarget(1, "a", "a1"), dropTarget(rows, gap = 2, wantedDepth = 1))
        assertEquals(DropTarget(2, "a1", null), dropTarget(rows, gap = 2, wantedDepth = 2))
    }

    @Test
    fun cannotGoShallowerThanTheRowBelow() {
        // Between a1 and a2, a top-level task would leave a2 under it.
        assertEquals(DropTarget(1, "a", "a1"), dropTarget(rows, gap = 2, wantedDepth = 0))
    }

    @Test
    fun atTheEndOfASublistEitherLevelWorks() {
        assertEquals(DropTarget(1, "a", "a2"), dropTarget(rows, gap = 3, wantedDepth = 1))
        assertEquals(DropTarget(0, null, "a"), dropTarget(rows, gap = 3, wantedDepth = 0))
        assertEquals(DropTarget(2, "a2", null), dropTarget(rows, gap = 3, wantedDepth = 5))
    }

    @Test
    fun topAndBottomOfTheList() {
        assertEquals(DropTarget(0, null, null), dropTarget(rows, gap = 0, wantedDepth = 3))
        assertEquals(DropTarget(0, null, "b"), dropTarget(rows, gap = 4, wantedDepth = 0))
        assertEquals(DropTarget(1, "b", null), dropTarget(rows, gap = 4, wantedDepth = 1))
    }
}
