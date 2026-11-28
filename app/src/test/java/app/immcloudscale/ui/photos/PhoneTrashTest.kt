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
        compose.onNodeWithText("Delete 3 photos and videos from your phone?").assertExists()
        compose.onNodeWithText("They aren't backed up, so these are the only copies. They go to your phone's trash, where Android keeps them for 30 days.").assertExists()
        compose.onNodeWithText("Delete").performClick()
        assertTrue(confirmed)
    }

    @Test fun oneAtATime() {
        compose.setContent { ConfirmPhoneTrash(1, onDismiss = {}) {} }
        compose.onNodeWithText("Delete this photo from your phone?").assertExists()
    }
}
