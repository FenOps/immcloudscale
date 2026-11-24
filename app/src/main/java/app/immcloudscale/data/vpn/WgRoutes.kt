package app.immcloudscale.data.vpn

/**
 * Pure helpers around wg-quick configs, kept free of Android and WireGuard classes so they are unit tested.
 */
object WgRoutes {
    val DEFAULT_ROUTES = listOf("0.0.0.0/0", "::/0")
    val PRIVATE_RANGES = listOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "fd00::/8")

    /**
     * AllowedIPs for a peer.
     * - [fullTunnel] (exit node): everything goes home.
     * - otherwise: the config's own routes without the default route; if that leaves nothing
     *   (a config that only had 0.0.0.0/0), the tunnel subnet(s) plus private LAN ranges.
     */
    fun allowedIps(configured: List<String>, interfaceAddresses: List<String>, fullTunnel: Boolean): List<String> {
        if (fullTunnel) return DEFAULT_ROUTES
        val lan = configured.map { it.trim() }.filter { it.isNotEmpty() && it !in DEFAULT_ROUTES && it != "0.0.0.0/1" && it != "128.0.0.0/1" }
        if (lan.isNotEmpty()) return lan.distinct()
        val tunnelNets = interfaceAddresses.mapNotNull { subnetOf(it.trim()) }
        return (tunnelNets + PRIVATE_RANGES).distinct()
    }

    /** "10.8.0.5/24" → "10.8.0.0/24"; host-only addresses (/32) become their /24. IPv6 is passed through. */
    fun subnetOf(address: String): String? {
        val ip = address.substringBefore('/')
        val prefix = address.substringAfter('/', "").toIntOrNull()
        if (':' in ip) return if (prefix != null && prefix < 128) address else null
        val parts = ip.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return null
        val p = if (prefix == null || prefix >= 32) 24 else prefix
        val n = (parts[0] shl 24) or (parts[1] shl 16) or (parts[2] shl 8) or parts[3]
        val mask = if (p == 0) 0 else (-1 shl (32 - p))
        val net = n and mask
        return "${net ushr 24 and 255}.${net ushr 16 and 255}.${net ushr 8 and 255}.${net and 255}/$p"
    }

    /** Builds a wg-quick config for "Add this device" from the fields a NAS WireGuard server shows. */
    fun buildConfig(
        privateKey: String,
        address: String,
        dns: String,
        serverPublicKey: String,
        endpoint: String,
        allowedIps: String,
        presharedKey: String = "",
        keepalive: Int = 25,
    ): String = buildString {
        appendLine("[Interface]")
        appendLine("PrivateKey = $privateKey")
        appendLine("Address = ${address.trim()}")
        if (dns.isNotBlank()) appendLine("DNS = ${dns.trim()}")
        appendLine()
        appendLine("[Peer]")
        appendLine("PublicKey = ${serverPublicKey.trim()}")
        if (presharedKey.isNotBlank()) appendLine("PresharedKey = ${presharedKey.trim()}")
        appendLine("Endpoint = ${endpoint.trim()}")
        appendLine("AllowedIPs = ${allowedIps.trim().ifEmpty { DEFAULT_ROUTES.joinToString(", ") }}")
        if (keepalive > 0) appendLine("PersistentKeepalive = $keepalive")
    }

    /** Lightweight read of a wg-quick file for display, tolerant of comments and casing. */
    data class Summary(val addresses: List<String>, val dns: List<String>, val peers: List<PeerSummary>)
    data class PeerSummary(val publicKey: String, val endpoint: String?, val allowedIps: List<String>, val name: String?)

    fun summarize(text: String): Summary {
        val addresses = mutableListOf<String>()
        val dns = mutableListOf<String>()
        val peers = mutableListOf<PeerSummary>()
        var section = ""
        var pk = ""; var ep: String? = null; var ips = listOf<String>(); var pendingName: String? = null; var peerName: String? = null
        fun flushPeer() {
            if (section == "peer" && pk.isNotEmpty()) peers += PeerSummary(pk, ep, ips, peerName)
            pk = ""; ep = null; ips = emptyList(); peerName = null
        }
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("#") -> {
                    // wg-easy and others write "# Name = laptop" / "# laptop" above a [Peer]
                    pendingName = line.removePrefix("#").trim().substringAfter("Name =", line.removePrefix("#").trim()).trim().ifEmpty { null }
                }
                line.equals("[Interface]", true) -> { flushPeer(); section = "interface"; pendingName = null }
                line.equals("[Peer]", true) -> { flushPeer(); section = "peer"; peerName = pendingName; pendingName = null }
                '=' in line -> {
                    val key = line.substringBefore('=').trim().lowercase()
                    val value = line.substringAfter('=').trim()
                    fun list() = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    when (section to key) {
                        "interface" to "address" -> addresses += list()
                        "interface" to "dns" -> dns += list()
                        "peer" to "publickey" -> pk = value
                        "peer" to "endpoint" -> ep = value
                        "peer" to "allowedips" -> ips = ips + list()
                    }
                }
            }
        }
        flushPeer()
        return Summary(addresses, dns, peers)
    }
}
