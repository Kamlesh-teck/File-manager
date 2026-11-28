package com.example.filemanager

import android.os.Build
import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

enum class SortBy { NAME, SIZE, DATE, TYPE }

enum class FileFilter(val label: String) {
    ALL("All"), FOLDERS("Folders only"), IMAGES("Images"), VIDEOS("Videos"), AUDIO("Audio"),
    DOCS("Documents"), ARCHIVES("Archives"), APK("APK"), CODE("Code / Text")
}

private val F_IMAGES = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic")
private val F_VIDEOS = setOf("mp4", "mkv", "3gp", "webm", "avi", "mov", "m4v")
private val F_AUDIO = setOf("mp3", "m4a", "wav", "ogg", "flac", "aac", "opus", "amr")
private val F_DOCS = setOf(
    "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "rtf", "txt", "csv", "epub"
)
private val F_ARCHIVES = setOf("zip", "rar", "7z", "tar", "gz", "jar", "xz", "bz2")
private val F_CODE = setOf(
    "kt", "kts", "java", "js", "ts", "py", "c", "cpp", "h", "cs", "go", "rs", "xml", "json",
    "html", "htm", "css", "sh", "gradle", "yml", "yaml", "md", "log", "ini", "conf", "properties"
)

/** Folder hamesha dikhte hain (taaki andar ja sako), sirf "Folders only" me files chhupti hain */
fun matchesFilter(f: File, flt: FileFilter): Boolean {
    if (flt == FileFilter.ALL) return true
    if (flt == FileFilter.FOLDERS) return f.isDirectory
    if (f.isDirectory) return true
    val e = f.extension.lowercase()
    return when (flt) {
        FileFilter.IMAGES -> e in F_IMAGES
        FileFilter.VIDEOS -> e in F_VIDEOS
        FileFilter.AUDIO -> e in F_AUDIO
        FileFilter.DOCS -> e in F_DOCS
        FileFilter.ARCHIVES -> e in F_ARCHIVES
        FileFilter.APK -> e == "apk"
        FileFilter.CODE -> e in F_CODE
        else -> true
    }
}

/**
 * Folder ki ek entry. Folder hai ya file, size aur date ek hi baar (background me) padhe jate hain,
 * taaki sort, filter aur screen banate waqt storage ko baar-baar chhuna na pade.
 */
class FileItem(val file: File, val isDir: Boolean, val size: Long, val modified: Long) {
    val name: String = file.name
    val path: String = file.absolutePath
    val lower: String = name.lowercase()
    val ext: String = name.substringAfterLast('.', "").lowercase()
}

fun matchesFilter(f: FileItem, flt: FileFilter): Boolean {
    if (flt == FileFilter.ALL) return true
    if (flt == FileFilter.FOLDERS) return f.isDir
    if (f.isDir) return true
    val e = f.ext
    return when (flt) {
        FileFilter.IMAGES -> e in F_IMAGES
        FileFilter.VIDEOS -> e in F_VIDEOS
        FileFilter.AUDIO -> e in F_AUDIO
        FileFilter.DOCS -> e in F_DOCS
        FileFilter.ARCHIVES -> e in F_ARCHIVES
        FileFilter.APK -> e == "apk"
        FileFilter.CODE -> e in F_CODE
        else -> true
    }
}

private fun statItem(f: File): FileItem {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        try {
            // ek hi call me isDirectory, size aur date teeno mil jate hain
            val a = Files.readAttributes(f.toPath(), BasicFileAttributes::class.java)
            return FileItem(f, a.isDirectory, if (a.isDirectory) 0L else a.size(), a.lastModifiedTime().toMillis())
        } catch (e: Exception) {
        }
    }
    val d = f.isDirectory
    return FileItem(f, d, if (d) 0L else f.length(), f.lastModified())
}

data class PaneState(
    val path: File,
    val files: List<FileItem> = emptyList(),   // screen par dikhne wali (filter + sort ke baad)
    val all: List<FileItem> = emptyList(),     // folder ki poori list
    val loading: Boolean = false,
    val selected: Set<String> = emptySet(),
    val showHidden: Boolean = true,
    val sortBy: SortBy = SortBy.NAME,
    val ascending: Boolean = true,
    val query: String = "",
    val filter: FileFilter = FileFilter.ALL,
    val back: List<File> = emptyList(),
    val forward: List<File> = emptyList()
)

class FileManagerViewModel : ViewModel() {
    private val root: File = Environment.getExternalStorageDirectory()

