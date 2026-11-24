package app.immcloudscale.data

import app.immcloudscale.data.immich.AssetQuery
import app.immcloudscale.data.immich.ImmichClient
import app.immcloudscale.data.nextcloud.NextcloudClient
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ClientsTest {
    private val server = MockWebServer()
    private lateinit var settings: AppSettings
    private val http by lazy { buildHttpClient { settings } }

    @Before fun start() {
        server.start()
        val base = server.url("/").toString().trimEnd('/')
        settings = AppSettings(
            immichUrl = base, immichToken = "tok123",
            ncUrl = "$base/nc", ncUser = "alice", ncAppPassword = "app-pass",
        )
    }

    @After fun stop() = server.shutdown()

    @Test fun immichSendsBearerAndSearchesTimeline() = runTest {
        server.enqueue(MockResponse().setBody("""{"assets":{"items":[{"id":"x1","type":"VIDEO","fileCreatedAt":"2026-01-01T00:00:00Z","isFavorite":true,"extra":1}],"nextPage":"2","total":1}}"""))
        val immich = ImmichClient(http) { settings.immichUrl }
        val page = immich.assets(AssetQuery.Favorites, page = 1)
        assertEquals("x1", page.items.single().id)
        assertTrue(page.items.single().isVideo)
        assertEquals("2", page.nextPage)

        val req = server.takeRequest()
        assertEquals("/api/search/metadata", req.path)
        assertEquals("Bearer tok123", req.getHeader("Authorization"))
        val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("true", body["isFavorite"]!!.jsonPrimitive.content)
        assertEquals("desc", body["order"]!!.jsonPrimitive.content)
    }

    @Test fun immichApiKeyHeaderAndErrors() = runTest {
        settings = settings.copy(immichTokenIsApiKey = true)
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"message":"Invalid API key","statusCode":401}"""))
        val immich = ImmichClient(http) { settings.immichUrl }
        val err = runCatching { immich.me() }.exceptionOrNull() as HttpException
        assertEquals(401, err.code)
        assertEquals("Invalid API key", err.message)
        val req = server.takeRequest()
        assertEquals("tok123", req.getHeader("x-api-key"))
        assertNull(req.getHeader("Authorization"))
    }

    @Test fun nextcloudListsWithPropfindAndBasicAuth() = runTest {
        server.enqueue(MockResponse().setResponseCode(207).setBody("""<?xml version="1.0"?>
<d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">
 <d:response><d:href>/nc/remote.php/dav/files/alice/Docs/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
 <d:response><d:href>/nc/remote.php/dav/files/alice/Docs/a%20b.txt</d:href><d:propstat><d:prop><d:resourcetype/><d:getcontentlength>5</d:getcontentlength></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
</d:multistatus>"""))
        val nc = NextcloudClient(http, { settings.ncUrl }, { settings.ncUser })
        val items = nc.list("/Docs")
        assertEquals(listOf("/Docs/a b.txt"), items.map { it.path }) // the folder itself is dropped
        val req = server.takeRequest()
        assertEquals("PROPFIND", req.method)
        assertEquals("1", req.getHeader("Depth"))
        assertEquals("/nc/remote.php/dav/files/alice/Docs/", req.path)
        assertTrue(req.getHeader("Authorization")!!.startsWith("Basic "))
    }

    @Test fun nextcloudMoveAndShareLink() = runTest {
        server.enqueue(MockResponse().setResponseCode(201))
        server.enqueue(MockResponse().setBody("""{"ocs":{"meta":{"status":"ok"},"data":{"id":"7","url":"https://cloud/s/AbC"}}}"""))
        val nc = NextcloudClient(http, { settings.ncUrl }, { settings.ncUser })
        nc.move("/a.txt", "/Old stuff/a.txt")
        val mv = server.takeRequest()
        assertEquals("MOVE", mv.method)
        assertTrue(mv.getHeader("Destination")!!.endsWith("/nc/remote.php/dav/files/alice/Old%20stuff/a.txt"))
        assertEquals("https://cloud/s/AbC", nc.createShareLink("/a.txt"))
        val share = server.takeRequest()
        assertEquals("true", share.getHeader("OCS-APIRequest"))
        assertTrue(share.body.readUtf8().contains("shareType=3"))
    }

    @Test fun credentialsAreNotSentToOtherHosts() = runTest {
        val other = MockWebServer().apply { start() }
        other.enqueue(MockResponse().setBody("ok"))
        http.newCall(okhttp3.Request.Builder().url(other.url("/x")).build()).execute().close()
        val req = other.takeRequest()
        assertNull(req.getHeader("Authorization"))
        assertNull(req.getHeader("x-api-key"))
        other.shutdown()
    }
}
