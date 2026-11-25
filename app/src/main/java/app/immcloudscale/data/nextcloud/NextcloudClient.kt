package app.immcloudscale.data.nextcloud

import app.immcloudscale.data.HttpException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.InputStream
import java.net.URLEncoder

/** A file or folder in Nextcloud. [path] is relative to the user's root and starts with "/". */
data class CloudFile(
    val path: String,
    val name: String,
    val isFolder: Boolean,
    val size: Long,
    val modified: Long,
    val contentType: String?,
    val fileId: Long?,
    val favorite: Boolean,
    val hasPreview: Boolean,
    val shared: Boolean,
    val etag: String? = null,
) {
    val extension get() = name.substringAfterLast('.', "").lowercase()
    val isImage get() = contentType?.startsWith("image/") == true
    val isVideo get() = contentType?.startsWith("video/") == true
    val parent get() = path.trimEnd('/').substringBeforeLast('/', "").ifEmpty { "/" }
}

data class Quota(val used: Long, val available: Long) {
    val total get() = if (available >= 0) used + available else -1
}

data class ShareInfo(val id: String, val path: String, val url: String?, val shareType: Int, val ownerName: String?)

@Serializable
private data class LoginFlowStart(val poll: Poll, val login: String) {
    @Serializable data class Poll(val token: String, val endpoint: String)
}

@Serializable
data class LoginFlowResult(val server: String, val loginName: String, val appPassword: String)

