package com.civiclauncher.app.launcher.map

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.mapsforge.core.graphics.Canvas
import org.mapsforge.core.graphics.Style
import org.mapsforge.core.model.BoundingBox
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.Point
import org.mapsforge.core.model.Rotation
import org.mapsforge.core.model.Tile
import org.mapsforge.core.util.MercatorProjection
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.util.AndroidUtil
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.layer.Layer
import org.mapsforge.map.reader.MapFile
import org.mapsforge.map.rendertheme.internal.MapsforgeThemes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

/** Para controlar el mapa desde botones (acercar, alejar, centrar). */
class MapHandle {
    internal var view: MapView? = null
    fun zoomIn() = view?.model?.mapViewPosition?.zoomIn()
    fun zoomOut() = view?.model?.mapViewPosition?.zoomOut()
    fun zoomLevel(): Int = view?.model?.mapViewPosition?.zoomLevel?.toInt() ?: 0
    /** El punto que está justo en medio del mapa (donde va el pin al elegir en el mapa). */
    fun center(): LatLong? = view?.model?.mapViewPosition?.center
    fun moveTo(p: LatLong) { view?.model?.mapViewPosition?.animateTo(p) }
}

/**
 * Mapa de Mapsforge dentro de Compose.
 *
 * Inicio y la pantalla del mapa comparten UNA sola vista (ver [SharedMap]): se muda a la pantalla
 * que está a la vista. Ahorra ~20 MB, un archivo abierto y un juego de hilos.
 *
 * - [follow]: el mapa se mueve con el carro. Si arrastras el mapa, llama a [onUserMoved].
 * - [interactive] false: no responde a toques (en la tarjeta de Inicio el toque abre el mapa completo).
 * - [night]: usa la paleta de noche del estilo propio (MapTheme); cambiarla crea el mapa de nuevo.
 * - [headingUp]: el mapa gira para que lo que tienes enfrente quede arriba (como un navegador) y el
 *   carro se dibuja más abajo, para ver más camino por delante. Falso = norte arriba.
 */
@Composable
fun CivicMap(
    file: File,
    center: LatLong,
    bearing: Float?,
    hasFix: Boolean,
    follow: Boolean,
    night: Boolean,
    initialZoom: Int,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
    handle: MapHandle? = null,
    onUserMoved: () -> Unit = {},
    headingUp: Boolean = false,
    route: com.civiclauncher.app.launcher.nav.Route? = null,
    routeFrom: Int = 0,
    destination: LatLong? = null,
    onLongPress: ((LatLong) -> Unit)? = null,
    fast: Boolean = false,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // Si la pantalla que lo contiene no se ve (Inicio mientras estás en otra), el mapa se queda
    // abierto pero escondido y sin seguir al GPS: no dibuja ni sube mosaicos a la GPU.
    val active = com.civiclauncher.app.launcher.ui.LocalScreenActive.current
    val latestMoved = androidx.compose.runtime.rememberUpdatedState(onUserMoved)
    val latestLongPress = androidx.compose.runtime.rememberUpdatedState(onLongPress)
    // El archivo (cientos de MB) se abre fuera del hilo de la pantalla; mientras, el color de la tierra.
    val shared by androidx.compose.runtime.produceState<SharedMapView?>(null, file.path, night) {
        value = null
        val map = SharedMap.obtain(context, file, night) ?: return@produceState
        value = map
        try {
            kotlinx.coroutines.awaitCancellation()
        } finally {
            SharedMap.release(map)
        }
    }
    val map = shared
    if (map == null) {
        androidx.compose.foundation.layout.Box(modifier.background(androidx.compose.ui.graphics.Color(MapTheme.landColor(night))))
        return
    }
    AndroidView(
        modifier = modifier,
        factory = { android.widget.FrameLayout(it) },
        update = { frame ->
            map.show(
                frame,
                active,
                MapRequest(
                    center, bearing, hasFix, follow, initialZoom, interactive, handle, headingUp, route, routeFrom, destination, fast,
                    onUserMoved = { latestMoved.value() },
                    onLongPress = { p -> latestLongPress.value?.let { cb -> cb(p); true } ?: false },
                ),
            )
        },
        onRelease = { frame -> map.leave(frame) },
    )
}

