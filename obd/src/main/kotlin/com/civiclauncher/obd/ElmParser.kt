package com.civiclauncher.obd

/** Resultado de una consulta al adaptador. */
sealed interface ObdResult {
    data class Value(val value: Double, val raw: String) : ObdResult
    /** El carro respondió que no tiene ese dato (normal en algunos PIDs). */
    data object NoData : ObdResult
    /** El adaptador no pudo hablar con el carro, o la respuesta no se entendió. */
    data class Error(val message: String, val raw: String) : ObdResult
}

/**
 * Convierte el texto que devuelve un ELM327 en datos.
 * Supone ecos y encabezados apagados (ATE0, ATH0), que es como lo configura [ElmSession].
 */
object ElmParser {

    private val NOISE = listOf("SEARCHING...", "BUS INIT:", "BUS INIT: ...OK", "OK")
    private val ERRORS = listOf(
        "UNABLE TO CONNECT", "CAN ERROR", "BUS ERROR", "BUS BUSY", "FB ERROR", "DATA ERROR",
        "BUFFER FULL", "STOPPED", "ERROR", "?",
    )

    /** Quita el ">" final, eco, espacios y líneas de "SEARCHING...". Devuelve líneas útiles en mayúsculas. */
    fun cleanLines(raw: String): List<String> =
        raw.replace(">", "")
            .split('\r', '\n')
            .map { it.trim().uppercase() }
            .filter { it.isNotEmpty() && NOISE.none { n -> it == n || it.startsWith("SEARCHING") } }

    /** Interpreta la respuesta a un PID del modo 01. Si responden varias computadoras, usa la primera válida. */
    fun parsePid(raw: String, spec: PidSpec): ObdResult {
        val lines = cleanLines(raw)
        if (lines.isEmpty()) return ObdResult.Error("Respuesta vacía", raw)
        if (lines.any { it == "NO DATA" }) return ObdResult.NoData
        ERRORS.firstOrNull { e -> lines.any { it.startsWith(e) } }?.let { return ObdResult.Error(it, raw) }

        val expected = "41%02X".format(spec.pid)
        for (line in lines) {
            val hex = line.replace(" ", "")
            val idx = hex.indexOf(expected)
            if (idx < 0) continue
            val data = hex.substring(idx + expected.length)
            if (data.length < spec.bytes * 2) continue
            val bytes = IntArray(spec.bytes) { i -> data.substring(i * 2, i * 2 + 2).toIntOrNull(16) ?: return@IntArray -1 }
            if (bytes.any { it < 0 }) continue
            return ObdResult.Value(spec.decode(bytes), raw)
        }
        return ObdResult.Error("No se encontró $expected en la respuesta", raw)
    }

    /** Respuesta de ATRV, por ejemplo "12.6V". Es el voltaje en el conector OBD (la batería). */
    fun parseVoltage(raw: String): ObdResult {
        val line = cleanLines(raw).firstOrNull() ?: return ObdResult.Error("Respuesta vacía", raw)
        val v = line.removeSuffix("V").trim().toDoubleOrNull() ?: return ObdResult.Error("Voltaje no válido", raw)
        return ObdResult.Value(v, raw)
    }
}
