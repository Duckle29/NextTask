package cc.mikkel.nexttask

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cc.mikkel.nexttask.auth.Credentials
import cc.mikkel.nexttask.auth.LoginFlowV2
import cc.mikkel.nexttask.auth.normalizeServerUrl
import cc.mikkel.nexttask.caldav.CalDavClient
import cc.mikkel.nexttask.caldav.RemoteTask
import cc.mikkel.nexttask.caldav.UnauthorizedException
import cc.mikkel.nexttask.ical.ICalDateTime
import cc.mikkel.nexttask.model.Edit
import cc.mikkel.nexttask.model.EditHistory
import cc.mikkel.nexttask.model.Task
import cc.mikkel.nexttask.model.TaskCompletion
import cc.mikkel.nexttask.model.withLastModified
import cc.mikkel.nexttask.tree.CompletedOrder
import cc.mikkel.nexttask.tree.TaskNode
import cc.mikkel.nexttask.tree.TaskTree
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestRetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.UnknownHostException

data class HistoryState(val canUndo: Boolean = false, val canRedo: Boolean = false)

sealed interface AppState {
    data object LoggedOut : AppState
    data object Loading : AppState
    /** Signed in, but the task list could not be fetched (offline, server down). */
    data object LoadFailed : AppState
    /** The user has been sent to the browser and we are waiting for them to approve. */
    data class WaitingForBrowser(val loginUrl: String) : AppState
    /** [active] are the open top-level tasks; [completed] the done top-level tasks, last completed on top. */
    data class Tasks(
        val calendarName: String,
        val active: List<TaskNode>,
        val completed: List<TaskNode>,
    ) : AppState
}

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val store = CredentialStore(application)
    private val http = HttpClient(OkHttp) {
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

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var job: Job? = null

    /** Serialises server round trips so ETags are never stale between consecutive writes. */
    private val serverLock = Mutex()

    private var client: CalDavClient? = null
    private var calendarName = ""
    private var calendarHref = ""
    private var items: Map<String, RemoteTask> = emptyMap()

    init {
        val saved = store.load()
        if (saved == null) _state.value = AppState.LoggedOut else load(saved)
    }

    fun signIn(serverInput: String, openBrowser: (String) -> Unit) {
        val serverUrl = normalizeServerUrl(serverInput)
        if (serverUrl == null) {
            _error.value = "Enter a valid server address"
            return
        }
        launchGuarded(fallback = AppState.LoggedOut) {
            _state.value = AppState.Loading
            val flow = LoginFlowV2(http, userAgent = "NextTask Android")
            val session = flow.start(serverUrl)
            _state.value = AppState.WaitingForBrowser(session.loginUrl)
            openBrowser(session.loginUrl)
            val credentials = flow.awaitCredentials(session)
            store.save(credentials)
            _state.value = AppState.Loading
            // The login itself succeeded: a failure from here on must not send the user back to sign-in.
            try {
                fetchTasks(credentials)
            } catch (e: CancellationException) {
                throw e
            } catch (e: UnauthorizedException) {
                throw e
            } catch (e: Exception) {
                report(e)
                _state.value = AppState.LoadFailed
            }
        }
    }

    fun cancelSignIn() {
        job?.cancel()
        _state.value = AppState.LoggedOut
    }

    fun refresh() {
        val credentials = store.load() ?: return
        val current = _state.value
        if (current is AppState.Tasks) refreshInPlace(credentials, current) else load(credentials)
    }

    fun signOut() {
        job?.cancel()
        store.clear()
        client = null
        items = emptyMap()
        history.clear()
        _state.value = AppState.LoggedOut
    }

    fun setCompletedOrder(order: CompletedOrder) {
        preferences.completedOrder = order
        _completedOrder.value = order
        if (_state.value is AppState.Tasks) publish()
    }

    fun dismissError() {
        _error.value = null
    }

    /** Ticks or unticks a task. The list updates immediately; the server is written in the background. */
    fun setCompleted(uid: String, completed: Boolean) {
        val now = ICalDateTime.nowUtc()
        val tasks = items.values.map { it.task }
        val changed = if (completed) TaskCompletion.complete(tasks, uid, now) else TaskCompletion.reopen(tasks, uid, now)
        if (changed.isEmpty()) return

        history.record(Edit(before = changed.map { items.getValue(it.uid).task }, after = changed))
        commit(changed)
    }

    fun undo() {
        val edit = history.undo() ?: return
        commit(edit.before.map { it.withLastModified(ICalDateTime.nowUtc()) })
    }

    fun redo() {
        val edit = history.redo() ?: return
        commit(edit.after.map { it.withLastModified(ICalDateTime.nowUtc()) })
    }

    /** Applies [changed] to the visible list at once, then writes them to the server in order. */
    private fun commit(changedTasks: List<Task>) {
        // After a refresh a task may be gone (deleted elsewhere); there is nothing to restore for it.
        val changed = changedTasks.filter { it.uid in items }
        items = items + changed.associate { it.uid to items.getValue(it.uid).copy(task = it) }
        publish()
        if (changed.isEmpty()) return

        viewModelScope.launch {
            serverLock.withLock {
                try {
                    val client = client ?: return@withLock
                    var needsRefetch = false
                    for (task in changed) {
                        val current = items.getValue(task.uid)
                        val result = client.put(calendarHref, current.task, href = current.href, etag = current.etag)
                        items = items + (task.uid to current.copy(etag = result.etag.orEmpty()))
                        if (result.etag == null) needsRefetch = true
                    }
                    if (needsRefetch) store.load()?.let { fetchTasks(it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    report(e)
                    // Show what the server really has instead of an optimistic state that failed to save.
                    try {
                        store.load()?.let { fetchTasks(it) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Keep the original error; the list stays as it was.
                    }
                }
            }
        }
    }

    private fun load(credentials: Credentials) {
        launchGuarded(fallback = AppState.LoadFailed) {
            _state.value = AppState.Loading
            fetchTasks(credentials)
        }
    }

    /** Re-syncs without replacing the list by a spinner; on failure the current list stays. */
    private fun refreshInPlace(credentials: Credentials, current: AppState.Tasks) {
        launchGuarded(fallback = current) {
            _refreshing.value = true
            try {
                fetchTasks(credentials)
            } finally {
                _refreshing.value = false
            }
        }
    }

    private suspend fun fetchTasks(credentials: Credentials) {
        val client = CalDavClient(http, credentials)
        val calendar = client.discoverTaskCalendars().firstOrNull()
            ?: throw IllegalStateException("No task list found on this account")
        val synced = client.sync(calendar.href, syncToken = null).changed

        this.client = client
        calendarName = calendar.displayName
        calendarHref = calendar.href
        items = synced.associateBy { it.task.uid }
        publish()
    }

    private fun publish() {
        _historyState.value = HistoryState(history.canUndo, history.canRedo)
        val (completed, active) = TaskTree.build(items.values.map { it.task }, _completedOrder.value).partition { it.task.isCompleted }
        _state.value = AppState.Tasks(calendarName, active, completed)
    }

    /** Runs [block], turning failures into a visible error. A revoked login sends the user back to sign-in. */
    private fun launchGuarded(fallback: AppState, block: suspend () -> Unit) {
        job?.cancel()
        job = viewModelScope.launch {
            try {
                serverLock.withLock { block() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is UnauthorizedException) store.clear()
                report(e)
                _state.value = if (e is UnauthorizedException) AppState.LoggedOut else fallback
            }
        }
    }

    private fun report(e: Exception) {
        _error.value = e.message ?: e::class.simpleName
    }

    override fun onCleared() {
        http.close()
    }
}
