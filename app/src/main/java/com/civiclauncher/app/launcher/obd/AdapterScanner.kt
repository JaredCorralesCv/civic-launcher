package com.civiclauncher.app.launcher.obd

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class AdapterCandidate(val address: String, val name: String?, val paired: Boolean, val looksLikeObd: Boolean)

/** Busca adaptadores OBD por Bluetooth clásico (tu pantalla no tiene BLE). */
class AdapterScanner(private val context: Context) {
    private val _devices = MutableStateFlow<List<AdapterCandidate>>(emptyList())
    val devices: StateFlow<List<AdapterCandidate>> = _devices.asStateFlow()
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()
    private var receiver: BroadcastReceiver? = null

    private val adapter get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    fun permissions(): Array<String> = if (Build.VERSION.SDK_INT >= 31) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    fun bluetoothReady(): Boolean = adapter?.isEnabled == true

    @SuppressLint("MissingPermission")
    fun start() {
        val a = adapter ?: return
        stop()
        // Los emparejados aparecen primero aunque estén apagados en este momento.
        val paired = runCatching { a.bondedDevices.orEmpty() }.getOrDefault(emptySet()).map { candidate(it.address, it.name, true) }
        _devices.value = sort(paired)
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val d = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
                        val name = intent.getStringExtra(BluetoothDevice.EXTRA_NAME) ?: runCatching { d.name }.getOrNull()
                        _devices.update { list ->
                            val existing = list.firstOrNull { it.address == d.address }
                            val merged = if (existing != null) list.map { if (it.address == d.address && it.name == null) it.copy(name = name, looksLikeObd = looksLikeObd(name)) else it }
                            else list + candidate(d.address, name, false)
                            sort(merged)
                        }
                    }
                    android.bluetooth.BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> _scanning.value = false
                }
            }
        }
        receiver = r
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(android.bluetooth.BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        ContextCompat.registerReceiver(context, r, filter, ContextCompat.RECEIVER_EXPORTED)
        _scanning.value = runCatching { a.startDiscovery() }.getOrDefault(false)
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        runCatching { adapter?.cancelDiscovery() }
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
        _scanning.value = false
    }

    private fun candidate(address: String, name: String?, paired: Boolean) = AdapterCandidate(address, name, paired, looksLikeObd(name))

    private fun sort(list: List<AdapterCandidate>) =
        list.sortedWith(compareByDescending<AdapterCandidate> { it.looksLikeObd }.thenByDescending { it.paired }.thenBy { it.name ?: "~" })

    companion object {
        private val HINTS = listOf("OBD", "ELM", "VLINK", "V-LINK", "VGATE", "ICAR", "KONNWEI", "VEEPEAK", "OBDLINK", "CARISTA")
        fun looksLikeObd(name: String?) = name?.uppercase()?.let { n -> HINTS.any { n.contains(it) } } ?: false
    }
}
