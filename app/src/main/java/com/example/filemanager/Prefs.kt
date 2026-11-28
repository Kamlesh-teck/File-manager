package com.example.filemanager

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** App ki settings (Preferences screen se badalti hain). SharedPreferences me save hoti hain. */
object Prefs {
    var sortBy by mutableIntStateOf(0)          // SortBy ka ordinal: 0 name, 1 size, 2 date, 3 type
    var ascending by mutableStateOf(true)
    var showHidden by mutableStateOf(true)
    var foldersFirst by mutableStateOf(true)
    var showDetails by mutableStateOf(true)     // rows me date aur size
    var textSize by mutableIntStateOf(1)        // 0 chhota, 1 normal, 2 bada

    // Editor ki settings
    var editorSize by mutableIntStateOf(14)     // font size (sp)
    var editorWrap by mutableStateOf(false)     // lambi lines tod kar dikhao
    var editorLineNumbers by mutableStateOf(true)
    var editorHighlight by mutableStateOf(true) // code ke rang

    /** Har badlav par badhta hai, taaki main screen ko pata chale ki settings badli hain */
    var version by mutableIntStateOf(0)

    private var loaded = false

    val scale: Float
        get() = when (textSize) {
            0 -> 0.88f
            2 -> 1.15f
            else -> 1f
        }

    private fun sp(c: Context) = c.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    fun load(c: Context) {
        if (loaded) return
        val s = sp(c)
        sortBy = s.getInt("sortBy", 0).coerceIn(0, 3)
        ascending = s.getBoolean("ascending", true)
        showHidden = s.getBoolean("showHidden", true)
        foldersFirst = s.getBoolean("foldersFirst", true)
        showDetails = s.getBoolean("showDetails", true)
        textSize = s.getInt("textSize", 1).coerceIn(0, 2)
        editorSize = s.getInt("editorSize", 14).coerceIn(8, 32)
        editorWrap = s.getBoolean("editorWrap", false)
        editorLineNumbers = s.getBoolean("editorLineNumbers", true)
        editorHighlight = s.getBoolean("editorHighlight", true)
        loaded = true
    }

    fun save(c: Context) {
        sp(c).edit()
            .putInt("sortBy", sortBy)
            .putBoolean("ascending", ascending)
            .putBoolean("showHidden", showHidden)
            .putBoolean("foldersFirst", foldersFirst)
            .putBoolean("showDetails", showDetails)
            .putInt("textSize", textSize)
            .putInt("editorSize", editorSize)
            .putBoolean("editorWrap", editorWrap)
            .putBoolean("editorLineNumbers", editorLineNumbers)
            .putBoolean("editorHighlight", editorHighlight)
            .apply()
    }

    fun reset(c: Context) {
        sortBy = 0
        ascending = true
        showHidden = true
        foldersFirst = true
        showDetails = true
        textSize = 1
        editorSize = 14
        editorWrap = false
        editorLineNumbers = true
        editorHighlight = true
        save(c)
        version++
    }
}
