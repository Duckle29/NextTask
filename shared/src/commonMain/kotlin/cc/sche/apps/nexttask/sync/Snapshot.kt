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

/** Everything the app knows about one account's task list; this is what is persisted on the device. */
@Serializable
data class AccountSnapshot(
    val serverUrl: String,
    val loginName: String,
    /** Null until the first successful discovery of the task calendar. */
    val calendarHref: String? = null,
    val calendarName: String = "",
    val syncToken: String? = null,
    /** All tasks of the calendar by UID, including deleted ones that the server has not been told about. */
    val tasks: Map<String, StoredTask> = emptyMap(),
    /** Bump when the stored format changes incompatibly; older snapshots are then discarded and re-synced. */
    val schemaVersion: Int = SCHEMA_VERSION,
) {
    /** False until the first sync has completed, i.e. while there is nothing to show yet. */
    val isSynced: Boolean get() = calendarHref != null

    /** Number of tasks with changes (edits, creations, deletions) the server has not received yet. */
    val pendingChanges: Int get() = tasks.values.count { it.isDirty }

    /** The tasks the user sees: everything that is not deleted. */
    val currentTasks: List<Task> get() = tasks.values.filterNot { it.isDeleted }.map { it.task }

    /** True if this snapshot belongs to the account [serverUrl]/[loginName] and can be read by this version. */
    fun belongsTo(serverUrl: String, loginName: String): Boolean =
        this.serverUrl == serverUrl && this.loginName == loginName && schemaVersion == SCHEMA_VERSION

    companion object {
        const val SCHEMA_VERSION = 1
    }
}
