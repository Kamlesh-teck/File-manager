package com.example.filemanager

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val vm: FileManagerViewModel by viewModels()
    private var granted by mutableStateOf(false)

    private val legacyPerm = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { checkPermission() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkPermission()
        Prefs.load(this)
        Bookmarks.load(this)
        setContent {
            AppTheme {
                if (granted) {
                    LaunchedEffect(Unit) {
                        val h = Bookmarks.home?.let { File(it) }
                        if (h != null && h.isDirectory && vm.left.back.isEmpty() && vm.right.back.isEmpty()) {
                            vm.load(true, h, false)
                            vm.load(false, h, false)
                        } else vm.refreshAll()
                    }
                    FileManagerScreen(vm)
                } else {
                    PermissionScreen { requestPermission() }
                }
            }
        }
    }

    private var appliedPrefs = 0

    override fun onResume() {
        super.onResume()
        checkPermission()
        if (granted) {
            if (Prefs.version != appliedPrefs) {
                appliedPrefs = Prefs.version
                vm.applyPrefs()
            }
            // Search se "Show in folder" aaya ho to wahi folder kholo
            val req = ShowRequest.dir
            ShowRequest.dir = null
            if (req != null) vm.load(vm.activeLeft, req) else vm.refreshAll()
        }
    }

    private fun checkPermission() {
        granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(
            this, Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        } else legacyPerm.launch(
            arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        )
    }
}

@Composable
fun PermissionScreen(onGrant: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Storage access chahiye", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text("Files dikhane aur manage karne ke liye permission do.")
        Spacer(Modifier.height(16.dp))
        Button(onClick = onGrant) { Text("Permission do") }
    }
}

