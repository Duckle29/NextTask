package cc.sche.apps.nexttask

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Surface
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.RadioButton
import androidx.compose.ui.semantics.Role
import cc.sche.apps.nexttask.tree.CompletedOrder
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.FloatingActionButton
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import cc.sche.apps.nexttask.ical.ICalDate
import cc.sche.apps.nexttask.ical.ICalDateTime
import cc.sche.apps.nexttask.model.Task
import androidx.compose.material.icons.outlined.Notifications
import java.time.ZoneId
import java.time.ZonedDateTime
import cc.sche.apps.nexttask.tree.TaskNode
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Only a fresh launch: after a rotation the intent has been handled already.
        if (savedInstanceState == null) openListFrom(intent)
        setContent {
            MaterialTheme {
                App(viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openListFrom(intent)
    }

    override fun onStart() {
        super.onStart()
        viewModel.onForeground()
    }

    /** A tapped reminder opens the list its task is in. */
    private fun openListFrom(intent: Intent) {
        intent.getStringExtra(EXTRA_LIST)?.let(viewModel::selectList)
    }

    companion object {
        const val EXTRA_LIST = "cc.sche.apps.nexttask.extra.LIST"
    }
}

@Composable
private fun App(viewModel: AppViewModel) {
    val state by viewModel.state.collectAsState()
    val message by viewModel.message.collectAsState()
    val syncStatus by viewModel.syncStatus.collectAsState()
    val refreshing by viewModel.refreshing.collectAsState()
    val history by viewModel.historyState.collectAsState()
    val completedOrder by viewModel.completedOrder.collectAsState()
    val remindersEnabled by viewModel.remindersEnabled.collectAsState()
    var showPreferences by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = showPreferences) { showPreferences = false }

    val banner: @Composable () -> Unit = {
        message?.let {
            MessageBanner(it, onUndo = { viewModel.undo(); viewModel.dismissMessage() }, onDismiss = viewModel::dismissMessage)
        }
    }
    val current = state
    if (current is AppState.Tasks && !showPreferences) {
        TaskTreeScreen(
            tasks = current,
            banner = banner,
            refreshing = refreshing,
            syncStatus = syncStatus,
            history = history,
            onSelectList = viewModel::selectList,
            onCreateList = viewModel::createList,
            onDeleteList = viewModel::deleteList,
            onOpenPreferences = { showPreferences = true },
            onUndo = viewModel::undo,
            onRedo = viewModel::redo,
            onSetCompleted = viewModel::setCompleted,
            onAddTask = viewModel::addTask,
            onDeleteTask = viewModel::deleteTask,
            onMoveTask = viewModel::moveTask,
            onSetDue = viewModel::setDue,
            onExport = viewModel::exportTasks,
            onRefresh = viewModel::refresh,
            onSignOut = viewModel::signOut,
            onSignInAgain = viewModel::signInAgain,
        )
        return
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        banner()
        when (current) {
            AppState.Loading -> Centered { CircularProgressIndicator() }
            AppState.LoggedOut -> LoginScreen(onSignIn = viewModel::signIn)
            is AppState.ConfirmHttps -> AlertDialog(
                onDismissRequest = viewModel::cancelSignIn,
                title = { Text("Server redirects to HTTPS") },
                text = { Text("The server redirects to an encrypted connection. Sign in at ${current.httpsServerUrl} instead?") },
                confirmButton = { TextButton(onClick = { viewModel.signIn(current.httpsServerUrl) }) { Text("OK") } },
                dismissButton = { TextButton(onClick = viewModel::cancelSignIn) { Text("Cancel") } },
            )
            is AppState.WaitingForBrowser -> WaitingForBrowserScreen(current.loginUrl, onCancel = viewModel::cancelSignIn)
            AppState.LoadFailed -> Centered {
                Text("Could not load your tasks.")
                syncStatus.failure?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Button(onClick = viewModel::refresh) { Text("Retry") }
                TextButton(onClick = viewModel::signOut) { Text("Sign out") }
            }
            is AppState.Tasks -> PreferencesScreen(
                completedOrder = completedOrder,
                onCompletedOrderChange = viewModel::setCompletedOrder,
                remindersEnabled = remindersEnabled,
                onRemindersEnabledChange = viewModel::setRemindersEnabled,
                onBack = { showPreferences = false },
            )
        }
    }
}

