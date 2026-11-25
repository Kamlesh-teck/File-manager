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
    val target = if (ext in IMAGE_EXTS || ext in VIDEO_EXTS || ext in AUDIO_EXTS)
        MediaActivity::class.java else EditorActivity::class.java
    context.startActivity(Intent(context, target).putExtra("path", f.absolutePath))
}

class MediaActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra("path")
        if (path == null) { finish(); return }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val file = File(path)
        setContent {
            MaterialTheme {
                Surface(
                    color = Color.Black,
                    contentColor = Color.White,
                    modifier = Modifier.fillMaxSize()
                ) {
                    when (file.extension.lowercase()) {
                        in IMAGE_EXTS -> ImageViewer(file) { finish() }
                        in VIDEO_EXTS -> VideoPlayer(file)
                        else -> AudioPlayer(file)
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

// ---------------- AUDIO ----------------

@Composable
fun AudioPlayer(file: File) {
    val player = remember { MediaPlayer() }
    var ready by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var pos by remember { mutableIntStateOf(0) }
    var dur by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    DisposableEffect(file) {
        try {
            player.setDataSource(file.absolutePath)
            player.setOnPreparedListener {
                dur = it.duration; ready = true; it.start(); playing = true
            }
            player.setOnCompletionListener { playing = false; pos = dur }
            player.setOnErrorListener { _, _, _ ->
                error = "Ye audio chal nahi payi"; true
            }
            player.prepareAsync()
        } catch (e: Exception) {
            error = "Ye audio chal nahi payi"
        }
        onDispose { try { player.release() } catch (_: Exception) {} }
    }

    LaunchedEffect(playing, dragging) {
        while (playing && !dragging) {
            pos = try { player.currentPosition } catch (e: Exception) { pos }
            delay(250)
        }
    }

    fun seekBy(ms: Int) {
        if (!ready) return
        val t = (player.currentPosition + ms).coerceIn(0, dur)
        player.seekTo(t); pos = t
    }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.MusicNote, null, tint = Color.White, modifier = Modifier.size(96.dp))
        Spacer(Modifier.height(16.dp))
        Text(file.name, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(24.dp))

        if (error != null) {
            Text(error ?: "", color = Color.White)
        } else {
            Slider(
                value = pos.toFloat(),
                onValueChange = { pos = it.toInt(); dragging = true },
                onValueChangeFinished = {
                    if (ready) player.seekTo(pos)
                    dragging = false
                },
                valueRange = 0f..maxOf(dur, 1).toFloat()
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(fmtTime(pos), color = Color.White)
                Text(fmtTime(dur), color = Color.White)
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { seekBy(-10_000) }) {
                    Icon(Icons.Default.Replay10, "-10s", tint = Color.White,
                        modifier = Modifier.size(40.dp))
                }
                Spacer(Modifier.width(16.dp))
                FilledIconButton(
                    onClick = {
                        if (!ready) return@FilledIconButton
                        if (playing) { player.pause(); playing = false }
                        else {
                            if (pos >= dur - 300) { player.seekTo(0); pos = 0 }
                            player.start(); playing = true
                        }
                    },
                    modifier = Modifier.size(72.dp)
                ) {
                    Icon(
                        if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        "Play/Pause", modifier = Modifier.size(40.dp)
                    )
                }
                Spacer(Modifier.width(16.dp))
                IconButton(onClick = { seekBy(10_000) }) {
                    Icon(Icons.Default.Forward10, "+10s", tint = Color.White,
                        modifier = Modifier.size(40.dp))
                }
            }
        }
    }
}

fun fmtTime(ms: Int): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
