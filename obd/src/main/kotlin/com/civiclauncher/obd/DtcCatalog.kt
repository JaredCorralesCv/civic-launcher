package com.civiclauncher.obd

/** Qué tan urgente es un código, para el color y el consejo. */
enum class DtcSeverity { INFO, WARN, SERIOUS }

data class DtcInfo(val code: String, val description: String, val severity: DtcSeverity, val advice: String)

/**
 * Descripción en español de los códigos de falla más comunes (genéricos SAE y algunos de Honda).
 * Si un código no está, se describe por su sistema (encendido, combustible, emisiones…).
 */
object DtcCatalog {
    private val known: Map<String, Pair<String, DtcSeverity>> = mapOf(
        // Aire y combustible
        "P0100" to ("Sensor de flujo de aire (MAF): falla del circuito" to DtcSeverity.WARN),
        "P0101" to ("Sensor de flujo de aire (MAF): lectura fuera de rango" to DtcSeverity.WARN),
        "P0105" to ("Sensor de presión del múltiple (MAP): falla del circuito" to DtcSeverity.WARN),
        "P0106" to ("Sensor de presión del múltiple (MAP): lectura fuera de rango" to DtcSeverity.WARN),
        "P0107" to ("Sensor de presión del múltiple (MAP): voltaje bajo" to DtcSeverity.WARN),
        "P0108" to ("Sensor de presión del múltiple (MAP): voltaje alto" to DtcSeverity.WARN),
        "P0110" to ("Sensor de temperatura de admisión: falla del circuito" to DtcSeverity.INFO),
        "P0112" to ("Sensor de temperatura de admisión: voltaje bajo" to DtcSeverity.INFO),
        "P0113" to ("Sensor de temperatura de admisión: voltaje alto" to DtcSeverity.INFO),
        "P0115" to ("Sensor de temperatura del motor: falla del circuito" to DtcSeverity.WARN),
        "P0116" to ("Sensor de temperatura del motor: lectura fuera de rango" to DtcSeverity.WARN),
        "P0117" to ("Sensor de temperatura del motor: voltaje bajo" to DtcSeverity.WARN),
        "P0118" to ("Sensor de temperatura del motor: voltaje alto" to DtcSeverity.WARN),
        "P0120" to ("Sensor de posición del acelerador: falla del circuito" to DtcSeverity.SERIOUS),
        "P0122" to ("Sensor de posición del acelerador: voltaje bajo" to DtcSeverity.SERIOUS),
        "P0123" to ("Sensor de posición del acelerador: voltaje alto" to DtcSeverity.SERIOUS),
        "P0125" to ("El motor tarda en calentarse para el control de mezcla" to DtcSeverity.INFO),
        "P0128" to ("Termostato: el motor no alcanza su temperatura" to DtcSeverity.WARN),
        "P0130" to ("Sensor de oxígeno 1: falla del circuito" to DtcSeverity.WARN),
        "P0131" to ("Sensor de oxígeno 1: voltaje bajo" to DtcSeverity.WARN),
        "P0132" to ("Sensor de oxígeno 1: voltaje alto" to DtcSeverity.WARN),
        "P0133" to ("Sensor de oxígeno 1: respuesta lenta" to DtcSeverity.WARN),
        "P0134" to ("Sensor de oxígeno 1: sin actividad" to DtcSeverity.WARN),
        "P0135" to ("Sensor de oxígeno 1: falla del calentador" to DtcSeverity.WARN),
        "P0136" to ("Sensor de oxígeno 2: falla del circuito" to DtcSeverity.INFO),
        "P0137" to ("Sensor de oxígeno 2: voltaje bajo" to DtcSeverity.INFO),
        "P0138" to ("Sensor de oxígeno 2: voltaje alto" to DtcSeverity.INFO),
        "P0139" to ("Sensor de oxígeno 2: respuesta lenta" to DtcSeverity.INFO),
        "P0141" to ("Sensor de oxígeno 2: falla del calentador" to DtcSeverity.INFO),
        "P0171" to ("Mezcla muy pobre (le falta gasolina o entra aire de más)" to DtcSeverity.WARN),
        "P0172" to ("Mezcla muy rica (le sobra gasolina)" to DtcSeverity.WARN),
        // Encendido
        "P0300" to ("Fallas de encendido en varios cilindros" to DtcSeverity.SERIOUS),
        "P0301" to ("Falla de encendido en el cilindro 1" to DtcSeverity.SERIOUS),
        "P0302" to ("Falla de encendido en el cilindro 2" to DtcSeverity.SERIOUS),
        "P0303" to ("Falla de encendido en el cilindro 3" to DtcSeverity.SERIOUS),
        "P0304" to ("Falla de encendido en el cilindro 4" to DtcSeverity.SERIOUS),
        "P0325" to ("Sensor de detonación: falla del circuito" to DtcSeverity.WARN),
        "P0335" to ("Sensor de posición del cigüeñal: falla" to DtcSeverity.SERIOUS),
        "P0340" to ("Sensor de posición del árbol de levas: falla" to DtcSeverity.SERIOUS),
        "P0341" to ("Sensor del árbol de levas: lectura fuera de rango" to DtcSeverity.SERIOUS),
        // Emisiones
        "P0400" to ("Recirculación de gases (EGR): falla" to DtcSeverity.INFO),
        "P0420" to ("Catalizador con eficiencia baja" to DtcSeverity.WARN),
        "P0440" to ("Sistema de vapores de gasolina (EVAP): falla" to DtcSeverity.INFO),
        "P0441" to ("EVAP: flujo de purga incorrecto" to DtcSeverity.INFO),
        "P0442" to ("EVAP: fuga pequeña (revisa el tapón de gasolina)" to DtcSeverity.INFO),
        "P0443" to ("EVAP: válvula de purga, falla del circuito" to DtcSeverity.INFO),
        "P0455" to ("EVAP: fuga grande (revisa el tapón de gasolina)" to DtcSeverity.INFO),
        "P0456" to ("EVAP: fuga muy pequeña (revisa el tapón de gasolina)" to DtcSeverity.INFO),
        "P0457" to ("EVAP: tapón de gasolina flojo o sin poner" to DtcSeverity.INFO),
        // Velocidad, ralentí, eléctrico
        "P0500" to ("Sensor de velocidad del vehículo: falla" to DtcSeverity.WARN),
        "P0505" to ("Control de marcha mínima (ralentí): falla" to DtcSeverity.WARN),
        "P0506" to ("Marcha mínima más baja de lo normal" to DtcSeverity.INFO),
        "P0507" to ("Marcha mínima más alta de lo normal" to DtcSeverity.INFO),
        "P0562" to ("Voltaje del sistema bajo (revisa batería y alternador)" to DtcSeverity.WARN),
        "P0563" to ("Voltaje del sistema alto" to DtcSeverity.WARN),
        "P0600" to ("Falla de comunicación de la computadora" to DtcSeverity.SERIOUS),
        "P0700" to ("La transmisión reporta una falla" to DtcSeverity.SERIOUS),
        "P0715" to ("Sensor de velocidad de entrada de la transmisión" to DtcSeverity.SERIOUS),
        "P0720" to ("Sensor de velocidad de salida de la transmisión" to DtcSeverity.SERIOUS),
        "P0740" to ("Embrague del convertidor de par: falla" to DtcSeverity.SERIOUS),
        // Honda
        "P1166" to ("Sensor de mezcla aire/combustible: falla del calentador (Honda)" to DtcSeverity.WARN),
        "P1259" to ("Sistema VTEC: falla (Honda)" to DtcSeverity.WARN),
        "P1456" to ("EVAP: fuga en el lado del tanque (Honda)" to DtcSeverity.INFO),
        "P1457" to ("EVAP: fuga en el lado del canister (Honda)" to DtcSeverity.INFO),
        "P2183" to ("Sensor 2 de temperatura del motor: lectura fuera de rango" to DtcSeverity.WARN),
        "P2646" to ("Sistema de balancines (VTEC): se queda apagado (Honda)" to DtcSeverity.WARN),
        "P2647" to ("Sistema de balancines (VTEC): se queda encendido (Honda)" to DtcSeverity.WARN),
        "U0001" to ("Red CAN de alta velocidad: falla de comunicación" to DtcSeverity.WARN),
        "U0100" to ("Se perdió la comunicación con la computadora del motor" to DtcSeverity.SERIOUS),
    )