@Composable
private fun MessageBanner(message: UiMessage, onUndo: () -> Unit, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            message.text,
            color = if (message.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (message.undoable) TextButton(onClick = onUndo) { Text("Undo") }
        TextButton(onClick = onDismiss) { Text("Dismiss") }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

private enum class Scheme(val label: String, val prefix: String) {
    Https("https://", "https://"),
    Http("http://", "http://"),
    /** The user types the full address, scheme included (or relies on the https default). */
    None("none", ""),
}

@Composable
private fun LoginScreen(onSignIn: (String) -> Unit) {
    var scheme by rememberSaveable { mutableStateOf(Scheme.Https) }
    var address by rememberSaveable { mutableStateOf("") }

    // Pasted full URLs pick the matching scheme; spaces never belong in an address.
    fun onAddressChange(input: String) {
        val cleaned = input.filterNot { it.isWhitespace() }
        when {
            cleaned.startsWith("https://", ignoreCase = true) -> { scheme = Scheme.Https; address = cleaned.drop("https://".length) }
            cleaned.startsWith("http://", ignoreCase = true) -> { scheme = Scheme.Http; address = cleaned.drop("http://".length) }
            else -> address = cleaned
        }
    }

    var confirmingHttp by rememberSaveable { mutableStateOf(false) }

    // Plain HTTP is only signed in to after the user has read why it is risky.
    val signIn = {
        when {
            address.isEmpty() -> Unit
            scheme == Scheme.Http -> confirmingHttp = true
            else -> onSignIn(scheme.prefix + address)
        }
    }

    if (confirmingHttp) {
        PlainHttpWarning(
            onConfirm = { confirmingHttp = false; onSignIn(scheme.prefix + address) },
            onDismiss = { confirmingHttp = false },
        )
    }

    Centered {
        Text("Sign in to Nextcloud", style = MaterialTheme.typography.headlineSmall)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            Scheme.entries.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = scheme == option,
                    onClick = { scheme = option },
                    shape = SegmentedButtonDefaults.itemShape(index, Scheme.entries.size),
                ) { Text(option.label) }
            }
        }
        OutlinedTextField(
            value = address,
            onValueChange = ::onAddressChange,
            label = { Text("Server address") },
            placeholder = { Text("cloud.example.com") },
            prefix = if (scheme.prefix.isEmpty()) null else ({ Text(scheme.prefix) }),
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go,
            ),
            keyboardActions = KeyboardActions(onGo = { signIn() }),
            modifier = Modifier.fillMaxWidth(),
        )
        if (scheme == Scheme.Http) {
            Text(
                "Unencrypted connection: your login and tasks are sent in plain text.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Button(onClick = { signIn() }, enabled = address.isNotEmpty()) { Text("Sign in with browser") }
    }
}

@Composable
private fun PlainHttpWarning(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sign in without encryption?") },
        text = {
            Text(
                "With http:// everything between this device and your server travels in plain text. " +
                    "Anyone on the same Wi-Fi, and anyone on the way to a server on the internet (the network's " +
                    "operator, your internet provider, a compromised router), can:\n\n" +
                    "• read your tasks\n" +
                    "• change them while they sync\n" +
                    "• copy the app password, which opens your whole Nextcloud account: " +
                    "files, contacts and calendars, not just tasks\n\n" +
                    "Only continue if you trust every network in between. HTTPS on the server, for example with a free " +
                    "Let's Encrypt certificate, avoids all of this.",
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Sign in anyway") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun WaitingForBrowserScreen(loginUrl: String, onCancel: () -> Unit) {
    val context = LocalContext.current
    // Saved across rotation, so the browser opens once per login rather than once per configuration.
    var openedUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var noBrowser by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(loginUrl) {
        if (openedUrl == loginUrl) return@LaunchedEffect
        openedUrl = loginUrl
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(loginUrl)))
        } catch (_: ActivityNotFoundException) {
            noBrowser = true
        }
    }
    Centered {
        if (noBrowser) {
            Text("No browser found. Open this address on this device to approve the login:\n$loginUrl")
        } else {
            Text("Approve the login in your browser, then come back here.")
        }
        CircularProgressIndicator()
        TextButton(onClick = onCancel) { Text("Cancel") }
    }
}

