package com.example.filemanager

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.io.File

/** Audio player screen. Gaana MusicService me chalta hai, isliye back dabane par bhi chalta rehta hai. */
@Composable
fun MusicPlayerScreen(file: File, onClose: () -> Unit) {
    val context = LocalContext.current

    val folderList = remember(file) {
        val l = file.parentFile?.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in AUDIO_EXTS }
            ?.sortedBy { it.name.lowercase() }
            ?.map { it.absolutePath } ?: emptyList()
        if (file.absolutePath in l) l else listOf(file.absolutePath)
    }

    fun startPlayback() {
        val i = folderList.indexOf(file.absolutePath).coerceAtLeast(0)
        val inst = MusicService.instance
        if (inst != null && MusicState.playlist == folderList) {
            if (MusicState.current != file.absolutePath) inst.playAt(i)
        } else {
            MusicService.start(context, folderList, i)
        }
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { startPlayback() }

    LaunchedEffect(file) {
        val needAsk = Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        if (needAsk) permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else startPlayback()
    }

    val list = MusicState.playlist.ifEmpty { folderList }
    var dragPos by remember { mutableStateOf<Float?>(null) }
    val white = Color.White
    val dim = Color.White.copy(alpha = 0.6f)

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = white)
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { MusicService.instance?.stopAll(); onClose() }) {
                Text("Stop", color = white)
            }
        }

        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier.size(120.dp).clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.MusicNote, null, tint = white, modifier = Modifier.size(72.dp))
            }
            Spacer(Modifier.height(12.dp))
            Text(
                MusicState.current?.let { File(it).name } ?: file.name,
                color = white, fontSize = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            Text(
                if (MusicState.index >= 0) "${MusicState.index + 1} / ${list.size}" else "",
                color = dim, fontSize = 12.sp
            )
            MusicState.error?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 12.sp) }

            Slider(
                value = dragPos ?: MusicState.pos.toFloat(),
                onValueChange = { dragPos = it },
                onValueChangeFinished = {
                    dragPos?.let { MusicService.instance?.seekTo(it.toInt()) }
                    dragPos = null
                },
                valueRange = 0f..maxOf(MusicState.dur, 1).toFloat()
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(fmtTime((dragPos ?: MusicState.pos.toFloat()).toInt()), color = white, fontSize = 12.sp)
                Text(fmtTime(MusicState.dur), color = white, fontSize = 12.sp)
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { MusicState.shuffle = !MusicState.shuffle }) {
                    Icon(
                        Icons.Default.Shuffle, "Shuffle",
                        tint = if (MusicState.shuffle) MaterialTheme.colorScheme.primary else dim
                    )
                }
                IconButton(onClick = { MusicService.instance?.prev() }) {
                    Icon(Icons.Default.SkipPrevious, "Prev", tint = white, modifier = Modifier.size(36.dp))
                }
                FilledIconButton(onClick = { MusicService.instance?.toggle() }, modifier = Modifier.size(64.dp)) {
                    Icon(
                        if (MusicState.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        "Play/Pause", modifier = Modifier.size(36.dp)
                    )
                }
                IconButton(onClick = { MusicService.instance?.next() }) {
                    Icon(Icons.Default.SkipNext, "Next", tint = white, modifier = Modifier.size(36.dp))
                }
                IconButton(onClick = { MusicState.repeat = (MusicState.repeat + 1) % 3 }) {
                    Icon(
                        if (MusicState.repeat == 2) Icons.Default.RepeatOne else Icons.Default.Repeat,
                        "Repeat",
                        tint = if (MusicState.repeat != 0) MaterialTheme.colorScheme.primary else dim
                    )
                }
            }
        }

        HorizontalDivider(Modifier.padding(top = 8.dp), color = dim.copy(alpha = 0.3f))
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            itemsIndexed(list) { i, path ->
                val cur = i == MusicState.index
                Row(
                    Modifier.fillMaxWidth().clickable { MusicService.instance?.playAt(i) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (cur && MusicState.playing) Icons.Default.PlayArrow else Icons.Default.MusicNote,
                        null,
                        tint = if (cur) MaterialTheme.colorScheme.primary else dim,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        File(path).name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (cur) MaterialTheme.colorScheme.primary else white
                    )
                }
            }
        }
    }
}
