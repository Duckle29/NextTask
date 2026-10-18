package cc.mikkel.nexttask

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import cc.mikkel.nexttask.ical.ICalDateTime
import cc.mikkel.nexttask.tree.TaskNode

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
            is AppState.Tasks -> TaskTreeScreen(
                tasks = current,
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

@Composable
private fun LoginScreen(onSignIn: (String) -> Unit) {
    var server by rememberSaveable { mutableStateOf("") }
    Centered {
        Text("Sign in to Nextcloud", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = server,
            onValueChange = { server = it },
            label = { Text("Server address") },
            placeholder = { Text("cloud.example.com") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { onSignIn(server) }, enabled = server.isNotBlank()) { Text("Sign in with browser") }
    }
}

@Composable
private fun TaskTreeScreen(
    tasks: AppState.Tasks,
    onSetCompleted: (uid: String, completed: Boolean) -> Unit,
    onRefresh: () -> Unit,
    onSignOut: () -> Unit,
) {
    var collapsed by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var completedSectionOpen by rememberSaveable { mutableStateOf(true) }
    val activeRows = remember(tasks.active, collapsed) { tasks.active.flatMap { visibleRows(it, collapsed) } }
    val completedRows = remember(tasks.completed, collapsed) { tasks.completed.flatMap { visibleRows(it, collapsed) } }
    val toggleCollapsed = { uid: String -> collapsed = collapsed.toggle(uid) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(tasks.calendarName, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onRefresh) { Text("Refresh") }
            TextButton(onClick = onSignOut) { Text("Sign out") }
        }
        LazyColumn(Modifier.fillMaxWidth()) {
            if (activeRows.isEmpty() && tasks.completed.isEmpty()) item { Text("No tasks.") }
            items(activeRows, key = { it.node.uid }) { TaskRow(it, collapsed, onSetCompleted, toggleCollapsed) }
            if (tasks.completed.isNotEmpty()) {
                item(key = "completed-header") {
                    val count = tasks.completed.size
                    Text(
                        text = (if (completedSectionOpen) "▾ " else "▸ ") +
                            "$count Completed ${if (count == 1) "Task" else "Tasks"}",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { completedSectionOpen = !completedSectionOpen }
                            .padding(top = 24.dp, bottom = 8.dp),
                    )
                }
                if (completedSectionOpen) {
                    items(completedRows, key = { it.node.uid }) { TaskRow(it, collapsed, onSetCompleted, toggleCollapsed) }
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
    val marker = when {
        !hasChildren -> ""
        row.node.uid in collapsed -> "▸"
        else -> "▾"
    }
    Row(
        Modifier.fillMaxWidth().padding(start = (row.depth * 24).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            marker,
            modifier = Modifier
                .width(20.dp)
                .clickable(enabled = hasChildren) { onToggleCollapsed(row.node.uid) },
        )
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

private data class VisibleRow(val node: TaskNode, val depth: Int)

private fun visibleRows(node: TaskNode, collapsed: Set<String>, depth: Int = 0): List<VisibleRow> =
    listOf(VisibleRow(node, depth)) +
        if (node.uid in collapsed) emptyList() else node.children.flatMap { visibleRows(it, collapsed, depth + 1) }

private fun Set<String>.toggle(uid: String): Set<String> = if (uid in this) this - uid else this + uid