@Composable
private fun PreferencesScreen(
    completedOrder: CompletedOrder,
    onCompletedOrderChange: (CompletedOrder) -> Unit,
    remindersEnabled: Boolean,
    onRemindersEnabledChange: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    var showLicenses by rememberSaveable { mutableStateOf(false) }
    if (showLicenses) {
        LicensesScreen(onBack = { showLicenses = false })
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Preferences", style = MaterialTheme.typography.titleLarge)
        }
        Text(
            "Completed task ordering",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
        )
        CompletedOrderOption(CompletedOrder.Off, "Off", "Nothing moves. Completed tasks stay where they are.", completedOrder, onCompletedOrderChange)
        CompletedOrderOption(
            CompletedOrder.CompletedListOnly,
            "Only in the completed tasks list",
            "Last completed on top in the completed list. Subtasks stay in place.",
            completedOrder,
            onCompletedOrderChange,
        )
        CompletedOrderOption(
            CompletedOrder.CompletedSubtasksLast,
            "Completed subtasks last",
            "As above, and completed subtasks sink below the open ones, as in the Nextcloud web app.",
            completedOrder,
            onCompletedOrderChange,
        )
        CompletedOrderOption(
            CompletedOrder.Everywhere,
            "Everywhere",
            "Completed subtasks sink to the bottom, last completed on top.",
            completedOrder,
            onCompletedOrderChange,
        )
        RemindersPreference(remindersEnabled, onRemindersEnabledChange)
        Text(
            "About",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { showLicenses = true }
                .padding(vertical = 8.dp),
        ) {
            Text("Open source licenses")
            Text(
                "NextTask ${BuildConfig.VERSION_NAME} · BSD 3-Clause License",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The switch for task reminders, and what keeps them from working: blocked notifications, or (Android 12+)
 * no permission for exact alarms, which makes them late. Both are checked again on returning from settings.
 */
@Composable
private fun RemindersPreference(enabled: Boolean, onEnabledChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val scheduler = remember { ReminderScheduler(context) }
    var canNotify by remember { mutableStateOf(scheduler.canNotify) }
    var onTime by remember { mutableStateOf(scheduler.canRemindOnTime) }
    LifecycleResumeEffect(Unit) {
        canNotify = scheduler.canNotify
        onTime = scheduler.canRemindOnTime
        onPauseOrDispose { }
    }
    val askToNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { canNotify = scheduler.canNotify }

    Text("Reminders", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = enabled, role = Role.Switch) { on ->
                onEnabledChange(on)
                if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !scheduler.canNotify) {
                    askToNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text("Notify me of task reminders")
            Text(
                "Nextcloud doesn't send reminders for tasks. Leave this off if another app on this phone, " +
                    "such as Tasks.org, already shows them. Reminders are as current as the last sync.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = enabled, onCheckedChange = null)
    }
    if (enabled && !canNotify) {
        ReminderProblem("Notifications are turned off for NextTask.", "Allow") {
            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }
    } else if (enabled && !onTime && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ReminderProblem("Reminders may come a few minutes late.", "Allow on time") {
            context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
        }
    }
}

@Composable
private fun ReminderProblem(text: String, action: String, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
        TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun CompletedOrderOption(
    option: CompletedOrder,
    title: String,
    description: String,
    selected: CompletedOrder,
    onSelect: (CompletedOrder) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = option == selected, role = Role.RadioButton, onClick = { onSelect(option) })
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = option == selected, onClick = null)
        Column(Modifier.padding(start = 16.dp)) {
            Text(title)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TaskTreeScreen(
    tasks: AppState.Tasks,
    banner: @Composable () -> Unit,
    refreshing: Boolean,
    syncStatus: SyncStatus,
    history: HistoryState,
    onSelectList: (href: String) -> Unit,
    onCreateList: (name: String) -> Unit,
    onDeleteList: (href: String) -> Unit,
    onOpenPreferences: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSetCompleted: (uid: String, completed: Boolean) -> Unit,
    onAddTask: (summary: String, parentUid: String?) -> Unit,
    onDeleteTask: (uid: String) -> Unit,
    onMoveTask: (uid: String, parentUid: String?, afterUid: String?) -> Unit,
    onSetDue: (uid: String, due: ICalDate?) -> Unit,
    onExport: (Uri) -> Unit,
    onRefresh: () -> Unit,
    onSignOut: () -> Unit,
    onSignInAgain: () -> Unit,
) {
    var confirmSignOut by remember { mutableStateOf(false) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var addParentUid by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmDeleteUid by rememberSaveable { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(EXPORT_MIME_TYPE)) { uri ->
        if (uri != null) onExport(uri)
    }
    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out with unsynced changes?") },
            text = {
                Text(
                    "${tasks.pendingChanges} ${if (tasks.pendingChanges == 1) "change hasn't" else "changes haven't"} reached the " +
                        "server yet and will be lost. You can export your tasks first.",
                )
            },
            confirmButton = { TextButton(onClick = { confirmSignOut = false; onSignOut() }) { Text("Sign out") } },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
        )
    }
    var collapsed by rememberSaveable { mutableStateOf(emptySet<String>()) }
    if (adding) {
        val parent = addParentUid?.let { uid -> findNode(tasks.active + tasks.completed, uid) }
        TextInputDialog(
            title = if (parent == null) "New task" else "New subtask of “${parent.task.summary}”",
            label = "Task",
            confirmLabel = "Add",
            onConfirm = { summary ->
                onAddTask(summary, parent?.uid)
                // A subtask added to a folded task would vanish; show it.
                parent?.let { collapsed = collapsed - it.uid }
                adding = false
            },
            onDismiss = { adding = false },
        )
    }
    confirmDeleteUid?.let { uid -> findNode(tasks.active + tasks.completed, uid) }?.let { node ->
        val subtasks = subtreeSize(node) - 1
        AlertDialog(
            onDismissRequest = { confirmDeleteUid = null },
            title = { Text("Delete task?") },
            text = { Text("“${node.task.summary}” and its $subtasks ${if (subtasks == 1) "subtask" else "subtasks"} will be deleted.") },
            confirmButton = { TextButton(onClick = { confirmDeleteUid = null; onDeleteTask(node.uid) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDeleteUid = null }) { Text("Cancel") } },
        )
    }
    var creatingList by rememberSaveable { mutableStateOf(false) }
    if (creatingList) {
        TextInputDialog(
            title = "New list",
            label = "Name",
            confirmLabel = "Create",
            onConfirm = { name -> onCreateList(name); creatingList = false },
            onDismiss = { creatingList = false },
        )
    }
    var confirmDeleteList by rememberSaveable { mutableStateOf<String?>(null) }
    confirmDeleteList?.let { href -> tasks.lists.firstOrNull { it.href == href } }?.let { list ->
        AlertDialog(
            onDismissRequest = { confirmDeleteList = null },
            title = { Text("Delete “${list.name}”?") },
            text = { Text(deleteListWarning(list)) },
            confirmButton = { TextButton(onClick = { confirmDeleteList = null; onDeleteList(list.href) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDeleteList = null }) { Text("Cancel") } },
        )
    }
    var dueTaskUid by rememberSaveable { mutableStateOf<String?>(null) }
    dueTaskUid?.let { uid -> findNode(tasks.active + tasks.completed, uid) }?.let { node ->
        DuePickerDialog(
            current = node.task.due?.resolve(ZoneId.systemDefault()),
            onConfirm = { due -> dueTaskUid = null; onSetDue(node.uid, due) },
            onDismiss = { dueTaskUid = null },
        )
    }
    val onEditDue = { uid: String -> dueTaskUid = uid }
    val onRemoveDue = { uid: String -> onSetDue(uid, null) }
    val onAddSubtask = { uid: String -> addParentUid = uid; adding = true }
    val onDelete = { node: TaskNode ->
        // Deleting a single task is undoable from the message that follows; a whole subtree asks first.
        if (node.children.isEmpty()) onDeleteTask(node.uid) else confirmDeleteUid = node.uid
    }
    var completedSectionOpen by rememberSaveable { mutableStateOf(true) }
    val activeRows = remember(tasks.active, collapsed) { tasks.active.flatMap { visibleRows(it, collapsed) } }
    val completedRows = remember(tasks.completed, collapsed) { tasks.completed.flatMap { visibleRows(it, collapsed) } }
    val toggleCollapsed = { uid: String -> collapsed = collapsed.toggle(uid) }
    val listState = rememberLazyListState()

    // Long-pressing an open task picks it up, with its subtasks, to move it elsewhere in the list.
    var drag by remember { mutableStateOf<TaskDrag?>(null) }
    val haptics = LocalHapticFeedback.current
    val indentPx = with(LocalDensity.current) { SUBTASK_INDENT.toPx() }
    // Lambdas rather than local functions: the compiler keeps a reference to a local function from the first
    // composition, rows and all, while a lambda is renewed whenever what it captures changes.
    val startDrag: (Offset) -> TaskDrag? = startDrag@{ position ->
        val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { position.y.toInt() in it.offset until it.offset + it.size }
            ?: return@startDrag null
        val index = activeRows.indexOfFirst { it.node.uid == item.key }
        if (index < 0) return@startDrag null
        val row = activeRows[index]
        val moving = row.node.flatten().map { it.task.uid }.toSet()
        val remaining = activeRows.filterNot { it.node.uid in moving }
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        TaskDrag(row, remaining, index, item.size, position.y - item.offset, position.y, indentPx).also { drag = it }
    }
    val drop: (TaskDrag) -> Unit = drop@{ held ->
        drag = null
        if (!held.moves) return@drop
        val target = held.target
        // A task dropped into a folded one would vanish; show it.
        target.parentUid?.let { collapsed = collapsed - it }
        onMoveTask(held.row.node.uid, target.parentUid, target.afterUid)
    }
    val currentStartDrag = rememberUpdatedState(startDrag)
    val currentDrop = rememberUpdatedState(drop)
    val currentCancelDrag = rememberUpdatedState { drag = null }
    FollowDrag(drag, listState)
    val activeRow: @Composable LazyItemScope.(VisibleRow) -> Unit = {
        // Rows slide aside for a dragged task; nothing fades, so a dropped task does not flicker into place.
        val modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)
        TaskRow(it, collapsed, tasks.accessRevoked, onSetCompleted, toggleCollapsed, onAddSubtask, onEditDue, onRemoveDue, onDelete, modifier)
    }
    val pullState = rememberPullToRefreshState()
    val pullThresholdPx = with(LocalDensity.current) { PullToRefreshDefaults.PositionalThreshold.toPx() }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }
    val closeThen = { action: () -> Unit -> scope.launch { drawerState.close() }; action() }

    // Swiping right anywhere on the screen opens the drawer, swiping left closes it.
    ModalNavigationDrawer(
        drawerState = drawerState,
        // Moving a dragged task sideways changes its level; it must not pull the drawer open.
        gesturesEnabled = drag == null,
        drawerContent = {
            ListsDrawer(
                tasks = tasks,
                onSelectList = { href -> closeThen { onSelectList(href) } },
                onNewList = { closeThen { creatingList = true } },
                onDeleteList = { closeThen { confirmDeleteList = tasks.selectedHref } },
                onRefresh = { closeThen(onRefresh) },
                onExport = { closeThen { exportLauncher.launch(exportFileName(tasks.calendarName)) } },
                onOpenPreferences = { closeThen(onOpenPreferences) },
                onSignOut = { closeThen { if (tasks.pendingChanges > 0) confirmSignOut = true else onSignOut() } },
            )
        },
    ) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            banner()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                    Icon(Icons.Default.Menu, contentDescription = "Lists and menu")
                }
                Text(tasks.calendarName, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = onUndo, enabled = history.canUndo) {
                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
                }
                IconButton(onClick = onRedo, enabled = history.canRedo) {
                    Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo")
                }
            }
            if (tasks.accessRevoked) SignedOutBanner(onSignInAgain)
            SyncStatusLine(tasks.pendingChanges, syncStatus)
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = onRefresh,
                state = pullState,
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(
                    // Let the list follow the finger like an overscroll, and ease back on release.
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { translationY = pullState.distanceFraction * pullThresholdPx }
                        .longPressDragging(
                            enabled = !tasks.accessRevoked,
                            onStart = currentStartDrag,
                            onDrop = currentDrop,
                            onCancel = currentCancelDrag,
                        ),
                    state = listState,
                    // Room below the last task, so the add button never covers it.
                    contentPadding = PaddingValues(bottom = 88.dp),
                ) {
                    if (activeRows.isEmpty() && tasks.completed.isEmpty()) item { Text("No tasks.") }
                    val held = drag
                    if (held == null) {
                        items(activeRows, key = { it.node.uid }) { activeRow(it) }
                    } else {
                        items(held.remaining.subList(0, held.gap), key = { it.node.uid }) { activeRow(it) }
                        item(key = DROP_GAP_KEY) { DropGap(held, Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)) }
                        items(held.remaining.subList(held.gap, held.remaining.size), key = { it.node.uid }) { activeRow(it) }
                    }
                    if (tasks.completed.isNotEmpty()) {
                        item(key = "completed-header") {
                            val count = tasks.completed.size
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable(role = Role.Button) { completedSectionOpen = !completedSectionOpen }
                                    .padding(top = 24.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(Modifier.size(FOLD_TARGET_SIZE), contentAlignment = Alignment.Center) {
                                    FoldIcon(expanded = completedSectionOpen)
                                }
                                Text(
                                    "$count Completed ${if (count == 1) "Task" else "Tasks"}",
                                    style = MaterialTheme.typography.titleSmall,
                                )
                            }
                        }
                        if (completedSectionOpen) {
                            items(completedRows, key = { it.node.uid }) { TaskRow(it, collapsed, tasks.accessRevoked, onSetCompleted, toggleCollapsed, onAddSubtask, onEditDue, onRemoveDue, onDelete) }
                        }
                    }
                }
                if (!tasks.accessRevoked) {
                    FloatingActionButton(
                        onClick = { addParentUid = null; adding = true },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add task")
                    }
                }
                drag?.let { held ->
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        shadowElevation = 6.dp,
                        modifier = Modifier.fillMaxWidth().offset { IntOffset(held.dx.roundToInt(), held.top.roundToInt()) },
                    ) {
                        // Above everything else, add button included. Its subtasks travel along folded up.
                        TaskRow(held.row, setOf(held.row.node.uid), tasks.accessRevoked, onSetCompleted, toggleCollapsed, onAddSubtask, onEditDue, onRemoveDue, onDelete)
                    }
                }
            }
        }
    }
}

