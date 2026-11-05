package com.civiclauncher.obd

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/** En qué va la conexión. La interfaz muestra esto tal cual. */
sealed interface EngineState {
    data object Stopped : EngineState
    data class Connecting(val attempt: Int) : EngineState
    /** El adaptador contestó; preguntando al carro qué protocolo usa (hasta ~10 s la primera vez). */
    data class Detecting(val adapter: String) : EngineState
    data class Connected(val adapter: String, val protocol: String, val supported: Set<Int>) : EngineState
    /** El adaptador sí responde pero el carro no: llave quitada. Seguimos leyendo la batería. */
    data class CarOff(val adapter: String) : EngineState
    data class Reconnecting(val reason: String, val retryInMs: Long, val attempt: Int) : EngineState
}

data class Reading(val value: Double, val atMs: Long)

data class VehicleData(
    val values: Map<Int, Reading> = emptyMap(),
    val battery: Reading? = null,
) {
    operator fun get(spec: PidSpec): Reading? = values[spec.pid]
}

/**
 * Motor OBD: se conecta, detecta el carro y lee datos en un ciclo continuo con prioridades.
 *
 *  - Rápidos (cada vuelta): RPM, velocidad, acelerador, carga.
 *  - Medios (cada 3 vueltas): presión del múltiple, flujo de aire, avance, ajustes de combustible.
 *  - Lentos (cada 10 vueltas): temperaturas, voltajes, presión barométrica.
 *
 * Los PIDs que el carro no tiene se saltan: primero por la lista de "soportados" y, si alguno
 * responde NO DATA 3 veces seguidas, se quita del ciclo.
 *
 * Si se cae la conexión, reintenta con espera creciente (1 s, 2 s, 4 s… hasta 30 s).
 * Si el carro deja de contestar (llave quitada) pasa a [EngineState.CarOff] sin desconectar.
 */
