package com.example.filemanager

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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

class SearchOptions(
    val root: File,
    val query: String,
    val filter: FileFilter,
    val hidden: Boolean
)

private fun isLink(f: File): Boolean =
    try { Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && Files.isSymbolicLink(f.toPath()) }
    catch (e: Exception) { false }

/**
 * Folder ke andar saare subfolders me dhundta hai. Mile hue results batch me onBatch ko deta hai.
 * Limit (3000) tak pahunchne par true deta hai.
 */
suspend fun runSearch(
    opt: SearchOptions,
    onBatch: suspend (List<File>, Int) -> Unit
): Boolean = withContext(Dispatchers.IO) {
    val q = opt.query.trim()
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
    val batch = ArrayList<File>()
    var scanned = 0
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
            val typeOk = when (opt.filter) {
                FileFilter.ALL -> true
                FileFilter.FOLDERS -> isDir
                else -> !isDir && matchesFilter(f, opt.filter)
            }
            if (typeOk && nameMatches(name)) {
                batch.add(f)
                total++
                if (total >= MAX_RESULTS) { capped = true; break }
            }
            if (isDir && !isLink(f)) stack.add(f)
        }
        val now = System.currentTimeMillis()
        if (batch.isNotEmpty() && (batch.size >= 100 || now - last > 200)) {
            onBatch(batch.toList(), scanned)
            batch.clear()
            last = now
        }
        if (capped) break
    }
    onBatch(batch.toList(), scanned)
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
    var scopeIdx by remember { mutableIntStateOf(0) }
    var filter by remember { mutableStateOf(FileFilter.ALL) }
    var hidden by remember { mutableStateOf(Prefs.showHidden) }
    val results = remember { mutableStateListOf<File>() }
    var running by remember { mutableStateOf(false) }
    var scanned by remember { mutableIntStateOf(0) }
    var capped by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var picked by remember { mutableStateOf<File?>(null) }
    val runCounter = remember { intArrayOf(0) }

    fun start() {
        focusManager.clearFocus()
        job?.cancel()
        results.clear()
        scanned = 0
        capped = false
        searched = true
        running = true
        runCounter[0]++
        val id = runCounter[0]
        val opt = SearchOptions(scopes[scopeIdx].second, query, filter, hidden)
        job = scope.launch {
            try {
                capped = runSearch(opt) { batch, n ->
                    withContext(Dispatchers.Main) {
                        results.addAll(batch)
                        scanned = n
                    }
                }
            } finally {
                if (id == runCounter[0]) running = false
            }
        }
    }

    fun openResult(f: File) {
        if (f.isDirectory) {
            ShowRequest.dir = f
            onClose()
        } else openFile(context, f)
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
                    placeholder = { Text("Naam (jaise *.pdf ya photo)") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { start() }),
                    modifier = Modifier.weight(1f).focusRequester(focus)
                )
                Spacer(Modifier.width(6.dp))
                if (running) OutlinedButton(onClick = { job?.cancel(); running = false }) { Text("Stop") }
                else Button(onClick = { start() }) { Text("Search") }
            }

            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
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
                Modifier.padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = hidden, onCheckedChange = { hidden = it })
                Text("Hidden files bhi dhundo", fontSize = 13.sp)
            }

            if (searched) {
                Text(
                    when {
                        running -> "Dhundh raha hoon...  ${results.size} mile, $scanned folder dekhe"
                        capped -> "${results.size} mile (limit: pehle $MAX_RESULTS dikhaye), $scanned folder dekhe"
                        else -> "${results.size} mile, $scanned folder dekhe"
                    },
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
                if (running) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(results, key = { it.absolutePath }) { f ->
                    Row(
                        Modifier.fillMaxWidth()
                            .combinedClickable(onClick = { openResult(f) }, onLongClick = { picked = f })
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

    picked?.let { f ->
        AlertDialog(
            onDismissRequest = { picked = null },
            title = { Text(f.name, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    ActionRow(Icons.Default.PlayArrow, if (f.isDirectory) "Folder kholo" else "Kholo") {
                        picked = null
                        openResult(f)
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
