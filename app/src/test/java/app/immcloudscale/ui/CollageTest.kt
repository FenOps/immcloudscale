package app.immcloudscale.ui

import app.immcloudscale.ui.photos.COLLAGE_MAX
import app.immcloudscale.ui.photos.collageCells
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CollageTest {
    @Test fun everyLayoutFillsTheSquareExactlyOnceWithOneCellPerPhoto() {
        for (n in 2..COLLAGE_MAX) {
            val cells = collageCells(n)
            assertEquals("cells for $n photos", n, cells.size)
            assertEquals("area for $n photos", 1.0, cells.sumOf { (it.width() * it.height()).toDouble() }, 1e-4)
            cells.forEach { c -> assert(c.left >= 0f && c.top >= 0f && c.right <= 1.0001f && c.bottom <= 1.0001f) { "$n: $c" } }
            for (i in cells.indices) for (j in i + 1 until cells.size) {
                val a = cells[i]; val b = cells[j]
                val overlap = maxOf(0f, minOf(a.right, b.right) - maxOf(a.left, b.left)) * maxOf(0f, minOf(a.bottom, b.bottom) - maxOf(a.top, b.top))
                assertEquals("$n photos: cells $i and $j overlap", 0f, overlap, 1e-5f)
            }
        }
    }
}
