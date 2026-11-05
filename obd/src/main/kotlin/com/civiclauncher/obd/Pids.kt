package com.civiclauncher.obd

/** Para agrupar los datos en la pantalla de sensores. */
enum class PidGroup(val label: String) {
    ENGINE("Motor"),
    AIR("Aire y admisión"),
    FUEL("Combustible"),
    TEMPS("Temperaturas"),
    ELECTRIC("Eléctrico"),
    EMISSIONS("Emisiones"),
    STATUS("Estado"),
}

/**
 * Un dato estándar de OBD2 (modo 01). [decode] recibe los bytes de datos (A, B, C...) ya separados.
 * Fórmulas: SAE J1979 / ISO 15031-5. [decimals] dice cuántos decimales mostrar.
 */
data class PidSpec(
    val pid: Int,
    val name: String,
    val unit: String,
    val bytes: Int,
    val group: PidGroup = PidGroup.ENGINE,
    val decimals: Int = 0,
    val decode: (IntArray) -> Double,
) {
    /** Comando para el ELM327, por ejemplo "010C". */
    val command: String get() = "01%02X".format(pid)

    /** El valor listo para leer: "2,450" o "13.9". */
    fun format(value: Double): String = if (decimals == 0) "%,d".format(Math.round(value)) else "%.${decimals}f".format(value)
}

object Pids {
    private fun word(b: IntArray) = b[0] * 256 + b[1]

    // ---- Estado
    /** Bit 7 de A = foco "Check Engine" encendido; bits 0-6 = cuántos códigos de falla hay. */
    val MONITOR_STATUS = PidSpec(0x01, "Estado de diagnóstico", "", 4, PidGroup.STATUS) { it[0].toDouble() }
    /** 1 lazo abierto (frío) · 2 lazo cerrado (normal) · 4 lazo abierto (carga) · 8/16 falla. */
    val FUEL_SYSTEM = PidSpec(0x03, "Sistema de combustible", "", 2, PidGroup.FUEL) { it[0].toDouble() }
    val RUN_TIME = PidSpec(0x1F, "Tiempo desde el arranque", "s", 2, PidGroup.STATUS) { word(it).toDouble() }
    val DIST_WITH_MIL = PidSpec(0x21, "Distancia con el foco encendido", "km", 2, PidGroup.STATUS) { word(it).toDouble() }
    val DIST_SINCE_CLEAR = PidSpec(0x31, "Distancia desde que se borraron códigos", "km", 2, PidGroup.STATUS) { word(it).toDouble() }
    val TIME_WITH_MIL = PidSpec(0x4D, "Tiempo con el foco encendido", "min", 2, PidGroup.STATUS) { word(it).toDouble() }
    val TIME_SINCE_CLEAR = PidSpec(0x4E, "Tiempo desde que se borraron códigos", "min", 2, PidGroup.STATUS) { word(it).toDouble() }
    val ODOMETER = PidSpec(0xA6, "Odómetro", "km", 4, PidGroup.STATUS, 1) { ((it[0].toLong() shl 24) + (it[1] shl 16) + (it[2] shl 8) + it[3]) / 10.0 }

    // ---- Motor
    val ENGINE_LOAD = PidSpec(0x04, "Carga del motor", "%", 1) { it[0] * 100.0 / 255 }
    val ABS_LOAD = PidSpec(0x43, "Carga absoluta", "%", 2) { word(it) * 100.0 / 255 }
    val RPM = PidSpec(0x0C, "RPM", "rpm", 2) { word(it) / 4.0 }
    val SPEED = PidSpec(0x0D, "Velocidad", "km/h", 1) { it[0].toDouble() }
    val TIMING_ADVANCE = PidSpec(0x0E, "Avance de encendido", "°", 1, decimals = 1) { it[0] / 2.0 - 64 }
    val THROTTLE = PidSpec(0x11, "Acelerador", "%", 1) { it[0] * 100.0 / 255 }
    val REL_THROTTLE = PidSpec(0x45, "Acelerador relativo", "%", 1) { it[0] * 100.0 / 255 }
    val PEDAL_D = PidSpec(0x49, "Pedal del acelerador", "%", 1) { it[0] * 100.0 / 255 }
    val COMMANDED_THROTTLE = PidSpec(0x4C, "Acelerador ordenado", "%", 1) { it[0] * 100.0 / 255 }

    // ---- Aire
    val INTAKE_MAP = PidSpec(0x0B, "Presión del múltiple", "kPa", 1, PidGroup.AIR) { it[0].toDouble() }
    val MAF = PidSpec(0x10, "Flujo de aire (MAF)", "g/s", 2, PidGroup.AIR, 1) { word(it) / 100.0 }
    val BAROMETRIC = PidSpec(0x33, "Presión barométrica", "kPa", 1, PidGroup.AIR) { it[0].toDouble() }

