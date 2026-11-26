package app.immcloudscale.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.immcloudscale.data.AppSettings
import app.immcloudscale.data.immich.AlbumDto
import app.immcloudscale.data.immich.AssetDto
import app.immcloudscale.data.immich.ExifInfo
import app.immcloudscale.data.immich.MemoryData
import app.immcloudscale.data.immich.MemoryDto
import app.immcloudscale.data.immich.PersonDto
import app.immcloudscale.data.immich.ServerStorage
import app.immcloudscale.data.immich.groupByDay
import app.immcloudscale.data.nextcloud.CloudFile
import app.immcloudscale.data.nextcloud.Quota
import app.immcloudscale.data.tailscale.TsState
import app.immcloudscale.data.tailscale.TsStatus
import app.immcloudscale.ui.account.AccountCard
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import app.immcloudscale.ui.account.BackupWhereStatus
import app.immcloudscale.ui.collections.CollectionsContent
import app.immcloudscale.ui.collections.CollectionsState
import app.immcloudscale.ui.files.FilesActions
import app.immcloudscale.ui.files.FilesContent
import app.immcloudscale.ui.files.FilesState
import app.immcloudscale.ui.network.NetworkActions
import app.immcloudscale.ui.network.NetworkContent
import app.immcloudscale.ui.network.RemoteAccessActions
import app.immcloudscale.ui.network.RemoteAccessCard
import app.immcloudscale.ui.network.NetworkUi
import app.immcloudscale.ui.network.Reach
import app.immcloudscale.ui.network.ServiceCheck
import app.immcloudscale.ui.photos.AssetGrid
import app.immcloudscale.ui.photos.BackupIndicator
import app.immcloudscale.ui.photos.MemoriesRow
import app.immcloudscale.ui.photos.PhotosTopBar
import app.immcloudscale.ui.theme.ImmTheme
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

