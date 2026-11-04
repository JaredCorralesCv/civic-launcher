package com.civiclauncher.app.launcher.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.TimeZone

/**
 * Hora actual que cambia una vez por minuto. Usa el aviso TIME_TICK del sistema en lugar de un
 * temporizador propio: no gasta CPU y se corrige solo si cambia la hora o la zona horaria.
 */
class ClockRepository(context: Context) {
    private val _now = MutableStateFlow(System.currentTimeMillis())
    val now: StateFlow<Long> = _now.asStateFlow()

    private val _timeZone = MutableStateFlow(TimeZone.getDefault().id)
    val timeZone: StateFlow<String> = _timeZone.asStateFlow()

    init {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_TIMEZONE_CHANGED) {
                    TimeZone.setDefault(null) // fuerza a Java a releer la zona del sistema
                    _timeZone.value = TimeZone.getDefault().id
                }
                _now.value = System.currentTimeMillis()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    /** Llamar al volver a la pantalla: después de dormir, TIME_TICK puede tardar hasta un minuto. */
    fun refresh() {
        _now.value = System.currentTimeMillis()
    }

    /**
     * La pantalla venía de fábrica con la zona de China. Si sigue así, el reloj muestra otra hora.
     */
    fun looksLikeWrongTimeZone(): Boolean = _timeZone.value == "Asia/Shanghai"
}
