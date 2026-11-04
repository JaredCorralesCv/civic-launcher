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
                connectedLoop(session)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                attempt++
                val wait = (1_000L shl (attempt - 1).coerceAtMost(5)).coerceAtMost(config.maxBackoffMs)
                _state.value = EngineState.Reconnecting(e.message ?: e.javaClass.simpleName, wait, attempt)
                delay(wait)
            } finally {
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
        val noDataCount = mutableMapOf<Int, Int>()
        var silentCycles = 0
        var cycle = 0L

        while (coroutineContext.isActive) {
            val started = clock()
            val groups = buildList {
                add(fast)
                if (cycle % 3 == 0L) add(medium)
                if (cycle % 10 == 0L) add(slow)
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

            // Si nada contestó varias vueltas seguidas, el carro se apagó (el adaptador sigue vivo).
            silentCycles = if (asked > 0 && answered == 0) silentCycles + 1 else 0
            if (silentCycles >= config.silentCyclesForCarOff) return

            cycle++
            val elapsed = clock() - started
            if (elapsed < config.minCycleMs) delay(config.minCycleMs - elapsed)
        }
    }

    private suspend fun readBattery(session: ElmSession) {
        (session.batteryVoltage() as? ObdResult.Value)?.let { v ->
            _data.update { it.copy(battery = Reading(v.value, clock())) }
        }
    }

    companion object {
        val FAST = listOf(Pids.RPM, Pids.SPEED, Pids.THROTTLE, Pids.ENGINE_LOAD)
        val MEDIUM = listOf(Pids.INTAKE_MAP, Pids.MAF, Pids.TIMING_ADVANCE, Pids.SHORT_FUEL_TRIM_1, Pids.LONG_FUEL_TRIM_1)
        val SLOW = listOf(Pids.COOLANT_TEMP, Pids.INTAKE_AIR_TEMP, Pids.MODULE_VOLTAGE, Pids.BAROMETRIC, Pids.AMBIENT_TEMP, Pids.FUEL_LEVEL)
    }
}
