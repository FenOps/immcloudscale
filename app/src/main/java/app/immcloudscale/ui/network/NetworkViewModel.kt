package app.immcloudscale.ui.network

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.immcloudscale.AppGraph
import app.immcloudscale.data.tailscale.TsStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.TimeUnit

enum class Reach { Checking, Up, Down, NotSetUp }
data class ServiceCheck(val name: String, val url: String, val reach: Reach, val latencyMs: Long? = null)

data class NetworkUi(val services: List<ServiceCheck> = emptyList())

class NetworkViewModel(private val g: AppGraph) : ViewModel() {
    private val _ui = MutableStateFlow(NetworkUi())
    val ui = _ui.asStateFlow()
    val settings = g.settingsState
    val ts = g.tailscale.state
    val atHome = g.tailscale.home

    fun setTsEnabled(on: Boolean) = viewModelScope.launch {
        g.settings.update { it.copy(tsEnabled = on) }
        if (on && !g.tailscale.atHome) g.tailscale.start() else if (!on) g.tailscale.stop()
    }
    fun tsConnect() = viewModelScope.launch { g.tailscale.start() }
    fun tsDisconnect() = viewModelScope.launch { g.tailscale.stop() }
    fun tsSignOut() = viewModelScope.launch { g.tailscale.logout() }

    init {
        checkServices()
        viewModelScope.launch { ts.collect { if (it.status == TsStatus.Connected) checkServices() } }
        viewModelScope.launch { atHome.drop(1).collect { checkServices() } }
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
}
