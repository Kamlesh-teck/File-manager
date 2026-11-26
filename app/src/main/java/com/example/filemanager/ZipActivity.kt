package com.example.filemanager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

val ZIP_EXTS = setOf("zip", "jar", "apk")

// ---------------- Zip banana / extract (helper functions) ----------------

fun uniqueFile(dir: File, name: String): File {
    var t = File(dir, name)
    var i = 1
    val base = name.substringBeforeLast('.', name)
    val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
    while (t.exists()) { t = File(dir, "$base ($i)$ext"); i++ }
    return t
}

fun uniqueDir(parent: File, name: String): File {
    var t = File(parent, name)
    var i = 1
    while (t.exists()) { t = File(parent, "$name ($i)"); i++ }
    return t
}

fun createZip(items: List<File>, out: File) {
    ZipOutputStream(BufferedOutputStream(FileOutputStream(out))).use { zos ->
        fun add(f: File, base: String) {
            if (f.absolutePath == out.absolutePath) return
            val name = base + f.name
            if (f.isDirectory) {
                zos.putNextEntry(ZipEntry("$name/"))
                zos.closeEntry()
                f.listFiles()?.forEach { add(it, "$name/") }
            } else {
                val e = ZipEntry(name)
                e.time = f.lastModified()
                zos.putNextEntry(e)
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        items.forEach { add(it, "") }
    }
}

/** Selected files/folders ka zip, usi folder me banata hai */
suspend fun zipSelectedItems(dir: File, items: List<File>): Result<File> =
    withContext(Dispatchers.IO) {
        runCatching {
            val base = if (items.size == 1) items[0].nameWithoutExtension.ifEmpty { items[0].name }
            else "archive"
            val out = uniqueFile(dir, "$base.zip")
            try {
                createZip(items, out)
            } catch (e: Exception) {
                out.delete()
                throw e
            }
            out
        }
    }

/** prefixes = null to sab kuch, warna sirf chuni hui files/folders. Zip Slip se bachav shamil hai. */
fun extractZip(zipFile: File, dest: File, prefixes: Set<String>?): Int {
    var count = 0
    val destCanon = dest.canonicalPath
    ZipFile(zipFile).use { zip ->
        val en = zip.entries()
        while (en.hasMoreElements()) {
            val e = en.nextElement()
            if (prefixes != null && prefixes.none {
                    if (it.endsWith("/")) e.name.startsWith(it) else e.name == it
                }) continue
            val out = File(dest, e.name)
            val canon = out.canonicalPath
            if (canon != destCanon && !canon.startsWith(destCanon + File.separator)) continue
            if (e.isDirectory) { out.mkdirs(); continue }
            out.parentFile?.mkdirs()
            zip.getInputStream(e).use { input ->
                out.outputStream().use { input.copyTo(it) }
            }
            if (e.time > 0) out.setLastModified(e.time)
            count++
        }
    }
    return count
}

// ---------------- Zip browser ----------------

data class RawEntry(val name: String, val isDir: Boolean, val size: Long, val time: Long)
data class ZipItem(val name: String, val path: String, val isDir: Boolean, val size: Long, val time: Long)

fun listLevel(entries: List<RawEntry>, prefix: String): List<ZipItem> {
    val dirs = LinkedHashMap<String, ZipItem>()
    val files = mutableListOf<ZipItem>()
    for (en in entries) {
        if (!en.name.startsWith(prefix) || en.name == prefix) continue
        val rest = en.name.substring(prefix.length)
        val slash = rest.indexOf('/')
        if (slash >= 0) {
            val dn = rest.substring(0, slash)
            dirs.getOrPut(dn) { ZipItem(dn, prefix + dn + "/", true, 0, en.time) }
        } else if (!en.isDir) {
            files.add(ZipItem(rest, en.name, false, en.size, en.time))
        }
    }
    return dirs.values.sortedBy { it.name.lowercase() } + files.sortedBy { it.name.lowercase() }
}

class ZipActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra("path")
        if (path == null) { finish(); return }
        setContent { MaterialTheme { ZipScreen(File(path)) { finish() } } }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ZipScreen(file: File, onClose: () -> Unit) {
    val context = LocalContext.current
    var entries by remember { mutableStateOf<List<RawEntry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var prefix by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var busy by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val fmt = remember { SimpleDateFormat("yy-MM-dd HH:mm", Locale.getDefault()) }

    LaunchedEffect(file) {
        withContext(Dispatchers.IO) {
            try {
                ZipFile(file).use { z ->
                    entries = Collections.list(z.entries()).map {
                        RawEntry(it.name, it.isDirectory, it.size, it.time)
                    }
                }
            } catch (e: Exception) {
                error = "Zip khul nahi payi: ${e.message}"
            }
        }
    }

    val items = remember(entries, prefix) {
        entries?.let { listLevel(it, prefix) } ?: emptyList()
    }

    fun parentPrefix(p: String): String {
        val up = p.trimEnd('/').substringBeforeLast('/', "")
        return if (up.isEmpty()) "" else "$up/"
    }

    BackHandler(enabled = prefix.isNotEmpty() || selected.isNotEmpty()) {
        if (selected.isNotEmpty()) selected = emptySet() else prefix = parentPrefix(prefix)
    }

    fun extract() {
        if (busy) return
        busy = true
        val sel = selected
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val dest = uniqueDir(file.parentFile ?: file, file.nameWithoutExtension)
                    dest.mkdirs()
                    val n = extractZip(file, dest, if (sel.isEmpty()) null else sel)
                    dest to n
                }
            }
            busy = false
            selected = emptySet()
            val msg = r.fold(
                { "${it.second} file extract hui: ${it.first.name}" },
                { "Extract nahi hua: ${it.message}" }
            )
            snackbar.showSnackbar(msg)
        }
    }

    fun openEntry(item: ZipItem) {
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val out = File(context.cacheDir, "zipview/${item.path.hashCode()}/${item.name}")
                    out.parentFile?.mkdirs()
                    ZipFile(file).use { z ->
                        val ze = z.getEntry(item.path)
                        z.getInputStream(ze).use { i -> out.outputStream().use { i.copyTo(it) } }
                    }
                    out
                }
            }
            val f = r.getOrNull()
            if (f != null) openFile(context, f)
            else snackbar.showSnackbar("File khul nahi payi (password wali zip support nahi)")
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium)
                        Text(
                            "/" + prefix + "   " +
                                    if (selected.isEmpty()) "${items.size} items"
                                    else "${selected.size} selected",
                            maxLines = 1, style = MaterialTheme.typography.bodySmall
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (prefix.isNotEmpty()) prefix = parentPrefix(prefix) else onClose()
                    }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(onClick = { extract() }, enabled = entries != null && !busy) {
                        Icon(Icons.Default.Unarchive, "Extract")
                    }
                }
            )
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when {
                error != null -> Text(error ?: "", modifier = Modifier.align(Alignment.Center).padding(24.dp))
                entries == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(items, key = { it.path }) { item ->
                        val isSel = item.path in selected
                        ListItem(
                            headlineContent = { Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                val sz = if (item.isDir) "Folder" else humanSize(item.size)
                                val tm = if (item.time > 0) "  " + fmt.format(Date(item.time)) else ""
                                Text(sz + tm, style = MaterialTheme.typography.bodySmall)
                            },
                            leadingContent = {
                                Icon(if (item.isDir) Icons.Default.Folder else Icons.Default.InsertDriveFile, null)
                            },
                            colors = ListItemDefaults.colors(
                                containerColor = if (isSel) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surface
                            ),
                            modifier = Modifier.combinedClickable(
                                onClick = {
                                    if (selected.isNotEmpty()) {
                                        selected = if (isSel) selected - item.path else selected + item.path
                                    } else if (item.isDir) prefix = item.path
                                    else openEntry(item)
                                },
                                onLongClick = {
                                    selected = if (isSel) selected - item.path else selected + item.path
                                }
                            )
                        )
                    }
                }
            }
            if (busy) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}
