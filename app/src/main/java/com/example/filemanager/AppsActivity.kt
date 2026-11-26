package com.example.filemanager

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Sab screens ke liye upar ki bar ke rang (theme ke hisab se) */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun appBarColors(): TopAppBarColors {
    val dark = LocalDark.current
    return TopAppBarDefaults.topAppBarColors(
        containerColor = if (dark) Color(0xFF2B2B2B) else MaterialTheme.colorScheme.primary,
        titleContentColor = Color.White,
        navigationIconContentColor = Color.White,
        actionIconContentColor = Color.White
    )
}

data class AppEntry(
    val label: String,
    val pkg: String,
    val version: String,
    val system: Boolean,
    val apkPath: String,
    val size: Long,
    val splits: Int,
    val icon: ImageBitmap?
)

@Suppress("DEPRECATION")
fun loadApps(context: Context): List<AppEntry> {
    val pm = context.packageManager
    return pm.getInstalledPackages(0).mapNotNull { pi ->
        val ai = pi.applicationInfo ?: return@mapNotNull null
        val label = try { ai.loadLabel(pm).toString() } catch (e: Exception) { pi.packageName }
        val icon = try { drawableToBitmap(ai.loadIcon(pm), 96).asImageBitmap() } catch (e: Exception) { null }
        val path = ai.sourceDir ?: ""
        AppEntry(
            label, pi.packageName, pi.versionName ?: "?",
            (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
            path, File(path).length(), ai.splitSourceDirs?.size ?: 0, icon
        )
    }
}

class AppsActivity : ComponentActivity() {
    private var tick by mutableIntStateOf(0)
    private var first = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppTheme { AppsScreen(tick) { finish() } } }
    }

    override fun onResume() {
        super.onResume()
        // uninstall karke wapas aane par list dobara load ho
        if (first) first = false else tick++
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen(reloadKey: Int, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableIntStateOf(0) } // 0 = user, 1 = system, 2 = all
    var selected by remember { mutableStateOf<AppEntry?>(null) }

    LaunchedEffect(reloadKey) {
        apps = withContext(Dispatchers.IO) { loadApps(context) }
    }

    val shown = remember(apps, query, filter) {
        val q = query.trim().lowercase()
        (apps ?: emptyList())
            .filter {
                (filter == 2 || (filter == 1) == it.system) &&
                        (q.isEmpty() || it.label.lowercase().contains(q) || it.pkg.lowercase().contains(q))
            }
            .sortedBy { it.label.lowercase() }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            if (apps == null) "Installed Apps" else "Installed Apps (${shown.size})",
                            fontSize = 17.sp
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    },
                    colors = appBarColors()
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("App ka naam ya package") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
                )
                Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("User", "System", "All").forEachIndexed { i, name ->
                        FilterChip(selected = filter == i, onClick = { filter = i }, label = { Text(name) })
                    }
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            if (apps == null) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(shown, key = { it.pkg }) { app ->
                        Row(
                            Modifier.fillMaxWidth().clickable { selected = app }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (app.icon != null) Image(
                                bitmap = app.icon, contentDescription = null,
                                modifier = Modifier.size(38.dp)
                            ) else Spacer(Modifier.size(38.dp))
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(app.label, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    app.pkg, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    "v${app.version}   ${humanSize(app.size)}", fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    selected?.let { app ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(app.label, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    ActionRow(Icons.Default.PlayArrow, "Open (chalao)") {
                        selected = null
                        val li = context.packageManager.getLaunchIntentForPackage(app.pkg)
                        if (li != null) context.startActivity(li)
                        else scope.launch { snackbar.showSnackbar("Is app ko seedha chala nahi sakte") }
                    }
                    ActionRow(Icons.Default.Info, "App info (settings)") {
                        selected = null
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.pkg}"))
                        )
                    }
                    ActionRow(Icons.Default.Archive, "Backup APK") {
                        selected = null
                        scope.launch {
                            val r = withContext(Dispatchers.IO) {
                                runCatching {
                                    val dir = File(Environment.getExternalStorageDirectory(), "AllInOneFM/backup")
                                    dir.mkdirs()
                                    val name = (app.label + "_" + app.version + ".apk")
                                        .replace(Regex("[^A-Za-z0-9._-]"), "_")
                                    val out = uniqueFile(dir, name)
                                    File(app.apkPath).copyTo(out)
                                    out
                                }
                            }
                            val msg = r.fold(
                                {
                                    "Backup ban gaya: AllInOneFM/backup/${it.name}" +
                                            if (app.splits > 0) " (split APK: sirf base copy hua)" else ""
                                },
                                { "Backup nahi hua: ${it.message}" }
                            )
                            snackbar.showSnackbar(msg)
                        }
                    }
                    ActionRow(Icons.Default.Folder, "APK ki files dekho") {
                        selected = null
                        context.startActivity(
                            Intent(context, ZipActivity::class.java).putExtra("path", app.apkPath)
                        )
                    }
                    ActionRow(Icons.Default.Delete, "Uninstall") {
                        selected = null
                        context.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.pkg}")))
                    }
                }
            },
            confirmButton = { TextButton({ selected = null }) { Text("Close") } }
        )
    }
}
