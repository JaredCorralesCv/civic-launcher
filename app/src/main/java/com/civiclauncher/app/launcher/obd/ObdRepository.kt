package com.civiclauncher.app.launcher.obd

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.civiclauncher.obd.Diagnostics
import com.civiclauncher.obd.PollDemand
import com.civiclauncher.obd.TimeMark
import com.civiclauncher.obd.EngineState
import com.civiclauncher.obd.FuelModel
import com.civiclauncher.obd.AlertFilter
import com.civiclauncher.obd.GearTracker
import com.civiclauncher.obd.Pids
import com.civiclauncher.obd.TripComputer
import com.civiclauncher.obd.TripStats
import com.civiclauncher.obd.VehicleAlert
import com.civiclauncher.obd.VehicleAlerts
import com.civiclauncher.obd.freshAt
import kotlinx.coroutines.launch
import com.civiclauncher.obd.ObdEngine
import com.civiclauncher.obd.ObdTransport
import com.civiclauncher.obd.SimulatedElm
import com.civiclauncher.obd.VehicleData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

enum class ObdMode { NONE, SIMULATOR, BLUETOOTH }

/** Calculado al momento a partir de los datos del OBD. */
data class LiveDerived(
    val kmPerLiter: Double? = null,
    val litersPerHour: Double? = null,
    val gear: Int? = null,
    val alerts: List<VehicleAlert> = emptyList(),
)

private const val NEW_TRIP_AFTER_MS = 30 * 60_000L

data class ObdConfig(val mode: ObdMode, val address: String? = null, val name: String? = null)

