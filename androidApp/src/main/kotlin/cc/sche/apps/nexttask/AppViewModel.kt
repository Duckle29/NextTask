package cc.sche.apps.nexttask

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cc.sche.apps.nexttask.auth.Credentials
import cc.sche.apps.nexttask.auth.LoginFlowV2
import cc.sche.apps.nexttask.auth.RedirectsToHttpsException
import cc.sche.apps.nexttask.auth.normalizeServerUrl
import cc.sche.apps.nexttask.auth.revokeAppPassword
import cc.sche.apps.nexttask.caldav.CalDavClient
import cc.sche.apps.nexttask.caldav.UnauthorizedException
import cc.sche.apps.nexttask.ical.ICalDate
import cc.sche.apps.nexttask.ical.ICalDateTime
import cc.sche.apps.nexttask.ical.TaskExport
import cc.sche.apps.nexttask.model.Edit
import cc.sche.apps.nexttask.model.EditHistory
import cc.sche.apps.nexttask.model.Task
import cc.sche.apps.nexttask.model.TaskChange
import cc.sche.apps.nexttask.model.TaskCompletion
import cc.sche.apps.nexttask.model.withLastModified
import cc.sche.apps.nexttask.sync.AccountSnapshot
import cc.sche.apps.nexttask.sync.CalendarHomeUnknownException
import cc.sche.apps.nexttask.sync.CalendarSnapshot
import cc.sche.apps.nexttask.sync.SyncReport
import cc.sche.apps.nexttask.sync.TaskRepository
import cc.sche.apps.nexttask.tree.CompletedOrder
import cc.sche.apps.nexttask.tree.TaskHierarchy
import cc.sche.apps.nexttask.tree.TaskNode
import cc.sche.apps.nexttask.tree.TaskTree
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestRetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.UnknownHostException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

data class HistoryState(val canUndo: Boolean = false, val canRedo: Boolean = false)

/** Progress of syncing with the server. [failure] is why the last attempt failed, until one succeeds. */
data class SyncStatus(val syncing: Boolean = false, val failure: String? = null)

/** A line shown above the content. With [undoable] it offers to undo the last edit it reports. */
data class UiMessage(val text: String, val isError: Boolean, val undoable: Boolean = false)

sealed interface AppState {
    data object LoggedOut : AppState
    data object Loading : AppState
    /** Signed in, but there is no local copy yet and the first fetch failed (offline, server down). */
    data object LoadFailed : AppState
    /** The plain HTTP address the user entered redirects to HTTPS; ask before signing in there instead. */
    data class ConfirmHttps(val httpsServerUrl: String) : AppState
    /** The user has been sent to the browser and we are waiting for them to approve. */
    data class WaitingForBrowser(val loginUrl: String) : AppState
    /**
     * The tasks of the [selectedHref] list out of all the account's [lists]: [active] are its open top-level
     * tasks and [completed] the done top-level tasks, last completed on top.
     * [pendingChanges] counts tasks (in all lists) with edits the server has not received yet.
     * With [accessRevoked] the server no longer accepts the login: the lists are the last known state, read-only.
     */
    data class Tasks(
        val lists: List<TaskList>,
        val selectedHref: String,
        val active: List<TaskNode>,
        val completed: List<TaskNode>,
        val accessRevoked: Boolean = false,
        val pendingChanges: Int = 0,
    ) : AppState {
        val calendarName: String get() = lists.firstOrNull { it.href == selectedHref }?.name.orEmpty()
    }
}

/**
 * A task list of the account, as shown in the side drawer. With [acceptsEvents] it is also an event calendar;
 * while [isNew] it has been created here and the server does not have it yet.
 */
