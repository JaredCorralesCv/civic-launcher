package com.civiclauncher.app.launcher.nav

import com.civiclauncher.app.launcher.map.MapRepository
import com.civiclauncher.app.launcher.map.MapStatus
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
import org.mapsforge.core.model.Tile
import org.mapsforge.core.util.MercatorProjection
import org.mapsforge.map.reader.MapFile
import java.io.File
import java.text.Normalizer
import kotlin.coroutines.coroutineContext

/** Un resultado de búsqueda. */
data class SearchHit(val name: String, val kind: String, val point: LatLong, val meters: Double)

sealed interface IndexState {
    data object Empty : IndexState
    /** Leyendo del disco el índice ya armado (unos segundos). */
    data object Loading : IndexState
    data class Building(val progress: Float) : IndexState
    data class Ready(val count: Int) : IndexState
}

/**
 * Búsqueda de lugares y calles SIN internet, sacada del mismo mapa que ya está descargado.
 *
 * La primera vez (o si te mueves lejos) se recorre el mapa en ~30 km a tu alrededor y se guardan los
 * nombres (restaurantes, gasolineras, hospitales, plazas, calles…) en un índice pequeño en el disco.
 * Tarda unos segundos una sola vez; luego buscar es instantáneo.
 *
 * No hay números de casa: el mapa de OpenStreetMap casi no los tiene en México. Se busca por nombre
 * del lugar o de la calle.
 */
class SearchIndex(private val map: MapRepository, private val scope: CoroutineScope) {
    // Coordenadas en Float (≈1 m de precisión, de sobra) y el tipo compartido: ~96 mil de estos.
    private class Entry(val name: String, val norm: String, val kind: String, val lat: Float, val lon: Float)

    private val _state = MutableStateFlow<IndexState>(IndexState.Empty)
    val state: StateFlow<IndexState> = _state.asStateFlow()
    @Volatile private var entries: List<Entry> = emptyList()
    @Volatile private var center: LatLong? = null
    private var job: Job? = null
    private var releaseJob: Job? = null
    /** Cuántas pantallas de búsqueda están abiertas (mientras haya alguna, el índice sigue en memoria). */
    @Volatile private var users = 0
    private val cache = File(map.dir, "busqueda.tsv")

    /** Si el índice ya está cargado y sirve para buscar (no solo guardado en el disco). */
    val loaded: Boolean get() = entries.isNotEmpty()

    /**
     * En segundo plano: deja el índice de la zona de [near] armado EN EL DISCO, sin cargarlo en la
     * memoria (~20 MB que no hacen falta hasta que buscas). Si ya está, solo lee cuántos lugares tiene.
     */
    fun prebuild(near: LatLong) {
        if (job?.isActive == true) return
        if (_state.value is IndexState.Ready && center?.let { meters(it, near) < REBUILD_KM * 1_000 } == true) return
        job = scope.launch(Dispatchers.IO) {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            val head = readHeader()
            if (head != null && meters(head.first, near) <= REBUILD_KM * 1_000) {
                center = head.first
                _state.value = IndexState.Ready(head.second)
                return@launch
            }
            build(near)
        }
    }

    /**
     * Al abrir la búsqueda: carga el índice en la memoria (o lo arma si no hay). Llama a [release]
     * al cerrarla.
     */
    fun prepare(near: LatLong) {
        users++
        releaseJob?.cancel()
        val c = center
        if (c != null && meters(c, near) < REBUILD_KM * 1_000 && entries.isNotEmpty()) return
        if (job?.isActive == true) {
            // Se está armando en segundo plano: que al terminar se quede en memoria (users > 0).
            if (_state.value is IndexState.Building) return
            job?.cancel()
        }
        job = scope.launch(Dispatchers.IO) {
            // Prioridad baja: que armar el índice no le quite procesador a la música ni al mapa.
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            if (loadCache(near)) return@launch
            build(near)
        }
    }

    /**
     * Se cerró la búsqueda: el índice sale de la memoria 2 minutos después (por si vuelves a buscar
     * enseguida), si no se abrió otra vez.
     */
    fun release() {
        users = (users - 1).coerceAtLeast(0)
        releaseJob?.cancel()
        releaseJob = scope.launch {
            kotlinx.coroutines.delay(RELEASE_MS)
            if (users == 0) entries = emptyList()
        }
    }

    /** Android pide memoria: si nadie está buscando, el índice sale ya (sigue en el disco). */
    fun trim() {
        if (users == 0) {
            releaseJob?.cancel()
            entries = emptyList()
        }
    }

