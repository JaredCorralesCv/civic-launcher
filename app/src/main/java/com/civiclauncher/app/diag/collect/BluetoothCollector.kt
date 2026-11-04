package com.civiclauncher.app.diag.collect

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.location.LocationManagerCompat
import com.civiclauncher.app.diag.DiagStore
import com.civiclauncher.app.diag.ReportSection
import com.civiclauncher.app.diag.ResultKeys
import com.civiclauncher.app.diag.Row
import com.civiclauncher.app.diag.Status
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

fun Context.hasPermissions(perms: Array<String>): Boolean =
    perms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

object BluetoothCollector {

    /** Nombres típicos de adaptadores OBD2. El Vgate iCar Pro BLE suele anunciarse como "IOS-Vlink". */
    private val OBD_HINTS = listOf("OBD", "ELM", "VLINK", "V-LINK", "VGATE", "ICAR", "KONNWEI", "VEEPEAK", "OBDLINK", "CARISTA")

    fun looksLikeObd(name: String?): Boolean = name?.uppercase()?.let { n -> OBD_HINTS.any { n.contains(it) } } ?: false

    fun scanPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        }

    fun adapter(context: Context): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    fun locationEnabled(context: Context): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return LocationManagerCompat.isLocationEnabled(lm)
    }

    @SuppressLint("MissingPermission", "HardwareIds")
    fun collect(context: Context): ReportSection {
        val pm = context.packageManager
        val adapter = adapter(context)
        val granted = context.hasPermissions(scanPermissions())
        val rows = mutableListOf(
            Row("Bluetooth clásico (hardware)", yesNo(pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH))),
            Row("Bluetooth 4.0 / BLE (hardware)", yesNo(pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE))),
            Row(
                "Adaptador accesible para apps",
                if (adapter != null) "Sí" else "No: el Bluetooth de la pantalla es un módulo aparte",
                if (adapter != null) Status.OK else Status.BAD,
            ),
        )
        if (adapter != null) {
            rows += Row("Encendido", yesNo(runCatching { adapter.isEnabled }.getOrDefault(false)), if (adapter.isEnabled) Status.OK else Status.WARN)
            rows += Row("Nombre", runCatching { adapter.name }.getOrNull() ?: "(sin permiso)")
            rows += Row("Estado", stateName(adapter.state))
            rows += Row("Varios anuncios BLE", yesNo(runCatching { adapter.isMultipleAdvertisementSupported }.getOrDefault(false)))
            val bonded = runCatching { adapter.bondedDevices.orEmpty() }.getOrNull()
            rows += Row("Dispositivos emparejados", bonded?.size?.toString() ?: "(sin permiso)")
            bonded?.forEach { d ->
                val name = runCatching { d.name }.getOrNull()
                rows += Row("  · ${name ?: "sin nombre"}", "${d.address} · ${typeName(d.type)}", if (looksLikeObd(name)) Status.OK else Status.INFO)
            }
        }
        rows += Row("Ubicación activada (requerida para escanear)", yesNo(locationEnabled(context)), if (locationEnabled(context)) Status.OK else Status.WARN)
        rows += Row("Permisos de escaneo", if (granted) "Concedidos" else "Faltan", if (granted) Status.OK else Status.WARN)
        // Propiedades de los chips Autochips: indican si el Bluetooth real lo maneja una app del fabricante.
        val stack = com.civiclauncher.app.diag.util.SysProp.get("ro.btstack")
        val androidBtOff = com.civiclauncher.app.diag.util.SysProp.get("ro.atc.disable_android_bt") == "1"
        if (stack.isNotEmpty()) rows += Row("Pila Bluetooth", stack)
        if (androidBtOff) rows += Row("Bluetooth de Android desactivado por el fabricante", "Sí (ro.atc.disable_android_bt=1)", Status.WARN)
        val notes = buildList {
            if (!pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
                add("Sin BLE: un adaptador que solo use Bluetooth 4.0/BLE no se podrá conectar. Uno de Bluetooth clásico, o uno por USB, sí podría.")
            }
            if (androidBtOff) add("El fabricante maneja el Bluetooth con su propia app. El escaneo dirá si las apps aún pueden usarlo.")
        }
        return ReportSection("Bluetooth", rows, notes)
    }

    fun stateName(state: Int) = when (state) {
        BluetoothAdapter.STATE_ON -> "encendido"
        BluetoothAdapter.STATE_OFF -> "apagado"
        BluetoothAdapter.STATE_TURNING_ON -> "encendiendo"
        BluetoothAdapter.STATE_TURNING_OFF -> "apagando"
        else -> "desconocido ($state)"
    }

    fun typeName(type: Int) = when (type) {
        BluetoothDevice.DEVICE_TYPE_CLASSIC -> "clásico"
        BluetoothDevice.DEVICE_TYPE_LE -> "BLE"
        BluetoothDevice.DEVICE_TYPE_DUAL -> "dual"
        else -> "desconocido"
    }

    private fun yesNo(b: Boolean) = if (b) "Sí" else "No"
}

data class FoundDevice(val address: String, val name: String?, val rssi: Int?, val kind: String, val looksLikeObd: Boolean)

data class BtScanState(
    val running: Boolean = false,
    val finished: Boolean = false,
    val devices: List<FoundDevice> = emptyList(),
    val classicStarted: Boolean? = null,
    val bleStarted: Boolean? = null,
    val bleError: Int? = null,
    val error: String? = null,
)