data class TaskList(
    val href: String,
    val name: String,
    val color: String?,
    val taskCount: Int,
    val pendingChanges: Int,
    val acceptsEvents: Boolean,
    val isNew: Boolean,
)

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val credentialStore = CredentialStore(application)
    private val snapshotStore = SnapshotStore(application)
    private val http = HttpClient(OkHttp) {
        // Every request URL is built from the account's server address. A redirect could only move
        // requests (and the credentials sent with them) somewhere the user did not choose.
        followRedirects = false
        // The app is often resumed before the network is usable again; a failed lookup means the
        // request was never sent, so retrying is safe for every method.
        install(HttpRequestRetry) {
            retryOnExceptionIf(maxRetries = 3) { _, cause -> generateSequence(cause) { it.cause }.any { it is UnknownHostException } }
            exponentialDelay()
        }
    }

    private val _state = MutableStateFlow<AppState>(AppState.Loading)
    val state: StateFlow<AppState> = _state.asStateFlow()

    private val preferences = PreferencesStore(application)

    private val _completedOrder = MutableStateFlow(preferences.completedOrder)
    val completedOrder: StateFlow<CompletedOrder> = _completedOrder.asStateFlow()

    private val reminders = ReminderScheduler(application)

    private val _remindersEnabled = MutableStateFlow(preferences.remindersEnabled)
    val remindersEnabled: StateFlow<Boolean> = _remindersEnabled.asStateFlow()

    /** Undo and redo belong to the list they were made in. */
    private val histories = mutableMapOf<String, EditHistory>()
    private var selectedHref: String? = preferences.selectedList

    private val _historyState = MutableStateFlow(HistoryState())
    val historyState: StateFlow<HistoryState> = _historyState.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    /** True while a pull-to-refresh is running; the list stays visible meanwhile. */
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _syncStatus = MutableStateFlow(SyncStatus())
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    private val _message = MutableStateFlow<UiMessage?>(null)
    val message: StateFlow<UiMessage?> = _message.asStateFlow()

    private val _saveRetryIn = MutableStateFlow<Int?>(null)
    /** Seconds until saving the local copy is tried again, after it failed; null while saving works. */
    val saveRetryIn: StateFlow<Int?> = _saveRetryIn.asStateFlow()

    private var loginJob: Job? = null
    private var syncJob: Job? = null
    private var syncAgain = false
    private var syncAgainFull = false
    private var lastSyncFinished = NEVER

    /**
     * All work for the signed-in account runs here, so signing out stops it in one go. A child of
     * [viewModelScope], so it also ends with the ViewModel; one failed job leaves the others running.
     */
    private val accountScope = CoroutineScope(viewModelScope.coroutineContext + SupervisorJob(viewModelScope.coroutineContext.job))

    /** Disk writes happen one at a time and in order, so a late save can never undo a sign-out's cleanup. */
    private val disk = Dispatchers.IO.limitedParallelism(1)

    private var credentials: Credentials? = null
    private var repository: TaskRepository? = null
    private var accessRevoked = false

    private val connectivity = application.getSystemService(ConnectivityManager::class.java)
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        // Back online: if the last sync failed (or edits are waiting), try again right away.
        override fun onAvailable(network: Network) {
            viewModelScope.launch { if (_syncStatus.value.failure != null) requestSync(force = true) }
        }
    }

    init {
        connectivity.registerDefaultNetworkCallback(networkCallback)
        credentials = credentialStore.load()
        credentials?.let(::startAccount) ?: run { _state.value = AppState.LoggedOut }
    }

    /** Starts the browser login; the UI opens [AppState.WaitingForBrowser.loginUrl]. */
    fun signIn(serverInput: String) {
        val serverUrl = normalizeServerUrl(serverInput)
        if (serverUrl == null) {
            _message.value = UiMessage("Enter a valid server address", isError = true)
            return
        }
        loginJob?.cancel()
        loginJob = accountScope.launch {
            _state.value = AppState.Loading
            try {
                val flow = LoginFlowV2(http, userAgent = "NextTask Android")
                val session = try {
                    flow.start(serverUrl)
                } catch (e: RedirectsToHttpsException) {
                    _state.value = AppState.ConfirmHttps(e.httpsServerUrl)
                    return@launch
                }
                _state.value = AppState.WaitingForBrowser(session.loginUrl)
                val approved = flow.awaitCredentials(session)
                credentialStore.save(approved)
                credentials = approved
                startAccount(approved)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = UiMessage(e.message ?: e::class.simpleName.orEmpty(), isError = true)
                _state.value = AppState.LoggedOut
            }
        }
    }

    fun cancelSignIn() {
        loginJob?.cancel()
        _state.value = AppState.LoggedOut
    }

    /** Pull-to-refresh and the retry button: sync now, whatever happened recently. */
    fun refresh() {
        val job = requestSync(force = true, userInitiated = true) ?: return
        viewModelScope.launch {
            _refreshing.value = true
            try {
                job.join()
            } finally {
                _refreshing.value = false
            }
        }
    }

    /** The app came to the foreground: catch up with the server, unless that was done just now. */
    fun onForeground() {
        requestSync(force = false)
    }

    fun signOut() {
        val signedOut = credentials
        forgetAccount(deleteLocalCopy = true)
        if (signedOut == null) return
        // Best effort: if this fails the app password stays valid on the server, so tell the user.
        viewModelScope.launch {
            try {
                revokeAppPassword(http, signedOut)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _message.value = UiMessage(
                    "Signed out, but the app password could not be revoked. Remove it in Nextcloud under Settings → Security.",
                    isError = true,
                )
            }
        }
    }

    /**
     * Leaves the read-only list shown after a revoked login for the sign-in screen. The local copy stays, so
     * signing in again as the same account still uploads the edits that never reached the server.
     */
    fun signInAgain() {
        forgetAccount(deleteLocalCopy = false)
    }

    /** Shows another of the account's task lists. */
    fun selectList(href: String) {
        selectedHref = href
        preferences.selectedList = href
        repository?.snapshot?.value?.let(::publish)
    }

    /** Creates a task list and shows it. Like a task, it is there at once, with or without a connection; the server follows. */
    fun createList(name: String) {
        val repository = repository?.takeUnless { accessRevoked } ?: return
        val text = name.trim()
        if (text.isEmpty()) return
        try {
            selectList(repository.createList(text))
        } catch (e: CalendarHomeUnknownException) {
            _message.value = UiMessage("Couldn't create “$text”: ${e.message}", isError = true)
            return
        }
        requestSync(force = true, full = false)
    }

    /**
     * Deletes a task list with its tasks. One the server has is deleted there right away, which needs a
     * connection; one not synced yet is only on this device and goes at once.
     */
    fun deleteList(href: String) {
        val repository = repository?.takeUnless { accessRevoked } ?: return
        val name = repository.snapshot.value.calendar(href)?.name ?: return
        accountScope.launch {
            try {
                repository.deleteList(href)
                histories.remove(href)
                _message.value = UiMessage("Deleted “$name”", isError = false)
            } catch (e: CancellationException) {
                throw e
            } catch (_: UnauthorizedException) {
                onAccessRevoked()
            } catch (e: Exception) {
                _message.value = UiMessage("Couldn't delete “$name”: ${describe(e)}", isError = true)
            }
        }
    }

    fun setCompletedOrder(order: CompletedOrder) {
        preferences.completedOrder = order
        _completedOrder.value = order
        repository?.snapshot?.value?.let(::publish)
    }

    /** Shows task reminders as notifications on this device, or stops doing so. */
    fun setRemindersEnabled(enabled: Boolean) {
        _remindersEnabled.value = enabled
        val snapshot = repository?.snapshot?.value
        viewModelScope.launch(disk) { reminders.setEnabled(enabled, snapshot) }
    }

    fun dismissMessage() {
        _message.value = null
    }

    /** Ticks or unticks a task. The list updates at once, with or without a connection; the server follows. */
    fun setCompleted(uid: String, completed: Boolean) {
        val (repository, list) = editable() ?: return
        val now = ICalDateTime.nowUtc()
        val tasks = list.currentTasks
        val changed = if (completed) TaskCompletion.complete(tasks, uid, now) else TaskCompletion.reopen(tasks, uid, now)
        if (changed.isEmpty()) return

        applyEdit(repository, list.href, Edit(changed.map { TaskChange(before = list.tasks.getValue(it.uid).task, after = it) }))
    }

    /** Adds a task to the selected list, under [parentUid] if given. It shows up at once, with or without a connection. */
    fun addTask(summary: String, parentUid: String?) {
        val (repository, list) = editable() ?: return
        val text = summary.trim()
        if (text.isEmpty()) return
        val parent = parentUid?.takeIf { uid -> list.currentTasks.any { it.uid == uid } }
        val task = Task.create(Uuid.random().toString(), text, parent, ICalDateTime.nowUtc())
        applyEdit(repository, list.href, Edit(listOf(TaskChange(before = null, after = task))))
    }

    /** Sets when a task is due, or with null removes its due date. Undo brings the previous one back. */
    fun setDue(uid: String, due: ICalDate?) {
        val (repository, list) = editable() ?: return
        val task = list.tasks[uid]?.takeUnless { it.isDeleted }?.task ?: return
        if (task.due == due) return
        applyEdit(repository, list.href, Edit(listOf(TaskChange(before = task, after = task.copy(due = due).withLastModified(ICalDateTime.nowUtc())))))
    }

    /**
     * Moves an open task, with its subtasks, under [parentUid] (null for top level) right after the sibling
     * [afterUid] (null to come first). Undo puts it back.
     */
    fun moveTask(uid: String, parentUid: String?, afterUid: String?) {
        val (repository, list) = editable() ?: return
        val active = TaskTree.build(list.currentTasks, _completedOrder.value).filterNot { it.task.isCompleted }
        val changed = TaskHierarchy.move(active, uid, parentUid, afterUid)
        if (changed.isEmpty()) return
        val now = ICalDateTime.nowUtc()
        applyEdit(repository, list.href, Edit(changed.map { TaskChange(before = list.tasks.getValue(it.uid).task, after = it.withLastModified(now)) }))
    }

    /** Deletes a task together with all its subtasks. Undo brings them back. */
    fun deleteTask(uid: String) {
        val (repository, list) = editable() ?: return
        val doomed = TaskTree.subtreeUids(list.currentTasks, uid)
        val changes = doomed.mapNotNull { list.tasks[it]?.takeUnless { stored -> stored.isDeleted }?.task }
            .map { TaskChange(before = it, after = null) }
        if (changes.isEmpty()) return
        applyEdit(repository, list.href, Edit(changes))
        val count = changes.size
        _message.value = UiMessage("Deleted $count ${if (count == 1) "task" else "tasks"}", isError = false, undoable = true)
    }

    fun undo() {
        val (repository, list) = editable() ?: return
        val edit = history(list.href).undo() ?: return
        repository.apply(list.href, edit.inverse().stamped(ICalDateTime.nowUtc()))
        afterEdit()
    }

    fun redo() {
        val (repository, list) = editable() ?: return
        val edit = history(list.href).redo() ?: return
        repository.apply(list.href, edit.stamped(ICalDateTime.nowUtc()))
        afterEdit()
    }

    /** The repository and the selected list, unless there is nothing to edit (not loaded yet, or read-only). */
    private fun editable(): Pair<TaskRepository, CalendarSnapshot>? {
        if (accessRevoked) return null
        val repository = repository ?: return null
        val list = selected(repository.snapshot.value) ?: return null
        return repository to list
    }

    private fun selected(snapshot: AccountSnapshot): CalendarSnapshot? =
        snapshot.calendar(selectedHref) ?: snapshot.calendars.firstOrNull()

    private fun history(href: String) = histories.getOrPut(href) { EditHistory() }

    /** Writes all tasks of the local copy as an iCalendar file to [uri], which the user picked. */
    fun exportTasks(uri: Uri) {
        val list = repository?.snapshot?.value?.let(::selected) ?: return
        // Not tied to the account: signing out right after must not cancel a half-written file.
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val ics = TaskExport.toIcs(list.currentTasks, list.name)
                    val output = getApplication<Application>().contentResolver.openOutputStream(uri, "wt")
                        ?: throw IOException("Could not open the file")
                    output.use { it.write(ics.encodeToByteArray()) }
                }
                val count = list.currentTasks.size
                _message.value = UiMessage("Exported $count ${if (count == 1) "task" else "tasks"}", isError = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = UiMessage("Export failed: ${e.message ?: e::class.simpleName}", isError = true)
            }
        }
    }

    /** Does what the user did, remembers it for undo, and starts syncing it. */
    private fun applyEdit(repository: TaskRepository, href: String, edit: Edit) {
        history(href).record(edit)
        repository.apply(href, edit)
        afterEdit()
    }

    private fun afterEdit() {
        // An "Undo" offered for an earlier edit no longer means what it says.
        if (_message.value?.undoable == true) _message.value = null
        updateHistoryState()
        // Right after an edit only the lists with changes waiting need talking to the server.
        requestSync(force = true, full = false)
    }

    /**
     * Opens the local copy of [credentials]' account, shows it at once, and syncs in the background. A saved
     * copy that belongs to another account (or an older format) is not used.
     */
    private fun startAccount(credentials: Credentials) {
        accountScope.launch {
            val saved = withContext(disk) { snapshotStore.load() }
                ?.takeIf { it.belongsTo(credentials.serverUrl, credentials.loginName) }
            val repository = TaskRepository(
                CalDavClient(http, credentials),
                saved ?: AccountSnapshot(credentials.serverUrl, credentials.loginName),
            )
            this@AppViewModel.repository = repository
            accessRevoked = false
            _syncStatus.value = SyncStatus()

            launch { repository.snapshot.collect(::publish) }
            launch { keepSaved(repository) }
            requestSync(force = true)
        }
    }

    /**
     * Writes every new version of the local copy to disk and updates the reminders from it. A failed save (e.g.
     * the storage is full) is retried, waiting longer each time; a newer version replaces the one being retried.
     */
    private suspend fun keepSaved(repository: TaskRepository) {
        var failures = 0
        repository.snapshot.collectLatest { snapshot ->
            withContext(disk) { reminders.update(snapshot) }
            while (true) {
                try {
                    withContext(disk) { snapshotStore.save(snapshot) }
                    failures = 0
                    _saveRetryIn.value = null
                    return@collectLatest
                } catch (_: IOException) {
                    countDownToSaveRetry(saveRetryDelay(failures++))
                }
            }
        }
    }

    private suspend fun countDownToSaveRetry(wait: Duration) {
        for (seconds in wait.inWholeSeconds downTo 1) {
            _saveRetryIn.value = seconds.toInt()
            delay(1.seconds)
        }
    }

    /**
     * Starts a sync unless one is running (then it runs once more afterwards, so nothing made meanwhile is
     * missed). A [full] sync also looks for added, renamed and removed lists and catches up on all of them;
     * otherwise only lists with changes waiting are synced. Without [force], a full sync that finished a
     * moment ago counts as recent enough.
     */
    private fun requestSync(force: Boolean, userInitiated: Boolean = false, full: Boolean = true): Job? {
        val repository = repository ?: return null
        if (accessRevoked) return null
        syncJob?.takeIf { it.isActive }?.let { running ->
            if (force) {
                syncAgain = true
                if (full) syncAgainFull = true
            }
            return running
        }
        if (!force && SystemClock.elapsedRealtime() - lastSyncFinished < RECENT_SYNC_MS) return null
        val job = accountScope.launch {
            var nextFull = full
            do {
                syncAgain = false
                syncAgainFull = false
                runSync(repository, userInitiated, nextFull)
                nextFull = syncAgainFull
            } while (syncAgain)
        }
        syncJob = job
        return job
    }

    private suspend fun runSync(repository: TaskRepository, userInitiated: Boolean, full: Boolean) {
        val hadData = repository.snapshot.value.isSynced
        if (!hadData) _state.value = AppState.Loading
        _syncStatus.value = _syncStatus.value.copy(syncing = true)
        try {
            val report = repository.sync(full)
            if (full) lastSyncFinished = SystemClock.elapsedRealtime()
            _syncStatus.value = SyncStatus()
            describe(report)?.let { _message.value = UiMessage(it, isError = false) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: UnauthorizedException) {
            onAccessRevoked()
        } catch (e: Exception) {
            val reason = describe(e)
            _syncStatus.value = SyncStatus(failure = reason)
            // With a list on screen, going offline is normal and the status line says so; otherwise
            // there is nothing to show, and a refresh the user asked for deserves an answer.
            if (!hadData) _state.value = AppState.LoadFailed
            if (userInitiated || !hadData) _message.value = UiMessage(reason, isError = true)
        }
    }

    private fun publish(snapshot: AccountSnapshot) {
        updateHistoryState()
        // Until the first sync has finished there is nothing to show; the spinner or error screen stays.
        if (!snapshot.isSynced) return
        val list = selected(snapshot) ?: return
        selectedHref = list.href
        val (completed, active) = TaskTree.build(list.currentTasks, _completedOrder.value).partition { it.task.isCompleted }
        val lists = snapshot.calendars.map {
            TaskList(it.href, it.name, it.color, it.currentTasks.size, it.pendingChanges, it.acceptsEvents, it.isNew)
        }
        _state.value = AppState.Tasks(lists, list.href, active, completed, accessRevoked, snapshot.pendingChanges)
    }

    private fun updateHistoryState() {
        val history = selectedHref?.let { histories[it] }
        _historyState.value = HistoryState(history?.canUndo == true && !accessRevoked, history?.canRedo == true && !accessRevoked)
    }

    /**
     * The server no longer accepts the app password. A task list on screen stays, read-only, so the user
     * can copy or export what they still need before signing in again; without one there is nothing to keep.
     */
    private fun onAccessRevoked() {
        if (_state.value !is AppState.Tasks) {
            forgetAccount(deleteLocalCopy = false)
            _message.value = UiMessage("Signed out: the server no longer accepts this device's login.", isError = true)
            return
        }
        credentialStore.clear()
        credentials = null
        accessRevoked = true
        _syncStatus.value = SyncStatus()
        repository?.snapshot?.value?.let(::publish)
    }

    /** Stops all work for the signed-in account, drops its credentials and shows the sign-in screen. */
    private fun forgetAccount(deleteLocalCopy: Boolean) {
        accountScope.coroutineContext.cancelChildren()
        credentialStore.clear()
        viewModelScope.launch(disk) {
            if (deleteLocalCopy) snapshotStore.clear()
            reminders.clear()
        }
        credentials = null
        repository = null
        accessRevoked = false
        syncJob = null
        syncAgain = false
        syncAgainFull = false
        lastSyncFinished = NEVER
        histories.clear()
        updateHistoryState()
        _syncStatus.value = SyncStatus()
        _saveRetryIn.value = null
        _state.value = AppState.LoggedOut
    }

    /** What a sync did that the user should hear about, or null if it was just a transfer. */
    private fun describe(report: SyncReport): String? {
        val notes = buildList {
            if (report.mergedEdits > 0) {
                add("${report.mergedEdits} offline ${if (report.mergedEdits == 1) "change was" else "changes were"} merged with newer changes from the server")
            }
            if (report.restoredDeletions > 0) {
                add("${report.restoredDeletions} deleted ${if (report.restoredDeletions == 1) "task was" else "tasks were"} kept because ${if (report.restoredDeletions == 1) "it was" else "they were"} changed on the server")
            }
            for (renamed in report.renamedLists) {
                add("A list named “${renamed.from}” already existed on the server, so the new one is called “${renamed.to}”")
            }
            if (report.discardedEdits > 0) {
                add("${report.discardedEdits} offline ${if (report.discardedEdits == 1) "change was" else "changes were"} dropped because the task was deleted on the server")
            }
        }
        return notes.joinToString(". ").ifEmpty { null }
    }

    private fun describe(e: Exception): String =
        if (generateSequence<Throwable>(e) { it.cause }.any { it is IOException }) {
            "No connection to the server"
        } else {
            e.message ?: e::class.simpleName.orEmpty()
        }

    override fun onCleared() {
        connectivity.unregisterNetworkCallback(networkCallback)
        http.close()
    }

    private companion object {
        const val NEVER = Long.MIN_VALUE / 2
        const val RECENT_SYNC_MS = 30_000L
        val SAVE_RETRY_FIRST = 2.seconds
        val SAVE_RETRY_MAX = 1.minutes

        /** Doubles after each failed save, from [SAVE_RETRY_FIRST] up to [SAVE_RETRY_MAX]. */
        fun saveRetryDelay(failures: Int): Duration =
            (SAVE_RETRY_FIRST * (1 shl failures.coerceAtMost(10))).coerceAtMost(SAVE_RETRY_MAX)
    }
}
