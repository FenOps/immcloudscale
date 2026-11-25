package app.immcloudscale.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Everything the app remembers. Lives in app-private storage (backups are disabled in the manifest). */
data class AppSettings(
    val immichUrl: String = "",
    /** Session token from email/password login, or an API key. */
    val immichToken: String = "",
    val immichTokenIsApiKey: Boolean = false,
    val immichUserName: String = "",
    val immichEmail: String = "",

    val ncUrl: String = "",
    val ncUser: String = "",
    val ncAppPassword: String = "",
    /** Nextcloud's internal user ID (used in WebDAV paths); differs from ncUser when signing in with an email. */
    val ncUserId: String = "",

    val vpnConfig: String = "",
    /** Route all traffic through home (Tailscale "exit node"); otherwise only the NAS / LAN subnets. */
    val vpnFullTunnel: Boolean = false,
    val vpnExcludedApps: Set<String> = emptySet(),
    val vpnAutoConnect: Boolean = false,
    val vpnTrustedSsids: Set<String> = emptySet(),

    /** Remote access through Tailscale, run inside the app: connects by itself away from home. */
    val tsEnabled: Boolean = false,
    /** The server's Tailscale address, from pairing or the server's info page. */
    val tsServerIp: String = "",
    /** From pairing when the server has a key: joins the tailnet without signing in. */
    val tsAuthKey: String = "",
    val tsControlUrl: String = "",

    val backupEnabled: Boolean = false,
    val backupWifiOnly: Boolean = true,
    val backupChargingOnly: Boolean = false,
    val backupVideos: Boolean = true,
    val backupFolders: Set<String> = emptySet(),
    val backupSince: Long = 0L,
    val lastBackupAt: Long = 0L,
    /** Files the last backup run couldn't send (unsupported type, unreadable). */
    val backupSkipped: Int = 0,
    /** Back up away from home too (through Tailscale), not only on the home Wi-Fi. */
    val backupAnywhere: Boolean = false,
    /** Days to back up on, 1 (Monday) to 7; empty means whenever there's something new. */
    val backupDays: Set<Int> = emptySet(),
    /** Time of day for scheduled backups, in minutes after midnight. */
    val backupTime: Int = 2 * 60,

    /** "system", "light" or "dark". */
    val theme: String = "system",

    /** Phone copies (MediaStore IDs) of photos deleted in the library elsewhere, waiting to go to the phone's trash. */
    val phoneTrashPending: Set<Long> = emptySet(),
    /** When backup last looked for photos deleted elsewhere (epoch ms). */
    val trashSyncedAt: Long = 0L,
    /** After each backup, offer to remove backed-up photos older than [freeUpKeepDays] from the phone. */
    val freeUpAuto: Boolean = false,
    val freeUpKeepDays: Int = 30,
    /** Backed-up phone copies (MediaStore IDs) waiting to be removed to free space. */
    val freeUpPending: Set<Long> = emptySet(),

    val filesGrid: Boolean = false,
    val photoColumns: Int = 4,

    /** Set when the phone was paired with an ImmCloudScale server instead of configured by hand. */
    val pairedServer: String = "",
    val pairedServerName: String = "",
    val pairedDeviceId: String = "",
    /** The server's ID from pairing; checked before sending credentials on Wi-Fi without the VPN. */
    val pairedServerId: String = "",
    /** This phone's secret from pairing: signs member management requests (admins). */
    val deviceToken: String = "",
    val isAdmin: Boolean = false,

    /** A request to join a server, waiting for an admin (set before this phone is paired). */
    val joinServer: String = "",
    val joinId: String = "",
    val joinToken: String = "",
    val joinName: String = "",
    /** Immich session for the locked folder (from the server; API keys can't open it). */
    val lockedSession: String = "",
    /** Whether the server runs Immich's machine learning (faces, smart search, duplicates), from its info page. */
    val serverMachineLearning: Boolean = true,
) {
    val immichConfigured get() = immichUrl.isNotBlank() && immichToken.isNotBlank()
    val nextcloudConfigured get() = ncUrl.isNotBlank() && ncUser.isNotBlank() && ncAppPassword.isNotBlank()
    val vpnConfigured get() = vpnConfig.isNotBlank()
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("settings")

class SettingsStore(private val context: Context) {
    private object K {
        val immichUrl = stringPreferencesKey("immich_url")
        val immichToken = stringPreferencesKey("immich_token")
        val immichApiKey = booleanPreferencesKey("immich_api_key")
        val immichUserName = stringPreferencesKey("immich_user_name")
        val immichEmail = stringPreferencesKey("immich_email")
        val ncUrl = stringPreferencesKey("nc_url")
        val ncUser = stringPreferencesKey("nc_user")
        val ncPass = stringPreferencesKey("nc_pass")
        val ncUserId = stringPreferencesKey("nc_user_id")
        val vpnConfig = stringPreferencesKey("vpn_config")
        val vpnFull = booleanPreferencesKey("vpn_full")
        val vpnExcluded = stringSetPreferencesKey("vpn_excluded")
        val vpnAuto = booleanPreferencesKey("vpn_auto")
        val vpnTrusted = stringSetPreferencesKey("vpn_trusted")
        val backupEnabled = booleanPreferencesKey("backup_enabled")
        val backupWifi = booleanPreferencesKey("backup_wifi")
        val backupCharging = booleanPreferencesKey("backup_charging")
        val backupVideos = booleanPreferencesKey("backup_videos")
        val backupFolders = stringSetPreferencesKey("backup_folders")
        val backupSince = longPreferencesKey("backup_since")
        val lastBackup = longPreferencesKey("last_backup")
        val backupSkipped = stringPreferencesKey("backup_skipped")
        val filesGrid = booleanPreferencesKey("files_grid")
        val photoColumns = stringPreferencesKey("photo_columns")
        val pairedServer = stringPreferencesKey("paired_server")
        val pairedServerName = stringPreferencesKey("paired_server_name")
        val pairedDeviceId = stringPreferencesKey("paired_device_id")
        val pairedServerId = stringPreferencesKey("paired_server_id")
        val deviceToken = stringPreferencesKey("device_token")
        val isAdmin = booleanPreferencesKey("is_admin")
        val joinServer = stringPreferencesKey("join_server")
        val joinId = stringPreferencesKey("join_id")
        val joinToken = stringPreferencesKey("join_token")
        val joinName = stringPreferencesKey("join_name")
        val lockedSession = stringPreferencesKey("locked_session")
        val serverMl = booleanPreferencesKey("server_ml")
        val tsEnabled = booleanPreferencesKey("ts_enabled")
        val tsServerIp = stringPreferencesKey("ts_server_ip")
        val tsAuthKey = stringPreferencesKey("ts_auth_key")
        val tsControlUrl = stringPreferencesKey("ts_control_url")
        val backupAnywhere = booleanPreferencesKey("backup_anywhere")
        val backupDays = stringSetPreferencesKey("backup_days")
        val backupTime = stringPreferencesKey("backup_time")
        val theme = stringPreferencesKey("theme")
        val phoneTrashPending = stringSetPreferencesKey("phone_trash_pending")
        val trashSyncedAt = longPreferencesKey("trash_synced_at")
        val freeUpAuto = booleanPreferencesKey("free_up_auto")
        val freeUpKeepDays = stringPreferencesKey("free_up_keep_days")
        val freeUpPending = stringSetPreferencesKey("free_up_pending")
    }

    private fun read(p: Preferences) =
        AppSettings(
            immichUrl = p[K.immichUrl] ?: "",
            immichToken = p[K.immichToken] ?: "",
            immichTokenIsApiKey = p[K.immichApiKey] ?: false,
            immichUserName = p[K.immichUserName] ?: "",
            immichEmail = p[K.immichEmail] ?: "",
            ncUrl = p[K.ncUrl] ?: "",
            ncUser = p[K.ncUser] ?: "",
            ncAppPassword = p[K.ncPass] ?: "",
            ncUserId = p[K.ncUserId] ?: "",
            vpnConfig = p[K.vpnConfig] ?: "",
            vpnFullTunnel = p[K.vpnFull] ?: false,
            vpnExcludedApps = p[K.vpnExcluded] ?: emptySet(),
            vpnAutoConnect = p[K.vpnAuto] ?: false,
            vpnTrustedSsids = p[K.vpnTrusted] ?: emptySet(),
            backupEnabled = p[K.backupEnabled] ?: false,
            backupWifiOnly = p[K.backupWifi] ?: true,
            backupChargingOnly = p[K.backupCharging] ?: false,
            backupVideos = p[K.backupVideos] ?: true,
            backupFolders = p[K.backupFolders] ?: emptySet(),
            backupSince = p[K.backupSince] ?: 0L,
            lastBackupAt = p[K.lastBackup] ?: 0L,
            backupSkipped = p[K.backupSkipped]?.toIntOrNull() ?: 0,
            filesGrid = p[K.filesGrid] ?: false,
            photoColumns = p[K.photoColumns]?.toIntOrNull() ?: 4,
            pairedServer = p[K.pairedServer] ?: "",
            pairedServerName = p[K.pairedServerName] ?: "",
            pairedDeviceId = p[K.pairedDeviceId] ?: "",
            pairedServerId = p[K.pairedServerId] ?: "",
            deviceToken = p[K.deviceToken] ?: "",
            isAdmin = p[K.isAdmin] ?: false,
            joinServer = p[K.joinServer] ?: "",
            joinId = p[K.joinId] ?: "",
            joinToken = p[K.joinToken] ?: "",
            joinName = p[K.joinName] ?: "",
            lockedSession = p[K.lockedSession] ?: "",
            serverMachineLearning = p[K.serverMl] ?: true,
            tsEnabled = p[K.tsEnabled] ?: false,
            tsServerIp = p[K.tsServerIp] ?: "",
            tsAuthKey = p[K.tsAuthKey] ?: "",
            tsControlUrl = p[K.tsControlUrl] ?: "",
            backupAnywhere = p[K.backupAnywhere] ?: false,
            backupDays = p[K.backupDays]?.mapNotNull { it.toIntOrNull() }?.toSet() ?: emptySet(),
            backupTime = p[K.backupTime]?.toIntOrNull() ?: 2 * 60,
            theme = p[K.theme] ?: "system",
            phoneTrashPending = p[K.phoneTrashPending]?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet(),
            trashSyncedAt = p[K.trashSyncedAt] ?: 0L,
            freeUpAuto = p[K.freeUpAuto] ?: false,
            freeUpKeepDays = p[K.freeUpKeepDays]?.toIntOrNull() ?: 30,
            freeUpPending = p[K.freeUpPending]?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet(),
        )

    val flow: Flow<AppSettings> = context.dataStore.data.map { read(it) }

    /**
     * The settings in memory, updated the moment [update] returns. API clients read this, so the first
     * request after saving a new server already goes to the new server (no lag behind DataStore).
     */
    val state: MutableStateFlow<AppSettings> by lazy { MutableStateFlow(runBlocking { flow.first() }) }

    suspend fun current(): AppSettings = state.value

    private val writeLock = Mutex()

    // Serialised so the in-memory copy always ends up equal to the newest saved settings.
    suspend fun update(block: (AppSettings) -> AppSettings) = writeLock.withLock { save(block) }

    private suspend fun save(block: (AppSettings) -> AppSettings) {
        var saved: AppSettings? = null
        context.dataStore.edit { p ->
            val old = read(p)
            var n = block(old)
            // What gets backed up, or where, changed: look at the whole library again (the server skips
            // what it already has), otherwise older photos in a newly chosen folder would never be sent.
            if (n.backupVideos != old.backupVideos || n.backupFolders != old.backupFolders ||
                n.immichUrl != old.immichUrl || n.immichToken != old.immichToken
            ) n = n.copy(backupSince = 0)
            saved = n
            p[K.immichUrl] = n.immichUrl; p[K.immichToken] = n.immichToken
            p[K.immichApiKey] = n.immichTokenIsApiKey; p[K.immichUserName] = n.immichUserName
            p[K.immichEmail] = n.immichEmail
            p[K.ncUrl] = n.ncUrl; p[K.ncUser] = n.ncUser; p[K.ncPass] = n.ncAppPassword; p[K.ncUserId] = n.ncUserId
            p[K.vpnConfig] = n.vpnConfig; p[K.vpnFull] = n.vpnFullTunnel
            p[K.vpnExcluded] = n.vpnExcludedApps; p[K.vpnAuto] = n.vpnAutoConnect
            p[K.vpnTrusted] = n.vpnTrustedSsids
            p[K.backupEnabled] = n.backupEnabled; p[K.backupWifi] = n.backupWifiOnly
            p[K.backupCharging] = n.backupChargingOnly; p[K.backupVideos] = n.backupVideos
            p[K.backupFolders] = n.backupFolders; p[K.backupSince] = n.backupSince
            p[K.lastBackup] = n.lastBackupAt; p[K.backupSkipped] = n.backupSkipped.toString()
            p[K.filesGrid] = n.filesGrid; p[K.photoColumns] = n.photoColumns.toString()
            p[K.pairedServer] = n.pairedServer; p[K.pairedServerName] = n.pairedServerName
            p[K.pairedDeviceId] = n.pairedDeviceId; p[K.pairedServerId] = n.pairedServerId
            p[K.deviceToken] = n.deviceToken; p[K.isAdmin] = n.isAdmin
            p[K.joinServer] = n.joinServer; p[K.joinId] = n.joinId; p[K.joinToken] = n.joinToken; p[K.joinName] = n.joinName
            p[K.lockedSession] = n.lockedSession; p[K.serverMl] = n.serverMachineLearning
            p[K.tsEnabled] = n.tsEnabled; p[K.tsServerIp] = n.tsServerIp
            p[K.tsAuthKey] = n.tsAuthKey; p[K.tsControlUrl] = n.tsControlUrl
            p[K.backupAnywhere] = n.backupAnywhere; p[K.backupDays] = n.backupDays.map { it.toString() }.toSet()
            p[K.backupTime] = n.backupTime.toString(); p[K.theme] = n.theme
            p[K.phoneTrashPending] = n.phoneTrashPending.map { it.toString() }.toSet(); p[K.trashSyncedAt] = n.trashSyncedAt
            p[K.freeUpAuto] = n.freeUpAuto; p[K.freeUpKeepDays] = n.freeUpKeepDays.toString()
            p[K.freeUpPending] = n.freeUpPending.map { it.toString() }.toSet()
        }
        saved?.let { state.value = it }
    }
}

/** Normalises what people type into a server field: adds a scheme, drops trailing slashes. */
fun normalizeServerUrl(input: String): String {
    var s = input.trim().trimEnd('/')
    if (s.isEmpty()) return s
    if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "https://$s"
    // Users often paste the Immich web URL with /api or the Nextcloud URL with /index.php
    s = s.removeSuffix("/api").removeSuffix("/index.php").trimEnd('/')
    return s
}
