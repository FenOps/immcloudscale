package app.immcloudscale

import android.app.Application
import androidx.work.Configuration
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.immcloudscale.data.AppSettings
import app.immcloudscale.data.GoogleReminderWorker
import app.immcloudscale.data.HomeGuard
import app.immcloudscale.data.HomeRoute
import app.immcloudscale.data.HttpException
import app.immcloudscale.data.NetworkMonitor
import app.immcloudscale.data.SettingsStore
import app.immcloudscale.data.ThumbnailWorker
import app.immcloudscale.data.backup.BackupWorker
import app.immcloudscale.data.buildHttpClient
import app.immcloudscale.data.immich.ImmichClient
import app.immcloudscale.data.immich.TimelineCache
import app.immcloudscale.data.nextcloud.NextcloudClient
import app.immcloudscale.data.pairing.MembersClient
import app.immcloudscale.data.pairing.PairLink
import app.immcloudscale.data.pairing.Pairing
import app.immcloudscale.data.tailscale.TailscaleManager
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import coil3.video.VideoFrameDecoder
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.withIndex
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.io.File
import java.time.Instant

/** Hand-wired dependencies; small enough not to need a DI framework. */
class AppGraph(val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsStore(context)
    val settingsState: StateFlow<AppSettings> = settings.state
    val tailscale = TailscaleManager(context, settings, scope)
    val homeGuard: HomeGuard = HomeGuard({ settingsState.value })
    val http = buildHttpClient({ settingsState.value }, homeGuard, object : HomeRoute {
        override fun proxy() = tailscale.proxy()
        override fun token() = tailscale.proxyToken()
        override fun extraHosts() = listOfNotNull(tailscale.inviteServer)
        override fun onlyExtraHosts() = tailscale.inviting
    })
    val immich = ImmichClient(http) { settingsState.value.immichUrl }
    val nextcloud = NextcloudClient(http, { settingsState.value.ncUrl }, { settingsState.value.let { it.ncUserId.ifBlank { it.ncUser } } })
    val network = NetworkMonitor(context, settings, scope, { key -> homeGuard.networkKey = key }) { home, info ->
        info?.let(tailscale::learnServerIp)
        // Connections made on the old network (direct, or through Tailscale) mustn't be reused on the new one.
        http.connectionPool.evictAll()
        tailscale.onNetwork(home)
    }
    init {
        // When Tailscale connects or stops, connections made the other way mustn't be reused.
        tailscale.state.map { it.status }.distinctUntilChanged().onEach { http.connectionPool.evictAll() }.launchIn(scope)
    }
    val offline = OfflineStore(context)
    val timelineCache = TimelineCache(context.noBackupFilesDir)

    /** Forgets what the phone keeps of the photo library: the saved timeline and the cached thumbnails. */
    suspend fun forgetLibrary() {
        timelineCache.clear()
        app.immcloudscale.ui.photos.forgetPrefetched()
        val loader = coil3.SingletonImageLoader.get(context)
        loader.memoryCache?.clear()
        withContext(Dispatchers.IO) { loader.diskCache?.clear() }
    }
    val members = MembersClient(http) { settingsState.value }

    /** Pairs with an ImmCloudScale server, which answers with Immich, Nextcloud and remote access settings for this phone. */
    suspend fun pairWithServer(server: String, code: String) {
        val name = listOf(android.os.Build.MANUFACTURER.replaceFirstChar { it.uppercase() }, android.os.Build.MODEL)
            .distinct().joinToString(" ").take(60)
        val result = Pairing.pair(http, server, code, name, settingsState.value.pairedDeviceId)
        settings.update { Pairing.apply(it, result, server) }
    }

    /**
     * Pairs from a link or invite. Away from home an invite carries the server's Tailscale details: the app
     * connects through Tailscale first, then pairs through it. [onSignIn] opens Tailscale's sign-in page when needed.
     */
    suspend fun pairWith(link: PairLink, onSignIn: (String) -> Unit = {}) {
        if (link.expired()) throw IOException("This invite has expired. Ask for a new one.")
        val server = Pairing.normalizeServer(link.server)
        // Nothing from the link is saved: the Tailscale details are used for this attempt only, and pairing
        // brings the server's own settings.
        if (link.tailscaleIp.isBlank() || tailscale.answersDirectly(server)) return pairWithServer(server, link.code)
        tailscale.connectForInvite(server, link.tailscaleIp, link.tailscaleKey, link.controlUrl, onSignIn)
        try {
            pairWithServer(server, link.code)
        } catch (e: HttpException) {
            // The "same Wi-Fi" advice for unreachable servers doesn't fit here.
            if (e.code == 0) throw IOException("Connected to Tailscale, but your server didn't answer through it. Ask whoever sent the invite to check the server.")
            throw e
        } finally {
            tailscale.inviteDone()
        }
    }

    /** Uploads a shared/picked file: photos & videos to Immich, anything else to Nextcloud [ncFolder]. */
    suspend fun uploadUri(uri: Uri, ncFolder: String = "/", forceFiles: Boolean = false) {
        val cr = context.contentResolver
        var name = "upload"; var size = -1L
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) { name = c.getString(0) ?: name; size = if (c.isNull(1)) -1 else c.getLong(1) }
        }
        val mime = cr.getType(uri) ?: "application/octet-stream"
        val body = object : RequestBody() {
            override fun contentType() = mime.toMediaTypeOrNull()
            override fun contentLength() = size
            override fun writeTo(sink: BufferedSink) { cr.openInputStream(uri)!!.source().use { sink.writeAll(it) } }
        }
        val media = mime.startsWith("image/") || mime.startsWith("video/")
        val s = settingsState.value
        if (media && s.immichConfigured && !forceFiles) {
            val now = Instant.now()
            immich.upload(body, name, "share-${uri.hashCode()}-$size", "immcloudscale", now, now, null)
        } else {
            nextcloud.upload(ncFolder.trimEnd('/') + "/" + name, body)
        }
    }
}

