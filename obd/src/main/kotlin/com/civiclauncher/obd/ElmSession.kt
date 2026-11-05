package com.civiclauncher.obd

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Canal de bytes hacia el adaptador. La app de Android implementa esto con Bluetooth SPP (y USB si
 * hiciera falta); las pruebas y el modo demo usan [SimulatedElm].
 */
interface ObdTransport {
    suspend fun open()
    /** Manda un comando (sin "\r") y devuelve todo lo recibido hasta el ">" del ELM327. */
    suspend fun send(command: String, timeoutMs: Long = 3_000): String
    suspend fun close()
}

/** Lo que se sabe del carro después de la primera consulta. */
data class Detection(val protocol: String, val supported: Set<Int>)

/**
 * Habla con un ELM327: lo configura y hace consultas una por una (el ELM no acepta dos a la vez).
 * [ObdEngine] decide qué preguntar y cuándo.
 */
class ElmSession(private val transport: ObdTransport) {
    private val lock = Mutex()

    /** Identificación del adaptador (respuesta a ATZ), disponible después de [start]. */
    var adapterId: String = ""
        private set

    suspend fun start() = lock.withLock {
        transport.open()
        // ATZ reinicia el adaptador y tarda más que los demás comandos.
        adapterId = ElmParser.cleanLines(transport.send("ATZ", timeoutMs = 5_000)).lastOrNull().orEmpty()
        INIT_COMMANDS.forEach { transport.send(it) }
    }

    /**
     * Primera consulta al carro: el ELM busca el protocolo (puede tardar varios segundos) y
     * aprendemos qué datos existen. Devuelve null si el carro no contesta (llave quitada).
     */
    suspend fun detect(): Detection? = lock.withLock {
        val first = transport.send("0100", timeoutMs = 12_000)
        if (ElmParser.isCarSilent(first)) return@withLock null
        val supported = ElmParser.parseSupported(first, 0x00)?.toMutableSet() ?: return@withLock null
        // Los PIDs 0x20 y 0x40 indican si hay otra tanda de PIDs que preguntar.
        for (base in listOf(0x20, 0x40)) {
            if (base !in supported) break
            ElmParser.parseSupported(transport.send("01%02X".format(base)), base)?.let { supported += it } ?: break
        }
        Detection(ElmParser.protocolName(transport.send("ATDPN")), supported)
    }

    suspend fun query(spec: PidSpec): ObdResult = lock.withLock {
        ElmParser.parsePid(transport.send(spec.command), spec)
    }

    suspend fun batteryVoltage(): ObdResult = lock.withLock {
        ElmParser.parseVoltage(transport.send("ATRV"))
    }

    /** Códigos de falla: modo 03 guardados, 07 pendientes, 0A permanentes. null = no se pudo leer. */
    suspend fun readDtcs(mode: Int, can: Boolean): List<String>? = lock.withLock {
        ElmParser.parseDtcs(transport.send("%02X".format(mode), timeoutMs = 5_000), mode + 0x40, can)
    }

    /**
     * Borra los códigos y apaga el foco "Check Engine" (modo 04). También borra los monitores de
     * emisiones: el carro tarda unos días de manejo en volver a revisarlos. Solo con motor apagado y
     * llave en ON es lo recomendado. Devuelve true si el carro confirmó (respuesta 44).
     */
    suspend fun clearDtcs(): Boolean = lock.withLock {
        ElmParser.cleanLines(transport.send("04", timeoutMs = 5_000)).any { it.replace(" ", "").startsWith("44") }
    }

    /** Número de serie del carro (VIN). null si el carro no lo da (algunos modelos viejos). */
    suspend fun readVin(): String? = lock.withLock {
        ElmParser.parseVin(transport.send("0902", timeoutMs = 5_000))
    }

    suspend fun stop() = lock.withLock { transport.close() }

    companion object {
        /**
         * ATE0 sin eco · ATL0 sin saltos de línea extra · ATS0 sin espacios · ATH0 sin encabezados ·
         * ATAT1 tiempos de espera adaptativos (más rápido) · ATSP0 protocolo automático
         * (tu Civic 2008 debería salir como CAN 11 bits 500 kbps).
         */
        val INIT_COMMANDS = listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATSP0")
    }
}
