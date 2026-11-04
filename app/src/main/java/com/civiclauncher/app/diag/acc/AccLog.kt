package com.civiclauncher.app.diag.acc

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Bitácora en disco de lo que pasa al quitar y poner la llave. Sobrevive reinicios. */
class AccLog(context: Context) {
    private val file = File(context.filesDir, "acc_log.txt")
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val _lines = MutableStateFlow(load())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    private fun load(): List<String> =
        runCatching { file.readLines().takeLast(MAX_LINES_MEMORY) }.getOrDefault(emptyList())

    @Synchronized
    fun log(event: String) {
        val line = "${fmt.format(Date())} | arranque+${SystemClock.elapsedRealtime() / 1000}s | $event"
        runCatching {
            if (file.length() > MAX_FILE_BYTES) {
                val keep = file.readLines().takeLast(MAX_LINES_MEMORY)
                file.writeText(keep.joinToString("\n", postfix = "\n"))
            }
            file.appendText(line + "\n")
        }
        _lines.value = (_lines.value + line).takeLast(MAX_LINES_MEMORY)
    }

    @Synchronized
    fun clear() {
        runCatching { file.delete() }
        _lines.value = emptyList()
    }

    fun text(): String = runCatching { file.readText() }.getOrDefault("")

    fun formatWall(ms: Long): String = fmt.format(Date(ms))

    private companion object {
        const val MAX_LINES_MEMORY = 400
        const val MAX_FILE_BYTES = 256 * 1024L
    }
}
