package app.immcloudscale.ui.network

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.immcloudscale.AppGraph
import app.immcloudscale.data.vpn.PeerStats
import app.immcloudscale.data.vpn.VpnManager
import app.immcloudscale.data.vpn.WgRoutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.TimeUnit

enum class Reach { Checking, Up, Down, NotSetUp }
data class ServiceCheck(val name: String, val url: String, val reach: Reach, val latencyMs: Long? = null)
data class AppEntry(val pkg: String, val label: String)

data class NetworkUi(
    val stats: List<PeerStats> = emptyList(),
    val services: List<ServiceCheck> = emptyList(),
    val apps: List<AppEntry>? = null,
    val message: String? = null,
)

class NetworkViewModel(private val g: AppGraph) : ViewModel() {
    private val _ui = MutableStateFlow(NetworkUi())
    val ui = _ui.asStateFlow()
    val vpn = g.vpn.state
    val settings = g.settingsState

    init {
        viewModelScope.launch {
            while (isActive) {
                _ui.update { it.copy(stats = g.vpn.stats()) }
                delay(2000)
            }
        }
        checkServices()
        viewModelScope.launch { vpn.collect { if (it.status == app.immcloudscale.data.vpn.VpnStatus.Connected) { delay(1500); checkServices() } } }
    }

    fun checkServices() = viewModelScope.launch {
        val s = settings.value
        val targets = listOf(
            Triple("Immich", s.immichUrl, "/api/server/ping"),
            Triple("Nextcloud", s.ncUrl, "/status.php"),
        )
        _ui.update { u -> u.copy(services = targets.map { (n, url, _) -> ServiceCheck(n, url, if (url.isBlank()) Reach.NotSetUp else Reach.Checking) }) }
        val quick = g.http.newBuilder().callTimeout(6, TimeUnit.SECONDS).build()
        val results = targets.map { (n, url, path) ->
            if (url.isBlank()) return@map ServiceCheck(n, url, Reach.NotSetUp)
            withContext(Dispatchers.IO) {
                val t0 = System.nanoTime()
                runCatching {
                    quick.newCall(Request.Builder().url(url.trimEnd('/') + path).build()).execute().use { it.isSuccessful }
                }.fold(
                    { ok -> ServiceCheck(n, url, if (ok) Reach.Up else Reach.Down, (System.nanoTime() - t0) / 1_000_000) },
                    { ServiceCheck(n, url, Reach.Down) },
                )
            }
        }
        _ui.update { it.copy(services = results) }
    }

    fun connect() = viewModelScope.launch { g.vpn.connect() }
    fun disconnect() = viewModelScope.launch { g.vpn.disconnect() }

    private fun save(reapply: Boolean = true, block: (app.immcloudscale.data.AppSettings) -> app.immcloudscale.data.AppSettings) =
        viewModelScope.launch { g.settings.update(block); if (reapply) g.vpn.reapply() }

    fun setExitNode(on: Boolean) = save { it.copy(vpnFullTunnel = on) }
    fun setAutoConnect(on: Boolean) = save(false) { it.copy(vpnAutoConnect = on) }
    fun setExcluded(pkgs: Set<String>) = save { it.copy(vpnExcludedApps = pkgs) }
    fun addTrusted(ssid: String) = save(false) { it.copy(vpnTrustedSsids = it.vpnTrustedSsids + ssid) }
    fun removeTrusted(ssid: String) = save(false) { it.copy(vpnTrustedSsids = it.vpnTrustedSsids - ssid) }
    fun currentSsid() = g.vpn.currentSsid()

    /** Returns an error message, or null when the config was saved. */
    fun importConfig(text: String): String? {
        val err = VpnManager.validate(text)
        if (err != null) return err
        save { it.copy(vpnConfig = text.trim() + "\n") }
        _ui.update { it.copy(message = "Configuration saved") }
        return null
    }

    fun removeConfig() = viewModelScope.launch {
        g.vpn.disconnect()
        g.settings.update { it.copy(vpnConfig = "", vpnAutoConnect = false) }
    }

    fun consumeMessage() = _ui.update { it.copy(message = null) }

    fun loadApps() {
        if (_ui.value.apps != null) return
        viewModelScope.launch {
            val pm = g.context.packageManager
            val apps = withContext(Dispatchers.IO) {
                pm.getInstalledApplications(PackageManager.GET_META_DATA)
                    .filter { it.packageName != g.context.packageName && (it.flags and ApplicationInfo.FLAG_SYSTEM == 0 || pm.getLaunchIntentForPackage(it.packageName) != null) }
                    .map { AppEntry(it.packageName, pm.getApplicationLabel(it).toString()) }
                    .sortedBy { it.label.lowercase() }
            }
            _ui.update { it.copy(apps = apps) }
        }
    }

    fun summary() = WgRoutes.summarize(settings.value.vpnConfig)
    fun publicKey() = VpnManager.publicKeyOf(settings.value.vpnConfig)
}
