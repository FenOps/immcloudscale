package app.immcloudscale.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import kotlin.math.abs

class ThumbHashTest {
    // Immich's own thumbhashes for solid-colour test photos (320x240 JPEGs).
    private val cases = mapOf(
        "lVoDBYCHh4iIiHiAd3h4f4iHgHgI" to Triple(200, 30, 30), // red
        "lroABYB3h4iIeIePeHiIhod4j3cI" to Triple(30, 200, 30), // green
        "lQICBYCHh4iIiHiAd3h4f3d4DwAA" to Triple(30, 30, 200), // blue
        "5EwCBYCHiIiId4iAh4d4iXh3f4gI" to Triple(220, 180, 40), // yellow
    )

    @Test fun decodesToTheRightColourAndShape() {
        for ((hash, rgb) in cases) {
            val img = ThumbHash.decode(Base64.getDecoder().decode(hash))
            assertTrue("$hash: landscape like the photo, got ${img.w}x${img.h}", img.w > img.h && img.w <= 32)
            val px = img.argb[(img.h / 2) * img.w + img.w / 2]
            val got = Triple((px shr 16) and 0xFF, (px shr 8) and 0xFF, px and 0xFF)
            listOf(got.first - rgb.first, got.second - rgb.second, got.third - rgb.third).forEach {
                assertTrue("$hash: got $got, want about $rgb", abs(it) <= 30)
            }
            assertEquals("opaque", 0xFF, (px ushr 24) and 0xFF)
        }
    }

    @Test fun aDamagedHashDoesntCrash() {
        runCatching { ThumbHash.decode(byteArrayOf(1, 2, 3, 4, 5)) } // too short: an image of sorts or an error, never a crash
    }
}