/** Lo que pide la pantalla que tiene el mapa en este momento. */
internal class MapRequest(
    val center: LatLong,
    val bearing: Float?,
    val hasFix: Boolean,
    val follow: Boolean,
    val initialZoom: Int,
    val interactive: Boolean,
    val handle: MapHandle?,
    val headingUp: Boolean,
    val route: com.civiclauncher.app.launcher.nav.Route?,
    val routeFrom: Int,
    val destination: LatLong?,
    val fast: Boolean,
    val onUserMoved: () -> Unit,
    val onLongPress: (LatLong) -> Boolean,
)

/**
 * La única vista del mapa. Cada pantalla que muestra un mapa ([CivicMap]) trae su contenedor y la
 * vista se pasa al contenedor de la pantalla activa. Dura mientras alguna pantalla la tenga armada.
 */
internal object SharedMap {
    private var current: SharedMapView? = null
    private val opening = kotlinx.coroutines.sync.Mutex()
    private var firstOpen = true
    private val prefetchThread = Dispatchers.IO.limitedParallelism(1)

    /** En el hilo de la pantalla. Devuelve null si el archivo no se pudo abrir. */
    suspend fun obtain(context: Context, file: File, night: Boolean): SharedMapView? = opening.withLock {
        val key = "${file.path}|$night"
        current?.takeIf { it.key == key }?.let {
            it.refs++
            return@withLock it
        }
        // Arranque escalonado: el mapa, lo más pesado de Inicio, entra hasta que ya se vio la pantalla.
        if (firstOpen) {
            firstOpen = false
            kotlinx.coroutines.delay(FIRST_OPEN_DELAY_MS)
        }
        var store: MapFile? = null
        try {
            withContext(Dispatchers.IO) { store = runCatching { MapFile(file, "es") }.getOrNull() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            runCatching { store?.close() }
            throw e
        }
        val opened = store ?: return@withLock null
        SharedMapView(context, key, file, opened, night).also {
            it.refs = 1
            current = it
        }
    }

    fun release(map: SharedMapView) {
        if (--map.refs > 0) return
        if (current === map) current = null
        map.destroy()
    }

    /** Dibuja a disco, con calma, los mosaicos del recorrido: al manejar el mapa ya está hecho. */
    suspend fun prefetch(points: List<LatLong>) {
        val map = current ?: return
        withContext(prefetchThread) {
            val thread = android.os.Process.myTid()
            val before = android.os.Process.getThreadPriority(thread)
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            try {
                map.prefetch(points)
            } finally {
                android.os.Process.setThreadPriority(before)
            }
        }
    }

    private const val FIRST_OPEN_DELAY_MS = 500L
}

/** Mosaicos a [margin] alrededor del camino, en orden de recorrido, sin repetir y hasta [max]. */
internal fun tilesAlong(points: List<LatLong>, zoom: Byte, margin: Int, max: Int): List<Pair<Int, Int>> {
    val seen = LinkedHashSet<Pair<Int, Int>>()
    fun add(x: Int, y: Int): Boolean {
        for (dx in -margin..margin) for (dy in -margin..margin) {
            seen += (x + dx) to (y + dy)
            if (seen.size >= max) return false
        }
        return true
    }
    var lastX = Int.MIN_VALUE
    var lastY = 0
    for (p in points) {
        val x = MercatorProjection.longitudeToTileX(p.longitude, zoom)
        val y = MercatorProjection.latitudeToTileY(p.latitude, zoom)
        if (lastX != Int.MIN_VALUE) {
            // Tramos largos y rectos: no saltarse los mosaicos de en medio.
            val steps = maxOf(kotlin.math.abs(x - lastX), kotlin.math.abs(y - lastY))
            for (i in 1 until steps) if (!add(lastX + (x - lastX) * i / steps, lastY + (y - lastY) * i / steps)) return seen.toList()
        }
        if (!add(x, y)) return seen.toList()
        lastX = x
        lastY = y
    }
    return seen.toList()
}

@SuppressLint("ClickableViewAccessibility")
internal class SharedMapView(
    private val context: Context,
    val key: String,
    private val file: File,
    private val store: MapFile,
    private val night: Boolean,
) {
    var refs = 0
    private val position = PositionLayer()
    private val overlays = RouteOverlays()
    private var last: MapRequest? = null
    private var lastHandle: MapHandle? = null
    /** ¿El mapa ya se alejó por ir rápido? Se regresa lo mismo: si acercaste a mano, se respeta. */
    private var speedZoomed = false
    private var rotatedW = 0
    private var rotatedH = 0
    @Volatile private var destroyed = false

    private val cache: org.mapsforge.map.layer.cache.TileCache

    private val view: MapView = MapView(context).apply {
        setBuiltInZoomControls(false)
        mapScaleBar.isVisible = false
        // Mientras se dibujan los mosaicos se ve el color de la tierra, no un destello blanco.
        setBackgroundColor(MapTheme.landColor(night).toInt())
        model.displayModel.setBackgroundColor(MapTheme.landColor(night).toInt())
        setZoomLevelMin(6)
        setZoomLevelMax(19)
        // Al girar con el rumbo, las esquinas de la vista salen del área dibujada: un búfer cuadrado
        // y 1.5 veces más grande cubre la vista girada a cualquier ángulo.
        model.frameBufferModel.overdrawFactor = 1.5
        // "-sl": mosaicos sin nombres (los nombres van en su capa). Se conservan entre arranques.
        cache = AndroidUtil.createTileCache(context, "map-shared-${if (night) "n" else "d"}${MapTheme.CACHE_SUFFIX}", model.displayModel.tileSize, 1f, CACHE_OVERDRAW, true)
        MapMemory.register(cache)
        // Nombres de calles en su propia capa (no dentro de los mosaicos): así, al girar el mapa
        // con el rumbo, el texto se acomoda para leerse derecho en vez de quedar de cabeza.
        val renderer = AndroidUtil.createTileRendererLayer(cache, model.mapViewPosition, store, MapTheme.get(context, night), false, false, true)
        layerManager.layers.add(renderer)
        // La ruta va debajo de los nombres de las calles (que se sigan leyendo) y del carro.
        overlays.install(this)
        layerManager.layers.add(org.mapsforge.map.layer.labels.LabelLayer(AndroidGraphicFactory.INSTANCE, renderer.labelStore))
        layerManager.layers.add(overlays.destination)
        layerManager.layers.add(position)
        // Mantener el dedo sobre el mapa = "¿ir aquí?".
        layerManager.layers.add(object : Layer() {
            override fun draw(boundingBox: BoundingBox, zoomLevel: Byte, canvas: Canvas, topLeftPoint: Point, rotation: Rotation) = Unit
            // Mapsforge pide la posición de cada capa antes de pasarle el toque largo; sin una,
            // truena. Esta capa no se dibuja, así que cualquier punto sirve.
            override fun getPosition(): LatLong = LatLong(0.0, 0.0)
            override fun onLongPress(tapLatLong: LatLong, layerXY: Point, tapXY: Point): Boolean {
                val request = last ?: return false
                post { request.onLongPress(tapLatLong) }
                return request.interactive
            }
        })
        // Al mudarse cambia de tamaño: el giro y el carro se recalculan con las medidas nuevas.
        addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or_, ob ->
            if (r - l != or_ - ol || b - t != ob - ot) last?.let { post { apply(it) } }
        }
    }

