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

enum class SortBy { NAME, SIZE, DATE, TYPE }

data class PaneState(
    val path: File,
    val files: List<File> = emptyList(),   // screen par dikhne wali (filter + sort ke baad)
    val all: List<File> = emptyList(),     // folder ki poori list
    val selected: Set<String> = emptySet(),
    val showHidden: Boolean = true,
    val sortBy: SortBy = SortBy.NAME,
    val ascending: Boolean = true,
    val query: String = "",
    val back: List<File> = emptyList(),
    val forward: List<File> = emptyList()
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

    private fun pane(isLeft: Boolean) = if (isLeft) left else right
    private val active get() = pane(activeLeft)
    private val other get() = pane(!activeLeft)

    private fun update(isLeft: Boolean, s: PaneState) {
        if (isLeft) left = s else right = s
    }

    private fun visible(s: PaneState): List<File> {
        val q = s.query.trim().lowercase()
        val cmp: Comparator<File> = when (s.sortBy) {
            SortBy.NAME -> compareBy<File> { it.name.lowercase() }
            SortBy.SIZE -> compareBy<File> { if (it.isFile) it.length() else 0L }
            SortBy.DATE -> compareBy<File> { it.lastModified() }
            SortBy.TYPE -> compareBy<File>({ it.extension.lowercase() }, { it.name.lowercase() })
        }
        val ordered = if (s.ascending) cmp else cmp.reversed()
        return s.all
            .filter { (s.showHidden || !it.name.startsWith(".")) && (q.isEmpty() || it.name.lowercase().contains(q)) }
            .sortedWith(compareBy<File> { !it.isDirectory }.then(ordered))
    }

    private fun refilter(isLeft: Boolean, change: (PaneState) -> PaneState) {
        val s = change(pane(isLeft))
        update(isLeft, s.copy(files = visible(s)))
    }

    fun refreshAll() {
        load(true, left.path, false)
        load(false, right.path, false)
    }

    fun setActive(isLeft: Boolean) {
        if (isLeft != activeLeft) {
            update(activeLeft, active.copy(selected = emptySet()))
            activeLeft = isLeft
        }
    }

    fun load(isLeft: Boolean, dir: File, history: Boolean = true) = viewModelScope.launch {
        val cur = pane(isLeft)
        val raw = withContext(Dispatchers.IO) {
            dir.listFiles()
                // /storage/emulated list nahi hota, isliye uske andar "0" khud dikhate hain
                ?: if (dir.path == "/storage/emulated") arrayOf(File("/storage/emulated/0")) else null
        }
        if (raw == null) message = "Is folder ka content padh nahi sakte (upar ja sakte ho)"
        val same = dir.absolutePath == cur.path.absolutePath
        val base = cur.copy(
            path = dir,
            all = raw?.toList() ?: emptyList(),
            selected = emptySet(),
            query = if (same) cur.query else "",
            back = if (history && !same) (cur.back + cur.path).takeLast(50) else cur.back,
            forward = if (history && !same) emptyList() else cur.forward
        )
        update(isLeft, base.copy(files = visible(base)))
    }

    fun open(isLeft: Boolean, f: File) {
        setActive(isLeft)
        if (f.isDirectory) load(isLeft, f)
    }

    fun goUp(isLeft: Boolean) {
        pane(isLeft).path.parentFile?.let { load(isLeft, it) }
    }

    fun goBack(isLeft: Boolean) {
        val cur = pane(isLeft)
        val target = cur.back.lastOrNull() ?: return
        update(isLeft, cur.copy(back = cur.back.dropLast(1), forward = cur.forward + cur.path))
        load(isLeft, target, false)
    }

    fun goForward(isLeft: Boolean) {
        val cur = pane(isLeft)
        val target = cur.forward.lastOrNull() ?: return
        update(isLeft, cur.copy(forward = cur.forward.dropLast(1), back = cur.back + cur.path))
        load(isLeft, target, false)
    }

    fun toggleSelect(isLeft: Boolean, f: File) {
        setActive(isLeft)
        val cur = pane(isLeft)
        val s = cur.selected.toMutableSet()
        if (!s.add(f.absolutePath)) s.remove(f.absolutePath)
        update(isLeft, cur.copy(selected = s))
    }

    fun selectAll() = update(activeLeft, active.copy(selected = active.files.map { it.absolutePath }.toSet()))
    fun clearSelection() = update(activeLeft, active.copy(selected = emptySet()))

    fun toggleHidden() = refilter(activeLeft) { it.copy(showHidden = !it.showHidden) }
    fun setSort(by: SortBy, asc: Boolean) = refilter(activeLeft) { it.copy(sortBy = by, ascending = asc) }
    fun setQuery(q: String) = refilter(activeLeft) { it.copy(query = q) }

    fun swapPanes() {
        val l = left
        left = right
        right = l
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

    fun newFile(name: String) = viewModelScope.launch {
        val ok = withContext(Dispatchers.IO) {
            runCatching { File(active.path, name).createNewFile() }.getOrDefault(false)
        }
        message = if (ok) "File ban gayi" else "File nahi bani (naam pehle se hai?)"
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
