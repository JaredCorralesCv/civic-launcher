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
}

/**
 * Mapa de Mapsforge dentro de Compose.
 *
 * - [follow]: el mapa se mueve con el carro. Si arrastras el mapa, llama a [onUserMoved].
 * - [interactive] false: no responde a toques (en la tarjeta de Inicio el toque abre el mapa completo).
 * - [night]: usa la paleta de noche del estilo propio (MapTheme); cambiarla crea el mapa de nuevo.
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
) {
    // Si cambia el archivo (borraste y descargaste otro), se crea un mapa nuevo en lugar de reusar el viejo.
    // También se recrea al cambiar de día a noche: cada paleta tiene su propia caché de mosaicos.
    val themedCache = "$cacheName-${if (night) "n" else "d"}-v${MapTheme.VERSION}"
    androidx.compose.runtime.key(file.path, themedCache) {
        MapViewHost(file, themedCache, center, bearing, hasFix, follow, night, initialZoom, modifier, interactive, handle, onUserMoved)
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
) {
    val position = remember { PositionLayer() }
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
                val cache = AndroidUtil.createTileCache(ctx, cacheName, model.displayModel.tileSize, 1f, model.frameBufferModel.overdrawFactor)
                val store = MapFile(file, "es")
                val renderer = AndroidUtil.createTileRendererLayer(cache, model.mapViewPosition, store, MapTheme.get(ctx, night))
                layerManager.layers.add(renderer)
                layerManager.layers.add(position)
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
            position.set(center, bearing, hasFix)
            if (follow) {
                val c = mv.model.mapViewPosition.center
                // Solo mover si cambió de verdad (≈1 m): cada movimiento redibuja el mapa.
                if (c == null || kotlin.math.abs(c.latitude - center.latitude) > 0.00001 || kotlin.math.abs(c.longitude - center.longitude) > 0.00001) {
                    mv.setCenter(center)
                }
            }
        },
        onRelease = { mv ->
            handle?.view = null
            mv.destroyAll()
            (mv.tag as? MapFile)?.close()
            AndroidGraphicFactory.clearResourceMemoryCache()
        },
    )
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
