package com.example.filemanager

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

class RemoteActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.load(this)
        FtpConfig.load(this)
        setContent { AppTheme { RemoteScreen { finish() } } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val running = FtpState.running
    val roots = remember {
        locationList(context).filter { it.first.startsWith("Internal") || it.first.startsWith("SD card") }
    }
    var portText by remember { mutableStateOf(FtpConfig.port.toString()) }

    fun say(msg: String) { scope.launch { snackbar.showSnackbar(msg) } }

    fun go() {
        val p = portText.toIntOrNull()
        if (p == null || p !in 1024..65535) { say("Port 1024 se 65535 ke beech ka number ho"); return }
        if (FtpConfig.user.isBlank() || FtpConfig.pass.isBlank()) { say("User aur password khaali nahi ho sakte"); return }
        FtpConfig.port = p
        FtpConfig.save(context)
        FtpState.error = null
        FtpService.start(context)
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { go() }

    fun startServer() {
        val needAsk = Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        if (needAsk) permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else go()
    }

    fun copy(text: String, what: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(what, text))
        say("$what copy ho gaya")
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Remote Manager (FTP)", fontSize = 17.sp) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = appBarColors()
            )
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        if (running) "Server chal raha hai" else "Server band hai",
                        fontSize = 18.sp, color = MaterialTheme.colorScheme.primary
                    )
                    if (running) {
                        val url = FtpState.address
                        val hostPort = url.removePrefix("ftp://")
                        Text("Address:  $url", fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                        Text("User:  ${FtpConfig.user}", fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                        Text("Password:  ${FtpConfig.pass}", fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                        Text("Computer se jude: ${FtpState.clients}", fontSize = 12.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Windows me File Explorer ka address bar kholo aur ye likho (Enter dabao):",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "ftp://${FtpConfig.user}:${FtpConfig.pass}@$hostPort",
                            fontFamily = FontFamily.Monospace, fontSize = 12.sp
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { copy(url, "Address") }) { Text("Address copy") }
                            OutlinedButton(onClick = {
                                copy("ftp://${FtpConfig.user}:${FtpConfig.pass}@$hostPort", "Login link")
                            }) { Text("Login link copy") }
                        }
                    } else {
                        Text(
                            "Start karne par phone ek FTP server ban jata hai. Computer aur phone " +
                                    "dono ek hi Wi-Fi par hone chahiye.",
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    FtpState.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                    Spacer(Modifier.height(4.dp))
                    if (running) Button(
                        onClick = { FtpService.stop(context) }, modifier = Modifier.fillMaxWidth()
                    ) { Text("Stop server") }
                    else Button(
                        onClick = { startServer() }, modifier = Modifier.fillMaxWidth()
                    ) { Text("Start server") }
                }
            }

            Text("Settings (server band hone par badlo)", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)

            Text("Kaunsa folder dikhana hai", fontSize = 13.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                roots.forEach { (name, f) ->
                    FilterChip(
                        selected = FtpConfig.root == f.absolutePath,
                        onClick = { FtpConfig.root = f.absolutePath; FtpConfig.save(context) },
                        enabled = !running,
                        label = { Text(name) }
                    )
                }
            }
            OutlinedTextField(
                value = portText, onValueChange = { portText = it.filter { c -> c.isDigit() }.take(5) },
                label = { Text("Port (1024-65535)") }, singleLine = true, enabled = !running,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = FtpConfig.user, onValueChange = { FtpConfig.user = it.trim() },
                label = { Text("Username") }, singleLine = true, enabled = !running,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = FtpConfig.pass, onValueChange = { FtpConfig.pass = it.trim() },
                label = { Text("Password") }, singleLine = true, enabled = !running,
                modifier = Modifier.fillMaxWidth()
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Read-only", fontSize = 16.sp)
                    Text(
                        "Computer se sirf dekh/download kar sakte ho, upload ya delete nahi",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = FtpConfig.readOnly, enabled = !running,
                    onCheckedChange = { FtpConfig.readOnly = it; FtpConfig.save(context) }
                )
            }

            Text(
                "Dhyan rakho: FTP me data bina encryption ke jata hai. Sirf apne bharose ke Wi-Fi par " +
                        "chalao, aur password zaroor rakho. Kaam khatam hone par Stop kar do.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
