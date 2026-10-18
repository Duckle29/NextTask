package cc.sche.apps.nexttask.model

import cc.sche.apps.nexttask.tree.TaskTree

/**
 * Completion rules matching Nextcloud Tasks: completing a task completes everything below it, and
 * reopening a task reopens the completed ancestors above it (an open task cannot sit under a done one).
 * Both return only the tasks that changed, stamped with [now] as `LAST-MODIFIED`.
 */
object TaskCompletion {

    fun complete(tasks: List<Task>, uid: String, now: String): List<Task> {
        val subtree = TaskTree.subtreeUids(tasks, uid)
        return tasks
            .filter { it.uid in subtree && !it.isCompleted }
            .map {
                it.copy(status = TaskStatus.Completed, percentComplete = 100, completed = now).withLastModified(now)
            }
    }

    fun reopen(tasks: List<Task>, uid: String, now: String): List<Task> {
        val byUid = tasks.associateBy { it.uid }
        val chain = mutableListOf<Task>()
        var current = byUid[uid]
        while (current != null && chain.none { it.uid == current!!.uid }) {
            chain += current
            current = current.parentUid?.let(byUid::get)
        }
        return chain
            .filter { it.isCompleted }
            .map { it.copy(status = TaskStatus.NeedsAction, percentComplete = 0, completed = null).withLastModified(now) }
    }

}
