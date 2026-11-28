package app.immcloudscale.data.mail

import app.immcloudscale.data.AppSettings
import app.immcloudscale.data.HttpException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

@Serializable data class Mailbox(
    val id: String,
    val email: String,
    val person: String = "",
    val count: Int = 0,
    val bytes: Long = 0,
    val syncing: Boolean = false,
    val paused: Boolean = false,
    val lastSync: String? = null,
    val error: String? = null,
    val deletesOnServer: Boolean = false,
)
@Serializable data class MailStatus(
    val configured: Boolean = false,
    val reachable: Boolean = false,
    val accounts: List<Mailbox> = emptyList(),
    val unassigned: List<Mailbox> = emptyList(),
)
@Serializable data class MailItem(
    val id: String,
    val account: String,
    val mailbox: String = "",
    val folder: String = "",
    val subject: String = "",
    val preview: String = "",
    val from: String = "",
    val fromName: String? = null,
    val to: List<String> = emptyList(),
    val cc: List<String>? = null,
    val date: String = "",
    val size: Long = 0,
    val attachments: Int = 0,
) {
    val sender get() = fromName?.takeIf { it.isNotBlank() } ?: from
}
@Serializable data class MailPage(val items: List<MailItem> = emptyList(), val total: Int = 0, val page: Int = 1, val more: Boolean = false)
@Serializable data class MailAttachment(
    @SerialName("filename") val name: String? = null,
    @SerialName("file_type") val type: String = "application/octet-stream",
    val size: Long = 0,
    @SerialName("content_hash") val hash: String,
    val inline: Boolean = false,
)
@Serializable data class MailMessage(
    val id: String,
    val account: String,
    val mailbox: String = "",
    val subject: String = "",
    val from: String = "",
    val fromName: String? = null,
    val to: List<String> = emptyList(),
    val cc: List<String>? = null,
    val date: String = "",
    val text: String? = null,
    val html: String? = null,
    val hasRemote: Boolean = false,
    val attachmentList: List<MailAttachment> = emptyList(),
) {
    val sender get() = fromName?.takeIf { it.isNotBlank() } ?: from
}
@Serializable data class MailDeleteFail(val account: String, val id: String, val error: String)
@Serializable data class MailDeleteResult(val deleted: Int = 0, val failed: List<MailDeleteFail> = emptyList())

/** A mailbox to start archiving; [host] can be left out for Gmail, Yahoo and iCloud. */
@Serializable data class NewMailbox(
    val email: String,
    val password: String,
    val host: String = "",
    val port: Int = 0,
    val security: String = "ssl",
    val login: String = "",
    @SerialName("for") val forPerson: String = "",
)

/** What to look for; empty fields don't filter. */
data class MailQuery(val text: String = "", val account: String? = null, val hasAttachment: Boolean = false)

/**
 * The email archive on the home server (kept by Bichon there). The server only ever shows a person their own
 * mailboxes; requests are signed with the secret this phone got at pairing, like member management.
 */
class MailClient(private val http: OkHttpClient, private val settings: () -> AppSettings) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    suspend fun status(): MailStatus = parse(call("GET", "/api/v1/me/mail"))
    suspend fun add(m: NewMailbox): Mailbox = parse(call("POST", "/api/v1/me/mail/accounts", json.encodeToString(NewMailbox.serializer(), m)))
    suspend fun setPassword(id: String, password: String) {
        call("POST", "/api/v1/me/mail/accounts/$id/password", json.encodeToString(mapOf("password" to password)))
    }
    suspend fun pause(id: String, paused: Boolean) { call("POST", "/api/v1/me/mail/accounts/$id/pause", """{"paused":$paused}""") }
    suspend fun remove(id: String) { call("DELETE", "/api/v1/me/mail/accounts/$id") }
    suspend fun assign(id: String, person: String) {
        call("POST", "/api/v1/manage/mail/accounts/$id/assign", json.encodeToString(mapOf("person" to person)))
    }

    suspend fun search(q: MailQuery, page: Int): MailPage {
        val body = buildString {
            append("""{"page":$page,"text":${json.encodeToString(q.text.trim())},"hasAttachment":${q.hasAttachment}""")
            if (q.account != null) append(""","account":"${q.account}"""")
            append("}")
        }
        return parse(call("POST", "/api/v1/me/mail/search", body))
    }

    /** A message to read; without [remote], images and anything else from the web are taken out (tracking pixels). */
    suspend fun message(account: String, id: String, remote: Boolean = false): MailMessage =
        parse(call("GET", "/api/v1/me/mail/messages/$account/${enc(id)}" + if (remote) "?remote=1" else ""))

    /** Downloads an attachment into [into]; [size] (when known) catches a download that stopped early. */
    suspend fun attachment(account: String, id: String, hash: String, into: File, size: Long = 0) = withContext(Dispatchers.IO) {
        val req = signed("/api/v1/me/mail/messages/$account/${enc(id)}/attachments/${enc(hash)}").get().build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw HttpException(r.code, errorOf(r.body?.string().orEmpty(), r.code))
            into.parentFile?.mkdirs()
            val tmp = File(into.path + ".part")
            r.body!!.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (size > 0 && tmp.length() != size) {
                tmp.delete()
                throw java.io.IOException("The download stopped early: try again")
            }
            if (!tmp.renameTo(into)) throw java.io.IOException("Couldn't save the attachment")
        }
    }

    /** Deletes from the mail server (to its Trash where it has one) and then from the archive. */
    suspend fun delete(messages: List<Pair<String, String>>): MailDeleteResult {
        val body = messages.joinToString(",", """{"messages":[""", "]}") { (a, id) -> """{"account":"$a","id":${json.encodeToString(id)}}""" }
        return parse(call("POST", "/api/v1/me/mail/delete", body))
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun signed(path: String): Request.Builder {
        val s = settings()
        if (s.deviceToken.isBlank() || s.pairedDeviceId.isBlank()) {
            throw HttpException(401, "Pair this phone with your server again to see your mail (Account → Servers → Pair again)")
        }
        return Request.Builder().url(s.pairedServer.trimEnd('/') + path).header("Authorization", "Device ${s.pairedDeviceId}:${s.deviceToken}")
    }

    private suspend fun call(method: String, path: String, body: String? = null): String = withContext(Dispatchers.IO) {
        val req = signed(path).method(method, (body ?: if (method == "POST") "{}" else null)?.toRequestBody("application/json".toMediaType())).build()
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw HttpException(r.code, errorOf(text, r.code))
            text
        }
    }

    private fun errorOf(text: String, code: Int) =
        runCatching { json.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull() ?: "The server answered HTTP $code"

    private inline fun <reified T> parse(text: String): T = json.decodeFromString(text)
}
