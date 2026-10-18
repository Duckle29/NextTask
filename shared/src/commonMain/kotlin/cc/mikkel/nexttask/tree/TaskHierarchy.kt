package cc.mikkel.nexttask.tree

import cc.mikkel.nexttask.model.Task

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
