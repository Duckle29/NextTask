package cc.sche.apps.nexttask.sync

import cc.sche.apps.nexttask.caldav.CalDavClient
import cc.sche.apps.nexttask.caldav.ConflictException
import cc.sche.apps.nexttask.caldav.DavException
import cc.sche.apps.nexttask.caldav.SyncResult
import cc.sche.apps.nexttask.caldav.TaskCalendar
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
 * The local copy of an account's tasks, usable without a connection.
 *
 * Reads and [edit]s never wait for the network: they act on [snapshot] immediately. [sync] later
 * brings the server and the device together: it pulls what changed on the server, merges it with edits
 * made meanwhile, and uploads those edits. Uploads are guarded by ETags, so nothing on the server is
 * overwritten blindly. If [sync] fails halfway (no connection), everything done so far is kept and the
 * rest happens on the next call.
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
    fun edit(changed: List<Task>) {
        state.update { it.applyEdits(changed) }
    }

    /** Creates a task. It exists locally at once and reaches the server on the next sync. */
    fun create(task: Task) = apply(Edit(listOf(TaskChange(before = null, after = task))))

    /** Deletes the tasks with these UIDs. Until the next sync they are only hidden, so deleting can be undone. */
    fun delete(uids: Collection<String>) {
        val tasks = state.value.tasks
        apply(Edit(uids.mapNotNull { tasks[it]?.takeUnless { stored -> stored.isDeleted }?.task }.map { TaskChange(before = it, after = null) }))
    }

    /** Applies (or, for an [Edit.inverse], reverts) something the user did. */
    fun apply(edit: Edit) {
        state.update { it.applyChanges(edit.changes) }
    }

    suspend fun sync(): SyncReport = syncLock.withLock {
        var report = SyncReport()
        repeat(MAX_ROUNDS) {
            report += pull()
            val upload = upload()
            report += SyncReport(uploaded = upload.uploaded)
            // Another client changed a task between our pull and upload: pull again and merge.
            if (!upload.conflicted) return report
        }
        throw ConflictException(PRECONDITION_FAILED)
    }

    private suspend fun pull(): SyncReport {
        val (result, fullListing) = fetchChanges()
        var outcome: PullOutcome? = null
        state.update { current ->
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

    /** The server's changes since the last sync, and whether they are the complete list rather than a delta. */
    private suspend fun fetchChanges(): Pair<SyncResult, Boolean> {
        val current = state.value
        val href = current.calendarHref ?: return fromNewCalendar()
        return try {
            try {
                client.sync(href, current.syncToken) to (current.syncToken == null)
            } catch (_: CalDavClient.SyncTokenInvalidException) {
                client.sync(href, null) to true
            }
        } catch (e: DavException) {
            // The list was deleted or moved: look for the account's task list again.
            if (e.status == NOT_FOUND) fromNewCalendar() else throw e
        }
    }

    private suspend fun fromNewCalendar(): Pair<SyncResult, Boolean> {
        val calendar: TaskCalendar = client.discoverTaskCalendars().firstOrNull() ?: throw NoTaskListException()
        state.update { it.copy(calendarHref = calendar.href, calendarName = calendar.displayName, syncToken = null) }
        return client.sync(calendar.href, null) to true
    }

    private class UploadOutcome(val uploaded: Int, val conflicted: Boolean)

    private suspend fun upload(): UploadOutcome {
        state.update { it.withoutAbandonedTasks() }
        val calendarHref = state.value.calendarHref ?: return UploadOutcome(0, false)
        var uploaded = 0
        var conflicted = false
        for (uid in state.value.tasks.filterValues { it.isDirty }.keys) {
            // Look again: the user may have changed or undone things since this sync began.
            val stored = state.value.tasks[uid]?.takeIf { it.isDirty } ?: continue
            try {
                if (stored.isDeleted) {
                    client.delete(stored.href, stored.etag)
                    state.update { it.applyDeletedOnServer(uid) }
                } else {
                    val result = client.put(calendarHref, stored.task, href = stored.href, etag = stored.etag)
                    state.update { it.applyUploaded(uid, stored.task, result.etag) }
                }
                uploaded++
            } catch (_: ConflictException) {
                conflicted = true
            }
        }
        return UploadOutcome(uploaded, conflicted)
    }

    private companion object {
        const val MAX_ROUNDS = 3
        const val NOT_FOUND = 404
        const val PRECONDITION_FAILED = 412
    }
}