    // ---- Combustible
    val SHORT_FUEL_TRIM_1 = PidSpec(0x06, "Ajuste de combustible corto", "%", 1, PidGroup.FUEL, 1) { (it[0] - 128) * 100.0 / 128 }
    val LONG_FUEL_TRIM_1 = PidSpec(0x07, "Ajuste de combustible largo", "%", 1, PidGroup.FUEL, 1) { (it[0] - 128) * 100.0 / 128 }
    val FUEL_PRESSURE = PidSpec(0x0A, "Presión de combustible", "kPa", 1, PidGroup.FUEL) { it[0] * 3.0 }
    val FUEL_LEVEL = PidSpec(0x2F, "Nivel de gasolina", "%", 1, PidGroup.FUEL) { it[0] * 100.0 / 255 }
    val COMMANDED_AFR = PidSpec(0x44, "Mezcla ordenada (lambda)", "λ", 2, PidGroup.FUEL, 2) { word(it) * 2.0 / 65536 }
    val FUEL_RATE = PidSpec(0x5E, "Consumo de combustible", "L/h", 2, PidGroup.FUEL, 1) { word(it) / 20.0 }

    // ---- Temperaturas
    val COOLANT_TEMP = PidSpec(0x05, "Temperatura del motor", "°C", 1, PidGroup.TEMPS) { it[0] - 40.0 }
    val INTAKE_AIR_TEMP = PidSpec(0x0F, "Temperatura de admisión", "°C", 1, PidGroup.TEMPS) { it[0] - 40.0 }
    val AMBIENT_TEMP = PidSpec(0x46, "Temperatura ambiente", "°C", 1, PidGroup.TEMPS) { it[0] - 40.0 }
    val CATALYST_TEMP = PidSpec(0x3C, "Temperatura del catalizador", "°C", 2, PidGroup.TEMPS) { word(it) / 10.0 - 40 }
    val OIL_TEMP = PidSpec(0x5C, "Temperatura del aceite", "°C", 1, PidGroup.TEMPS) { it[0] - 40.0 }

    // ---- Eléctrico
    val MODULE_VOLTAGE = PidSpec(0x42, "Voltaje de la computadora", "V", 2, PidGroup.ELECTRIC, 1) { word(it) / 1000.0 }

    // ---- Emisiones
    val O2_B1S1 = PidSpec(0x14, "Sensor de oxígeno 1 (antes)", "V", 2, PidGroup.EMISSIONS, 2) { it[0] / 200.0 }
    val O2_B1S2 = PidSpec(0x15, "Sensor de oxígeno 2 (después)", "V", 2, PidGroup.EMISSIONS, 2) { it[0] / 200.0 }
    val EVAP_PURGE = PidSpec(0x2E, "Purga del canister", "%", 1, PidGroup.EMISSIONS) { it[0] * 100.0 / 255 }

    val all: List<PidSpec> = listOf(
        MONITOR_STATUS, FUEL_SYSTEM, RUN_TIME, DIST_WITH_MIL, DIST_SINCE_CLEAR, TIME_WITH_MIL, TIME_SINCE_CLEAR, ODOMETER,
        ENGINE_LOAD, ABS_LOAD, RPM, SPEED, TIMING_ADVANCE, THROTTLE, REL_THROTTLE, PEDAL_D, COMMANDED_THROTTLE,
        INTAKE_MAP, MAF, BAROMETRIC,
        SHORT_FUEL_TRIM_1, LONG_FUEL_TRIM_1, FUEL_PRESSURE, FUEL_LEVEL, COMMANDED_AFR, FUEL_RATE,
        COOLANT_TEMP, INTAKE_AIR_TEMP, AMBIENT_TEMP, CATALYST_TEMP, OIL_TEMP,
        MODULE_VOLTAGE,
        O2_B1S1, O2_B1S2, EVAP_PURGE,
    )

    fun byPid(pid: Int): PidSpec? = all.firstOrNull { it.pid == pid }

    /** Texto del estado del sistema de combustible (PID 03). */
    fun fuelSystemText(v: Double?): String? = when (v?.toInt()) {
        null -> null
        1 -> "Calentando (lazo abierto)"
        2 -> "Normal (lazo cerrado)"
        4 -> "Acelerando o frenando con motor"
        8 -> "Lazo abierto por falla"
        16 -> "Lazo cerrado con falla de sensor"
        else -> "Código ${v.toInt()}"
    }
}
