package app.immcloudscale.ui.photos

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import app.immcloudscale.AppGraph
import app.immcloudscale.data.immich.AssetDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.time.Instant

const val COLLAGE_MAX = 9

/**
 * Where each of [n] photos (2 to 9) goes, as fractions of a square: two side by side, three as one big and two
 * small, then 2x2, 3x2 and 3x3 grids (with the first photo taking the leftover space).
 */
fun collageCells(n: Int): List<RectF> {
    require(n in 2..COLLAGE_MAX)
    fun grid(cols: Int, rows: Int) = (0 until cols * rows).map { i ->
        val c = i % cols; val r = i / cols
        RectF(c / cols.toFloat(), r / rows.toFloat(), (c + 1) / cols.toFloat(), (r + 1) / rows.toFloat())
    }
    return when (n) {
        2 -> grid(2, 1)
        3 -> listOf(RectF(0f, 0f, 0.5f, 1f), RectF(0.5f, 0f, 1f, 0.5f), RectF(0.5f, 0.5f, 1f, 1f))
        4 -> grid(2, 2)
        5 -> listOf(RectF(0f, 0f, 0.5f, 0.5f), RectF(0.5f, 0f, 1f, 0.5f)) + grid(3, 2).drop(3)
        6 -> grid(3, 2)
        7 -> listOf(RectF(0f, 0f, 1f, 1 / 3f)) + grid(3, 3).drop(3)
        8 -> listOf(RectF(0f, 0f, 2 / 3f, 1 / 3f)) + grid(3, 3).drop(2)
        else -> grid(3, 3)
    }
}

/** Draws [photos] into a square collage with thin white gaps, each photo cropped to fill its cell. */
fun drawCollage(photos: List<Bitmap>, size: Int = 2048, gap: Int = 12): Bitmap {
    val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(out).apply { drawColor(Color.WHITE) }
    val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    collageCells(photos.size).zip(photos).forEach { (cell, b) ->
        val dst = RectF(cell.left * size + gap / 2f, cell.top * size + gap / 2f, cell.right * size - gap / 2f, cell.bottom * size - gap / 2f)
        // Centre-crop the photo to the cell's shape.
        val cellRatio = dst.width() / dst.height()
        val src = if (b.width.toFloat() / b.height > cellRatio) {
            val w = (b.height * cellRatio).toInt(); Rect((b.width - w) / 2, 0, (b.width + w) / 2, b.height)
        } else {
            val h = (b.width / cellRatio).toInt(); Rect(0, (b.height - h) / 2, b.width, (b.height + h) / 2)
        }
        c.drawBitmap(b, src, dst, paint)
    }
    return out
}

/** Makes a collage of [assets] (their previews) and adds it to the library as a new photo. */
suspend fun makeCollage(g: AppGraph, assets: List<AssetDto>) {
    val photos = coroutineScope {
        assets.take(COLLAGE_MAX).map { a ->
            async(Dispatchers.IO) {
                g.http.newCall(Request.Builder().url(g.immich.thumbnailUrl(a.id, preview = true)).build()).execute().use { r ->
                    r.body?.bytes()?.takeIf { r.isSuccessful }?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                }
            }
        }.awaitAll().filterNotNull()
    }
    require(photos.size >= 2) { "Couldn't load the photos for the collage" }
    val bytes = withContext(Dispatchers.Default) {
        ByteArrayOutputStream().also { drawCollage(photos).compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }
    val now = Instant.now()
    g.immich.upload(bytes.toRequestBody("image/jpeg".toMediaType()), "Collage-${now.epochSecond}.jpg", "collage-${now.toEpochMilli()}", "immcloudscale-collage", now, now, null)
}
