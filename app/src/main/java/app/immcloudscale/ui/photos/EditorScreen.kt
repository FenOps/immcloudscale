package app.immcloudscale.ui.photos

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.immcloudscale.data.immich.AssetDto
import app.immcloudscale.graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.time.Instant

/** The photo the editor opens (set by the viewer before navigating). */
object EditorSession {
    var asset: AssetDto? = null
}

private enum class Tool { None, Crop, Draw }

/** A pen stroke in image pixels. */
private class PenStroke(val color: Int, val width: Float, val points: MutableList<Offset> = mutableListOf())

/** Crop, rotate and draw on a photo; saves a new copy next to the original, which stays as it was. */
@Composable
fun EditorScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val asset = EditorSession.asset
    if (asset == null) { LaunchedEffect(Unit) { onBack() }; return }
    val scope = rememberCoroutineScope()
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var tool by remember { mutableStateOf(Tool.None) }
    var crop by remember { mutableStateOf(Rect(0f, 0f, 1f, 1f)) } // fractions of the image
    val strokes = remember { mutableStateListOf<PenStroke>() }
    var pen by remember { mutableStateOf(PEN_COLORS.first()) }
    var saving by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var changed by remember { mutableStateOf(false) }

    LaunchedEffect(asset.id) {
        bitmap = withContext(Dispatchers.IO) { runCatching { loadForEditing(g.http, g.immich.originalUrl(asset.id), g.immich.thumbnailUrl(asset.id, preview = true)) }.getOrNull() }
        if (bitmap == null) error = "Couldn't open this photo for editing"
    }

    // Rotating or cropping draws the strokes into the picture first, so they stay where they were drawn.
    fun bake(): Bitmap {
        val b = bitmap!!
        if (strokes.isEmpty()) return b
        val out = b.copy(Bitmap.Config.ARGB_8888, true)
        drawStrokes(Canvas(out), strokes)
        strokes.clear()
        return out
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (changed) confirmLeave = true else onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
                Text("Edit", color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Button(enabled = bitmap != null && changed && !saving, onClick = {
                    saving = true
                    scope.launch {
                        runCatching {
                            val out = withContext(Dispatchers.Default) { bake().let { b -> if (tool == Tool.Crop) applyCrop(b, crop) else b } }
                            saveCopy(g, asset, out)
                        }.onSuccess { onBack() }.onFailure { error = it.message ?: "Couldn't save" }
                        saving = false
                    }
                }) { if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Save copy") }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val b = bitmap
                if (b == null) {
                    if (error == null) CircularProgressIndicator() else Text(error!!, color = Color.White)
                    return@BoxWithConstraints
                }
                val density = LocalDensity.current
                val boxW = with(density) { maxWidth.toPx() }
                val boxH = with(density) { maxHeight.toPx() }
                val scale = minOf(boxW / b.width, boxH / b.height)
                val shown = Size(b.width * scale, b.height * scale)
                val origin = Offset((boxW - shown.width) / 2, (boxH - shown.height) / 2)
                val image = remember(b) { b.asImageBitmap() }
                Image(image, "Photo being edited", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                ComposeCanvas(
                    Modifier.fillMaxSize().pointerInput(tool, b) {
                        when (tool) {
                            Tool.Draw -> detectDragGestures(
                                onDragStart = { p -> strokes += PenStroke(pen.toArgb(), b.width / 150f).apply { points += (p - origin) / scale }; changed = true },
                                onDrag = { change, _ ->
                                    val last = strokes.removeAt(strokes.lastIndex)
                                    last.points += (change.position - origin) / scale
                                    strokes += last // re-adding tells Compose to redraw
                                },
                            )
                            Tool.Crop -> detectDragGestures { change, drag ->
                                // Move the nearest corner of the crop box.
                                val p = (change.position - origin)
                                val fx = drag.x / shown.width
                                val fy = drag.y / shown.height
                                val left = p.x < (crop.left + crop.right) / 2 * shown.width
                                val top = p.y < (crop.top + crop.bottom) / 2 * shown.height
                                crop = Rect(
                                    if (left) (crop.left + fx).coerceIn(0f, crop.right - 0.1f) else crop.left,
                                    if (top) (crop.top + fy).coerceIn(0f, crop.bottom - 0.1f) else crop.top,
                                    if (!left) (crop.right + fx).coerceIn(crop.left + 0.1f, 1f) else crop.right,
                                    if (!top) (crop.bottom + fy).coerceIn(crop.top + 0.1f, 1f) else crop.bottom,
                                )
                                changed = true
                            }
                            Tool.None -> Unit
                        }
                    },
                ) {
                    strokes.forEach { s ->
                        for (i in 1 until s.points.size) drawLine(
                            Color(s.color), origin + s.points[i - 1] * scale, origin + s.points[i] * scale,
                            strokeWidth = s.width * scale, cap = StrokeCap.Round,
                        )
                    }
                    if (tool == Tool.Crop) {
                        val r = Rect(
                            origin.x + crop.left * shown.width, origin.y + crop.top * shown.height,
                            origin.x + crop.right * shown.width, origin.y + crop.bottom * shown.height,
                        )
                        val dim = Color(0x99000000)
                        drawRect(dim, origin, Size(shown.width, r.top - origin.y))
                        drawRect(dim, Offset(origin.x, r.bottom), Size(shown.width, origin.y + shown.height - r.bottom))
                        drawRect(dim, Offset(origin.x, r.top), Size(r.left - origin.x, r.height))
                        drawRect(dim, Offset(r.right, r.top), Size(origin.x + shown.width - r.right, r.height))
                        drawRect(Color.White, r.topLeft, r.size, style = Stroke(width = 2.dp.toPx()))
                    }
                }
            }
            if (tool == Tool.Draw) Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.Center) {
                PEN_COLORS.forEach { c ->
                    Box(
                        Modifier.padding(6.dp).size(32.dp).background(c, CircleShape)
                            .then(if (c == pen) Modifier.border(3.dp, Color.White, CircleShape) else Modifier)
                            .clickable { pen = c },
                    )
                }
                IconButton(onClick = { if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex) }) {
                    Icon(Icons.AutoMirrored.Outlined.Undo, "Undo", tint = Color.White)
                }
            }
            if (tool == Tool.Crop) Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = { crop = Rect(0f, 0f, 1f, 1f); tool = Tool.None }) { Text("Cancel", color = Color.White) }
                Button(onClick = { bitmap = applyCrop(bake(), crop); crop = Rect(0f, 0f, 1f, 1f); tool = Tool.None }) { Text("Apply crop") }
            }
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                ToolButton(Icons.Outlined.RotateRight, "Rotate", false) {
                    bitmap?.let { bitmap = rotate(bake(), 90f); changed = true }
                }
                ToolButton(Icons.Outlined.Crop, "Crop", tool == Tool.Crop) { tool = if (tool == Tool.Crop) Tool.None else Tool.Crop }
                ToolButton(Icons.Outlined.Brush, "Mark up", tool == Tool.Draw) { tool = if (tool == Tool.Draw) Tool.None else Tool.Draw }
            }
        }
    }
    if (confirmLeave) AlertDialog(
        onDismissRequest = { confirmLeave = false },
        title = { Text("Discard your edits?") },
        text = { Text("The original photo isn't changed either way.") },
        confirmButton = { TextButton(onClick = { confirmLeave = false; onBack() }) { Text("Discard") } },
        dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Keep editing") } },
    )
}

