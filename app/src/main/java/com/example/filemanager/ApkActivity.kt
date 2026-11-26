package com.example.filemanager

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

const val INSTALL_ACTION = "com.example.filemanager.INSTALL_RESULT"

data class ApkInfo(
    val label: String,
    val pkg: String,
    val version: String,
    val code: Long,
    val icon: Bitmap?,
    val installedVersion: String?,
    val installedCode: Long?
)

fun drawableToBitmap(d: Drawable, px: Int = 128): Bitmap {
    val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    d.setBounds(0, 0, px, px)
    d.draw(Canvas(bmp))
    return bmp
}

@Suppress("DEPRECATION")
fun versionCodeOf(pi: PackageInfo): Long =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode else pi.versionCode.toLong()

@Suppress("DEPRECATION")
fun readApk(context: Context, file: File): ApkInfo? {
    val pm = context.packageManager
    val pi = pm.getPackageArchiveInfo(file.absolutePath, 0) ?: return null
    var label: String = pi.packageName
    var icon: Bitmap? = null
    val ai = pi.applicationInfo
    if (ai != null) {
        ai.sourceDir = file.absolutePath
        ai.publicSourceDir = file.absolutePath
        try { label = ai.loadLabel(pm).toString() } catch (e: Exception) { }
        try { icon = drawableToBitmap(ai.loadIcon(pm)) } catch (e: Exception) { }
    }
    val installed = try { pm.getPackageInfo(pi.packageName, 0) } catch (e: Exception) { null }
    return ApkInfo(
        label, pi.packageName, pi.versionName ?: "?", versionCodeOf(pi), icon,
        installed?.versionName, installed?.let { versionCodeOf(it) }
    )
}

@Suppress("DEPRECATION")
private fun extraIntent(i: Intent): Intent? =
    if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
    else i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)

/**
 * APK install karta hai (PackageInstaller se). Turant koi dikkat ho to message deta hai,
 * warna null deta hai aur final natija onResult se aata hai.
 */
fun installApk(context: Context, file: File, onResult: (String) -> Unit): String? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
        )
        return "Pehle 'Install unknown apps' allow karo, phir wapas aakar Install dabao"
    }
    val appCtx = context.applicationContext
    val installer = context.packageManager.packageInstaller
    var sessionId = -1
    return try {
        sessionId = installer.createSession(
            PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        )
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    val status = intent.getIntExtra(
                        PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE
                    )
                    when (status) {
                        PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                            val confirm = extraIntent(intent)
                            if (confirm != null) {
                                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                c.startActivity(confirm)
                            }
                        }
                        PackageInstaller.STATUS_SUCCESS -> {
                            try { appCtx.unregisterReceiver(this) } catch (e: Exception) { }
                            onResult("Install ho gaya")
                        }
                        else -> {
                            try { appCtx.unregisterReceiver(this) } catch (e: Exception) { }
                            val m = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "code $status"
                            onResult("Install nahi hua: $m")
                        }
                    }
                }
            }
            ContextCompat.registerReceiver(
                appCtx, receiver, IntentFilter(INSTALL_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED
            )
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(
                appCtx, sessionId, Intent(INSTALL_ACTION).setPackage(appCtx.packageName), flags
            )
            session.commit(pending.intentSender)
        }
        null
    } catch (e: Exception) {
        try { if (sessionId >= 0) installer.abandonSession(sessionId) } catch (_: Exception) { }
        "Install shuru nahi hua: ${e.message}"
    }
}

class ApkActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra("path")
        if (path == null) { finish(); return }
        setContent { AppTheme { ApkScreen(File(path)) { finish() } } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApkScreen(file: File, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var info by remember { mutableStateOf<ApkInfo?>(null) }
    var failed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(file) {
        val r = withContext(Dispatchers.IO) { readApk(context, file) }
        if (r == null) failed = true else info = r
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(file.name, maxLines = 1, fontSize = 16.sp) },
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
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val i = info
            when {
                failed -> Text("APK padh nahi payi (kharab ya adhoori ho sakti hai)")
                i == null -> CircularProgressIndicator()
                else -> {
                    if (i.icon != null) Image(
                        bitmap = i.icon.asImageBitmap(), contentDescription = null,
                        modifier = Modifier.size(88.dp)
                    ) else FileIcon(file, 88.dp)
                    Spacer(Modifier.height(12.dp))
                    Text(i.label, fontSize = 20.sp, textAlign = TextAlign.Center)
                    Text(i.pkg, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Text("Version: ${i.version} (${i.code})")
                    Text("Size: ${humanSize(file.length())}")
                    val ic = i.installedCode
                    val status = when {
                        ic == null -> "Abhi installed nahi hai"
                        ic < i.code -> "Update: ${i.installedVersion} se ${i.version}"
                        ic == i.code -> "Yahi version pehle se installed hai"
                        else -> "Isse naya version installed hai (downgrade nahi hoga)"
                    }
                    Text(status, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(20.dp))
                    Button(
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            busy = true
                            scope.launch {
                                val msg = withContext(Dispatchers.IO) {
                                    installApk(context, file) { m -> scope.launch { snackbar.showSnackbar(m) } }
                                }
                                busy = false
                                if (msg != null) snackbar.showSnackbar(msg)
                            }
                        }
                    ) {
                        Text(
                            when {
                                ic == null -> "Install"
                                ic < i.code -> "Update"
                                else -> "Reinstall"
                            }
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    context.startActivity(
                        Intent(context, ZipActivity::class.java).putExtra("path", file.absolutePath)
                    )
                }
            ) { Text("Andar ki files dekho (zip)") }
        }
    }
}
