package com.example.filemanager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class PreferencesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.load(this)
        setContent { AppTheme { PreferencesScreen { finish() } } }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
    )
}

@Composable
fun PrefRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(title, fontSize = 16.sp)
        Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp)
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreferencesScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    var dialog by remember { mutableStateOf<String?>(null) }

    fun changed() {
        Prefs.save(context)
        Prefs.version++
    }

    val sortNames = listOf("Name", "Size", "Date (modified)", "Type")
    val sizeNames = listOf("Chhota", "Normal", "Bada")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Preferences", fontSize = 17.sp) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = appBarColors()
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) {
            SectionTitle("Files")
            PrefRow(
                "Default sort",
                sortNames[Prefs.sortBy] + if (Prefs.ascending) "  (A-Z, chhota-bada)" else "  (Z-A, bada-chhota)"
            ) { dialog = "sort" }
            SwitchRow(
                "Hidden files dikhao", "Jin ke naam . se shuru hote hain", Prefs.showHidden
            ) { Prefs.showHidden = it; changed() }
            SwitchRow(
                "Folders pehle", "Sort me folders hamesha upar", Prefs.foldersFirst
            ) { Prefs.foldersFirst = it; changed() }
            SwitchRow(
                "Date aur size dikhao", "Har file ke neeche chhoti line me", Prefs.showDetails
            ) { Prefs.showDetails = it; changed() }

            SectionTitle("Look")
            PrefRow("Text size", "File list ke akshar aur icon: " + sizeNames[Prefs.textSize]) {
                dialog = "size"
            }
            PrefRow("Theme / Colors", "Rang aur dark mode") { dialog = "theme" }

            SectionTitle("Other")
            PrefRow("Reset preferences", "Upar ki sab settings default par") { dialog = "reset" }

            SectionTitle("About")
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("All-In-One FM", fontSize = 16.sp)
                Text(
                    "File manager, text/code editor, image/video/audio player, PDF viewer, " +
                            "zip, terminal, APK tools, recycle bin",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    when (dialog) {
        "sort" -> SortDialog(
            SortBy.values()[Prefs.sortBy.coerceIn(0, 3)], Prefs.ascending,
            { by, asc -> Prefs.sortBy = by.ordinal; Prefs.ascending = asc; changed() }
        ) { dialog = null }
        "size" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Text size") },
            text = {
                Column {
                    sizeNames.forEachIndexed { i, name ->
                        Row(
                            Modifier.fillMaxWidth().clickable { Prefs.textSize = i; changed() },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = Prefs.textSize == i, onClick = { Prefs.textSize = i; changed() })
                            Text(name)
                        }
                    }
                }
            },
            confirmButton = { TextButton({ dialog = null }) { Text("OK") } }
        )
        "theme" -> ThemeDialog { dialog = null }
        "reset" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Reset karein?") },
            text = { Text("Sort, hidden files, text size wagairah default par aa jayenge. Theme nahi badlega.") },
            confirmButton = { TextButton({ Prefs.reset(context); dialog = null }) { Text("Reset") } },
            dismissButton = { TextButton({ dialog = null }) { Text("Cancel") } }
        )
    }
}