class ObdEngine(
    private val transportFactory: () -> ObdTransport,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val config: Config = Config(),
) {
    data class Config(
        val minCycleMs: Long = 80,
        val carOffRetryMs: Long = 5_000,
        val maxBackoffMs: Long = 30_000,
        /** Vueltas seguidas sin respuesta del carro antes de decidir que se apagó. */
        val silentCyclesForCarOff: Int = 3,
    )

    private val _state = MutableStateFlow<EngineState>(EngineState.Stopped)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _data = MutableStateFlow(VehicleData())
    val data: StateFlow<VehicleData> = _data.asStateFlow()

    private val _diagnostics = MutableStateFlow(Diagnostics())
    /** VIN, foco Check Engine y códigos de falla. Se lee al conectar y cada 2 minutos. */
    val diagnostics: StateFlow<Diagnostics> = _diagnostics.asStateFlow()

    /** La sesión activa (para borrar códigos a pedido). El candado de la sesión evita choques con el ciclo. */
    @Volatile private var session: ElmSession? = null
    @Volatile private var isCan = true
    @Volatile private var refreshDtcsRequested = false

    private var job: Job? = null

    val isRunning: Boolean get() = job?.isActive == true

    fun start() {
        if (isRunning) return
        job = scope.launch { runLoop() }
    }

    fun stop() {
        job?.cancel()
        job = null
        _state.value = EngineState.Stopped
    }

    private suspend fun runLoop() {
        var attempt = 0
        while (coroutineContext.isActive) {
            val transport = transportFactory()
            try {
                _state.value = EngineState.Connecting(attempt + 1)
                val session = ElmSession(transport)
                session.start()
                attempt = 0
                this.session = session
                connectedLoop(session)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                attempt++
                val wait = (1_000L shl (attempt - 1).coerceAtMost(5)).coerceAtMost(config.maxBackoffMs)
                _state.value = EngineState.Reconnecting(e.message ?: e.javaClass.simpleName, wait, attempt)
                delay(wait)
            } finally {
                session = null
                // NonCancellable: al detener el motor hay que soltar el Bluetooth aunque nos cancelen.
                withContext(NonCancellable) { runCatching { transport.close() } }
            }
        }
    }

    /** Con el adaptador conectado: detectar el carro, leer, y si se apaga, esperar a que vuelva. */
    private suspend fun connectedLoop(session: ElmSession) {
        while (coroutineContext.isActive) {
            // Mientras el carro está apagado no cambiamos a "Detectando" en cada reintento (parpadearía).
            if (_state.value !is EngineState.CarOff) _state.value = EngineState.Detecting(session.adapterId)
            val detection = session.detect()
            if (detection == null) {
                _state.value = EngineState.CarOff(session.adapterId)
                readBattery(session)
                delay(config.carOffRetryMs)
                continue
            }
            _state.value = EngineState.Connected(session.adapterId, detection.protocol, detection.supported)
            isCan = detection.protocol.startsWith("CAN")
            _diagnostics.update { it.copy(protocol = detection.protocol) }
            if (_diagnostics.value.vin == null) {
                session.readVin()?.let { vin -> _diagnostics.update { it.copy(vin = vin) } }
            }
            refreshDtcs(session)
            poll(session, detection.supported)
            // poll() regresa cuando el carro deja de contestar.
            _state.value = EngineState.CarOff(session.adapterId)
        }
    }

    private suspend fun poll(session: ElmSession, supported: Set<Int>) {
        fun usable(list: List<PidSpec>) = list.filter { supported.isEmpty() || it.pid in supported }.toMutableList()
        val fast = usable(FAST)
        val medium = usable(MEDIUM)
        val slow = usable(SLOW)
        val rare = usable(RARE)
        var lastDtcCheck = clock()
        val noDataCount = mutableMapOf<Int, Int>()
        var silentCycles = 0
        var cycle = 0L

        while (coroutineContext.isActive) {
            val started = clock()
            val groups = buildList {
                add(fast)
                if (cycle % 3 == 0L) add(medium)
                if (cycle % 10 == 0L) add(slow)
                if (cycle % 60 == 0L) add(rare)
            }
            var answered = 0
            var asked = 0
            for (group in groups) {
                val fresh = mutableMapOf<Int, Reading>()
                val iterator = group.iterator()
                while (iterator.hasNext()) {
                    val spec = iterator.next()
                    asked++
                    when (val r = session.query(spec)) {
                        is ObdResult.Value -> {
                            answered++
                            noDataCount.remove(spec.pid)
                            fresh[spec.pid] = Reading(r.value, clock())
                        }
                        ObdResult.NoData -> {
                            val n = (noDataCount[spec.pid] ?: 0) + 1
                            noDataCount[spec.pid] = n
                            if (n >= 3 && group !== fast) iterator.remove()
                        }
                        is ObdResult.Error -> Unit
                    }
                }
                if (fresh.isNotEmpty()) _data.update { it.copy(values = it.values + fresh) }
            }
            if (cycle % 10 == 0L) readBattery(session)

            // Códigos de falla: cada 2 minutos, o enseguida si cambió el foco / la cantidad (PID 01).
            if (refreshDtcsRequested || clock() - lastDtcCheck > DTC_REFRESH_MS || monitorChanged()) {
                refreshDtcsRequested = false
                lastDtcCheck = clock()
                refreshDtcs(session)
            }

            // Si nada contestó varias vueltas seguidas, el carro se apagó (el adaptador sigue vivo).
            silentCycles = if (asked > 0 && answered == 0) silentCycles + 1 else 0
            if (silentCycles >= config.silentCyclesForCarOff) return

            cycle++
            val elapsed = clock() - started
            if (elapsed < config.minCycleMs) delay(config.minCycleMs - elapsed)
        }
    }

    private var lastMonitor: Int? = null

    /** Actualiza foco/cantidad desde el PID 01 y dice si cambió desde la última vez. */
    private fun monitorChanged(): Boolean {
        val raw = _data.value[Pids.MONITOR_STATUS]?.value?.toInt() ?: return false
        val mil = raw and 0x80 != 0
        val count = raw and 0x7F
        _diagnostics.update { it.copy(milOn = mil, dtcCount = count) }
        val changed = lastMonitor != null && lastMonitor != raw
        lastMonitor = raw
        return changed
    }

    private suspend fun refreshDtcs(session: ElmSession) {
        val stored = session.readDtcs(0x03, isCan)
        val pending = session.readDtcs(0x07, isCan)
        _diagnostics.update {
            it.copy(
                stored = stored ?: it.stored,
                pending = pending ?: it.pending,
                checkedAtMs = clock(),
            )
        }
    }

    /**
     * Borra los códigos de falla y apaga el foco (modo 04). Solo funciona conectado al carro.
     * Devuelve true si el carro lo confirmó. Después se vuelven a leer los códigos.
     */
    suspend fun clearCodes(): Boolean {
        val s = session ?: return false
        if (_state.value !is EngineState.Connected) return false
        val ok = runCatching { s.clearDtcs() }.getOrDefault(false)
        if (ok) {
            _diagnostics.update { it.copy(stored = emptyList(), pending = emptyList(), milOn = false, dtcCount = 0, lastClear = ClearResult.OK) }
            lastMonitor = null
        } else {
            _diagnostics.update { it.copy(lastClear = ClearResult.FAILED) }
        }
        refreshDtcsRequested = true
        return ok
    }

    /** Volver a leer los códigos en la siguiente vuelta del ciclo (botón "Revisar de nuevo"). */
    fun requestDtcRefresh() {
        refreshDtcsRequested = true
    }

    private suspend fun readBattery(session: ElmSession) {
        (session.batteryVoltage() as? ObdResult.Value)?.let { v ->
            _data.update { it.copy(battery = Reading(v.value, clock())) }
        }
    }

    companion object {
        /** Cada vuelta: lo que se ve moverse y lo que necesita el cálculo de consumo. */
        val FAST = listOf(Pids.RPM, Pids.SPEED, Pids.THROTTLE, Pids.ENGINE_LOAD, Pids.INTAKE_MAP, Pids.MAF)
        /** Cada 3 vueltas. */
        val MEDIUM = listOf(
            Pids.TIMING_ADVANCE, Pids.SHORT_FUEL_TRIM_1, Pids.LONG_FUEL_TRIM_1, Pids.FUEL_RATE, Pids.REL_THROTTLE,
            Pids.PEDAL_D, Pids.COMMANDED_THROTTLE, Pids.ABS_LOAD, Pids.O2_B1S1, Pids.O2_B1S2, Pids.COMMANDED_AFR,
        )
        /** Cada 10 vueltas: temperaturas, voltajes, estado. */
        val SLOW = listOf(
            Pids.COOLANT_TEMP, Pids.INTAKE_AIR_TEMP, Pids.MODULE_VOLTAGE, Pids.BAROMETRIC, Pids.AMBIENT_TEMP,
            Pids.FUEL_LEVEL, Pids.OIL_TEMP, Pids.CATALYST_TEMP, Pids.MONITOR_STATUS, Pids.FUEL_SYSTEM,
            Pids.EVAP_PURGE, Pids.FUEL_PRESSURE,
        )
        /** Cada 60 vueltas: contadores que cambian muy poco. */
        val RARE = listOf(Pids.RUN_TIME, Pids.DIST_WITH_MIL, Pids.DIST_SINCE_CLEAR, Pids.TIME_WITH_MIL, Pids.TIME_SINCE_CLEAR, Pids.ODOMETER)
        private const val DTC_REFRESH_MS = 120_000L
    }
}
