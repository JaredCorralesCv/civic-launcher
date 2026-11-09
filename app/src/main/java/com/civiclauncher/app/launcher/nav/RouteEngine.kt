package com.civiclauncher.app.launcher.nav

import android.app.ActivityManager
import android.content.Context
import btools.router.CivicRouteBridge
import btools.router.OsmNodeNamed
import btools.router.RoutingContext
import btools.router.RoutingEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.mapsforge.core.model.LatLong
import java.io.File
import kotlin.math.floor

/** Una indicación de la ruta: qué hacer y en qué punto de la ruta. */
data class Maneuver(
    /** Índice en [Route.points] donde ocurre. */
    val index: Int,
    val kind: Turn,
    /** Para glorietas: qué salida tomar (0 si no aplica). */
    val exit: Int = 0,
)

enum class Turn { STRAIGHT, LEFT, SLIGHT_LEFT, SHARP_LEFT, RIGHT, SLIGHT_RIGHT, SHARP_RIGHT, KEEP_LEFT, KEEP_RIGHT, U_TURN, ROUNDABOUT, EXIT_LEFT, EXIT_RIGHT, ARRIVE }

data class Route(
    val points: List<LatLong>,
    /** Metros acumulados desde el inicio hasta cada punto (mismo tamaño que [points]). */
    val cumulative: DoubleArray,
    val maneuvers: List<Maneuver>,
    val totalMeters: Double,
    val totalSeconds: Int,
) {
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

/** Por qué no salió una ruta, en palabras para la pantalla. */
class RouteException(message: String, val missingTiles: List<String> = emptyList()) : Exception(message)

/**
 * Calcula rutas en auto SIN internet con BRouter (licencia MIT), el motor que usan apps de
 * navegación offline en Android. Está hecho para teléfonos con poca memoria: lee los datos de las
 * calles del disco por partes, en lugar de cargarlos completos.
 *
 * Datos que necesita (en Android/data/com.civiclauncher.app/files/routing):
 *  - profiles2/: el perfil "auto rápido" y la tabla de etiquetas (vienen dentro de Civic).
 *  - segments4/: los datos de las calles, en cuadros de 5°×5° (la CDMX está en W100_N15, ~41 MB).
 *    Se descargan una vez con [RoutingData].
 */
class RouteEngine(private val context: Context) {
    val baseDir: File = (context.getExternalFilesDir("routing") ?: File(context.filesDir, "routing")).apply { mkdirs() }
    val segmentsDir = File(baseDir, "segments4").apply { mkdirs() }
    private val profilesDir = File(baseDir, "profiles2").apply { mkdirs() }
    // Una ruta a la vez: BRouter usa bastante memoria mientras calcula y la pantalla tiene 1 GB.
    private val lock = Mutex()

    /** Copia el perfil y la tabla de etiquetas que vienen dentro de Civic (solo si cambiaron). */
    private fun ensureProfiles(): File {
        for (name in listOf("car-fast.brf", "lookups.dat")) {
            val out = File(profilesDir, name)
            val bytes = context.assets.open("brouter/$name").use { it.readBytes() }
            if (!out.exists() || out.length() != bytes.size.toLong()) out.writeBytes(bytes)
        }
        return File(profilesDir, "car-fast.brf")
    }

    /**
     * Ruta en auto de [from] a [to]. [heading] (grados, 0 = norte) ayuda a que la ruta salga hacia
     * donde ya vas, en vez de pedirte una vuelta en U. Lanza [RouteException] si falta algo.
     */
    suspend fun route(from: LatLong, to: LatLong, heading: Float? = null): Route = lock.withLock {
        withContext(Dispatchers.Default) {
            val missing = RoutingData.tilesFor(from, to).filter { !File(segmentsDir, it).exists() }
            if (missing.isNotEmpty()) throw RouteException("Faltan los datos de rutas de esta zona", missing)
            val profile = ensureProfiles()
            val rc = RoutingContext().apply {
                localFunction = profile.path
                memoryclass = (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).memoryClass
                turnInstructionMode = 1
                if (heading != null) {
                    startDirection = Math.round(heading)
                    startDirectionValid = true
                }
            }
            val engine = RoutingEngine(null, null, segmentsDir, listOf(node(from, "desde"), node(to, "hasta")), rc)
            engine.quite = true
            engine.doRun(MAX_RUN_MS)
            val track = engine.foundTrack
            if (track == null || track.nodes.isNullOrEmpty()) {
                throw RouteException(friendly(engine.errorMessage))
            }
            val points = track.nodes.map { LatLong(it.iLat / 1e6 - 90.0, it.iLon / 1e6 - 180.0) }
            val cumulative = DoubleArray(points.size)
            for (i in 1 until points.size) cumulative[i] = cumulative[i - 1] + meters(points[i - 1], points[i])
            val maneuvers = CivicRouteBridge.hints(track).mapNotNull { h ->
                val kind = turnOf(h[1]) ?: return@mapNotNull null
                Maneuver(h[0].coerceIn(0, points.lastIndex), kind, h[2])
            }.filter { it.kind != Turn.STRAIGHT } + Maneuver(points.lastIndex, Turn.ARRIVE)
            Route(points, cumulative, maneuvers.distinctBy { it.index }, cumulative.last(), track.totalSeconds)
        }
    }

    private fun node(p: LatLong, name: String) = OsmNodeNamed().apply {
        this.name = name
        ilon = ((p.longitude + 180.0) * 1e6 + 0.5).toInt()
        ilat = ((p.latitude + 90.0) * 1e6 + 0.5).toInt()
    }

    private fun friendly(msg: String?): String = when {
        msg == null -> "No se encontró una ruta"
        msg.contains("not found", true) || msg.contains("no track", true) -> "No hay un camino en auto hasta ese punto"
        msg.contains("timeout", true) -> "La ruta es muy larga para calcularla aquí"
        msg.contains("datafile", true) -> "Faltan los datos de rutas de esta zona"
        else -> "No se pudo calcular la ruta"
    }

    private fun turnOf(cmd: Int): Turn? = when (cmd) {
        1 -> Turn.STRAIGHT
        2 -> Turn.LEFT
        3 -> Turn.SLIGHT_LEFT
        4 -> Turn.SHARP_LEFT
        5 -> Turn.RIGHT
        6 -> Turn.SLIGHT_RIGHT
        7 -> Turn.SHARP_RIGHT
        8 -> Turn.KEEP_LEFT
        9 -> Turn.KEEP_RIGHT
        10, 11, 15 -> Turn.U_TURN
        13, 14 -> Turn.ROUNDABOUT
        17 -> Turn.EXIT_LEFT
        18 -> Turn.EXIT_RIGHT
        100 -> Turn.ARRIVE
        else -> null
    }

    private companion object {
        /** Tope para calcular: en el Cortex-A7, una ruta dentro de la ciudad tarda pocos segundos. */
        const val MAX_RUN_MS = 60_000L
    }
}

/** Metros entre dos puntos (fórmula de haversine). */
fun meters(a: LatLong, b: LatLong): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(b.latitude - a.latitude)
    val dLon = Math.toRadians(b.longitude - a.longitude)
    val s = kotlin.math.sin(dLat / 2).let { it * it } +
        kotlin.math.cos(Math.toRadians(a.latitude)) * kotlin.math.cos(Math.toRadians(b.latitude)) * kotlin.math.sin(dLon / 2).let { it * it }
    return 2 * r * kotlin.math.asin(kotlin.math.sqrt(s.coerceIn(0.0, 1.0)))
}

