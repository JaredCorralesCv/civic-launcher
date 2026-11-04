package com.civiclauncher.app.diag

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class Status { OK, WARN, BAD, INFO }

data class Row(val key: String, val value: String, val status: Status = Status.INFO)

data class ReportSection(
    val title: String,
    val rows: List<Row>,
    val notes: List<String> = emptyList(),
) {
    fun toText(): String = buildString {
        append("== ").append(title).append(" ==\n")
        rows.forEach { r ->
            append(r.key).append(": ").append(r.value)
            if (r.status == Status.WARN || r.status == Status.BAD) append("  [").append(r.status.name).append(']')
            append('\n')
        }
        notes.forEach { append("  · ").append(it).append('\n') }
        append('\n')
    }
}

/**
 * Resultados de las pruebas interactivas (escaneo BT, GPS, fluidez...) para el reporte.
 * Se guardan en disco: así no se pierden si cierras la app o la pantalla se reinicia entre pruebas.
 */
class DiagStore(context: Context) {
    private val file = File(context.filesDir, "diag_results.json")
    private val _results = MutableStateFlow(load())
    val results: StateFlow<Map<String, ReportSection>> = _results.asStateFlow()

    fun put(key: String, section: ReportSection) {
        _results.update { it + (key to section) }
        save()
    }

    fun get(key: String): ReportSection? = _results.value[key]

    fun clear() {
        _results.value = emptyMap()
        runCatching { file.delete() }
    }

    @Synchronized
    private fun save() = runCatching {
        val root = JSONObject()
        _results.value.forEach { (key, s) ->
            root.put(
                key,
                JSONObject()
                    .put("title", s.title)
                    .put("rows", JSONArray(s.rows.map { JSONObject().put("k", it.key).put("v", it.value).put("s", it.status.name) }))
                    .put("notes", JSONArray(s.notes)),
            )
        }
        file.writeText(root.toString())
    }

    private fun load(): Map<String, ReportSection> = runCatching {
        if (!file.exists()) return@runCatching emptyMap()
        val root = JSONObject(file.readText())
        root.keys().asSequence().associateWith { key ->
            val o = root.getJSONObject(key)
            val rows = o.getJSONArray("rows").let { a ->
                (0 until a.length()).map { i ->
                    val r = a.getJSONObject(i)
                    Row(r.getString("k"), r.getString("v"), runCatching { Status.valueOf(r.getString("s")) }.getOrDefault(Status.INFO))
                }
            }
            val notes = o.getJSONArray("notes").let { a -> (0 until a.length()).map { a.getString(it) } }
            ReportSection(o.getString("title"), rows, notes)
        }
    }.getOrDefault(emptyMap())
}

object ResultKeys {
    const val BT_SCAN = "bt_scan"
    const val GPS = "gps"
    const val ROOT = "root"
    const val PERF_LIGHT = "perf_light"
    const val PERF_HEAVY = "perf_heavy"
}

/** Datos que deben sobrevivir reinicios (el monitor de la llave los necesita). */
class DiagPrefs(context: Context) {
    private val sp = context.getSharedPreferences("diag", Context.MODE_PRIVATE)

    var rootOk: Boolean
        get() = sp.getBoolean("root_ok", false)
        set(v) = sp.edit().putBoolean("root_ok", v).apply()

    /** Se pone en true cuando compilas la app en modo AOT desde la prueba de fluidez. */
    var aotCompiled: Boolean
        get() = sp.getBoolean("aot", false)
        set(v) = sp.edit().putBoolean("aot", v).apply()

    var accMonitorEnabled: Boolean
        get() = sp.getBoolean("acc_enabled", false)
        set(v) = sp.edit().putBoolean("acc_enabled", v).apply()

    val lastHeartbeatWall: Long get() = sp.getLong("hb_wall", 0L)
    val lastHeartbeatElapsed: Long get() = sp.getLong("hb_elapsed", 0L)

    fun saveHeartbeat(wall: Long, elapsed: Long) {
        sp.edit().putLong("hb_wall", wall).putLong("hb_elapsed", elapsed).apply()
    }

    fun clearHeartbeat() {
        sp.edit().remove("hb_wall").remove("hb_elapsed").apply()
    }

    var knownActions: Set<String>
        get() = sp.getStringSet("known_actions", emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet("known_actions", v).apply()
}