    private val touch = android.view.View.OnTouchListener { _, e ->
        if (e.actionMasked == MotionEvent.ACTION_MOVE) last?.onUserMoved?.invoke()
        false
    }

    fun show(frame: android.view.ViewGroup, active: Boolean, request: MapRequest) {
        if (!active) {
            if (view.parent === frame && view.visibility != View.INVISIBLE) view.visibility = View.INVISIBLE
            return
        }
        last = request
        if (view.parent !== frame) moveTo(frame, request)
        if (view.visibility != View.VISIBLE) view.visibility = View.VISIBLE
        apply(request)
    }

    fun leave(frame: android.view.ViewGroup) {
        if (view.parent !== frame) return
        frame.removeView(view)
        lastHandle?.view = null
        lastHandle = null
    }

    private fun moveTo(frame: android.view.ViewGroup, request: MapRequest) {
        (view.parent as? android.view.ViewGroup)?.removeView(view)
        frame.removeAllViews()
        frame.addView(view, android.widget.FrameLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT))
        lastHandle?.view = null
        request.handle?.view = view
        lastHandle = request.handle
        view.isClickable = request.interactive
        view.setOnTouchListener(if (request.interactive) touch else null)
        // Cada pantalla arranca como siempre: centrada en el carro y con su zoom.
        view.setCenter(request.center)
        view.setZoomLevel(request.initialZoom.toByte())
        speedZoomed = false
        view.rotate(Rotation.NULL_ROTATION)
        rotatedW = 0
    }

    private fun apply(r: MapRequest) {
        if (destroyed) return
        // La flecha gira de 5 en 5 grados: con el carro parado el rumbo del GPS "tiembla" y cada
        // temblor redibujaba el mapa entero.
        position.set(r.center, r.bearing?.let { Math.round(it / 5f) * 5f }, r.hasFix)
        overlays.show(r.route, r.routeFrom, r.destination)
        // Rumbo arriba: girar el mapa al revés del rumbo para que "enfrente" quede arriba. Solo con
        // un rumbo válido (el GPS lo da al moverse); detenido, se queda como estaba.
        val wantRotation = when {
            !r.headingUp || !r.follow -> 0f
            r.bearing != null -> -(Math.round(r.bearing / 5f) * 5f)
            else -> null
        }
        if (wantRotation != null && view.width > 0 && (view.mapRotation.degrees != wantRotation || view.width != rotatedW || view.height != rotatedH)) {
            // Siempre alrededor del centro de la vista: Mapsforge calcula qué mosaicos pintar con ese
            // centro, y girar alrededor de otro punto deja esquinas sin dibujar.
            view.rotate(Rotation(wantRotation, view.width * 0.5f, view.height * 0.5f))
            rotatedW = view.width
            rotatedH = view.height
        }
        if (!r.follow) return
        // A velocidad, un nivel más lejos: se ve más camino y hay 4 veces menos mosaicos por kilómetro.
        if (r.fast && !speedZoomed && view.model.mapViewPosition.zoomLevel >= MIN_ZOOM_FOR_SPEED) {
            view.model.mapViewPosition.zoomOut()
            speedZoomed = true
        } else if (!r.fast && speedZoomed) {
            view.model.mapViewPosition.zoomIn()
            speedZoomed = false
        }
        // Con rumbo arriba, el centro del mapa va un poco "adelante" del carro, en la dirección
        // en que avanza: así el carro queda abajo (a 2/3 de la altura) y se ve más camino.
        val target = if (r.headingUp && view.height > 0) {
            aheadOf(r.center, -view.mapRotation.degrees, (CAR_Y_HEADING_UP - 0.5f) * view.height, view.model.mapViewPosition.zoomLevel, view.model.displayModel.tileSize)
        } else {
            r.center
        }
        val c = view.model.mapViewPosition.center
        // Solo mover si cambió de verdad (≈5 m): estacionado, el GPS brinca unos metros cada
        // segundo y cada movimiento redibuja y sube el mapa completo a la GPU.
        if (c == null || kotlin.math.abs(c.latitude - target.latitude) > MIN_MOVE_DEG || kotlin.math.abs(c.longitude - target.longitude) > MIN_MOVE_DEG) {
            view.setCenter(target)
        }
    }

    /**
     * Va directo al disco: meterlos a la caché en memoria sacaría los que se están viendo. Con su
     * propio lector del archivo, para no competir con el dibujo normal.
     */
    fun prefetch(points: List<LatLong>) {
        val disk = (cache as? org.mapsforge.map.layer.cache.TwoLevelTileCache)?.secondLevelTileCache ?: return
        val displayModel = view.model.displayModel
        val reader = MapFile(file, "es")
        val graphics = AndroidGraphicFactory.INSTANCE
        val theme = org.mapsforge.map.rendertheme.rule.RenderThemeFuture(graphics, MapTheme.get(context, night), displayModel)
        Thread(theme, "civic-tema").start()
        try {
            val renderer = org.mapsforge.map.layer.renderer.DatabaseRenderer(reader, graphics, disk, null, false, false, null)
            var drawn = 0
            for ((zoom, margin) in PREFETCH_LEVELS) {
                for ((x, y) in tilesAlong(points, zoom, margin, PREFETCH_MAX_TILES)) {
                    if (destroyed) return
                    if (Thread.currentThread().isInterrupted) return
                    val job = org.mapsforge.map.layer.renderer.RendererJob(Tile(x, y, zoom, displayModel.tileSize), reader, theme, displayModel, 1f, false, false)
                    if (disk.containsKey(job)) continue
                    val bitmap = runCatching { renderer.executeJob(job) }.getOrNull() ?: continue
                    disk.put(job, bitmap)
                    bitmap.decrementRefCount()
                    drawn++
                }
            }
            android.util.Log.i("CivicMap", "precarga de la ruta: $drawn mosaicos dibujados")
        } finally {
            theme.decrementRefCount()
            runCatching { reader.close() }
        }
    }

    fun destroy() {
        destroyed = true
        lastHandle?.view = null
        (view.parent as? android.view.ViewGroup)?.removeView(view)
        MapMemory.unregister(cache)
        view.destroyAll()
        store.close()
        AndroidGraphicFactory.clearResourceMemoryCache()
    }

    private companion object {
        /** Primero el zoom de manejo con un mosaico de margen, luego el de carretera solo sobre el camino. */
        val PREFETCH_LEVELS = listOf(16.toByte() to 1, 15.toByte() to 0)
        const val PREFETCH_MAX_TILES = 300
    }
}

