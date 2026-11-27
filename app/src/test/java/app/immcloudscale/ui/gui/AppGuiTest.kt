package app.immcloudscale.ui.gui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.pressBack
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import app.immcloudscale.ImmApp
import app.immcloudscale.MainActivity
import app.immcloudscale.data.AppSettings
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.allowHardware
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshots of the real app (navigation, view models, HTTP clients, image loading) talking to a pretend
 * home server. Run with `./gradlew :app:testDebugUnitTest --tests '*AppGuiTest*' -Pgui`; images go to
 * app/build/gui/. Android 11, so the screens use the standard palette rather than wallpaper colours.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w400dp-h860dp-xxhdpi")
@OptIn(ExperimentalRoborazziApi::class)
class AppGuiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val home = FakeHomeServer().also { it.prepareImages() }
    private val out = System.getProperty("gui.out") ?: "build/gui"

    @After fun stop() = home.shutdown()

    @Before fun testWorkManager() {
        // A synchronous WorkManager: the real one keeps database threads running after the test closes the database.
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            ctx, Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
    }

    @Before fun softwareBitmaps() {
        // Robolectric has no GPU, so images must stay in normal memory (the app uses hardware bitmaps on phones),
        // and its native graphics aren't thread-safe, so images are decoded on the main thread.
        val app = ApplicationProvider.getApplicationContext<ImmApp>()
        SingletonImageLoader.setUnsafe(
            ImageLoader.Builder(app)
                .components { add(OkHttpNetworkFetcherFactory(callFactory = { app.graph.http })) }
                .allowHardware(false)
                .decoderCoroutineContext(Dispatchers.Main)
                .memoryCache(null)
                .diskCache(null)
                .build(),
        )
    }

    private fun signIn() {
        val g = ApplicationProvider.getApplicationContext<ImmApp>().graph
        runBlocking {
            g.settings.update {
                it.copy(
                    immichUrl = home.base, immichToken = "demo", immichTokenIsApiKey = true,
                    immichUserName = "Camille Martin", immichEmail = "camille@example.com",
                    ncUrl = home.base + "/nc", ncUser = "camille", ncAppPassword = "demo", ncUserId = "camille",
                    backupEnabled = true, lastBackupAt = System.currentTimeMillis() - 4 * 60_000,
                    pairedServer = home.base, pairedServerName = "homeserver",
                )
            }
        }
    }

    /** Lets network calls, image decoding and animations finish before a screenshot. */
    private fun settle(ms: Long = 2500) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(100)
            Thread.sleep(60)
        }
        compose.waitForIdle()
    }

    private fun shot(name: String) {
        settle()
        captureScreenRoboImage("$out/$name.png", RoborazziOptions(taskType = RoborazziTaskType.Record))
    }

    private fun tab(label: String) = compose.onNode(hasText(label) and hasClickAction()).performClick()

    @Test fun firstRun() {
        // Settings live in a process-wide store, so clear what an earlier test saved.
        runBlocking { ApplicationProvider.getApplicationContext<ImmApp>().graph.settings.update { AppSettings() } }
        ActivityScenario.launch(MainActivity::class.java).use {
            shot("01-welcome-and-pairing")
        }
    }

    @Test fun tour() {
        signIn()
        ActivityScenario.launch(MainActivity::class.java).use {
            shot("02-photos")
            compose.onAllNodesWithContentDescription("Photo", substring = true)[3].performClick()
            shot("03-viewer")
            compose.onNodeWithText("Info").performClick()
            shot("04-photo-details")
            pressBack()
            settle(800)
            pressBack()
            settle(800)
            compose.onNodeWithContentDescription("Search").performClick()
            shot("05-search")
            pressBack()
            settle(800)
            tab("Collections")
            shot("06-collections")
            compose.onNodeWithText("Summer in Lisbon").performClick()
            shot("07-album")
            pressBack()
            settle(800)
            tab("Files")
            shot("08-files")
            compose.onAllNodesWithContentDescription("More actions")[4].performClick()
            shot("09-file-actions")
            pressBack()
            settle(800)
            tab("Network")
            shot("10-network")
            compose.onAllNodesWithContentDescription("Account and settings").onFirst().performClick()
            shot("11-account")
            pressBack()
            settle(800)
            tab("Photos")
            compose.onNodeWithContentDescription("Backup complete").performClick()
            shot("12-backup-settings")
        }
    }

    /**
     * Away from home Wi-Fi: the server doesn't answer and remote access can't start. The screens must still work.
     * (Tailscale itself can't run here; tsbridge_test.go covers it starting the way it does on a phone.)
     */
    @Test fun awayFromHome() {
        val g = ApplicationProvider.getApplicationContext<ImmApp>().graph
        val away = "http://127.0.0.1:9"
        runBlocking {
            g.settings.update {
                it.copy(
                    immichUrl = away, immichToken = "demo", immichTokenIsApiKey = true, immichUserName = "Camille Martin",
                    ncUrl = "$away/nc", ncUser = "camille", ncAppPassword = "demo", ncUserId = "camille",
                    backupEnabled = true,
                    pairedServer = away, pairedServerName = "homeserver", pairedServerId = "ics_home",
                    tsEnabled = true, tsServerIp = "100.64.0.1", tsAuthKey = "tskey-auth-demo",
                    deviceToken = "t", pairedDeviceId = "dev_x",
                )
            }
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            g.tailscale.onNetwork(false)
            settle(4000)
            for (t in listOf("Collections", "Files", "Network", "Photos")) {
                tab(t)
                settle(2500)
            }
            compose.onAllNodesWithContentDescription("Account and settings").onFirst().performClick()
            settle(2500)
            shot("40-away-from-home")
        }
    }

    /** The features added in 0.3.0: remote access, backup choices, deleting, editing, members and more. */
    @Test fun newFeatures() {
        signIn()
        val g = ApplicationProvider.getApplicationContext<ImmApp>().graph
        runBlocking {
            g.settings.update {
                it.copy(
                    pairedServerId = "ics_demo", pairedDeviceId = "dev_demo", deviceToken = "demo", isAdmin = true,
                    tsServerIp = "100.101.102.103", tsEnabled = true,
                )
            }
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            tab("Network")
            shot("15-network-remote-access")
            tab("Photos")
            compose.onNodeWithContentDescription("Backup complete").performClick()
            compose.onNodeWithText("Where backup runs").performScrollTo()
            shot("16-backup-where-and-schedule")
            compose.onNodeWithText("On a schedule").performScrollTo().performClick()
            compose.onNodeWithText("Phone storage").performScrollTo()
            shot("17-backup-schedule-and-free-up")
            pressBack()
            settle(800)

            compose.onAllNodesWithContentDescription("Photo", substring = true)[3].performClick()
            settle(1500)
            compose.onNodeWithContentDescription("More").performClick()
            shot("18-viewer-menu")
            compose.onNodeWithText("Edit").performClick()
            settle(2500)
            compose.onNodeWithText("Mark up").performClick()
            compose.onNodeWithContentDescription("Photo being edited").performTouchInput {
                swipe(Offset(width * 0.3f, height * 0.55f), Offset(width * 0.7f, height * 0.4f), 400)
            }
            shot("19-editor")
            pressBack()
            settle(800)
            compose.onNodeWithText("Discard").performClick()
            settle(1000)
            compose.onNodeWithText("Delete").performClick()
            shot("20-move-to-trash-asks-first")
            compose.onNodeWithText("Cancel").performClick()
            settle(800)
            compose.onNodeWithContentDescription("Back").performClick()
            settle(2000)

            compose.onAllNodesWithContentDescription("Photo", substring = true)[1].performTouchInput { longClick() }
            compose.onAllNodesWithContentDescription("Photo", substring = true)[2].performClick()
            compose.onAllNodesWithContentDescription("Photo", substring = true)[4].performClick()
            compose.onNodeWithContentDescription("More").performClick()
            shot("21-selection-menu")
            compose.onNodeWithText("Make a collage").performClick()
            shot("22-collage")
            compose.onNodeWithText("Cancel").performClick()
            compose.onNodeWithContentDescription("Clear selection").performClick()
            settle(800)

            tab("Files")
            settle(1500)
            compose.onNodeWithText("Budget 2026.xlsx").performTouchInput { longClick() }
            compose.onNodeWithText("Kitchen renovation.jpg").performClick()
            compose.onNodeWithText("2 selected").assertExists()
            shot("33-files-selected")
            compose.onNodeWithContentDescription("Remove").performClick()
            compose.onNodeWithText("Remove 2 items?").assertExists()
            compose.onNodeWithText("Cancel").performClick()
            compose.onNodeWithContentDescription("Clear selection").performClick()
            settle(800)

            tab("Collections")
            compose.onNodeWithText("Duplicates").performClick()
            shot("23-duplicates")
            pressBack()
            settle(800)
            compose.onNodeWithText("Locked folder").performClick()
            shot("24-locked-folder")
            pressBack()
            settle(800)

            compose.onAllNodesWithContentDescription("Account and settings").onFirst().performClick()
            shot("25-account-theme-members")
            compose.onNodeWithText("Dark").performClick()
            shot("26-dark-theme")
            compose.onNodeWithText("Members").performClick()
            shot("27-members-dark")
            compose.onNodeWithContentDescription("Pair an Android phone for tom").performClick()
            shot("32-invite-to-send")
            compose.onNodeWithText("Done").performClick()
            settle(800)
            pressBack()
            settle(800)
            tab("Network")
            shot("30-network-dark")
            tab("Collections")
            compose.onNodeWithText("Duplicates").performClick()
            shot("31-duplicates-dark")
        }
    }

    /** An invite opened on a new phone: the dialog says until when it works. */
    @Test fun openingAnInvite() {
        runBlocking { ApplicationProvider.getApplicationContext<ImmApp>().graph.settings.update { AppSettings() } }
        val exp = System.currentTimeMillis() / 1000 + 12 * 60
        val link = "immcloudscale://pair?server=http%3A%2F%2F192.168.1.49%3A8790&code=K7QX-M2PA&exp=$exp&ts=100.101.102.103"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link)).setClass(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
        ActivityScenario.launch<MainActivity>(intent).use { shot("33-opening-an-invite") }
    }

    @Test @Config(qualifiers = "+night") fun darkTheme() {
        signIn()
        ActivityScenario.launch(MainActivity::class.java).use {
            shot("13-photos-dark")
            tab("Files")
            shot("14-files-dark")
        }
    }
}
