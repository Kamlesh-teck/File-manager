package com.example.filemanager

import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class TerminalActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val start = intent.getStringExtra("path")
            ?: Environment.getExternalStorageDirectory().absolutePath
        setContent { AppTheme { TerminalScreen(File(start)) { finish() } } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(start: File, onClose: () -> Unit) {
    var cwd by remember { mutableStateOf(start) }
    var output by remember {
        mutableStateOf("All-In-One Terminal\nCommands try karo: ls, pwd, cd, cat, df, ps, pm list packages\n\n")
    }
    var input by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var root by remember { mutableStateOf(false) }
    val history = remember { mutableStateListOf<String>() }
    var histIdx by remember { mutableIntStateOf(0) }
    val holder = remember { arrayOfNulls<Process>(1) }
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()

    fun append(t: String) { output = (output + t).takeLast(200_000) }

    LaunchedEffect(output.length) {
        withFrameNanos { }
        scroll.scrollTo(Int.MAX_VALUE)
    }

    DisposableEffect(Unit) {
        onDispose { holder[0]?.destroy() }
    }

    fun exec(cmd: String) {
        running = true
        val dir = cwd
        val useRoot = root
        scope.launch(Dispatchers.IO) {
            try {
                val args = if (useRoot) listOf("su", "-c", "cd '${dir.path}' && $cmd")
                else listOf("/system/bin/sh", "-c", cmd)
                val p = ProcessBuilder(args).directory(dir).redirectErrorStream(true).start()
                holder[0] = p
                p.outputStream.close()
                val reader = p.inputStream.bufferedReader()
                val buf = CharArray(4096)
                val sb = StringBuilder()
                var last = 0L
                while (true) {
                    val n = reader.read(buf)
                    if (n < 0) break
                    sb.append(buf, 0, n)
                    val now = System.currentTimeMillis()
                    if (now - last > 100) {
                        val s = sb.toString()
                        sb.setLength(0)
                        last = now
                        withContext(Dispatchers.Main) { append(s) }
                    }
                }
                val code = p.waitFor()
                sb.append("[exit $code]\n")
                val rest = sb.toString()
                withContext(Dispatchers.Main) { append(rest) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { append("Error: ${e.message}\n") }
            } finally {
                holder[0] = null
                withContext(NonCancellable + Dispatchers.Main) { running = false }
            }
        }
    }

    fun run() {
        val cmd = input.trim()
        if (cmd.isEmpty() || running) return
        input = ""
        history.add(cmd)
        histIdx = history.size
        if (cmd == "clear") { output = ""; return }
        append("${cwd.path} $ $cmd\n")
        if (cmd == "cd" || cmd.startsWith("cd ")) {
            val arg = cmd.removePrefix("cd").trim().trim('"', '\'')
            val target = when {
                arg.isEmpty() || arg == "~" -> Environment.getExternalStorageDirectory()
                arg.startsWith("/") -> File(arg)
                else -> File(cwd, arg)
            }
            val c = try { target.canonicalFile } catch (e: Exception) { target }
            if (c.isDirectory) cwd = c else append("cd: $arg: folder nahi mila\n")
        } else {
            exec(cmd)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = appBarColors(),
                title = { Text("Terminal", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    Text("root", style = MaterialTheme.typography.labelMedium)
                    Switch(checked = root, onCheckedChange = { root = it },
                        modifier = Modifier.padding(horizontal = 4.dp))
                    IconButton(onClick = { holder[0]?.destroy() }, enabled = running) {
                        Icon(Icons.Default.Stop, "Stop")
                    }
                    IconButton(onClick = { output = "" }) {
                        Icon(Icons.Default.DeleteSweep, "Clear")
                    }
                }
            )
        },
        modifier = Modifier.imePadding()
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Box(
                Modifier.weight(1f).fillMaxWidth().background(Color(0xFF101010))
                    .verticalScroll(scroll).padding(8.dp)
            ) {
                SelectionContainer {
                    Text(
                        output,
                        color = Color(0xFFDDDDDD),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                }
            }
            Text(
                cwd.path + " $",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    placeholder = { Text("command likho") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { run() }),
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = {
                    if (history.isNotEmpty()) {
                        histIdx = (histIdx - 1).coerceAtLeast(0)
                        input = history[histIdx]
                    }
                }) { Icon(Icons.Default.KeyboardArrowUp, "Pichla") }
                IconButton(onClick = {
                    if (histIdx < history.lastIndex) { histIdx++; input = history[histIdx] }
                    else { histIdx = history.size; input = "" }
                }) { Icon(Icons.Default.KeyboardArrowDown, "Agla") }
                IconButton(onClick = { run() }, enabled = !running) {
                    Icon(Icons.Default.PlayArrow, "Run")
                }
            }
        }
    }
}
