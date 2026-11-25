package com.example.filemanager

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    private val vm: FileManagerViewModel by viewModels()
    private var granted by mutableStateOf(false)

    private val legacyPerm = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { checkPermission() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkPermission()
        setContent {
            MaterialTheme {
                if (granted) {
                    LaunchedEffect(Unit) { vm.refreshAll() }
                    FileManagerScreen(vm)
                } else {
                    PermissionScreen { requestPermission() }
                }
            }
        }
    }

    override fun onResume() { super.onResume(); checkPermission() }

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

@OptIn(ExperimentalMaterial3Api::class)
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileManagerScreen(vm: FileManagerViewModel) {
    val snackbar = remember { SnackbarHostState() }
    var dialog by remember { mutableStateOf<String?>(null) } // "rename" | "folder" | "delete"

    LaunchedEffect(vm.message) {
        vm.message?.let { snackbar.showSnackbar(it); vm.message = null }
    }

    val active = if (vm.activeLeft) vm.left else vm.right
    val count = active.selected.size

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(active.path.absolutePath, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium)
                        Text("Items: ${active.files.size}  Selected: $count",
                            style = MaterialTheme.typography.bodySmall)
                    }
                },
                actions = {
                    IconButton(onClick = { vm.selectAll() }) { Icon(Icons.Default.SelectAll, "Select all") }
                    IconButton(onClick = { vm.toggleHidden() }) { Icon(Icons.Default.Visibility, "Hidden") }
                }
            )
        },
        bottomBar = {
            BottomAppBar {
                BarBtn(Icons.Default.CreateNewFolder, "Folder") { dialog = "folder" }
                BarBtn(Icons.Default.ContentCopy, "Copy", count > 0) { vm.copySelected(false) }
                BarBtn(Icons.Default.DriveFileMove, "Move", count > 0) { vm.copySelected(true) }
                BarBtn(Icons.Default.Edit, "Rename", count == 1) { dialog = "rename" }
                BarBtn(Icons.Default.Delete, "Delete", count > 0) { dialog = "delete" }
            }
        }
    ) { pad ->
        Row(Modifier.padding(pad).fillMaxSize()) {
            FilePane(vm, true, vm.left, Modifier.weight(1f))
            FilePane(vm, false, vm.right, Modifier.weight(1f))
        }
    }

    when (dialog) {
        "folder" -> NameDialog("Naya folder", "", { dialog = null }) { vm.newFolder(it); dialog = null }
        "rename" -> NameDialog("Rename", File(active.selected.first()).name, { dialog = null }) {
            vm.renameSelected(it); dialog = null
        }
        "delete" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Delete karein?") },
            text = { Text("$count item hamesha ke liye delete honge.") },
            confirmButton = { TextButton({ vm.deleteSelected(); dialog = null }) { Text("Delete") } },
            dismissButton = { TextButton({ dialog = null }) { Text("Cancel") } }
        )
    }
}

@Composable
fun RowScope.BarBtn(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String,
                    enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.weight(1f)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, label)
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

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

@Composable
fun FilePane(vm: FileManagerViewModel, isLeft: Boolean, state: PaneState, modifier: Modifier) {
    val isActive = vm.activeLeft == isLeft
    Column(
        modifier
            .fillMaxHeight()
            .border(if (isActive) 2.dp else 0.dp,
                if (isActive) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent)
    ) {
        LazyColumn(Modifier.weight(1f)) {
            item {
                ListItem(
                    headlineContent = { Text("..") },
                    leadingContent = { Icon(Icons.Default.Folder, null) },
                    modifier = Modifier.combinedClickable(onClick = { vm.setActive(isLeft); vm.goUp(isLeft) })
                )
            }
            items(state.files, key = { it.absolutePath }) { f ->
                FileRow(f, f.absolutePath in state.selected,
                    onClick = { vm.open(isLeft, f) },
                    onLong = { vm.toggleSelect(isLeft, f) })
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileRow(f: File, selected: Boolean, onClick: () -> Unit, onLong: () -> Unit) {
    val fmt = remember { SimpleDateFormat("yy-MM-dd HH:mm", Locale.getDefault()) }
    ListItem(
        headlineContent = { Text(f.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            val size = if (f.isFile) " • " + humanSize(f.length()) else ""
            Text(fmt.format(Date(f.lastModified())) + size, style = MaterialTheme.typography.bodySmall)
        },
        leadingContent = {
            Icon(
                if (f.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                null
            )
        },
        colors = ListItemDefaults.colors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface
        ),
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLong)
    )
}

fun humanSize(b: Long): String {
    if (b < 1024) return "$b B"
    val u = arrayOf("KB", "MB", "GB", "TB")
    var v = b / 1024.0; var i = 0
    while (v >= 1024 && i < u.lastIndex) { v /= 1024; i++ }
    return "%.1f %s".format(v, u[i])
}
