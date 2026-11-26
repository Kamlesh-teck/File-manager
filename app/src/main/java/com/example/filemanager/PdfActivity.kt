package com.example.filemanager

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class PdfActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra("path")
        if (path == null) { finish(); return }
        setContent {
            AppTheme { PdfScreen(File(path)) { finish() } }
        }
    }
}

class PdfHolder {
    var renderer: PdfRenderer? = null
    var pfd: ParcelFileDescriptor? = null
}

class BitmapCache(maxBytes: Int) : LruCache<String, Bitmap>(maxBytes) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
}

private val ZOOM_LEVELS = listOf(1f, 1.5f, 2f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfScreen(file: File, onClose: () -> Unit) {
    val holder = remember { PdfHolder() }
    val mutex = remember { Mutex() }
    val cache = remember { BitmapCache((Runtime.getRuntime().maxMemory() / 6).toInt()) }
    var sizes by remember { mutableStateOf<List<Pair<Int, Int>>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var zoomIdx by remember { mutableIntStateOf(0) }
    var showGoto by remember { mutableStateOf(false) }
    var gotoText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val current by remember { derivedStateOf { listState.firstVisibleItemIndex + 1 } }

    LaunchedEffect(file) {
        withContext(Dispatchers.IO) {
            try {
                val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                holder.pfd = pfd
                val r = PdfRenderer(pfd)
                holder.renderer = r
                val list = (0 until r.pageCount).map { i ->
                    val p = r.openPage(i)
                    val s = p.width to p.height
                    p.close()
                    s
                }
                if (list.isEmpty()) error = "PDF me koi page nahi hai" else sizes = list
            } catch (e: SecurityException) {
                error = "Password wali PDF abhi support nahi hai"
            } catch (e: Exception) {
                error = "PDF khul nahi payi"
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try { holder.renderer?.close() } catch (_: Exception) {}
            try { holder.pfd?.close() } catch (_: Exception) {}
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = appBarColors(),
                title = {
                    Column {
                        Text(file.name, maxLines = 1, style = MaterialTheme.typography.titleMedium)
                        if (sizes.isNotEmpty()) {
                            Text(
                                "Page $current / ${sizes.size}  (tap: page par jao)",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.clickable { gotoText = ""; showGoto = true }
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { if (zoomIdx > 0) zoomIdx-- },
                        enabled = zoomIdx > 0
                    ) { Icon(Icons.Default.ZoomOut, "Zoom out") }
                    IconButton(
                        onClick = { if (zoomIdx < ZOOM_LEVELS.lastIndex) zoomIdx++ },
                        enabled = zoomIdx < ZOOM_LEVELS.lastIndex
                    ) { Icon(Icons.Default.ZoomIn, "Zoom in") }
                }
            )
        }
    ) { pad ->
        BoxWithConstraints(
            Modifier.padding(pad).fillMaxSize().background(Color(0xFF424242))
        ) {
            when {
                error != null -> Text(
                    error ?: "", color = Color.White,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp)
                )
                sizes.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                else -> {
                    val pageW = maxWidth * ZOOM_LEVELS[zoomIdx]
                    val widthPx = with(LocalDensity.current) { pageW.toPx() }.toInt()
                    Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
                        LazyColumn(
                            Modifier.width(pageW).fillMaxHeight(),
                            state = listState,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            itemsIndexed(sizes) { index, size ->
                                PdfPage(
                                    index, size, pageW.value, widthPx, holder, mutex, cache
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showGoto) {
        AlertDialog(
            onDismissRequest = { showGoto = false },
            title = { Text("Page number (1-${sizes.size})") },
            text = {
                OutlinedTextField(
                    gotoText, { gotoText = it.filter { c -> c.isDigit() } }, singleLine = true
                )
            },
            confirmButton = {
                TextButton({
                    val n = gotoText.toIntOrNull()
                    showGoto = false
                    if (n != null && n in 1..sizes.size)
                        scope.launch { listState.scrollToItem(n - 1) }
                }) { Text("Jao") }
            },
            dismissButton = { TextButton({ showGoto = false }) { Text("Cancel") } }
        )
    }
}

@Composable
fun PdfPage(
    index: Int,
    size: Pair<Int, Int>,
    widthDp: Float,
    widthPx: Int,
    holder: PdfHolder,
    mutex: Mutex,
    cache: BitmapCache
) {
    val key = "$index@$widthPx"
    var bmp by remember(key) { mutableStateOf(cache.get(key)) }

    LaunchedEffect(key) {
        if (bmp == null) {
            val b = renderPage(holder, mutex, index, widthPx, size.first, size.second)
            if (b != null) { cache.put(key, b); bmp = b }
        }
    }

    val heightDp = widthDp * size.second / size.first
    Box(
        Modifier.width(widthDp.dp).height(heightDp.dp).background(Color.White),
        contentAlignment = Alignment.Center
    ) {
        val b = bmp
        if (b == null) CircularProgressIndicator()
        else Image(
            bitmap = b.asImageBitmap(),
            contentDescription = "Page ${index + 1}",
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize()
        )
    }
}

/** PdfRenderer ek samay me sirf ek page kholta hai, isliye mutex lagaya hai */
suspend fun renderPage(
    holder: PdfHolder, mutex: Mutex, index: Int,
    widthPx: Int, pageW: Int, pageH: Int
): Bitmap? = withContext(Dispatchers.IO) {
    mutex.withLock {
        try {
            val r = holder.renderer
            if (r == null) null
            else {
                val page = r.openPage(index)
                try {
                    val h = (widthPx.toLong() * pageH / pageW).toInt().coerceAtLeast(1)
                    val out = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888)
                    out.eraseColor(0xFFFFFFFF.toInt())
                    page.render(out, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    out
                } finally {
                    page.close()
                }
            }
        } catch (e: Exception) {
            null
        }
    }
}
