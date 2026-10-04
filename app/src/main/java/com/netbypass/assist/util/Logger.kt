package com.netbypass.assist.util

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Tiny in-memory ring-buffer logger. Mirrors every line to Logcat and exposes a [StateFlow] of
 * the last [MAX_LINES] lines so the Settings UI can show a live log view without reading files.
 */
object Logger {

    private const val MAX_LINES = 400
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val _lines = MutableStateFlow("")
    val lines = _lines.asStateFlow()

    private val buffer = ArrayDeque<String>()

    @Synchronized
    private fun append(level: String, tag: String, msg: String) {
        val line = "${timeFormat.format(System.currentTimeMillis())} $level/$tag: $msg"
        buffer.addLast(line)
        while (buffer.size > MAX_LINES) buffer.removeFirst()
        _lines.value = buffer.joinToString("\n")
    }

    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
        append("I", tag, msg)
    }

    fun w(tag: String, msg: String) {
        Log.w(tag, msg)
        append("W", tag, msg)
    }

    fun e(tag: String, msg: String, t: Throwable? = null) {
        Log.e(tag, msg, t)
        append("E", tag, msg + (t?.let { " :: ${it.message}" } ?: ""))
    }

    fun d(tag: String, msg: String) {
        Log.d(tag, msg)
        append("D", tag, msg)
    }

    fun clear() {
        buffer.clear()
        _lines.value = ""
    }
}
