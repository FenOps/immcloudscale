package app.immcloudscale.ui.collections

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.PeopleOutline
import androidx.compose.material.icons.outlined.PhotoAlbum
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.immcloudscale.AppGraph
import app.immcloudscale.data.immich.AlbumDto
import app.immcloudscale.data.immich.AssetDto
import app.immcloudscale.data.immich.PersonDto
import app.immcloudscale.ui.components.LetterAvatar
import app.immcloudscale.ui.components.RemoteImage
import app.immcloudscale.ui.components.SectionHeader
import app.immcloudscale.ui.components.graphViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CollectionsState(
    val albums: List<AlbumDto> = emptyList(),
    val shared: List<AlbumDto> = emptyList(),
    val people: List<PersonDto> = emptyList(),
    val places: List<AssetDto> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

class CollectionsViewModel(private val g: AppGraph) : ViewModel() {
    private val _state = MutableStateFlow(CollectionsState())
    val state = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        if (!g.settingsState.value.immichConfigured) { _state.update { it.copy(loading = false) }; return }
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val albums = async { runCatching { g.immich.albums() } }
            val shared = async { runCatching { g.immich.sharedAlbums() } }
            val people = async { runCatching { g.immich.people() } }
            val places = async { runCatching { g.immich.places() } }
            val a = albums.await()
            _state.value = CollectionsState(
                albums = a.getOrDefault(emptyList()).filter { !it.shared },
                shared = shared.await().getOrDefault(emptyList()),
                people = people.await().getOrDefault(emptyList()).take(30),
                places = places.await().getOrDefault(emptyList()).filter { it.exifInfo?.city != null }.take(30),
                loading = false,
                error = a.exceptionOrNull()?.message,
            )
        }
    }

    fun createAlbum(name: String, onCreated: (AlbumDto) -> Unit) = viewModelScope.launch {
        runCatching { g.immich.createAlbum(name, emptyList()) }.onSuccess { refresh(); onCreated(it) }
    }
}

/** Where a collection tile leads. */
sealed interface CollectionTarget {
    data object Favorites : CollectionTarget
    data object Archive : CollectionTarget
    data object Trash : CollectionTarget
    data object Videos : CollectionTarget
    data object Duplicates : CollectionTarget
    data object LockedFolder : CollectionTarget
    data object PhoneOnly : CollectionTarget
    data object PhoneTrash : CollectionTarget
    data class Album(val album: AlbumDto) : CollectionTarget
    data class Person(val person: PersonDto) : CollectionTarget
    data class Place(val city: String) : CollectionTarget
}

@Composable
fun CollectionsScreen(
    onOpen: (CollectionTarget) -> Unit,
    onAccount: () -> Unit,
    accountName: String,
    thumb: (String) -> String?,
    personThumb: (String) -> String?,
    bottomPadding: PaddingValues,
) {
    val vm = graphViewModel { CollectionsViewModel(it) }
    val st by vm.state.collectAsStateWithLifecycle()
    var newAlbum by remember { mutableStateOf(false) }
    CollectionsContent(st, onOpen, { newAlbum = true }, onAccount, accountName, thumb, personThumb, vm::refresh, bottomPadding)
    if (newAlbum) NewAlbumDialog(onDismiss = { newAlbum = false }) { name ->
        newAlbum = false
        vm.createAlbum(name) { onOpen(CollectionTarget.Album(it)) }
    }
}

