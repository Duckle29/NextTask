package cc.sche.apps.nexttask.sync

import cc.sche.apps.nexttask.model.Task
import cc.sche.apps.nexttask.model.withLastModified

/**
 * Three-way merge of one task: [base] is what both sides started from, [local] what the user made of it
 * offline and [remote] what the server has now.
 *
 * Each field that the user changed keeps the user's value; every other field takes the server's, so
 * edits made elsewhere in the meantime are not lost. If both sides changed the same field, the user's
 * edit wins. Status, percent and completion time count as one field, so they never end up out of step.
 * Properties this app does not edit (dates, categories, ...) always come from the server.
 */
internal fun mergeTask(base: Task, local: Task, remote: Task): Task {
    fun <T> pick(field: (Task) -> T): T = if (field(local) != field(base)) field(local) else field(remote)

    val completion = if (completionChanged(base, local)) local else remote
    val merged = remote.copy(
        summary = pick { it.summary },
        description = pick { it.description },
        priority = pick { it.priority },
        parentUid = pick { it.parentUid },
        sortOrder = pick { it.sortOrder },
        status = completion.status,
        percentComplete = completion.percentComplete,
        completed = completion.completed,
    )
    if (merged == remote) return merged
    // Something of the user's survives, so the result is a new edit: stamp it with when the user made it.
    val editedAt = local.unmapped.firstOrNull { it.name == "LAST-MODIFIED" }?.value
    return if (editedAt != null) merged.withLastModified(editedAt) else merged
}

private fun completionChanged(base: Task, local: Task) =
    local.status != base.status || local.percentComplete != base.percentComplete || local.completed != base.completed