/**
 * Punto único del OBD para toda la app: qué adaptador usar, el motor activo y sus datos.
 *
 * Ciclo de vida (lo que aprendimos de tu pantalla en la Fase 0):
 *  - Pantalla apagada (llave quitada, arranque rápido): se detiene el motor y se suelta el Bluetooth.
 *  - Pantalla encendida: arranca de nuevo de inmediato.
 *  - Reinicio completo o la app cerrada por el sistema: Civic es el launcher, así que Android lo
 *    vuelve a abrir y [com.civiclauncher.app.CivicApp] reanuda el servicio.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ObdRepository(private val context: Context, private val scope: CoroutineScope) {
    private val sp = context.getSharedPreferences("obd", Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(loadConfig())
    val config: StateFlow<ObdConfig> = _config.asStateFlow()

    private val engine = MutableStateFlow<ObdEngine?>(null)
    private var wantRunning = false
    private var screenOn = true

    val state: StateFlow<EngineState> = engine
        .flatMapLatest { it?.state ?: flowOf(EngineState.Stopped) }
        .stateIn(scope, SharingStarted.Eagerly, EngineState.Stopped)

    val data: StateFlow<VehicleData> = engine
        .flatMapLatest { it?.data ?: flowOf(VehicleData()) }
        .stateIn(scope, SharingStarted.Eagerly, VehicleData())

    /** VIN, foco Check Engine y códigos de falla. Se conserva aunque el motor se detenga. */
    val diagnostics: StateFlow<Diagnostics> = engine
        .flatMapLatest { it?.diagnostics ?: flowOf(Diagnostics()) }
        .stateIn(scope, SharingStarted.Eagerly, Diagnostics())

    // ------------------------------------------------------------------ Viaje y cálculos

    /** Un solo hilo de fondo para los cálculos: el viaje se suma en orden y sin choques. */
    private val calc = Dispatchers.Default.limitedParallelism(1)

    private val tripPrefs = context.getSharedPreferences("trip", Context.MODE_PRIVATE)
    private val trip = TripComputer(loadTrip())
    private val _tripStats = MutableStateFlow(trip.stats)
    /** Computadora de viaje. Se guarda cada tanto: sobrevive al apagado del carro. */
    val tripStats: StateFlow<TripStats> = _tripStats.asStateFlow()

    private val _live = MutableStateFlow(LiveDerived())
    /** Lo calculado al momento: consumo, marcha y avisos. */
    val live: StateFlow<LiveDerived> = _live.asStateFlow()
    private var lastSaveMs = 0L
    private var lastTripPublish = 0L
    /** Avisos con tolerancia (no parpadean ni saltan por un bajón de un segundo). */
    private val alertFilter = AlertFilter()
    /** Marcha que aprende las relaciones de tu carro; se guardan para la próxima vez. */
    private val gears = GearTracker(
        tripPrefs.getString("gear_ratios", null)?.split(',')?.mapNotNull { it.toDoubleOrNull() }?.toDoubleArray()
            ?: com.civiclauncher.obd.GearEstimator.defaultRatios(),
    )
    private val bootId by lazy { runCatching { java.io.File("/proc/sys/kernel/random/boot_id").readText().trim() }.getOrDefault("") }
    // Datos guardados por versiones anteriores: solo la hora (sin encendido), se comparan por la hora.
    private var lastRunning: TimeMark? = tripPrefs.getLong("last_running", 0L).takeIf { it != 0L }?.let {
        TimeMark(tripPrefs.getString("last_boot", "").orEmpty(), tripPrefs.getLong("last_running_e", 0L), it)
    }

    private fun loadTrip() = TripStats(
        startedAtMs = tripPrefs.getLong("started", 0L),
        drivingMs = tripPrefs.getLong("driving", 0L),
        distanceKm = tripPrefs.getFloat("km", 0f).toDouble(),
        fuelLiters = tripPrefs.getFloat("liters", 0f).toDouble(),
        maxSpeedKmh = tripPrefs.getFloat("max", 0f).toDouble(),
        idleMs = tripPrefs.getLong("idle", 0L),
    )

    private fun saveTrip(s: TripStats) {
        tripPrefs.edit()
            .putLong("started", s.startedAtMs).putLong("driving", s.drivingMs)
            .putFloat("km", s.distanceKm.toFloat()).putFloat("liters", s.fuelLiters.toFloat())
            .putFloat("max", s.maxSpeedKmh.toFloat()).putLong("idle", s.idleMs)
            .putLong("last_running", lastRunning?.wallMs ?: 0L)
            .putLong("last_running_e", lastRunning?.elapsedMs ?: 0L)
            .putString("last_boot", lastRunning?.bootId)
            .putString("gear_ratios", gears.learned.joinToString(",") { "%.2f".format(java.util.Locale.US, it) })
            .apply()
    }

    private fun onData(d: VehicleData) {
        // El mismo reloj que las lecturas (ver apply): no salta cuando el GPS corrige la hora.
        val now = SystemClock.elapsedRealtime()
        val wall = System.currentTimeMillis()
        val running = (d[Pids.RPM].freshAt(now)?.value ?: 0.0) > 300
        if (running) {
            val mark = TimeMark(bootId, now, wall)
            // Más de 30 min con el motor apagado = viaje nuevo (como la computadora de muchos carros).
            if (lastRunning?.let { it.msUntil(mark) > NEW_TRIP_AFTER_MS } == true) trip.reset()
            lastRunning = mark
        }
        val s = trip.update(d, now, wall)
        // A la pantalla, una vez por segundo: el viaje cambia en cada lectura del OBD (decenas por
        // segundo) y cada aviso volvía a armar las tarjetas que lo muestran.
        if (now - lastTripPublish >= 1_000) {
            lastTripPublish = now
            _tripStats.value = s
        }
        if (now - lastSaveMs > 15_000) {
            lastSaveMs = now
            saveTrip(s)
        }
        // Redondeado a lo que se muestra (una décima): si no cambia lo visible, StateFlow no emite y la
        // pantalla no se vuelve a dibujar por diferencias que nadie ve.
        fun tenth(v: Double?) = v?.let { Math.round(it * 10) / 10.0 }
        _live.value = LiveDerived(
            kmPerLiter = tenth(FuelModel.kmPerLiter(d, now)),
            litersPerHour = tenth(FuelModel.litersPerHour(d, now)),
            gear = gears.update(d[Pids.RPM].freshAt(now)?.value, d[Pids.SPEED].freshAt(now)?.value, now),
            alerts = alertFilter.update(VehicleAlerts.evaluate(d, diagnostics.value, now), now),
        )
    }

    /** Empezar un viaje nuevo (botón "Reiniciar"). */
    fun resetTrip() {
        // En el mismo hilo que los cálculos, para no reiniciar el viaje a media suma.
        scope.launch(calc) {
            trip.reset()
            _tripStats.value = trip.stats
            saveTrip(trip.stats)
        }
    }

    /**
     * Borra los códigos de falla. Devuelve false si no hay conexión con el carro o si el carro no
     * lo aceptó. Lo ideal: con la llave en ON y el motor apagado.
     */
    suspend fun clearCodes(): Boolean = engine.value?.clearCodes() ?: false

    fun refreshCodes() {
        engine.value?.requestDtcRefresh()
    }

    init {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                screenOn = intent.action == Intent.ACTION_SCREEN_ON
                apply()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        // Los cálculos (viaje, consumo, marcha, avisos) van fuera del hilo de la pantalla.
        scope.launch(calc) { data.collect { onData(it) } }
    }

    private fun loadConfig(): ObdConfig {
        val mode = runCatching { ObdMode.valueOf(sp.getString("mode", null) ?: "") }.getOrDefault(ObdMode.NONE)
        return ObdConfig(mode, sp.getString("address", null), sp.getString("name", null))
    }

    fun useBluetooth(address: String, name: String?) = setConfig(ObdConfig(ObdMode.BLUETOOTH, address, name))
    fun useSimulator() = setConfig(ObdConfig(ObdMode.SIMULATOR))
    fun disable() = setConfig(ObdConfig(ObdMode.NONE))

    private fun setConfig(c: ObdConfig) {
        sp.edit().putString("mode", c.mode.name).putString("address", c.address).putString("name", c.name).apply()
        _config.value = c
        engine.value?.stop()
        engine.value = null
        if (c.mode == ObdMode.NONE) ObdService.stop(context) else ObdService.start(context)
        apply()
    }

    /** Lo llama [ObdService]: mientras el servicio vive, el motor corre (si la pantalla está encendida). */
    fun setRunning(running: Boolean) {
        wantRunning = running
        apply()
    }

    private fun apply() {
        val c = _config.value
        val shouldRun = wantRunning && screenOn && c.mode != ObdMode.NONE
        if (!shouldRun) {
            engine.value?.stop()
            return
        }
        // El ciclo del OBD (decenas de preguntas y respuestas por segundo) corre en su propio hilo:
        // la pantalla solo recibe los resultados.
        val e = engine.value
            ?: ObdEngine(transportFactory = { transportFor(c) }, scope = engineScope, clock = SystemClock::elapsedRealtime).also { engine.value = it }
        e.demand = demand()
        e.start()
    }

    // ------------------------------------------------------------------ Cuánto se le pide al carro

    private var foreground = true
    private var liveScreen = true
    private var sensorsOpen = false

    /** Con otra app al frente (Android Auto) el OBD no compite por CPU y Bluetooth. */
    fun setForeground(value: Boolean) { foreground = value; updateDemand() }

    /** ¿La pantalla actual de Civic muestra datos del motor? */
    fun setLiveScreen(value: Boolean) { liveScreen = value; updateDemand() }

    fun setSensorsOpen(value: Boolean) { sensorsOpen = value; updateDemand() }

    private fun demand() = when {
        !foreground || !liveScreen -> PollDemand.BACKGROUND
        sensorsOpen -> PollDemand.ALL
        else -> PollDemand.LIVE
    }

    private fun updateDemand() {
        engine.value?.demand = demand()
    }

    private val engineScope = CoroutineScope(scope.coroutineContext + Dispatchers.Default)

    private fun transportFor(c: ObdConfig): ObdTransport = when (c.mode) {
        ObdMode.BLUETOOTH -> BluetoothSppTransport(context, c.address ?: error("Falta el adaptador"))
        else -> SimulatedElm(latencyMs = 40)
    }
}
