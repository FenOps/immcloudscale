package app.immcloudscale.data

import app.immcloudscale.data.immich.AssetQuery
import app.immcloudscale.data.immich.ImmichClient
import app.immcloudscale.data.nextcloud.NextcloudClient
import app.immcloudscale.data.pairing.Pairing
import app.immcloudscale.data.vpn.VpnManager
import app.immcloudscale.data.vpn.WgRoutes
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PairingTest {
    /** The shape of a real `ics-server` pairing response, with made-up values. */
    private val realResponse = """
        {"deviceId":"dev_example0001","server":{"name":"vm","version":"0.1.0"},
         "user":{"name":"camille","email":"camille@example.com"},
         "immich":{"url":"http://192.0.2.2:2283","apiKey":"example-immich-api-key-not-real"},
         "nextcloud":{"url":"http://192.0.2.2:8080","user":"camille","appPassword":"example-nextcloud-app-password-not-real"},
         "wireguard":{"address":"10.8.0.2/32","serverPublicKey":"2yJvJ0StMfztR5pQRt1jxvCWRFLrJiT0jaoacP6TT0I=","endpoint":"192.0.2.2:51820","allowedIps":"10.8.0.0/24, 192.0.2.0/24","dns":""}}
    """.trimIndent()

    @Test fun normalizesWhatPeopleType() {
        assertEquals("http://192.168.1.50:8790", Pairing.normalizeServer("192.168.1.50"))
        assertEquals("http://nas.local:9000", Pairing.normalizeServer("nas.local:9000/"))
        assertEquals("https://nas.example.org:8790", Pairing.normalizeServer("https://nas.example.org"))
        assertEquals("K7QX-M2PA", Pairing.normalizeCode(" k7qx m2pa "))
        assertEquals("K7QX-M2PA", Pairing.normalizeCode("K7QX-M2PA"))
    }

    @Test fun parsesQrLinks() {
        val l = Pairing.parseLink("immcloudscale://pair?code=ECS8-WRTS&server=http%3A%2F%2F192.0.2.2%3A8790")!!
        assertEquals("http://192.0.2.2:8790", l.server)
        assertEquals("ECS8-WRTS", l.code)
        assertNull(Pairing.parseLink("https://example.com/?code=x"))
        assertNull(Pairing.parseLink("immcloudscale://pair?code=x"))
    }

    @Test fun pairsAndConfiguresEverything() = runTest {
        val server = MockWebServer().apply { enqueue(MockResponse().setBody(realResponse)); start() }
        val (priv, pub) = VpnManager.newKeyPair()
        val r = Pairing.pair(OkHttpClient(), server.url("/").toString(), "ecs8wrts", "Pixel 9", pub)
        val req = server.takeRequest()
        assertEquals("/api/v1/pair", req.path)
        val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("ECS8-WRTS", body["code"]!!.jsonPrimitive.content)
        assertEquals(pub, body["wgPublicKey"]!!.jsonPrimitive.content)
        assertTrue("the private key must never be sent", !body.toString().contains(priv))

        val s = Pairing.apply(AppSettings(), r, priv, "192.0.2.2")
        assertTrue(s.immichConfigured && s.nextcloudConfigured && s.vpnConfigured)
        assertTrue(s.immichTokenIsApiKey)
        assertEquals("http://192.0.2.2:8080", s.ncUrl)
        assertEquals("http://192.0.2.2:8790", s.pairedServer)
        assertNull(VpnManager.validate(s.vpnConfig))
        assertEquals(VpnManager.publicKeyOf(s.vpnConfig), pub)
        val summary = WgRoutes.summarize(s.vpnConfig)
        assertEquals(listOf("10.8.0.2/32"), summary.addresses)
        assertEquals("192.0.2.2:51820", summary.peers.single().endpoint)
        server.shutdown()
    }

    @Test fun reportsServerErrors() = runTest {
        val server = MockWebServer().apply {
            enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"that pairing code is wrong or has expired"}"""))
            start()
        }
        val e = runCatching { Pairing.pair(OkHttpClient(), server.url("/").toString(), "AAAA-BBBB", "x", "k") }.exceptionOrNull()
        assertEquals("that pairing code is wrong or has expired", e?.message)
        server.shutdown()
    }

    /** Runs only when pointed at a live server, e.g. ICS_E2E_SERVER=http://192.0.2.2:8790 ICS_E2E_CODE=…. */
    @Test fun liveServer() = runTest {
        val url = System.getenv("ICS_E2E_SERVER")
        val code = System.getenv("ICS_E2E_CODE")
        assumeTrue(url != null && code != null)
        val (priv, pub) = VpnManager.newKeyPair()
        val r = Pairing.pair(OkHttpClient(), url!!, code!!, "Robolectric phone", pub)
        var settings = Pairing.apply(AppSettings(), r, priv, url)
        val http = buildHttpClient { settings }
        // The app's own clients, with the credentials the server handed out.
        val immich = ImmichClient(http) { settings.immichUrl }
        assertEquals(r.user.email, immich.me().email)
        immich.assets(AssetQuery.Timeline, 1)
        val nc = NextcloudClient(http, { settings.ncUrl }, { settings.ncUser })
        assertTrue(nc.list("/").isNotEmpty())
        assertNull(VpnManager.validate(settings.vpnConfig))
        println("LIVE OK: ${r.user.name} ${r.immich.url} ${r.nextcloud.url} vpn=${r.wireguard?.address}")
    }
}
