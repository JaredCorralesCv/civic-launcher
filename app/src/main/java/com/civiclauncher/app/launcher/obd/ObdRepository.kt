package com.civiclauncher.app.launcher.obd

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.civiclauncher.obd.EngineState
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
