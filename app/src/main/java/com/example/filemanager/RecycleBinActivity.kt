package com.example.filemanager

import android.content.Context
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ======================= Recycle Bin ka logic =======================
// Har storage (internal, SD card) me ".AllInOneFM_trash" folder banta hai.
// Har deleted item: <trash>/<id>/<naam>  aur  <trash>/<id>.info (asli path + samay)

const val TRASH_DIR = ".AllInOneFM_trash"

fun volumeRootOf(f: File): File {
    val p = f.absolutePath
    return when {
        p.startsWith("/storage/emulated/") -> File(p.split("/").take(4).joinToString("/"))
        p.startsWith("/storage/") -> File(p.split("/").take(3).joinToString("/"))
        else -> Environment.getExternalStorageDirectory()
    }
}

fun isInTrash(f: File): Boolean {
    val p = f.absolutePath
    return p.contains("/$TRASH_DIR/") || p.endsWith("/$TRASH_DIR")
}

/** File/folder ko Recycle Bin me bhejta hai (usi storage me, to turant hota hai) */
fun moveToTrash(f: File): Result<Unit> = runCatching {
    if (isInTrash(f)) {
        if (!f.deleteRecursively()) error("Delete nahi hua")
        return@runCatching
    }
    val trash = File(volumeRootOf(f), TRASH_DIR)
    trash.mkdirs()
    File(trash, ".nomedia").createNewFile()
    val base = "${System.currentTimeMillis()}_${kotlin.math.abs(f.absolutePath.hashCode())}"
    var id = base
    var n = 1
    while (File(trash, id).exists()) { id = "${base}_$n"; n++ }
    val holder = File(trash, id)
    holder.mkdirs()
    val target = File(holder, f.name)
    if (!f.renameTo(target)) {
        f.copyRecursively(target, overwrite = false)
        if (!f.deleteRecursively()) error("Original delete nahi hua")
    }
    File(trash, "$id.info").writeText(f.absolutePath + "\n" + System.currentTimeMillis())
}

fun storageRoots(context: Context): List<File> {
    val roots = linkedMapOf<String, File>()
    fun add(f: File) { roots[f.absolutePath] = f }
    add(Environment.getExternalStorageDirectory())
    context.getExternalFilesDirs(null).forEach { d ->
        val p = d?.absolutePath ?: return@forEach
        val i = p.indexOf("/Android/data")
        if (i > 0) add(File(p.substring(0, i)))
    }
    File("/storage").listFiles()?.forEach {
        if (it.isDirectory && it.name != "emulated" && it.name != "self") add(it)
    }
    return roots.values.toList()
}

data class TrashItem(
    val id: String,
    val trashDir: File,
    val holder: File,
    val file: File,
    val originalPath: String,
    val deletedAt: Long,
    val size: Long
)

fun listTrash(roots: List<File>): List<TrashItem> {
    val out = mutableListOf<TrashItem>()
    for (root in roots) {
        val trash = File(root, TRASH_DIR)
        val infos = trash.listFiles { f -> f.name.endsWith(".info") } ?: continue
        for (info in infos) {
            val id = info.name.removeSuffix(".info")
            val holder = File(trash, id)
            val file = holder.listFiles()?.firstOrNull() ?: continue
            val lines = try { info.readLines() } catch (e: Exception) { emptyList() }
            val orig = lines.getOrNull(0) ?: continue
            val at = lines.getOrNull(1)?.toLongOrNull() ?: info.lastModified()
            val size = if (file.isFile) file.length()
            else file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            out.add(TrashItem(id, trash, holder, file, orig, at, size))
        }
    }
    return out.sortedByDescending { it.deletedAt }
}

fun restoreTrash(item: TrashItem): Result<File> = runCatching {
    val orig = File(item.originalPath)
    val parent = orig.parentFile ?: error("Asli folder nahi mila")
    parent.mkdirs()
    var dest = orig
    if (dest.exists()) {
        dest = if (item.file.isDirectory) uniqueDir(parent, orig.name) else uniqueFile(parent, orig.name)
    }
    if (!item.file.renameTo(dest)) {
        item.file.copyRecursively(dest, overwrite = false)
        item.file.deleteRecursively()
    }
    item.holder.deleteRecursively()
    File(item.trashDir, item.id + ".info").delete()
    dest
}

fun deleteForever(item: TrashItem) {
    item.holder.deleteRecursively()
    File(item.trashDir, item.id + ".info").delete()
}

// ======================= Screen =======================

class RecycleBinActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppTheme { RecycleBinScreen { finish() } } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecycleBinScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var items by remember { mutableStateOf<List<TrashItem>?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<TrashItem?>(null) }
    var confirmEmpty by remember { mutableStateOf(false) }
    val fmt = remember { SimpleDateFormat("yy-MM-dd HH:mm", Locale.getDefault()) }

    LaunchedEffect(tick) {
        items = withContext(Dispatchers.IO) { listTrash(storageRoots(context)) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                colors = appBarColors(),
                title = {
                    Text(
                        if (items == null) "Recycle Bin" else "Recycle Bin (${items?.size ?: 0})",
                        fontSize = 17.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { confirmEmpty = true },
                        enabled = !items.isNullOrEmpty()
                    ) { Icon(Icons.Default.DeleteSweep, "Empty bin") }
                }
            )
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            val list = items
            when {
                list == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                list.isEmpty() -> Text(
                    "Recycle Bin khaali hai", modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(list, key = { it.holder.absolutePath }) { item ->
                        Row(
                            Modifier.fillMaxWidth().clickable { selected = item }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FileIcon(item.file, 34.dp)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(
                                    item.file.name, fontSize = 14.sp, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    item.originalPath, fontSize = 10.sp, maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    fmt.format(Date(item.deletedAt)) + "   " + humanSize(item.size),
                                    fontSize = 10.sp, color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    selected?.let { item ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(item.file.name, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    ActionRow(Icons.Default.Restore, "Restore (wapas rakho)") {
                        selected = null
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { restoreTrash(item) }
                            snackbar.showSnackbar(
                                r.fold(
                                    { "Wapas aa gayi: ${it.absolutePath}" },
                                    { "Restore nahi hua: ${it.message}" }
                                )
                            )
                            tick++
                        }
                    }
                    ActionRow(Icons.Default.DeleteForever, "Hamesha ke liye delete") {
                        selected = null
                        scope.launch {
                            withContext(Dispatchers.IO) { deleteForever(item) }
                            snackbar.showSnackbar("Hamesha ke liye delete ho gaya")
                            tick++
                        }
                    }
                }
            },
            confirmButton = { TextButton({ selected = null }) { Text("Close") } }
        )
    }

    if (confirmEmpty) {
        AlertDialog(
            onDismissRequest = { confirmEmpty = false },
            title = { Text("Recycle Bin khaali karein?") },
            text = { Text("Saari files hamesha ke liye delete ho jayengi.") },
            confirmButton = {
                TextButton({
                    confirmEmpty = false
                    val all = items ?: emptyList()
                    scope.launch {
                        withContext(Dispatchers.IO) { all.forEach { deleteForever(it) } }
                        snackbar.showSnackbar("Recycle Bin khaali ho gaya")
                        tick++
                    }
                }) { Text("Khaali karo") }
            },
            dismissButton = { TextButton({ confirmEmpty = false }) { Text("Cancel") } }
        )
    }
}
