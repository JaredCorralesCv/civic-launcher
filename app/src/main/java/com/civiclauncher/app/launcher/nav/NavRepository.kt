package com.civiclauncher.app.launcher.nav

import android.content.Context
import com.civiclauncher.app.launcher.data.GpsSpeed
import com.civiclauncher.app.launcher.data.GpsSpeedRepository
import com.civiclauncher.app.launcher.map.MapRepository
import com.civiclauncher.app.launcher.map.distanceToSegmentM
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mapsforge.core.model.LatLong
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Un lugar al que se puede ir. */
data class Place(val name: String, val point: LatLong)

/** Cómo vas en la ruta, para la pantalla. */
data class NavProgress(
    val next: Maneuver?,
    /** Metros hasta la siguiente indicación. */
    val toNextM: Double,
    /** Calle a la que entras en la siguiente indicación (si el mapa la conoce). */
    val nextStreet: String?,
    val remainingM: Double,
    val remainingS: Int,
    /** Índice del tramo de la ruta en el que vas (para dibujar solo lo que falta). */
    val segment: Int,
    /** Dónde vas, pegado a la ruta (el mapa se centra aquí: no "tiembla" fuera de la calle). */
    val position: LatLong? = null,
    /** Hacia dónde va la ruta en ese punto (grados, 0 = norte). */
    val bearing: Float? = null,
    /** Te saliste y no se pudo calcular una ruta nueva (faltan datos de la zona): se sigue intentando. */
    val noRoute: Boolean = false,
)

sealed interface NavState {
    data object Idle : NavState
    /** Elegiste un destino: se calcula y se muestra la ruta antes de arrancar. */
    data class Preview(
        val dest: Place,
        val route: Route? = null,
        val calculating: Boolean = true,
        val error: String? = null,
        /** Cuadros de datos que faltan para esta ruta (se ofrecen para descargar). */
        val missing: List<String> = emptyList(),
    ) : NavState
    data class Navigating(val dest: Place, val route: Route, val progress: NavProgress, val rerouting: Boolean = false) : NavState
    data class Arrived(val dest: Place) : NavState
}

/** Descarga de datos de rutas. */
sealed interface TilesDownload {
    data object Idle : TilesDownload
    data class Running(val name: String, val index: Int, val count: Int, val progress: Float) : TilesDownload
    data class Failed(val message: String) : TilesDownload
}

/**
 * Navegación de Civic, sin Android Auto y sin internet:
 *  1. Eliges un destino (tocando el mapa, buscando o de tus favoritos) y se calcula la ruta.
 *  2. "Ir": el mapa te sigue, muestra la siguiente vuelta y cuánto falta.
 *  3. Si te sales de la ruta (más de ~50 m un rato), se recalcula desde donde estás.
 *  4. Al llegar, avisa y termina sola.
 *
 * La navegación sigue aunque cambies de pantalla (Inicio muestra la siguiente vuelta).
 */
