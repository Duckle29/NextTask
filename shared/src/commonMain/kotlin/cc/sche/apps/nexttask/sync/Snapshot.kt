package cc.sche.apps.nexttask.sync

import cc.sche.apps.nexttask.model.Task
import kotlinx.serialization.Serializable

/**
 * A task as kept on the device. [href] and [etag] identify the server copy; [task] is the current local
 * state, which is what the user sees.
 *
 * What the server has not been told yet is recorded here, so it can be sent later:
 * - [base] is the server copy that local edits started from (null while there are no edits). It is what
 *   lets a later sync tell the user's changes from other clients'.
 * - [isNew] marks a task created on this device that the server does not have yet. It has no [etag].
 * - [isDeleted] marks a task the user deleted. It stays as a hidden tombstone until the server has been
 *   told, and can still be restored until then.
 */
@Serializable
data class StoredTask(
    val href: String,
    /** Null for a new task, and when the server did not report one after an upload; the next pull fills it in. */
    val etag: String?,
    val task: Task,
    val base: Task? = null,
    val isNew: Boolean = false,
    val isDeleted: Boolean = false,
) {
    /**
     * True if the server has something to learn about this task. A task that was created and deleted
     * before ever reaching the server has nothing to tell.
     */
    val isDirty: Boolean get() = if (isDeleted) !isNew else isNew || base != null
}

/** Everything the app knows about one task list of the account. */
@Serializable
data class CalendarSnapshot(
    /** Where the list lives on the server; identifies it. */
    val href: String,
    val name: String,
    /** The list's colour as the server reports it (e.g. `#0082c9`), if any. */
    val color: String? = null,
    val syncToken: String? = null,
    /** All tasks of the list by UID, including deleted ones that the server has not been told about. */
    val tasks: Map<String, StoredTask> = emptyMap(),
) {
    /** Number of tasks with changes (edits, creations, deletions) the server has not received yet. */
    val pendingChanges: Int get() = tasks.values.count { it.isDirty }

    /** The tasks the user sees: everything that is not deleted. */
    val currentTasks: List<Task> get() = tasks.values.filterNot { it.isDeleted }.map { it.task }
}

/** Everything the app knows about one account: its task lists with their tasks. This is what is persisted. */
@Serializable
data class AccountSnapshot(
    val serverUrl: String,
    val loginName: String,
    /** In the order the server lists them. Empty until the first sync has found them. */
    val calendars: List<CalendarSnapshot> = emptyList(),
    /** Bump when the stored format changes incompatibly; older snapshots are then discarded and re-synced. */
    val schemaVersion: Int = SCHEMA_VERSION,
) {
    /** False until the first sync has completed, i.e. while there is nothing to show yet. */
    val isSynced: Boolean get() = calendars.isNotEmpty()

    /** Number of changes over all lists that the server has not received yet. */
    val pendingChanges: Int get() = calendars.sumOf { it.pendingChanges }

    fun calendar(href: String?): CalendarSnapshot? = calendars.firstOrNull { it.href == href }

    /** True if this snapshot belongs to the account [serverUrl]/[loginName] and can be read by this version. */
    fun belongsTo(serverUrl: String, loginName: String): Boolean =
        this.serverUrl == serverUrl && this.loginName == loginName && schemaVersion == SCHEMA_VERSION

    companion object {
        const val SCHEMA_VERSION = 2
    }
}
