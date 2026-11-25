package app.immcloudscale.ui

import app.immcloudscale.data.pairing.PairCode
import app.immcloudscale.data.pairing.Pairing
import app.immcloudscale.ui.account.inviteMessage
import app.immcloudscale.ui.account.inviteTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** Newer Java puts a narrow no-break space before "PM"; compare with plain spaces. */
private fun String.plain() = replace('\u202F', ' ').replace('\u00A0', ' ')

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InviteTest {
    private val istanbul = ZoneId.of("Europe/Istanbul")
    private val now = Instant.parse("2026-11-25T12:30:00Z") // 15:30 in Istanbul
    private val link = "immcloudscale://pair?server=http%3A%2F%2F192.168.1.49%3A8790&code=K7QX-M2PA&exp=1795624200&ts=100.101.102.103"
    private val invite = PairCode("K7QX-M2PA", "http://192.168.1.49:8790", link, expires = "2026-11-25T12:45:00Z", name = "nina")

    @Test fun theMessageSaysUntilWhenTheLinkWorks() {
        Locale.setDefault(Locale.US)
        assertEquals("3:45 PM", inviteTime(invite.expires, istanbul, now).plain())
        val msg = inviteMessage("homeserver", invite, istanbul, now).plain()
        assertTrue(msg, msg.contains("Works once, for 15 minutes (until 3:45 PM)."))
        assertTrue(msg, msg.contains("Copy this whole message"))
        assertTrue(msg, msg.trimEnd().endsWith(link)) // the link last, as data
        assertTrue(msg, msg.contains("releases/latest"))
        // Copied whole and pasted into the app, the link is found again.
        assertEquals("K7QX-M2PA", Pairing.findLink(msg)?.code)
        assertEquals("100.101.102.103", Pairing.findLink(msg)?.tailscaleIp)
    }

    @Test fun anotherDayShowsTheDate() {
        Locale.setDefault(Locale.US)
        assertEquals("3:45 PM, Nov 26, 2026", inviteTime("2026-11-26T12:45:00Z", istanbul, now).plain())
    }
}
