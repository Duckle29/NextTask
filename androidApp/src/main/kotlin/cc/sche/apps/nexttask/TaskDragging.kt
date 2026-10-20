package cc.sche.apps.nexttask

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import cc.sche.apps.nexttask.tree.TaskNode
import kotlin.math.roundToInt

/** How far each level of subtasks is indented; dragging a task sideways by this much changes its level by one. */
internal val SUBTASK_INDENT = 24.dp

/** A task as a line of the list, [depth] levels below the top. */
internal data class VisibleRow(val node: TaskNode, val depth: Int)

/** [node] and, unless it is folded, its subtasks to any depth, in display order. */
internal fun visibleRows(node: TaskNode, collapsed: Set<String>, depth: Int = 0): List<VisibleRow> =
    listOf(VisibleRow(node, depth)) +
        if (node.uid in collapsed) emptyList() else node.children.flatMap { visibleRows(it, collapsed, depth + 1) }

/** Where a dragged task lands: [depth] levels deep, under [parentUid] (null: top level), right after [afterUid] (null: first). */
internal data class DropTarget(val depth: Int, val parentUid: String?, val afterUid: String?)

/**
 * Where a task dropped into the gap before `rows[gap]` lands if the user wants it [wantedDepth] levels deep.
 * The depth is limited to what fits there: at most one level below the row above (a subtask of it), and no
 * shallower than the row below (which would otherwise end up under the dropped task).
 */
internal fun dropTarget(rows: List<VisibleRow>, gap: Int, wantedDepth: Int): DropTarget {
    val deepest = rows.getOrNull(gap - 1)?.let { it.depth + 1 } ?: 0
    val shallowest = rows.getOrNull(gap)?.depth ?: 0
    val depth = wantedDepth.coerceIn(shallowest, deepest)
    var parentUid: String? = null
    var afterUid: String? = null
    for (row in rows.subList(0, gap).asReversed()) {
        if (row.depth < depth) {
            parentUid = row.node.uid
            break
        }
        if (row.depth == depth && afterUid == null) afterUid = row.node.uid
    }
    return DropTarget(depth, parentUid, afterUid)
}

/**
 * A task being dragged in the list of open tasks. While it is held, the list shows [remaining] (everything but
 * the task and its subtasks) with a gap of [rowHeight] before `remaining[gap]`, and the task itself floats
 * under the finger, [dx] to the side of where it started.
 */
internal class TaskDrag(
    val row: VisibleRow,
    val remaining: List<VisibleRow>,
    private val startGap: Int,
    val rowHeight: Int,
    /** Where in the row the finger took hold of it. */
    private val grabOffset: Float,
    /** Where the finger picked the task up, in list coordinates. */
    val startY: Float,
    private val indentPx: Float,
) {
    var pointerY by mutableFloatStateOf(startY)
    var dx by mutableFloatStateOf(0f)
    var gap by mutableIntStateOf(startGap)
        private set

    /** The top of the floating row, in list coordinates. */
    val top: Float get() = pointerY - grabOffset

    val target: DropTarget by derivedStateOf { dropTarget(remaining, gap, row.depth + (dx / indentPx).roundToInt()) }

    /** False while the task would land where it already is. */
    val moves: Boolean get() = gap != startGap || target.depth != row.depth

    private val indexOf = remaining.withIndex().associate { it.value.node.uid to it.index }

    /**
     * Moves the gap to where the floating row is. Rows are measured as if there were no gap, so the result does
     * not depend on where the gap is now and cannot flip back and forth.
     */
    fun followPointer(layout: LazyListLayoutInfo) {
        val shown = layout.visibleItemsInfo.filter { it.key in indexOf }
        val first = shown.firstOrNull() ?: return
        val center = top + rowHeight / 2f
        val above = shown.count { item ->
            val offset = if (indexOf.getValue(item.key as String) >= gap) item.offset - rowHeight else item.offset
            offset + item.size / 2f < center
        }
        gap = indexOf.getValue(first.key as String) + above
    }
}

/**
 * Long-pressing a row of the list picks it up: [onStart] gets where the finger is, in list coordinates, and
 * returns the drag, or null if that row cannot be moved. The finger then moves it until it is lifted ([onDrop]),
 * or the gesture is interrupted ([onCancel]). Taps and scrolling work as before.
 */
internal fun Modifier.longPressDragging(
    enabled: Boolean,
    onStart: State<(Offset) -> TaskDrag?>,
    onDrop: State<(TaskDrag) -> Unit>,
    onCancel: State<() -> Unit>,
): Modifier = if (!enabled) this else pointerInput(Unit) {
    awaitEachGesture {
        // Rows have buttons that take the press, so a press they have handled counts too.
        val down = awaitFirstDown(requireUnconsumed = false)
        val longPress = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
        val drag = onStart.value(longPress.position) ?: return@awaitEachGesture
        longPress.consume()
        while (true) {
            // Taken before the list and the row's buttons see it, so neither scrolls nor clicks.
            val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == longPress.id }
            if (change == null) break
            drag.pointerY = change.position.y
            drag.dx += change.positionChange().x
            change.consume()
            if (!change.pressed) {
                onDrop.value(drag)
                return@awaitEachGesture
            }
        }
        onCancel.value()
    }
}

/** While [drag] is held, scrolls the list when the finger nears its top or bottom edge, and keeps the gap under the finger. */
@Composable
internal fun FollowDrag(drag: TaskDrag?, listState: LazyListState) {
    val edgePx = with(LocalDensity.current) { AUTO_SCROLL_EDGE.toPx() }
    val maxStepPx = with(LocalDensity.current) { AUTO_SCROLL_MAX_STEP.toPx() }
    LaunchedEffect(drag) {
        val held = drag ?: return@LaunchedEffect
        while (true) {
            withFrameNanos { }
            val height = listState.layoutInfo.viewportSize.height
            // Faster the closer to the edge: up to maxStepPx per frame at the edge itself. A task picked up near an
            // edge only starts the scrolling once it is moved closer to it.
            val topZone = minOf(edgePx, held.startY)
            val bottomZone = minOf(edgePx, height - held.startY)
            val step = when {
                held.pointerY < topZone -> -maxStepPx * (1 - held.pointerY.coerceAtLeast(0f) / topZone)
                held.pointerY > height - bottomZone -> maxStepPx * (1 - (height - held.pointerY).coerceAtLeast(0f) / bottomZone)
                else -> 0f
            }
            if (step != 0f) listState.scrollBy(step)
            val gap = held.gap
            held.followPointer(listState.layoutInfo)
            // The list keeps its first visible item in place, and that may be the gap: moving it would scroll the
            // list along, which moves the gap again. Holding the list where it is by position stops that.
            if (held.gap != gap) listState.requestScrollToItem(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        }
    }
}

private val AUTO_SCROLL_EDGE = 64.dp
private val AUTO_SCROLL_MAX_STEP = 12.dp
