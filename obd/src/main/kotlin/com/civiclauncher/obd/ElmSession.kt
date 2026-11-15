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

/** Lo que se sabe del carro después de la primera consulta. [protocolCode]: el número del ELM ("6"). */
data class Detection(val protocol: String, val supported: Set<Int>, val protocolCode: String? = null)

/**
 * Habla con un ELM327: lo configura y hace consultas una por una (el ELM no acepta dos a la vez).
 * [ObdEngine] decide qué preguntar y cuándo.
 */
class ElmSession(private val transport: ObdTransport, private val log: ObdLog? = null) {
    private val lock = Mutex()
    private var protocolCommand = "ATSP0"
    /** Mientras se configura no se busca "el adaptador se reinició" (ATZ contesta justo eso). */
    private var configuring = false

    /** Veces que el adaptador se reinició a media sesión y se volvió a configurar. */
    var resets: Int = 0
        private set

    /** Todo pasa por aquí: queda en el registro y, si el adaptador se reinició, se vuelve a configurar. */
    private suspend fun talk(command: String, timeoutMs: Long = 3_000): String {
        val r = rawSend(command, timeoutMs)
        if (!configuring && looksReset(r, command)) {
            resets++
            log?.note("El adaptador se reinició (eco o letrero ELM327): se vuelve a configurar")
            configure()
            return rawSend(command, timeoutMs)
        }
        return r
    }

    private suspend fun rawSend(command: String, timeoutMs: Long): String {
        val started = System.nanoTime()
        val r = transport.send(command, timeoutMs)
        log?.add(command, r, (System.nanoTime() - started) / 1_000_000)
        return r
    }

    /**
     * Un bajón de voltaje al dar marcha puede reiniciar el adaptador: vuelve con el eco encendido (la
     * primera línea repite el comando) o saluda con "ELM327". Sin reconfigurarlo, las respuestas
     * dejan de entenderse hasta reconectar.
     */
    private fun looksReset(reply: String, command: String): Boolean {
        val lines = ElmParser.cleanLines(reply)
        return lines.any { it.startsWith("ELM327") } || lines.firstOrNull()?.replace(" ", "") == command.uppercase().replace(" ", "")
    }

    /** ATE0, ATL0… y el protocolo. Si un comando no contesta "OK", se repite una vez; si tampoco, falla. */
    private suspend fun configure() {
        configuring = true
        try {
            for (cmd in INIT_COMMANDS) {
                if (!isOk(rawSend(cmd, 3_000)) && !isOk(rawSend(cmd, 3_000))) {
                    throw java.io.IOException("El adaptador no aceptó la configuración ($cmd)")
                }
            }
            // Un protocolo recordado que el adaptador no acepte: búsqueda automática normal.
            if (!isOk(rawSend(protocolCommand, 3_000))) {
                protocolCommand = "ATSP0"
                rawSend(protocolCommand, 3_000)
            }
        } finally {
            configuring = false
        }
    }

    private fun isOk(reply: String) = reply.uppercase().contains("OK")

    /** Identificación del adaptador (respuesta a ATZ), disponible después de [start]. */
    var adapterId: String = ""
        private set

    /**
     * [protocolHint]: el protocolo con el que el carro contestó la vez pasada ("6"). Con "ATSPA6" el
     * adaptador lo prueba primero y solo si falla busca entre todos: ahorra segundos al encender.
     */
    suspend fun start(protocolHint: String? = null) = lock.withLock {
        transport.open()
        protocolCommand = protocolHint?.takeIf { it.length == 1 && it[0].isLetterOrDigit() }?.let { "ATSPA$it" } ?: "ATSP0"
        configuring = true
        // ATZ reinicia el adaptador y tarda más que los demás comandos.
        adapterId = try {
            ElmParser.cleanLines(rawSend("ATZ", 5_000)).lastOrNull().orEmpty()
        } finally {
            configuring = false
        }
        configure()
    }

    /**
     * Primera consulta al carro: el ELM busca el protocolo (puede tardar varios segundos) y
     * aprendemos qué datos existen. Devuelve null si el carro no contesta (llave quitada).
     */
    suspend fun detect(): Detection? = lock.withLock {
        val first = talk("0100", timeoutMs = 12_000)
        if (ElmParser.isCarSilent(first)) return@withLock null
        val supported = ElmParser.parseSupported(first, 0x00)?.toMutableSet() ?: return@withLock null
        // Los PIDs 0x20, 0x40… indican si hay otra tanda de PIDs que preguntar (hasta el odómetro, 0xA6).
        for (base in listOf(0x20, 0x40, 0x60, 0x80, 0xA0)) {
            if (base !in supported) break
            ElmParser.parseSupported(talk("01%02X".format(base)), base)?.let { supported += it } ?: break
        }
        val dpn = talk("ATDPN")
        Detection(ElmParser.protocolName(dpn), supported, ElmParser.protocolCode(dpn))
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
            val r = talk(command + frames.coerceIn(1, 15).toString(16).uppercase(), timeoutMs)
            if (ElmParser.cleanLines(r).none { it == "?" }) return r
            fastReply = false
        }
        return talk(command, timeoutMs)
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
        ElmParser.parseVoltage(talk("ATRV"))
    }

    /** Códigos de falla: modo 03 guardados, 07 pendientes, 0A permanentes. null = no se pudo leer. */
    suspend fun readDtcs(mode: Int, can: Boolean): List<String>? = lock.withLock {
        ElmParser.parseDtcs(talk("%02X".format(mode), timeoutMs = 5_000), mode + 0x40, can)
    }

    /**
     * Borra los códigos y apaga el foco "Check Engine" (modo 04). También borra los monitores de
     * emisiones: el carro tarda unos días de manejo en volver a revisarlos. Solo con motor apagado y
     * llave en ON es lo recomendado. Devuelve true si el carro confirmó (respuesta 44).
     */
    suspend fun clearDtcs(): Boolean = lock.withLock {
        ElmParser.cleanLines(talk("04", timeoutMs = 5_000)).any { it.replace(" ", "").startsWith("44") }
    }

    /** Número de serie del carro (VIN). null si el carro no lo da (algunos modelos viejos). */
    suspend fun readVin(): String? = lock.withLock {
        ElmParser.parseVin(talk("0902", timeoutMs = 5_000))
    }

    suspend fun stop() = lock.withLock { transport.close() }

    companion object {
        /**
         * ATE0 sin eco · ATL0 sin saltos de línea extra · ATS0 sin espacios · ATH0 sin encabezados ·
         * ATAT1 tiempos de espera adaptativos (más rápido). Después va el protocolo: el recordado
         * o ATSP0, automático (tu Civic 2008 debería salir como CAN 11 bits 500 kbps).
         */
        val INIT_COMMANDS = listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAT1")
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
