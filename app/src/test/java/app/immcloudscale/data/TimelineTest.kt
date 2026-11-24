package app.immcloudscale.data

import app.immcloudscale.data.immich.AssetDto
import app.immcloudscale.data.immich.ExifInfo
import app.immcloudscale.data.immich.dayTitle
import app.immcloudscale.data.immich.groupByDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

class TimelineTest {
    private val today = LocalDate.of(2026, 11, 24) // a Tuesday

    @Test fun dayTitles() {
        assertEquals("Today", dayTitle(today, today, Locale.US))
        assertEquals("Yesterday", dayTitle(today.minusDays(1), today, Locale.US))
        assertEquals("Friday", dayTitle(LocalDate.of(2026, 11, 20), today, Locale.US))
        assertEquals("Sat, Oct 3", dayTitle(LocalDate.of(2026, 10, 3), today, Locale.US))
        assertEquals("Mon, Dec 1, 2025", dayTitle(LocalDate.of(2025, 12, 1), today, Locale.US))
    }

    @Test fun groupsByLocalDayNewestFirst() {
        val assets = listOf(
            AssetDto("a", fileCreatedAt = "2026-11-24T08:00:00.000Z", localDateTime = "2026-11-24T09:00:00.000Z",
                exifInfo = ExifInfo(city = "Lyon", state = "Auvergne-Rhône-Alpes")),
            AssetDto("b", fileCreatedAt = "2026-11-23T23:30:00.000Z", localDateTime = "2026-11-24T00:30:00.000Z"),
            AssetDto("c", fileCreatedAt = "2026-11-20T12:00:00.000Z", localDateTime = "2026-11-20T13:00:00.000Z"),
        )
        val days = groupByDay(assets.reversed(), today, Locale.US)
        assertEquals(listOf("Today", "Friday"), days.map { it.title })
        assertEquals(listOf("c"), days[1].assets.map { it.id })
        assertEquals(setOf("a", "b"), days[0].assets.map { it.id }.toSet()) // b is local-time Today despite UTC date
        assertEquals("Lyon, Auvergne-Rhône-Alpes", days[0].subtitle)
        assertNull(days[1].subtitle)
    }

    @Test fun shortDuration() {
        assertEquals("0:12", AssetDto("v", duration = "0:00:12.345000").shortDuration)
        assertEquals("1:02:03", AssetDto("v", duration = "1:02:03.000000").shortDuration)
        assertNull(AssetDto("p", duration = "0:00:00.00000").shortDuration)
    }

    @Test fun normalizesServerUrls() {
        assertEquals("https://photos.example.com", normalizeServerUrl(" photos.example.com/ "))
        assertEquals("http://192.168.1.10:2283", normalizeServerUrl("http://192.168.1.10:2283/api"))
        assertEquals("https://cloud.example.com/nextcloud", normalizeServerUrl("https://cloud.example.com/nextcloud/index.php"))
    }
}
