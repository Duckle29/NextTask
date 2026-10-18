package cc.mikkel.nexttask.tree

import cc.mikkel.nexttask.model.Task
import cc.mikkel.nexttask.model.TaskStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TaskTreeTest {

    private fun task(uid: String, parent: String? = null, order: Int? = null) =
        Task(uid = uid, summary = uid, parentUid = parent, sortOrder = order)

    @Test
    fun buildsArbitraryDepth() {
        val forest = TaskTree.build(listOf(task("c", "b"), task("a"), task("b", "a"), task("d", "c")))
        assertEquals(listOf("a" to 0, "b" to 1, "c" to 2, "d" to 3), forest.single().flatten().map { it.task.uid to it.depth })
    }

    @Test
    fun ordersSiblingsBySortOrderThenSummary() {
        val forest = TaskTree.build(listOf(task("x", order = 2), task("y", order = 1), task("b"), task("a")))
        assertEquals(listOf("y", "x", "a", "b"), forest.map { it.uid })
    }

    @Test
    fun orphanBecomesRoot() {
        val forest = TaskTree.build(listOf(task("a", "missing")))
        assertEquals(listOf("a"), forest.map { it.uid })
    }

    @Test
    fun selfParentBecomesRoot() {
        assertEquals(listOf("a"), TaskTree.build(listOf(task("a", "a"))).map { it.uid })
    }

    @Test
    fun cycleTerminatesAndKeepsAllTasks() {
        val forest = TaskTree.build(listOf(task("a", "b"), task("b", "a")))
        assertEquals(setOf("a", "b"), forest.flatMap { it.flatten() }.map { it.task.uid }.toSet())
        assertEquals(2, forest.flatMap { it.flatten() }.size)
    }

    @Test
    fun indentMakesTaskChildOfPreviousSibling() {
        val forest = TaskTree.build(listOf(task("a", order = 1), task("b", order = 2)))
        assertEquals("a", TaskHierarchy.indent(forest, "b").single().parentUid)
        assertTrue(TaskHierarchy.indent(forest, "a").isEmpty())
    }

    @Test
    fun outdentMovesToGrandparent() {
        val forest = TaskTree.build(listOf(task("a"), task("b", "a"), task("c", "b")))
        assertEquals("a", TaskHierarchy.outdent(forest, "c").single().parentUid)
        assertEquals(null, TaskHierarchy.outdent(forest, "b").single().parentUid)
        assertTrue(TaskHierarchy.outdent(forest, "a").isEmpty())
    }

    @Test
    fun reparentRefusesCycles() {
        val forest = TaskTree.build(listOf(task("a"), task("b", "a"), task("c", "b")))
        assertTrue(TaskHierarchy.reparent(forest, "a", "c").isEmpty())
        assertTrue(TaskHierarchy.reparent(forest, "a", "a").isEmpty())
    }
}

class TaskTreeCompletionOrderTest {

    private fun task(uid: String, parent: String? = null, completed: String? = null, order: Int? = null) = Task(
        uid = uid,
        summary = uid,
        parentUid = parent,
        sortOrder = order,
        status = if (completed != null) TaskStatus.Completed else TaskStatus.NeedsAction,
        completed = completed,
    )

    @Test
    fun completedTasksComeAfterOpenOnesNewestFirst() {
        val forest = TaskTree.build(
            listOf(
                task("done-old", completed = "20260101T000000Z"),
                task("open-b", order = 2),
                task("done-new", completed = "20260301T000000Z"),
                task("open-a", order = 1),
            ),
        )
        assertEquals(listOf("open-a", "open-b", "done-new", "done-old"), forest.map { it.uid })
    }

    @Test
    fun completedSubtasksSortLastWithinTheirParent() {
        val forest = TaskTree.build(
            listOf(task("p"), task("c1", "p", completed = "20260101T000000Z"), task("c2", "p"), task("c3", "p", order = 1)),
        )
        assertEquals(listOf("c3", "c2", "c1"), forest.single().children.map { it.uid })
    }
}

class CompletedOrderTest {

    private fun task(uid: String, parent: String? = null, order: Int, completedAt: String? = null) = Task(
        uid = uid,
        summary = uid,
        parentUid = parent,
        sortOrder = order,
        status = if (completedAt != null) TaskStatus.Completed else TaskStatus.NeedsAction,
        completed = completedAt,
    )

    // Roots: two completed (r-old is first in normal order). Children of "p" alternate open/completed.
    private val tasks = listOf(
        task("r-old", order = 1, completedAt = "20260101T000000Z"),
        task("r-new", order = 2, completedAt = "20260301T000000Z"),
        task("p", order = 0),
        task("a", parent = "p", order = 1),
        task("b", parent = "p", order = 2, completedAt = "20260101T000000Z"),
        task("c", parent = "p", order = 3),
        task("d", parent = "p", order = 4, completedAt = "20260301T000000Z"),
    )

    private fun completedRoots(setting: CompletedOrder) =
        TaskTree.build(tasks, setting).filter { it.task.isCompleted }.map { it.uid }

    private fun subtasks(setting: CompletedOrder) =
        TaskTree.build(tasks, setting).single { it.uid == "p" }.children.map { it.uid }

    @Test
    fun offMovesNothing() {
        assertEquals(listOf("r-old", "r-new"), completedRoots(CompletedOrder.Off))
        assertEquals(listOf("a", "b", "c", "d"), subtasks(CompletedOrder.Off))
    }

    @Test
    fun completedListOnlySortsTheListAndLeavesSubtasksInPlace() {
        assertEquals(listOf("r-new", "r-old"), completedRoots(CompletedOrder.CompletedListOnly))
        assertEquals(listOf("a", "b", "c", "d"), subtasks(CompletedOrder.CompletedListOnly))
    }

    @Test
    fun completedSubtasksLastSinksThemInNormalOrder() {
        assertEquals(listOf("r-new", "r-old"), completedRoots(CompletedOrder.CompletedSubtasksLast))
        assertEquals(listOf("a", "c", "b", "d"), subtasks(CompletedOrder.CompletedSubtasksLast))
    }

    @Test
    fun everywhereSinksThemNewestFirst() {
        assertEquals(listOf("r-new", "r-old"), completedRoots(CompletedOrder.Everywhere))
        assertEquals(listOf("a", "c", "d", "b"), subtasks(CompletedOrder.Everywhere))
    }
}
