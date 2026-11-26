package com.example.filemanager

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Bookmarks aur Home folder. SharedPreferences me save hote hain. */
object Bookmarks {
    val items = mutableStateListOf<String>()
    var home by mutableStateOf<String?>(null)
    private var loaded = false

    private fun prefs(c: Context) = c.getSharedPreferences("bookmarks", Context.MODE_PRIVATE)

    fun load(c: Context) {
        if (loaded) return
        val sp = prefs(c)
        items.clear()
        sp.getString("items", "")
            ?.split("\n")
            ?.filter { it.isNotBlank() }
            ?.let { items.addAll(it) }
        home = sp.getString("home", null)
        loaded = true
    }

    private fun save(c: Context) {
        prefs(c).edit()
            .putString("items", items.joinToString("\n"))
            .putString("home", home)
            .apply()
    }

    /** Bookmark add ya remove karta hai. Add hua to true deta hai. */
    fun toggle(c: Context, path: String): Boolean {
        load(c)
        return if (items.remove(path)) {
            save(c); false
        } else {
            items.add(path); save(c); true
        }
    }

    fun remove(c: Context, path: String) {
        items.remove(path)
        save(c)
    }

    fun setHome(c: Context, path: String) {
        home = path
        save(c)
    }
}
