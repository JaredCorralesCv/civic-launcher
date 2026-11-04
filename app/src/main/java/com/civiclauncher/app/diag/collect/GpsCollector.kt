package com.civiclauncher.app.diag.collect

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
import com.civiclauncher.app.diag.DiagStore
import com.civiclauncher.app.diag.ReportSection
import com.civiclauncher.app.diag.ResultKeys
import com.civiclauncher.app.diag.Row
import com.civiclauncher.app.diag.Status
import com.civiclauncher.app.diag.util.formatDuration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object GpsCollector {
    val permissions = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    fun collect(context: Context): ReportSection {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val hasGps = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS)
        val gpsOn = runCatching { lm?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true }.getOrDefault(false)
        val rows = listOf(
            Row("GPS (hardware)", if (hasGps) "Sí" else "No", if (hasGps) Status.OK else Status.BAD),
            Row("GPS activado", if (gpsOn) "Sí" else "No", if (gpsOn) Status.OK else Status.WARN),
            Row("Proveedores", lm?.allProviders?.joinToString().orEmpty()),
            Row("Permiso de ubicación", if (context.hasPermissions(permissions)) "Concedido" else "Falta"),
        )
        return ReportSection("GPS", rows)
    }
}

data class GpsState(
    val running: Boolean = false,
    val satsVisible: Int = 0,
    val satsUsed: Int = 0,
    val ttffMs: Int? = null,
    val fixes: Int = 0,
    val speedKmh: Float? = null,
    val accuracyM: Float? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val startedAt: Long = 0L,
    val error: String? = null,
)

/** Prueba en vivo del GPS. Las coordenadas solo se muestran en pantalla; no van al reporte. */
class GpsProbe(private val context: Context, private val store: DiagStore) {
    private val _state = MutableStateFlow(GpsState())
    val state: StateFlow<GpsState> = _state.asStateFlow()
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private val handler = Handler(Looper.getMainLooper())

    // En Android < 11 hay que implementar los tres métodos viejos o la app se cierra.
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            _state.update {
                it.copy(
                    fixes = it.fixes + 1,
                    speedKmh = if (location.hasSpeed()) location.speed * 3.6f else null,
                    accuracyM = if (location.hasAccuracy()) location.accuracy else null,
                    lat = location.latitude,
                    lon = location.longitude,
                )
            }
        }

        @Deprecated("API vieja")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    private val gnss = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            for (i in 0 until status.satelliteCount) if (status.usedInFix(i)) used++
            _state.update { it.copy(satsVisible = status.satelliteCount, satsUsed = used) }
        }

        override fun onFirstFix(ttffMillis: Int) {
            _state.update { it.copy(ttffMs = ttffMillis) }
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (_state.value.running) return
        val lm = lm
        if (lm == null) {
            _state.value = GpsState(error = "No hay servicio de ubicación")
            return
        }
        if (!context.hasPermissions(GpsCollector.permissions)) {
            _state.value = GpsState(error = "Falta el permiso de ubicación")
            return
        }
        _state.value = GpsState(running = true, startedAt = SystemClock.elapsedRealtime())
        runCatching {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, listener, Looper.getMainLooper())
            lm.registerGnssStatusCallback(gnss, handler)
        }.onFailure { e -> _state.update { it.copy(running = false, error = e.message ?: "Error al iniciar el GPS") } }
    }

    fun stop() {
        if (!_state.value.running) return
        runCatching { lm?.removeUpdates(listener) }
        runCatching { lm?.unregisterGnssStatusCallback(gnss) }
        _state.update { it.copy(running = false) }
        save()
    }

    private fun save() {
        val s = _state.value
        val rows = listOf(
            // onFirstFix no llega si el GPS ya tenía ubicación antes de la prueba; lo que importa son las lecturas.
            Row("Ubicación obtenida", if (s.fixes > 0) "Sí (${s.fixes} lecturas)" else "No", if (s.fixes > 0) Status.OK else Status.BAD),
            Row("Duración de la prueba", formatDuration(SystemClock.elapsedRealtime() - s.startedAt)),
            Row("Satélites visibles", s.satsVisible.toString()),
            Row("Satélites en uso", s.satsUsed.toString(), if (s.satsUsed >= 4) Status.OK else Status.WARN),
            Row("Primera ubicación en", s.ttffMs?.let { formatDuration(it.toLong()) } ?: "El GPS ya tenía ubicación"),
            Row("Lecturas recibidas", s.fixes.toString()),
            Row("Reporta velocidad", if (s.speedKmh != null) "Sí" else "No", if (s.speedKmh != null) Status.OK else Status.WARN),
            Row("Precisión", s.accuracyM?.let { "%.0f m".format(it) } ?: "—"),
        )
        store.put(ResultKeys.GPS, ReportSection("Prueba de GPS", rows))
    }
}