    private fun initialPane() = PaneState(
        root,
        showHidden = Prefs.showHidden,
        sortBy = SortBy.values().getOrElse(Prefs.sortBy) { SortBy.NAME },
        ascending = Prefs.ascending
    )

    var left by mutableStateOf(initialPane())
        private set
    var right by mutableStateOf(initialPane())
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

    private fun visible(s: PaneState): List<FileItem> {
        val q = s.query.trim().lowercase()
        val cmp: Comparator<FileItem> = when (s.sortBy) {
            SortBy.NAME -> compareBy<FileItem> { it.lower }
            SortBy.SIZE -> compareBy<FileItem> { it.size }
            SortBy.DATE -> compareBy<FileItem> { it.modified }
            SortBy.TYPE -> compareBy<FileItem>({ it.ext }, { it.lower })
        }
        val ordered = if (s.ascending) cmp else cmp.reversed()
        val finalOrder = if (Prefs.foldersFirst) compareBy<FileItem> { !it.isDir }.then(ordered) else ordered
        return s.all
            .filter {
                (s.showHidden || !it.name.startsWith(".")) &&
                        (q.isEmpty() || it.lower.contains(q)) &&
                        matchesFilter(it, s.filter)
            }
            .sortedWith(finalOrder)
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

    private var leftJob: Job? = null
    private var rightJob: Job? = null

    /** Folder ki entries parallel me padhta hai (badi list ke liye tez) */
    private suspend fun readItems(dir: File): List<FileItem>? {
        // /storage/emulated list nahi hota, isliye uske andar "0" khud dikhate hain
        val raw: Array<File> = dir.listFiles()
            ?: if (dir.path == "/storage/emulated") arrayOf(File("/storage/emulated/0")) else return null
        if (raw.size <= 300) return raw.map { statItem(it) }
        return coroutineScope {
            raw.toList().chunked(250)
                .map { part -> async(Dispatchers.IO) { part.map { statItem(it) } } }
                .awaitAll()
                .flatten()
        }
    }

    fun load(isLeft: Boolean, dir: File, history: Boolean = true): Job {
        // pichla load chal raha ho to rok do, taaki purana natija naye ko na bigade
        if (isLeft) leftJob?.cancel() else rightJob?.cancel()

        val cur = pane(isLeft)
        val same = dir.absolutePath == cur.path.absolutePath
        val back = if (history && !same) (cur.back + cur.path).takeLast(50) else cur.back
        val forward = if (history && !same) emptyList() else cur.forward
        // naye folder par turant khaali list aur loading dikhao; wahi folder ho to purani list rehne do
        update(
            isLeft,
            if (same) cur.copy(loading = true)
            else cur.copy(
                path = dir, all = emptyList(), files = emptyList(), selected = emptySet(),
                query = "", back = back, forward = forward, loading = true
            )
        )

        val job = viewModelScope.launch {
            val items = withContext(Dispatchers.IO) { readItems(dir) }
            if (items == null) message = "Is folder ka content padh nahi sakte (upar ja sakte ho)"
            val base = pane(isLeft).copy(all = items ?: emptyList(), selected = emptySet(), loading = false)
            // sort aur filter bhi UI thread se alag
            val shown = withContext(Dispatchers.Default) { visible(base) }
            update(isLeft, base.copy(files = shown))
        }
        if (isLeft) leftJob = job else rightJob = job
        return job
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

    fun selectAll() = update(activeLeft, active.copy(selected = active.files.map { it.path }.toSet()))
    fun clearSelection() = update(activeLeft, active.copy(selected = emptySet()))

    fun toggleHidden() = refilter(activeLeft) { it.copy(showHidden = !it.showHidden) }
    fun setSort(by: SortBy, asc: Boolean) = refilter(activeLeft) { it.copy(sortBy = by, ascending = asc) }
    /** Preferences me badli hui settings dono panes par lagata hai */
    fun applyPrefs() {
        val by = SortBy.values().getOrElse(Prefs.sortBy) { SortBy.NAME }
        for (isLeft in listOf(true, false)) {
            refilter(isLeft) {
                it.copy(sortBy = by, ascending = Prefs.ascending, showHidden = Prefs.showHidden)
            }
        }
    }

    fun setFilter(f: FileFilter) = refilter(activeLeft) { it.copy(filter = f) }
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

    /** Recycle Bin me bhejta hai (wapas laa sakte ho) */
    fun trashSelected() = viewModelScope.launch {
        val items = selectedFiles()
        val failed = withContext(Dispatchers.IO) { items.count { moveToTrash(it).isFailure } }
        message = if (failed == 0) "${items.size} item Recycle Bin me gaye" else "$failed item nahi hate"
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
