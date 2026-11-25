package app.immcloudscale.data.pairing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer

/** Reads a pairing QR code from a picture (a screenshot of the admin console, a photo of the setup screen). */
object QrImage {
    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
    )

    /** The text of the QR code in the picture at [uri], or null when there's none (or it can't be read). */
    fun read(context: Context, uri: Uri): String? = load(context, uri)?.let { bmp ->
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        decode(px, bmp.width, bmp.height)
    }

    /** Decodes ARGB [pixels]; tries both binarizers, and the inverted picture for light-on-dark codes. */
    fun decode(pixels: IntArray, width: Int, height: Int): String? {
        val source = RGBLuminanceSource(width, height, pixels)
        for (src in listOf(source, source.invert())) {
            for (bin in listOf(HybridBinarizer(src), GlobalHistogramBinarizer(src))) {
                runCatching { return MultiFormatReader().decode(BinaryBitmap(bin), hints).text }
            }
        }
        return null
    }

    /** Loads the picture scaled down to at most about 2000 pixels a side (plenty for a QR code). */
    private fun load(context: Context, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2000) sample *= 2
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }
}
