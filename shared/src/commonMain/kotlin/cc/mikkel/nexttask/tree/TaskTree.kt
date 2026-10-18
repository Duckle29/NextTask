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

object TaskTree {

    /**
     * Builds a forest from a flat task list.
     *
     * Tasks whose parent is unknown (deleted, or in another calendar) become roots instead of
     * disappearing, and tasks that are part of a parent cycle are broken out as roots so the
     * result is always a finite tree. Siblings are ordered open tasks first (by [Task.sortOrder],
     * unset last, then summary), followed by completed tasks, most recently completed first.
     */
    fun build(tasks: List<Task>): List<TaskNode> {
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

        val sorted = siblingOrder
        fun node(task: Task, ancestors: Set<String>): TaskNode = TaskNode(
            task,
            childrenOf[task.uid].orEmpty()
                .filter { it.uid !in ancestors && it.uid !in rootUids }
                .sortedWith(sorted)
                .map { node(it, ancestors + task.uid) },
        )
        return tasks.filter { it.uid in rootUids }.sortedWith(sorted).map { node(it, setOf(it.uid)) }
    }

    private val siblingOrder = Comparator<Task> { a, b ->
        when {
            a.isCompleted != b.isCompleted -> if (a.isCompleted) 1 else -1
            a.isCompleted -> compareValues(b.completed, a.completed).takeIf { it != 0 } ?: compareSummary(a, b)
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
