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

    @Test fun trashedPhotosAreRememberedForEveryScreen() {
        PhoneTrashed.add(listOf("local:7"))
        PhoneTrashed.add(listOf("local:9"))
        assertTrue(PhoneTrashed.ids.value.containsAll(listOf("local:7", "local:9")))
    }
}