private val PEN_COLORS = listOf(Color(0xFFEA4335), Color(0xFFFBBC04), Color(0xFF34A853), Color(0xFF4285F4), Color.White, Color.Black)

@Composable
private fun ToolButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick).padding(8.dp)) {
        Icon(icon, null, tint = if (active) MaterialTheme.colorScheme.primary else Color.White)
        Text(label, color = if (active) MaterialTheme.colorScheme.primary else Color.White, style = MaterialTheme.typography.labelMedium)
    }
}

private fun drawStrokes(c: Canvas, strokes: List<PenStroke>) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    strokes.forEach { s ->
        if (s.points.isEmpty()) return@forEach
        paint.color = s.color; paint.strokeWidth = s.width
        val path = Path().apply { moveTo(s.points[0].x, s.points[0].y); s.points.drop(1).forEach { lineTo(it.x, it.y) } }
        c.drawPath(path, paint)
    }
}

internal fun rotate(b: Bitmap, degrees: Float): Bitmap =
    Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(degrees) }, true)

internal fun applyCrop(b: Bitmap, r: Rect): Bitmap {
    val x = (r.left * b.width).toInt().coerceIn(0, b.width - 1)
    val y = (r.top * b.height).toInt().coerceIn(0, b.height - 1)
    val w = ((r.right - r.left) * b.width).toInt().coerceIn(1, b.width - x)
    val h = ((r.bottom - r.top) * b.height).toInt().coerceIn(1, b.height - y)
    return Bitmap.createBitmap(b, x, y, w, h)
}

/** The original, scaled down to at most about 3000 pixels a side; the preview if the original can't be decoded. */
private fun loadForEditing(http: okhttp3.OkHttpClient, original: String, preview: String): Bitmap? {
    fun fetch(url: String) = http.newCall(Request.Builder().url(url).build()).execute().use { r -> if (r.isSuccessful) r.body?.bytes() else null }
    for (url in listOf(original, preview)) {
        val bytes = runCatching { fetch(url) }.getOrNull() ?: continue
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) continue
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 3000) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.let { return it }
    }
    return null
}

/** Uploads the edited picture as a new photo dated like the original, so it sits right next to it. */
private suspend fun saveCopy(g: app.immcloudscale.AppGraph, asset: AssetDto, b: Bitmap) {
    val bytes = withContext(Dispatchers.Default) { ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray() }
    val name = asset.originalFileName.substringBeforeLast('.').ifBlank { "photo" } + "-edited.jpg"
    val taken = runCatching { Instant.parse(asset.fileCreatedAt) }.getOrDefault(Instant.now())
    g.immich.upload(
        bytes.toRequestBody("image/jpeg".toMediaType()), name, "edit-${asset.id}-${System.currentTimeMillis()}", "immcloudscale-editor",
        taken, Instant.now(), null,
    )
}

private operator fun Offset.div(f: Float) = Offset(x / f, y / f)
