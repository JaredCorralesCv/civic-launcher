package com.civiclauncher.app.launcher.media

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.civiclauncher.app.container

/**
 * Avisa cuando el celular se conecta a la pantalla como "bocina Bluetooth" (A2DP sink), para que la
 * música empiece sola. El aviso es implícito: en Android 8 solo llega a un receptor registrado en
 * código, y Civic sigue vivo con el carro apagado, así que basta con registrarlo al arrancar.
 */
object PhoneWatcher {
    /** BluetoothA2dpSink.ACTION_CONNECTION_STATE_CHANGED (API oculta, pero el texto es fijo). */
    private const val A2DP_SINK_STATE = "android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED"
    private const val EXTRA_STATE = "android.bluetooth.profile.extra.STATE"
    private const val EXTRA_PREVIOUS = "android.bluetooth.profile.extra.PREVIOUS_STATE"
    private const val CONNECTED = 2

    private var registered = false

    fun register(context: Context) {
        if (registered) return
        val app = context.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val now = intent.getIntExtra(EXTRA_STATE, -1)
                val before = intent.getIntExtra(EXTRA_PREVIOUS, -1)
                android.util.Log.i("CivicBT", "A2DP: $before -> $now")
                val media = app.container.media
                when {
                    now == CONNECTED && before != CONNECTED ->
                        if (app.container.launcherPrefs.autoplayBluetooth) media.onPhoneConnected() else media.phoneConnected = true
                    now != CONNECTED -> media.onPhoneDisconnected()
                }
            }
        }
        registered = runCatching {
            ContextCompat.registerReceiver(app, receiver, IntentFilter(A2DP_SINK_STATE), ContextCompat.RECEIVER_EXPORTED)
            true
        }.getOrDefault(false)
    }
}
