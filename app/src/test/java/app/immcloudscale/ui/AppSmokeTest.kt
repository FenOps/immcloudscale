package app.immcloudscale.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.immcloudscale.MainActivity
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

/** Starts the real app (fresh install, nothing configured) and walks the first-run flow and every tab. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@Config(sdk = [34])
class AppSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Before fun testWorkManager() {
        // A synchronous WorkManager: the real one keeps database threads running after the test closes the database.
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(
            ctx, androidx.work.Configuration.Builder().setExecutor(androidx.work.testing.SynchronousExecutor()).build(),
        )
    }


    @Test fun firstRunAndTabs() {
        compose.onNodeWithText("Welcome to ImmCloudScale").assertExists()
        compose.onNodeWithText("Sign in with browser").assertExists()
        compose.onNodeWithText("Skip").performClick()

        compose.onNodeWithText("Connect your photo library").assertExists()
        compose.onNodeWithText("Files").performClick()
        compose.onNodeWithText("Connect your files").assertExists()
        compose.onNodeWithText("Network").performClick()
        compose.onNodeWithText("Reach your NAS from anywhere").assertExists()
        compose.onNodeWithText("Add this device").performClick()
        compose.onNodeWithText("Server public key").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Collections").performClick()
        compose.onNodeWithText("Favorites").assertExists()
    }
}
