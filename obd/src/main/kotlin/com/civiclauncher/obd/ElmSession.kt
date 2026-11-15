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

    /**
     * "Respuesta rápida": agregar al final la cantidad de respuestas esperadas ("010C1") hace que el
     * ELM regrese en cuanto contesta la computadora, en vez de esperar por si contesta otra. Con
     * CAN ahorra decenas de milisegundos por pregunta. Si el adaptador no lo entiende ("?"), se apaga.
     */
    var fastReply: Boolean = true
        private set

    /** El carro acepta preguntas de varios PIDs a la vez (hasta 6, solo en CAN). */
    var multiPid: Boolean = false
        private set

    /**
     * [frames]: cuántas tramas trae la respuesta. Con varios PIDs la respuesta ocupa varias tramas, y
     * el ELM se detiene en la cantidad que se le diga: con "1" se perdían todos los datos menos los
     * dos primeros (en tu Civic: el acelerador y la carga nunca llegaban).
     */
    private suspend fun ask(command: String, timeoutMs: Long = 3_000, frames: Int = 1): String {
        if (fastReply) {
            val r = transport.send(command + frames.coerceIn(1, 15).toString(16).uppercase(), timeoutMs)
            if (ElmParser.cleanLines(r).none { it == "?" }) return r
            fastReply = false
        }
        return transport.send(command, timeoutMs)
    }

    suspend fun query(spec: PidSpec): ObdResult = lock.withLock {
        ElmParser.parsePid(ask(spec.command), spec)
    }

    /**
     * Varios PIDs en una sola pregunta (máx. 6). Devuelve null si no se pudo leer así: entonces el
     * motor vuelve a preguntar de uno en uno. Los PIDs que no vienen en la respuesta no existen.
     */
    suspend fun queryMany(specs: List<PidSpec>): Map<Int, Double>? = lock.withLock {
        require(specs.size in 1..MAX_MULTI)
        val command = "01" + specs.joinToString("") { "%02X".format(it.pid) }
        ElmParser.parseMulti(ask(command, frames = framesFor(specs)), specs)
    }

    /**
     * Prueba una vez si el carro y el adaptador contestan varios PIDs juntos. Solo en CAN: en los
     * protocolos viejos (ISO 9141, KWP) no existe. Se prueba con una pregunta que ocupa DOS tramas,
     * como las del sondeo real: RPM y velocidad caben en una, y justo en las de varias tramas es
     * donde los adaptadores baratos cortan la respuesta.
     */
    suspend fun probeMultiPid(can: Boolean, supported: Set<Int> = emptySet()): Boolean {
        multiPid = false
        if (!can) return false
        val wide = PROBE.filter { supported.isEmpty() || it.pid in supported }.take(4)
        val test = if (wide.size >= 4) wide else listOf(Pids.RPM, Pids.SPEED)
        val r = runCatching { queryMany(test) }.getOrNull()
        multiPid = r != null && test.all { r.containsKey(it.pid) }
        return multiPid
    }

    /** Las respuestas múltiples llegaron incompletas aunque la prueba pasó: de aquí en adelante, de uno en uno. */
    fun disableMultiPid() {
        multiPid = false
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
        /** El estándar (ISO 15765-4) permite hasta 6 PIDs por pregunta. */
        const val MAX_MULTI = 6

        /** Para la prueba: los primeros 4 que el carro tenga ocupan dos tramas. */
        private val PROBE = listOf(Pids.RPM, Pids.SPEED, Pids.COOLANT_TEMP, Pids.ENGINE_LOAD, Pids.THROTTLE, Pids.INTAKE_MAP)

        /** Tramas CAN de la respuesta: el modo (41) más cada PID con sus datos; 7 bytes en la primera si cabe todo, si no 6 y luego de 7 en 7. */
        internal fun framesFor(specs: List<PidSpec>): Int {
            val length = 1 + specs.sumOf { 1 + it.bytes }
            return if (length <= 7) 1 else 1 + (length - 6 + 6) / 7
        }
    }
}
