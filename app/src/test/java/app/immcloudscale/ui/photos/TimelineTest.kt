package app.immcloudscale.ui.photos

import app.immcloudscale.data.immich.AssetDto
import app.immcloudscale.data.immich.ExifInfo
import app.immcloudscale.data.immich.PersonDto
import app.immcloudscale.data.immich.TimelineCache
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TimelineTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun a(id: String, day: Int) = AssetDto(id, fileCreatedAt = "2026-11-%02dT10:00:00Z".format(day))

    @Test fun theNewestPhotosReplaceWhatWasSavedDownToTheOldestOfThem() {
        val saved = listOf(a("n1", 20), a("gone", 19), a("x", 18), a("old", 10))
        val newest = listOf(a("new", 25), a("n1", 20), a("x", 18))
        assertEquals(listOf("new", "n1", "x", "old"), mergeNewest(saved, newest).map { it.id })
        assertEquals(saved, mergeNewest(saved, emptyList()))
    }

    @Test fun duplicatesNeverReachTheGrid() {
        val saved = listOf(a("x", 18), a("x", 18), a("old", 10))
        assertEquals(listOf("new", "x", "old"), mergeNewest(saved, listOf(a("new", 25), a("x", 18))).map { it.id })
        assertEquals(listOf("x", "old"), mergeNewest(saved, emptyList()).map { it.id })
    }

    @Test fun savedPhotosSharingTheCutoffTimeStay() {
        val merged = mergeNewest(listOf(a("same-time", 18), a("old", 10)), listOf(a("new", 25), a("x", 18)))
        assertEquals(listOf("new", "x", "same-time", "old"), merged.map { it.id })
    }

    @Test fun phonePhotosMergeInByDate() {
        val server = listOf(a("s3", 20), a("s2", 15), a("s1", 5))
        val phone = listOf(a("local:9", 22), a("local:8", 10))
        assertEquals(listOf("local:9", "s3", "s2", "local:8", "s1"), mergeByDate(server, phone).map { it.id })
    }

    @Test fun theSavedTimelineRoundTripsPerAccountAndSlim() = runBlocking {
        val cache = TimelineCache(tmp.root)
        val full = AssetDto(
            "a1", fileCreatedAt = "2026-11-20T10:00:00Z", thumbhash = "lVoDBYCH",
            exifInfo = ExifInfo(make = "Pixel", city = "Lisbon", country = "Portugal"),
            people = listOf(PersonDto("p", "Camille")), deviceId = "immcloudscale-x", deviceAssetId = "12-345",
        )
        val local = AssetDto("local:9", localUri = "content://media/external/images/media/9")
        cache.save("http://nas:2283", "camille@x", listOf(full, local), synced = 1234)
        val back = cache.load("http://nas:2283", "camille@x")!!
        assertEquals(1234, back.synced)
        assertEquals(listOf("a1"), back.assets.map { it.id }) // photos only on the phone aren't saved
        val got = back.assets.single()
        assertEquals("lVoDBYCH", got.thumbhash)
        assertEquals("Lisbon", got.exifInfo?.city)
        assertNull(got.exifInfo?.make) // only what the grid needs
        assertTrue(got.people.isEmpty())
        assertEquals("12-345", got.deviceAssetId)
        assertNull(cache.load("http://nas:2283", "nina@x")) // another account: nothing
        cache.clear()
        assertNull(cache.load("http://nas:2283", "camille@x"))
    }

    @Test fun aPhoneCopyMatchesTheServersAcrossATimeZone() {
        val server = AssetDto("s", originalFileName = "IMG_1234.JPG", fileCreatedAt = "2026-11-20T23:30:00Z")
        val phone = AssetDto("local:1", originalFileName = "img_1234.jpg", fileCreatedAt = "2026-11-21T01:30:00Z")
        val elsewhere = AssetDto("local:2", originalFileName = "img_1234.jpg", fileCreatedAt = "2024-05-01T10:00:00Z")
        val keys = nameDayKeys(server).toSet()
        assertTrue(nameDayKey(phone) in keys)
        assertTrue(nameDayKey(elsewhere) !in keys) // same name, another photo: still shown as not backed up
    }
}