// ======================= Main screen =======================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileManagerScreen(vm: FileManagerViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    var dialog by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var plusOpen by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    val dark = LocalDark.current

    LaunchedEffect(vm.message) {
        vm.message?.let { snackbar.showSnackbar(it); vm.message = null }
    }

    val active = if (vm.activeLeft) vm.left else vm.right
    val count = active.selected.size
    val single = active.selected.singleOrNull()?.let { File(it) }
    val startRoot = remember { Environment.getExternalStorageDirectory().absolutePath }
    val insideHome = active.path.absolutePath.startsWith("$startRoot/")

    BackHandler(
        enabled = drawerState.isOpen || count > 0 || searching || insideHome || active.back.isNotEmpty()
    ) {
        when {
            drawerState.isOpen -> scope.launch { drawerState.close() }
            count > 0 -> vm.clearSelection()
            searching -> { searching = false; vm.setQuery("") }
            insideHome -> vm.goUp(vm.activeLeft)
            else -> vm.goBack(vm.activeLeft)
        }
    }

    val barBg = if (dark) Color(0xFF2B2B2B) else MaterialTheme.colorScheme.primary
    val barColors = TopAppBarDefaults.topAppBarColors(
        containerColor = barBg,
        titleContentColor = Color.White,
        navigationIconContentColor = Color.White,
        actionIconContentColor = Color.White
    )

    val diskInfo = remember(active.path, active.all.size) {
        val t = active.path.totalSpace
        if (t > 0) humanSize(t - active.path.freeSpace) + "/" + humanSize(t) else "-"
    }
    val hereDir = active.path.absolutePath
    val bookmarked = hereDir in Bookmarks.items
    val folders = active.files.count { it.isDirectory }
    val filesCount = active.files.size - folders

    fun openTerminal() {
        context.startActivity(
            Intent(context, TerminalActivity::class.java)
                .putExtra("path", active.path.absolutePath)
        )
    }

    fun handleAction(a: String) {
        dialog = null
        when (a) {
            "copy" -> vm.copySelected(false)
            "move" -> vm.copySelected(true)
            "rename" -> dialog = "rename"
            "delete" -> dialog = "delete"
            "props" -> dialog = "props"
            "all" -> vm.selectAll()
            "text" -> single?.let {
                context.startActivity(
                    Intent(context, EditorActivity::class.java).putExtra("path", it.absolutePath)
                )
            }
            "zip" -> {
                val items = active.selected.map { File(it) }
                val dir = active.path
                scope.launch {
                    val r = zipSelectedItems(dir, items)
                    vm.message = r.fold(
                        { "Zip ban gaya: ${it.name}" },
                        { "Zip nahi bana: ${it.message}" }
                    )
                    vm.refreshAll()
                }
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            DrawerContent(
                onPick = {
                    vm.load(vm.activeLeft, it)
                    scope.launch { drawerState.close() }
                },
                onTerminal = {
                    scope.launch { drawerState.close() }
                    openTerminal()
                },
                onTheme = {
                    scope.launch { drawerState.close() }
                    dialog = "theme"
                }
            )
        }
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                Column {
                    if (count > 0) {
                        TopAppBar(
                            title = { Text("$count selected", fontSize = 16.sp) },
                            navigationIcon = {
                                IconButton(onClick = { vm.clearSelection() }) {
                                    Icon(Icons.Default.Close, "Clear")
                                }
                            },
                            actions = {
                                IconButton(onClick = { vm.selectAll() }) {
                                    Icon(Icons.Default.SelectAll, "Select all")
                                }
                                TextButton(onClick = { dialog = "actions" }) {
                                    Text("Actions", color = Color.White)
                                }
                            },
                            colors = barColors
                        )
                    } else {
                        TopAppBar(
                            title = {
                                Column {
                                    Text(
                                        shortPath(active.path), fontSize = 15.sp,
                                        fontWeight = FontWeight.Medium, maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        "Folders: $folders  Files: $filesCount  Disk: $diskInfo" + (if (active.filter != FileFilter.ALL) "  [${active.filter.label}]" else ""),
                                        fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                }
                            },
                            navigationIcon = {
                                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                    Icon(Icons.Default.Menu, "Locations")
                                }
                            },
                            actions = {
                                Box {
                                    IconButton(onClick = { menuOpen = true }) {
                                        Icon(Icons.Default.MoreVert, "Menu")
                                    }
                                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                        MenuRow(Icons.Default.Refresh, "Refresh") {
                                            menuOpen = false; vm.refreshAll()
                                        }
                                        MenuRow(Icons.Default.Search, "Search") {
                                            menuOpen = false
                                            context.startActivity(
                                                Intent(context, SearchActivity::class.java)
                                                    .putExtra("path", active.path.absolutePath)
                                            )
                                        }
                                        MenuRow(Icons.Default.SelectAll, "Select all") {
                                            menuOpen = false; vm.selectAll()
                                        }
                                        MenuRow(Icons.AutoMirrored.Filled.Sort, "Sort") {
                                            menuOpen = false; dialog = "sort"
                                        }
                                        MenuRow(
                                            Icons.Default.FilterList,
                                            if (active.filter == FileFilter.ALL) "Filter" else "Filter: ${active.filter.label}"
                                        ) {
                                            menuOpen = false; dialog = "filter"
                                        }
                                        MenuRow(Icons.Default.Terminal, "Open terminal") {
                                            menuOpen = false; openTerminal()
                                        }
                                        DropdownMenuItem(
                                            text = { Text("Hidden files") },
                                            leadingIcon = { Icon(Icons.Default.Visibility, null) },
                                            trailingIcon = {
                                                Checkbox(checked = active.showHidden, onCheckedChange = null)
                                            },
                                            onClick = { menuOpen = false; vm.toggleHidden() }
                                        )
                                        MenuRow(Icons.Default.SwapHoriz, "Swap windows") {
                                            menuOpen = false; vm.swapPanes()
                                        }
                                        MenuRow(
                                            Icons.Default.Bookmark,
                                            if (bookmarked) "Remove bookmark" else "Add bookmark"
                                        ) {
                                            menuOpen = false
                                            val added = Bookmarks.toggle(context, hereDir)
                                            vm.message = if (added) "Bookmark ban gaya" else "Bookmark hata diya"
                                        }
                                        MenuRow(Icons.Default.Home, "Set as Home") {
                                            menuOpen = false
                                            Bookmarks.setHome(context, hereDir)
                                            vm.message = "Ab app isi folder se shuru hoga"
                                        }
                                        MenuRow(Icons.Default.Palette, "Theme / Colors") {
                                            menuOpen = false; dialog = "theme"
                                        }
                                        MenuRow(Icons.Default.Settings, "Preferences") {
                                            menuOpen = false
                                            context.startActivity(Intent(context, PreferencesActivity::class.java))
                                        }
                                        MenuRow(Icons.AutoMirrored.Filled.ExitToApp, "Exit") {
                                            menuOpen = false; (context as? Activity)?.finish()
                                        }
                                    }
                                }
                            },
                            colors = barColors
                        )
                    }
                    if (searching) {
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = active.query,
                                    onValueChange = { vm.setQuery(it) },
                                    singleLine = true,
                                    placeholder = { Text("Is folder me dhundo") },
                                    leadingIcon = { Icon(Icons.Default.Search, null) },
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = { searching = false; vm.setQuery("") }) {
                                    Icon(Icons.Default.Close, "Close search")
                                }
                            }
                        }
                    }
                }
            },
            bottomBar = {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                    Row(
                        Modifier.fillMaxWidth().navigationBarsPadding().height(48.dp),
                        horizontalArrangement = Arrangement.SpaceAround,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { vm.goBack(vm.activeLeft) },
                            enabled = active.back.isNotEmpty()
                        ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Back") }
                        IconButton(
                            onClick = { vm.goForward(vm.activeLeft) },
                            enabled = active.forward.isNotEmpty()
                        ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Forward") }
                        Box {
                            IconButton(onClick = { plusOpen = true }) {
                                Icon(Icons.Default.Add, "New")
                            }
                            DropdownMenu(expanded = plusOpen, onDismissRequest = { plusOpen = false }) {
                                MenuRow(Icons.Default.CreateNewFolder, "New folder") {
                                    plusOpen = false; dialog = "folder"
                                }
                                MenuRow(Icons.Default.NoteAdd, "New file") {
                                    plusOpen = false; dialog = "file"
                                }
                            }
                        }
                        IconButton(onClick = { vm.swapPanes() }) {
                            Icon(Icons.Default.SwapHoriz, "Swap")
                        }
                        IconButton(onClick = { vm.goUp(vm.activeLeft) }) {
                            Icon(Icons.Default.ArrowUpward, "Up")
                        }
                    }
                }
            }
        ) { pad ->
            Row(Modifier.padding(pad).fillMaxSize()) {
                FilePane(vm, true, vm.left, Modifier.weight(1f)) { dialog = "actions" }
                VerticalDivider()
                FilePane(vm, false, vm.right, Modifier.weight(1f)) { dialog = "actions" }
            }
        }
    }

    when (dialog) {
        "actions" -> if (count > 0) ActionsDialog(
            title = single?.name ?: "$count items",
            single = single,
            onAction = { handleAction(it) },
            onDismiss = { dialog = null }
        )
        "folder" -> NameDialog("Naya folder", "", { dialog = null }) { vm.newFolder(it); dialog = null }
        "file" -> NameDialog("Nayi file", "", { dialog = null }) { vm.newFile(it); dialog = null }
        "rename" -> single?.let { f ->
            NameDialog("Rename", f.name, { dialog = null }) { vm.renameSelected(it); dialog = null }
        }
        "delete" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Delete karein?") },
            text = {
                Column {
                    Text("$count item Recycle Bin me bhejein? Wahan se wapas la sakte ho.")
                    TextButton(onClick = { vm.deleteSelected(); dialog = null }) {
                        Text("Hamesha ke liye delete karo", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = { TextButton({ vm.trashSelected(); dialog = null }) { Text("Recycle Bin") } },
            dismissButton = { TextButton({ dialog = null }) { Text("Cancel") } }
        )
        "sort" -> SortDialog(active.sortBy, active.ascending, { by, asc -> vm.setSort(by, asc) }) {
            dialog = null
        }
        "filter" -> FilterDialog(active.filter, { vm.setFilter(it); dialog = null }) { dialog = null }
        "theme" -> ThemeDialog { dialog = null }
        "props" -> single?.let { PropertiesDialog(it) { dialog = null } }
    }
}

fun shortPath(f: File): String {
    val seg = f.absolutePath.split("/").filter { it.isNotEmpty() }
    return when {
        seg.isEmpty() -> "/"
        seg.size <= 3 -> "/" + seg.joinToString("/") + "/"
        else -> ".../" + seg.takeLast(3).joinToString("/") + "/"
    }
}

@Composable
fun MenuRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(icon, null) },
        onClick = onClick
    )
}