    fun describe(code: String): DtcInfo {
        val hit = known[code.uppercase()]
        val (text, severity) = hit ?: (bySystem(code) to DtcSeverity.WARN)
        return DtcInfo(code.uppercase(), text, severity, advice(severity, code))
    }

    /** Si no lo conocemos, al menos el sistema al que pertenece (por la letra y los dígitos). */
    private fun bySystem(code: String): String {
        val c = code.uppercase()
        val manufacturer = c.getOrNull(1) == '1' || c.getOrNull(1) == '3'
        val prefix = if (manufacturer) " (código del fabricante)" else ""
        return when (c.firstOrNull()) {
            'P' -> when (c.getOrNull(2)) {
                '0', '1', '2' -> "Falla en la mezcla de aire y combustible"
                '3' -> "Falla del sistema de encendido"
                '4' -> "Falla en el control de emisiones"
                '5' -> "Falla en velocidad, ralentí o voltaje"
                '6' -> "Falla en la computadora o sus circuitos"
                '7', '8', '9' -> "Falla de la transmisión"
                else -> "Falla del tren motriz"
            } + prefix
            'C' -> "Falla del chasis (frenos ABS, suspensión o dirección)$prefix"
            'B' -> "Falla de la carrocería (bolsas de aire, aire acondicionado, luces)$prefix"
            'U' -> "Falla de comunicación entre computadoras$prefix"
            else -> "Código desconocido"
        }
    }

    private fun advice(severity: DtcSeverity, code: String): String = when {
        code.uppercase().startsWith("P030") -> "Evita acelerar fuerte y llévalo a revisar pronto: puede dañar el catalizador."
        severity == DtcSeverity.SERIOUS -> "Llévalo a revisar pronto."
        severity == DtcSeverity.WARN -> "Puedes manejar, pero conviene revisarlo."
        else -> "No es urgente."
    }
}