@Composable
private fun ListsDrawer(
    tasks: AppState.Tasks,
    onSelectList: (href: String) -> Unit,
    onNewList: () -> Unit,
    onDeleteList: () -> Unit,
    onRefresh: () -> Unit,
    onExport: () -> Unit,
    onOpenPreferences: () -> Unit,
    onSignOut: () -> Unit,
) {
    ModalDrawerSheet(Modifier.fillMaxWidth(DRAWER_WIDTH_FRACTION)) {
        Column(Modifier.fillMaxHeight().padding(vertical = 12.dp)) {
            Text(
                "Task lists",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp),
            )
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                tasks.lists.forEach { list ->
                    NavigationDrawerItem(
                        label = { Text(list.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        selected = list.href == tasks.selectedHref,
                        onClick = { onSelectList(list.href) },
                        icon = { ListColorDot(list.color) },
                        badge = { if (list.pendingChanges > 0) Text(list.pendingChanges.toString()) },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
                if (!tasks.accessRevoked) DrawerAction("New list", Icons.Default.Add, onNewList)
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp, horizontal = 16.dp))
            // The last list stays: without one there would be nothing to show.
            if (!tasks.accessRevoked && tasks.lists.size > 1) DrawerAction("Delete this list…", Icons.Default.Delete, onDeleteList)
            if (!tasks.accessRevoked) DrawerAction("Refresh", Icons.Default.Refresh, onRefresh)
            DrawerAction("Export tasks…", Icons.Default.Download, onExport)
            DrawerAction("Preferences", Icons.Default.Settings, onOpenPreferences)
            DrawerAction("Sign out", Icons.AutoMirrored.Filled.Logout, onSignOut)
        }
    }
}

@Composable
private fun DrawerAction(label: String, icon: ImageVector, onClick: () -> Unit) {
    NavigationDrawerItem(
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        selected = false,
        onClick = onClick,
        icon = { Icon(icon, contentDescription = null) },
        modifier = Modifier.padding(horizontal = 12.dp),
    )
}

/** The list's own colour from the server, or the theme colour if it has none or it cannot be read. */
@Composable
private fun ListColorDot(color: String?) {
    val parsed = remember(color) { color?.let(::parseServerColor) }
    Box(Modifier.size(12.dp).clip(CircleShape).background(parsed ?: MaterialTheme.colorScheme.primary))
}

/**
 * `#RRGGBB`, or `#RRGGBBAA` as many CalDAV clients write it (alpha last, unlike Android's `#AARRGGBB`).
 * The transparency is ignored: a list's colour is always shown opaque.
 */
internal fun parseServerColor(value: String): Color? {
    val hex = value.trim().removePrefix("#")
    if (hex.length != 6 && hex.length != 8) return null
    val rgb = hex.take(6).toIntOrNull(16) ?: return null
    return Color(0xFF000000.toInt() or rgb)
}

private const val DRAWER_WIDTH_FRACTION = 0.72f

/** What deleting [list] takes with it. */
private fun deleteListWarning(list: TaskList): String = buildString {
    append(
        if (list.taskCount == 0) "The list will be deleted"
        else "The list and its ${list.taskCount} ${if (list.taskCount == 1) "task" else "tasks"} will be deleted",
    )
    append(if (list.isNew) "." else " from the server and all your devices.")
    if (!list.isNew && list.pendingChanges > 0) {
        append(" ${list.pendingChanges} ${if (list.pendingChanges == 1) "change that hasn't" else "changes that haven't"} synced yet will be lost.")
    }
    if (list.acceptsEvents) append(" It is also a calendar: its events will be deleted too.")
}

/** Asks for one line of text, such as a task's summary or a list's name. */
@Composable
private fun TextInputDialog(
    title: String,
    label: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val submit = { if (text.isNotBlank()) onConfirm(text) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = { submit() }, enabled = text.isNotBlank()) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SyncStatusLine(pendingChanges: Int, status: SyncStatus) {
    val changes = "$pendingChanges ${if (pendingChanges == 1) "change" else "changes"}"
    val text = when {
        status.failure != null && pendingChanges > 0 -> "$changes waiting to sync · ${status.failure}"
        status.failure != null -> "Showing the saved copy · ${status.failure}"
        pendingChanges > 0 && status.syncing -> "Syncing $changes…"
        pendingChanges > 0 -> "$changes waiting to sync"
        else -> return
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (status.failure != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun SignedOutBanner(onSignInAgain: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    ) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp)) {
            Text("Signed out", style = MaterialTheme.typography.titleSmall)
            Text(
                "The server no longer accepts this device's login. Your tasks stay here, read-only, " +
                    "until you sign in again. Long-press a task to copy its text.",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = onSignInAgain, modifier = Modifier.align(Alignment.End)) { Text("Sign in again") }
        }
    }
}

@Composable
private fun TaskRow(
    row: VisibleRow,
    collapsed: Set<String>,
    readOnly: Boolean,
    onSetCompleted: (uid: String, completed: Boolean) -> Unit,
    onToggleCollapsed: (uid: String) -> Unit,
    onAddSubtask: (uid: String) -> Unit,
    onEditDue: (uid: String) -> Unit,
    onRemoveDue: (uid: String) -> Unit,
    onDelete: (TaskNode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val task = row.node.task
    val hasChildren = row.node.children.isNotEmpty()
    Row(
        modifier.fillMaxWidth().padding(start = SUBTASK_INDENT * row.depth),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(FOLD_TARGET_SIZE)
                .clickable(enabled = hasChildren, role = Role.Button) { onToggleCollapsed(row.node.uid) },
            contentAlignment = Alignment.Center,
        ) {
            if (hasChildren) FoldIcon(expanded = row.node.uid !in collapsed)
        }
        Checkbox(checked = task.isCompleted, onCheckedChange = { onSetCompleted(task.uid, it) }, enabled = !readOnly)
        Column(Modifier.weight(1f)) {
            val summary = @Composable {
                Text(
                    task.summary,
                    textDecoration = if (task.isCompleted) TextDecoration.LineThrough else null,
                    color = if (task.isCompleted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
            }
            // Read-only after a revoked login: let the user copy what they still need.
            if (readOnly) SelectionContainer { summary() } else summary()
            task.completed?.let(ICalDateTime::dateOf)?.let {
                Text("Completed $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            if (!task.isCompleted) DueLine(task)
        }
        if (!readOnly) {
            Box {
                var menuOpen by remember { mutableStateOf(false) }
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Task actions")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Add subtask") }, onClick = { menuOpen = false; onAddSubtask(task.uid) })
                    DropdownMenuItem(
                        text = { Text(if (task.due == null) "Set due date…" else "Change due date…") },
                        onClick = { menuOpen = false; onEditDue(task.uid) },
                    )
                    if (task.due != null) {
                        DropdownMenuItem(text = { Text("Remove due date") }, onClick = { menuOpen = false; onRemoveDue(task.uid) })
                    }
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menuOpen = false; onDelete(row.node) })
                }
            }
        }
    }
}

