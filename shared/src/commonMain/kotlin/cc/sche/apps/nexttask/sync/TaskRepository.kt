package cc.sche.apps.nexttask.sync

import cc.sche.apps.nexttask.caldav.CalDavClient
import cc.sche.apps.nexttask.caldav.ConflictException
import cc.sche.apps.nexttask.caldav.SyncResult
import cc.sche.apps.nexttask.model.Edit
import cc.sche.apps.nexttask.model.Task
import cc.sche.apps.nexttask.model.TaskChange
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What a [TaskRepository.sync] did, beyond the ordinary transfer of changes. */
data class SyncReport(
    val uploaded: Int = 0,
    /** Offline edits that were combined with a newer version from the server. */
    val mergedEdits: Int = 0,
    /** Offline edits dropped because the task was deleted on the server. */
    val discardedEdits: Int = 0,
    /** Tasks the user deleted that were kept, because another client changed them in the meantime. */
    val restoredDeletions: Int = 0,
) {
    operator fun plus(other: SyncReport) = SyncReport(
        uploaded + other.uploaded,
        mergedEdits + other.mergedEdits,
        discardedEdits + other.discardedEdits,
        restoredDeletions + other.restoredDeletions,
    )
}

class NoTaskListException : Exception("No task list found on this account")

/**
 * The local copy of an account's task lists, usable without a connection.
 *
 * Reads and edits never wait for the network: they act on [snapshot] immediately. [sync] later brings the
 * server and the device together, list by list: it pulls what changed on the server, merges it with edits made
 * meanwhile, and uploads those edits. Uploads are guarded by ETags, so nothing on the server is overwritten
 * blindly. If [sync] fails halfway (no connection), everything done so far is kept and the rest happens on the
 * next call.
 *
 * Persisting [snapshot] is the caller's job: collect it and write each value to storage.
 */
class TaskRepository(
    private val client: CalDavClient,
    initial: AccountSnapshot,
) {
    private val state = MutableStateFlow(initial)
    val snapshot: StateFlow<AccountSnapshot> = state.asStateFlow()

    private val syncLock = Mutex()

    /** Applies the user's new versions of existing tasks to the local copy. Syncing them is up to the caller. */
    fun edit(calendarHref: String, changed: List<Task>) {
        update(calendarHref) { it.applyEdits(changed) }
    }

    /** Creates a task. It exists locally at once and reaches the server on the next sync. */
    fun create(calendarHref: String, task: Task) = apply(calendarHref, Edit(listOf(TaskChange(before = null, after = task))))

    /** Deletes the tasks with these UIDs. Until the next sync they are only hidden, so deleting can be undone. */
    fun delete(calendarHref: String, uids: Collection<String>) {
        val tasks = state.value.calendar(calendarHref)?.tasks ?: return
        val changes = uids.mapNotNull { tasks[it]?.takeUnless { stored -> stored.isDeleted }?.task }
            .map { TaskChange(before = it, after = null) }
        apply(calendarHref, Edit(changes))
    }

    /** Applies (or, for an [Edit.inverse], reverts) something the user did in a list. */
    fun apply(calendarHref: String, edit: Edit) {
        update(calendarHref) { it.applyChanges(edit.changes) }
    }

    /**
     * Syncs with the server. A [full] sync first looks up the account's lists (they may have been added,
     * renamed or removed) and then syncs every one; otherwise only lists with changes waiting are synced, which
     * is what to do right after an edit.
     */
    suspend fun sync(full: Boolean = true): SyncReport = syncLock.withLock {
        var report = SyncReport()
        // Without any list known yet there is nothing to be lightweight about: find them and load them all.
        val everything = full || !state.value.isSynced
        if (everything) report += discoverLists()
        val hrefs = state.value.calendars.filter { everything || it.pendingChanges > 0 }.map { it.href }
        for (href in hrefs) report += syncList(href)
        report
    }

    /** Brings the list of task lists up to date. Edits to a list that no longer exists are lost, and counted. */
    private suspend fun discoverLists(): SyncReport {
        val found = client.discoverTaskCalendars()
        if (found.isEmpty()) throw NoTaskListException()
        var dropped = 0
        state.update { current ->
            val known = current.calendars.associateBy { it.href }
            dropped = current.calendars.filter { it.href !in found.map { f -> f.href } }.sumOf { it.pendingChanges }
            current.copy(
                calendars = found.map { f ->
                    known[f.href]?.copy(name = f.displayName, color = f.color)
                        ?: CalendarSnapshot(f.href, f.displayName, f.color)
                },
            )
        }
        return SyncReport(discardedEdits = dropped)
    }

    private suspend fun syncList(href: String): SyncReport {
        var report = SyncReport()
        repeat(MAX_ROUNDS) {
            report += pull(href)
            val upload = upload(href)
            report += SyncReport(uploaded = upload.uploaded)
            // Another client changed a task between our pull and upload: pull again and merge.
            if (!upload.conflicted) return report
        }
        throw ConflictException(PRECONDITION_FAILED)
    }

    private suspend fun pull(href: String): SyncReport {
        val (result, fullListing) = fetchChanges(href)
        var outcome: PullOutcome? = null
        update(href) { current ->
            current.applyPull(result, fullListing)
                .also { outcome = it }
                .snapshot.copy(syncToken = result.syncToken)
        }
        return SyncReport(
            mergedEdits = outcome?.mergedEdits ?: 0,
            discardedEdits = outcome?.discardedEdits ?: 0,
            restoredDeletions = outcome?.restoredDeletions ?: 0,
        )
    }

    /** The server's changes to a list since the last sync, and whether they are the complete list rather than a delta. */
    private suspend fun fetchChanges(href: String): Pair<SyncResult, Boolean> {
        val token = state.value.calendar(href)?.syncToken
        return try {
            client.sync(href, token) to (token == null)
        } catch (_: CalDavClient.SyncTokenInvalidException) {
            client.sync(href, null) to true
        }
    }

    private class UploadOutcome(val uploaded: Int, val conflicted: Boolean)

    private suspend fun upload(href: String): UploadOutcome {
        update(href) { it.withoutAbandonedTasks() }
        var uploaded = 0
        var conflicted = false
        for (uid in tasksOf(href).filterValues { it.isDirty }.keys) {
            // Look again: the user may have changed or undone things since this sync began.
            val stored = tasksOf(href)[uid]?.takeIf { it.isDirty } ?: continue
            try {
                if (stored.isDeleted) {
                    client.delete(stored.href, stored.etag)
                    update(href) { it.applyDeletedOnServer(uid) }
                } else {
                    val result = client.put(href, stored.task, href = stored.href, etag = stored.etag)
                    update(href) { it.applyUploaded(uid, stored.task, result.etag) }
                }
                uploaded++
            } catch (_: ConflictException) {
                conflicted = true
            }
        }
        return UploadOutcome(uploaded, conflicted)
    }

    private fun tasksOf(href: String): Map<String, StoredTask> = state.value.calendar(href)?.tasks.orEmpty()

    /** Changes one list; does nothing if it has disappeared in the meantime. */
    private fun update(href: String, transform: (CalendarSnapshot) -> CalendarSnapshot) {
        state.update { account ->
            account.copy(calendars = account.calendars.map { if (it.href == href) transform(it) else it })
        }
    }

    private companion object {
        const val MAX_ROUNDS = 3
        const val PRECONDITION_FAILED = 412
    }
}
