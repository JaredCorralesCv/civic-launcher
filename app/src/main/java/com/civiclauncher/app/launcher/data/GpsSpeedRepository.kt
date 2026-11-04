package com.civiclauncher.app.launcher.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class GpsSpeed(
    val permission: Boolean = false,
    val enabled: Boolean = true,
    /** null = sin ubicación reciente (bajo techo, arrancando o sin satélites). */
    val kmh: Float? = null,
    val satsUsed: Int = 0,
    val satsVisible: Int = 0,
    val accuracyM: Float? = null,
)

/**
 * Velocidad por GPS para el velocímetro de la pantalla de inicio. Cuando llegue el OBD (Fase 3),
 * la velocidad del carro tendrá prioridad y esta quedará de respaldo.
 *
 * El GPS solo está encendido mientras alguien observa el flujo (la pantalla de inicio visible),
 * y se apaga 5 s después de dejar de verla.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GpsSpeedRepository(private val context: Context, scope: CoroutineScope) {
    private val permissionTrigger = MutableStateFlow(0)

    val speed: StateFlow<GpsSpeed> = permissionTrigger
        .flatMapLatest { updates() }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), GpsSpeed(permission = hasPermission()))

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Llamar después de pedir el permiso para volver a intentar. */
    fun onPermissionChanged() {
        permissionTrigger.value++
    }

    @SuppressLint("MissingPermission")
    private fun updates() = callbackFlow {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (!hasPermission() || lm == null) {
            trySend(GpsSpeed(permission = false))
            awaitClose { }
            return@callbackFlow
        }
        var state = GpsSpeed(permission = true, enabled = runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false))
        var lastFix = 0L
        fun emit(s: GpsSpeed) {
            state = s
            trySend(s)
        }
        emit(state)

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                lastFix = SystemClock.elapsedRealtime()
                // Por debajo de 3 km/h el GPS "tiembla" aunque el carro esté detenido.
                val kmh = if (location.hasSpeed()) (location.speed * 3.6f).let { if (it < 3f) 0f else it } else null
                emit(state.copy(kmh = kmh, accuracyM = if (location.hasAccuracy()) location.accuracy else null))
            }

            @Deprecated("API vieja")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = emit(state.copy(enabled = true))
            override fun onProviderDisabled(provider: String) = emit(state.copy(enabled = false, kmh = null))
        }
        val gnss = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                var used = 0
                for (i in 0 until status.satelliteCount) if (status.usedInFix(i)) used++
                if (used != state.satsUsed || status.satelliteCount != state.satsVisible) {
                    emit(state.copy(satsUsed = used, satsVisible = status.satelliteCount))
                }
            }
        }
        val handler = Handler(Looper.getMainLooper())
        runCatching {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, listener, Looper.getMainLooper())
            lm.registerGnssStatusCallback(gnss, handler)
        }
        // Si dejan de llegar ubicaciones (túnel, estacionamiento), no mostrar una velocidad vieja.
        launch {
            while (isActive) {
                delay(2_000)
                if (state.kmh != null && SystemClock.elapsedRealtime() - lastFix > 5_000) emit(state.copy(kmh = null))
            }
        }
        awaitClose {
            runCatching { lm.removeUpdates(listener) }
            runCatching { lm.unregisterGnssStatusCallback(gnss) }
        }
    }.conflate()
}
