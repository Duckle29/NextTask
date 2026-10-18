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
import cc.sche.apps.nexttask.ical.ICalDateTime
import cc.sche.apps.nexttask.ical.TaskExport
import cc.sche.apps.nexttask.model.Edit
import cc.sche.apps.nexttask.model.EditHistory
import cc.sche.apps.nexttask.model.Task
import cc.sche.apps.nexttask.model.TaskChange
import cc.sche.apps.nexttask.model.TaskCompletion
import cc.sche.apps.nexttask.sync.AccountSnapshot
import cc.sche.apps.nexttask.sync.SyncReport
import cc.sche.apps.nexttask.sync.TaskRepository
import cc.sche.apps.nexttask.tree.CompletedOrder
import cc.sche.apps.nexttask.tree.TaskNode
import cc.sche.apps.nexttask.tree.TaskTree
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestRetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.UnknownHostException
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
     * [active] are the open top-level tasks; [completed] the done top-level tasks, last completed on top.
     * [pendingChanges] counts tasks with edits the server has not received yet.
     * With [accessRevoked] the server no longer accepts the login: the list is the last known state, read-only.
     */
    data class Tasks(
        val calendarName: String,
        val active: List<TaskNode>,
        val completed: List<TaskNode>,
        val accessRevoked: Boolean = false,
        val pendingChanges: Int = 0,
    ) : AppState
}

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

    private val history = EditHistory()

    private val _historyState = MutableStateFlow(HistoryState())
    val historyState: StateFlow<HistoryState> = _historyState.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    /** True while a pull-to-refresh is running; the list stays visible meanwhile. */
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _syncStatus = MutableStateFlow(SyncStatus())
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    private val _message = MutableStateFlow<UiMessage?>(null)
    val message: StateFlow<UiMessage?> = _message.asStateFlow()

    private var loginJob: Job? = null
    private var syncJob: Job? = null
    private var syncAgain = false
    private var lastSyncFinished = NEVER

    /** Parent of all work for the signed-in account, so signing out stops it in one go. */
    private val accountWork = SupervisorJob(viewModelScope.coroutineContext.job)

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
        loginJob = viewModelScope.launch(accountWork) {
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

    fun setCompletedOrder(order: CompletedOrder) {
        preferences.completedOrder = order
        _completedOrder.value = order
        repository?.snapshot?.value?.let(::publish)
    }

    fun dismissMessage() {
        _message.value = null
    }

    /** Ticks or unticks a task. The list updates at once, with or without a connection; the server follows. */
    fun setCompleted(uid: String, completed: Boolean) {
        val repository = repository ?: return
        if (accessRevoked) return
        val now = ICalDateTime.nowUtc()
        val snapshot = repository.snapshot.value
        val tasks = snapshot.currentTasks
        val changed = if (completed) TaskCompletion.complete(tasks, uid, now) else TaskCompletion.reopen(tasks, uid, now)
        if (changed.isEmpty()) return

        applyEdit(repository, Edit(changed.map { TaskChange(before = snapshot.tasks.getValue(it.uid).task, after = it) }))
    }

    /** Adds a task, under [parentUid] if given. It shows up at once, with or without a connection. */
    fun addTask(summary: String, parentUid: String?) {
        val repository = repository ?: return
        if (accessRevoked) return
        val text = summary.trim()
        if (text.isEmpty()) return
        val parent = parentUid?.takeIf { uid -> repository.snapshot.value.currentTasks.any { it.uid == uid } }
        val task = Task.create(Uuid.random().toString(), text, parent, ICalDateTime.nowUtc())
        applyEdit(repository, Edit(listOf(TaskChange(before = null, after = task))))
    }

    /** Deletes a task together with all its subtasks. Undo brings them back. */
    fun deleteTask(uid: String) {
        val repository = repository ?: return
        if (accessRevoked) return
        val snapshot = repository.snapshot.value
        val doomed = TaskTree.subtreeUids(snapshot.currentTasks, uid)
        val changes = doomed.mapNotNull { snapshot.tasks[it]?.takeUnless { stored -> stored.isDeleted }?.task }
            .map { TaskChange(before = it, after = null) }
        if (changes.isEmpty()) return
        applyEdit(repository, Edit(changes))
        val count = changes.size
        _message.value = UiMessage("Deleted $count ${if (count == 1) "task" else "tasks"}", isError = false, undoable = true)
    }

    fun undo() {
        val repository = repository ?: return
        if (accessRevoked) return
        val edit = history.undo() ?: return
        repository.apply(edit.inverse().stamped(ICalDateTime.nowUtc()))
        afterEdit()
    }

    fun redo() {
        val repository = repository ?: return
        if (accessRevoked) return
        val edit = history.redo() ?: return
        repository.apply(edit.stamped(ICalDateTime.nowUtc()))
        afterEdit()
    }

    /** Writes all tasks of the local copy as an iCalendar file to [uri], which the user picked. */
    fun exportTasks(uri: Uri) {
        val snapshot = repository?.snapshot?.value ?: return
        // Not tied to the account: signing out right after must not cancel a half-written file.
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val ics = TaskExport.toIcs(snapshot.currentTasks, snapshot.calendarName)
                    val output = getApplication<Application>().contentResolver.openOutputStream(uri, "wt")
                        ?: throw IOException("Could not open the file")
                    output.use { it.write(ics.encodeToByteArray()) }
                }
                val count = snapshot.tasks.size
                _message.value = UiMessage("Exported $count ${if (count == 1) "task" else "tasks"}", isError = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = UiMessage("Export failed: ${e.message ?: e::class.simpleName}", isError = true)
            }
        }
    }

    /** Does what the user did, remembers it for undo, and starts syncing it. */
    private fun applyEdit(repository: TaskRepository, edit: Edit) {
        history.record(edit)
        repository.apply(edit)
        afterEdit()
    }

    private fun afterEdit() {
        // An "Undo" offered for an earlier edit no longer means what it says.
        if (_message.value?.undoable == true) _message.value = null
        updateHistoryState()
        requestSync(force = true)
    }

    /**
     * Opens the local copy of [credentials]' account, shows it at once, and syncs in the background. A saved
     * copy that belongs to another account (or an older format) is not used.
     */
    private fun startAccount(credentials: Credentials) {
        viewModelScope.launch(accountWork) {
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
            launch { repository.snapshot.collectLatest { snapshot -> withContext(disk) { snapshotStore.save(snapshot) } } }
            requestSync(force = true)
        }
    }

    /**
     * Starts a sync unless one is running (then it runs once more afterwards, so nothing made meanwhile is
     * missed). Without [force], a sync that finished a moment ago counts as recent enough.
     */
    private fun requestSync(force: Boolean, userInitiated: Boolean = false): Job? {
        val repository = repository ?: return null
        if (accessRevoked) return null
        syncJob?.takeIf { it.isActive }?.let { running ->
            if (force) syncAgain = true
            return running
        }
        if (!force && SystemClock.elapsedRealtime() - lastSyncFinished < RECENT_SYNC_MS) return null
        val job = viewModelScope.launch(accountWork) {
            do {
                syncAgain = false
                runSync(repository, userInitiated)
            } while (syncAgain)
        }
        syncJob = job
        return job
    }

    private suspend fun runSync(repository: TaskRepository, userInitiated: Boolean) {
        val hadData = repository.snapshot.value.isSynced
        if (!hadData) _state.value = AppState.Loading
        _syncStatus.value = _syncStatus.value.copy(syncing = true)
        try {
            val report = repository.sync()
            lastSyncFinished = SystemClock.elapsedRealtime()
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
        val (completed, active) = TaskTree.build(snapshot.currentTasks, _completedOrder.value).partition { it.task.isCompleted }
        _state.value = AppState.Tasks(snapshot.calendarName, active, completed, accessRevoked, snapshot.pendingChanges)
    }

    private fun updateHistoryState() {
        _historyState.value = HistoryState(history.canUndo && !accessRevoked, history.canRedo && !accessRevoked)
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
        accountWork.cancelChildren()
        credentialStore.clear()
        if (deleteLocalCopy) viewModelScope.launch(disk) { snapshotStore.clear() }
        credentials = null
        repository = null
        accessRevoked = false
        syncJob = null
        syncAgain = false
        lastSyncFinished = NEVER
        history.clear()
        updateHistoryState()
        _syncStatus.value = SyncStatus()
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
    }
}
