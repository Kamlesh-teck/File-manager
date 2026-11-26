package com.example.filemanager

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.io.File

private val CODE_TYPES = setOf(
    "kt", "kts", "java", "js", "ts", "jsx", "tsx", "py", "c", "cpp", "h", "hpp", "cs", "go",
    "rs", "swift", "dart", "php", "xml", "json", "css", "sh", "gradle", "yml", "yaml",
    "properties", "toml", "smali", "sql"
)
private val DOC_TYPES = setOf("txt", "md", "log", "doc", "docx", "rtf", "csv", "ini", "conf")
private val ARCHIVE_TYPES = setOf("zip", "rar", "7z", "tar", "gz", "jar", "xz", "bz2")

/** MT Manager jaisa rang-birangi rounded icon: file ke type ke hisab se rang aur nishan */
@Composable
fun FileIcon(file: File, size: Dp = 28.dp) {
    val dark = LocalDark.current
    val folderColor =
        if (dark) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.primary

    val ext = file.extension.lowercase()
    val (icon, color) = when {
        file.isDirectory -> Icons.Default.Folder to folderColor
        ext == "pdf" -> Icons.Default.PictureAsPdf to Color(0xFFD32F2F)
        ext == "apk" -> Icons.Default.Android to Color(0xFF2E7D32)
        ext in ARCHIVE_TYPES -> Icons.Default.Archive to Color(0xFF795548)
        ext == "html" || ext == "htm" -> Icons.Default.Language to Color(0xFF1976D2)
        ext in IMAGE_EXTS -> Icons.Default.Image to Color(0xFF8E24AA)
        ext in AUDIO_EXTS -> Icons.Default.MusicNote to Color(0xFFEF6C00)
        ext in VIDEO_EXTS -> Icons.Default.Videocam to Color(0xFFD81B60)
        ext in CODE_TYPES -> Icons.Default.Code to Color(0xFF00897B)
        ext in DOC_TYPES -> Icons.Default.Description to Color(0xFF546E7A)
        else -> Icons.Default.InsertDriveFile to Color(0xFF757575)
    }
    IconBox(icon, color, size)
}

@Composable
private fun IconBox(icon: ImageVector, color: Color, size: Dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.25f)).background(color),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(size * 0.64f))
    }
}