/**
 * La ruta (una línea azul con borde oscuro, como en los navegadores) y el destino. Solo se dibuja lo
 * que falta por recorrer, y solo se actualiza cuando avanzas de tramo (no en cada lectura del GPS).
 */
private class RouteOverlays {
    private val gf = AndroidGraphicFactory.INSTANCE
    private fun stroke(color: Int, width: Float) = gf.createPaint().apply {
        setColor(color)
        setStyle(Style.STROKE)
        setStrokeWidth(width)
        setStrokeCap(org.mapsforge.core.graphics.Cap.ROUND)
        setStrokeJoin(org.mapsforge.core.graphics.Join.ROUND)
    }
    private val casing = org.mapsforge.map.layer.overlay.Polyline(stroke(0xFF0B3A8C.toInt(), 15f), gf)
    private val line = org.mapsforge.map.layer.overlay.Polyline(stroke(0xFF3B8BFF.toInt(), 9f), gf)
    val destination = DestinationLayer()
    private var shownRoute: com.civiclauncher.app.launcher.nav.Route? = null
    private var shownFrom = -1

    fun install(mv: MapView) {
        mv.layerManager.layers.add(casing)
        mv.layerManager.layers.add(line)
    }

    fun show(route: com.civiclauncher.app.launcher.nav.Route?, from: Int, dest: LatLong?) {
        destination.set(dest)
        if (route === shownRoute && from == shownFrom) return
        shownRoute = route
        shownFrom = from
        val pts = route?.points?.let { it.subList(from.coerceIn(0, it.lastIndex), it.size) }.orEmpty()
        casing.setPoints(pts)
        line.setPoints(pts)
        line.requestRedraw()
    }
}

