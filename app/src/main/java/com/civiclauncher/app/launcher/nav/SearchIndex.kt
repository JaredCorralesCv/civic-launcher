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
    private class Entry(val name: String, val norm: String, val kind: String, val lat: Double, val lon: Double)

    private val _state = MutableStateFlow<IndexState>(IndexState.Empty)
    val state: StateFlow<IndexState> = _state.asStateFlow()
    @Volatile private var entries: List<Entry> = emptyList()
    private var center: LatLong? = null
    private var job: Job? = null
    private val cache = File(map.dir, "busqueda.tsv")

    /**
     * Asegura un índice para la zona de [near] (lo carga del disco o lo arma). Civic lo llama solo
     * en segundo plano cuando hay mapa y ubicación, para que al buscar ya esté listo.
     */
    fun prepare(near: LatLong) {
        val c = center
        if (c != null && meters(c, near) < REBUILD_KM * 1_000 && entries.isNotEmpty()) return
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            // Prioridad baja: que armar el índice no le quite procesador a la música ni al mapa.
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            if (loadCache(near)) return@launch
            build(near)
        }
    }

    fun search(query: String, near: LatLong, limit: Int = 40): List<SearchHit> {
        val q = normalize(query)
        if (q.length < 2) return emptyList()
        val tokens = q.split(' ').filter { it.isNotBlank() }
        return entries.asSequence()
            .filter { e -> tokens.all { e.norm.contains(it) } }
            .map { e ->
                val d = meters(near, LatLong(e.lat, e.lon))
                // Lo que empieza igual que lo que escribiste va primero; luego, lo más cercano.
                val score = d * (if (e.norm.startsWith(q) || e.norm.contains(" $q")) 0.3 else 1.0)
                score to SearchHit(e.name, e.kind, LatLong(e.lat, e.lon), d)
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
        val seen = HashSet<String>()
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
                    entries = ArrayList(out)
                    _state.value = IndexState.Building((i + 1).toFloat() / tiles.size)
                }
            }
        } finally {
            runCatching { mf.close() }
        }
        entries = out
        center = near
        _state.value = IndexState.Ready(out.size)
        saveCache(near, out)
    }

    private fun add(out: MutableList<Entry>, seen: MutableSet<String>, name: String, kind: String, lat: Double, lon: Double, cellM: Double) {
        val norm = normalize(name)
        val cellDeg = cellM / 111_000.0
        val key = "$norm|$kind|${(lat / cellDeg).toLong()}|${(lon / cellDeg).toLong()}"
        if (seen.add(key)) out += Entry(name, norm, kind, lat, lon)
    }

    private fun saveCache(near: LatLong, list: List<Entry>) = runCatching {
        cache.bufferedWriter().use { w ->
            w.write("${near.latitude}\t${near.longitude}\n")
            for (e in list) w.write("${e.name.replace('\t', ' ')}\t${e.kind}\t${e.lat}\t${e.lon}\n")
        }
    }

    private fun loadCache(near: LatLong): Boolean = runCatching {
        if (!cache.exists()) return false
        _state.value = IndexState.Loading
        val lines = cache.readLines()
        val head = lines.firstOrNull()?.split('\t') ?: return false
        val c = LatLong(head[0].toDouble(), head[1].toDouble())
        if (meters(c, near) > REBUILD_KM * 1_000) return false
        entries = lines.drop(1).mapNotNull { l ->
            val p = l.split('\t')
            if (p.size < 4) null else Entry(p[0], normalize(p[0]), p[1], p[2].toDouble(), p[3].toDouble())
        }
        center = c
        _state.value = IndexState.Ready(entries.size)
        true
    }.getOrDefault(false).also { if (!it && _state.value == IndexState.Loading) _state.value = IndexState.Empty }

    private companion object {
        /** Radio que cubre el índice: la ciudad y sus alrededores. */
        const val RADIUS_KM = 20.0

        /** Etiquetas que hacen que algo con nombre valga la pena como destino. */
        val USEFUL = listOf("highway", "amenity", "shop", "tourism", "leisure", "building", "place", "public_transport")
        /** Si te alejas más que esto del centro del índice, se arma uno nuevo. */
        const val REBUILD_KM = 15.0

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
