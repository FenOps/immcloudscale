package app.immcloudscale.ui.mail

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.immcloudscale.data.mail.NewMailbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MailUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun theProviderFollowsTheAddressAndOthersAskForTheirServer() {
        var added: NewMailbox? = null
        compose.setContent { AddMailboxDialog(emptyList(), "camille", {}, { added = it }) }
        compose.onNodeWithText("Add").assertIsNotEnabled()
        compose.onNodeWithText("Email address").performTextInput("me@family.example")
        compose.onNodeWithText("Other (IMAP)").assertExists() // an unknown domain needs its server
        compose.onNodeWithText("Password").performTextInput("secret")
        compose.onNodeWithText("Add").assertIsNotEnabled()
        compose.onNodeWithText("Incoming (IMAP) server").performTextInput("mail.family.example")
        compose.onNodeWithText("Add").assertIsEnabled().performClick()
        assertEquals(NewMailbox("me@family.example", "secret", "mail.family.example", 993, "ssl", "", ""), added)
    }

    @Test fun gmailNeedsOnlyTheAddressAndAnAppPassword() {
        var added: NewMailbox? = null
        compose.setContent { AddMailboxDialog(emptyList(), "camille", {}, { added = it }) }
        compose.onNodeWithText("Email address").performTextInput("camille.martin@gmail.com")
        compose.onNodeWithText("App password").performTextInput("abcd efgh ijkl mnop")
        compose.onNodeWithText("Add").performClick()
        assertEquals("", added?.host)
        assertEquals(MailProvider.Gmail, MailProvider.of("Camille@GMail.com"))
        assertEquals(MailProvider.Outlook, MailProvider.of("x@hotmail.com"))
    }

    @Test fun deletingSaysWhereItGoes() {
        assertTrue(deleteExplanation(listOf("a@gmail.com")).contains("Gmail keeps them in its Trash for 30 days"))
        val other = deleteExplanation(listOf("me@family.example"))
        assertTrue(other, other.startsWith("They're deleted from your NAS and from me@family.example.") && "Gmail" !in other)
    }

    @Test fun listDatesAreShort() {
        Locale.setDefault(Locale.US)
        val zone = ZoneId.of("Europe/Istanbul")
        val now = Instant.parse("2026-11-28T09:00:00Z")
        assertEquals("Nov 3", mailDate("2026-11-03T10:00:00Z", zone, now))
        assertEquals("1/5/25", mailDate("2025-01-05T10:00:00Z", zone, now))
        assertEquals("", mailDate("not a date", zone, now))
        assertTrue(mailDate("2026-11-28T08:00:00Z", zone, now).replace(' ', ' ').endsWith("AM"))
    }
}
