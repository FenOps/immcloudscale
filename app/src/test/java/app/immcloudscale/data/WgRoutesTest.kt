package app.immcloudscale.data

import app.immcloudscale.data.vpn.VpnManager
import app.immcloudscale.data.vpn.WgRoutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WgRoutesTest {
    @Test fun exitNodeRoutesEverything() {
        assertEquals(listOf("0.0.0.0/0", "::/0"), WgRoutes.allowedIps(listOf("10.8.0.0/24"), listOf("10.8.0.2/24"), fullTunnel = true))
    }

    @Test fun lanOnlyKeepsConfiguredSubnets() {
        assertEquals(
            listOf("10.8.0.0/24", "192.168.1.0/24"),
            WgRoutes.allowedIps(listOf("0.0.0.0/0", "10.8.0.0/24", "192.168.1.0/24", "::/0"), listOf("10.8.0.2/24"), fullTunnel = false),
        )
    }

    @Test fun lanOnlyFallsBackToTunnelAndPrivateRanges() {
        val r = WgRoutes.allowedIps(listOf("0.0.0.0/0", "::/0"), listOf("10.8.0.2/32"), fullTunnel = false)
        assertEquals(listOf("10.8.0.0/24", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "fd00::/8"), r)
    }

    @Test fun subnetOf() {
        assertEquals("192.168.7.0/24", WgRoutes.subnetOf("192.168.7.45/24"))
        assertEquals("10.6.0.0/16", WgRoutes.subnetOf("10.6.3.2/16"))
        assertEquals("100.64.1.0/24", WgRoutes.subnetOf("100.64.1.9"))
        assertNull(WgRoutes.subnetOf("not-an-ip"))
    }

    private val wgEasy = """
        [Interface]
        PrivateKey = yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=
        Address = 10.8.0.2/24
        DNS = 1.1.1.1, 10.8.0.1

        # Name = nas
        [Peer]
        PublicKey = xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=
        PresharedKey = 4yBmb0qSZCk8kbOdkvK08vhn7jmxjrpHkDk8q26/fP4=
        AllowedIPs = 0.0.0.0/0, ::/0
        PersistentKeepalive = 25
        Endpoint = home.example.com:51820
    """.trimIndent()

    @Test fun summarizesWgQuick() {
        val s = WgRoutes.summarize(wgEasy)
        assertEquals(listOf("10.8.0.2/24"), s.addresses)
        assertEquals(listOf("1.1.1.1", "10.8.0.1"), s.dns)
        assertEquals(1, s.peers.size)
        val p = s.peers[0]
        assertEquals("nas", p.name)
        assertEquals("home.example.com:51820", p.endpoint)
        assertEquals(listOf("0.0.0.0/0", "::/0"), p.allowedIps)
    }

    @Test fun builtConfigIsValidWireGuard() {
        val text = WgRoutes.buildConfig(
            privateKey = "yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=",
            address = "10.8.0.9/32", dns = "10.8.0.1",
            serverPublicKey = "xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=",
            endpoint = "vpn.example.org:51820", allowedIps = "10.8.0.0/24, 192.168.1.0/24",
        )
        assertTrue(text.contains("PersistentKeepalive = 25"))
        assertNull(VpnManager.validate(text))
        assertNull(VpnManager.validate(wgEasy))
        assertNotNull(VpnManager.validate("[Interface]\nPrivateKey = nope\n"))
        assertEquals("HIgo9xNzJMWLKASShiTqIybxZ0U3wGLiUeJ1PKf8ykw=", VpnManager.publicKeyOf(wgEasy))
    }
}
