package cc.sche.apps.nexttask.sync

import cc.sche.apps.nexttask.model.Task
import kotlinx.serialization.Serializable

/**
 * A task as kept on the device. [href] and [etag] identify the server copy; [task] is the current local
 * state, which is what the user sees.
 *
 * [base] is the server copy that the local edits started from. It is null while the task has no
 * unsynced edits, and it is what lets a later sync tell the user's changes from the server's.
 *
 * Only existing tasks can be edited so far. Creating or deleting tasks offline will extend this class.
 */
@Serializable
data class StoredTask(
    val href: String,
    /** Null when the server did not report one after an upload; the next pull fills it in. */
    val etag: String?,
    val task: Task,
    val base: Task? = null,
) {
    val isDirty: Boolean get() = base != null
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
    /** All tasks of the calendar, by UID. */
    val tasks: Map<String, StoredTask> = emptyMap(),
    /** Bump when the stored format changes incompatibly; older snapshots are then discarded and re-synced. */
    val schemaVersion: Int = SCHEMA_VERSION,
) {
    /** False until the first sync has completed, i.e. while there is nothing to show yet. */
    val isSynced: Boolean get() = calendarHref != null

    /** Number of tasks with edits the server has not received yet. */
    val pendingChanges: Int get() = tasks.values.count { it.isDirty }

    val currentTasks: List<Task> get() = tasks.values.map { it.task }

    /** True if this snapshot belongs to the account [serverUrl]/[loginName] and can be read by this version. */
    fun belongsTo(serverUrl: String, loginName: String): Boolean =
        this.serverUrl == serverUrl && this.loginName == loginName && schemaVersion == SCHEMA_VERSION

    companion object {
        const val SCHEMA_VERSION = 1
    }
}
