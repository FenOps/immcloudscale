package app.immcloudscale.data.backup

import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import app.immcloudscale.data.immich.AssetDto
import app.immcloudscale.data.immich.ImmichClient

/**
 * This phone's own copies of backed-up photos and videos. Backup tells the server which phone a file came from
 * (deviceId) and its MediaStore ID (the start of deviceAssetId), so the copy can be found again later.
 */
object PhoneCopies {
    @SuppressLint("HardwareIds") // a per-app, per-device ID; only used to recognise this phone's own uploads
    fun deviceId(context: Context) =
        "immcloudscale-" + (Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "android")

    /**
     * What backup recorded for [assets] this phone uploaded: MediaStore ID to file size ("id-size"). MediaStore
     * reuses IDs (after a media rescan, or for an edited file), so a copy only counts when the size matches too.
     */
    fun backedUp(context: Context, assets: List<AssetDto>): Map<Long, Long> {
        val me = deviceId(context)
        return assets.filter { it.deviceId == me }.mapNotNull { a ->
            val id = a.deviceAssetId.substringBefore('-').toLongOrNull()
            val size = a.deviceAssetId.substringAfter('-', "").toLongOrNull()
            if (id != null && size != null) id to size else null
        }.toMap()
    }

    /** Content URIs of copies still on the phone: same ID and, when [sizes] has one, the same size. */
    fun find(context: Context, ids: Collection<Long>, sizes: Map<Long, Long> = emptyMap()): List<Uri> {
        if (ids.isEmpty()) return emptyList()
        val out = mutableListOf<Uri>()
        val cols = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.SIZE)
        for (base in listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)) {
            for (chunk in ids.chunked(500)) {
                val sel = "${MediaStore.MediaColumns._ID} IN (${chunk.joinToString(",") { "?" }})"
                runCatching {
                    context.contentResolver.query(base, cols, sel, chunk.map { it.toString() }.toTypedArray(), null)?.use { c ->
                        while (c.moveToNext()) {
                            val id = c.getLong(0)
                            if (sizes[id].let { it == null || it == c.getLong(1) }) out += ContentUris.withAppendedId(base, id)
                        }
                    }
                }
            }
        }
        return out
    }

    /** How a copy is remembered between a backup run and the person's confirmation: "id-size". */
    fun key(id: Long, size: Long) = "$id-$size"

    /** Back from [key]s to ID and size (entries that don't parse are dropped). */
    fun sizes(keys: Collection<String>): Map<Long, Long> = keys.mapNotNull { k ->
        val id = k.substringBefore('-').toLongOrNull()
        val size = k.substringAfter('-', "").toLongOrNull()
        if (id != null && size != null) id to size else null
    }.toMap()

    /** Copies remembered as [keys] that are still on the phone, unchanged. */
    fun findKeys(context: Context, keys: Collection<String>): List<Uri> = sizes(keys).let { find(context, it.keys, it) }

    /** This phone's copies of [assets] that are still on it (matched by ID and size). */
    fun copiesOf(context: Context, assets: List<AssetDto>): List<Uri> {
        val known = backedUp(context, assets)
        return find(context, known.keys, known)
    }

    /**
     * Asks Android to move [uris] to the phone's trash (restorable there for 30 days). Android shows its own
     * confirmation unless the app has "Media management" access. Null when there's nothing to do or the phone
     * is older than Android 11, which has no trash.
     */
    fun trashRequest(context: Context, uris: List<Uri>): IntentSender? =
        if (uris.isEmpty() || Build.VERSION.SDK_INT < 30) null
        else MediaStore.createTrashRequest(context.contentResolver, uris, true).intentSender

    /** Asks Android to delete [uris] for good (to free space once they're safely backed up). */
    fun deleteRequest(context: Context, uris: List<Uri>): IntentSender? =
        if (uris.isEmpty() || Build.VERSION.SDK_INT < 30) null
        else MediaStore.createDeleteRequest(context.contentResolver, uris).intentSender

    /** True when Android lets the app trash or delete photos without asking each time (Android 12+). */
    fun canManageMedia(context: Context) = Build.VERSION.SDK_INT >= 31 && MediaStore.canManageMedia(context)
}

/** Backed-up photos and videos that can be removed from the phone, and the space that frees. */
data class FreeUp(val sizes: Map<Long, Long>, val bytes: Long) {
    val keys get() = sizes.map { (id, size) -> PhoneCopies.key(id, size) }.toSet()
}

/** What "Free up space" would remove: this phone's copies already on the server, older than [keepDays]. */
suspend fun findFreeUp(context: Context, immich: ImmichClient, keepDays: Int): FreeUp {
    val onServer = PhoneCopies.backedUp(context, immich.assetsFrom(PhoneCopies.deviceId(context)))
    if (onServer.isEmpty()) return FreeUp(emptyMap(), 0)
    val before = System.currentTimeMillis() / 1000 - keepDays * 86_400L
    val found = mutableMapOf<Long, Long>()
    var bytes = 0L
    for (base in listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)) {
        context.contentResolver.query(
            base, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.SIZE),
            "${MediaStore.MediaColumns.DATE_ADDED} < ?", arrayOf(before.toString()), null,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val size = c.getLong(1)
                if (onServer[id] == size) { found[id] = size; bytes += size } // same file as backed up, not a reused ID
            }
        }
    }
    return FreeUp(found, bytes)
}
