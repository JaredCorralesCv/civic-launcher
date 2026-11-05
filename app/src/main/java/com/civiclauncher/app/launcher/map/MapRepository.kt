package com.civiclauncher.app.launcher.map

import android.app.Application
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import com.civiclauncher.app.launcher.data.GpsSpeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.Tile
import org.mapsforge.core.util.MercatorProjection
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.reader.MapFile
import java.io.File
import kotlin.math.cos
import kotlin.math.sqrt

sealed interface MapStatus {
    data object Missing : MapStatus
    /** [progress] null mientras el servidor aún no dice el tamaño total. */
    data class Downloading(val progress: Float?, val downloadedMb: Long) : MapStatus
    data class Importing(val progress: Float) : MapStatus
    data class Ready(val file: File) : MapStatus
    data class Error(val message: String) : MapStatus
}

/**
 * Mapa sin internet. Usa los mapas oficiales de Mapsforge (OpenStreetMap), que se dibujan en la
 * pantalla con el motor 2D de Android: funciona en la Mali-450 (solo OpenGL ES 2.0) y sin datos.
 *
 * El archivo vive en la carpeta privada de Civic (Android/data/com.civiclauncher.app/files/maps).
 * Se consigue de tres formas: descargarlo por WiFi desde la pantalla, importarlo desde una memoria
 * USB, o copiarlo por adb desde la PC.
 */
class MapRepository(private val context: Context, private val scope: CoroutineScope) {
    val dir: File = (context.getExternalFilesDir("maps") ?: File(context.filesDir, "maps")).apply { mkdirs() }
    private val sp = context.getSharedPreferences("map", Context.MODE_PRIVATE)
    private val _status = MutableStateFlow<MapStatus>(MapStatus.Missing)
    val status: StateFlow<MapStatus> = _status.asStateFlow()
    private var pollJob: Job? = null

    init {
        // Mapsforge necesita esto una vez antes de crear cualquier mapa.
        // Ajustes para el Cortex-A7 de 4 núcleos y su poca memoria (antes de crear la fábrica):
        //  - 2 hilos dibujando mosaicos (de fábrica es 1); los otros 2 núcleos quedan para la interfaz
        //    y el audio.
        //  - Mosaicos de 16 bits: la mitad de memoria y de ancho de banda por mosaico. El mapa usa
        //    colores lisos, así que no se nota.
        //  - Al hacer zoom, el mosaico de respaldo se dibuja rápido, no "bonito".
        org.mapsforge.core.util.Parameters.NUMBER_OF_THREADS = 2
        org.mapsforge.core.util.Parameters.ANDROID_32BIT_COLOR = false
        org.mapsforge.core.util.Parameters.PARENT_TILES_RENDERING = org.mapsforge.core.util.Parameters.ParentTilesRendering.SPEED
        AndroidGraphicFactory.createInstance(context.applicationContext as Application)
        refresh()
    }

