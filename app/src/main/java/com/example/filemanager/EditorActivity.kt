package com.example.filemanager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class EditorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.load(this)
        val path = intent.getStringExtra("path")
        if (path == null) { finish(); return }
        setContent {
            AppTheme {
                EditorScreen(
                    File(path),
                    startLine = intent.getIntExtra("line", 0),
                    startFind = intent.getStringExtra("find") ?: "",
                    onClose = { finish() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(file: File, startLine: Int = 0, startFind: String = "", onClose: () -> Unit) {
    var tfv by remember { mutableStateOf(TextFieldValue("")) }
    var original by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>("Khul raha hai...") }
    var loaded by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(startFind.isNotEmpty()) }
    var query by remember { mutableStateOf(startFind) }
    var pendingJump by remember { mutableIntStateOf(startLine) }
    var replacement by remember { mutableStateOf("") }
    var confirmExit by remember { mutableStateOf(false) }

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()
    val context = LocalContext.current
    val lineHeightPx = with(LocalDensity.current) { (Prefs.editorSize * 1.45f).sp.toPx() }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    val modified = loaded && tfv.text != original

    LaunchedEffect(file) {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                if (file.length() > 2_000_000) error("File bahut badi hai (2 MB se zyada)")
                val bytes = file.readBytes()
                if (bytes.take(8000).any { it == 0.toByte() })
                    error("Ye binary file hai, text me nahi khul sakti")
                String(bytes, Charsets.UTF_8)
            }
        }
        result.onSuccess {
            original = it; loaded = true; status = null
            tfv = TextFieldValue(it, TextRange(lineStartOffset(it, startLine)))
        }.onFailure { status = it.message ?: "File khul nahi payi" }
    }

    // Search se kisi line par kholne par wahan tak scroll karo
    LaunchedEffect(layout, loaded) {
        val l = layout
        if (pendingJump > 0 && loaded && l != null && l.layoutInput.text.length == tfv.text.length) {
            val off = tfv.selection.start.coerceIn(0, l.layoutInput.text.length)
            val y = l.getLineTop(l.getLineForOffset(off))
            vScroll.scrollTo(maxOf(0, (y - 120f).toInt()))
            pendingJump = 0
        }
    }

    fun save(thenExit: Boolean = false) {
        scope.launch {
            val text = tfv.text
            val ok = withContext(Dispatchers.IO) {
                runCatching { file.writeText(text) }.isSuccess
            }
            if (ok) original = text
            if (ok && thenExit) onClose()
            else snackbar.showSnackbar(if (ok) "Save ho gaya" else "Save nahi hua")
        }
    }

    fun findNext() {
        if (query.isEmpty()) return
        val t = tfv.text
        var i = t.indexOf(query, tfv.selection.end, ignoreCase = true)
        if (i < 0) i = t.indexOf(query, 0, ignoreCase = true)
        if (i < 0) {
            scope.launch { snackbar.showSnackbar("Nahi mila") }
            return
        }
        tfv = tfv.copy(selection = TextRange(i, i + query.length))
        val line = t.substring(0, i).count { it == '\n' }
        val y: Float = layout?.let { l ->
            l.getLineTop(l.getLineForOffset(minOf(i, l.layoutInput.text.length)))
        } ?: (line * lineHeightPx)
        scope.launch { vScroll.animateScrollTo(maxOf(0, (y - 200f).toInt())) }
    }

    fun requestClose() { if (modified) confirmExit = true else onClose() }

    BackHandler(enabled = modified) { confirmExit = true }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                colors = appBarColors(),
                title = {
                    Text(file.name + if (modified) " *" else "", maxLines = 1,
                        style = MaterialTheme.typography.titleMedium)
                },
                navigationIcon = {
                    IconButton(onClick = { requestClose() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showSearch = !showSearch }, enabled = loaded) {
                        Icon(Icons.Default.Search, "Find")
                    }
                    IconButton(onClick = { save() }, enabled = modified) {
                        Icon(Icons.Default.Save, "Save")
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, "Menu")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Word wrap") },
                                trailingIcon = { Checkbox(checked = Prefs.editorWrap, onCheckedChange = null) },
                                onClick = { Prefs.editorWrap = !Prefs.editorWrap; Prefs.save(context) }
                            )
                            DropdownMenuItem(
                                text = { Text("Line numbers") },
                                trailingIcon = { Checkbox(checked = Prefs.editorLineNumbers, onCheckedChange = null) },
                                onClick = { Prefs.editorLineNumbers = !Prefs.editorLineNumbers; Prefs.save(context) }
                            )
                            DropdownMenuItem(
                                text = { Text("Syntax colors") },
                                trailingIcon = { Checkbox(checked = Prefs.editorHighlight, onCheckedChange = null) },
                                onClick = { Prefs.editorHighlight = !Prefs.editorHighlight; Prefs.save(context) }
                            )
                            DropdownMenuItem(
                                text = { Text("Text bada (+)   ${Prefs.editorSize} sp") },
                                onClick = {
                                    Prefs.editorSize = (Prefs.editorSize + 2).coerceAtMost(32)
                                    Prefs.save(context)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Text chhota (-)") },
                                onClick = {
                                    Prefs.editorSize = (Prefs.editorSize - 2).coerceAtLeast(8)
                                    Prefs.save(context)
                                }
                            )
                        }
                    }
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (status != null) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(status ?: "")
                }
            } else {
                if (showSearch) {
                    Column(Modifier.fillMaxWidth().padding(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                query, { query = it }, singleLine = true,
                                label = { Text("Dhundo") }, modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = { findNext() }) { Text("Next") }
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                replacement, { replacement = it }, singleLine = true,
                                label = { Text("Replace") }, modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = {
                                if (query.isNotEmpty())
                                    tfv = TextFieldValue(
                                        tfv.text.replace(query, replacement, ignoreCase = true)
                                    )
                            }) { Text("All") }
                        }
                    }
                }

                val ext = file.extension.lowercase()
                val transform = remember(ext, Prefs.editorHighlight) {
                    if (Prefs.editorHighlight) SyntaxTransformation(ext) else VisualTransformation.None
                }
                val wrap = Prefs.editorWrap
                val showNumbers = Prefs.editorLineNumbers && !wrap
                val numbers = remember(tfv.text, showNumbers) {
                    if (showNumbers) (1..(tfv.text.count { it == '\n' } + 1)).joinToString("\n") else ""
                }
                val size = Prefs.editorSize
                val style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = size.sp,
                    lineHeight = (size * 1.45f).sp,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Row(Modifier.fillMaxSize().verticalScroll(vScroll)) {
                    if (showNumbers) {
                        Text(
                            numbers,
                            style = style.copy(color = Color.Gray, textAlign = TextAlign.End),
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(horizontal = 6.dp)
                        )
                    }
                    if (wrap) {
                        // wrap: field ki chaudai screen tak seemit, isliye lambi line neeche tut jati hai
                        BasicTextField(
                            value = tfv,
                            onValueChange = { tfv = it },
                            textStyle = style,
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            visualTransformation = transform,
                            onTextLayout = { layout = it },
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 400.dp)
                                .padding(horizontal = 8.dp)
                        )
                    } else {
                        // no wrap: side me scroll
                        Box(Modifier.horizontalScroll(hScroll)) {
                            BasicTextField(
                                value = tfv,
                                onValueChange = { tfv = it },
                                textStyle = style,
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                visualTransformation = transform,
                                onTextLayout = { layout = it },
                                modifier = Modifier
                                    .defaultMinSize(minWidth = 300.dp, minHeight = 400.dp)
                                    .padding(start = 8.dp, end = 48.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("Save karein?") },
            text = { Text("Badlav save nahi hue hain.") },
            confirmButton = { TextButton({ confirmExit = false; save(thenExit = true) }) { Text("Save") } },
            dismissButton = { TextButton({ confirmExit = false; onClose() }) { Text("Discard") } }
        )
    }
}

