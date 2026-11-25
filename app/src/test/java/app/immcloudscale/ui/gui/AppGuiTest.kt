package app.immcloudscale.ui.gui

import android.os.Looper
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.immcloudscale.ImmApp
import app.immcloudscale.MainActivity
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.allowHardware
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
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(
            ctx, androidx.work.Configuration.Builder().setExecutor(androidx.work.testing.SynchronousExecutor()).build(),
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
                    vpnConfig = DEMO_VPN, backupEnabled = true, lastBackupAt = System.currentTimeMillis() - 4 * 60_000,
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
        runBlocking { ApplicationProvider.getApplicationContext<ImmApp>().graph.settings.update { app.immcloudscale.data.AppSettings() } }
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
            androidx.test.espresso.Espresso.pressBack()
            settle(800)
            androidx.test.espresso.Espresso.pressBack()
            settle(800)
            compose.onNodeWithContentDescription("Search").performClick()
            shot("05-search")
            androidx.test.espresso.Espresso.pressBack()
            settle(800)
            tab("Collections")
            shot("06-collections")
            compose.onNodeWithText("Summer in Lisbon").performClick()
            shot("07-album")
            androidx.test.espresso.Espresso.pressBack()
            settle(800)
            tab("Files")
            shot("08-files")
            compose.onAllNodesWithContentDescription("More actions")[4].performClick()
            shot("09-file-actions")
            androidx.test.espresso.Espresso.pressBack()
            settle(800)
            tab("Network")
            shot("10-network")
            compose.onAllNodesWithContentDescription("Account and settings").onFirst().performClick()
            shot("11-account")
            androidx.test.espresso.Espresso.pressBack()
            settle(800)
            tab("Photos")
            compose.onNodeWithContentDescription("Backup complete").performClick()
            shot("12-backup-settings")
        }
    }

    @Test @Config(qualifiers = "+night") fun darkTheme() {
        signIn()
        ActivityScenario.launch(MainActivity::class.java).use {
            shot("13-photos-dark")
            tab("Files")
            shot("14-files-dark")
        }
    }

    companion object {
        val DEMO_VPN = """
            [Interface]
            PrivateKey = yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=
            Address = 10.8.0.2/32
            # Name = homeserver
            [Peer]
            PublicKey = xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=
            AllowedIPs = 10.8.0.0/24, 192.168.1.0/24
            Endpoint = myhome.duckdns.org:51820
            PersistentKeepalive = 25
        """.trimIndent()
    }
}