/** When an open task is due (red once overdue), and a bell if it has reminders. */
@Composable
private fun DueLine(task: Task) {
    val now = ZonedDateTime.now()
    val due = remember(task.due, now.zone) { task.due?.resolve(now.zone) }
    if (due == null && !task.hasReminders) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (due != null) {
            Text(
                dueLabel(due, now),
                style = MaterialTheme.typography.bodySmall,
                color = if (task.isOverdue(now)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (task.hasReminders) {
            Icon(
                Icons.Outlined.Notifications,
                contentDescription = "Has reminders",
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val FOLD_TARGET_SIZE = 32.dp

private const val DROP_GAP_KEY = "drop-gap"

/** The empty slot a dragged task would drop into, indented to the level it would land at. */
@Composable
private fun DropGap(drag: TaskDrag, modifier: Modifier) {
    val height = with(LocalDensity.current) { drag.rowHeight.toDp() }
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .padding(start = SUBTASK_INDENT * drag.target.depth, top = 4.dp, bottom = 4.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small),
    )
}

private const val EXPORT_MIME_TYPE = "text/calendar"

/** `<list name>-2026-10-18.ics`, without characters that file systems reject. */
private fun exportFileName(calendarName: String): String {
    val name = calendarName.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().ifEmpty { "tasks" }
    val date = ICalDateTime.dateOf(ICalDateTime.nowUtc())
    return "$name-$date.ics"
}

@Composable
private fun FoldIcon(expanded: Boolean) {
    Icon(
        imageVector = if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
        contentDescription = if (expanded) "Collapse" else "Expand",
        modifier = Modifier.size(24.dp),
    )
}

private fun findNode(forest: List<TaskNode>, uid: String): TaskNode? {
    for (node in forest) {
        if (node.uid == uid) return node
        findNode(node.children, uid)?.let { return it }
    }
    return null
}

/** The task itself plus everything below it. */
private fun subtreeSize(node: TaskNode): Int = 1 + node.children.sumOf(::subtreeSize)

private fun Set<String>.toggle(uid: String): Set<String> = if (uid in this) this - uid else this + uid
