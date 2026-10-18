package cc.sche.apps.nexttask

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import cc.sche.apps.nexttask.ical.ICalDateTime
import cc.sche.apps.nexttask.tree.TaskNode

class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                App(viewModel)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.onForeground()
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

    val signIn = { if (address.isNotEmpty()) onSignIn(scheme.prefix + address) }

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
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
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
    val onAddSubtask = { uid: String -> addParentUid = uid; adding = true }
    val onDelete = { node: TaskNode ->
        // Deleting a single task is undoable from the message that follows; a whole subtree asks first.
        if (node.children.isEmpty()) onDeleteTask(node.uid) else confirmDeleteUid = node.uid
    }
    var completedSectionOpen by rememberSaveable { mutableStateOf(true) }
    val activeRows = remember(tasks.active, collapsed) { tasks.active.flatMap { visibleRows(it, collapsed) } }
    val completedRows = remember(tasks.completed, collapsed) { tasks.completed.flatMap { visibleRows(it, collapsed) } }
    val toggleCollapsed = { uid: String -> collapsed = collapsed.toggle(uid) }
    val pullState = rememberPullToRefreshState()
    val pullThresholdPx = with(LocalDensity.current) { PullToRefreshDefaults.PositionalThreshold.toPx() }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }
    val closeThen = { action: () -> Unit -> scope.launch { drawerState.close() }; action() }

    // Swiping right anywhere on the screen opens the drawer, swiping left closes it.
    ModalNavigationDrawer(
        drawerState = drawerState,
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
                    Modifier.fillMaxSize().graphicsLayer { translationY = pullState.distanceFraction * pullThresholdPx },
                    // Room below the last task, so the add button never covers it.
                    contentPadding = PaddingValues(bottom = 88.dp),
                ) {
                    if (activeRows.isEmpty() && tasks.completed.isEmpty()) item { Text("No tasks.") }
                    items(activeRows, key = { it.node.uid }) { TaskRow(it, collapsed, tasks.accessRevoked, onSetCompleted, toggleCollapsed, onAddSubtask, onDelete) }
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
                            items(completedRows, key = { it.node.uid }) { TaskRow(it, collapsed, tasks.accessRevoked, onSetCompleted, toggleCollapsed, onAddSubtask, onDelete) }
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
    onDelete: (TaskNode) -> Unit,
) {
    val task = row.node.task
    val hasChildren = row.node.children.isNotEmpty()
    Row(
        Modifier.fillMaxWidth().padding(start = (row.depth * 24).dp),
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
        }
        if (!readOnly) {
            Box {
                var menuOpen by remember { mutableStateOf(false) }
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Task actions")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Add subtask") }, onClick = { menuOpen = false; onAddSubtask(task.uid) })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menuOpen = false; onDelete(row.node) })
                }
            }
        }
    }
}

private val FOLD_TARGET_SIZE = 32.dp

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

private data class VisibleRow(val node: TaskNode, val depth: Int)

private fun visibleRows(node: TaskNode, collapsed: Set<String>, depth: Int = 0): List<VisibleRow> =
    listOf(VisibleRow(node, depth)) +
        if (node.uid in collapsed) emptyList() else node.children.flatMap { visibleRows(it, collapsed, depth + 1) }

private fun Set<String>.toggle(uid: String): Set<String> = if (uid in this) this - uid else this + uid
