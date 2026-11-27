package com.example.filemanager

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaPlayer
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
val VIDEO_EXTS = setOf("mp4", "mkv", "3gp", "webm", "avi", "mov", "m4v")
val AUDIO_EXTS = setOf("mp3", "m4a", "wav", "ogg", "flac", "aac", "opus", "amr")

/** File ke type ke hisab se sahi screen kholta hai */
fun openFile(context: Context, f: File) {
    val ext = f.extension.lowercase()
    val target = when {
        ext == "pdf" -> PdfActivity::class.java
        ext == "apk" -> ApkActivity::class.java
        ext in ZIP_EXTS -> ZipActivity::class.java
        ext in IMAGE_EXTS || ext in VIDEO_EXTS || ext in AUDIO_EXTS -> MediaActivity::class.java
        else -> EditorActivity::class.java
    }
    context.startActivity(Intent(context, target).putExtra("path", f.absolutePath))
}

class MediaActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra("path")
        if (path == null) { finish(); return }
        val file = File(path)
        if (file.extension.lowercase() in VIDEO_EXTS)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            AppTheme {
                Surface(
                    color = Color.Black,
                    contentColor = Color.White,
                    modifier = Modifier.fillMaxSize()
                ) {
                    when (file.extension.lowercase()) {
                        in IMAGE_EXTS -> ImageViewer(file) { finish() }
                        in VIDEO_EXTS -> VideoPlayer(file)
                        else -> MusicPlayerScreen(file) { finish() }
                    }
                }
            }
        }
    }
}

// ---------------- IMAGE ----------------

@Composable
fun ImageViewer(file: File, onClose: () -> Unit) {
    val images = remember {
        val list = file.parentFile?.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in IMAGE_EXTS }
            ?.sortedBy { it.name.lowercase() } ?: emptyList()
        if (list.any { it.absolutePath == file.absolutePath }) list else listOf(file)
    }
    val start = images.indexOfFirst { it.absolutePath == file.absolutePath }.coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = start, pageCount = { images.size })
    var zoomed by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        HorizontalPager(state = pager, userScrollEnabled = !zoomed) { page ->
            ZoomableImage(images[page]) { zoomed = it }
        }
        Row(
            Modifier.fillMaxWidth().background(Color(0x88000000)).statusBarsPadding(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
            }
            Text(
                images[pager.currentPage].name, color = Color.White, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
            )
            Text("${pager.currentPage + 1}/${images.size}", color = Color.White,
                modifier = Modifier.padding(end = 12.dp))
        }
    }
}

@Composable
fun ZoomableImage(file: File, onZoom: (Boolean) -> Unit) {
    var bitmap by remember(file) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(file) { mutableStateOf(false) }
    var scale by remember(file) { mutableFloatStateOf(1f) }
    var offset by remember(file) { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(file) {
        val bmp = withContext(Dispatchers.IO) { decodeSampled(file, 2048) }
        if (bmp == null) failed = true else bitmap = bmp
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(file) {
                detectTapGestures(onDoubleTap = {
                    scale = if (scale > 1f) 1f else 2.5f
                    offset = Offset.Zero
                    onZoom(scale > 1f)
                })
            }
            .pointerInput(file) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        if (zoom != 1f || scale > 1f) {
                            scale = (scale * zoom).coerceIn(1f, 6f)
                            val maxX = size.width * (scale - 1f) / 2f
                            val maxY = size.height * (scale - 1f) / 2f
                            offset = if (scale <= 1f) Offset.Zero else Offset(
                                (offset.x + pan.x).coerceIn(-maxX, maxX),
                                (offset.y + pan.y).coerceIn(-maxY, maxY)
                            )
                            onZoom(scale > 1f)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center
    ) {
        val bmp = bitmap
        when {
            failed -> Text("Image khul nahi payi", color = Color.White)
            bmp == null -> CircularProgressIndicator()
            else -> Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = file.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer(
                    scaleX = scale, scaleY = scale,
                    translationX = offset.x, translationY = offset.y
                )
            )
        }
    }
}

fun decodeSampled(f: File, max: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(f.absolutePath, bounds)
    var sample = 1
    while (bounds.outWidth / sample > max || bounds.outHeight / sample > max) sample *= 2
    val bmp = BitmapFactory.decodeFile(
        f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }
    ) ?: return null
    val rotation = try {
        when (ExifInterface(f.absolutePath)
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
    } catch (e: Exception) { 0f }
    return if (rotation == 0f) bmp else Bitmap.createBitmap(
        bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation) }, true
    )
}

// ---------------- VIDEO ----------------

@Composable
fun VideoPlayer(file: File) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val vv = VideoView(ctx)
            val mc = MediaController(ctx)
            mc.setAnchorView(vv)
            vv.setMediaController(mc)
            vv.setVideoPath(file.absolutePath)
            vv.setOnPreparedListener { it.start(); mc.show(3000) }
            FrameLayout(ctx).apply {
                addView(
                    vv,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        Gravity.CENTER
                    )
                )
                tag = vv
            }
        },
        onRelease = { (it.tag as? VideoView)?.stopPlayback() }
    )
}

fun fmtTime(ms: Int): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