@Composable
fun NewAlbumDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New album") },
        text = { OutlinedTextField(name, { name = it }, label = { Text("Add a title") }, singleLine = true) },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onCreate(name.trim()) }) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionsContent(
    st: CollectionsState,
    onOpen: (CollectionTarget) -> Unit,
    onNewAlbum: () -> Unit,
    onAccount: () -> Unit,
    accountName: String,
    thumb: (String) -> String?,
    personThumb: (String) -> String?,
    onRefresh: () -> Unit,
    bottomPadding: PaddingValues,
) {
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Collections") },
            actions = {
                IconButton(onClick = onNewAlbum) { Icon(Icons.Outlined.Add, "New album") }
                IconButton(onClick = onAccount) { LetterAvatar(accountName, 32.dp) }
            },
        )
    }) { pad ->
        PullToRefreshBox(st.loading && st.albums.isNotEmpty(), onRefresh, Modifier.fillMaxSize().padding(top = pad.calculateTopPadding())) {
            LazyVerticalGrid(
                GridCells.Fixed(2),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = bottomPadding.calculateBottomPadding() + 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val quick = listOf(
                    Triple(Icons.Outlined.FavoriteBorder, "Favorites", CollectionTarget.Favorites),
                    Triple(Icons.Outlined.VideoLibrary, "Videos", CollectionTarget.Videos),
                    Triple(Icons.Outlined.Archive, "Archive", CollectionTarget.Archive),
                    Triple(Icons.Outlined.DeleteOutline, "Trash", CollectionTarget.Trash),
                    Triple(Icons.Outlined.ContentCopy, "Duplicates", CollectionTarget.Duplicates),
                    Triple(Icons.Outlined.Lock, "Locked folder", CollectionTarget.LockedFolder),
                    Triple(Icons.Outlined.PhoneAndroid, "On this phone only", CollectionTarget.PhoneOnly),
                    Triple(Icons.Outlined.RestoreFromTrash, "Phone trash", CollectionTarget.PhoneTrash),
                )
                items(quick, key = { it.second }) { (icon, label, target) -> QuickTile(icon, label) { onOpen(target) } }

                if (st.people.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "people") {
                        Column {
                            SectionHeader("People", start = 4.dp)
                            LazyRow(Modifier.bleed(12.dp), contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                items(st.people, key = { it.id }) { p ->
                                    Column(
                                        Modifier.width(76.dp).clickable { onOpen(CollectionTarget.Person(p)) },
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        RemoteImage(personThumb(p.id), p.id, Modifier.size(76.dp).clip(CircleShape))
                                        Text(
                                            p.name.ifBlank { "Add a name" }, style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp),
                                            color = if (p.name.isBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                if (st.places.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "places") {
                        Column {
                            SectionHeader("Places", start = 4.dp)
                            LazyRow(Modifier.bleed(12.dp), contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                items(st.places, key = { it.id }) { a ->
                                    val city = a.exifInfo?.city ?: ""
                                    Column(Modifier.width(110.dp).clickable { onOpen(CollectionTarget.Place(city)) }) {
                                        RemoteImage(thumb(a.id), a.id, Modifier.size(110.dp).clip(RoundedCornerShape(16.dp)))
                                        Text(city, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                                    }
                                }
                            }
                        }
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }, key = "albums-h") { SectionHeader("Albums", start = 4.dp) }
                item(key = "new-album") {
                    Column(Modifier.clickable(onClick = onNewAlbum)) {
                        Box(
                            Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Outlined.Add, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary) }
                        Text("New album", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
                    }
                }
                items(st.albums, key = { it.id }) { a -> AlbumTile(a, thumb) { onOpen(CollectionTarget.Album(a)) } }
                if (st.shared.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "shared-h") { SectionHeader("Shared albums", start = 4.dp) }
                    items(st.shared, key = { "s" + it.id }) { a -> AlbumTile(a, thumb) { onOpen(CollectionTarget.Album(a)) } }
                }
            }
        }
    }
}

/** Lets a horizontal row scroll to the screen edges past the grid's side padding. */
private fun Modifier.bleed(by: Dp) = layout { measurable, constraints ->
    val extra = (by * 2).roundToPx()
    val placeable = measurable.measure(constraints.copy(maxWidth = constraints.maxWidth + extra, minWidth = constraints.minWidth + extra))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-by.roundToPx(), 0) }
}

@Composable
private fun QuickTile(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer).clickable(onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 14.dp))
    }
}

@Composable
fun AlbumTile(a: AlbumDto, thumb: (String) -> String?, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        if (a.albumThumbnailAssetId != null) {
            RemoteImage(thumb(a.albumThumbnailAssetId), a.albumThumbnailAssetId, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp)))
        } else {
            Box(
                Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.PhotoAlbum, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.outline) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            Text(a.albumName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (a.shared) Icon(Icons.Outlined.PeopleOutline, "Shared", Modifier.padding(start = 4.dp).size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("${a.assetCount} items", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