    /** El mapa más grande ya completo (las descargas e importaciones a medias no terminan en .map). */
    private fun readyFile(): File? =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".map") && f.length() > 1_000_000 }
            ?.maxByOrNull { it.length() }

    fun refresh() {
        val ready = readyFile()
        when {
            ready != null -> {
                // Ya hay mapa (copiado por adb o importado de USB): una descarga pendiente sobra y,
                // si siguiera viva, taparía el mapa con "Descargando".
                dropPendingDownload()
                _status.value = MapStatus.Ready(ready)
            }
            sp.getLong("download_id", -1) >= 0 -> watchDownload()
            _status.value !is MapStatus.Importing -> _status.value = MapStatus.Missing
        }
    }

    // ------------------------------------------------------------------ Descarga por WiFi

    /** Descarga el mapa de México (686 MB) solo por WiFi, para no gastar los datos del celular. */
    fun startDownload() {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        File(dir, PARTIAL).delete()
        val request = DownloadManager.Request(Uri.parse(MEXICO_URL))
            .setTitle("Mapa de México para Civic")
            .setDescription("Mapa sin internet (OpenStreetMap)")
            .setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(context, "maps", PARTIAL)
        val id = runCatching { dm.enqueue(request) }.getOrElse {
            _status.value = MapStatus.Error("No se pudo iniciar la descarga (${it.message})")
            return
        }
        sp.edit().putLong("download_id", id).apply()
        watchDownload()
    }

    fun cancelDownload() {
        dropPendingDownload()
        refresh()
    }

    /** Cancela la descarga en curso (si hay) y borra lo que llevaba. No toca los mapas completos. */
    private fun dropPendingDownload() {
        val id = sp.getLong("download_id", -1)
        if (id >= 0) runCatching { (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).remove(id) }
        sp.edit().remove("download_id").apply()
        pollJob?.cancel()
        File(dir, PARTIAL).delete()
    }

    private fun watchDownload() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            while (isActive) {
                val id = sp.getLong("download_id", -1)
                if (id < 0) break
                // Llegó un mapa completo por otro lado mientras se descargaba: gana ese.
                val ready = readyFile()
                if (ready != null) {
                    sp.edit().remove("download_id").apply()
                    runCatching { dm.remove(id) }
                    File(dir, PARTIAL).delete()
                    _status.value = MapStatus.Ready(ready)
                    break
                }
                val cursor = runCatching { dm.query(DownloadManager.Query().setFilterById(id)) }.getOrNull()
                var done = false
                cursor?.use { c ->
                    if (!c.moveToFirst()) {
                        // El usuario la canceló desde la notificación.
                        sp.edit().remove("download_id").apply()
                        _status.value = MapStatus.Missing
                        done = true
                        return@use
                    }
                    val st = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val got = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    when (st) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            sp.edit().remove("download_id").apply()
                            val part = File(dir, PARTIAL)
                            val final = File(dir, "mexico.map")
                            if (part.exists() && part.renameTo(final)) _status.value = MapStatus.Ready(final)
                            else _status.value = MapStatus.Error("La descarga terminó pero no se encontró el archivo")
                            done = true
                        }
                        DownloadManager.STATUS_FAILED -> {
                            sp.edit().remove("download_id").apply()
                            dm.remove(id)
                            _status.value = MapStatus.Error("La descarga falló. Revisa el WiFi e intenta otra vez.")
                            done = true
                        }
                        // Mientras se importa de USB, la barra de la importación manda.
                        else -> if (_status.value !is MapStatus.Importing) _status.value = MapStatus.Downloading(if (total > 0) got.toFloat() / total else null, got / 1_048_576)
                    }
                }
                if (done) break
                delay(1_000)
            }
        }
    }

    // ------------------------------------------------------------------ Importar desde USB

    fun import(uri: Uri, onDone: (Boolean) -> Unit) {
        scope.launch {
            _status.value = MapStatus.Importing(0f)
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val total = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }?.takeIf { it > 0 } ?: -1L
                    val part = File(dir, "importado.map.part")
                    context.contentResolver.openInputStream(uri)!!.use { input ->
                        part.outputStream().use { out ->
                            val buf = ByteArray(256 * 1024)
                            var copied = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                copied += n
                                if (total > 0) _status.value = MapStatus.Importing(copied.toFloat() / total)
                            }
                        }
                    }
                    // Comprobar que de verdad es un mapa de Mapsforge antes de aceptarlo.
                    MapFile(part).close()
                    val final = File(dir, "importado.map")
                    part.renameTo(final)
                }.getOrElse { File(dir, "importado.map.part").delete(); false }
            }
            refresh()
            if (!ok) _status.value = MapStatus.Error("Ese archivo no es un mapa válido (.map de Mapsforge)")
            onDone(ok)
        }
    }

    fun delete() {
        // Si había una descarga en curso, también se cancela.
        val id = sp.getLong("download_id", -1)
        if (id >= 0) runCatching { (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).remove(id) }
        sp.edit().remove("download_id").apply()
        pollJob?.cancel()
        dir.listFiles()?.forEach { it.delete() }
        closeStreetFile()
        refresh()
    }

    // ------------------------------------------------------------------ Última posición

    /** Para abrir el mapa en tu última ubicación mientras el GPS encuentra satélites. */
    fun lastPosition(): LatLong? {
        val lat = sp.getFloat("last_lat", Float.NaN)
        val lon = sp.getFloat("last_lon", Float.NaN)
        return if (lat.isNaN() || lon.isNaN()) null else LatLong(lat.toDouble(), lon.toDouble())
    }

    private var lastSaved = 0L

    fun rememberPosition(gps: GpsSpeed) {
        val lat = gps.lat ?: return
        val lon = gps.lon ?: return
        val now = System.currentTimeMillis()
        if (now - lastSaved < 30_000) return
        lastSaved = now
        sp.edit().putFloat("last_lat", lat.toFloat()).putFloat("last_lon", lon.toFloat()).apply()
    }

    // ------------------------------------------------------------------ Calle actual

    private val streetLock = Mutex()
    private var streetFile: MapFile? = null
    private var streetFilePath: String? = null

    private fun closeStreetFile() {
        runCatching { streetFile?.close() }
        streetFile = null
        streetFilePath = null
    }

    /**
     * Nombre de la calle más cercana (a menos de 35 m), leyendo el propio mapa. Sin internet.
     */
    suspend fun streetAt(lat: Double, lon: Double): String? = streetLock.withLock {
        val file = (status.value as? MapStatus.Ready)?.file ?: return@withLock null
        withContext(Dispatchers.IO) {
            runCatching {
                if (streetFilePath != file.path) {
                    closeStreetFile()
                    streetFile = MapFile(file, "es")
                    streetFilePath = file.path
                }
                val mf = streetFile!!
                val zoom: Byte = 16
                val tile = Tile(MercatorProjection.longitudeToTileX(lon, zoom), MercatorProjection.latitudeToTileY(lat, zoom), zoom, 256)
                val result = mf.readMapData(tile) ?: return@runCatching null
                var best: String? = null
                var bestDist = 35.0
                for (way in result.ways) {
                    val highway = way.tags.firstOrNull { it.key == "highway" }?.value ?: continue
                    if (highway !in DRIVABLE) continue
                    val name = way.tags.firstOrNull { it.key == "name" }?.value ?: continue
                    for (line in way.latLongs) {
                        for (i in 0 until line.size - 1) {
                            val d = distanceToSegmentM(lat, lon, line[i], line[i + 1])
                            if (d < bestDist) {
                                bestDist = d
                                best = name
                            }
                        }
                    }
                }
                best
            }.getOrNull()
        }
    }

    private fun distanceToSegmentM(lat: Double, lon: Double, a: LatLong, b: LatLong): Double {
        // Proyección plana local: suficiente para distancias de decenas de metros.
        val kx = 111_320.0 * cos(Math.toRadians(lat))
        val ky = 110_540.0
        val ax = (a.longitude - lon) * kx
        val ay = (a.latitude - lat) * ky
        val bx = (b.longitude - lon) * kx
        val by = (b.latitude - lat) * ky
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
        val px = ax + t * dx
        val py = ay + t * dy
        return sqrt(px * px + py * py)
    }

    companion object {
        const val MEXICO_URL = "https://download.mapsforge.org/maps/v5/north-america/mexico.map"
        const val MEXICO_SIZE_MB = 686
        private const val PARTIAL = "mexico.map.download"
        private val DRIVABLE = setOf(
            "motorway", "trunk", "primary", "secondary", "tertiary", "unclassified", "residential", "living_street",
            "motorway_link", "trunk_link", "primary_link", "secondary_link", "tertiary_link", "road",
        )
    }
}