    fun search(query: String, near: LatLong, limit: Int = 40): List<SearchHit> {
        val q = normalize(query)
        if (q.length < 2) return emptyList()
        val tokens = q.split(' ').filter { it.isNotBlank() }
        return entries.asSequence()
            .filter { e -> tokens.all { e.norm.contains(it) } }
            .map { e ->
                val p = LatLong(e.lat.toDouble(), e.lon.toDouble())
                val d = meters(near, p)
                // Lo que empieza igual que lo que escribiste va primero; luego, lo más cercano.
                val score = d * (if (e.norm.startsWith(q) || e.norm.contains(" $q")) 0.3 else 1.0)
                score to SearchHit(e.name, e.kind, p, d)
            }
            .sortedBy { it.first }
            .take(limit)
            .map { it.second }
            .toList()
    }

    private suspend fun build(near: LatLong) {
        val file = (map.status.value as? MapStatus.Ready)?.file ?: return
        _state.value = IndexState.Building(0f)
        val out = ArrayList<Entry>(20_000)
        val seen = LongHashSet()
        val mf = MapFile(file, "es")
        try {
            val z: Byte = 14
            val dLat = RADIUS_KM / 111.0
            val dLon = RADIUS_KM / (111.0 * kotlin.math.cos(Math.toRadians(near.latitude)))
            val x0 = MercatorProjection.longitudeToTileX(near.longitude - dLon, z)
            val x1 = MercatorProjection.longitudeToTileX(near.longitude + dLon, z)
            val y0 = MercatorProjection.latitudeToTileY(near.latitude + dLat, z)
            val y1 = MercatorProjection.latitudeToTileY(near.latitude - dLat, z)
            val cx = MercatorProjection.longitudeToTileX(near.longitude, z)
            val cy = MercatorProjection.latitudeToTileY(near.latitude, z)
            // De adentro hacia afuera: lo más cercano queda listo primero y ya se puede buscar mientras
            // se termina el resto (en la pantalla del carro, armar todo tarda unos minutos).
            val tiles = (y0..y1).flatMap { y -> (x0..x1).map { x -> x to y } }
                .sortedBy { (x, y) -> maxOf(kotlin.math.abs(x - cx), kotlin.math.abs(y - cy)) }
            tiles.forEachIndexed { i, (x, y) ->
                if (!coroutineContext.isActive) return
                val r = mf.readNamedItems(Tile(x, y, z, 256)) ?: return@forEachIndexed
                for (poi in r.pois) {
                    val name = poi.tags.firstOrNull { it.key == "name" }?.value ?: continue
                    add(out, seen, name, kindOf(poi.tags.associate { it.key to it.value }), poi.position.latitude, poi.position.longitude, 150.0)
                }
                for (way in r.ways) {
                    val name = way.tags.firstOrNull { it.key == "name" }?.value ?: continue
                    val tags = way.tags.associate { it.key to it.value }
                    // Solo lo que sirve como destino: calles, comercios, servicios, parques, edificios con
                    // nombre. Ríos, vías del tren o terrenos solo llenarían el índice.
                    if (USEFUL.none { tags.containsKey(it) }) continue
                    val p = way.labelPosition ?: way.latLongs.firstOrNull()?.let { it.getOrNull(it.size / 2) } ?: continue
                    // Una calle aparece en muchos pedazos: se guarda un punto por cada ~1 km de calle.
                    val cell = if (tags.containsKey("highway")) 1_000.0 else 150.0
                    add(out, seen, name, kindOf(tags), p.latitude, p.longitude, cell)
                }
                if (i % 25 == 24) {
                    // Resultados parciales solo si alguien está buscando ahora mismo.
                    if (users > 0) entries = ArrayList(out)
                    _state.value = IndexState.Building((i + 1).toFloat() / tiles.size)
                }
            }
        } finally {
            runCatching { mf.close() }
        }
        center = near
        saveCache(near, out)
        // Si nadie está buscando (se armó en segundo plano), no se queda en la memoria: ya está en el disco.
        entries = if (users > 0) out else emptyList()
        _state.value = IndexState.Ready(out.size)
    }

    private fun add(out: MutableList<Entry>, seen: LongHashSet, name: String, kind: String, lat: Double, lon: Double, cellM: Double) {
        val norm = normalize(name)
        val cellDeg = cellM / 111_000.0
        // Una huella de 64 bits en lugar del texto: ~96 mil textos largos eran ~15 MB de pico al armar.
        var key = FNV_START
        for (ch in norm) key = fnv(key, ch.code.toLong())
        for (ch in kind) key = fnv(key, ch.code.toLong())
        key = fnv(fnv(key, (lat / cellDeg).toLong()), (lon / cellDeg).toLong())
        if (seen.add(key)) out += Entry(name, norm, kind, lat.toFloat(), lon.toFloat())
    }

