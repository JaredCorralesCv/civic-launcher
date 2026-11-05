package com.civiclauncher.obd

import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Adaptador ELM327 falso que simula un Civic manejando en ciudad: acelera, frena, se detiene en el
 * semáforo y el motor se va calentando. Sirve para programar y probar sin el carro.
 *
 * [clockMs] permite controlar el tiempo en las pruebas. [ignitionOn] simula quitar la llave:
 * el adaptador sigue contestando (y da el voltaje de la batería) pero el carro ya no.
 */
class SimulatedElm(
    private val latencyMs: Long = 40,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val ignitionOn: () -> Boolean = { true },
    /** Contesta varios PIDs en una sola pregunta (como un carro CAN). Falso = adaptador o carro viejo. */
    private val multiPid: Boolean = true,
    /** Entiende el "1" al final para responder rápido (ELM327 v1.3+). Falso = clon viejo. */
    private val fastReply: Boolean = true,
) : ObdTransport {
    private val startedAt = clockMs()
    private var open = false

    override suspend fun open() {
        open = true
    }

    override suspend fun close() {
        open = false
    }

    override suspend fun send(command: String, timeoutMs: Long): String {
        check(open) { "El adaptador simulado está cerrado" }
        if (latencyMs > 0) delay(latencyMs)
        val cmd = command.trim().uppercase().replace(" ", "")
        return reply(cmd) + "\r\r>"
    }

    private fun reply(cmd: String): String {
        val on = ignitionOn()
        if (cmd == "ATZ") return "\rELM327 v1.5 (simulado)"
        if (cmd == "ATRV") return "%.1fV".format(Locale.US, if (on && state().rpm > 0) 14.1 else 12.4)
        if (cmd == "ATDPN") return "A6"
        if (cmd.startsWith("AT")) return "OK"
        if (!on && (cmd.startsWith("0") )) return "SEARCHING...\rUNABLE TO CONNECT"
        when (cmd) {
            // Códigos de falla guardados (CAN: 43 + cantidad + pares). Un sensor de oxígeno lento.
            "03" -> return if (codes.isEmpty()) "4300" else "43%02X".format(codes.size) + codes.joinToString("")
            "07" -> return "4700"
            "04" -> { codes.clear(); return "44" }
            // VIN en varias tramas, como lo manda un carro CAN real.
            "0902" -> return "014\r0:490201314847\r1:46413136353838\r2:4C303030313233"
        }
        if (!cmd.startsWith("01")) return "?"
        // "010C1": la cantidad de respuestas esperadas al final (número impar de cifras).
        var body = cmd.substring(2)
        if (body.length % 2 == 1) {
            if (!fastReply) return "?"
            body = body.dropLast(1)
        }
        val pids = body.chunked(2).map { it.toIntOrNull(16) ?: return "?" }
        if (pids.isEmpty() || pids.size > 6) return "?"
        if (pids.size > 1) {
            if (!multiPid) return "NO DATA"
            // Cada PID que exista: su número y sus datos. Los que no existen simplemente no vienen.
            val parts = pids.mapNotNull { p -> single(p)?.removePrefix("41") }
            return if (parts.isEmpty()) "NO DATA" else "41" + parts.joinToString("")
        }
        return single(pids[0]) ?: "NO DATA"
    }

    /** Respuesta a un solo PID ("410C1AF8"), o null si este "carro" no lo tiene. */
    private fun single(pid: Int): String? {
        if (pid == 0x00 || pid == 0x20 || pid == 0x40) return "41%02X".format(pid) + supportedMask(pid)
        val s = state()
        val word = { v: Int -> listOf(v shr 8 and 0xFF, v and 0xFF) }
        val data: List<Int> = when (pid) {
            // Foco apagado, 1 código guardado (no enciende el foco, como pasa con un P0133).
            0x01 -> listOf(codes.size and 0x7F, 0x07, 0xE5, 0x00)
            0x03 -> listOf(if (s.coolant < 60) 1 else if (s.throttle < 1 && s.speed > 20) 4 else 2, 0)
            0x04 -> listOf(pct(s.load))
            0x05 -> listOf((s.coolant + 40).roundToInt())
            0x06 -> listOf(128 + 3)
            0x07 -> listOf(128 - 2)
            0x0B -> listOf(s.map.roundToInt())
            0x0C -> word((s.rpm * 4).roundToInt())
            0x0D -> listOf(s.speed.roundToInt())
            0x0E -> listOf(((12 + 64) * 2))
            0x0F -> listOf(30 + 40)
            0x11 -> listOf(pct(s.throttle))
            0x14 -> listOf((0.45 + 0.4 * sin(clockMs() / 300.0)).times(200).roundToInt(), 0xFF)
            0x15 -> listOf((0.70 * 200).roundToInt(), 0xFF)
            0x1F -> word(((clockMs() - startedAt) / 1000).toInt())
            0x21 -> word(0)
            0x2E -> listOf(pct(if (s.coolant > 70) 18.0 else 0.0))
            0x31 -> word(1_284)
            0x33 -> listOf(101)
            0x42 -> word(14_100)
            0x45 -> listOf(pct(s.throttle * 0.9))
            0x49 -> listOf(pct(s.throttle + 15))
            0x4E -> word(2_210)
            else -> return null // como un Civic real: no tiene MAF, nivel de gasolina, aceite, etc.
        }
        return "41%02X".format(pid) + data.joinToString("") { "%02X".format(it.coerceIn(0, 255)) }
    }

    /** Códigos guardados: un P0133 (sensor de oxígeno lento). Se borra con el modo 04. */
    private val codes = mutableListOf("0133")

    /** Bits de "PIDs soportados" a partir de la lista que este simulador sabe contestar. */
    private fun supportedMask(base: Int): String {
        var bits = 0L
        val withNext = IMPLEMENTED + listOf(0x20, 0x40)
        for (pid in withNext) {
            val i = pid - base - 1
            if (i in 0..31) bits = bits or (1L shl (31 - i))
        }
        return "%08X".format(bits)
    }

    private fun pct(v: Double) = (v * 255 / 100).roundToInt()

    data class State(val speed: Double, val rpm: Double, val throttle: Double, val load: Double, val coolant: Double) {
        /** Presión del múltiple: ~30 kPa en ralentí, sube con el acelerador (motor atmosférico). */
        val map: Double get() = 28 + throttle * 0.9
    }

    /** Ciclo de 60 s: 0–40 s manejando (ondas suaves), 40–60 s detenido en el semáforo. */
    fun state(): State {
        val t = (clockMs() - startedAt) / 1000.0
        val phase = t % 60
        val driving = phase < 40
        val speed = if (driving) max(0.0, 45 + 25 * sin(phase / 40 * 2 * PI) * sin(phase / 7)) else 0.0
        val throttle = if (driving) 15 + speed / 3 else 0.0
        val gearRatio = when {
            speed < 20 -> 110.0
            speed < 40 -> 65.0
            speed < 60 -> 45.0
            else -> 35.0
        }
        val rpm = if (driving) max(800.0, speed * gearRatio) else 750.0
        // Se calienta de 25 °C a 90 °C en los primeros 5 minutos.
        val coolant = 25 + 65 * (t / 300).coerceAtMost(1.0)
        return State(speed, rpm, throttle, 20 + throttle, coolant)
    }

    private companion object {
        /** Lo que tendría un Civic 2008 (sin MAF: usa sensor MAP). */
        val IMPLEMENTED = listOf(
            0x01, 0x03, 0x04, 0x05, 0x06, 0x07, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x11, 0x14, 0x15, 0x1F,
            0x21, 0x2E, 0x31, 0x33, 0x42, 0x45, 0x49, 0x4E,
        )
    }
}
