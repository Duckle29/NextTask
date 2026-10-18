package cc.sche.apps.nexttask.model

/** One user action, as the tasks it touched before and after. Undo restores [before], redo restores [after]. */
data class Edit(val before: List<Task>, val after: List<Task>)

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
