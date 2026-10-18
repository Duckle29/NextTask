package cc.mikkel.nexttask.tree

import cc.mikkel.nexttask.model.Task

/** A task together with its subtasks, to any depth. */
data class TaskNode(val task: Task, val children: List<TaskNode>) {
    val uid: String get() = task.uid

    /** This node followed by all descendants in display order. */
    fun flatten(depth: Int = 0): List<FlatTask> =
        listOf(FlatTask(task, depth)) + children.flatMap { it.flatten(depth + 1) }
}

data class FlatTask(val task: Task, val depth: Int)

/**
 * How completed tasks are ordered. Every setting keeps open tasks in their normal order
 * ([Task.sortOrder], then summary); they differ in what happens to completed ones.
 */
enum class CompletedOrder(
    internal val rootCompletedLast: Boolean,
    internal val rootNewestFirst: Boolean,
    internal val subtaskCompletedLast: Boolean,
    internal val subtaskNewestFirst: Boolean,
) {
    /** Nothing moves: completed tasks keep their normal position everywhere. */
    Off(false, false, false, false),

    /** Completed top-level tasks, last completed on top. Subtasks stay in place. */
    CompletedListOnly(true, true, false, false),

    /** Like [CompletedListOnly], and completed subtasks sink below the open ones, in normal order. */
    CompletedSubtasksLast(true, true, true, false),

    /** Completed tasks sink below open ones at every level, last completed on top. */
    Everywhere(true, true, true, true),
}

object TaskTree {

    /**
     * Builds a forest from a flat task list.
     *
     * Tasks whose parent is unknown (deleted, or in another calendar) become roots instead of
     * disappearing, and tasks that are part of a parent cycle are broken out as roots so the
     * result is always a finite tree. Siblings are ordered by [Task.sortOrder] (unset last), then
     * summary; where completed tasks go relative to that follows [completedOrder].
     */
    fun build(tasks: List<Task>, completedOrder: CompletedOrder = CompletedOrder.Everywhere): List<TaskNode> {
        val byUid = tasks.associateBy { it.uid }
        val rootUids = tasks.filter { effectiveParent(it, byUid) == null }.map { it.uid }.toMutableSet()

        // Anything not reachable from a root is part of a cycle: promote one member per cycle.
        val childrenOf = tasks.groupBy { effectiveParent(it, byUid) }
        val reachable = mutableSetOf<String>()
        fun visit(uid: String) {
            if (reachable.add(uid)) childrenOf[uid].orEmpty().forEach { visit(it.uid) }
        }
        rootUids.forEach(::visit)
        for (task in tasks) {
            if (task.uid !in reachable) {
                rootUids += task.uid
                visit(task.uid)
            }
        }

        val rootOrder = siblingOrder(completedOrder.rootCompletedLast, completedOrder.rootNewestFirst)
        val childOrder = siblingOrder(completedOrder.subtaskCompletedLast, completedOrder.subtaskNewestFirst)
        fun node(task: Task, ancestors: Set<String>): TaskNode = TaskNode(
            task,
            childrenOf[task.uid].orEmpty()
                .filter { it.uid !in ancestors && it.uid !in rootUids }
                .sortedWith(childOrder)
                .map { node(it, ancestors + task.uid) },
        )
        return tasks.filter { it.uid in rootUids }.sortedWith(rootOrder).map { node(it, setOf(it.uid)) }
    }

    private fun siblingOrder(completedLast: Boolean, newestCompletedFirst: Boolean) = Comparator<Task> { a, b ->
        when {
            completedLast && a.isCompleted != b.isCompleted -> if (a.isCompleted) 1 else -1
            completedLast && newestCompletedFirst && a.isCompleted ->
                compareValues(b.completed, a.completed).takeIf { it != 0 } ?: compareSummary(a, b)
            else -> compareValues(a.sortOrder ?: Int.MAX_VALUE, b.sortOrder ?: Int.MAX_VALUE)
                .takeIf { it != 0 } ?: compareSummary(a, b)
        }
    }

    private fun compareSummary(a: Task, b: Task) = a.summary.lowercase().compareTo(b.summary.lowercase())

    private fun effectiveParent(task: Task, byUid: Map<String, Task>): String? =
        task.parentUid?.takeIf { it != task.uid && it in byUid }

    /** Uids of [uid] and everything below it. */
    fun subtreeUids(tasks: List<Task>, uid: String): Set<String> {
        val childrenOf = tasks.groupBy { it.parentUid }
        val result = mutableSetOf<String>()
        fun visit(current: String) {
            if (result.add(current)) childrenOf[current].orEmpty().forEach { visit(it.uid) }
        }
        visit(uid)
        return result
    }
}
