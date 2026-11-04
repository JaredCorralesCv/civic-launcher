package com.civiclauncher.obd

/**
 * Un dato estándar de OBD2 (modo 01). [decode] recibe los bytes de datos (A, B, C...) ya separados.
 * Fórmulas: SAE J1979 / ISO 15031-5.
 */
data class PidSpec(
    val pid: Int,
    val name: String,
    val unit: String,
    val bytes: Int,
    val decode: (IntArray) -> Double,
) {
    /** Comando para el ELM327, por ejemplo "010C". */
    val command: String get() = "01%02X".format(pid)
}

object Pids {
    val ENGINE_LOAD = PidSpec(0x04, "Carga del motor", "%", 1) { it[0] * 100.0 / 255 }
    val COOLANT_TEMP = PidSpec(0x05, "Temperatura del motor", "°C", 1) { it[0] - 40.0 }
    val SHORT_FUEL_TRIM_1 = PidSpec(0x06, "Ajuste de combustible corto", "%", 1) { (it[0] - 128) * 100.0 / 128 }
    val LONG_FUEL_TRIM_1 = PidSpec(0x07, "Ajuste de combustible largo", "%", 1) { (it[0] - 128) * 100.0 / 128 }
    val INTAKE_MAP = PidSpec(0x0B, "Presión del múltiple", "kPa", 1) { it[0].toDouble() }
    val RPM = PidSpec(0x0C, "RPM", "rpm", 2) { (it[0] * 256 + it[1]) / 4.0 }
    val SPEED = PidSpec(0x0D, "Velocidad", "km/h", 1) { it[0].toDouble() }
    val TIMING_ADVANCE = PidSpec(0x0E, "Avance de encendido", "°", 1) { it[0] / 2.0 - 64 }
    val INTAKE_AIR_TEMP = PidSpec(0x0F, "Temperatura de admisión", "°C", 1) { it[0] - 40.0 }
    val MAF = PidSpec(0x10, "Flujo de aire (MAF)", "g/s", 2) { (it[0] * 256 + it[1]) / 100.0 }
    val THROTTLE = PidSpec(0x11, "Acelerador", "%", 1) { it[0] * 100.0 / 255 }
    val FUEL_LEVEL = PidSpec(0x2F, "Nivel de gasolina", "%", 1) { it[0] * 100.0 / 255 }
    val BAROMETRIC = PidSpec(0x33, "Presión barométrica", "kPa", 1) { it[0].toDouble() }
    val MODULE_VOLTAGE = PidSpec(0x42, "Voltaje del módulo", "V", 2) { (it[0] * 256 + it[1]) / 1000.0 }
    val AMBIENT_TEMP = PidSpec(0x46, "Temperatura ambiente", "°C", 1) { it[0] - 40.0 }

    val all: List<PidSpec> = listOf(
        ENGINE_LOAD, COOLANT_TEMP, SHORT_FUEL_TRIM_1, LONG_FUEL_TRIM_1, INTAKE_MAP, RPM, SPEED,
        TIMING_ADVANCE, INTAKE_AIR_TEMP, MAF, THROTTLE, FUEL_LEVEL, BAROMETRIC, MODULE_VOLTAGE, AMBIENT_TEMP,
    )

    fun byPid(pid: Int): PidSpec? = all.firstOrNull { it.pid == pid }
}
