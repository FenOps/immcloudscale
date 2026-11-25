package app.immcloudscale.data

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class HomeGuardTest {
    private val paired = AppSettings(
        immichUrl = "http://192.168.1.50:2283", immichToken = "secret", immichTokenIsApiKey = true,
        pairedServer = "http://192.168.1.50:8790", pairedServerId = "ics_home",
    )

    @Test fun privateHosts() {
        listOf("192.168.1.50", "10.8.0.1", "172.20.1.1", "100.100.1.1", "nas.local", "fd00::1", "[fd12::5]")
            .forEach { assertTrue(it, HomeGuard.isPrivateHost(it)) }
        listOf("photos.example.com", "8.8.8.8", "172.32.0.1").forEach { assertFalse(it, HomeGuard.isPrivateHost(it)) }
    }

    @Test fun cafeWithTheSameAddressGetsNoCredentials() {
        var probes = 0
        val guard = HomeGuard({ paired }, { false }, probe = { probes++; "someone-elses-device" })
        val e = runCatching { guard.check("http://192.168.1.50:2283/api/users/me".toHttpUrl()) }.exceptionOrNull()
        assertTrue(e is IOException && e.message!!.contains("turn on the VPN"))
        assertEquals(1, probes)
    }

    @Test fun homeIsVerifiedOncePerNetwork() {
        var probes = 0
        val guard = HomeGuard({ paired }, { false }, probe = { probes++; "ics_home" })
        guard.networkKey = "wifi-1"
        repeat(3) { guard.check("http://192.168.1.50:2283/api/x".toHttpUrl()) }
        assertEquals(1, probes)
        guard.networkKey = "wifi-2" // moved to another network: check again
        guard.check("http://192.168.1.50:2283/api/x".toHttpUrl())
        assertEquals(2, probes)
    }

    @Test fun onlyThePairedServersAddressIsChecked() {
        // A server set up by hand at another LAN address isn't checked against the paired one.
        HomeGuard({ paired }, { false }, probe = { error("must not probe") }).check("http://192.168.2.10:2283/api".toHttpUrl())
    }

    @Test fun vpnPublicHostsAndManualSetupsSkipTheCheck() {
        val guard = HomeGuard({ paired }, { true }, probe = { error("must not probe") })
        guard.check("http://192.168.1.50:2283/api/x".toHttpUrl()) // VPN up
        HomeGuard({ paired }, { false }, probe = { error("must not probe") }).check("https://photos.example.com/api".toHttpUrl())
        HomeGuard({ paired.copy(pairedServerId = "") }, { false }, probe = { error("must not probe") })
            .check("http://192.168.1.50:2283/api".toHttpUrl())
    }

    @Test fun interceptorRefusesBeforeSendingCredentials() {
        val server = MockWebServer().apply { start() }
        val base = server.url("/").toString().trimEnd('/')
        val s = paired.copy(immichUrl = base)
        val guard = HomeGuard({ s }, { false }, probe = { "wrong" })
        val http = buildHttpClient({ s }, guard)
        // MockWebServer listens on 127.0.0.1, which counts as private, so the guard applies.
        val r = runCatching { http.newCall(Request.Builder().url("$base/api/users/me").build()).execute() }
        assertTrue(r.isFailure)
        assertEquals(0, server.requestCount) // nothing at all reached the stranger
        server.shutdown()
    }

    @Test fun credentialsDontFollowRedirectsToOtherHosts() {
        val other = MockWebServer().apply { enqueue(MockResponse().setBody("ok")); start() }
        val immich = MockWebServer().apply {
            enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/landing")))
            start()
        }
        val s = AppSettings(immichUrl = immich.url("/").toString().trimEnd('/'), immichToken = "k", immichTokenIsApiKey = true)
        buildHttpClient({ s }).newCall(Request.Builder().url(immich.url("/api/x")).build()).execute().close()
        assertEquals("k", immich.takeRequest().getHeader("x-api-key"))
        assertNull(other.takeRequest().getHeader("x-api-key"))
        immich.shutdown(); other.shutdown()
    }
}
