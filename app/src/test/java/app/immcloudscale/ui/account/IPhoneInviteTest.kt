package app.immcloudscale.ui.account

import app.immcloudscale.data.pairing.IPhoneInvite
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class IPhoneInviteTest {
    private val istanbul = ZoneId.of("Europe/Istanbul")
    private val now = Instant.parse("2026-11-25T12:30:00Z")
    private val invite = IPhoneInvite("nina", "nina@example.com", "k7qx-m2pa-9rtz", "2026-11-25T12:45:00Z", "http://192.168.1.49:2283", "http://100.101.102.103:2283")

    @Test fun theMessageHasTheAppsAddressAndTemporaryPassword() {
        Locale.setDefault(Locale.US)
        val msg = iphoneInviteMessage("homeserver", invite, istanbul, now).replace(' ', ' ')
        assertTrue(msg, msg.contains("apps.apple.com/app/immich/id1613945652"))
        assertTrue(msg, msg.contains("server address: http://100.101.102.103:2283"))
        assertTrue(msg, msg.contains("apps.apple.com/app/tailscale/id1470499037"))
        assertTrue(msg, msg.contains("http://192.168.1.49:2283"))
        assertTrue(msg, msg.contains("Email: nina@example.com"))
        assertTrue(msg, msg.contains("Temporary password: k7qx-m2pa-9rtz"))
        assertTrue(msg, msg.contains("works until 3:45 PM"))
        assertTrue(msg, msg.contains("permanent"))
    }

    @Test fun withoutRemoteAccessItSendsTheHomeAddress() {
        val msg = iphoneInviteMessage("", invite.copy(photosAway = ""), istanbul, now)
        assertTrue(msg, msg.contains("server address: http://192.168.1.49:2283\n"))
        assertTrue(msg, msg.contains("home Wi-Fi"))
        assertFalse(msg, msg.contains("tailscale"))
    }
}
