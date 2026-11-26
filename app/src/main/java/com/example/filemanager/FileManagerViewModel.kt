package com.example.filemanager

import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class PaneState(
    val path: File,
    val files: List<File> = emptyList(),
    val selected: Set<String> = emptySet(),
    val showHidden: Boolean = true
)

class FileManagerViewModel : ViewModel() {
    private val root: File = Environment.getExternalStorageDirectory()

    var left by mutableStateOf(PaneState(root))
        private set
    var right by mutableStateOf(PaneState(root))
        private set
    var activeLeft by mutableStateOf(true)
        private set
    var message by mutableStateOf<String?>(null)

    private val active get() = if (activeLeft) left else right
    private val other get() = if (activeLeft) right else left

    fun refreshAll() {
        load(true, left.path); load(false, right.path)
    }

    fun setActive(isLeft: Boolean) { activeLeft = isLeft }

    private fun update(isLeft: Boolean, s: PaneState) {
        if (isLeft) left = s else right = s
    }

    fun load(isLeft: Boolean, dir: File) = viewModelScope.launch {
        val cur = if (isLeft) left else right
        val raw = withContext(Dispatchers.IO) {
            dir.listFiles()
                // /storage/emulated list nahi hota, isliye uske andar "0" khud dikhate hain
                ?: if (dir.path == "/storage/emulated") arrayOf(File("/storage/emulated/0")) else null
        }
        if (raw == null) message = "Is folder ka content padh nahi sakte (upar ja sakte ho)"
        val list = (raw?.toList() ?: emptyList())
            .filter { cur.showHidden || !it.name.startsWith(".") }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        update(isLeft, cur.copy(path = dir, files = list, selected = emptySet()))
    }

    fun open(isLeft: Boolean, f: File) {
        activeLeft = isLeft
        if (f.isDirectory) load(isLeft, f)
        // TODO: file type ke hisab se editor/viewer/player kholna
    }

    fun goUp(isLeft: Boolean) {
        val cur = if (isLeft) left else right
        cur.path.parentFile?.let { load(isLeft, it) }
    }

    fun toggleSelect(isLeft: Boolean, f: File) {
        activeLeft = isLeft
        val cur = if (isLeft) left else right
        val s = cur.selected.toMutableSet()
        if (!s.add(f.absolutePath)) s.remove(f.absolutePath)
        update(isLeft, cur.copy(selected = s))
    }

    fun selectAll() = update(activeLeft, active.copy(selected = active.files.map { it.absolutePath }.toSet()))
    fun clearSelection() = update(activeLeft, active.copy(selected = emptySet()))

    fun toggleHidden() {
        update(activeLeft, active.copy(showHidden = !active.showHidden))
        load(activeLeft, active.path)
    }

    private fun selectedFiles() = active.selected.map { File(it) }

    /** Active pane ki selected files -> doosre pane ke folder me */
    fun copySelected(move: Boolean) = viewModelScope.launch {
        val dest = other.path
        val items = selectedFiles()
        val err = withContext(Dispatchers.IO) {
            runCatching {
                items.forEach { src ->
                    val target = uniqueTarget(dest, src.name)
                    if (src.isDirectory && target.absolutePath.startsWith(src.absolutePath + "/"))
                        error("Folder ko apne andar copy nahi kar sakte")
                    if (move && src.renameTo(target)) return@forEach
                    src.copyRecursively(target, overwrite = false)
                    if (move) src.deleteRecursively()
                }
            }.exceptionOrNull()
        }
        message = err?.message ?: if (move) "Move ho gaya" else "Copy ho gaya"
        refreshAll()
    }

    fun deleteSelected() = viewModelScope.launch {
        val items = selectedFiles()
        withContext(Dispatchers.IO) { items.forEach { it.deleteRecursively() } }
        message = "${items.size} item delete hue"
        refreshAll()
    }

    fun renameSelected(newName: String) = viewModelScope.launch {
        val f = selectedFiles().singleOrNull() ?: return@launch
        val ok = withContext(Dispatchers.IO) { f.renameTo(File(f.parentFile, newName)) }
        message = if (ok) "Rename ho gaya" else "Rename fail"
        refreshAll()
    }

    fun newFolder(name: String) = viewModelScope.launch {
        val ok = withContext(Dispatchers.IO) { File(active.path, name).mkdirs() }
        message = if (ok) "Folder ban gaya" else "Folder nahi bana"
        refreshAll()
    }

    private fun uniqueTarget(dir: File, name: String): File {
        var t = File(dir, name)
        var i = 1
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        while (t.exists()) { t = File(dir, "$base ($i)$ext"); i++ }
        return t
    }
}
