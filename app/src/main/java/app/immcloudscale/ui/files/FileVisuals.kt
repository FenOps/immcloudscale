package app.immcloudscale.ui.files

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import app.immcloudscale.data.nextcloud.CloudFile
import app.immcloudscale.ui.theme.GoogleColors

enum class FileKind { Folder, Image, Video, Audio, Pdf, Doc, Sheet, Slides, Archive, Code, Text, Other }

fun CloudFile.kind(): FileKind = when {
    isFolder -> FileKind.Folder
    isImage || extension in setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "svg") -> FileKind.Image
    isVideo || extension in setOf("mp4", "mov", "mkv", "webm", "avi", "m4v") -> FileKind.Video
    contentType?.startsWith("audio/") == true || extension in setOf("mp3", "flac", "wav", "m4a", "ogg", "opus") -> FileKind.Audio
    extension == "pdf" -> FileKind.Pdf
    extension in setOf("doc", "docx", "odt", "rtf", "pages") -> FileKind.Doc
    extension in setOf("xls", "xlsx", "ods", "csv", "numbers") -> FileKind.Sheet
    extension in setOf("ppt", "pptx", "odp", "key") -> FileKind.Slides
    extension in setOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz") -> FileKind.Archive
    extension in setOf("kt", "java", "py", "js", "ts", "json", "xml", "html", "css", "sh", "yml", "yaml", "toml", "c", "cpp", "h", "go", "rs") -> FileKind.Code
    extension in setOf("txt", "md", "log", "ini", "conf", "cfg") || contentType?.startsWith("text/") == true -> FileKind.Text
    else -> FileKind.Other
}

/** Text-like files we can show inline. */
fun CloudFile.isTextLike() = kind() in setOf(FileKind.Text, FileKind.Code) || extension == "csv"

private fun visual(kind: FileKind, shared: Boolean): Pair<ImageVector, Color?> = when (kind) {
    FileKind.Folder -> (if (shared) Icons.Filled.FolderShared else Icons.Filled.Folder) to null
    FileKind.Image -> Icons.Filled.Image to GoogleColors.Red
    FileKind.Video -> Icons.Filled.Movie to GoogleColors.Red
    FileKind.Audio -> Icons.Filled.AudioFile to GoogleColors.Red
    FileKind.Pdf -> Icons.Filled.PictureAsPdf to GoogleColors.Pdf
    FileKind.Doc -> Icons.Filled.Description to GoogleColors.Doc
    FileKind.Sheet -> Icons.Filled.TableChart to GoogleColors.Sheet
    FileKind.Slides -> Icons.Filled.Slideshow to GoogleColors.Slide
    FileKind.Archive -> Icons.Filled.FolderZip to null
    FileKind.Code -> Icons.Filled.Code to GoogleColors.Doc
    FileKind.Text -> Icons.AutoMirrored.Filled.Article to GoogleColors.Doc
    FileKind.Other -> Icons.AutoMirrored.Filled.InsertDriveFile to null
}

@Composable
fun FileIcon(f: CloudFile, size: Dp = 24.dp, modifier: Modifier = Modifier) {
    val (icon, color) = visual(f.kind(), f.shared)
    Icon(icon, null, modifier.size(size), tint = color ?: MaterialTheme.colorScheme.onSurfaceVariant)
}
