package com.example.filemanager

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Search screen se "Show in folder" dabane par main screen ko batata hai ki kaunsa folder kholna hai */
object ShowRequest {
    var dir: File? = null
}

private const val MAX_RESULTS = 3000
private const val MAX_TEXT_BYTES = 5L * 1024 * 1024   // isse badi file ke andar nahi dhundte
private const val MAX_PER_FILE = 5                     // ek file se zyada se zyada itne match dikhte hain

/** In type ki files ke andar text nahi hota, isliye kholte hi nahi */
private val BINARY_EXTS = setOf(
    "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "mp4", "mkv", "3gp", "webm", "avi", "mov",
    "m4v", "mp3", "m4a", "wav", "ogg", "flac", "aac", "opus", "amr", "zip", "rar", "7z", "gz", "xz",
    "bz2", "jar", "apk", "pdf", "so", "dex", "odex", "db", "class", "bin", "iso", "exe", "dll",
    "ttf", "otf", "woff", "woff2", "mo", "docx", "xlsx", "pptx"
)

/** Ek result: sirf file (line = 0) ya file ke andar ki ek line */
class SearchHit(val file: File, val line: Int = 0, val snippet: String = "")

class SearchOptions(
    val root: File,
    val query: String,
    val filter: FileFilter,
    val hidden: Boolean,
    val content: String = "",
    val caseSensitive: Boolean = false
)

private fun isLink(f: File): Boolean =
    try { Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && Files.isSymbolicLink(f.toPath()) }
    catch (e: Exception) { false }

private fun snippetOf(line: String, needle: String, ignoreCase: Boolean): String {
    val i = line.indexOf(needle, ignoreCase = ignoreCase).coerceAtLeast(0)
    val start = maxOf(0, i - 30)
    val end = minOf(line.length, i + needle.length + 70)
    return (if (start > 0) "…" else "") + line.substring(start, end).trim() +
            (if (end < line.length) "…" else "")
}

/** File ke andar line-line dhundta hai. Binary ya bahut badi file ko chhod deta hai. */
private fun findInFile(f: File, needle: String, ignoreCase: Boolean, maxHits: Int): List<SearchHit> {
    val len = f.length()
    if (len == 0L || len > MAX_TEXT_BYTES) return emptyList()
    val hits = ArrayList<SearchHit>()
    try {
        f.inputStream().buffered().use { ins ->
            ins.mark(2048)
            val head = ByteArray(2048)
            val n = ins.read(head)
            ins.reset()
            for (i in 0 until maxOf(n, 0)) {
                if (head[i] == 0.toByte()) return emptyList()   // NUL mila: binary file hai
            }
            val reader = ins.reader(Charsets.UTF_8).buffered()
            var lineNo = 0
            while (true) {
                val line = reader.readLine() ?: break
                lineNo++
                if (line.contains(needle, ignoreCase)) {
                    hits.add(SearchHit(f, lineNo, snippetOf(line, needle, ignoreCase)))
                    if (hits.size >= maxHits) break
                }
            }
        }
    } catch (e: Exception) {
    }
    return hits
}

/**
 * Folder ke andar saare subfolders me dhundta hai (naam se, aur chaho to file ke andar ke text se).
 * Mile hue results batch me onBatch ko deta hai: (results, dekhe gaye folder, padhi gayi files).
 * Limit (3000) tak pahunchne par true deta hai.
 */
