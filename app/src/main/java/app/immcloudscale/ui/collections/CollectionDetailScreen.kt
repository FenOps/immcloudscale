package app.immcloudscale.ui.collections

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.immcloudscale.data.immich.AssetQuery
import app.immcloudscale.graph
import app.immcloudscale.ui.components.RemoteImage
import app.immcloudscale.ui.components.SectionHeader
import app.immcloudscale.ui.components.graphViewModel
import app.immcloudscale.ui.photos.AssetBrowser
import app.immcloudscale.ui.photos.AssetListViewModel

fun CollectionTarget.query(): AssetQuery = when (this) {
    CollectionTarget.Favorites -> AssetQuery.Favorites
    CollectionTarget.Archive -> AssetQuery.Archive
    CollectionTarget.Trash -> AssetQuery.Trash
    CollectionTarget.Videos -> AssetQuery.Videos
    is CollectionTarget.Album -> AssetQuery.Album(album.id)
    is CollectionTarget.Person -> AssetQuery.Person(person.id)
    is CollectionTarget.Place -> AssetQuery.Place(city)
    // Screens of their own, never shown as a plain photo list.
    CollectionTarget.Duplicates, CollectionTarget.LockedFolder -> AssetQuery.Timeline
}

fun AssetQuery.key(): String = when (this) {
    AssetQuery.Timeline -> "timeline"
    AssetQuery.Favorites -> "favorites"
    AssetQuery.Archive -> "archive"
    AssetQuery.Trash -> "trash"
    AssetQuery.Videos -> "videos"
    is AssetQuery.Album -> "album:$id"
    is AssetQuery.Person -> "person:$id"
    is AssetQuery.Place -> "place:$city"
    is AssetQuery.Smart -> "smart:$text"
}

/** A photo grid for one album / person / place / utility, with its own app bar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionDetailScreen(query: AssetQuery, title: String, onBack: () -> Unit, onOpenViewer: () -> Unit) {
    val vm = graphViewModel(query.key()) { AssetListViewModel(it, query) }
    val st by vm.state.collectAsStateWithLifecycle()
    val (emptyTitle, emptyBody) = when (query) {
        AssetQuery.Favorites -> "No favorites yet" to "Tap the heart on a photo to add it here"
        AssetQuery.Archive -> "Nothing archived" to "Archive photos to hide them from your Photos view without deleting them"
        AssetQuery.Trash -> "Trash is empty" to "Items in trash are permanently deleted by your server after its retention period"
        is AssetQuery.Smart -> "No results" to "Try a different word, like \"beach\", \"dog\" or \"birthday\""
        else -> "Nothing here yet" to "Add photos to see them here"
    }
    AssetBrowser(
        vm = vm,
        onOpenViewer = onOpenViewer,
        emptyTitle = emptyTitle,
        emptyBody = emptyBody,
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = {
                    Column {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (st.assets.isNotEmpty()) Text(
                            "${st.assets.size}${if (st.endReached) "" else "+"} items",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    if (query == AssetQuery.Trash && st.assets.isNotEmpty()) TextButton(onClick = vm::emptyTrash) { Text("Empty trash") }
                },
            )
        },
    )
}

/** Google Photos search: a pill field, then People, Places and categories as starting points. */
@Composable
fun SearchScreen(onBack: () -> Unit, onSearch: (String) -> Unit, onOpen: (CollectionTarget) -> Unit) {
    val g = LocalContext.current.graph
    val vm = graphViewModel { CollectionsViewModel(it) }
    val st by vm.state.collectAsStateWithLifecycle()
    var text by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            TextField(
                value = text, onValueChange = { text = it },
                placeholder = { Text("Search your photos") },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (text.isNotBlank()) onSearch(text.trim()) }),
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
        }
        val settings by LocalContext.current.graph.settingsState.collectAsStateWithLifecycle()
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            if (!settings.serverMachineLearning) item {
                Text(
                    "Searching by what's in a photo (\"dog on the beach\") and finding people need face recognition and smart search, " +
                        "which are off on your server. An admin can switch them on in Account → Members.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            if (st.people.isNotEmpty()) {
                item { SectionHeader("People") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(st.people, key = { it.id }) { p ->
                            Column(Modifier.width(72.dp).clickable { onOpen(CollectionTarget.Person(p)) }, horizontalAlignment = Alignment.CenterHorizontally) {
                                RemoteImage(g.immich.personThumbnailUrl(p.id), p.id, Modifier.size(72.dp).clip(CircleShape))
                                Text(p.name.ifBlank { "Add a name" }, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                            }
                        }
                    }
                }
            }
            if (st.places.isNotEmpty()) {
                item { SectionHeader("Places") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(st.places, key = { it.id }) { a ->
                            val city = a.exifInfo?.city ?: ""
                            Column(Modifier.width(104.dp).clickable { onOpen(CollectionTarget.Place(city)) }) {
                                RemoteImage(g.immich.thumbnailUrl(a.id), a.id, Modifier.size(104.dp).clip(RoundedCornerShape(16.dp)))
                                Text(city, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                            }
                        }
                    }
                }
            }
            item { SectionHeader("Categories") }
            item {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip({ onOpen(CollectionTarget.Favorites) }, { Text("Favorites") }, leadingIcon = { Icon(Icons.Outlined.FavoriteBorder, null) })
                    AssistChip({ onOpen(CollectionTarget.Videos) }, { Text("Videos") }, leadingIcon = { Icon(Icons.Outlined.VideoLibrary, null) })
                    AssistChip({ onOpen(CollectionTarget.Archive) }, { Text("Archive") }, leadingIcon = { Icon(Icons.Outlined.Archive, null) })
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
            item {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip({ onOpen(CollectionTarget.Trash) }, { Text("Trash") }, leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) })
                }
            }
            item {
                Text(
                    "Smart search finds things in your photos, like \"sunset at the beach\", using your Immich server's machine learning.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            }
        }
    }
}