// ======================= Panes & rows =======================

@Composable
fun FilePane(
    vm: FileManagerViewModel,
    isLeft: Boolean,
    state: PaneState,
    modifier: Modifier,
    onActions: () -> Unit
) {
    val isActive = vm.activeLeft == isLeft
    val context = LocalContext.current
    Column(
        modifier
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface)
            .then(
                if (isActive) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary)
                else Modifier
            )
    ) {
        LazyColumn(Modifier.weight(1f)) {
            item(key = "..") {
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { vm.setActive(isLeft); vm.goUp(isLeft) }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FileIcon(File("/"), (28f * Prefs.scale).dp)
                    Spacer(Modifier.width(6.dp))
                    Text("..", fontSize = 13.sp)
                }
            }
            items(state.files, key = { it.absolutePath }) { f ->
                FileRow(
                    f = f,
                    selected = f.absolutePath in state.selected,
                    onClick = {
                        vm.setActive(isLeft)
                        if (state.selected.isNotEmpty()) vm.toggleSelect(isLeft, f)
                        else if (f.isDirectory) vm.open(isLeft, f)
                        else openFile(context, f)
                    },
                    onLong = {
                        if (f.absolutePath !in state.selected) vm.toggleSelect(isLeft, f)
                        onActions()
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileRow(f: File, selected: Boolean, onClick: () -> Unit, onLong: () -> Unit) {
    val fmt = remember { SimpleDateFormat("yy-MM-dd HH:mm", Locale.getDefault()) }
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    val info = fmt.format(Date(f.lastModified())) + if (f.isFile) "  " + humanSize(f.length()) else ""
    val scale = Prefs.scale
    Row(
        Modifier
            .fillMaxWidth()
            .background(bg)
            .combinedClickable(onClick = onClick, onLongClick = onLong)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FileIcon(f, (28f * scale).dp)
        Spacer(Modifier.width(6.dp))
        Column {
            Text(
                f.name, fontSize = (12.5f * scale).sp, lineHeight = (15f * scale).sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            if (Prefs.showDetails) {
                Text(
                    info, fontSize = (10f * scale).sp, lineHeight = (12f * scale).sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1
                )
            }
        }
    }
}

// ======================= Dialogs =======================

@Composable
fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true) },
        confirmButton = { TextButton({ if (text.isNotBlank()) onOk(text.trim()) }) { Text("OK") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } }
    )
}

/** Long-press par aane wala popup: copy, move, rename, delete, zip waghera */
@Composable
fun ActionsDialog(
    title: String,
    single: File?,
    onAction: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ActionRow(Icons.Default.ContentCopy, "Copy (doosre pane me)") { onAction("copy") }
                ActionRow(Icons.Default.DriveFileMove, "Move (doosre pane me)") { onAction("move") }
                ActionRow(Icons.Default.Edit, "Rename", single != null) { onAction("rename") }
                ActionRow(Icons.Default.Delete, "Delete") { onAction("delete") }
                ActionRow(Icons.Default.Archive, "Zip banao") { onAction("zip") }
                ActionRow(
                    Icons.Default.Description, "Text editor me kholo",
                    single != null && single.isFile
                ) { onAction("text") }
                ActionRow(Icons.Default.Info, "Properties", single != null) { onAction("props") }
                ActionRow(Icons.Default.SelectAll, "Select all") { onAction("all") }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Close") } }
    )
}