    /**
     * Formato: primera línea "lat, lon, cuántos"; luego "nombre, tipo, lat, lon, nombre normalizado"
     * (guardar el normalizado ahorra calcularlo al cargar, que era lo más lento).
     */
    private fun saveCache(near: LatLong, list: List<Entry>) = runCatching {
        cache.bufferedWriter().use { w ->
            w.write("${near.latitude}\t${near.longitude}\t${list.size}\n")
            for (e in list) w.write("${e.name.replace('\t', ' ')}\t${e.kind}\t${e.lat}\t${e.lon}\t${e.norm}\n")
        }
    }

    /** Solo la primera línea del índice guardado: dónde está centrado y cuántos lugares tiene. */
    private fun readHeader(): Pair<LatLong, Int>? = runCatching {
        if (!cache.exists()) return null
        val head = cache.bufferedReader().use { it.readLine() }?.split('\t') ?: return null
        // Índices viejos (sin el normalizado guardado): se rearman una vez.
        if (head.size < 3) return null
        LatLong(head[0].toDouble(), head[1].toDouble()) to head[2].toInt()
    }.getOrNull()

    private fun loadCache(near: LatLong): Boolean = runCatching {
        val head = readHeader() ?: return false
        if (meters(head.first, near) > REBUILD_KM * 1_000) return false
        _state.value = IndexState.Loading
        // Línea por línea: leer el archivo entero de golpe (6 MB) duplicaba la memoria un rato.
        val kinds = HashMap<String, String>()
        val list = ArrayList<Entry>(head.second)
        cache.bufferedReader().useLines { lines ->
            lines.drop(1).forEach { l ->
                val p = l.split('\t')
                if (p.size >= 5) {
                    list += Entry(p[0], p[4], kinds.getOrPut(p[1]) { p[1] }, p[2].toFloat(), p[3].toFloat())
                }
            }
        }
        entries = list
        center = head.first
        _state.value = IndexState.Ready(list.size)
        true
    }.getOrDefault(false).also { if (!it && _state.value == IndexState.Loading) _state.value = IndexState.Empty }

    private companion object {
        /** Radio que cubre el índice: la ciudad y sus alrededores. */
        const val RADIUS_KM = 20.0

        /** Etiquetas que hacen que algo con nombre valga la pena como destino. */
        val USEFUL = listOf("highway", "amenity", "shop", "tourism", "leisure", "building", "place", "public_transport")
        /** Si te alejas más que esto del centro del índice, se arma uno nuevo. */
        const val REBUILD_KM = 15.0

        /** Cuánto se queda el índice en memoria después de cerrar la búsqueda. */
        const val RELEASE_MS = 120_000L

        val KINDS = mapOf(
            "fuel" to "Gasolinera", "hospital" to "Hospital", "clinic" to "Clínica", "pharmacy" to "Farmacia",
            "restaurant" to "Restaurante", "fast_food" to "Comida rápida", "cafe" to "Café", "bar" to "Bar",
            "bank" to "Banco", "atm" to "Cajero", "school" to "Escuela", "university" to "Universidad",
            "parking" to "Estacionamiento", "police" to "Policía", "place_of_worship" to "Iglesia",
            "cinema" to "Cine", "theatre" to "Teatro", "marketplace" to "Mercado", "post_office" to "Correos",
            "supermarket" to "Supermercado", "mall" to "Plaza comercial", "convenience" to "Tienda",
            "hotel" to "Hotel", "museum" to "Museo", "park" to "Parque", "stadium" to "Estadio",
        )

        fun kindOf(tags: Map<String, String>): String {
            for (k in listOf("amenity", "shop", "tourism", "leisure")) tags[k]?.let { v -> KINDS[v]?.let { return it } }
            return when {
                tags.containsKey("highway") -> "Calle"
                tags.containsKey("shop") -> "Tienda"
                tags.containsKey("amenity") -> "Servicio"
                tags.containsKey("tourism") -> "Turismo"
                tags.containsKey("leisure") -> "Recreación"
                tags.containsKey("place") -> "Colonia o lugar"
                else -> "Lugar"
            }
        }

        // Compiladas una sola vez: se usan con cada uno de los ~100 mil nombres del índice (antes se
        // compilaban tres por nombre y cargar el índice tardaba ~25 s).
        private val MARKS = Regex("\\p{Mn}+")
        private val OTHER = Regex("[^a-z0-9ñ ]")
        private val SPACES = Regex(" +")

        fun normalize(s: String): String =
            Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(MARKS, "").replace(OTHER, " ").replace(SPACES, " ").trim()
    }
}

private const val FNV_START = -3750763034362895579L
private fun fnv(hash: Long, value: Long): Long = (hash xor value) * 1099511628211L
