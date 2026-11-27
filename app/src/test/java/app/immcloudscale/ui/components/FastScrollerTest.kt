package app.immcloudscale.ui.components

import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FastScrollerTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var state: LazyGridState

    private fun grid(count: Int) = compose.setContent {
        state = rememberLazyGridState()
        Box(Modifier.fillMaxSize()) {
            LazyVerticalGrid(GridCells.Fixed(4), state = state, modifier = Modifier.fillMaxSize().testTag("grid")) {
                items(count, key = { it }) { i -> Text("$i", Modifier.aspectRatio(1f)) }
            }
            FastScroller(state, label = { key -> (key as? Int)?.let { "Month ${it / 100}" } }, modifier = Modifier.align(Alignment.TopEnd))
        }
    }

    @Test fun scrollingShowsAHandleThatJumpsThroughTheList() {
        grid(2000)
        compose.onNodeWithContentDescription("Scroll handle").assertDoesNotExist() // hidden until it scrolls
        compose.mainClock.autoAdvance = false // it hides again a moment after scrolling stops
        compose.onNodeWithTag("grid").performTouchInput { swipeUp() }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithContentDescription("Scroll handle").assertExists()
        compose.onNodeWithContentDescription("Scroll handle").performTouchInput { down(center); moveBy(Offset(0f, 400f)) }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Month", substring = true).assertExists() // which month it's passing
        compose.onNodeWithContentDescription("Scroll handle").performTouchInput { up() }
        compose.mainClock.advanceTimeBy(500)
        assertTrue("jumped to ${state.firstVisibleItemIndex}", state.firstVisibleItemIndex > 200)
        repeat(30) { compose.mainClock.advanceTimeBy(100); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)) }
        compose.onNodeWithContentDescription("Scroll handle").assertDoesNotExist() // and fades once left alone
    }

    @Test fun aShortListHasNoHandle() {
        grid(12)
        compose.onNodeWithTag("grid").performTouchInput { swipeUp() }
        compose.onNodeWithContentDescription("Scroll handle").assertDoesNotExist()
    }
}
