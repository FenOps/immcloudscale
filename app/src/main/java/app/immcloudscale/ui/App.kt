package app.immcloudscale.ui

import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.immcloudscale.data.immich.AssetQuery
import app.immcloudscale.graph
import app.immcloudscale.data.pairing.PairLink
import app.immcloudscale.ui.account.AccountDialog
import app.immcloudscale.ui.account.PairLinkDialog
import app.immcloudscale.ui.account.BackupScreen
import app.immcloudscale.ui.account.ServersScreen
import app.immcloudscale.ui.collections.CollectionDetailScreen
import app.immcloudscale.ui.collections.CollectionTarget
import app.immcloudscale.ui.collections.CollectionsScreen
import app.immcloudscale.ui.collections.NewAlbumDialog
import app.immcloudscale.ui.collections.SearchScreen
import app.immcloudscale.ui.files.FilePreviewScreen
import app.immcloudscale.ui.files.FilePreviewSession
import app.immcloudscale.ui.files.FilesScreen
import app.immcloudscale.ui.network.NetworkScreen
import app.immcloudscale.ui.photos.PhotosScreen
import app.immcloudscale.ui.photos.ViewerScreen
import kotlinx.coroutines.launch

enum class Tab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    Photos("Photos", Icons.Outlined.Photo, Icons.Filled.Photo),
    Collections("Collections", Icons.Outlined.PhotoLibrary, Icons.Filled.PhotoLibrary),
    Files("Files", Icons.Outlined.Folder, Icons.Filled.Folder),
    Network("Network", Icons.Outlined.Hub, Icons.Filled.Hub),
}

@Composable
fun BottomBar(current: Tab, onSelect: (Tab) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Tab.entries.forEach { t ->
            NavigationBarItem(
                selected = t == current,
                onClick = { onSelect(t) },
                icon = { Icon(if (t == current) t.selectedIcon else t.icon, null) },
                label = { Text(t.label) },
                colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.secondaryContainer),
            )
        }
    }
}

private fun NavHostController.openCollection(t: CollectionTarget) {
    val (kind, id, title) = when (t) {
        CollectionTarget.Favorites -> Triple("favorites", "", "Favorites")
        CollectionTarget.Archive -> Triple("archive", "", "Archive")
        CollectionTarget.Trash -> Triple("trash", "", "Trash")
        CollectionTarget.Videos -> Triple("videos", "", "Videos")
        is CollectionTarget.Album -> Triple("album", t.album.id, t.album.albumName)
        is CollectionTarget.Person -> Triple("person", t.person.id, t.person.name.ifBlank { "Unnamed person" })
        is CollectionTarget.Place -> Triple("place", t.city, t.city)
    }
    navigate("collection/$kind?id=${Uri.encode(id)}&title=${Uri.encode(title)}")
}

private fun queryFor(kind: String, id: String): AssetQuery = when (kind) {
    "favorites" -> AssetQuery.Favorites
    "archive" -> AssetQuery.Archive
    "trash" -> AssetQuery.Trash
    "videos" -> AssetQuery.Videos
    "album" -> AssetQuery.Album(id)
    "person" -> AssetQuery.Person(id)
    "place" -> AssetQuery.Place(id)
    "smart" -> AssetQuery.Smart(id)
    else -> AssetQuery.Timeline
}