// ---------- Syntax highlighting (simple) ----------

class SyntaxTransformation(private val ext: String) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText =
        TransformedText(highlight(text.text, ext), OffsetMapping.Identity)
}

private val CODE_EXTS = setOf(
    "kt", "kts", "java", "js", "ts", "jsx", "tsx", "c", "cpp", "h", "hpp",
    "cs", "go", "rs", "swift", "gradle", "dart", "php", "smali", "json"
)
private val HASH_EXTS = setOf("py", "sh", "yml", "yaml", "properties", "toml", "rb", "conf")
private val MARKUP_EXTS = setOf("xml", "html", "htm", "svg")

private val KEYWORDS = setOf(
    "fun", "val", "var", "class", "object", "interface", "if", "else", "when", "for", "while",
    "do", "return", "import", "package", "private", "public", "protected", "static", "final",
    "void", "int", "long", "float", "double", "boolean", "true", "false", "null", "this",
    "new", "try", "catch", "finally", "throw", "override", "const", "let", "function", "def",
    "in", "is", "as", "from", "with", "lambda", "self", "None", "True", "False", "extends",
    "implements", "abstract", "enum", "struct", "fn", "mut", "switch", "case", "break",
    "continue", "async", "await", "export", "default", "typeof", "echo", "then", "fi", "done"
)

