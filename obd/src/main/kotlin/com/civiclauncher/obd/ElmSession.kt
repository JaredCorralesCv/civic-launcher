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

/**
 * Habla con un ELM327: lo configura y hace consultas una por una (el ELM no acepta dos a la vez).
 * El motor completo con prioridades y reconexión llega en la Fase 3; aquí está la base.
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

    suspend fun query(spec: PidSpec): ObdResult = lock.withLock {
        ElmParser.parsePid(transport.send(spec.command), spec)
    }

    suspend fun batteryVoltage(): ObdResult = lock.withLock {
        ElmParser.parseVoltage(transport.send("ATRV"))
    }

    suspend fun stop() = lock.withLock { transport.close() }

    companion object {
        /**
         * ATE0 sin eco · ATL0 sin saltos de línea extra · ATS0 sin espacios · ATH0 sin encabezados ·
         * ATSP0 protocolo automático (tu Civic 2008 usa CAN 11 bits a 500 kbps, ATSP6).
         */
        val INIT_COMMANDS = listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATSP0")
    }
}
