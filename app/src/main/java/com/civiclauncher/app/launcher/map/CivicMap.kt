package com.civiclauncher.app.launcher.map

import android.annotation.SuppressLint
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.view.MotionEvent
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.mapsforge.core.graphics.Canvas
import org.mapsforge.core.graphics.Style
import org.mapsforge.core.model.BoundingBox
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.Point
import org.mapsforge.core.model.Rotation
import org.mapsforge.core.util.MercatorProjection
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.util.AndroidUtil
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.layer.Layer
import org.mapsforge.map.reader.MapFile
import org.mapsforge.map.rendertheme.internal.MapsforgeThemes
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
 * - [follow]: el mapa se mueve con el carro. Si arrastras el mapa, llama a [onUserMoved].
 * - [interactive] false: no responde a toques (en la tarjeta de Inicio el toque abre el mapa completo).
 * - [night]: usa la paleta de noche del estilo propio (MapTheme); cambiarla crea el mapa de nuevo.
 * - [headingUp]: el mapa gira para que lo que tienes enfrente quede arriba (como un navegador) y el
 *   carro se dibuja más abajo, para ver más camino por delante. Falso = norte arriba.
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun CivicMap(
    file: File,
    cacheName: String,
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
) {
    // Si cambia el archivo (borraste y descargaste otro), se crea un mapa nuevo en lugar de reusar el viejo.
    // También se recrea al cambiar de día a noche: cada paleta tiene su propia caché de mosaicos.
    // "-sl": mosaicos sin nombres (los nombres van en su capa); no se mezclan con los viejos de la caché.
    val themedCache = "$cacheName-${if (night) "n" else "d"}-v${MapTheme.VERSION}-sl"
    androidx.compose.runtime.key(file.path, themedCache) {
        MapViewHost(file, themedCache, center, bearing, hasFix, follow, night, initialZoom, modifier, interactive, handle, onUserMoved, headingUp, route, routeFrom, destination, onLongPress)
    }
}

