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

    /**
     * Respuesta a 0100 / 0120 / 0140: 4 bytes donde cada bit dice si existe un PID.
     * Bit más alto del primer byte = PID base+1. Devuelve null si el carro no respondió.
     */
    fun parseSupported(raw: String, base: Int): Set<Int>? {
        val lines = cleanLines(raw)
        if (lines.any { it == "NO DATA" } || ERRORS.any { e -> lines.any { it.startsWith(e) } }) return null
        val expected = "41%02X".format(base)
        for (line in lines) {
            val hex = line.replace(" ", "")
            val idx = hex.indexOf(expected)
            if (idx < 0 || hex.length < idx + expected.length + 8) continue
            val bits = hex.substring(idx + expected.length, idx + expected.length + 8).toLongOrNull(16) ?: continue
            return (0 until 32).filter { i -> (bits shr (31 - i)) and 1L == 1L }.map { base + it + 1 }.toSet()
        }
        return null
    }

    /** Si el carro no contesta (llave quitada o computadora dormida), el ELM dice alguna de estas. */
    fun isCarSilent(raw: String): Boolean {
        val lines = cleanLines(raw)
        return lines.isEmpty() || lines.any { l ->
            l == "NO DATA" || l.startsWith("UNABLE TO CONNECT") || l.startsWith("CAN ERROR") ||
                l.startsWith("BUS INIT") && l.contains("ERROR") || l.startsWith("BUS ERROR") || l.startsWith("STOPPED")
        }
    }

    /** ATDPN: número del protocolo detectado. "A6" = automático, CAN 11 bits 500 kbps. */
    fun protocolName(raw: String): String {
        val code = cleanLines(raw).firstOrNull()?.removePrefix("A")?.trim() ?: return "desconocido"
        return when (code) {
            "1" -> "SAE J1850 PWM"
            "2" -> "SAE J1850 VPW"
            "3" -> "ISO 9141-2"
            "4" -> "ISO 14230 KWP (5 baud)"
            "5" -> "ISO 14230 KWP (rápido)"
            "6" -> "CAN 11 bits 500 kbps"
            "7" -> "CAN 29 bits 500 kbps"
            "8" -> "CAN 11 bits 250 kbps"
            "9" -> "CAN 29 bits 250 kbps"
            else -> "protocolo $code"
        }
    }

    // ------------------------------------------------------------------ Respuestas largas (CAN)

    /**
     * Junta los bytes de datos de una respuesta que puede venir en varias líneas.
     *
     * Con encabezados apagados (ATH0) y CAN, una respuesta larga (ISO-TP) se ve así:
     * ```
     * 014            ← largo total en hex (opcional)
     * 0:490201314847 ← trama 0
     * 1:434D...      ← trama 1
     * ```
     * Una corta viene en una sola línea. Devuelve los bytes desde el byte de respuesta del modo
     * ([responseMode], p. ej. 0x43) en adelante, o null si no se encontró.
     */
    fun payload(raw: String, responseMode: Int): IntArray? {
        val lines = cleanLines(raw)
        if (lines.isEmpty() || lines.any { it == "NO DATA" }) return null
        if (ERRORS.any { e -> lines.any { it.startsWith(e) } }) return null
        val multi = lines.any { FRAME.matches(it) }
        val hex = if (multi) {
            lines.filter { FRAME.matches(it) }
                .sortedBy { it.substringBefore(':').trim().toInt(16) }
                .joinToString("") { it.substringAfter(':').replace(" ", "") }
        } else {
            lines.joinToString("") { it.replace(" ", "") }
        }
        val start = hex.indexOf("%02X".format(responseMode))
        if (start < 0 || start % 2 != 0) return null
        val body = hex.substring(start)
        return IntArray(body.length / 2) { i -> body.substring(i * 2, i * 2 + 2).toIntOrNull(16) ?: return null }
    }

    private val FRAME = Regex("^[0-9A-F]{1,2}:.*")

    /**
     * Códigos de falla del modo 03 (guardados), 07 (pendientes) o 0A (permanentes).
     * En CAN el primer byte después del modo es la cantidad de códigos; en los protocolos viejos no
     * hay cantidad y vienen de 3 en 3 rellenos con ceros. [can] dice cuál es.
     * Devuelve null si no se pudo leer; lista vacía = sin códigos.
     */
    fun parseDtcs(raw: String, responseMode: Int, can: Boolean): List<String>? {
        if (cleanLines(raw).any { it == "NO DATA" }) return emptyList()
        val bytes = payload(raw, responseMode) ?: return null
        val pairs = if (can) {
            val count = bytes.getOrNull(1) ?: return emptyList()
            (0 until count).mapNotNull { i ->
                val a = bytes.getOrNull(2 + i * 2) ?: return@mapNotNull null
                val b = bytes.getOrNull(3 + i * 2) ?: return@mapNotNull null
                a to b
            }
        } else {
            (1 until bytes.size - 1 step 2).map { bytes[it] to bytes[it + 1] }
        }
        return pairs.filter { (a, b) -> a != 0 || b != 0 }.map { (a, b) -> dtcName(a, b) }.distinct()
    }

    /**
     * Respuesta a una pregunta de varios PIDs a la vez ("010C0D11"): 41 · PID · datos · PID · datos…
     * Si el carro no tiene alguno, simplemente no viene. Devuelve null si la respuesta no se pudo
     * recorrer completa (entonces conviene volver a preguntar de uno en uno).
     */
    fun parseMulti(raw: String, specs: List<PidSpec>): Map<Int, Double>? {
        if (cleanLines(raw).any { it == "NO DATA" }) return emptyMap()
        val bytes = payload(raw, 0x41) ?: return null
        val byPid = specs.associateBy { it.pid }
        val out = mutableMapOf<Int, Double>()
        var i = 1
        while (i < bytes.size) {
            // Una respuesta larga puede traer más "41" intercalados (una por computadora): se saltan.
            if (bytes[i] == 0x41 && byPid[bytes[i]] == null) { i++; continue }
            val spec = byPid[bytes[i]] ?: return if (out.isEmpty()) null else out
            // Datos incompletos (respuesta cortada): lo que ya se leyó sí sirve.
            if (i + spec.bytes >= bytes.size) return if (out.isEmpty()) null else out
            val data = IntArray(spec.bytes) { k -> bytes[i + 1 + k] }
            out[spec.pid] = spec.decode(data)
            i += 1 + spec.bytes
        }
        return out
    }

    /** Dos bytes → "P0420". Los 2 bits altos dicen el sistema (P motor, C chasis, B carrocería, U red). */
    fun dtcName(a: Int, b: Int): String {
        val letter = "PCBU"[(a shr 6) and 0x3]
        val first = (a shr 4) and 0x3
        return "%c%d%X%02X".format(letter, first, a and 0xF, b)
    }

    /** Número de serie del carro (VIN) del modo 09 PID 02: 17 letras y números. */
    fun parseVin(raw: String): String? {
        val bytes = payload(raw, 0x49) ?: return null
        // 49 02 [01] luego 17 caracteres ASCII. Algunos carros no mandan el byte de cantidad.
        if (bytes.size < 3 || bytes[1] != 0x02) return null
        val text = bytes.drop(2).filter { it in 0x20..0x7E }.map { it.toChar() }.joinToString("")
        val vin = text.filter { it.isLetterOrDigit() }.takeLast(17)
        return vin.takeIf { it.length == 17 }
    }

    /** Respuesta de ATRV, por ejemplo "12.6V". Es el voltaje en el conector OBD (la batería). */
    fun parseVoltage(raw: String): ObdResult {
        val line = cleanLines(raw).firstOrNull() ?: return ObdResult.Error("Respuesta vacía", raw)
        val v = line.removeSuffix("V").trim().toDoubleOrNull() ?: return ObdResult.Error("Voltaje no válido", raw)
        return ObdResult.Value(v, raw)
    }
}