suspend fun runSearch(
    opt: SearchOptions,
    onBatch: suspend (List<SearchHit>, Int, Int) -> Unit
): Boolean = withContext(Dispatchers.IO) {
    val q = opt.query.trim()
    val needle = opt.content
    val contentMode = needle.isNotEmpty()

    // * aur ? wale pattern (jaise *.pdf) ke liye regex, warna seedha "naam me ye likha ho"
    val rx: Regex? = if (q.contains('*') || q.contains('?')) {
        Regex(
            buildString {
                for (c in q) {
                    when (c) {
                        '*' -> append(".*")
                        '?' -> append('.')
                        else -> append(Regex.escape(c.toString()))
                    }
                }
            },
            RegexOption.IGNORE_CASE
        )
    } else null

    fun nameMatches(name: String): Boolean = when {
        q.isEmpty() -> true
        rx != null -> rx.matches(name)
        else -> name.contains(q, ignoreCase = true)
    }

    val stack = ArrayDeque<File>()
    stack.add(opt.root)
    val batch = ArrayList<SearchHit>()
    var scanned = 0
    var read = 0
    var total = 0
    var last = System.currentTimeMillis()
    var capped = false

    while (stack.isNotEmpty()) {
        ensureActive()
        val dir = stack.removeLast()
        val children = dir.listFiles() ?: continue
        scanned++
        for (f in children) {
            val name = f.name
            if (!opt.hidden && name.startsWith(".")) continue
            if (name.startsWith(".AllInOneFM")) continue
            val isDir = f.isDirectory

            if (contentMode) {
                // file ke andar ka text dhundna: sirf files, folder nahi
                val typeOk = when (opt.filter) {
                    FileFilter.ALL -> true
                    FileFilter.FOLDERS -> false
                    else -> matchesFilter(f, opt.filter)
                }
                if (!isDir && typeOk && nameMatches(name) && f.extension.lowercase() !in BINARY_EXTS) {
                    ensureActive()
                    read++
                    for (h in findInFile(f, needle, !opt.caseSensitive, MAX_PER_FILE)) {
                        batch.add(h)
                        total++
                        if (total >= MAX_RESULTS) { capped = true; break }
                    }
                }
            } else {
                val typeOk = when (opt.filter) {
                    FileFilter.ALL -> true
                    FileFilter.FOLDERS -> isDir
                    else -> !isDir && matchesFilter(f, opt.filter)
                }
                if (typeOk && nameMatches(name)) {
                    batch.add(SearchHit(f))
                    total++
                    if (total >= MAX_RESULTS) capped = true
                }
            }
            if (capped) break
            if (isDir && !isLink(f)) stack.add(f)
        }
        val now = System.currentTimeMillis()
        if (batch.isNotEmpty() && (batch.size >= 100 || now - last > 200)) {
            onBatch(batch.toList(), scanned, read)
            batch.clear()
            last = now
        }
        if (capped) break
    }
    onBatch(batch.toList(), scanned, read)
    capped
}

class SearchActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra("path") ?: "/storage/emulated/0"
        setContent { AppTheme { SearchScreen(File(path)) { finish() } } }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SearchScreen(startDir: File, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val snackbar = remember { SnackbarHostState() }
    val focus = remember { FocusRequester() }
    val fmt = remember { SimpleDateFormat("yy-MM-dd HH:mm", Locale.getDefault()) }

    val scopes = remember(startDir) {
        val l = mutableListOf(("Is folder: " + startDir.name.ifEmpty { "/" }) to startDir)
        locationList(context)
            .filter { it.first.startsWith("Internal") || it.first.startsWith("SD card") }
            .forEach { l.add(it.first to it.second) }
        l.toList()
    }

    var query by remember { mutableStateOf("") }
    var contentQuery by remember { mutableStateOf("") }
    var caseSensitive by remember { mutableStateOf(false) }
    var scopeIdx by remember { mutableIntStateOf(0) }
    var filter by remember { mutableStateOf(FileFilter.ALL) }
    var hidden by remember { mutableStateOf(Prefs.showHidden) }
    val results = remember { mutableStateListOf<SearchHit>() }
    var running by remember { mutableStateOf(false) }
    var scanned by remember { mutableIntStateOf(0) }
    var filesRead by remember { mutableIntStateOf(0) }
    var capped by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var searchedContent by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }
    var picked by remember { mutableStateOf<SearchHit?>(null) }
    val runCounter = remember { intArrayOf(0) }

    fun start() {
        focusManager.clearFocus()
        job?.cancel()
        results.clear()
        scanned = 0
        filesRead = 0
        capped = false
        searched = true
        searchedContent = contentQuery
        running = true
        runCounter[0]++
        val id = runCounter[0]
        val opt = SearchOptions(
            scopes[scopeIdx].second, query, filter, hidden, contentQuery, caseSensitive
        )
        job = scope.launch {
            try {
                capped = runSearch(opt) { batch, n, r ->
                    withContext(Dispatchers.Main) {
                        results.addAll(batch)
                        scanned = n
                        filesRead = r
                    }
                }
            } finally {
                if (id == runCounter[0]) running = false
            }
        }
    }

    fun openResult(h: SearchHit) {
        val f = h.file
        when {
            f.isDirectory -> { ShowRequest.dir = f; onClose() }
            h.line > 0 -> context.startActivity(
                Intent(context, EditorActivity::class.java)
                    .putExtra("path", f.absolutePath)
                    .putExtra("line", h.line)
                    .putExtra("find", searchedContent)
            )
            else -> openFile(context, f)
        }
    }

    DisposableEffect(Unit) { onDispose { job?.cancel() } }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Search", fontSize = 17.sp) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = appBarColors()
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("File ka naam (jaise *.pdf ya photo)") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { start() }),
                    modifier = Modifier.weight(1f).focusRequester(focus)
                )
                Spacer(Modifier.width(6.dp))
                if (running) OutlinedButton(onClick = { job?.cancel(); running = false }) { Text("Stop") }
                else Button(onClick = { start() }) { Text("Search") }
            }
            OutlinedTextField(
                value = contentQuery,
                onValueChange = { contentQuery = it },
                singleLine = true,
                placeholder = { Text("File ke andar ka text (optional)") },
                leadingIcon = { Icon(Icons.Default.Description, null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { start() }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
            )

            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                scopes.forEachIndexed { i, (name, _) ->
                    FilterChip(selected = scopeIdx == i, onClick = { scopeIdx = i }, label = { Text(name) })
                }
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FileFilter.values().forEach { f ->
                    FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) })
                }
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = hidden, onCheckedChange = { hidden = it })
                Text("Hidden files bhi", fontSize = 13.sp)
                Spacer(Modifier.width(12.dp))
                Checkbox(checked = caseSensitive, onCheckedChange = { caseSensitive = it })
                Text("Capital/small ka farak (text me)", fontSize = 13.sp)
            }

            if (searched) {
                Text(
                    when {
                        running -> "Dhundh raha hoon...  ${results.size} mile, $scanned folder dekhe" +
                                if (searchedContent.isNotEmpty()) ", $filesRead files padhi" else ""
                        capped -> "${results.size} mile (limit: pehle $MAX_RESULTS dikhaye), $scanned folder dekhe"
                        else -> "${results.size} mile, $scanned folder dekhe" +
                                if (searchedContent.isNotEmpty()) ", $filesRead files padhi" else ""
                    },
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
                if (running) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(results, key = { it.file.absolutePath + "#" + it.line }) { h ->
                    val f = h.file
                    Row(
                        Modifier.fillMaxWidth()
                            .combinedClickable(onClick = { openResult(h) }, onLongClick = { picked = h })
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FileIcon(f, 30.dp)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(f.name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                f.parent ?: "", fontSize = 10.sp, maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (h.line > 0) {
                                Text(
                                    "L${h.line}:  ${h.snippet}", fontSize = 11.sp, maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            } else {
                                Text(
                                    if (f.isFile) humanSize(f.length()) + "   " + fmt.format(Date(f.lastModified()))
                                    else "Folder",
                                    fontSize = 10.sp, color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    picked?.let { h ->
        val f = h.file
        AlertDialog(
            onDismissRequest = { picked = null },
            title = { Text(f.name, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    ActionRow(
                        Icons.Default.PlayArrow,
                        if (f.isDirectory) "Folder kholo" else if (h.line > 0) "Line ${h.line} par kholo" else "Kholo"
                    ) {
                        picked = null
                        openResult(h)
                    }
                    ActionRow(Icons.Default.Folder, "Folder me dikhao (Show in folder)") {
                        picked = null
                        ShowRequest.dir = f.parentFile
                        onClose()
                    }
                    ActionRow(Icons.Default.ContentCopy, "Path copy karo") {
                        picked = null
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("path", f.absolutePath))
                        scope.launch { snackbar.showSnackbar("Path copy ho gaya") }
                    }
                }
            },
            confirmButton = { TextButton({ picked = null }) { Text("Close") } }
        )
    }
}
