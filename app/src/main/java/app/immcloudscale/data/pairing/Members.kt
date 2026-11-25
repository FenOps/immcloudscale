package app.immcloudscale.data.pairing

import app.immcloudscale.data.AppSettings
import app.immcloudscale.data.HttpException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@Serializable data class JoinTicket(val id: String, val token: String)
@Serializable data class JoinStatus(val status: String, val server: String = "", val code: String? = null)
@Serializable data class Me(val name: String, val isAdmin: Boolean = false, val pendingRequests: Int = 0)
@Serializable data class JoinRequestInfo(
    val id: String,
    val name: String,
    val email: String = "",
    val device: String = "",
    val created: String = "",
    val status: String = "pending",
    val code: String? = null,
)
@Serializable data class Member(val name: String, val email: String = "", val isAdmin: Boolean = false, val devices: Int = 0)
@Serializable data class Features(val machineLearning: Boolean = false, val memoryGB: Double = 0.0, val applying: Boolean = false)
@Serializable data class PairCode(val code: String, val server: String = "", val link: String = "", val expires: String = "", val name: String = "")
@Serializable data class InviteInfo(val name: String, val code: String, val expires: String, val createdBy: String = "")

/**
 * Joining a server (someone without an account asks; an admin approves) and managing members from an admin's
 * phone, which signs its requests with the secret it got at pairing.
 */
class MembersClient(private val http: OkHttpClient, private val settings: () -> AppSettings) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun requestJoin(server: String, name: String, email: String, device: String): JoinTicket =
        parse(send(Pairing.normalizeServer(server) + "/api/v1/join", "POST", buildJsonObject {
            put("name", name.trim().lowercase()); put("email", email.trim()); put("device", device)
        }.toString()))

    suspend fun joinStatus(server: String, ticket: JoinTicket): JoinStatus =
        parse(send(Pairing.normalizeServer(server) + "/api/v1/join/" + ticket.id, "GET", null) { header("X-ICS-Token", ticket.token) })

    suspend fun me(): Me = parse(manage("GET", "/api/v1/me"))
    suspend fun requests(): List<JoinRequestInfo> = parse(manage("GET", "/api/v1/manage/requests"))
    suspend fun members(): List<Member> = parse(manage("GET", "/api/v1/manage/users"))

    /** Approves a request; the result's code is what the person's app pairs with (or what they scan). */
    suspend fun approve(id: String): PairCode {
        val o = json.parseToJsonElement(manage("POST", "/api/v1/manage/requests/$id/approve")).jsonObject
        val code = o["request"]?.jsonObject?.get("code")?.jsonPrimitive?.content.orEmpty()
        return PairCode(code, o["server"]?.jsonPrimitive?.content.orEmpty(), o["link"]?.jsonPrimitive?.content.orEmpty())
    }

    suspend fun decline(id: String) { manage("POST", "/api/v1/manage/requests/$id/decline") }

    suspend fun setAdmin(name: String, admin: Boolean) {
        manage("POST", "/api/v1/manage/users/$name/admin", buildJsonObject { put("admin", admin) }.toString())
    }

    suspend fun features(): Features = parse(manage("GET", "/api/v1/manage/features"))

    /** Switches face recognition, smart search and duplicate detection on the server (takes a few minutes). */
    suspend fun setMachineLearning(on: Boolean): Features =
        parse(manage("POST", "/api/v1/manage/features", buildJsonObject { put("machineLearning", on) }.toString()))

    /** An Immich session for this person, for the locked folder (which API keys can't open). */
    suspend fun session(): String =
        json.parseToJsonElement(manage("POST", "/api/v1/me/session")).jsonObject["accessToken"]?.jsonPrimitive?.content.orEmpty()

    /** An invite (single use, 15 minutes) for [name], who is created on the server if new. */
    suspend fun invite(name: String, email: String): PairCode = parse(manage("POST", "/api/v1/manage/invites", buildJsonObject {
        put("name", name.trim().lowercase()); put("email", email.trim())
    }.toString()))

    suspend fun invites(): List<InviteInfo> = parse(manage("GET", "/api/v1/manage/invites"))

    suspend fun cancelInvite(code: String) { manage("DELETE", "/api/v1/manage/invites/$code") }

    /** A new pairing code for a member's (new) phone. */
    suspend fun pairCode(name: String): PairCode = parse(manage("POST", "/api/v1/manage/users/$name/pair"))

    private suspend fun manage(method: String, path: String, body: String? = null): String {
        val s = settings()
        if (s.deviceToken.isBlank() || s.pairedDeviceId.isBlank()) {
            throw HttpException(401, "Pair this phone again to manage members from it (Account → Servers → Pair again)")
        }
        return send(s.pairedServer.trimEnd('/') + path, method, body ?: if (method == "POST") "{}" else null) {
            header("Authorization", "Device ${s.pairedDeviceId}:${s.deviceToken}")
        }
    }

    private suspend fun send(url: String, method: String, body: String?, extra: Request.Builder.() -> Unit = {}): String =
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url(url).method(method, body?.toRequestBody("application/json".toMediaType())).apply(extra).build()
            http.newCall(req).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) {
                    val msg = runCatching { json.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull()
                    throw HttpException(r.code, msg ?: "The server answered HTTP ${r.code}")
                }
                text
            }
        }

    private inline fun <reified T> parse(text: String): T = json.decodeFromString(text)
}
