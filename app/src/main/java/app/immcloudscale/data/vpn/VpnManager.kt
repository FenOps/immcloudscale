package app.immcloudscale.data.vpn

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import app.immcloudscale.data.AppSettings
import app.immcloudscale.data.SettingsStore
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.config.InetNetwork
import com.wireguard.config.Interface
import com.wireguard.config.Peer
import com.wireguard.crypto.KeyPair
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class VpnStatus { Disconnected, Connecting, Connected, Error }

data class VpnState(
    val status: VpnStatus = VpnStatus.Disconnected,
    val error: String? = null,
    val since: Long = 0L,
    val network: String? = null,
)

data class PeerStats(val publicKey: String, val rxBytes: Long, val txBytes: Long, val lastHandshake: Long)

/**
 * Home VPN built on wireguard-go (the same engine Tailscale uses) running in Android's VpnService.
 * Adds the Tailscale-style switches on top of a plain WireGuard config: exit node (full tunnel)
 * vs. home-LAN-only routing, per-app split tunnelling, and auto-connect away from trusted Wi-Fi.
 */
class VpnManager(
    private val context: Context,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    /** Told an identifier of the current underlying network whenever it changes (see HomeGuard). */
    private val onNetworkKey: (String) -> Unit = {},
) {
    private val backend by lazy { GoBackend(context.applicationContext) }
    private val mutex = Mutex()
    private val _state = MutableStateFlow(VpnState())
    val state: StateFlow<VpnState> = _state.asStateFlow()

    private val tunnel = object : Tunnel {
        override fun getName() = "home"
        override fun onStateChange(newState: Tunnel.State) {
            _state.value = when (newState) {
                Tunnel.State.UP -> _state.value.copy(status = VpnStatus.Connected, error = null, since = System.currentTimeMillis())
                else -> _state.value.copy(status = VpnStatus.Disconnected, since = 0)
            }
        }
    }

    init {
        // Android's "Always-on VPN" setting starts GoBackend's service by itself; bring the tunnel up then.
        GoBackend.setAlwaysOnCallback { scope.launch { connect() } }
    }

    /** Non-null when the user still has to approve the VPN in the system dialog. */
    fun permissionIntent(): Intent? = VpnService.prepare(context)

    suspend fun connect() = mutex.withLock {
        val s = settings.current()
        if (!s.vpnConfigured) {
            _state.value = VpnState(VpnStatus.Error, "Add a WireGuard configuration first")
            return@withLock
        }
        if (permissionIntent() != null) {
            _state.value = VpnState(VpnStatus.Error, "VPN permission not granted")
            return@withLock
        }
        _state.value = _state.value.copy(status = VpnStatus.Connecting, error = null)
        try {
            val cfg = buildConfig(s)
            withContext(Dispatchers.IO) { backend.setState(tunnel, Tunnel.State.UP, cfg) }
            _state.value = _state.value.copy(status = VpnStatus.Connected, since = System.currentTimeMillis())
        } catch (e: Exception) {
            Log.w("VpnManager", "connect failed", e)
            _state.value = VpnState(VpnStatus.Error, e.message ?: e.javaClass.simpleName)
        }
    }

    /** Set when the person switches the VPN off; auto-connect then waits until the network changes. */
    @Volatile private var userDisconnected = false

    /** Disconnect because the person asked to (auto-connect respects it until they change networks). */
    suspend fun disconnectByUser() {
        userDisconnected = true
        disconnect()
    }

    suspend fun connectByUser() {
        userDisconnected = false
        connect()
    }

    suspend fun disconnect() = mutex.withLock {
        try {
            withContext(Dispatchers.IO) { backend.setState(tunnel, Tunnel.State.DOWN, null) }
        } catch (e: Exception) {
            Log.w("VpnManager", "disconnect failed", e)
        }
        _state.value = VpnState(VpnStatus.Disconnected)
    }

    /** Re-applies settings (exit node, excluded apps) to a running tunnel. */
    suspend fun reapply() {
        if (state.value.status == VpnStatus.Connected) connect()
    }

    suspend fun stats(): List<PeerStats> = withContext(Dispatchers.IO) {
        if (state.value.status != VpnStatus.Connected) return@withContext emptyList()
        runCatching {
            val st = backend.getStatistics(tunnel)
            st.peers().map { key ->
                val p = st.peer(key)
                PeerStats(key.toBase64(), p?.rxBytes ?: 0, p?.txBytes ?: 0, p?.latestHandshakeEpochMillis ?: 0)
            }
        }.getOrDefault(emptyList())
    }

    private fun buildConfig(s: AppSettings): Config {
        val parsed = Config.parse(s.vpnConfig.byteInputStream())
        val i = parsed.`interface`
        val iface = Interface.Builder()
            .setKeyPair(i.keyPair)
            .addAddresses(i.addresses)
            .addDnsServers(i.dnsServers)
            .addDnsSearchDomains(i.dnsSearchDomains)
            .apply {
                i.listenPort.ifPresent { setListenPort(it) }
                i.mtu.ifPresent { setMtu(it) }
                val excluded = (i.excludedApplications + s.vpnExcludedApps).toSet()
                if (i.includedApplications.isNotEmpty()) includeApplications(i.includedApplications)
                else if (excluded.isNotEmpty()) excludeApplications(excluded)
            }
            .build()
        val peers = parsed.peers.map { p ->
            val routes = WgRoutes.allowedIps(
                configured = p.allowedIps.map { it.toString() },
                interfaceAddresses = i.addresses.map { it.toString() },
                fullTunnel = s.vpnFullTunnel,
            )
            Peer.Builder()
                .setPublicKey(p.publicKey)
                .apply {
                    p.preSharedKey.ifPresent { setPreSharedKey(it) }
                    p.endpoint.ifPresent { setEndpoint(it) }
                    p.persistentKeepalive.ifPresent { setPersistentKeepalive(it) }
                }
                .addAllowedIps(routes.map { InetNetwork.parse(it) })
                .build()
        }
        return Config.Builder().setInterface(iface).addPeers(peers).build()
    }

    // ---- auto-connect ----

    private var callbackRegistered = false
    private val networks = java.util.concurrent.ConcurrentHashMap<Network, NetworkCapabilities>()
    @Volatile private var lastKey: String? = null

    /**
     * Watches the phone's real (non-VPN) networks. Decisions use one "current" network, Wi-Fi if there is one,
     * else mobile data, and are taken only when it actually changes (not on every signal-strength update).
     */
    fun startAutoConnectMonitor() {
        if (callbackRegistered) return
        callbackRegistered = true
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val onChange: (Network, NetworkCapabilities?) -> Unit = { network, caps ->
            if (caps == null) networks.remove(network) else networks[network] = caps
            evaluate()
        }
        // NetworkCallback(flags) only exists on Android 12+; calling it on older phones crashes the app at start.
        val cb = if (Build.VERSION.SDK_INT >= 31) {
            object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = onChange(network, caps)
                override fun onLost(network: Network) = onChange(network, null)
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = onChange(network, caps)
                override fun onLost(network: Network) = onChange(network, null)
            }
        }
        runCatching { cm.registerNetworkCallback(request, cb) }
    }

    private fun evaluate() {
        val all = networks.entries.toList()
        val current = all.firstOrNull { it.value.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }
            ?: all.firstOrNull { it.value.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) }
            ?: all.firstOrNull { it.value.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) }
            ?: all.firstOrNull()
        val caps = current?.value
        val wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val ssid = if (wifi) ssid(caps!!) else null
        val name = when {
            caps == null -> "Offline"
            wifi -> ssid ?: "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Network"
        }
        _state.value = _state.value.copy(network = name)
        // The network itself, not its name: Android may reveal the Wi-Fi name later, which isn't a move.
        val key = current?.key?.toString() ?: "none"
        if (key == lastKey) return
        lastKey = key
        userDisconnected = false // a new network: auto-connect may act again
        onNetworkKey(key)
        val net = current?.key
        scope.launch { decisions.withLock { onNetworkChanged(key, wifi, ssid, net) } }
    }

    private val decisions = Mutex()

    private suspend fun onNetworkChanged(key: String, wifi: Boolean, ssid: String?, network: Network?) {
        if (key != lastKey) return // the phone has already moved on to another network
        val s = settings.current()
        if (!s.vpnAutoConnect || !s.vpnConfigured || network == null || permissionIntent() != null) return
        // Home is a trusted Wi-Fi name, or (works without location permission) a Wi-Fi where our own server
        // answers directly, asked over that Wi-Fi itself rather than through the tunnel.
        val home = wifi && (
            (ssid != null && s.vpnTrustedSsids.any { it.equals(ssid, ignoreCase = true) }) ||
                (s.pairedServerId.isNotBlank() && serverAnswersOn(network, s.pairedServer, s.pairedServerId))
            )
        if (key != lastKey) return // changed while checking
        val status = state.value.status
        if (home && status == VpnStatus.Connected) disconnect()
        // An earlier failed attempt (Error) mustn't stop auto-connect for good: try again on this network.
        if (!home && (status == VpnStatus.Disconnected || status == VpnStatus.Error) && !userDisconnected) connect()
    }

    private suspend fun serverAnswersOn(network: Network, server: String, id: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val client = okhttp3.OkHttpClient.Builder().socketFactory(network.socketFactory)
                .callTimeout(3, java.util.concurrent.TimeUnit.SECONDS).followRedirects(false).build()
            client.newCall(okhttp3.Request.Builder().url(server.trimEnd('/') + "/api/v1/info").build()).execute().use { r ->
                r.isSuccessful && r.body?.string()?.contains("\"id\":\"$id\"") == true
            }
        }.getOrDefault(false)
    }

    @SuppressLint("MissingPermission")
    private fun ssid(caps: NetworkCapabilities): String? {
        val info = (if (Build.VERSION.SDK_INT >= 29) caps.transportInfo as? WifiInfo else null)
            ?: @Suppress("DEPRECATION") context.applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo
        return info?.ssid?.trim('"')?.takeIf { it.isNotEmpty() && it != "<unknown ssid>" && it != "unknown ssid" }
    }

    @SuppressLint("MissingPermission")
    fun currentSsid(): String? {
        @Suppress("DEPRECATION")
        val info = context.applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo
        return info?.ssid?.trim('"')?.takeIf { it.isNotEmpty() && it != "<unknown ssid>" }
    }

    companion object {
        /** New key pair for "Add this device": the private key stays here, the public key goes to the NAS. */
        fun newKeyPair(): Pair<String, String> = KeyPair().let { it.privateKey.toBase64() to it.publicKey.toBase64() }

        fun publicKeyOf(config: String): String? = runCatching {
            Config.parse(config.byteInputStream()).`interface`.keyPair.publicKey.toBase64()
        }.getOrNull()

        fun validate(config: String): String? = runCatching { Config.parse(config.byteInputStream()); null }
            .getOrElse { it.message ?: "Invalid WireGuard configuration" }
    }
}