/** Cuadros de datos de rutas (5°×5°) y su descarga. */
object RoutingData {
    const val BASE_URL = "https://brouter.de/brouter/segments4/"

    /** Nombre del cuadro que contiene el punto, por ejemplo W100_N15.rd5 (CDMX). */
    fun tileOf(lat: Double, lon: Double): String {
        val lonBase = (floor((lon + 180.0) / 5.0) * 5.0 - 180.0).toInt()
        val latBase = (floor((lat + 90.0) / 5.0) * 5.0 - 90.0).toInt()
        val lo = if (lonBase < 0) "W${-lonBase}" else "E$lonBase"
        val la = if (latBase < 0) "S${-latBase}" else "N$latBase"
        return "${lo}_$la.rd5"
    }

    /** Los cuadros que cubre el rectángulo entre los dos puntos (con un margen de ~20 km). */
    fun tilesFor(a: LatLong, b: LatLong): List<String> {
        val margin = 0.2
        val minLat = minOf(a.latitude, b.latitude) - margin
        val maxLat = maxOf(a.latitude, b.latitude) + margin
        val minLon = minOf(a.longitude, b.longitude) - margin
        val maxLon = maxOf(a.longitude, b.longitude) + margin
        val out = LinkedHashSet<String>()
        var lat = minLat
        while (true) {
            var lon = minLon
            while (true) {
                out += tileOf(lat.coerceAtMost(maxLat), lon.coerceAtMost(maxLon))
                if (lon >= maxLon) break
                lon = (lon + 5.0).coerceAtMost(maxLon)
            }
            if (lat >= maxLat) break
            lat = (lat + 5.0).coerceAtMost(maxLat)
        }
        return out.toList()
    }

    /** Los cuadros alrededor de un punto (el suyo y los 8 vecinos): una región de ~1,500 km. */
    fun regionAround(p: LatLong): List<String> = buildList {
        for (dLat in -1..1) for (dLon in -1..1) add(tileOf(p.latitude + dLat * 5.0, p.longitude + dLon * 5.0))
    }.distinct()
}
