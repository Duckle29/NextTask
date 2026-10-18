package cc.sche.apps.nexttask.sync

import cc.sche.apps.nexttask.caldav.SyncResult
import cc.sche.apps.nexttask.caldav.taskHref
import cc.sche.apps.nexttask.model.Task
import cc.sche.apps.nexttask.model.TaskChange

/** What a pull did to changes the server had not seen yet, for reporting to the user. */
internal data class PullOutcome(
    val snapshot: AccountSnapshot,
    /** Edits that were combined with a newer server version of the same task. */
    val mergedEdits: Int,
    /** Edits dropped because the task no longer exists on the server. */
    val discardedEdits: Int,
    /** Deletions given up because the task was changed on the server in the meantime. */
    val restoredDeletions: Int,
)

/**
 * Applies server changes to the local state. Tasks without local changes are replaced by the server copy;
 * edited tasks are merged with it ([mergeTask]); tasks deleted on the server disappear, along with any
 * edits made to them. Tasks created here are not on the server yet, so the server cannot have deleted them.
 * A task the user deleted stays deleted, unless another client changed it since: nothing is thrown away
 * that somebody has worked on.
 *
 * With [fullListing] the result is the complete calendar rather than a delta, so every task missing from
 * it has been deleted.
 */
internal fun AccountSnapshot.applyPull(result: SyncResult, fullListing: Boolean): PullOutcome {
    val tasks = tasks.toMutableMap()
    var merged = 0
    var discarded = 0
    var restored = 0

    fun remove(uid: String) {
        val removed = tasks.remove(uid) ?: return
        if (removed.base != null && !removed.isDeleted) discarded++
    }

    val uidByHref = tasks.entries.filterNot { it.value.isNew }.associate { (uid, stored) -> stored.href to uid }
    result.deletedHrefs.mapNotNull(uidByHref::get).forEach(::remove)
    if (fullListing) {
        val present = result.changed.map { it.task.uid }.toSet()
        tasks.filter { (uid, stored) -> uid !in present && !stored.isNew }.keys.forEach(::remove)
    }

    for (remote in result.changed) {
        val uid = remote.task.uid
        val local = tasks[uid]
        val base = local?.base
        tasks[uid] = when {
            local == null -> StoredTask(remote.href, remote.etag, remote.task)
            local.isDeleted ->
                // Unknown or unchanged since we last saw it: still ours to delete, and now we know its ETag.
                if (local.etag == null || local.etag == remote.etag) {
                    local.copy(href = remote.href, etag = remote.etag, isNew = false)
                } else {
                    restored++
                    StoredTask(remote.href, remote.etag, remote.task)
                }
            // Created here, yet the server has it: our own upload, whose confirmation never arrived.
            local.isNew ->
                if (local.task == remote.task) StoredTask(remote.href, remote.etag, remote.task)
                else StoredTask(remote.href, remote.etag, local.task, base = remote.task)
            base == null -> StoredTask(remote.href, remote.etag, remote.task)
            else -> {
                val mergedTask = mergeTask(base, local.task, remote.task)
                if (mergedTask == remote.task) {
                    StoredTask(remote.href, remote.etag, remote.task)
                } else {
                    merged++
                    StoredTask(remote.href, remote.etag, mergedTask, base = remote.task)
                }
            }
        }
    }
    return PullOutcome(copy(tasks = tasks), merged, discarded, restored)
}

/** Records the user's new versions of existing tasks. Tasks that are not in the list (any more) are ignored. */
internal fun AccountSnapshot.applyEdits(changed: List<Task>): AccountSnapshot =
    changed.fold(this) { snapshot, task -> snapshot.updated(task) }

/**
 * Records what the user did: creations, deletions and edits. Applying the reverse of a change
 * ([cc.sche.apps.nexttask.model.Edit.inverse]) undoes it, including bringing a deleted task back.
 */
internal fun AccountSnapshot.applyChanges(changes: List<TaskChange>): AccountSnapshot =
    changes.fold(this) { snapshot, change ->
        val before = change.before
        val after = change.after
        when {
            after == null -> if (before == null) snapshot else snapshot.deleted(before.uid)
            before == null -> snapshot.created(after)
            else -> snapshot.updated(after)
        }
    }

private fun AccountSnapshot.updated(task: Task): AccountSnapshot {
    val stored = tasks[task.uid]?.takeUnless { it.isDeleted } ?: return this
    return copy(tasks = tasks + (task.uid to stored.edited(task)))
}

/** Local edits, kept relative to the server copy; edited back to exactly that copy, there is nothing to upload. */
private fun StoredTask.edited(newTask: Task): StoredTask {
    if (isNew) return copy(task = newTask)
    val serverCopy = base ?: task
    return copy(task = newTask, base = serverCopy.takeUnless { it == newTask })
}

private fun AccountSnapshot.created(task: Task): AccountSnapshot {
    val stored = tasks[task.uid]
    return when {
        // Bringing back a task deleted earlier, which the server may not have been told about yet.
        stored != null -> copy(tasks = tasks + (task.uid to stored.copy(isDeleted = false).edited(task)))
        // A brand new task needs a calendar to live in.
        calendarHref == null -> this
        else -> copy(tasks = tasks + (task.uid to StoredTask(taskHref(calendarHref, task.uid), etag = null, task = task, isNew = true)))
    }
}

private fun AccountSnapshot.deleted(uid: String): AccountSnapshot {
    val stored = tasks[uid] ?: return this
    return copy(tasks = tasks + (uid to stored.copy(isDeleted = true)))
}

/** Drops tombstones of tasks that were created and deleted before they ever reached the server. */
internal fun AccountSnapshot.withoutAbandonedTasks(): AccountSnapshot =
    copy(tasks = tasks.filterNot { it.value.isDeleted && it.value.isNew })

/**
 * Records that [uploaded] was written to the server. If the user changed the task again while the upload
 * was in flight, those newer edits stay pending, now relative to what was uploaded; if they deleted it,
 * the deletion is still pending and now has a server copy to delete.
 */
internal fun AccountSnapshot.applyUploaded(uid: String, uploaded: Task, etag: String?): AccountSnapshot {
    val stored = tasks[uid] ?: return this
    val updated = when {
        stored.isDeleted -> stored.copy(etag = etag, isNew = false)
        stored.task == uploaded -> stored.copy(etag = etag, base = null, isNew = false)
        else -> stored.copy(etag = etag, base = uploaded, isNew = false)
    }
    return copy(tasks = tasks + (uid to updated))
}

/**
 * Records that the server deleted the task. If the user brought it back in the meantime, it has to be
 * created again, as the server no longer has it.
 */
internal fun AccountSnapshot.applyDeletedOnServer(uid: String): AccountSnapshot {
    val stored = tasks[uid] ?: return this
    if (stored.isDeleted) return copy(tasks = tasks - uid)
    return copy(tasks = tasks + (uid to stored.copy(etag = null, base = null, isNew = true)))
}
