package app.immcloudscale.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlin.math.roundToInt

/**
 * Google Photos' scroll handle: appears on the right while the grid scrolls and can be dragged to jump through
 * everything loaded so far, showing the month being passed. [label] names the item with the given key.
 */
@Composable
fun FastScroller(state: LazyGridState, label: (Any?) -> String?, modifier: Modifier = Modifier) {
    val currentLabel by rememberUpdatedState(label) // the grid's months change as photos load
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var shown by remember { mutableStateOf(false) }
    // Only worth having when there's a lot more to scroll than fits on the screen.
    val worthIt by remember {
        derivedStateOf { state.layoutInfo.visibleItemsInfo.size.let { v -> v > 0 && state.layoutInfo.totalItemsCount > v * 3 } }
    }
    // Where the handle sits, 0 to 1. Dragging and scrolling use the same scale, so it stays where it's let go.
    val scrollable by remember { derivedStateOf { (state.layoutInfo.totalItemsCount - state.layoutInfo.visibleItemsInfo.size).coerceAtLeast(1) } }
    val fraction by remember {
        derivedStateOf { if (dragging) dragFraction else (state.firstVisibleItemIndex.toFloat() / scrollable).coerceIn(0f, 1f) }
    }
    val current by remember { derivedStateOf { currentLabel(state.layoutInfo.visibleItemsInfo.firstOrNull()?.key) } }
    // Shown while scrolling or dragging, then hidden after a moment of stillness.
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress || dragging }.collectLatest { busy ->
            if (busy) shown = true else { delay(1500); shown = false }
        }
    }
    LaunchedEffect(state) {
        snapshotFlow { if (dragging) dragFraction else null }.collectLatest { f ->
            if (f != null && state.layoutInfo.totalItemsCount > 0) state.scrollToItem((f * scrollable).roundToInt())
        }
    }
    // A tick for each new month passed while dragging, since the thumb hides the label.
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(state) {
        snapshotFlow { if (dragging) current else null }.filterNotNull().distinctUntilChanged().drop(1)
            .collect { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    }
    val alpha = animateFloatAsState(if (shown && worthIt) 1f else 0f, label = "scroller")
    val visible by remember { derivedStateOf { alpha.value > 0f || dragging } }
    if (!visible) return

    BoxWithConstraints(modifier.fillMaxHeight().graphicsLayer { this.alpha = alpha.value }) {
        val handle = 48.dp
        val travel = with(LocalDensity.current) { (maxHeight - handle).toPx().coerceAtLeast(1f) }
        val currentTravel by rememberUpdatedState(travel)
        Row(
            // Read while placing, so scrolling moves the handle without recomposing it.
            Modifier.align(Alignment.TopEnd).offset { IntOffset(0, (fraction * travel).roundToInt()) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimatedVisibility(dragging && current != null, enter = fadeIn(), exit = fadeOut()) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shadowElevation = 3.dp,
                    modifier = Modifier.padding(end = 8.dp),
                ) {
                    Text(current.orEmpty(), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                }
            }
            Surface(
                shape = RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                shadowElevation = 3.dp,
                modifier = Modifier.size(width = 48.dp, height = handle)
                    .systemGestureExclusion() // the edge's back gesture mustn't take the drag
                    .semantics { contentDescription = "Scroll handle" }
                    .pointerInput(Unit) {
                        try {
                            detectVerticalDragGestures(
                                onDragStart = { dragFraction = fraction; dragging = true },
                                onDragEnd = { dragging = false },
                                onDragCancel = { dragging = false },
                            ) { change, dy ->
                                change.consume()
                                dragFraction = (dragFraction + dy / currentTravel).coerceIn(0f, 1f)
                            }
                        } finally {
                            dragging = false // also when the gesture is cut short
                        }
                    },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.UnfoldMore, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
        }
    }
}
