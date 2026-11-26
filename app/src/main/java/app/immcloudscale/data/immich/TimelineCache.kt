package app.immcloudscale.data.immich

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * The photo timeline kept on the phone, so the Photos page draws at once and works offline; it's brought up to
 * date in the background. One file per server and account, in the app's private storage (not backed up).
 */
class TimelineCache(private val dir: File) {
    @Serializable
    private data class Saved(val synced: Long = 0, val assets: List<AssetDto> = emptyList())

    data class Timeline(val assets: List<AssetDto>, val synced: Long)

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false }

    private fun file(server: String, account: String): File {
        val h = MessageDigest.getInstance("SHA-256").digest("$server\u0000$account".toByteArray())
            .take(12).joinToString("") { "%02x".format(it) }
        return File(dir, "timeline-$h.json")
    }

    suspend fun load(server: String, account: String): Timeline? = withContext(Dispatchers.IO) {
        val f = file(server, account)
        if (!f.exists()) return@withContext null
        runCatching { json.decodeFromString(Saved.serializer(), f.readText()) }.getOrNull()?.let { Timeline(it.assets, it.synced) }
    }

    /** Saves [assets] (photos only on the phone are left out), keeping just what the grid needs. */
    suspend fun save(server: String, account: String, assets: List<AssetDto>, synced: Long) = withContext(Dispatchers.IO) {
        val slim = assets.filter { !it.isLocal }.map { a ->
            a.copy(people = emptyList(), exifInfo = a.exifInfo?.let { e -> ExifInfo(city = e.city, state = e.state, country = e.country) })
        }
        dir.mkdirs()
        val f = file(server, account)
        val tmp = File(dir, f.name + ".tmp")
        tmp.writeText(json.encodeToString(Saved.serializer(), Saved(synced, slim)))
        tmp.renameTo(f)
    }

    /** Forgets every saved timeline (e.g. on signing out). */
    suspend fun clear() = withContext(Dispatchers.IO) {
        dir.listFiles { f -> f.name.startsWith("timeline-") }?.forEach { it.delete() }
    }
}