/** Banderita del destino: un círculo rojo con borde blanco. */
private class DestinationLayer : Layer() {
    @Volatile private var pos: LatLong? = null
    private val gf = AndroidGraphicFactory.INSTANCE
    private val ring = gf.createPaint().apply { setColor(0xFFFFFFFF.toInt()); setStyle(Style.FILL) }
    private val fill = gf.createPaint().apply { setColor(0xFFFF453A.toInt()); setStyle(Style.FILL) }

    fun set(p: LatLong?) {
        if (p == pos) return
        pos = p
        requestRedraw()
    }

    override fun draw(boundingBox: BoundingBox, zoomLevel: Byte, canvas: Canvas, topLeftPoint: Point, rotation: Rotation) {
        val p = pos ?: return
        val mapSize = MercatorProjection.getMapSize(zoomLevel, displayModel?.tileSize ?: 256)
        val x = (MercatorProjection.longitudeToPixelX(p.longitude, mapSize) - topLeftPoint.x).toInt()
        val y = (MercatorProjection.latitudeToPixelY(p.latitude, mapSize) - topLeftPoint.y).toInt()
        canvas.drawCircle(x, y, 13, ring)
        canvas.drawCircle(x, y, 9, fill)
    }
}

/** Flecha azul de "estás aquí", que gira hacia donde avanza el carro. */
private class PositionLayer : Layer() {
    @Volatile private var pos: LatLong? = null
    @Volatile private var bearing: Float? = null
    @Volatile private var fix = false
    private val gf = AndroidGraphicFactory.INSTANCE
    private val ring = gf.createPaint().apply { setColor(0xFFFFFFFF.toInt()); setStyle(Style.FILL) }
    private val fill = gf.createPaint().apply { setColor(0xFF3B8BFF.toInt()); setStyle(Style.FILL) }
    private val stale = gf.createPaint().apply { setColor(0xFF8A939F.toInt()); setStyle(Style.FILL) }
    // Halo azul alrededor del carro (tres círculos translúcidos: un degradado sin costo extra).
    private val glow = listOf(42 to 0x1A3B8BFF, 34 to 0x263B8BFF, 27 to 0x403B8BFF).map { (r, color) ->
        r to gf.createPaint().apply { setColor(color); setStyle(Style.FILL) }
    }
    private val arrow = gf.createPath()