/** "Available offline" copies of Nextcloud files, kept in app storage. */
class OfflineStore(context: Context) {
    private val dir = File(context.filesDir, "offline").apply { mkdirs() }
    fun fileFor(path: String): File {
        val f = File(dir, path.trimStart('/'))
        // Paths come from the server; never let one point outside the offline folder.
        require(f.canonicalPath.startsWith(dir.canonicalPath + File.separator)) { "Unsafe path: $path" }
        return f
    }
    fun has(path: String) = fileFor(path).exists()
    fun remove(path: String) { fileFor(path).delete() }
    fun all(): List<Pair<String, File>> = dir.walkTopDown().filter { it.isFile && !it.name.endsWith(".part") }
        .map { "/" + it.relativeTo(dir).path to it }.toList()
}

class ImmApp : Application(), SingletonImageLoader.Factory, Configuration.Provider {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.network.start()
        graph.scope.launch { graph.settings.dropRetired() }
        // Reschedule backup with the settings that changed (not a separately collected copy, which can lag).
        graph.settingsState
            .distinctUntilChanged { a, b ->
                a.backupEnabled == b.backupEnabled && a.immichConfigured == b.immichConfigured &&
                    a.backupWifiOnly == b.backupWifiOnly && a.backupChargingOnly == b.backupChargingOnly &&
                    a.backupDays == b.backupDays && a.backupTime == b.backupTime
            }
            .withIndex()
            // At start-up there's nothing to cancel when backup is off, so WorkManager isn't touched then.
            .onEach { (i, s) -> if (i > 0 || s.backupEnabled) BackupWorker.schedule(this, s) }
            .launchIn(graph.scope)
        // Another account or server (or signing out): the saved timeline and the thumbnails of the old one go.
        graph.settingsState
            .distinctUntilChanged { a, b -> a.immichUrl == b.immichUrl && a.immichEmail == b.immichEmail && a.immichUserName == b.immichUserName }
            .withIndex()
            .onEach { (i, _) -> if (i > 0) graph.forgetLibrary() }
            .launchIn(graph.scope)
        // Thumbnails downloaded ahead on Wi-Fi while charging, while wanted (see ThumbnailWorker).
        graph.settingsState
            .distinctUntilChanged { a, b -> a.keepThumbnails == b.keepThumbnails && a.immichConfigured == b.immichConfigured }
            .withIndex()
            .onEach { (i, s) -> if (i > 0 || (s.keepThumbnails && s.immichConfigured)) ThumbnailWorker.update(this, s) }
            .launchIn(graph.scope)
        // The reminder to switch off Google Photos backup, while it applies (see GoogleReminderWorker).
        graph.settingsState
            .distinctUntilChanged { a, b -> GoogleReminderWorker.due(a) == GoogleReminderWorker.due(b) }
            .withIndex()
            .onEach { (i, s) -> if (i > 0 || GoogleReminderWorker.due(s)) GoogleReminderWorker.update(this, s) }
            .launchIn(graph.scope)
    }

    /** On-demand WorkManager initialisation (the startup initializer is removed in the manifest). */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setMinimumLoggingLevel(android.util.Log.INFO).build()

    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components {
            add(OkHttpNetworkFetcherFactory(callFactory = { graph.http }))
            add(VideoFrameDecoder.Factory())
        }
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.25).build() }
        // Room for the whole library's thumbnails on most phones, without crowding a nearly full one.
        .diskCache {
            DiskCache.Builder().directory(context.cacheDir.resolve("thumbs"))
                .maxSizePercent(0.2).minimumMaxSizeBytes(256L shl 20).maximumMaxSizeBytes(2L shl 30).build()
        }
        .crossfade(true)
        .build()
}

val Context.graph: AppGraph get() = (applicationContext as ImmApp).graph
