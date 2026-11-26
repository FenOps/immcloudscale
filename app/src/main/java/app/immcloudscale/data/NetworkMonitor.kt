package app.immcloudscale.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Watches the phone's networks and tells whether it's at home: on a Wi-Fi where the paired server answers directly.
 * Decisions use one "current" network, Wi-Fi if there is one, else mobile data, and are taken only when it
 * actually changes (not on every signal-strength update).
 */
class NetworkMonitor(
    private val context: Context,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    /** Told an identifier of the current network whenever it changes (see HomeGuard). */
    private val onNetworkKey: (String) -> Unit,
    /** Told whether the phone is at home after each network change, with the server's info page when it answered. */
    private val onHome: (Boolean, String?) -> Unit,
) {
    private var registered = false
    private val networks = ConcurrentHashMap<Network, NetworkCapabilities>()
    @Volatile private var lastKey: String? = null
    private val decisions = Mutex()
    private val _unmetered = MutableStateFlow(true)
    /** The current network doesn't charge by the byte (Wi-Fi, usually): "Only on Wi-Fi" backup can run. */
    val unmetered: StateFlow<Boolean> = _unmetered.asStateFlow()

    fun start() {
        if (registered) return
        registered = true
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        runCatching {
            cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = changed(network, caps)
                override fun onLost(network: Network) = changed(network, null)
            })
        }
    }

    private fun changed(network: Network, caps: NetworkCapabilities?) {
        if (caps == null) networks.remove(network) else networks[network] = caps
        val all = networks.entries.toList()
        val current = all.firstOrNull { it.value.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }
            ?: all.firstOrNull { it.value.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) }
            ?: all.firstOrNull { it.value.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) }
            ?: all.firstOrNull()
        _unmetered.value = current?.value?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
        val key = current?.key?.toString() ?: "none"
        if (key == lastKey) return
        lastKey = key
        onNetworkKey(key)
        val wifi = current?.value?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        scope.launch { decisions.withLock { decide(key, wifi, current?.key) } }
    }

    private suspend fun decide(key: String, wifi: Boolean, network: Network?) {
        if (key != lastKey) return // the phone has already moved on to another network
        val s = settings.current()
        // Asked over that Wi-Fi itself, never through Tailscale.
        val info = if (wifi && network != null && s.pairedServer.isNotBlank()) serverInfoOn(network, s.pairedServer) else null
        val home = info != null && s.pairedServerId.isNotBlank() && info.contains("\"id\":\"${s.pairedServerId}\"")
        if (key != lastKey) return // changed while checking
        onHome(home, info?.takeIf { home })
    }

    /** The server's info page fetched over [network] itself, or null when it doesn't answer. */
    private suspend fun serverInfoOn(network: Network, server: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val client = OkHttpClient.Builder().socketFactory(network.socketFactory)
                .callTimeout(3, TimeUnit.SECONDS).followRedirects(false).build()
            client.newCall(Request.Builder().url(server.trimEnd('/') + "/api/v1/info").build()).execute().use { r ->
                if (r.isSuccessful) r.body?.string() else null
            }
        }.getOrNull()
    }
}
