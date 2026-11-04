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
        if (!cmd.startsWith("01") || cmd.length != 4) return "?"
        if (!on) return "SEARCHING...\rUNABLE TO CONNECT"
        val pid = cmd.substring(2).toIntOrNull(16) ?: return "?"
        if (pid == 0x00 || pid == 0x20 || pid == 0x40) return "41%02X".format(pid) + supportedMask(pid)
        val s = state()
        val data: List<Int> = when (pid) {
            0x04 -> listOf(pct(s.load))
            0x05 -> listOf((s.coolant + 40).roundToInt())
            0x06 -> listOf(128 + 3)
            0x07 -> listOf(128 - 2)
            0x0B -> listOf((30 + s.load * 0.7).roundToInt())
            0x0C -> (s.rpm * 4).roundToInt().let { listOf(it shr 8, it and 0xFF) }
            0x0D -> listOf(s.speed.roundToInt())
            0x0E -> listOf(((12 + 64) * 2))
            0x0F -> listOf(30 + 40)
            0x10 -> (s.rpm / 300 * 100).roundToInt().let { listOf(it shr 8, it and 0xFF) }
            0x11 -> listOf(pct(s.throttle))
            0x33 -> listOf(101)
            0x42 -> 14100.let { listOf(it shr 8, it and 0xFF) }
            else -> return "NO DATA" // como un Civic real: no todos los PIDs existen (p. ej. nivel de gasolina)
        }
        return "41%02X".format(pid) + data.joinToString("") { "%02X".format(it.coerceIn(0, 255)) }
    }

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

    data class State(val speed: Double, val rpm: Double, val throttle: Double, val load: Double, val coolant: Double)

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
        val IMPLEMENTED = listOf(0x04, 0x05, 0x06, 0x07, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x33, 0x42)
    }
}
