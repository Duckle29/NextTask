package cc.sche.apps.nexttask.sync

import cc.sche.apps.nexttask.caldav.SyncResult
import cc.sche.apps.nexttask.model.Task

/** What a pull did to unsynced edits, for reporting to the user. */
internal data class PullOutcome(
    val snapshot: AccountSnapshot,
    /** Edits that were combined with a newer server version of the same task. */
    val mergedEdits: Int,
    /** Edits dropped because the task no longer exists on the server. */
    val discardedEdits: Int,
)

/**
 * Applies server changes to the local state. Tasks without local edits are replaced by the server copy;
 * edited tasks are merged with it ([mergeTask]); tasks deleted on the server disappear, along with any
 * edits made to them.
 *
 * With [fullListing] the result is the complete calendar rather than a delta, so every task missing from
 * it has been deleted.
 */
internal fun AccountSnapshot.applyPull(result: SyncResult, fullListing: Boolean): PullOutcome {
    val tasks = tasks.toMutableMap()
    var merged = 0
    var discarded = 0

    fun remove(uid: String) {
        if (tasks.remove(uid)?.isDirty == true) discarded++
    }

    val uidByHref = tasks.entries.associate { (uid, stored) -> stored.href to uid }
    result.deletedHrefs.mapNotNull(uidByHref::get).forEach(::remove)
    if (fullListing) {
        val present = result.changed.map { it.task.uid }.toSet()
        tasks.keys.filter { it !in present }.forEach(::remove)
    }

    for (remote in result.changed) {
        val uid = remote.task.uid
        val local = tasks[uid]
        val base = local?.base
        tasks[uid] = if (local == null || base == null) {
            StoredTask(remote.href, remote.etag, remote.task)
        } else {
            val mergedTask = mergeTask(base, local.task, remote.task)
            if (mergedTask == remote.task) {
                StoredTask(remote.href, remote.etag, remote.task)
            } else {
                merged++
                StoredTask(remote.href, remote.etag, mergedTask, base = remote.task)
            }
        }
    }
    return PullOutcome(copy(tasks = tasks), merged, discarded)
}

/** Records the user's new versions of tasks. Tasks that are not in the list (any more) are ignored. */
internal fun AccountSnapshot.applyEdits(changed: List<Task>): AccountSnapshot {
    val updated = changed.mapNotNull { task ->
        val stored = tasks[task.uid] ?: return@mapNotNull null
        val base = stored.base ?: stored.task
        // Edited back to exactly what the server has: nothing left to upload.
        task.uid to stored.copy(task = task, base = base.takeUnless { it == task })
    }
    return copy(tasks = tasks + updated)
}

/**
 * Records that [uploaded] was written to the server. If the user edited the task again while the upload
 * was in flight, those newer edits stay pending, now relative to what was uploaded.
 */
internal fun AccountSnapshot.applyUploaded(uid: String, uploaded: Task, etag: String?): AccountSnapshot {
    val stored = tasks[uid] ?: return this
    val updated = if (stored.task == uploaded) {
        stored.copy(etag = etag, base = null)
    } else {
        stored.copy(etag = etag, base = uploaded)
    }
    return copy(tasks = tasks + (uid to updated))
}