private val WORD = Regex("\\b[A-Za-z_]+\\b")
private val NUMBER = Regex("\\b\\d+(\\.\\d+)?\\b")
private val STRING = Regex("\"(?:\\\\.|[^\"\\\\\n])*\"|'(?:\\\\.|[^'\\\\\n])*'")
private val DQ_STRING = Regex("\"[^\"\n]*\"")
private val BLOCK_COMMENT = Regex("/\\*[\\s\\S]*?\\*/")
private val SLASH_COMMENT = Regex("//.*")
private val HASH_COMMENT = Regex("#.*")
private val XML_COMMENT = Regex("<!--[\\s\\S]*?-->")
private val TAG = Regex("</?[A-Za-z][\\w:.-]*")

private val C_KEYWORD = Color(0xFF7B1FA2)
private val C_STRING = Color(0xFF2E7D32)
private val C_COMMENT = Color(0xFF757575)
private val C_NUMBER = Color(0xFF1565C0)
private val C_TAG = Color(0xFFC62828)

fun highlight(src: String, ext: String): AnnotatedString = buildAnnotatedString {
    append(src)
    if (src.length > 150_000) return@buildAnnotatedString

    fun paint(r: Regex, c: Color) {
        r.findAll(src).forEach { addStyle(SpanStyle(color = c), it.range.first, it.range.last + 1) }
    }

    when (ext) {
        in CODE_EXTS, in HASH_EXTS -> {
            WORD.findAll(src).forEach {
                if (it.value in KEYWORDS)
                    addStyle(SpanStyle(color = C_KEYWORD), it.range.first, it.range.last + 1)
            }
            paint(NUMBER, C_NUMBER)
            paint(STRING, C_STRING)
            if (ext in HASH_EXTS) paint(HASH_COMMENT, C_COMMENT)
            else { paint(SLASH_COMMENT, C_COMMENT); paint(BLOCK_COMMENT, C_COMMENT) }
        }
        in MARKUP_EXTS -> {
            paint(TAG, C_TAG)
            paint(DQ_STRING, C_STRING)
            paint(XML_COMMENT, C_COMMENT)
        }
    }
}


/** text me line number `line` (1 se shuru) kahan se shuru hoti hai */
private fun lineStartOffset(text: String, line: Int): Int {
    if (line <= 1) return 0
    var idx = 0
    var n = 1
    while (n < line) {
        val nl = text.indexOf('\n', idx)
        if (nl < 0) return idx
        idx = nl + 1
        n++
    }
    return idx
}
