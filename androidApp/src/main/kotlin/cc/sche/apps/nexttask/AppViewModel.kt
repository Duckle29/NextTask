package cc.sche.apps.nexttask

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cc.sche.apps.nexttask.auth.Credentials
import cc.sche.apps.nexttask.auth.LoginFlowV2
import cc.sche.apps.nexttask.auth.RedirectsToHttpsException
import cc.sche.apps.nexttask.auth.normalizeServerUrl
import cc.sche.apps.nexttask.auth.revokeAppPassword
import cc.sche.apps.nexttask.caldav.CalDavClient
import cc.sche.apps.nexttask.caldav.RemoteTask
import cc.sche.apps.nexttask.caldav.UnauthorizedException
import cc.sche.apps.nexttask.ical.ICalDateTime
import cc.sche.apps.nexttask.model.Edit
import cc.sche.apps.nexttask.model.EditHistory
import cc.sche.apps.nexttask.model.Task
import cc.sche.apps.nexttask.model.TaskCompletion
import cc.sche.apps.nexttask.model.withLastModified
import cc.sche.apps.nexttask.tree.CompletedOrder
import cc.sche.apps.nexttask.tree.TaskNode
import cc.sche.apps.nexttask.tree.TaskTree
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestRetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
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
    /** The plain HTTP address the user entered redirects to HTTPS; ask before signing in there instead. */
    data class ConfirmHttps(val httpsServerUrl: String) : AppState
    /** The user has been sent to the browser and we are waiting for them to approve. */
    data class WaitingForBrowser(val loginUrl: String) : AppState
    /**
     * [active] are the open top-level tasks; [completed] the done top-level tasks, last completed on top.
     * With [accessRevoked] the server no longer accepts the login: the list is the last known state, read-only.
     */
    data class Tasks(
        val calendarName: String,
        val active: List<TaskNode>,
        val completed: List<TaskNode>,
        val accessRevoked: Boolean = false,
    ) : AppState
}

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val store = CredentialStore(application)
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

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var job: Job? = null

    /** Parent of all work for the signed-in account, so signing out stops it in one go. */
    private val accountWork = SupervisorJob(viewModelScope.coroutineContext.job)

    /** Serialises server round trips so ETags are never stale between consecutive writes. */
    private val serverLock = Mutex()

    private var credentials: Credentials? = null
    private var client: CalDavClient? = null
    private var calendarName = ""
    private var calendarHref = ""
    private var items: Map<String, RemoteTask> = emptyMap()
    private var accessRevoked = false

    init {
        val saved = store.load()
        credentials = saved
        if (saved == null) _state.value = AppState.LoggedOut else load(saved)
    }

    /** Starts the browser login; the UI opens [AppState.WaitingForBrowser.loginUrl]. */
    fun signIn(serverInput: String) {
        val serverUrl = normalizeServerUrl(serverInput)
        if (serverUrl == null) {
            _error.value = "Enter a valid server address"
            return
        }
        launchGuarded(fallback = AppState.LoggedOut) {
            _state.value = AppState.Loading
            val flow = LoginFlowV2(http, userAgent = "NextTask Android")
            val session = try {
                flow.start(serverUrl)
            } catch (e: RedirectsToHttpsException) {
                _state.value = AppState.ConfirmHttps(e.httpsServerUrl)
                return@launchGuarded
            }
            _state.value = AppState.WaitingForBrowser(session.loginUrl)
            val approved = flow.awaitCredentials(session)
            store.save(approved)
            credentials = approved
            _state.value = AppState.Loading
            // The login itself succeeded: a failure from here on must not send the user back to sign-in.
            try {
                serverLock.withLock { fetchTasks(approved) }
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
        val credentials = credentials ?: return
        val current = _state.value
        if (current is AppState.Tasks) refreshInPlace(credentials, current) else load(credentials)
    }

    fun signOut() {
        val signedOut = credentials
        forgetAccount()
        if (signedOut == null) return
        // Best effort: if this fails the app password stays valid on the server, so tell the user.
        viewModelScope.launch {
            try {
                revokeAppPassword(http, signedOut)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _error.value = "Signed out, but the app password could not be revoked. " +
                    "Remove it in Nextcloud under Settings → Security."
            }
        }
    }

    /** Leaves the read-only list shown after a revoked login for the sign-in screen. */
    fun signInAgain() {
        forgetAccount()
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
        if (accessRevoked) return
        val now = ICalDateTime.nowUtc()
        val tasks = items.values.map { it.task }
        val changed = if (completed) TaskCompletion.complete(tasks, uid, now) else TaskCompletion.reopen(tasks, uid, now)
        if (changed.isEmpty()) return

        history.record(Edit(before = changed.map { items.getValue(it.uid).task }, after = changed))
        commit(changed)
    }

    fun undo() {
        if (accessRevoked) return
        val edit = history.undo() ?: return
        commit(edit.before.map { it.withLastModified(ICalDateTime.nowUtc()) })
    }

    fun redo() {
        if (accessRevoked) return
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

        viewModelScope.launch(accountWork) {
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
                    if (needsRefetch) credentials?.let { fetchTasks(it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: UnauthorizedException) {
                    onAccessRevoked()
                } catch (e: Exception) {
                    report(e)
                    // Show what the server really has instead of an optimistic state that failed to save.
                    try {
                        credentials?.let { fetchTasks(it) }
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
            serverLock.withLock { fetchTasks(credentials) }
        }
    }

    /** Re-syncs without replacing the list by a spinner; on failure the current list stays. */
    private fun refreshInPlace(credentials: Credentials, current: AppState.Tasks) {
        launchGuarded(fallback = current) {
            _refreshing.value = true
            try {
                serverLock.withLock { fetchTasks(credentials) }
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
        _historyState.value = HistoryState(history.canUndo && !accessRevoked, history.canRedo && !accessRevoked)
        val (completed, active) = TaskTree.build(items.values.map { it.task }, _completedOrder.value).partition { it.task.isCompleted }
        _state.value = AppState.Tasks(calendarName, active, completed, accessRevoked)
    }

    /**
     * Runs [block], turning failures into a visible error, or [onAccessRevoked] for a revoked login.
     * [block] takes [serverLock] itself where it touches the task list, so waiting on the browser blocks nothing.
     */
    private fun launchGuarded(fallback: AppState, block: suspend () -> Unit) {
        job?.cancel()
        job = viewModelScope.launch(accountWork) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: UnauthorizedException) {
                onAccessRevoked()
            } catch (e: Exception) {
                report(e)
                _state.value = fallback
            }
        }
    }

    /**
     * The server no longer accepts the app password. A task list on screen stays, read-only, so the user
     * can copy what they still need before signing in again; without one there is nothing to keep.
     */
    private fun onAccessRevoked() {
        if (_state.value !is AppState.Tasks) {
            forgetAccount()
            _error.value = "Signed out: the server no longer accepts this device's login."
            return
        }
        accountWork.cancelChildren()
        store.clear()
        credentials = null
        client = null
        accessRevoked = true
        publish()
    }

    /** Stops all work for the signed-in account, drops its data and shows the sign-in screen. */
    private fun forgetAccount() {
        accountWork.cancelChildren()
        store.clear()
        credentials = null
        client = null
        items = emptyMap()
        accessRevoked = false
        history.clear()
        _state.value = AppState.LoggedOut
    }

    private fun report(e: Exception) {
        _error.value = e.message ?: e::class.simpleName
    }

    override fun onCleared() {
        http.close()
    }
}