/** Renders the main screens with sample data; images are placeholder gradients (no network in tests). */
class ScreenshotTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6, maxPercentDifference = 0.5)

    private val now = System.currentTimeMillis()
    private val today = LocalDate.now()
    private fun day(daysAgo: Long, hour: Int) = today.minusDays(daysAgo).atTime(hour, 0).toString() + ":00.000Z"

    private val assets = buildList {
        var n = 0
        listOf(0L to 5, 1L to 7, 3L to 4, 9L to 9, 40L to 6).forEach { (ago, count) ->
            repeat(count) {
                n++
                add(
                    AssetDto(
                        id = "asset-$n", type = if (n % 7 == 0) "VIDEO" else "IMAGE",
                        fileCreatedAt = day(ago, 10), localDateTime = day(ago, 10),
                        isFavorite = n % 5 == 0, duration = if (n % 7 == 0) "0:00:${10 + n}.000" else null,
                        exifInfo = when (ago) { 1L -> ExifInfo(city = "Annecy", state = "Haute-Savoie"); 9L -> ExifInfo(city = "Lisbon", country = "Portugal"); else -> null },
                    ),
                )
            }
        }
    }

    private val settings = AppSettings(
        immichUrl = "http://nas.lan:2283", immichToken = "t", immichUserName = "Camille Martin", immichEmail = "camille@home.lan",
        ncUrl = "https://cloud.home.lan", ncUser = "camille", ncAppPassword = "p",
        pairedServer = "http://nas.lan:8790", pairedServerId = "ics_home", tsServerIp = "100.101.102.103", tsEnabled = true,
        backupEnabled = true, lastBackupAt = now - 5 * 60_000,
    )

    @Composable
    private fun Frame(tab: Tab, content: @Composable (PaddingValues) -> Unit) = ImmTheme(dark = false, dynamic = false) {
        Scaffold(bottomBar = { BottomBar(tab) {} }) { pad -> content(PaddingValues(bottom = pad.calculateBottomPadding())) }
    }

    @Test fun photos() = paparazzi.snapshot {
        Frame(Tab.Photos) { pad ->
            Scaffold(topBar = { PhotosTopBar("Camille Martin", BackupIndicator.Running, 0.6f, {}, {}, {}, {}) }) { inner ->
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                    AssetGrid(
                        sections = groupByDay(assets), columns = 4, selected = emptySet(), thumbUrl = { null },
                        onOpen = {}, onToggle = {}, onSelectDay = {}, onColumnsChange = {},
                        contentPadding = PaddingValues(top = inner.calculateTopPadding(), bottom = pad.calculateBottomPadding()),
                        header = {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                MemoriesRow(
                                    listOf(1, 2, 4, 6).map { MemoryDto("m$it", data = MemoryData(today.year - it), assets = listOf(assets[it * 3])) },
                                    { null },
                                ) {}
                            }
                        },
                    )
                }
            }
        }
    }

    @Test fun photosSelecting() = paparazzi.snapshot {
        ImmTheme(dark = false, dynamic = false) {
            Scaffold(topBar = {
                app.immcloudscale.ui.photos.SelectionTopBar(3, app.immcloudscale.data.immich.AssetQuery.Timeline, {}, {}, {}, {}, {}, {}, {}, {})
            }) { inner ->
                AssetGrid(
                    sections = groupByDay(assets), columns = 3, selected = setOf("asset-2", "asset-3", "asset-7"), thumbUrl = { null },
                    onOpen = {}, onToggle = {}, onSelectDay = {}, onColumnsChange = {},
                    contentPadding = PaddingValues(top = inner.calculateTopPadding()),
                )
            }
        }
    }

    @Test fun collections() = paparazzi.snapshot {
        Frame(Tab.Collections) { pad ->
            CollectionsContent(
                CollectionsState(
                    albums = listOf(
                        AlbumDto("a1", "Summer in Lisbon", "asset-20", 142), AlbumDto("a2", "Hiking", "asset-12", 58),
                        AlbumDto("a3", "Family", "asset-5", 311, shared = true), AlbumDto("a4", "Recipes", null, 0),
                    ),
                    people = listOf("Léa", "Hugo", "", "Mamie", "Tom").mapIndexed { i, n -> PersonDto("p$i", n) },
                    places = listOf("Annecy", "Lisbon", "Paris", "Lyon").mapIndexed { i, c -> AssetDto("pl$i", exifInfo = ExifInfo(city = c)) },
                    loading = false,
                ),
                {}, {}, {}, "Camille Martin", { null }, { null }, {}, pad,
            )
        }
    }

    private fun file(path: String, folder: Boolean = false, size: Long = 0, type: String? = null, fav: Boolean = false, shared: Boolean = false, daysAgo: Int = 1) =
        CloudFile(path, path.substringAfterLast('/'), folder, size, now - daysAgo * 86_400_000L, type, 1, fav, false, shared)

    @Test fun files() = paparazzi.snapshot {
        Frame(Tab.Files) { pad ->
            FilesContent(
                FilesState(
                    items = listOf(
                        file("/Documents", folder = true, daysAgo = 2), file("/Family", folder = true, shared = true, daysAgo = 12),
                        file("/Music", folder = true, daysAgo = 90),
                        file("/Tax return 2025.pdf", size = 1_204_000, type = "application/pdf", fav = true, daysAgo = 3),
                        file("/Budget.xlsx", size = 48_200, daysAgo = 5), file("/Trip plan.docx", size = 22_000, shared = true, daysAgo = 8),
                        file("/Wedding slides.pptx", size = 8_400_000, daysAgo = 30),
                        file("/router-backup.tar.gz", size = 52_000_000, daysAgo = 60),
                        file("/notes.md", size = 3_100, type = "text/markdown", daysAgo = 0),
                    ),
                    offlinePaths = setOf("/Tax return 2025.pdf"),
                ),
                grid = false, accountName = "Camille Martin", a = FilesActions(), previewUrl = { null }, bottomPadding = pad,
            )
        }
    }

    @Test fun network() = paparazzi.snapshot {
        Frame(Tab.Network) { pad ->
            NetworkContent(
                settings,
                NetworkUi(
                    services = listOf(
                        ServiceCheck("Immich", settings.immichUrl, Reach.Up, 38),
                        ServiceCheck("Nextcloud", settings.ncUrl, Reach.Up, 52),
                    ),
                ),
                NetworkActions(), bottomPadding = pad,
            ) { RemoteAccessCard(TsState(TsStatus.Connected, ip = "100.64.0.9"), settings, atHome = false, RemoteAccessActions()) }
        }
    }

    /** The Photos page's backup status as it follows the connection: home, away and connected, away and Remote access off. */
    @Test fun backupStatus() = paparazzi.snapshot {
        ImmTheme(dark = false, dynamic = false) {
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                BackupWhereStatus(settings, atHome = true, TsState(TsStatus.Off), {}, compact = true)
                BackupWhereStatus(settings, atHome = false, TsState(TsStatus.Connected), {}, compact = true)
                BackupWhereStatus(settings, atHome = false, TsState(TsStatus.Starting), {})
                BackupWhereStatus(settings.copy(tsEnabled = false), atHome = false, TsState(TsStatus.Off), {}, compact = true)
            }
        }
    }

    @Test fun account() = paparazzi.snapshot {
        ImmTheme(dark = false, dynamic = false) {
            Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0x66000000)), contentAlignment = Alignment.Center) {
                AccountCard(
                    settings,
                    immichStorage = ServerStorage("8 TiB", "2.3 TiB", "5.7 TiB", 28.7),
                    quota = Quota(312_000_000_000, 688_000_000_000), {}, {}, {}, {},
                )
            }
        }
    }

    @Test fun filesDark() = paparazzi.snapshot {
        ImmTheme(dark = true, dynamic = false) {
            Scaffold(bottomBar = { BottomBar(Tab.Files) {} }) { pad ->
                FilesContent(
                    FilesState(items = listOf(file("/Photos 2019", folder = true), file("/Invoice 042.pdf", size = 98_000, fav = true), file("/server.yml", size = 900))),
                    grid = true, accountName = "Camille Martin", a = FilesActions(), previewUrl = { null },
                    bottomPadding = PaddingValues(bottom = pad.calculateBottomPadding()),
                )
            }
        }
    }
}