class NavRepository(
    private val context: Context,
    private val scope: CoroutineScope,
    private val gps: GpsSpeedRepository,
    private val map: MapRepository,
) {
    val engine = RouteEngine(context)
    /** Búsqueda de lugares y calles sin internet (sale del mismo mapa). */
    val search = SearchIndex(map, scope)
    private val _state = MutableStateFlow<NavState>(NavState.Idle)
    val state: StateFlow<NavState> = _state.asStateFlow()
    private var job: Job? = null
    private var redirectJob: Job? = null
    @Volatile private var redirecting = false
    private var simulating = false

    // ------------------------------------------------------------------ Elegir destino

    /** Calcula la ruta hasta [place] para mostrarla antes de arrancar. */
    fun preview(place: Place) {
        job?.cancel()
        _state.value = NavState.Preview(place)
        job = scope.launch {
            val dest = place.copy(name = place.name.ifBlank { nameOf(place.point) ?: "Punto en el mapa" })
            _state.value = NavState.Preview(dest)
            val from = currentPosition() ?: run {
                _state.value = NavState.Preview(dest, calculating = false, error = "Esperando tu ubicación del GPS…")
                return@launch
            }
            _state.value = try {
                NavState.Preview(dest, engine.route(from, dest.point, gps.speed.value.bearing), calculating = false)
            } catch (e: RouteException) {
                NavState.Preview(dest, calculating = false, error = e.message, missing = e.missingTiles)
            } catch (e: Exception) {
                NavState.Preview(dest, calculating = false, error = "No se pudo calcular la ruta")
            }
        }
    }

    /** Arranca la navegación con la ruta del destino elegido. */
    /**
     * Arranca la navegación con la ruta del destino elegido. [simulate]: en lugar del GPS, un carro
     * virtual recorre la ruta a ~50 km/h (para ver el recorrido antes de manejarlo, o para probar).
     */
    fun start(simulate: Boolean = false) {
        val p = _state.value as? NavState.Preview ?: return
        val route = p.route ?: return
        if (!simulate) addRecent(p.dest)
        drive(p.dest, route, simulate)
    }

    private fun drive(dest: Place, route: Route, simulate: Boolean) {
        job?.cancel()
        redirectJob?.cancel()
        redirecting = false
        simulating = simulate
        _resumable.value = null
        // Un recorrido simulado no se ofrece después como ruta por continuar.
        if (simulate) clearActive() else saveActive(dest)
        prefetchRoute(route)
        _state.value = NavState.Navigating(dest, route, progressOf(route, 0, 0.0, null))
        job = scope.launch { follow(dest, route, if (simulate) simulated(route) else gps.speed) }
    }

    /**
     * Cambiar el destino SIN dejar de navegar (elegiste otro punto en el mapa a medio camino): la ruta
     * actual sigue en pantalla mientras se calcula la nueva, y luego se cambia sola. Si la nueva no se
     * puede calcular (faltan datos de la zona, por ejemplo), se muestra como vista previa con el aviso.
     */
    fun redirect(place: Place) {
        val now = _state.value as? NavState.Navigating ?: return preview(place)
        redirectJob?.cancel()
        redirecting = true
        _state.value = now.copy(rerouting = true)
        redirectJob = scope.launch {
            val dest = place.copy(name = place.name.ifBlank { nameOf(place.point) ?: "Punto en el mapa" })
            val from = (_state.value as? NavState.Navigating)?.progress?.position ?: currentPosition()
            val route = from?.let { runCatching { engine.route(it, dest.point, gps.speed.value.bearing) }.getOrNull() }
            redirecting = false
            // Este trabajo ya terminó: que drive() no se cancele a sí mismo al limpiar.
            redirectJob = null
            if (route == null) {
                preview(dest)
                return@launch
            }
            if (!simulating) addRecent(dest)
            drive(dest, route, simulating)
        }
    }

    /** Un carro virtual que avanza por la ruta, una lectura por segundo, como el GPS. */
    private fun simulated(route: Route): kotlinx.coroutines.flow.Flow<GpsSpeed> = kotlinx.coroutines.flow.flow {
        var along = 0.0
        while (along <= route.totalMeters) {
            val (p, b) = pointAt(route, along)
            emit(GpsSpeed(permission = true, kmh = SIM_KMH.toFloat(), bearing = b, lat = p.latitude, lon = p.longitude, fresh = true))
            along += SIM_KMH / 3.6
            kotlinx.coroutines.delay(1_000)
        }
    }

    /** El punto (y el rumbo) a [along] metros del inicio de la ruta. */
    private fun pointAt(route: Route, along: Double): Pair<LatLong, Float> {
        val c = route.cumulative
        var i = java.util.Arrays.binarySearch(c, along).let { if (it >= 0) it else -it - 2 }.coerceIn(0, route.points.size - 2)
        if (route.points.size < 2) return route.points.first() to 0f
        val a = route.points[i]
        val b = route.points[i + 1]
        val len = (c[i + 1] - c[i]).takeIf { it > 0 } ?: 1.0
        val f = ((along - c[i]) / len).coerceIn(0.0, 1.0)
        val p = LatLong(a.latitude + (b.latitude - a.latitude) * f, a.longitude + (b.longitude - a.longitude) * f)
        return p to bearingOf(a, b)
    }

    fun stop() {
        job?.cancel()
        job = null
        redirectJob?.cancel()
        redirecting = false
        prefetchJob?.cancel()
        clearActive()
        _state.value = NavState.Idle
    }

    // ------------------------------------------------------------------ Continuar la ruta

    /** Si Android cerró Civic a medio camino (al quitar la llave), el destino que quedó pendiente. */
    val resumable: StateFlow<Place?> get() = _resumable.asStateFlow()

    /** Vuelve a calcular desde donde estás hacia el destino que quedó pendiente, y arranca. */
    fun continueRoute() {
        val place = _resumable.value ?: return
        _resumable.value = null
        job?.cancel()
        _state.value = NavState.Preview(place)
        job = scope.launch {
            val from = currentPosition()
            val route = try {
                from?.let { engine.route(it, place.point, gps.speed.value.bearing) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            // Sin ruta (faltan datos, sin GPS): la vista previa explica qué pasó y ofrece opciones.
            if (route != null) drive(place, route, simulate = false) else preview(place)
        }
    }

    fun dismissResume() {
        _resumable.value = null
        clearActive()
    }

    private fun saveActive(p: Place) {
        // commit (no apply): al quitar la llave la pantalla se apaga de golpe.
        sp.edit().putString(KEY_ACTIVE, p.toJson().toString()).putLong(KEY_ACTIVE_AT, System.currentTimeMillis()).commit()
    }

    private fun clearActive() {
        sp.edit().remove(KEY_ACTIVE).remove(KEY_ACTIVE_AT).commit()
    }

    private fun loadActive(): Place? {
        val p = loadPlace(KEY_ACTIVE) ?: return null
        val age = System.currentTimeMillis() - sp.getLong(KEY_ACTIVE_AT, 0L)
        val alreadyThere = map.lastPosition()?.let { meters(it, p.point) < ARRIVED_M * 5 } == true
        if (age > RESUME_MAX_MS || alreadyThere) {
            clearActive()
            return null
        }
        return p
    }

    // ------------------------------------------------------------------ Precarga

    private var prefetchJob: Job? = null

    /** Con la ruta lista, los mosaicos del recorrido se van dibujando con calma, a disco. */
    private fun prefetchRoute(route: Route) {
        prefetchJob?.cancel()
        prefetchJob = scope.launch {
            kotlinx.coroutines.delay(PREFETCH_DELAY_MS)
            com.civiclauncher.app.launcher.map.SharedMap.prefetch(route.points)
        }
    }

    // ------------------------------------------------------------------ Mientras navegas

    private suspend fun follow(dest: Place, first: Route, source: kotlinx.coroutines.flow.Flow<GpsSpeed>) {
        var route = first
        var segment = 0
        var offRoute = 0
        val backoff = RerouteBackoff()
        var noRoute = false
        var lastStreetLookup: Maneuver? = null
        var nextStreet: String? = null
        source.collect { fix ->
            val pos = fix.position() ?: return@collect
            val snap = snap(route, pos, segment)
            val off = snap.distance > OFF_ROUTE_M
            if (off) {
                offRoute++
            } else {
                offRoute = 0
                backoff.reset()
                noRoute = false
            }
            if (offRoute >= OFF_ROUTE_FIXES && backoff.canTry(android.os.SystemClock.elapsedRealtime())) {
                // Te saliste: nueva ruta desde aquí, hacia donde ya vas.
                _state.value = (_state.value as? NavState.Navigating)?.copy(rerouting = true) ?: return@collect
                val again = runCatching { engine.route(pos, dest.point, fix.bearing) }.getOrNull()
                if (again != null) {
                    route = again
                    segment = 0
                    lastStreetLookup = null
                    offRoute = 0
                    backoff.reset()
                    noRoute = false
                    prefetchRoute(again)
                } else {
                    // Cada intento es cálculo pesado: si falló, se espera cada vez más.
                    backoff.failed(android.os.SystemClock.elapsedRealtime())
                    noRoute = true
                }
                return@collect
            }
            segment = snap.segment
            val along = route.cumulative[segment] + snap.along
            val remaining = (route.totalMeters - along).coerceAtLeast(0.0)
            if (remaining < ARRIVED_M && !off) {
                _state.value = NavState.Arrived(dest)
                clearActive()
                prefetchJob?.cancel()
                // Llegaste: se deja de seguir el GPS (y el recorrido simulado termina).
                throw kotlinx.coroutines.CancellationException("llegó")
            }
            val (onRoute, heading) = pointAt(route, along)
            // Fuera de la ruta el carro se dibuja donde está de verdad, no pegado a una calle que ya dejó.
            val progress = progressOf(route, segment, along, nextStreet).copy(
                position = if (off) pos else onRoute,
                bearing = if (off) fix.bearing ?: heading else heading,
                noRoute = noRoute,
            )
            // El nombre de la calle de la siguiente vuelta se busca una vez por vuelta (lee el mapa).
            val next = progress.next
            if (next != null && next != lastStreetLookup) {
                lastStreetLookup = next
                nextStreet = null
                // 40 m después de la vuelta ya estás en la calle nueva (justo en la esquina, o dentro de una
                // glorieta, el mapa todavía diría la calle de la que vienes).
                val target = route.cumulative[next.index] + 40.0
                val afterIdx = (next.index until route.points.size).firstOrNull { route.cumulative[it] >= target } ?: route.points.lastIndex
                val after = route.points[afterIdx]
                scope.launch {
                    val name = runCatching { map.streetAt(after.latitude, after.longitude, null, sticky = false) }.getOrNull()
                    if (lastStreetLookup == next) {
                        nextStreet = name
                        (_state.value as? NavState.Navigating)?.let { s -> _state.value = s.copy(progress = s.progress.copy(nextStreet = name)) }
                    }
                }
            }
            _state.value = NavState.Navigating(dest, route, progress, rerouting = redirecting)
        }
    }

    private fun progressOf(route: Route, segment: Int, along: Double, street: String?): NavProgress {
        val next = route.maneuvers.firstOrNull { route.cumulative[it.index] > along + 3 }
        val toNext = next?.let { route.cumulative[it.index] - along } ?: 0.0
        val remaining = (route.totalMeters - along).coerceAtLeast(0.0)
        val seconds = if (route.totalMeters > 0) (route.totalSeconds * remaining / route.totalMeters).toInt() else 0
        return NavProgress(next, toNext, street, remaining, seconds, segment)
    }

    private fun bearingOf(a: LatLong, b: LatLong): Float {
        val dx = (b.longitude - a.longitude) * kotlin.math.cos(Math.toRadians(a.latitude))
        val dy = b.latitude - a.latitude
        return ((Math.toDegrees(kotlin.math.atan2(dx, dy)) + 360.0) % 360.0).toFloat()
    }

    private class Snap(val segment: Int, val along: Double, val distance: Double)

    /**
     * Dónde vas sobre la ruta: el tramo más cercano, buscando solo un poco hacia atrás y bastante
     * hacia adelante del último (así una calle que pasa cerca dos veces no te "regresa").
     */
    private fun snap(route: Route, pos: LatLong, from: Int): Snap {
        var best = Snap(from, 0.0, Double.MAX_VALUE)
        val start = (from - 3).coerceAtLeast(0)
        val end = (from + 120).coerceAtMost(route.points.lastIndex - 1)
        for (i in start..end) {
            val a = route.points[i]
            val b = route.points[i + 1]
            val d = distanceToSegmentM(pos.latitude, pos.longitude, a, b)
            if (d < best.distance) {
                val segLen = route.cumulative[i + 1] - route.cumulative[i]
                val toA = meters(a, pos)
                val along = kotlin.math.sqrt((toA * toA - d * d).coerceAtLeast(0.0)).coerceAtMost(segLen)
                best = Snap(i, along, d)
            }
        }
        if (route.points.size < 2) best = Snap(0, 0.0, meters(route.points.first(), pos))
        return best
    }

    private fun GpsSpeed.position(): LatLong? = if (fresh && lat != null && lon != null) LatLong(lat, lon) else null

    private fun currentPosition(): LatLong? = gps.speed.value.position() ?: map.lastPosition()

    private suspend fun nameOf(p: LatLong): String? = runCatching { map.streetAt(p.latitude, p.longitude, null, sticky = false) }.getOrNull()

    // ------------------------------------------------------------------ Favoritos y recientes

    private val sp = context.getSharedPreferences("nav", Context.MODE_PRIVATE)
    private val _home = MutableStateFlow(loadPlace("home"))
    val home: StateFlow<Place?> = _home.asStateFlow()
    private val _work = MutableStateFlow(loadPlace("work"))
    val work: StateFlow<Place?> = _work.asStateFlow()
    private val _recent = MutableStateFlow(loadRecent())
    val recent: StateFlow<List<Place>> = _recent.asStateFlow()
    private val _resumable = MutableStateFlow(loadActive())

    fun setHome(p: Place) = savePlace("home", p).also { _home.value = p }
    fun setWork(p: Place) = savePlace("work", p).also { _work.value = p }
    fun clearHome() { sp.edit().remove("home").apply(); _home.value = null }
    fun clearWork() { sp.edit().remove("work").apply(); _work.value = null }

    private fun addRecent(p: Place) {
        val list = (listOf(p) + _recent.value.filterNot { meters(it.point, p.point) < 50 }).take(MAX_RECENT)
        _recent.value = list
        sp.edit().putString("recent", JSONArray(list.map { it.toJson() }).toString()).apply()
    }

    private fun Place.toJson() = JSONObject().put("n", name).put("a", point.latitude).put("o", point.longitude)
    private fun JSONObject.toPlace() = Place(optString("n"), LatLong(getDouble("a"), getDouble("o")))
    private fun savePlace(key: String, p: Place) = sp.edit().putString(key, p.toJson().toString()).apply()
    private fun loadPlace(key: String): Place? = runCatching { JSONObject(sp.getString(key, null)!!).toPlace() }.getOrNull()
    private fun loadRecent(): List<Place> = runCatching {
        val a = JSONArray(sp.getString("recent", "[]"))
        (0 until a.length()).map { a.getJSONObject(it).toPlace() }
    }.getOrDefault(emptyList())

    // ------------------------------------------------------------------ Datos de rutas

    private val _download = MutableStateFlow<TilesDownload>(TilesDownload.Idle)
    val download: StateFlow<TilesDownload> = _download.asStateFlow()

    /** Borra todos los datos de rutas descargados. */
    fun deleteTiles() {
        engine.segmentsDir.listFiles()?.forEach { it.delete() }
    }

    /** Cuadros ya descargados (nombre → MB). */
    fun installedTiles(): Map<String, Long> =
        engine.segmentsDir.listFiles { f -> f.name.endsWith(".rd5") }?.associate { it.name to it.length() / 1_048_576 }.orEmpty()

    /**
     * Descarga cuadros de datos de rutas (por WiFi, una sola vez). Se bajan a un ".part" y solo al
     * terminar completos se renombran: una descarga cortada nunca deja un archivo dañado.
     */
    fun downloadTiles(names: List<String>, onDone: () -> Unit = {}) {
        if (_download.value is TilesDownload.Running) return
        scope.launch {
            val todo = names.filter { !File(engine.segmentsDir, it).exists() }
            try {
                todo.forEachIndexed { i, name ->
                    _download.value = TilesDownload.Running(name, i, todo.size, 0f)
                    withContext(Dispatchers.IO) { fetch(name) { p -> _download.value = TilesDownload.Running(name, i, todo.size, p) } }
                }
                _download.value = TilesDownload.Idle
                onDone()
            } catch (e: Exception) {
                _download.value = TilesDownload.Failed(
                    when (e) {
                        is java.net.UnknownHostException -> "Sin internet. Conecta la pantalla al WiFi."
                        is java.io.FileNotFoundException -> "Esa zona no tiene datos de rutas"
                        else -> "La descarga falló; vuelve a intentarlo"
                    },
                )
            }
        }
    }

    private fun fetch(name: String, progress: (Float) -> Unit) {
        val part = File(engine.segmentsDir, "$name.part")
        val conn = (URL(RoutingData.BASE_URL + name).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "CivicLauncher")
        }
        try {
            if (conn.responseCode == 404) throw java.io.FileNotFoundException(name)
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    var last = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        if (total > 0 && read - last > 512 * 1024) {
                            last = read
                            progress(read.toFloat() / total)
                        }
                    }
                }
            }
            if (total > 0 && part.length() != total) error("incompleta")
            if (!part.renameTo(File(engine.segmentsDir, name))) error("no se pudo guardar")
        } finally {
            conn.disconnect()
            if (part.exists()) part.delete()
        }
    }

    private companion object {
        /** Más de esto lejos de la ruta cuenta como "fuera de la ruta" (el GPS tiembla ~10-20 m). */
        const val OFF_ROUTE_M = 50.0
        /** Lecturas seguidas fuera de la ruta antes de recalcular (~4 s): una curva no lo dispara. */
        const val OFF_ROUTE_FIXES = 4
        /** A esta distancia del destino ya llegaste. */
        const val ARRIVED_M = 30.0
        const val MAX_RECENT = 6
        /** Velocidad del recorrido simulado. */
        const val SIM_KMH = 50.0
        const val KEY_ACTIVE = "active"
        const val KEY_ACTIVE_AT = "active_at"
        /** Pasado este tiempo ya no tiene sentido ofrecer la ruta de antes. */
        const val RESUME_MAX_MS = 6 * 3_600_000L
        /** Que primero termine de calcular y de dibujar la vista normal. */
        const val PREFETCH_DELAY_MS = 5_000L
    }
}
