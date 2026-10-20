package cc.sche.apps.nexttask.tree

import cc.sche.apps.nexttask.model.Task

/** Pure edits of the parent/child structure. Each returns the changed tasks, or empty if the edit is not allowed. */
object TaskHierarchy {

    /** Makes [uid] the last child of its previous sibling. Not possible for the first sibling. */
    fun indent(forest: List<TaskNode>, uid: String): List<Task> {
        val siblings = siblingsOf(forest, uid) ?: return emptyList()
        val index = siblings.indexOfFirst { it.uid == uid }
        if (index <= 0) return emptyList()
        return reparent(forest, uid, siblings[index - 1].uid)
    }

    /** Moves [uid] up one level, to become a sibling of its current parent. */
    fun outdent(forest: List<TaskNode>, uid: String): List<Task> {
        val parent = findParent(forest, uid) ?: return emptyList()
        return reparent(forest, uid, findParent(forest, parent.uid)?.uid)
    }

    /** Sets the parent of [uid] to [newParentUid] (null for top level), refusing to create a cycle. */
    fun reparent(forest: List<TaskNode>, uid: String, newParentUid: String?): List<Task> {
        val node = find(forest, uid) ?: return emptyList()
        if (newParentUid != null) {
            if (find(forest, newParentUid) == null) return emptyList()
            if (find(listOf(node), newParentUid) != null) return emptyList()
        }
        if (node.task.parentUid == newParentUid) return emptyList()
        return listOf(node.task.copy(parentUid = newParentUid))
    }

    /**
     * Moves [uid], with its subtasks, under [newParentUid] (null for top level), right after the sibling
     * [afterUid] (null to come first). Like Nextcloud Tasks, it then numbers the siblings there 0, 1, 2, … in
     * [Task.sortOrder]; only tasks whose parent or number changes are returned. Siblings are taken in the order
     * of [forest], so at top level it holds just the roots that can be reordered (the open ones).
     * Refuses a cycle, an unknown parent, and an [afterUid] that is not a sibling there.
     */
    fun move(forest: List<TaskNode>, uid: String, newParentUid: String?, afterUid: String?): List<Task> {
        val node = find(forest, uid) ?: return emptyList()
        val siblings = if (newParentUid == null) {
            forest
        } else {
            if (find(listOf(node), newParentUid) != null) return emptyList()
            find(forest, newParentUid)?.children ?: return emptyList()
        }
        val order = siblings.map { it.task }.filterNot { it.uid == uid }.toMutableList()
        val position = if (afterUid == null) 0 else order.indexOfFirst { it.uid == afterUid } + 1
        if (afterUid != null && position == 0) return emptyList()
        order.add(position, node.task)
        return order.mapIndexedNotNull { index, task ->
            // Only the moved task changes parent: a sibling shown at top level may name a parent this list lacks.
            val placed = if (task.uid == uid) task.copy(parentUid = newParentUid, sortOrder = index) else task.copy(sortOrder = index)
            placed.takeIf { it != task }
        }
    }

    private fun find(forest: List<TaskNode>, uid: String): TaskNode? {
        for (node in forest) {
            if (node.uid == uid) return node
            find(node.children, uid)?.let { return it }
        }
        return null
    }

    private fun findParent(forest: List<TaskNode>, uid: String): TaskNode? {
        for (node in forest) {
            if (node.children.any { it.uid == uid }) return node
            findParent(node.children, uid)?.let { return it }
        }
        return null
    }

    private fun siblingsOf(forest: List<TaskNode>, uid: String): List<TaskNode>? =
        if (forest.any { it.uid == uid }) forest else findParent(forest, uid)?.children
}