@Composable
fun ActionRow(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val c = if (enabled) MaterialTheme.colorScheme.onSurface
    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = if (enabled) MaterialTheme.colorScheme.primary else c)
        Spacer(Modifier.width(14.dp))
        Text(label, color = c)
    }
}

@Composable
fun SortDialog(
    by: SortBy,
    asc: Boolean,
    onChange: (SortBy, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val labels = mapOf(
        SortBy.NAME to "Name", SortBy.SIZE to "Size",
        SortBy.DATE to "Date (modified)", SortBy.TYPE to "Type"
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sort") },
        text = {
            Column {
                SortBy.values().forEach { s ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onChange(s, asc) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = by == s, onClick = { onChange(s, asc) })
                        Text(labels[s] ?: s.name)
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Row(
                    Modifier.fillMaxWidth().clickable { onChange(by, !asc) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = !asc, onCheckedChange = { onChange(by, !asc) })
                    Text("Ulta (Z-A, bada-chhota)")
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("OK") } }
    )
}

@Composable
fun FilterDialog(current: FileFilter, onPick: (FileFilter) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Filter") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                FileFilter.values().forEach { f ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(f) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = current == f, onClick = { onPick(f) })
                        Text(f.label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Close") } }
    )
}

@Composable
fun ThemeDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Theme / Colors") },
        text = {
            Column {
                ThemeSettings.accents.chunked(4).forEachIndexed { row, group ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        group.forEachIndexed { col, (name, color) ->
                            val idx = row * 4 + col
                            Box(
                                Modifier.size(44.dp).clip(CircleShape).background(color)
                                    .clickable {
                                        ThemeSettings.accentIndex = idx
                                        ThemeSettings.save(context)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                if (ThemeSettings.accentIndex == idx)
                                    Icon(Icons.Default.Check, name, tint = Color.White)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                listOf("System", "Light", "Dark").forEachIndexed { i, label ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            ThemeSettings.darkMode = i
                            ThemeSettings.save(context)
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = ThemeSettings.darkMode == i, onClick = {
                            ThemeSettings.darkMode = i
                            ThemeSettings.save(context)
                        })
                        Text(label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("OK") } }
    )
}

@Composable
fun PropertiesDialog(f: File, onDismiss: () -> Unit) {
    var size by remember(f) { mutableStateOf<Long?>(null) }
    LaunchedEffect(f) {
        size = withContext(Dispatchers.IO) {
            if (f.isFile) f.length()
            else f.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }
    }
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Properties") },
        text = {
            Column {
                Text("Name: ${f.name}")
                Text("Path: ${f.parent ?: "/"}")
                Text("Type: ${if (f.isDirectory) "Folder" else f.extension.ifEmpty { "File" }}")
                Text("Size: ${size?.let { humanSize(it) } ?: "calculating..."}")
                Text("Modified: ${fmt.format(Date(f.lastModified()))}")
                Text("Read: ${if (f.canRead()) "yes" else "no"}   Write: ${if (f.canWrite()) "yes" else "no"}")
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("OK") } }
    )
}

// ======================= Left drawer (MT Manager jaisa) =======================

fun locationList(context: Context): List<Pair<String, File>> {
    val out = mutableListOf<Pair<String, File>>()
    val seen = mutableSetOf<String>()
    fun add(name: String, f: File) {
        val key = try { f.canonicalPath } catch (e: Exception) { f.path }
        if (seen.add(key)) out.add(name to f)
    }
    context.getExternalFilesDirs(null).forEachIndexed { i, d ->
        val p = d?.absolutePath ?: return@forEachIndexed
        val idx = p.indexOf("/Android/data")
        if (idx > 0) add(if (i == 0) "Internal storage" else "SD card / USB", File(p.substring(0, idx)))
    }
    File("/storage").listFiles()?.forEach {
        if (it.isDirectory && it.name != "emulated" && it.name != "self")
            add("SD card / USB (${it.name})", it)
    }
    add("/storage", File("/storage"))
    add("Root (/)", File("/"))
    return out
}

@Composable
fun DrawerContent(onPick: (File) -> Unit, onTerminal: () -> Unit, onTheme: () -> Unit) {
    val context = LocalContext.current
    val places = remember { locationList(context) }
    ModalDrawerSheet(modifier = Modifier.width(300.dp)) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Box(
                Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.primary)
                    .statusBarsPadding()
                    .padding(16.dp)
            ) {
                Column {
                    Text("All-In-One FM", color = MaterialTheme.colorScheme.onPrimary,
                        fontSize = 20.sp, fontWeight = FontWeight.Medium)
                    Text("Files, editor, viewer, terminal",
                        color = MaterialTheme.colorScheme.onPrimary, fontSize = 12.sp)
                }
            }
            places.forEach { (name, f) -> StorageRow(name, f) { onPick(f) } }
            Bookmarks.home?.let { h ->
                DrawerItem(Icons.Default.Home, "Home: " + File(h).name.ifEmpty { h }, true) {
                    onPick(File(h))
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(
                "Bookmarks", color = MaterialTheme.colorScheme.primary,
                fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            if (Bookmarks.items.isEmpty()) {
                Text(
                    "Koi bookmark nahi. Kisi folder me jao aur menu se Add bookmark dabao.",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
            Bookmarks.items.toList().forEach { p ->
                BookmarkRow(p, onOpen = { onPick(File(p)) }, onRemove = { Bookmarks.remove(context, p) })
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(
                "Tools", color = MaterialTheme.colorScheme.primary,
                fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            DrawerItem(Icons.Default.Terminal, "Terminal", true, onTerminal)
            DrawerItem(Icons.Default.Palette, "Theme / Colors", true, onTheme)
            DrawerItem(Icons.Default.Delete, "Recycle Bin", true) {
                context.startActivity(Intent(context, RecycleBinActivity::class.java))
            }
            DrawerItem(Icons.Default.Apps, "Installed Apps", true) {
                context.startActivity(Intent(context, AppsActivity::class.java))
            }
        }
    }
}

@Composable
fun BookmarkRow(path: String, onOpen: () -> Unit, onRemove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Bookmark, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(File(path).name.ifEmpty { path }, fontSize = 14.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            Text(path, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onRemove, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Default.Close, "Remove", modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
fun StorageRow(name: String, f: File, onClick: () -> Unit) {
    val total = f.totalSpace
    val used = total - f.freeSpace
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Storage, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text(name, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            if (total > 0) Text(
                "${used * 100 / total}%", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp
            )
        }
        if (total > 0) {
            LinearProgressIndicator(
                progress = { used.toFloat() / total },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            )
            Text(
                "${humanSize(used)} used, ${humanSize(f.freeSpace)} available",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        } else {
            Text(f.path, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun DrawerItem(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val c = if (enabled) MaterialTheme.colorScheme.onSurface
    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = if (enabled) MaterialTheme.colorScheme.primary else c)
        Spacer(Modifier.width(14.dp))
        Text(label + if (!enabled) "  (jald aayega)" else "", color = c)
    }
}

fun humanSize(b: Long): String {
    if (b < 1024) return "$b B"
    val u = arrayOf("KB", "MB", "GB", "TB")
    var v = b / 1024.0
    var i = 0
    while (v >= 1024 && i < u.lastIndex) { v /= 1024; i++ }
    return "%.1f %s".format(v, u[i])
}
