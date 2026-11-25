package app.immcloudscale.data.immich

import app.immcloudscale.data.HttpException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant

/** Immich REST client (targets the v1.12x – v2.x API). Auth headers are added by [app.immcloudscale.data.AuthInterceptor]. */
class ImmichClient(
    private val http: OkHttpClient,
    private val baseUrl: () -> String,
) {
    val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }
    private val jsonType = "application/json".toMediaType()

    private fun api(path: String) = baseUrl().trimEnd('/') + "/api" + path

    fun thumbnailUrl(assetId: String, preview: Boolean = false) =
        api("/assets/$assetId/thumbnail?size=${if (preview) "preview" else "thumbnail"}")

    fun originalUrl(assetId: String) = api("/assets/$assetId/original")
    fun videoUrl(assetId: String) = api("/assets/$assetId/video/playback")
    fun personThumbnailUrl(personId: String) = api("/people/$personId/thumbnail")
    fun shareUrl(key: String) = baseUrl().trimEnd('/') + "/share/" + key

    private suspend fun call(method: String, path: String, body: JsonElement? = null): String =
        withContext(Dispatchers.IO) {
            val rb: RequestBody? = body?.toString()?.toRequestBody(jsonType)
                ?: if (method in setOf("POST", "PUT", "DELETE", "PATCH")) "{}".toRequestBody(jsonType) else null
            val req = Request.Builder().url(api(path)).method(method, rb).header("Accept", "application/json").build()
            http.newCall(req).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw HttpException(r.code, errorMessage(r.code, text))
                text
            }
        }

    private fun errorMessage(code: Int, body: String): String {
        val msg = runCatching {
            (json.parseToJsonElement(body) as? JsonObject)?.get("message")?.let {
                if (it is JsonArray) it.joinToString { e -> (e as? JsonPrimitive)?.content.orEmpty() }
                else (it as? JsonPrimitive)?.content
            }
        }.getOrNull()
        return msg ?: "Immich returned HTTP $code"
    }

    private inline fun <reified T> parse(text: String): T = json.decodeFromString(text)

    // ---- account ----

    suspend fun login(email: String, password: String): LoginResponse = parse(
        call("POST", "/auth/login", buildJsonObject { put("email", email); put("password", password) }),
    )

    suspend fun me(): UserDto = parse(call("GET", "/users/me"))
    suspend fun about(): ServerAbout = parse(call("GET", "/server/about"))
    suspend fun storage(): ServerStorage = parse(call("GET", "/server/storage"))

    // ---- browsing ----

    /** One page of a library slice, newest first. Page numbers start at 1. */
    suspend fun assets(query: AssetQuery, page: Int, size: Int = 200): AssetPage {
        if (query is AssetQuery.Smart) {
            return parse<SearchResponse>(
                call("POST", "/search/smart", buildJsonObject {
                    put("query", query.text); put("page", page); put("size", size); put("withExif", true)
                }),
            ).assets
        }
        val body = buildJsonObject {
            put("page", page); put("size", size); put("order", "desc"); put("withExif", true)
            when (query) {
                AssetQuery.Timeline -> put("visibility", "timeline")
                AssetQuery.Favorites -> { put("isFavorite", true); put("visibility", "timeline") }
                AssetQuery.Archive -> put("visibility", "archive")
                AssetQuery.Trash -> { put("withDeleted", true); put("trashedAfter", "1970-01-01T00:00:00.000Z") }
                AssetQuery.Videos -> { put("type", "VIDEO"); put("visibility", "timeline") }
                is AssetQuery.Album -> putJsonArray("albumIds") { add(JsonPrimitive(query.id)) }
                is AssetQuery.Person -> putJsonArray("personIds") { add(JsonPrimitive(query.id)) }
                is AssetQuery.Place -> put("city", query.city)
                is AssetQuery.Smart -> Unit
            }
        }
        return parse<SearchResponse>(call("POST", "/search/metadata", body)).assets
    }

    suspend fun asset(id: String): AssetDto = parse(call("GET", "/assets/$id"))

    /** Every asset one phone uploaded (not in the trash). */
    suspend fun assetsFrom(deviceId: String): List<AssetDto> {
        val out = mutableListOf<AssetDto>()
        var page = 1
        while (true) {
            val p = parse<SearchResponse>(call("POST", "/search/metadata", buildJsonObject {
                put("deviceId", deviceId); put("page", page); put("size", 1000)
            })).assets
            out += p.items
            if (p.nextPage == null || p.items.isEmpty()) return out
            page++
        }
    }

    /** Assets from one phone that went to the trash after [since] (newest first, up to 1000). */
    suspend fun trashedFrom(deviceId: String, since: Instant): List<AssetDto> = parse<SearchResponse>(
        call("POST", "/search/metadata", buildJsonObject {
            put("deviceId", deviceId); put("withDeleted", true); put("trashedAfter", since.toString()); put("size", 1000)
        }),
    ).assets.items.filter { it.isTrashed }
    suspend fun albums(): List<AlbumDto> = parse(call("GET", "/albums"))
    suspend fun sharedAlbums(): List<AlbumDto> = parse(call("GET", "/albums?shared=true"))
    suspend fun people(): List<PersonDto> = parse<PeopleResponse>(call("GET", "/people")).people.filter { !it.isHidden }
    /** One representative asset per city: Immich's "Places". */
    suspend fun places(): List<AssetDto> = parse(call("GET", "/search/cities"))
    suspend fun memories(): List<MemoryDto> =
        runCatching { parse<List<MemoryDto>>(call("GET", "/memories?for=${Instant.now()}")) }.getOrDefault(emptyList())

    // ---- editing ----

    suspend fun setFavorite(ids: List<String>, favorite: Boolean) {
        call("PUT", "/assets", buildJsonObject { ids(ids); put("isFavorite", favorite) })
    }

    suspend fun setArchived(ids: List<String>, archived: Boolean) {
        call("PUT", "/assets", buildJsonObject { ids(ids); put("visibility", if (archived) "archive" else "timeline") })
    }

    /** Moves to trash (or deletes for good with [force]). */
    suspend fun delete(ids: List<String>, force: Boolean = false) {
        call("DELETE", "/assets", buildJsonObject { ids(ids); put("force", force) })
    }

    suspend fun restore(ids: List<String>) {
        call("POST", "/trash/restore/assets", buildJsonObject { ids(ids) })
    }

    suspend fun emptyTrash() { call("POST", "/trash/empty") }

    suspend fun createAlbum(name: String, assetIds: List<String>): AlbumDto = parse(
        call("POST", "/albums", buildJsonObject {
            put("albumName", name); putJsonArray("assetIds") { assetIds.forEach { add(JsonPrimitive(it)) } }
        }),
    )

    suspend fun addToAlbum(albumId: String, ids: List<String>) {
        call("PUT", "/albums/$albumId/assets", buildJsonObject { ids(ids) })
    }

    suspend fun createShareLink(ids: List<String>): String {
        val link: SharedLinkDto = parse(
            call("POST", "/shared-links", buildJsonObject {
                put("type", "INDIVIDUAL"); putJsonArray("assetIds") { ids.forEach { add(JsonPrimitive(it)) } }
                put("allowDownload", true); put("showMetadata", true)
            }),
        )
        return shareUrl(link.key)
    }

    // ---- backup ----

    suspend fun bulkUploadCheck(items: List<BulkCheckItem>): List<BulkCheckResult> = parse<BulkCheckResponse>(
        call("POST", "/assets/bulk-upload-check", json.encodeToJsonElement(BulkCheckRequest.serializer(), BulkCheckRequest(items))),
    ).results

    suspend fun upload(
        file: RequestBody,
        fileName: String,
        deviceAssetId: String,
        deviceId: String,
        createdAt: Instant,
        modifiedAt: Instant,
        checksum: String?,
    ): UploadResponse = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("deviceAssetId", deviceAssetId)
            .addFormDataPart("deviceId", deviceId)
            .addFormDataPart("fileCreatedAt", createdAt.toString())
            .addFormDataPart("fileModifiedAt", modifiedAt.toString())
            .addFormDataPart("isFavorite", "false")
            .addFormDataPart("assetData", fileName, file)
            .build()
        val req = Request.Builder().url(api("/assets")).post(body).apply {
            if (checksum != null) header("x-immich-checksum", checksum)
        }.build()
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw HttpException(r.code, errorMessage(r.code, text))
            json.decodeFromString(text)
        }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.ids(ids: List<String>) =
        putJsonArray("ids") { ids.forEach { add(JsonPrimitive(it)) } }
}
