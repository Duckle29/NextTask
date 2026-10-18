package cc.sche.apps.nexttask

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.lifecycle.viewmodel.compose.viewModel
import cc.sche.apps.nexttask.ical.ICalDateTime
import cc.sche.apps.nexttask.tree.TaskNode

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                App()
            }
        }
    }
}

@Composable
private fun App(viewModel: AppViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val error by viewModel.error.collectAsState()
    val refreshing by viewModel.refreshing.collectAsState()
    val history by viewModel.historyState.collectAsState()
    val completedOrder by viewModel.completedOrder.collectAsState()
    var showPreferences by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = showPreferences) { showPreferences = false }
    val context = LocalContext.current

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        error?.let {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                TextButton(onClick = viewModel::dismissError) { Text("Dismiss") }
            }
        }
        when (val current = state) {
            AppState.Loading -> Centered { CircularProgressIndicator() }
            AppState.LoggedOut -> LoginScreen { server ->
                viewModel.signIn(server) { url -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            }
            is AppState.WaitingForBrowser -> Centered {
                Text("Approve the login in your browser, then come back here.")
                CircularProgressIndicator()
                TextButton(onClick = viewModel::cancelSignIn) { Text("Cancel") }
            }
            AppState.LoadFailed -> Centered {
                Text("Could not load your tasks.")
                Button(onClick = viewModel::refresh) { Text("Retry") }
                TextButton(onClick = viewModel::signOut) { Text("Sign out") }
            }
            is AppState.Tasks -> if (showPreferences) PreferencesScreen(
                completedOrder = completedOrder,
                onCompletedOrderChange = viewModel::setCompletedOrder,
                onBack = { showPreferences = false },
            ) else TaskTreeScreen(
                tasks = current,
                refreshing = refreshing,
                history = history,
                onOpenPreferences = { showPreferences = true },
                onUndo = viewModel::undo,
                onRedo = viewModel::redo,
                onSetCompleted = viewModel::setCompleted,
                onRefresh = viewModel::refresh,
                onSignOut = viewModel::signOut,
            )
        }
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
    refreshing: Boolean,
    history: HistoryState,
    onOpenPreferences: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSetCompleted: (uid: String, completed: Boolean) -> Unit,
    onRefresh: () -> Unit,
    onSignOut: () -> Unit,
) {
    var collapsed by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var completedSectionOpen by rememberSaveable { mutableStateOf(true) }
    val activeRows = remember(tasks.active, collapsed) { tasks.active.flatMap { visibleRows(it, collapsed) } }
    val completedRows = remember(tasks.completed, collapsed) { tasks.completed.flatMap { visibleRows(it, collapsed) } }
    val toggleCollapsed = { uid: String -> collapsed = collapsed.toggle(uid) }
    val pullState = rememberPullToRefreshState()
    val pullThresholdPx = with(LocalDensity.current) { PullToRefreshDefaults.PositionalThreshold.toPx() }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box {
                var menuOpen by remember { mutableStateOf(false) }
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.Menu, contentDescription = "Menu")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Refresh") }, onClick = { menuOpen = false; onRefresh() })
                    DropdownMenuItem(text = { Text("Preferences") }, onClick = { menuOpen = false; onOpenPreferences() })
                    DropdownMenuItem(text = { Text("Sign out") }, onClick = { menuOpen = false; onSignOut() })
                }
            }
            Text(tasks.calendarName, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = onUndo, enabled = history.canUndo) {
                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
            }
            IconButton(onClick = onRedo, enabled = history.canRedo) {
                Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo")
            }
        }
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = onRefresh,
            state = pullState,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                // Let the list follow the finger like an overscroll, and ease back on release.
                Modifier.fillMaxSize().graphicsLayer { translationY = pullState.distanceFraction * pullThresholdPx },
            ) {
                if (activeRows.isEmpty() && tasks.completed.isEmpty()) item { Text("No tasks.") }
                items(activeRows, key = { it.node.uid }) { TaskRow(it, collapsed, onSetCompleted, toggleCollapsed) }
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
                        items(completedRows, key = { it.node.uid }) { TaskRow(it, collapsed, onSetCompleted, toggleCollapsed) }
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskRow(
    row: VisibleRow,
    collapsed: Set<String>,
    onSetCompleted: (uid: String, completed: Boolean) -> Unit,
    onToggleCollapsed: (uid: String) -> Unit,
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
        Checkbox(checked = task.isCompleted, onCheckedChange = { onSetCompleted(task.uid, it) })
        Column(Modifier.weight(1f)) {
            Text(
                task.summary,
                textDecoration = if (task.isCompleted) TextDecoration.LineThrough else null,
                color = if (task.isCompleted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            task.completed?.let(ICalDateTime::dateOf)?.let {
                Text("Completed $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

private val FOLD_TARGET_SIZE = 32.dp

@Composable
private fun FoldIcon(expanded: Boolean) {
    Icon(
        imageVector = if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
        contentDescription = if (expanded) "Collapse" else "Expand",
        modifier = Modifier.size(24.dp),
    )
}

private data class VisibleRow(val node: TaskNode, val depth: Int)

private fun visibleRows(node: TaskNode, collapsed: Set<String>, depth: Int = 0): List<VisibleRow> =
    listOf(VisibleRow(node, depth)) +
        if (node.uid in collapsed) emptyList() else node.children.flatMap { visibleRows(it, collapsed, depth + 1) }

private fun Set<String>.toggle(uid: String): Set<String> = if (uid in this) this - uid else this + uid
