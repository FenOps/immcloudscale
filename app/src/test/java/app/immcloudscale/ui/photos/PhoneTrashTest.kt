package app.immcloudscale.ui.photos

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhoneTrashTest {
    @get:Rule val compose = createComposeRule()

    @Test fun deletingAPhotoThatIsntBackedUpSaysItsTheOnlyCopy() {
        var confirmed = false
        compose.setContent { ConfirmPhoneTrash(3, onDismiss = {}) { confirmed = true } }
        compose.onNodeWithText("Delete 3 items from your phone?").assertExists()
        compose.onNodeWithText("They aren't backed up, so these are the only copies. They go to your phone's trash, where Android keeps them for 30 days, then deletes them for good.").assertExists()
        compose.onNodeWithText("Move to trash").performClick()
        assertTrue(confirmed)
    }

    @Test fun oneVideo() {
        compose.setContent { ConfirmPhoneTrash(1, video = true, onDismiss = {}) {} }
        compose.onNodeWithText("Delete this video from your phone?").assertExists()
        compose.onNodeWithText("It isn't backed up, so this is the only copy. It goes to your phone's trash, where Android keeps it for 30 days, then deletes it for good.").assertExists()
    }

    @Test fun deletingForGoodAsksFirstAndSaysItCantBeUndone() {
        var confirmed = false
        compose.setContent { ConfirmDeleteForever(2, onDismiss = {}) { confirmed = true } }
        compose.onNodeWithText("Delete 2 items for good?").assertExists()
        compose.onNodeWithText("They're removed from your phone now and can't be restored. Anything that isn't backed up is gone for good.").assertExists()
        org.junit.Assert.assertFalse(confirmed) // nothing happens before the button
        compose.onNodeWithText("Delete for good").performClick()
        assertTrue(confirmed)
    }

    @Test fun daysLeftRoundUp() {
        val now = 1_800_000_000_000L
        org.junit.Assert.assertEquals(1L, daysLeft(now / 1000 + 3600, now)) // an hour left: "1 day"
        org.junit.Assert.assertEquals(30L, daysLeft(now / 1000 + 29 * 86400 + 60, now))
        org.junit.Assert.assertEquals(null, daysLeft(0, now)) // unknown
    }

    @Test fun trashedPhotosAreRememberedForEveryScreen() {
        PhoneTrashed.add(listOf("local:7"))
        PhoneTrashed.add(listOf("local:9"))
        assertTrue(PhoneTrashed.ids.value.containsAll(listOf("local:7", "local:9")))
    }
}