@SuppressLint("ClickableViewAccessibility")
@Composable
private fun MapViewHost(
    file: File,
    cacheName: String,
    center: LatLong,
    bearing: Float?,
    hasFix: Boolean,
    follow: Boolean,
    night: Boolean,
    initialZoom: Int,
    modifier: Modifier,
    interactive: Boolean,
    handle: MapHandle?,
    onUserMoved: () -> Unit,
    headingUp: Boolean,
    route: com.civiclauncher.app.launcher.nav.Route?,
    routeFrom: Int,
    destination: LatLong?,
    onLongPress: ((LatLong) -> Unit)?,
) {
    val position = remember { PositionLayer() }
    val overlays = remember { RouteOverlays() }
    val latestOnLongPress = androidx.compose.runtime.rememberUpdatedState(onLongPress)
    // Si la pantalla que lo contiene no se ve (Inicio mientras estás en otra), el mapa se queda
    // abierto pero escondido y sin seguir al GPS: no dibuja ni sube mosaicos a la GPU.
    val active = com.civiclauncher.app.launcher.ui.LocalScreenActive.current
    // El listener de toques se crea una vez; así siempre llama a la versión más reciente.
    val latestOnUserMoved = androidx.compose.runtime.rememberUpdatedState(onUserMoved)
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            MapView(ctx).apply {
                isClickable = interactive
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
                // La tarjeta de Inicio ocupa ~la mitad de la pantalla: su caché en memoria se calcula para
                // esa parte (antes guardaba mosaicos como si fuera el mapa completo).
                val cache = AndroidUtil.createTileCache(ctx, cacheName, model.displayModel.tileSize, if (interactive) 1f else CARD_SCREEN_RATIO, CACHE_OVERDRAW)
                MapMemory.register(cache)
                val store = MapFile(file, "es")
                // Nombres de calles en su propia capa (no dentro de los mosaicos): así, al girar el mapa
                // con el rumbo, el texto se acomoda para leerse derecho en vez de quedar de cabeza.
                val renderer = AndroidUtil.createTileRendererLayer(cache, model.mapViewPosition, store, MapTheme.get(ctx, night), false, false, true)
                layerManager.layers.add(renderer)
                // La ruta va debajo de los nombres de las calles (que se sigan leyendo) y del carro.
                overlays.install(this)
                layerManager.layers.add(org.mapsforge.map.layer.labels.LabelLayer(AndroidGraphicFactory.INSTANCE, renderer.labelStore))
                layerManager.layers.add(overlays.destination)
                layerManager.layers.add(position)
                if (interactive) {
                    // Mantener el dedo sobre el mapa = "¿ir aquí?".
                    layerManager.layers.add(object : Layer() {
                        override fun draw(boundingBox: BoundingBox, zoomLevel: Byte, canvas: Canvas, topLeftPoint: Point, rotation: Rotation) = Unit
                        // Mapsforge pide la posición de cada capa antes de pasarle el toque largo; sin una,
                        // truena. Esta capa no se dibuja, así que cualquier punto sirve.
                        override fun getPosition(): LatLong = LatLong(0.0, 0.0)
                        override fun onLongPress(tapLatLong: LatLong, layerXY: Point, tapXY: Point): Boolean {
                            val cb = latestOnLongPress.value ?: return false
                            post { cb(tapLatLong) }
                            return true
                        }
                    })
                }
                setCenter(center)
                setZoomLevel(initialZoom.toByte())
                tag = store
                if (interactive) {
                    setOnTouchListener { _, e ->
                        if (e.actionMasked == MotionEvent.ACTION_MOVE) latestOnUserMoved.value()
                        false
                    }
                }
                handle?.view = this
            }
        },
        update = { mv ->
            val visibility = if (active) android.view.View.VISIBLE else android.view.View.INVISIBLE
            if (mv.visibility != visibility) mv.visibility = visibility
            if (!active) return@AndroidView
            // La flecha gira de 5 en 5 grados: con el carro parado el rumbo del GPS "tiembla" y cada
            // temblor redibujaba el mapa entero.
            position.set(center, bearing?.let { Math.round(it / 5f) * 5f }, hasFix)
            overlays.show(route, routeFrom, destination)
            // Rumbo arriba: girar el mapa al revés del rumbo para que "enfrente" quede arriba. Solo con
            // un rumbo válido (el GPS lo da al moverse); detenido, se queda como estaba.
            val wantRotation = when {
                !headingUp || !follow -> 0f
                bearing != null -> -(Math.round(bearing / 5f) * 5f)
                else -> null
            }
            if (wantRotation != null && mv.width > 0 && mv.mapRotation.degrees != wantRotation) {
                // Siempre alrededor del centro de la vista: Mapsforge calcula qué mosaicos pintar con ese
                // centro, y girar alrededor de otro punto deja esquinas sin dibujar.
                mv.rotate(Rotation(wantRotation, mv.width * 0.5f, mv.height * 0.5f))
            }
            if (follow) {
                // Con rumbo arriba, el centro del mapa va un poco "adelante" del carro, en la dirección
                // en que avanza: así el carro queda abajo (a 2/3 de la altura) y se ve más camino.
                val target = if (headingUp && mv.height > 0) {
                    aheadOf(center, -mv.mapRotation.degrees, (CAR_Y_HEADING_UP - 0.5f) * mv.height, mv.model.mapViewPosition.zoomLevel, mv.model.displayModel.tileSize)
                } else {
                    center
                }
                val c = mv.model.mapViewPosition.center
                // Solo mover si cambió de verdad (≈5 m): estacionado, el GPS brinca unos metros cada
                // segundo y cada movimiento redibuja y sube el mapa completo a la GPU.
                if (c == null || kotlin.math.abs(c.latitude - target.latitude) > MIN_MOVE_DEG || kotlin.math.abs(c.longitude - target.longitude) > MIN_MOVE_DEG) {
                    mv.setCenter(target)
                }
            }
        },
        onRelease = { mv ->
            handle?.view = null
            mv.layerManager.layers.filterIsInstance<org.mapsforge.map.layer.renderer.TileRendererLayer>().forEach { MapMemory.unregister(it.tileCache) }
            mv.destroyAll()
            (mv.tag as? MapFile)?.close()
            AndroidGraphicFactory.clearResourceMemoryCache()
        },
    )
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

/** Parte de la pantalla que ocupa la tarjeta del mapa en Inicio. */
private const val CARD_SCREEN_RATIO = 0.5f

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

/** ~5 m: menos que lo que avanza el carro en un segundo a 20 km/h, más que el temblor del GPS. */
private const val MIN_MOVE_DEG = 0.00005

/** Con rumbo arriba, el carro va a 2/3 de la altura: se ve el doble de camino por delante que por detrás. */
private const val CAR_Y_HEADING_UP = 0.66f
