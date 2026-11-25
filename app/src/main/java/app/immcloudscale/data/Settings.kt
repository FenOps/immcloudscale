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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
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

    val backupEnabled: Boolean = false,
    val backupWifiOnly: Boolean = true,
    val backupChargingOnly: Boolean = false,
    val backupVideos: Boolean = true,
    val backupFolders: Set<String> = emptySet(),
    val backupSince: Long = 0L,
    val lastBackupAt: Long = 0L,
    /** Files the last backup run couldn't send (unsupported type, unreadable). */
    val backupSkipped: Int = 0,

    val filesGrid: Boolean = false,
    val photoColumns: Int = 4,

    /** Set when the phone was paired with an ImmCloudScale server instead of configured by hand. */
    val pairedServer: String = "",
    val pairedServerName: String = "",
    val pairedDeviceId: String = "",
    /** The server's ID from pairing; checked before sending credentials on Wi-Fi without the VPN. */
    val pairedServerId: String = "",
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
        )

    val flow: Flow<AppSettings> = context.dataStore.data.map { read(it) }

    /**
     * The settings in memory, updated the moment [update] returns. API clients read this, so the first
     * request after saving a new server already goes to the new server (no lag behind DataStore).
     */
    val state: MutableStateFlow<AppSettings> by lazy { MutableStateFlow(runBlocking { flow.first() }) }

    suspend fun current(): AppSettings = state.value

    private val writeLock = kotlinx.coroutines.sync.Mutex()

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
