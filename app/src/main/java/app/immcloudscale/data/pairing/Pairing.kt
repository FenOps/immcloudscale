package app.immcloudscale.data.pairing

import android.net.Uri
import app.immcloudscale.data.AppSettings
import app.immcloudscale.data.HttpException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** What an ImmCloudScale server returns when a phone redeems a pairing code (see server/internal/core). */
@Serializable
data class PairResult(
    val deviceId: String,
    val deviceToken: String = "",
    val server: ServerInfo,
    val user: UserInfo,
    val immich: ImmichInfo,
    val nextcloud: NextcloudInfo,
    val tailscale: TailscaleInfo? = null,
) {
    @Serializable data class ServerInfo(val name: String = "", val version: String = "", val id: String = "")
    @Serializable data class UserInfo(val name: String, val email: String = "", val isAdmin: Boolean = false)
    @Serializable data class ImmichInfo(val url: String, val apiKey: String)
    @Serializable data class NextcloudInfo(val url: String, val user: String, val appPassword: String)
    /** Present when the server is on a tailnet; authKey lets the app join it without a sign-in. */
    @Serializable data class TailscaleInfo(val ip: String, val authKey: String = "", val controlUrl: String = "")
}

@Serializable
private data class PairRequest(
    val code: String,
    val deviceName: String,
    /** This phone's previous device ID, so the server retires the old entry when it pairs again. */
    val replacesDevice: String = "",
)

/**
 * A pairing link or invite. [expires] is when the code stops working (epoch seconds, 0 if not given).
 * [tailscaleIp] (and [tailscaleKey], [controlUrl]) let a phone away from home reach the server through Tailscale.
 */
data class PairLink(
    val server: String,
    val code: String,
    val expires: Long = 0,
    val tailscaleIp: String = "",
    val tailscaleKey: String = "",
    val controlUrl: String = "",
) {
    /** Past the deadline, with two minutes' grace for a phone clock that runs fast (the server has the last word). */
    fun expired(nowSeconds: Long = System.currentTimeMillis() / 1000) = expires > 0 && nowSeconds >= expires + 120
}

object Pairing {
    const val DEFAULT_PORT = 8790
    private val json = Json { ignoreUnknownKeys = true }

    /** "192.168.1.50" → "http://192.168.1.50:8790"; full URLs are kept. */
    fun normalizeServer(input: String): String {
        var s = input.trim().trimEnd('/')
        if (s.isEmpty()) return s
        if (!s.contains("://")) s = "http://$s"
        val afterScheme = s.substringAfter("://")
        val hostPart = afterScheme.substringBefore('/')
        val hasPort = if (hostPart.startsWith("[")) hostPart.contains("]:") else hostPart.contains(':')
        if (!hasPort) s = s.substringBefore("://") + "://" + hostPart + ":" + DEFAULT_PORT + afterScheme.removePrefix(hostPart)
        return s
    }

    /** Codes are typed loosely: "k7qx m2pa" → "K7QX-M2PA". */
    fun normalizeCode(input: String): String {
        val c = input.uppercase().filter { it.isLetterOrDigit() }
        return if (c.length == 8) c.substring(0, 4) + "-" + c.substring(4) else c
    }

    /** Parses the QR / deep link `immcloudscale://pair?server=…&code=…` (plus exp, ts, tskey and control in invites). */
    fun parseLink(text: String): PairLink? {
        val uri = runCatching { Uri.parse(text.trim()) }.getOrNull() ?: return null
        if (uri.scheme != "immcloudscale" || uri.host != "pair") return null
        val server = uri.getQueryParameter("server") ?: return null
        val code = uri.getQueryParameter("code") ?: return null
        return PairLink(
            normalizeServer(server), normalizeCode(code),
            expires = uri.getQueryParameter("exp")?.toLongOrNull() ?: 0,
            tailscaleIp = uri.getQueryParameter("ts").orEmpty(),
            tailscaleKey = uri.getQueryParameter("tskey").orEmpty(),
            controlUrl = uri.getQueryParameter("control").orEmpty(),
        )
    }

    /** Finds an invite link inside a pasted message (the link is usually one line among others). */
    fun findLink(text: String): PairLink? =
        Regex("""immcloudscale://pair\?\S+""").find(text)?.value?.let { parseLink(it.trimEnd('.', ',', ')', '"', '\'')) }

    suspend fun pair(
        http: OkHttpClient, server: String, code: String, deviceName: String, replacesDevice: String = "",
    ): PairResult =
        withContext(Dispatchers.IO) {
            val body = json.encodeToString(PairRequest.serializer(), PairRequest(normalizeCode(code), deviceName, replacesDevice))
            val req = Request.Builder().url(normalizeServer(server) + "/api/v1/pair")
                .post(body.toRequestBody("application/json".toMediaType())).build()
            val call = runCatching { http.newCall(req).execute() }.getOrElse { e ->
                val host = normalizeServer(server).substringAfter("://")
                throw HttpException(
                    0,
                    when (e) {
                        is java.net.UnknownHostException -> "Can't find the server \"$host\". Check the address."
                        is java.net.SocketTimeoutException, is java.net.ConnectException, is java.net.NoRouteToHostException ->
                            "Can't reach the server at $host. Make sure this phone is on the same Wi-Fi as the server, " +
                                "and that the server is switched on."
                        else -> e.message ?: "Couldn't connect to the server"
                    },
                )
            }
            call.use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) {
                    val msg = runCatching { json.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull()
                    throw HttpException(r.code, msg ?: "The server answered HTTP ${r.code}")
                }
                json.decodeFromString(PairResult.serializer(), text)
            }
        }

    /** Applies a pairing to the settings. */
    fun apply(s: AppSettings, r: PairResult, server: String): AppSettings {
        return s.copy(
            immichUrl = r.immich.url.trimEnd('/'), immichToken = r.immich.apiKey, immichTokenIsApiKey = true,
            immichUserName = r.user.name, immichEmail = r.user.email,
            ncUrl = r.nextcloud.url.trimEnd('/'), ncUser = r.nextcloud.user, ncAppPassword = r.nextcloud.appPassword,
            ncUserId = r.nextcloud.user,
            pairedServer = normalizeServer(server), pairedDeviceId = r.deviceId, pairedServerName = r.server.name,
            pairedServerId = r.server.id,
            deviceToken = r.deviceToken, isAdmin = r.user.isAdmin,
            joinServer = "", joinId = "", joinToken = "", joinName = "",
            // Remote access comes on by itself when the server offers Tailscale: away from home the app connects
            // on its own (with the server's key) or asks to sign in once.
            tsEnabled = if (r.tailscale != null) true else s.tsEnabled,
            tsServerIp = r.tailscale?.ip ?: s.tsServerIp,
            tsAuthKey = r.tailscale?.authKey ?: s.tsAuthKey,
            tsControlUrl = r.tailscale?.controlUrl ?: s.tsControlUrl,
        )
    }
}
