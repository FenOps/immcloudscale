package app.immcloudscale.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Stops credentials going to a stranger's device. (The server ID is public on purpose: it only has to tell
 * "my server" from "some other device at the same address", not keep anyone out.) A paired phone reaches its server at a home LAN address
 * (e.g. 192.168.1.50); on another Wi-Fi network that uses the same numbers, that address belongs to someone
 * else. So before credentials go to a private address without the VPN, ask the server there for its ID and
 * compare it with the one saved at pairing. The answer is remembered per network.
 */
class HomeGuard(
    private val settings: () -> AppSettings,
    private val vpnConnected: () -> Boolean,
    private val probe: (String) -> String? = ::fetchServerId,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Changes whenever the phone's underlying network changes (set by the network monitor). */
    @Volatile var networkKey: String = ""

    @Volatile private var verified: Pair<String, Long>? = null

    fun check(url: HttpUrl) {
        val s = settings()
        if (s.pairedServer.isBlank() || s.pairedServerId.isBlank()) return // hand-configured servers: user's choice
        // The addresses this pairing set up (the server's LAN address for photos and files, and the address the
        // phone paired through, which may be a name like nas.local). Hand-configured servers clear the pairing.
        val hosts = listOf(s.pairedServer, s.immichUrl, s.ncUrl).mapNotNull { it.toHttpUrlOrNull()?.host?.lowercase() }
        if (url.host.lowercase() !in hosts) return
        if (!isPrivateHost(url.host)) return // public names (e.g. HTTPS behind a domain) aren't ambiguous
        if (vpnConnected()) return // through the tunnel, private addresses are always home
        // One check at a time: a screen of thumbnails shouldn't send a probe per image.
        synchronized(this) {
            val key = networkKey
            verified?.let { (k, at) -> if (k == key && now() - at < 10 * 60_000) return }
            val id = runCatching { probe(s.pairedServer) }.getOrNull()
            if (id != s.pairedServerId) {
                throw IOException("Not on your home network: turn on the VPN in the Network tab to reach your server")
            }
            verified = key to now()
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val quick = OkHttpClient.Builder().callTimeout(4, TimeUnit.SECONDS).followRedirects(false).build()

        fun fetchServerId(server: String): String? =
            quick.newCall(Request.Builder().url(server.trimEnd('/') + "/api/v1/info").build()).execute().use { r ->
                if (!r.isSuccessful) return null
                json.parseToJsonElement(r.body?.string().orEmpty()).jsonObject["id"]?.jsonPrimitive?.content
            }

        /** Literal private/link-local/CGNAT addresses and local-only names. */
        fun isPrivateHost(host: String): Boolean {
            val h = host.lowercase().trim('[', ']')
            if (h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa") || h == "localhost") return true
            if (':' in h) return h.startsWith("fd") || h.startsWith("fc") || h.startsWith("fe80")
            val p = h.split('.').mapNotNull { it.toIntOrNull() }
            if (p.size != 4) return false
            return p[0] == 10 || (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168) ||
                (p[0] == 100 && p[1] in 64..127) || (p[0] == 169 && p[1] == 254) || p[0] == 127
        }
    }
}