    fun set(p: LatLong, b: Float?, hasFix: Boolean) {
        if (p == pos && b == bearing && hasFix == fix) return
        pos = p
        bearing = b
        fix = hasFix
        requestRedraw()
    }

    override fun draw(boundingBox: BoundingBox, zoomLevel: Byte, canvas: Canvas, topLeftPoint: Point, rotation: Rotation) {
        val p = pos ?: return
        val tileSize = displayModel?.tileSize ?: 256
        val mapSize = MercatorProjection.getMapSize(zoomLevel, tileSize)
        val x = (MercatorProjection.longitudeToPixelX(p.longitude, mapSize) - topLeftPoint.x).toFloat()
        val y = (MercatorProjection.latitudeToPixelY(p.latitude, mapSize) - topLeftPoint.y).toFloat()
        if (fix) glow.forEach { (r, paint) -> canvas.drawCircle(x.toInt(), y.toInt(), r, paint) }
        canvas.drawCircle(x.toInt(), y.toInt(), 20, ring)
        canvas.drawCircle(x.toInt(), y.toInt(), 16, if (fix) fill else stale)
        // Flecha blanca: punta arriba, girada según el rumbo del GPS.
        val a = Math.toRadians((bearing ?: 0f).toDouble())
        fun pt(dx: Float, dy: Float): Pair<Float, Float> {
            val rx = dx * cos(a) - dy * sin(a)
            val ry = dx * sin(a) + dy * cos(a)
            return (x + rx.toFloat()) to (y + ry.toFloat())
        }
        arrow.clear()
        val tip = pt(0f, -10f)
        val r = pt(7f, 8f)
        val m = pt(0f, 4f)
        val l = pt(-7f, 8f)
        arrow.moveTo(tip.first, tip.second)
        arrow.lineTo(r.first, r.second)
        arrow.lineTo(m.first, m.second)
        arrow.lineTo(l.first, l.second)
        arrow.close()
        canvas.drawPath(arrow, ring)
    }
}

