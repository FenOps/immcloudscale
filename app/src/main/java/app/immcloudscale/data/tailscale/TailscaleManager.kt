package app.immcloudscale.data.tailscale

import android.content.Context
import android.os.Build
import android.util.Log
import app.immcloudscale.data.AppSettings
import app.immcloudscale.data.SettingsStore
import app.immcloudscale.tsbridge.Tsbridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** What the app needs from the Go bridge (app/tailscale). The real one loads native code; tests use a fake. */
interface TailscaleBridge {
    fun start(dir: String, hostname: String, authKey: String, controlUrl: String)
    fun stop()
    fun logout()
    fun setRoute(lanHost: String, tailnetHost: String)
    fun proxyPort(): Int
    fun proxyToken(): String
    fun status(): String
    fun logs(): String
}

/** The gomobile-built bridge. Its native library loads on first use, never in unit tests. */
object GoTailscaleBridge : TailscaleBridge {
    override fun start(dir: String, hostname: String, authKey: String, controlUrl: String) =
        Tsbridge.start(dir, hostname, authKey, controlUrl)
    override fun stop() = Tsbridge.stop()
    override fun logout() = Tsbridge.logout()
    override fun setRoute(lanHost: String, tailnetHost: String) = Tsbridge.setRoute(lanHost, tailnetHost)
    override fun proxyPort(): Int = Tsbridge.proxyPort().toInt()
    override fun proxyToken(): String = Tsbridge.proxyToken()
    override fun status(): String = Tsbridge.status()
    override fun logs(): String = Tsbridge.logs()
}

enum class TsStatus { Off, Starting, NeedsLogin, Connected, Error }

data class TsState(
    val status: TsStatus = TsStatus.Off,
    /** The Tailscale sign-in page, while [status] is NeedsLogin. */
    val authUrl: String? = null,
    /** This phone's Tailscale address once connected. */
    val ip: String? = null,
    val error: String? = null,
)

/**
 * Remote access through Tailscale, run inside the app: no VPN slot, no separate Tailscale app. Away from home
 * it starts by itself (when switched on) and the app's requests for the home server go through a local proxy
 * into the tailnet; at home they go straight to the server and Tailscale stops to save battery.
 */