/** WebDAV + OCS client for Nextcloud. Basic auth is added by [app.immcloudscale.data.AuthInterceptor]. */
class NextcloudClient(
    private val http: OkHttpClient,
    private val baseUrl: () -> String,
    private val user: () -> String,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val xml = "application/xml; charset=utf-8".toMediaType()

    private fun base() = baseUrl().trimEnd('/')
    private fun davRoot() = "${base()}/remote.php/dav/files/${enc(user())}"

    /** URL of a file, encoding each path segment. */
    fun fileUrl(path: String): String =
        davRoot() + path.split('/').filter { it.isNotEmpty() }.joinToString("") { "/" + enc(it) }

    fun previewUrl(f: CloudFile, px: Int = 256): String? =
        f.fileId?.takeIf { f.hasPreview }?.let { "${base()}/index.php/core/preview?fileId=$it&x=$px&y=$px&a=1&forceIcon=0" }

    private suspend fun exec(req: Request): String = withContext(Dispatchers.IO) {
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw HttpException(r.code, ncError(r.code))
            text
        }
    }

    private fun ncError(code: Int) = when (code) {
        401 -> "Nextcloud rejected the user name or app password"
        403 -> "You don't have permission to do that"
        404 -> "Not found on the server"
        405 -> "That item already exists"
        409 -> "The parent folder doesn't exist"
        412 -> "An item with that name already exists"
        507 -> "Your Nextcloud storage is full"
        else -> "Nextcloud returned HTTP $code"
    }

    // ---- listing ----

    suspend fun list(path: String): List<CloudFile> {
        val req = Request.Builder().url(fileUrl(path).let { if (it.endsWith('/')) it else "$it/" })
            .method("PROPFIND", PROPFIND_BODY.toRequestBody(xml)).header("Depth", "1").build()
        val root = davRoot().toHttpUrl().encodedPath
        return WebDavParser.parse(exec(req), root).filter { it.path.trimEnd('/') != path.trimEnd('/') }
    }

    suspend fun stat(path: String): CloudFile? {
        val req = Request.Builder().url(fileUrl(path))
            .method("PROPFIND", PROPFIND_BODY.toRequestBody(xml)).header("Depth", "0").build()
        return WebDavParser.parse(exec(req), davRoot().toHttpUrl().encodedPath).firstOrNull()
    }

    suspend fun quota(): Quota {
        val req = Request.Builder().url(davRoot() + "/")
            .method("PROPFIND", QUOTA_BODY.toRequestBody(xml)).header("Depth", "0").build()
        return WebDavParser.parseQuota(exec(req))
    }

    suspend fun favorites(): List<CloudFile> {
        val req = Request.Builder().url(davRoot() + "/").method("REPORT", FAVORITES_BODY.toRequestBody(xml)).build()
        return WebDavParser.parse(exec(req), davRoot().toHttpUrl().encodedPath)
    }

    /** Recently modified files (Drive's "Home" / Suggested), or a name search when [nameLike] is set. */
    suspend fun search(nameLike: String? = null, limit: Int = 50): List<CloudFile> {
        val body = searchBody(user(), nameLike, limit)
        val req = Request.Builder().url("${base()}/remote.php/dav/").method("SEARCH", body.toRequestBody(xml)).build()
        return WebDavParser.parse(exec(req), davRoot().toHttpUrl().encodedPath)
    }

    // ---- changing ----

    suspend fun createFolder(path: String) {
        exec(Request.Builder().url(fileUrl(path)).method("MKCOL", null).build())
    }

    suspend fun upload(path: String, body: RequestBody) {
        exec(Request.Builder().url(fileUrl(path)).put(body).build())
    }

    suspend fun move(from: String, to: String, copy: Boolean = false) {
        exec(
            Request.Builder().url(fileUrl(from)).method(if (copy) "COPY" else "MOVE", null)
                .header("Destination", fileUrl(to)).header("Overwrite", "F").build(),
        )
    }

    suspend fun delete(path: String) {
        exec(Request.Builder().url(fileUrl(path)).delete().build())
    }

    suspend fun setFavorite(path: String, favorite: Boolean) {
        val body = """<?xml version="1.0"?>
            <d:propertyupdate xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">
              <d:set><d:prop><oc:favorite>${if (favorite) 1 else 0}</oc:favorite></d:prop></d:set>
            </d:propertyupdate>""".trimIndent()
        exec(Request.Builder().url(fileUrl(path)).method("PROPPATCH", body.toRequestBody(xml)).build())
    }

    suspend fun download(path: String, into: File, onProgress: (Float) -> Unit = {}) = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(fileUrl(path)).build()).execute().use { r ->
            if (!r.isSuccessful) throw HttpException(r.code, ncError(r.code))
            val body = r.body ?: return@use
            val total = body.contentLength()
            into.parentFile?.mkdirs()
            val tmp = File(into.path + ".part")
            body.byteStream().use { input -> tmp.outputStream().use { out -> copy(input, out, total, onProgress) } }
            tmp.renameTo(into)
        }
    }

    private fun copy(input: InputStream, out: java.io.OutputStream, total: Long, onProgress: (Float) -> Unit) {
        val buf = ByteArray(64 * 1024)
        var done = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            done += n
            if (total > 0) onProgress(done.toFloat() / total)
        }
    }

    suspend fun readText(path: String, maxBytes: Long = 512 * 1024): String = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(fileUrl(path)).header("Range", "bytes=0-${maxBytes - 1}").build()).execute().use { r ->
            if (!r.isSuccessful) throw HttpException(r.code, ncError(r.code))
            r.body?.string().orEmpty()
        }
    }

    /** The signed-in account's internal user ID (WebDAV paths use it; the login name can be an email). */
    suspend fun currentUserId(): String {
        val text = exec(Request.Builder().url("${base()}/ocs/v1.php/cloud/user?format=json").header("OCS-APIRequest", "true").build())
        return json.parseToJsonElement(text).jsonObject["ocs"]!!.jsonObject["data"]!!.jsonObject["id"]!!.jsonPrimitive.content
    }

    // ---- sharing (OCS) ----

    private fun ocs(path: String) = "${base()}/ocs/v2.php/apps/files_sharing/api/v1$path"

    suspend fun createShareLink(path: String): String {
        val form = FormBody.Builder().add("path", path).add("shareType", "3").add("permissions", "1").build()
        val text = exec(Request.Builder().url(ocs("/shares?format=json")).post(form).header("OCS-APIRequest", "true").build())
        val data = json.parseToJsonElement(text).jsonObject["ocs"]!!.jsonObject["data"]!!.jsonObject
        return data["url"]!!.jsonPrimitive.content
    }

    /** Files shared with me, or (when [mine]) shared by me. */
    suspend fun shares(mine: Boolean): List<ShareInfo> {
        val q = if (mine) "" else "&shared_with_me=true"
        val text = exec(Request.Builder().url(ocs("/shares?format=json$q")).header("OCS-APIRequest", "true").build())
        val arr = json.parseToJsonElement(text).jsonObject["ocs"]!!.jsonObject["data"]!!.jsonArray
        return arr.map { it.jsonObject }.map { o: JsonObject ->
            ShareInfo(
                id = o["id"]!!.jsonPrimitive.content,
                path = o["path"]?.jsonPrimitive?.content ?: o["file_target"]?.jsonPrimitive?.content ?: "",
                url = o["url"]?.jsonPrimitive?.content,
                shareType = o["share_type"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                ownerName = o["displayname_owner"]?.jsonPrimitive?.content,
            )
        }
    }

    // ---- login flow v2 (browser sign-in that yields an app password) ----

    suspend fun startLoginFlow(server: String): Pair<String, suspend () -> LoginFlowResult> {
        val text = exec(Request.Builder().url("${server.trimEnd('/')}/index.php/login/v2").post(FormBody.Builder().build()).build())
        val start = json.decodeFromString<LoginFlowStart>(text)
        val poll: suspend () -> LoginFlowResult = {
            var result: LoginFlowResult? = null
            repeat(300) { // ~10 minutes
                if (result == null) {
                    val r = withContext(Dispatchers.IO) {
                        http.newCall(
                            Request.Builder().url(start.poll.endpoint)
                                .post(FormBody.Builder().add("token", start.poll.token).build()).build(),
                        ).execute().use { if (it.isSuccessful) it.body?.string() else null }
                    }
                    if (r != null) result = json.decodeFromString<LoginFlowResult>(r) else delay(2000)
                }
            }
            result ?: throw HttpException(408, "Sign-in timed out")
        }
        return start.login to poll
    }

    companion object {
        fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

        private const val PROPS = """
            <d:prop>
              <d:displayname/><d:getlastmodified/><d:getcontentlength/><d:getcontenttype/>
              <d:resourcetype/><d:getetag/><oc:fileid/><oc:favorite/><oc:size/>
              <nc:has-preview/><oc:share-types/>
            </d:prop>"""

        const val PROPFIND_BODY = """<?xml version="1.0"?>
<d:propfind xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns">$PROPS
</d:propfind>"""

        const val QUOTA_BODY = """<?xml version="1.0"?>
<d:propfind xmlns:d="DAV:"><d:prop><d:quota-used-bytes/><d:quota-available-bytes/></d:prop></d:propfind>"""

        const val FAVORITES_BODY = """<?xml version="1.0"?>
<oc:filter-files xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns">$PROPS
  <oc:filter-rules><oc:favorite>1</oc:favorite></oc:filter-rules>
</oc:filter-files>"""

        fun searchBody(user: String, nameLike: String?, limit: Int): String {
            val where = if (nameLike.isNullOrBlank()) {
                "<d:not><d:is-collection/></d:not>"
            } else {
                val safe = nameLike.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                "<d:like><d:prop><d:displayname/></d:prop><d:literal>%$safe%</d:literal></d:like>"
            }
            return """<?xml version="1.0" encoding="UTF-8"?>
<d:searchrequest xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns">
  <d:basicsearch>
    <d:select>$PROPS</d:select>
    <d:from><d:scope><d:href>/files/${enc(user)}</d:href><d:depth>infinity</d:depth></d:scope></d:from>
    <d:where>$where</d:where>
    <d:orderby><d:order><d:prop><d:getlastmodified/></d:prop><d:descending/></d:order></d:orderby>
    <d:limit><d:nresults>$limit</d:nresults></d:limit>
  </d:basicsearch>
</d:searchrequest>"""
        }
    }
}