/** El punto a [px] pixeles de [p] hacia [headingDeg] (0 = norte), al zoom actual. */
private fun aheadOf(p: LatLong, headingDeg: Float, px: Float, zoom: Byte, tileSize: Int): LatLong {
    val mapSize = MercatorProjection.getMapSize(zoom, tileSize)
    val size = mapSize.toDouble()
    val a = Math.toRadians(headingDeg.toDouble())
    val x = (MercatorProjection.longitudeToPixelX(p.longitude, mapSize) + sin(a) * px).coerceIn(0.0, size)
    val y = (MercatorProjection.latitudeToPixelY(p.latitude, mapSize) - cos(a) * px).coerceIn(0.0, size)
    return LatLong(MercatorProjection.pixelYToLatitude(y, mapSize), MercatorProjection.pixelXToLongitude(x, mapSize))
}

/**
 * Tamaño de la caché de mosaicos en memoria, en pantallas. Girada, la vista pide los mosaicos de un
 * cuadro más grande (el búfer de 1.5 girado 45° ≈ 2.1); con menos, unos se sacan para meter otros y
 * quedan huecos oscuros que nunca se llenan.
 */
private const val CACHE_OVERDRAW = 2.5

/**
 * Los mosaicos del mapa guardados en la memoria (los del disco se quedan). Cuando Civic queda detrás
 * de otra app (AutoKit, Maps), Android pide memoria: se sueltan y al volver se leen del disco.
 */
object MapMemory {
    private val caches = java.util.Collections.newSetFromMap(java.util.WeakHashMap<org.mapsforge.map.layer.cache.TileCache, Boolean>())

    @Synchronized fun register(c: org.mapsforge.map.layer.cache.TileCache) { caches += c }
    @Synchronized fun unregister(c: org.mapsforge.map.layer.cache.TileCache) { caches -= c }

    @Synchronized fun trim() {
        for (c in caches) runCatching { ((c as? org.mapsforge.map.layer.cache.TwoLevelTileCache)?.firstLevelTileCache ?: return@runCatching).purge() }
    }
}

/** Con el mapa ya lejos no se aleja más. */
private const val MIN_ZOOM_FOR_SPEED = 15

/** Se aleja desde 60 km/h y se regresa hasta bajar de 45: en el umbral no estaría brincando. */
internal fun isFastForMap(wasFast: Boolean, kmh: Float?): Boolean = when {
    kmh == null -> wasFast
    kmh >= 60f -> true
    kmh <= 45f -> false
    else -> wasFast
}

/** ~5 m: menos que lo que avanza el carro en un segundo a 20 km/h, más que el temblor del GPS. */
private const val MIN_MOVE_DEG = 0.00005

/** Con rumbo arriba, el carro va a 2/3 de la altura: se ve el doble de camino por delante que por detrás. */
private const val CAR_Y_HEADING_UP = 0.66f
