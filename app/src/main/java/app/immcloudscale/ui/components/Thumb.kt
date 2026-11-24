package app.immcloudscale.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.material3.MaterialTheme
import coil3.compose.AsyncImage
import kotlin.math.abs

/**
 * A remote image (thumbnail, preview, avatar). In previews and screenshot tests, where nothing is
 * fetched, it paints a stable photo-like gradient picked from [key] instead.
 */
@Composable
fun RemoteImage(
    url: String?,
    key: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    contentDescription: String? = null,
) {
    if (LocalInspectionMode.current || url == null) {
        Box(modifier.background(placeholderBrush(key)))
        return
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        AsyncImage(model = url, contentDescription = contentDescription, contentScale = contentScale, modifier = Modifier.fillMaxSize())
    }
}

private val scenes = listOf(
    listOf(Color(0xFF8EC5FC), Color(0xFFE0C3FC)), // sky
    listOf(Color(0xFF2B5876), Color(0xFF4E4376)), // dusk
    listOf(Color(0xFFF6D365), Color(0xFFFDA085)), // sunset
    listOf(Color(0xFF84FAB0), Color(0xFF8FD3F4)), // lake
    listOf(Color(0xFF355C3A), Color(0xFF9DC08B)), // forest
    listOf(Color(0xFFD4A373), Color(0xFFFAEDCD)), // beach
    listOf(Color(0xFF434343), Color(0xFF8E8E8E)), // city
    listOf(Color(0xFFFF9A9E), Color(0xFFFECFEF)), // blossom
    listOf(Color(0xFF30CFD0), Color(0xFF330867)), // night
    listOf(Color(0xFFC79081), Color(0xFFDFA579)), // autumn
)

fun placeholderBrush(key: String): Brush {
    val c = scenes[abs(key.hashCode()) % scenes.size]
    return Brush.linearGradient(c)
}
