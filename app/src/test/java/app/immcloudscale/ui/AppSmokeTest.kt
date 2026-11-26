package app.immcloudscale.ui

import android.content.Context
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import app.immcloudscale.MainActivity
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
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            ctx, Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
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
        compose.onNodeWithText("Pair with your server").assertExists()
        compose.onNodeWithText("Collections").performClick()
        compose.onNodeWithText("Favorites").assertExists()
    }
}
