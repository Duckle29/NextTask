package cc.sche.apps.nexttask.model

/**
 * What happened to one task: it was created ([before] is null), deleted ([after] is null) or changed.
 * Undoing swaps the two sides.
 */
data class TaskChange(val before: Task?, val after: Task?)

/** One user action, as the changes it made to tasks. */
data class Edit(val changes: List<TaskChange>) {

    /** The edit that reverses this one: it restores every task to how it was before. */
    fun inverse() = Edit(changes.map { TaskChange(before = it.after, after = it.before) })

    /** The same edit with every resulting task marked as modified at [now]. */
    fun stamped(now: String) = Edit(changes.map { it.copy(after = it.after?.withLastModified(now)) })
}

/** Bounded undo/redo stacks. Recording a new edit discards anything that could be redone. */
class EditHistory(private val limit: Int = DEFAULT_LIMIT) {

    private val undoStack = ArrayDeque<Edit>()
    private val redoStack = ArrayDeque<Edit>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun record(edit: Edit) {
        undoStack.addLast(edit)
        if (undoStack.size > limit) undoStack.removeFirst()
        redoStack.clear()
    }

    /** Takes the most recent edit off the undo stack so the caller can revert it. */
    fun undo(): Edit? = undoStack.removeLastOrNull()?.also { redoStack.addLast(it) }

    fun redo(): Edit? = redoStack.removeLastOrNull()?.also { undoStack.addLast(it) }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }

    private companion object {
        const val DEFAULT_LIMIT = 50
    }
}
