package app.immcloudscale.ui.photos

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.immcloudscale.data.immich.AssetDto
import app.immcloudscale.data.immich.DaySection
import app.immcloudscale.data.immich.localDate
import app.immcloudscale.ui.components.RemoteImage

/**
 * The Google Photos grid: day headers, square tiles with 2 dp gutters, long-press to select,
 * tap a date to select the whole day, pinch to change the number of columns.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AssetGrid(
    sections: List<DaySection>,
    columns: Int,
    selected: Set<String>,
    thumbUrl: (AssetDto) -> String?,
    onOpen: (AssetDto) -> Unit,
    onToggle: (AssetDto) -> Unit,
    onSelectDay: (DaySection) -> Unit,
    onColumnsChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    loadingMore: Boolean = false,
    onLoadMore: () -> Unit = {},
    state: LazyGridState = rememberLazyGridState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    header: (LazyGridScope.() -> Unit)? = null,
) {
    val selecting = selected.isNotEmpty()
    val currentColumns by rememberUpdatedState(columns)
    val nearEnd by remember {
        derivedStateOf {
            val info = state.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 30
        }
    }
    LaunchedEffect(nearEnd, sections.sumOf { it.assets.size }) { if (nearEnd) onLoadMore() }

    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = state,
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = modifier.fillMaxSize().pointerInput(Unit) {
            // Pinch with two fingers to zoom the grid in or out; single-finger scroll is untouched.
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                var zoom = 1f
                do {
                    val event = awaitPointerEvent()
                    if (event.changes.count { it.pressed } >= 2) {
                        zoom *= event.calculateZoom()
                        if (zoom > 1.35f && currentColumns > 2) { onColumnsChange(currentColumns - 1); zoom = 1f }
                        if (zoom < 0.74f && currentColumns < 7) { onColumnsChange(currentColumns + 1); zoom = 1f }
                        event.changes.forEach { it.consume() }
                    }
                } while (event.changes.any { it.pressed })
            }
        },
    ) {
        header?.invoke(this)
        sections.forEach { section ->
            item(key = "h-${section.date}", span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
                DayHeader(section, selecting, section.assets.all { it.id in selected }) { onSelectDay(section) }
            }
            items(section.assets, key = { it.id }, contentType = { "tile" }) { a ->
                AssetTile(
                    asset = a,
                    url = thumbUrl(a),
                    selecting = selecting,
                    selected = a.id in selected,
                    small = columns >= 6,
                    modifier = Modifier.animateItem().combinedClickable(
                        onClick = { if (selecting) onToggle(a) else onOpen(a) },
                        onLongClick = { onToggle(a) },
                    ),
                )
            }
        }
        if (loadingMore) item(span = { GridItemSpan(maxLineSpan) }, key = "more") {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
            }
        }
    }
}

@Composable
private fun DayHeader(section: DaySection, selecting: Boolean, allSelected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 10.dp)
            .combinedClickableCompat(onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) {
            SelectCircle(allSelected, Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(section.title, style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium))
        if (section.subtitle != null) {
            Text(
                "  ·  " + section.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onLongOrClick: () -> Unit) =
    this.then(Modifier.combinedClickable(onClick = onLongOrClick, onLongClick = onLongOrClick))

@Composable
fun SelectCircle(selected: Boolean, modifier: Modifier = Modifier) {
    if (selected) {
        Box(modifier.clip(CircleShape).background(Color.White)) {
            Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxSize())
        }
    } else {
        Box(modifier.clip(CircleShape).border(2.dp, MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
    }
}

@Composable
fun AssetTile(
    asset: AssetDto,
    url: String?,
    selecting: Boolean,
    selected: Boolean,
    modifier: Modifier = Modifier,
    small: Boolean = false,
) {
    val inset by animateDpAsState(if (selected) 14.dp else 0.dp, label = "inset")
    val label = (if (asset.isVideo) "Video" else "Photo") + ", " + asset.localDate() + (if (selected) ", selected" else "")
    Box(
        modifier.aspectRatio(1f).background(
            if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        ).semantics { contentDescription = label },
    ) {
        RemoteImage(
            url, asset.id,
            Modifier.fillMaxSize().padding(inset).clip(RoundedCornerShape(if (selected) 12.dp else 0.dp)),
        )
        if (asset.isVideo && !small) {
            Row(Modifier.align(Alignment.TopEnd).padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                asset.shortDuration?.let {
                    Text(it, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(end = 4.dp))
                }
                Icon(Icons.Filled.PlayCircle, null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
        if (asset.isFavorite && !small) {
            Icon(
                Icons.Filled.Favorite, null, tint = Color.White,
                modifier = Modifier.align(Alignment.BottomStart).padding(6.dp + inset).size(16.dp),
            )
        }
        if (selecting) {
            Box(Modifier.align(Alignment.TopStart).padding(6.dp)) {
                if (selected) SelectCircle(true, Modifier.size(22.dp))
                else Box(Modifier.size(22.dp).clip(CircleShape).border(2.dp, Color.White, CircleShape).background(Color(0x33000000)))
            }
        }
    }
}
