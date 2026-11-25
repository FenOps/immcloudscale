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

    /** The MediaStore IDs of [assets] that this phone backed up. */
    fun mediaIds(context: Context, assets: List<AssetDto>): List<Long> {
        val me = deviceId(context)
        return assets.filter { it.deviceId == me }.mapNotNull { it.deviceAssetId.substringBefore('-').toLongOrNull() }
    }

    /** Content URIs of the copies that are still on the phone. */
    fun find(context: Context, ids: Collection<Long>): List<Uri> {
        if (ids.isEmpty()) return emptyList()
        val out = mutableListOf<Uri>()
        for (base in listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)) {
            for (chunk in ids.chunked(500)) {
                val sel = "${MediaStore.MediaColumns._ID} IN (${chunk.joinToString(",") { "?" }})"
                runCatching {
                    context.contentResolver.query(base, arrayOf(MediaStore.MediaColumns._ID), sel, chunk.map { it.toString() }.toTypedArray(), null)
                        ?.use { c -> while (c.moveToNext()) out += ContentUris.withAppendedId(base, c.getLong(0)) }
                }
            }
        }
        return out
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
data class FreeUp(val ids: List<Long>, val bytes: Long)

/** What "Free up space" would remove: this phone's copies already on the server, older than [keepDays]. */
suspend fun findFreeUp(context: Context, immich: ImmichClient, keepDays: Int): FreeUp {
    val onServer = PhoneCopies.mediaIds(context, immich.assetsFrom(PhoneCopies.deviceId(context))).toSet()
    if (onServer.isEmpty()) return FreeUp(emptyList(), 0)
    val before = System.currentTimeMillis() / 1000 - keepDays * 86_400L
    val ids = mutableListOf<Long>()
    var bytes = 0L
    for (base in listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)) {
        context.contentResolver.query(
            base, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.SIZE),
            "${MediaStore.MediaColumns.DATE_ADDED} < ?", arrayOf(before.toString()), null,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                if (id in onServer) { ids += id; bytes += c.getLong(1) }
            }
        }
    }
    return FreeUp(ids, bytes)
}