@Composable
fun App(sharedUris: List<Uri>, onSharedHandled: () -> Unit, pairLink: PairLink? = null, onPairLinkHandled: () -> Unit = {}) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val nav = rememberNavController()
    val settings by g.settingsState.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.Photos) }
    var account by remember { mutableStateOf(false) }
    var newAlbum by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val start = remember { if (settings.immichConfigured || settings.nextcloudConfigured || settings.vpnConfigured) "main" else "setup" }

    NavHost(
        nav, start,
        enterTransition = { fadeIn() }, exitTransition = { fadeOut() },
        popEnterTransition = { fadeIn() }, popExitTransition = { fadeOut() },
    ) {
        composable("main") {
            Scaffold(bottomBar = { BottomBar(tab) { tab = it } }) { pad ->
                val bottom = PaddingValues(bottom = pad.calculateBottomPadding())
                val name = settings.immichUserName.ifBlank { settings.ncUser.ifBlank { "Me" } }
                when (tab) {
                    Tab.Photos -> PhotosScreen(
                        onOpenViewer = { nav.navigate("viewer") },
                        onSearch = { nav.navigate("search") },
                        onCreate = { newAlbum = true },
                        onAccount = { account = true },
                        onBackupSettings = { nav.navigate("backup") },
                        onSetup = { nav.navigate("servers") },
                        bottomPadding = bottom,
                    )
                    Tab.Collections -> CollectionsScreen(
                        onOpen = { nav.openCollection(it) },
                        onAccount = { account = true },
                        accountName = name,
                        thumb = { g.immich.thumbnailUrl(it) },
                        personThumb = { g.immich.personThumbnailUrl(it) },
                        bottomPadding = bottom,
                    )
                    Tab.Files -> FilesScreen(
                        onPreview = { FilePreviewSession.file = it; nav.navigate("file") },
                        onAccount = { account = true },
                        onSetup = { nav.navigate("servers") },
                        bottomPadding = bottom,
                    )
                    Tab.Network -> NetworkScreen(onAccount = { account = true }, bottomPadding = bottom)
                }
            }
        }
        composable("viewer") { ViewerScreen(onBack = { nav.popBackStack() }) }
        composable("search") {
            SearchScreen(
                onBack = { nav.popBackStack() },
                onSearch = { q -> nav.navigate("collection/smart?id=${Uri.encode(q)}&title=${Uri.encode(q)}") },
                onOpen = { nav.openCollection(it) },
            )
        }
        composable(
            "collection/{kind}?id={id}&title={title}",
            arguments = listOf(
                navArgument("kind") { type = NavType.StringType },
                navArgument("id") { type = NavType.StringType; defaultValue = "" },
                navArgument("title") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { e ->
            val a = e.arguments!!
            CollectionDetailScreen(
                query = queryFor(a.getString("kind")!!, a.getString("id")!!),
                title = a.getString("title")!!,
                onBack = { nav.popBackStack() },
                onOpenViewer = { nav.navigate("viewer") },
            )
        }
        composable("file") { FilePreviewScreen(onBack = { nav.popBackStack() }) }
        composable("backup") { BackupScreen(onBack = { nav.popBackStack() }) }
        composable("servers") {
            ServersScreen(onBack = { nav.popBackStack() }, onNetwork = { tab = Tab.Network; nav.popBackStack("main", false) }, onDone = {})
        }
        composable("setup") {
            val toMain = { nav.navigate("main") { popUpTo("setup") { inclusive = true } } }
            ServersScreen(onBack = null, onNetwork = { tab = Tab.Network; toMain() }, onDone = { toMain() })
        }
    }

    if (account) AccountDialog(
        onDismiss = { account = false },
        onServers = { nav.navigate("servers") },
        onBackup = { nav.navigate("backup") },
        onNetwork = { tab = Tab.Network },
    )
    if (newAlbum) NewAlbumDialog(onDismiss = { newAlbum = false }) { name ->
        newAlbum = false
        scope.launch {
            runCatching { g.immich.createAlbum(name, emptyList()) }
                .onSuccess { nav.openCollection(CollectionTarget.Album(it)) }
                .onFailure { Toast.makeText(ctx, it.message, Toast.LENGTH_LONG).show() }
        }
    }
    if (sharedUris.isNotEmpty()) SharedUploadDialog(sharedUris, onSharedHandled)
    if (pairLink != null) PairLinkDialog(pairLink) { paired ->
        onPairLinkHandled()
        if (paired) {
            tab = Tab.Photos
            nav.navigate("main") { popUpTo(0) { inclusive = true } }
            Toast.makeText(ctx, "Paired. Photos, files and the home VPN are ready.", Toast.LENGTH_LONG).show()
        }
    }
}

/** "Share to ImmCloudScale" from other apps: pick Photos (Immich) or Files (Nextcloud). */
@Composable
private fun SharedUploadDialog(uris: List<Uri>, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val s by g.settingsState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var progress by remember { mutableIntStateOf(-1) }
    fun upload(toFiles: Boolean) = scope.launch {
        var failed = 0
        uris.forEachIndexed { i, u ->
            progress = i
            runCatching { g.uploadUri(u, "/", forceFiles = toFiles) }.onFailure { failed++ }
        }
        Toast.makeText(ctx, if (failed == 0) "Uploaded ${uris.size} item(s)" else "$failed upload(s) failed", Toast.LENGTH_LONG).show()
        onDone()
    }
    AlertDialog(
        onDismissRequest = { if (progress < 0) onDone() },
        title = { Text(if (progress >= 0) "Uploading ${progress + 1} of ${uris.size}…" else "Upload ${uris.size} item${if (uris.size == 1) "" else "s"}") },
        text = { Text("Photos and videos can go to your Immich library; any file can go to your Nextcloud files.") },
        confirmButton = {
            if (progress < 0) TextButton(enabled = s.nextcloudConfigured, onClick = { upload(true) }) { Text("Files") }
        },
        dismissButton = {
            if (progress < 0) TextButton(enabled = s.immichConfigured, onClick = { upload(false) }) { Text("Photos") }
        },
        modifier = Modifier.navigationBarsPadding(),
    )
}
