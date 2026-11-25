package app.immcloudscale.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import app.immcloudscale.ImmApp
import app.immcloudscale.data.AppSettings
import app.immcloudscale.data.immich.AssetQuery
import app.immcloudscale.ui.files.FilesViewModel
import app.immcloudscale.ui.gui.FakeHomeServer
import app.immcloudscale.ui.photos.AssetListViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import android.os.Looper

/** Lists opened before pairing (or before anything was backed up) must fill in once there is something to show. */
@RunWith(RobolectricTestRunner::class)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@Config(sdk = [34])
class LibraryRefreshTest {
    private val home = FakeHomeServer()
    private val graph get() = ApplicationProvider.getApplicationContext<ImmApp>().graph

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            ApplicationProvider.getApplicationContext<Context>(), Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        runBlocking { graph.settings.update { AppSettings() } }
    }

    @After fun tearDown() {
        runBlocking { graph.settings.update { AppSettings() } }
        home.shutdown()
    }

    @Test fun listsOpenedBeforePairingLoadAfterIt() {
        val photos = AssetListViewModel(graph, AssetQuery.Timeline)
        val files = FilesViewModel(graph)
        idle()
        assertTrue(photos.state.value.assets.isEmpty())
        assertTrue(files.state.value.items.isEmpty())

        // What pairing stores.
        runBlocking {
            graph.settings.update {
                it.copy(
                    immichUrl = home.base, immichToken = "demo", immichTokenIsApiKey = true,
                    ncUrl = home.base + "/nc", ncUser = "camille", ncAppPassword = "demo", ncUserId = "camille",
                )
            }
        }
        waitFor { photos.state.value.assets.isNotEmpty() && files.state.value.items.isNotEmpty() }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun waitFor(condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        while (!condition()) {
            assertTrue("timed out waiting for the lists to load", System.currentTimeMillis() < until)
            idle()
            Thread.sleep(20)
        }
    }
}