/** Escanea Bluetooth clásico y BLE al mismo tiempo. Si encuentra algo, las apps sí pueden usar el Bluetooth. */
class BluetoothScanner(private val context: Context, private val store: DiagStore) {
    private val _state = MutableStateFlow(BtScanState())
    val state: StateFlow<BtScanState> = _state.asStateFlow()
    private val found = LinkedHashMap<String, FoundDevice>()
    private var receiver: BroadcastReceiver? = null
    private var bleCallback: ScanCallback? = null
    private var timer: Job? = null

    @SuppressLint("MissingPermission")
    fun start(scope: CoroutineScope, durationMs: Long = 15_000) {
        if (_state.value.running) return
        val adapter = BluetoothCollector.adapter(context)
        if (adapter == null) {
            _state.value = BtScanState(finished = true, error = "No hay adaptador Bluetooth accesible para apps")
            save()
            return
        }
        if (!adapter.isEnabled) {
            _state.value = BtScanState(error = "El Bluetooth está apagado")
            return
        }
        found.clear()
        _state.value = BtScanState(running = true)

        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action != BluetoothDevice.ACTION_FOUND) return
                val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
                val name = intent.getStringExtra(BluetoothDevice.EXTRA_NAME) ?: runCatching { device.name }.getOrNull()
                val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt().takeIf { it != Short.MIN_VALUE.toInt() }
                add(FoundDevice(device.address, name, rssi, "clásico", BluetoothCollector.looksLikeObd(name)))
            }
        }
        receiver = r
        ContextCompat.registerReceiver(context, r, IntentFilter(BluetoothDevice.ACTION_FOUND), ContextCompat.RECEIVER_EXPORTED)
        val classicOk = runCatching { adapter.startDiscovery() }.getOrDefault(false)

        var bleOk = false
        val scanner = runCatching { adapter.bluetoothLeScanner }.getOrNull()
        if (scanner != null) {
            val cb = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val name = result.scanRecord?.deviceName ?: runCatching { result.device.name }.getOrNull()
                    add(FoundDevice(result.device.address, name, result.rssi, "BLE", BluetoothCollector.looksLikeObd(name)))
                }

                override fun onScanFailed(errorCode: Int) {
                    _state.update { it.copy(bleError = errorCode) }
                }
            }
            bleCallback = cb
            bleOk = runCatching { scanner.startScan(cb); true }.getOrDefault(false)
        }
        _state.update { it.copy(classicStarted = classicOk, bleStarted = bleOk) }
        timer = scope.launch {
            delay(durationMs)
            stop()
        }
    }

    private fun add(d: FoundDevice) {
        synchronized(found) {
            val prev = found[d.address]
            found[d.address] = if (prev != null && d.name == null) d.copy(name = prev.name, looksLikeObd = prev.looksLikeObd) else d
            _state.update { it.copy(devices = found.values.sortedWith(compareByDescending<FoundDevice> { it.looksLikeObd }.thenByDescending { it.rssi ?: -999 })) }
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!_state.value.running) return
        timer?.cancel()
        val adapter = BluetoothCollector.adapter(context)
        runCatching { adapter?.cancelDiscovery() }
        bleCallback?.let { cb -> runCatching { adapter?.bluetoothLeScanner?.stopScan(cb) } }
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
        bleCallback = null
        _state.update { it.copy(running = false, finished = true) }
        save()
    }

    private fun save() {
        val s = _state.value
        val rows = mutableListOf(
            Row("Escaneo clásico", when (s.classicStarted) { true -> "Iniciado"; false -> "No se pudo iniciar"; null -> "—" }, if (s.classicStarted == true) Status.OK else Status.WARN),
            Row("Escaneo BLE", when (s.bleStarted) { true -> "Iniciado"; false -> "No se pudo iniciar"; null -> "—" } + (s.bleError?.let { " (error $it)" } ?: ""), if (s.bleStarted == true && s.bleError == null) Status.OK else Status.WARN),
            Row(
                "Dispositivos encontrados",
                s.devices.size.toString(),
                if (s.devices.isNotEmpty()) Status.OK else Status.BAD,
            ),
        )
        s.error?.let { rows += Row("Error", it, Status.BAD) }
        s.devices.forEach { d ->
            rows += Row("  · ${d.name ?: "sin nombre"}", "${d.address} · ${d.kind} · ${d.rssi ?: "?"} dBm" + if (d.looksLikeObd) " · POSIBLE OBD" else "", if (d.looksLikeObd) Status.OK else Status.INFO)
        }
        val hasBle = context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
        val notes = listOf(
            when {
                s.devices.isEmpty() -> "No se encontró nada. Pon tu celular en modo visible y repite. Si sigue en cero, usaremos un adaptador OBD por USB."
                hasBle -> "Las apps pueden buscar dispositivos. Falta la Prueba SPP para confirmar que se pueden conectar."
                else -> "Las apps pueden buscar dispositivos clásicos, pero no hay BLE: un adaptador solo-BLE (como el iCar Pro 4.0) no servirá. Falta la Prueba SPP."
            },
        )
        store.put(ResultKeys.BT_SCAN, ReportSection("Prueba de escaneo Bluetooth", rows, notes))
    }
}
