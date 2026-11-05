package com.civiclauncher.app.launcher.obd

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.civiclauncher.obd.Diagnostics
import com.civiclauncher.obd.EngineState
import com.civiclauncher.obd.FuelModel
import com.civiclauncher.obd.GearEstimator
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

    private val tripPrefs = context.getSharedPreferences("trip", Context.MODE_PRIVATE)
    private val trip = TripComputer(loadTrip())
    private val _tripStats = MutableStateFlow(trip.stats)
    /** Computadora de viaje. Se guarda cada tanto: sobrevive al apagado del carro. */
    val tripStats: StateFlow<TripStats> = _tripStats.asStateFlow()

    private val _live = MutableStateFlow(LiveDerived())
    /** Lo calculado al momento: consumo, marcha y avisos. */
    val live: StateFlow<LiveDerived> = _live.asStateFlow()
    private var lastSaveMs = 0L
    private var lastRunningMs = tripPrefs.getLong("last_running", 0L)

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
            .putLong("last_running", lastRunningMs)
            .apply()
    }

    private fun onData(d: VehicleData) {
        val now = System.currentTimeMillis()
        val running = (d[Pids.RPM].freshAt(now)?.value ?: 0.0) > 300
        if (running) {
            // Más de 30 min con el motor apagado = viaje nuevo (como la computadora de muchos carros).
            if (lastRunningMs != 0L && now - lastRunningMs > NEW_TRIP_AFTER_MS) trip.reset()
            lastRunningMs = now
        }
        val s = trip.update(d, now)
        _tripStats.value = s
        if (now - lastSaveMs > 15_000) {
            lastSaveMs = now
            saveTrip(s)
        }
        _live.value = LiveDerived(
            kmPerLiter = FuelModel.kmPerLiter(d, now),
            litersPerHour = FuelModel.litersPerHour(d, now),
            gear = GearEstimator.estimate(d[Pids.RPM].freshAt(now)?.value, d[Pids.SPEED].freshAt(now)?.value),
            alerts = VehicleAlerts.evaluate(d, diagnostics.value, now),
        )
    }

    /** Empezar un viaje nuevo (botón "Reiniciar"). */
    fun resetTrip() {
        trip.reset()
        _tripStats.value = trip.stats
        saveTrip(trip.stats)
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
        scope.launch { data.collect { onData(it) } }
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
        val e = engine.value ?: ObdEngine(transportFactory = { transportFor(c) }, scope = scope).also { engine.value = it }
        e.start()
    }

    private fun transportFor(c: ObdConfig): ObdTransport = when (c.mode) {
        ObdMode.BLUETOOTH -> BluetoothSppTransport(context, c.address ?: error("Falta el adaptador"))
        else -> SimulatedElm(latencyMs = 40)
    }
}
