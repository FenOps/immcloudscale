package app.immcloudscale.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/** A QR code for [text] (black on white, with a quiet zone, so any camera reads it from this screen). */
@Composable
fun QrCode(text: String, size: Dp = 220.dp, contentDescription: String = "Pairing QR code") {
    val bmp = remember(text) {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 1))
        Bitmap.createBitmap(m.width, m.height, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until m.height) for (x in 0 until m.width) setPixel(x, y, if (m[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }.asImageBitmap()
    }
    Image(
        bmp, contentDescription, filterQuality = FilterQuality.None,
        modifier = Modifier.background(Color.White).padding(8.dp).size(size),
    )
}
