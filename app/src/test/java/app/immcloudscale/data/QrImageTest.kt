package app.immcloudscale.data

import app.immcloudscale.data.pairing.Pairing
import app.immcloudscale.data.pairing.QrImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrImageTest {
    private val link = "immcloudscale://pair?server=http%3A%2F%2F192.168.1.49%3A8790&code=K7QX-M2PA"

    private fun render(text: String, size: Int, dark: Int, light: Int): IntArray {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        return IntArray(size * size) { i -> if (m.get(i % size, i / size)) dark else light }
    }

    @Test fun readsAPairingCodeFromAPicture() {
        val text = QrImage.decode(render(link, 400, 0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 400, 400)
        assertEquals(link, text)
        assertEquals("K7QX-M2PA", Pairing.parseLink(text!!)?.code)
    }

    @Test fun readsLightOnDarkCodes() {
        assertEquals(link, QrImage.decode(render(link, 400, 0xFFFFFFFF.toInt(), 0xFF202124.toInt()), 400, 400))
    }

    @Test fun noCodeInAPlainPicture() {
        assertNull(QrImage.decode(IntArray(200 * 200) { 0xFF8AB4F8.toInt() }, 200, 200))
    }
}