class TailscaleManager(
    private val context: Context,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    private val bridge: TailscaleBridge = GoTailscaleBridge,
) {
    private val _state = MutableStateFlow(TsState())
    val state: StateFlow<TsState> = _state.asStateFlow()
    private val lock = Mutex()

    private val _home = MutableStateFlow(true)
    /** Set from the network monitor: the home server answers directly on the current network. */
    val home: StateFlow<Boolean> = _home.asStateFlow()
    val atHome: Boolean get() = _home.value

    /** The local proxy to send home-server requests through, or null to go direct. */
    fun proxy(): Proxy? {
        if (atHome || _state.value.status != TsStatus.Connected) return null
        val port = runCatching { bridge.proxyPort() }.getOrDefault(0)
        return if (port > 0) Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)) else null
    }


    fun proxyToken(): String = runCatching { bridge.proxyToken() }.getOrDefault("")

    fun logs(): String = runCatching { bridge.logs() }.getOrDefault("")

    /** The server being joined through an invite (its home address), routed through Tailscale until paired. */
    @Volatile var inviteServer: String? = null
        private set
    /** An invite join is running: the phone's own Tailscale stays off and only the invited server is routed. */
    @Volatile var inviting = false
        private set
    @Volatile private var homeBeforeInvite = true
    private val inviteClaimed = AtomicBoolean(false) // one join at a time; only the one that claimed it cleans up

    /**
     * Joins through an invite away from home, with a temporary Tailscale identity of its own: the phone's own
     * Tailscale settings are left alone, and only the invited server's home address is routed (to [ip]).
     * [onSignIn] opens Tailscale's sign-in page when the invite has no key. When this returns, the join is
     * connected and the caller must call [inviteDone]; when it throws, it has cleaned up after itself.
     */
    suspend fun connectForInvite(server: String, ip: String, key: String, controlUrl: String, onSignIn: (String) -> Unit) {
        if (!inviteClaimed.compareAndSet(false, true)) throw IOException("Already joining a server with an invite; wait for that to finish")
        try {
            joinInvite(server, ip, key, controlUrl, onSignIn)
        } catch (e: Throwable) {
            inviteDone()
            throw e
        }
    }

    private suspend fun joinInvite(server: String, ip: String, key: String, controlUrl: String, onSignIn: (String) -> Unit) {
        val host = server.toHttpUrlOrNull()?.host ?: throw IOException("That invite has no server address")
        val s = settings.current()
        if (listOf(s.pairedServer, s.immichUrl, s.ncUrl).any { it.toHttpUrlOrNull()?.host.equals(host, ignoreCase = true) }) {
            // Same address as this phone's server, but reached through someone else's tailnet: refuse.
            throw IOException("This phone already uses a server at $host. To join it again, use \"Pair again\" at home instead.")
        }
        // Mark first, so nothing restarts the phone's own Tailscale between stopping it and starting the invite's.
        homeBeforeInvite = _home.value
        inviting = true
        stopNow() // the phone's own tailnet, if it was connected (this also forgets its routes)
        inviteServer = server
        _home.value = false
        connect(inviteDir(), hostname(settings.current()), key, controlUrl) { bridge.setRoute(host, ip) }
        if (_state.value.status == TsStatus.NeedsLogin) {
            _state.value.authUrl?.let(onSignIn)
            // Give the person time to sign in; watchSignIn updates the state when they're done.
            withTimeoutOrNull(5 * 60_000) { while (_state.value.status == TsStatus.NeedsLogin) delay(1000) }
        }
        if (_state.value.status != TsStatus.Connected) {
            throw IOException(_state.value.error ?: "Couldn't connect to the server through Tailscale. Check the internet connection and try again.")
        }
    }

    /**
     * Ends an invite join, whether it worked or not: the route goes, the temporary identity is stopped and
     * deleted, and the phone's own Tailscale comes back as the network calls for it.
     */
    suspend fun inviteDone() = withContext(NonCancellable) { // also when the screen that started the join has gone
        if (!inviteClaimed.get()) return@withContext // nothing of ours to end (a refused join, or already done)
        val host = inviteServer?.toHttpUrlOrNull()?.host
        inviteServer = null
        if (!inviting) {
            inviteClaimed.set(false)
            return@withContext
        }
        try {
            lock.withLock {
                withContext(Dispatchers.IO) {
                    host?.let { runCatching { bridge.setRoute(it, "") } }
                    runCatching { bridge.stop() }
                    inviteDir().deleteRecursively()
                }
                _state.value = TsState(TsStatus.Off)
            }
        } finally {
            inviting = false
            inviteClaimed.set(false)
        }
        onNetwork(homeBeforeInvite)
        scope.launch { checkHome() } // the phone may now be paired with the invite's server, on another network
    }

    private fun inviteDir() = File(context.cacheDir, "tailscale-invite")

    /** Whether ImmCloudScale's pairing service answers at [server] directly from here (then no Tailscale is needed). */
    suspend fun answersDirectly(server: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            direct.newCall(Request.Builder().url(server.trimEnd('/') + "/api/v1/info").build()).execute().use { r ->
                r.isSuccessful && r.body?.string()?.contains("\"product\":\"immcloudscale\"") == true
            }
        }.getOrDefault(false)
    }

    /** Called when the phone's network changes. */
    fun onNetwork(home: Boolean) {
        // During an invite join the network monitor mustn't stop or reroute the temporary connection.
        if (inviting) {
            homeBeforeInvite = home
            return
        }
        _home.value = home
        scope.launch {
            val s = settings.current()
            if (home) {
                if (_state.value.status != TsStatus.Off && _state.value.status != TsStatus.NeedsLogin) stop()
            } else if (s.tsEnabled && s.tsServerIp.isNotBlank()) {
                start()
            }
        }
    }

    /**
     * Asks the server directly (never through Tailscale) whether it's reachable from here, for code that runs
     * before the network monitor has decided, such as a backup started in the background.
     */
    suspend fun checkHome(): Boolean = withContext(Dispatchers.IO) {
        val s = settings.current()
        if (s.pairedServer.isBlank() || s.pairedServerId.isBlank()) return@withContext _home.value
        val body = runCatching {
            direct.newCall(Request.Builder().url(s.pairedServer.trimEnd('/') + "/api/v1/info").build()).execute().use { r ->
                if (r.isSuccessful) r.body?.string() else null
            }
        }.getOrNull()
        val home = body != null && body.contains("\"id\":\"${s.pairedServerId}\"")
        if (home) learnServerIp(body!!)
        if (home != _home.value) onNetwork(home) else _home.value = home
        home
    }

    private val direct = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).callTimeout(4, TimeUnit.SECONDS).followRedirects(false).build()

    /** Learns the server's Tailscale address from its info page (phones paired before Tailscale was set up). */
    fun learnServerIp(infoJson: String) {
        val info = runCatching { Json.parseToJsonElement(infoJson).jsonObject }.getOrNull() ?: return
        val ip = info["tailscaleIp"]?.jsonPrimitive?.content
        val ml = info["machineLearning"]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
        scope.launch {
            val s = settings.current()
            if ((ip != null && s.tsServerIp != ip) || (ml != null && s.serverMachineLearning != ml)) {
                settings.update { it.copy(tsServerIp = ip ?: it.tsServerIp, serverMachineLearning = ml ?: it.serverMachineLearning) }
            }
        }
    }

    /** Joins the tailnet and waits until connected, a sign-in is needed, or it fails. */
    suspend fun start() = lock.withLock {
        if (inviting) return@withLock // the invite's temporary connection is in use
        val s = settings.current()
        if (s.tsServerIp.isBlank()) {
            _state.value = TsState(TsStatus.Error, error = "Your server isn't on Tailscale yet. On the server run: sudo ics-server tailscale")
            return@withLock
        }
        if (_state.value.status == TsStatus.Connected) return@withLock
        connectLocked(File(context.filesDir, "tailscale"), hostname(s), s.tsAuthKey, s.tsControlUrl) {
            lanHost(s)?.let { bridge.setRoute(it, s.tsServerIp) }
        }
    }

    private suspend fun connect(dir: File, hostname: String, key: String, controlUrl: String, routes: () -> Unit) =
        lock.withLock { connectLocked(dir, hostname, key, controlUrl, routes) }

    /** Starts the bridge with [dir] as its identity and waits until connected, a sign-in is needed, or it fails. */
    private suspend fun connectLocked(dir: File, hostname: String, key: String, controlUrl: String, routes: () -> Unit) {
        _state.value = TsState(TsStatus.Starting)
        try {
            withContext(Dispatchers.IO) {
                dir.mkdirs()
                bridge.start(dir.path, hostname, key, controlUrl)
                routes()
            }
            // A first connection takes a few seconds; a sign-in can take as long as the person needs.
            withTimeoutOrNull(45_000) {
                while (true) {
                    val st = read()
                    _state.value = st
                    if (st.status == TsStatus.Connected || st.status == TsStatus.NeedsLogin && st.authUrl != null) break
                    delay(500)
                }
            } ?: run { _state.value = TsState(TsStatus.Error, error = "Tailscale didn't connect. Check the internet connection and try again.") }
            if (_state.value.status == TsStatus.NeedsLogin) watchSignIn()
        } catch (e: Throwable) {
            Log.w("Tailscale", "start failed", e)
            _state.value = TsState(TsStatus.Error, error = e.message ?: e.javaClass.simpleName)
        }
    }

    /** After the person opens the sign-in page, notice when it's done. */
    private fun watchSignIn() = scope.launch {
        repeat(600) {
            delay(1000)
            val st = runCatching { read() }.getOrNull() ?: return@launch
            if (_state.value.status != TsStatus.NeedsLogin) return@launch
            if (st.status != TsStatus.NeedsLogin) {
                _state.value = st
                return@launch
            }
        }
    }

    /** Stops the phone's Tailscale (not an invite join in progress, which ends with [inviteDone]). */
    suspend fun stop() {
        if (!inviting) stopNow()
    }

    private suspend fun stopNow() = lock.withLock {
        withContext(Dispatchers.IO) { runCatching { bridge.stop() } }
        _state.value = TsState(TsStatus.Off)
    }

    /** Signs this phone out of the tailnet; the next connection needs a sign-in or the server's key. */
    suspend fun logout() = lock.withLock {
        withContext(Dispatchers.IO) { runCatching { bridge.logout() } }
        _state.value = TsState(TsStatus.Off)
    }

    /**
     * For backup away from home: makes sure Tailscale is up before uploading. False when it can't connect
     * (switched off, needs a sign-in in the app, or no internet).
     */
    suspend fun ensureConnected(): Boolean {
        if (inviting) return false // not the paired server's tailnet: wait until the invite join is over
        if (atHome) return true
        val s = settings.current()
        if (!s.tsEnabled) return false
        start()
        return !inviting && _state.value.status == TsStatus.Connected // an invite join may have started meanwhile
    }

    private fun read(): TsState {
        val o = Json.parseToJsonElement(bridge.status()).jsonObject
        val authUrl = o["authURL"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        val ip = o["ip"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        return when (o["state"]?.jsonPrimitive?.content) {
            "Running" -> TsState(TsStatus.Connected, ip = ip)
            "NeedsLogin", "NeedsMachineAuth" -> TsState(TsStatus.NeedsLogin, authUrl = authUrl)
            "Stopped" -> TsState(TsStatus.Off)
            else -> TsState(TsStatus.Starting)
        }
    }

    private fun lanHost(s: AppSettings) = (s.immichUrl.ifBlank { s.pairedServer }).toHttpUrlOrNull()?.host

    private fun hostname(s: AppSettings): String {
        val who = s.immichUserName.ifBlank { s.ncUser }.lowercase().filter { it.isLetterOrDigit() }
        val model = Build.MODEL.lowercase().filter { it.isLetterOrDigit() }.take(20)
        return listOf("immcloudscale", who, model).filter { it.isNotEmpty() }.joinToString("-")
    }
}
