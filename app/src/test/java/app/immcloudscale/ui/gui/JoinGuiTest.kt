package app.immcloudscale.ui.gui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.immcloudscale.ImmApp
import app.immcloudscale.data.AppSettings
import app.immcloudscale.ui.account.JoinDialog
import app.immcloudscale.ui.account.PairCard
import app.immcloudscale.ui.theme.ImmTheme
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** "Ask to join" screens, captured on their own (see AppGuiTest for the rest of the tour). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w400dp-h860dp-xxhdpi")
class JoinGuiTest {
    @get:Rule val compose = createComposeRule()
    private val out = System.getProperty("gui.out") ?: "build/gui"
    private val graph get() = ApplicationProvider.getApplicationContext<ImmApp>().graph

    @After fun reset() = runBlocking { graph.settings.update { AppSettings() } }

    private fun shot(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage("$out/$name.png", RoborazziOptions(taskType = RoborazziTaskType.Record))
    }

    // Robolectric never settles a dialog with text fields when the screen size is set, so this one keeps the default size.
    @Test @Config(qualifiers = "xxhdpi") fun askToJoin() {
        runBlocking { graph.settings.update { AppSettings() } }
        compose.setContent { ImmTheme(dark = false, dynamic = false) { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) { JoinDialog {} } } }
        shot("28-ask-to-join")
    }

    @Test fun waitingForApproval() {
        // A request that never gets an answer here (no server), so the card stays on "waiting".
        runBlocking { graph.settings.update { AppSettings(joinServer = "http://127.0.0.1:9", joinId = "req_demo", joinToken = "t", joinName = "lea") } }
        compose.setContent {
            ImmTheme(dark = false, dynamic = false) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(16.dp)) { PairCard(onPaired = {}) }
            }
        }
        shot("29-waiting-for-approval")
    }
}
