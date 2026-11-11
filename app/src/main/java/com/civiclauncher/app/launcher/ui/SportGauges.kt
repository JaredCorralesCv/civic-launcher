package com.civiclauncher.app.launcher.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.civiclauncher.app.container
import com.civiclauncher.app.ui.design.Civic
import com.civiclauncher.app.ui.design.PhosphorIcons as P
import com.civiclauncher.app.ui.design.bevelBorder
import com.civiclauncher.obd.EngineState
import com.civiclauncher.obd.Pids
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

// =================================================================== Datos del manejo

/** Lo que muestran los medidores: velocidad, RPM y marcha (null = sin dato). */
@Immutable
data class DriveValues(val speed: Float?, val rpm: Float?, val gear: Int?)

/** Valores fijos para las miniaturas del menú: no leen nada ni se animan. */
val SAMPLE_DRIVE = DriveValues(speed = 65f, rpm = 3500f, gear = 3)

private const val SPEED_GAUGE_MAX = 200f
private const val RPM_GAUGE_MAX = 8000f
/** Zona roja del motor del Civic. */
private const val RPM_GAUGE_RED = 6800f

/**
 * Velocidad (OBD o GPS), RPM y marcha para los medidores de Inicio Sport.
 *
 * Blindado: si Inicio no está en pantalla, los valores se CONGELAN (no se lee nada nuevo y nada se
 * vuelve a dibujar). Y como solo cambian cuando cambia lo que se ve (velocidad entera, RPM de 50 en
 * 50), los 30+ datos por segundo del OBD no redibujan los medidores.
 */
@Composable
fun rememberDriveValues(): androidx.compose.runtime.State<DriveValues> {
    val c = LocalContext.current.container
    val gps = c.gps.speed.collectWhenVisible()
    val obdState = c.obd.state.collectWhenVisible()
    val obdData = c.obd.data.collectWhenVisible()
    val live = c.obd.live.collectWhenVisible()
    // Se devuelve el State sin leerlo: cada widget lo lee donde lo usa, y un cambio de velocidad solo
    // vuelve a armar los widgets que la muestran (antes, todo Inicio).
    return remember {
        derivedStateOf {
            val connected = obdState.value is EngineState.Connected
            val obdSpeed = if (connected) obdData.value[Pids.SPEED].fresh(5_000)?.value?.let { Math.round(it).toFloat() } else null
            val speed = obdSpeed ?: gps.value.kmh?.let { Math.round(it).toFloat() }
            val rpm = if (connected) obdData.value[Pids.RPM].fresh()?.value?.let { Math.round(it / 50.0) * 50f } else null
            DriveValues(speed, rpm, if (connected) live.value.gear else null)
        }
    }
}

/**
 * Fracción animada del medidor, como estado: se lee con `by` y SOLO dentro del dibujo, así una
 * animación redibuja el medidor sin volver a componerlo (antes cada cuadro recomponía todo).
 * Los cambios chicos (el OBD manda decenas por segundo) se aplican de golpe, en un solo cuadro; solo
 * los saltos grandes se animan. En las miniaturas, sin animación.
 */
@Composable
internal fun gaugeFraction(value: Float?, max: Float, live: Boolean): androidx.compose.runtime.State<Float> {
    val target = ((value ?: 0f) / max).coerceIn(0f, 1f)
    val anim = remember { androidx.compose.animation.core.Animatable(target) }
    if (!live) return androidx.compose.runtime.rememberUpdatedState(target)
    androidx.compose.runtime.LaunchedEffect(target) {
        // En bajo consumo la aguja va directo: animarla es la mayor parte de los cuadros del medidor.
        if (com.civiclauncher.app.launcher.LowPower.reduced || kotlin.math.abs(target - anim.value) < 0.03f) anim.snapTo(target)
        else anim.animateTo(target, tween(350, easing = FastOutSlowInEasing))
    }
    return anim.asState()
}

internal val ES = Locale("es", "MX")
internal fun rpmText(rpm: Float?): String {
    val v = rpm?.toInt() ?: return "--"
    return if (v >= 1000) "${v / 1000},${(v % 1000).toString().padStart(3, '0')}" else v.toString()
}
internal fun speedText(speed: Float?): String = speed?.let { Math.round(it).toString() } ?: "--"

/** Escalas: constantes (armarlas en cada dibujo era basura para el recolector). */
internal val SPEED_LABELS = (0..10).map { (it * 20).toString() }
internal val SPEED_LABELS_220 = (0..11).map { (it * 20).toString() }
internal val RPM_LABELS = (0..8).map { it.toString() }

/**
 * La parte fija de un medidor se pinta una sola vez en su propia textura: al moverse la aguja, la GPU
 * solo pega esa textura y dibuja lo que cambia. En las miniaturas no (serían texturas enormes).
 */
internal fun Modifier.staticLayer(live: Boolean): Modifier =
    if (live) graphicsLayer(compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen) else this

// =================================================================== Piezas de dibujo

internal val G_BLUE = Color(0xFF4F8DFF)
internal val G_SKY = Color(0xFF8FC3FF)
internal val G_CYAN = Color(0xFF5FE1FF)
internal val G_GREEN = Color(0xFF39D98A)
internal val G_LIME = Color(0xFF9BE15D)
internal val G_AMBER = Color(0xFFFFB020)
internal val G_ORANGE = Color(0xFFFF7A1A)
internal val G_RED = Color(0xFFFF453A)

internal fun polar(c: Offset, r: Float, deg: Float): Offset {
    val a = Math.toRadians(deg.toDouble())
    return Offset(c.x + r * cos(a).toFloat(), c.y + r * sin(a).toFloat())
}

/** Arco de [start] a [start]+[sweep] con un degradado que sigue el arco (colores fijos en la escala). */
internal fun DrawScope.gradientArc(c: Offset, r: Float, start: Float, fullSweep: Float, sweep: Float, width: Float, stops: Array<Pair<Float, Color>>, alpha: Float = 1f, round: Boolean = true) {
    if (sweep <= 0.2f) return
    // Los trazos translúcidos son los resplandores.
    if (alpha < 0.5f && com.civiclauncher.app.launcher.LowPower.reduced) return
    val brush = arcBrush(c, fullSweep, stops)
    rotate(start, c) {
        drawArc(brush, 0f, sweep, false, Offset(c.x - r, c.y - r), Size(2 * r, 2 * r), alpha = alpha, style = Stroke(width, cap = if (round) StrokeCap.Round else StrokeCap.Butt))
    }
}

private data class ArcKey(val stops: Array<Pair<Float, Color>>, val sweep: Float, val cx: Float, val cy: Float)
private val arcBrushes = HashMap<ArcKey, Brush>()

/**
 * El degradado de un arco, uno por combinación: crear un shader en cada trazo y cada cuadro generaba
 * basura y tirones (con 8 medidores, 24 por cuadro). [stops] debe ser una constante: se compara por
 * identidad. Solo se usa desde el hilo de dibujo.
 */
private fun arcBrush(c: Offset, fullSweep: Float, stops: Array<Pair<Float, Color>>): Brush {
    val key = ArcKey(stops, fullSweep, c.x, c.y)
    arcBrushes[key]?.let { return it }
    if (arcBrushes.size >= 64) arcBrushes.clear()
    val scaled = stops.map { (f, col) -> f * fullSweep / 360f to col }.toMutableList()
    // Después del final vuelve al primer color: así la punta redonda del inicio no toma el último.
    scaled += 1f to stops.first().second
    return Brush.sweepGradient(*scaled.toTypedArray(), center = c).also { arcBrushes[key] = it }
}

/** Colores de los arcos, como constantes (ver [arcBrush]). */
private val STOPS_BLUE = arrayOf(0f to G_BLUE, 1f to G_SKY)
private val STOPS_WHITE_RED = arrayOf(0f to Color.White.copy(alpha = 0.6f), 0.8f to Color.White, 0.85f to G_RED, 1f to G_RED)
private val STOPS_SOFT_BLUE = arrayOf(0f to G_BLUE.copy(alpha = 0.6f), 1f to G_SKY)
private val STOPS_FIRE = arrayOf(0f to G_AMBER, 0.6f to G_ORANGE, 1f to G_RED)
private val STOPS_CYAN = arrayOf(0f to G_CYAN, 1f to G_SKY)
private val STOPS_RING = arrayOf(0f to G_BLUE, 0.75f to G_SKY, RPM_GAUGE_RED / RPM_GAUGE_MAX to G_SKY, 0.86f to G_RED, 1f to G_RED)
private val ZONES_SPEED = arrayOf(0f to G_GREEN, 0.55f to G_LIME, 0.7f to G_AMBER, 0.9f to G_RED)
private val ZONES_RPM = arrayOf(0f to G_GREEN, 0.55f to G_LIME, 0.7f to G_AMBER, RPM_GAUGE_RED / RPM_GAUGE_MAX to G_RED, 1f to G_RED)

internal fun DrawScope.plainArc(c: Offset, r: Float, start: Float, sweep: Float, color: Color, width: Float, round: Boolean = true) {
    drawArc(color, start, sweep, false, Offset(c.x - r, c.y - r), Size(2 * r, 2 * r), style = Stroke(width, cap = if (round) StrokeCap.Round else StrokeCap.Butt))
}

/** Marcas de la escala: [segments] tramos; [color] decide el color de cada marca (null = no se dibuja). */
internal inline fun DrawScope.ticks(c: Offset, rOut: Float, start: Float, sweep: Float, segments: Int, len: (Int) -> Float, width: (Int) -> Float, color: (Int, Float) -> Color?) {
    for (i in 0..segments) {
        val f = i / segments.toFloat()
        val col = color(i, f) ?: continue
        val a = start + sweep * f
        drawLine(col, polar(c, rOut, a), polar(c, rOut - len(i), a), strokeWidth = width(i), cap = StrokeCap.Round)
    }
}

/** Números de la escala (texto nativo: rápido, se dibuja con el mismo pincel). */
internal fun DrawScope.scaleLabels(paint: android.graphics.Paint, c: Offset, r: Float, start: Float, sweep: Float, labels: List<String>, color: Color, sizePx: Float) {
    paint.color = color.toArgb()
    paint.textSize = sizePx
    val n = labels.size - 1
    labels.forEachIndexed { i, t ->
        val p = polar(c, r, start + sweep * i / n)
        drawContext.canvas.nativeCanvas.drawText(t, p.x, p.y + sizePx * 0.36f, paint)
    }
}

internal fun DrawScope.needle(c: Offset, len: Float, deg: Float, color: Color, width: Float, tail: Float = 0f, glow: Boolean = true) {
    val tip = polar(c, len, deg)
    val back = polar(c, -tail, deg)
    if (glow && !com.civiclauncher.app.launcher.LowPower.reduced) drawLine(color.copy(alpha = 0.25f), back, tip, strokeWidth = width * 3.2f, cap = StrokeCap.Round)
    drawLine(color, back, tip, strokeWidth = width, cap = StrokeCap.Round)
}

@Composable
internal fun rememberLabelPaint() = remember {
    android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = android.graphics.Paint.Align.CENTER
        typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
    }
}

/** Fondo de todos estos widgets (el mismo de las tarjetas de Inicio). */
@Composable
private fun GaugeSurface(modifier: Modifier, onClick: (() -> Unit)?, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(Civic.radii.xl)
    Box(
        modifier
            .clip(shape)
            .background(Civic.colors.surface)
            .bevelBorder(shape)
            .let { if (onClick != null) it.clickable(role = Role.Button, onClick = onClick) else it },
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Un medidor cuadrado centrado en el lugar, con texto encima al centro. */
@Composable
private fun DialBox(
    modifier: Modifier,
    onClick: (() -> Unit)?,
    live: Boolean,
    draw: DrawScope.(c: Offset, r: Float) -> Unit,
    static: (DrawScope.(c: Offset, r: Float) -> Unit)? = null,
    center: @Composable () -> Unit,
) {
    GaugeSurface(modifier, onClick) {
        Box(Modifier.fillMaxHeight().aspectRatio(1f, matchHeightConstraintsFirst = true).padding(14.dp), contentAlignment = Alignment.Center) {
            if (static != null) {
                Canvas(Modifier.fillMaxSize().staticLayer(live)) {
                    val r = min(size.width, size.height) / 2f
                    if (r >= 40.dp.toPx()) static(Offset(size.width / 2f, size.height / 2f), r)
                }
            }
            Canvas(Modifier.fillMaxSize()) {
                val r = min(size.width, size.height) / 2f
                // Sin espacio todavía: no se dibuja (un degradado de radio 0 cierra la app).
                if (r < 40.dp.toPx()) return@Canvas
                draw(Offset(size.width / 2f, size.height / 2f), r)
            }
            center()
        }
    }
}

internal fun big(size: Int, weight: FontWeight = FontWeight.Light, color: Color? = null, glow: Color? = null) = TextStyle(
    // Inter, como el resto de Civic (antes, sin familia, estos números salían en la fuente del sistema).
    fontFamily = com.civiclauncher.app.ui.design.Inter,
    fontSize = size.sp,
    lineHeight = (size + 2).sp,
    fontWeight = weight,
    fontFeatureSettings = "tnum",
    letterSpacing = (-0.01).em,
    shadow = glow?.takeUnless { com.civiclauncher.app.launcher.LowPower.reduced }?.let { Shadow(it, blurRadius = 18f) },
).let { if (color != null) it.copy(color = color) else it }

@Composable
internal fun UnitLabel(text: String, color: Color = Civic.colors.textDim) {
    Text(text, style = Civic.type.caption.copy(fontSize = 10.sp, lineHeight = 12.sp, letterSpacing = 0.2.em), color = color)
}

// =================================================================== Medidores

/**
 * Un medidor de velocidad o RPM según [w]. [live] falso: miniatura del menú (valores fijos, sin
 * animación ni lecturas).
 */
@Composable
internal fun GaugeWidget(w: SportWidget, d: DriveValues, live: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val click = if (live) onClick else null
    when (w) {
        SportWidget.SPEED_RINGS -> SpeedRings(d, live, modifier, click)
        SportWidget.SPEED_BAR -> SpeedBars(d, live, modifier, click)
        SportWidget.SPEED_CLASSIC -> ClassicDial(d.speed, SPEED_GAUGE_MAX, speed = true, d, live, modifier, click)
        SportWidget.SPEED_SIMPLE -> SimpleDial(speedText(d.speed), "KM/H", gaugeFraction(d.speed, SPEED_GAUGE_MAX, live), live, modifier, click)
        SportWidget.SPEED_DIGITAL -> SpeedDigital(d, live, modifier, click)
        SportWidget.SPEED_SPORT -> SportDial(d.speed, SPEED_GAUGE_MAX, speed = true, d, live, modifier, click)
        SportWidget.SPEED_FIRE -> FireDial(d.speed, SPEED_GAUGE_MAX, speed = true, d, live, modifier, click)
        SportWidget.RPM_CLASSIC -> ClassicDial(d.rpm, RPM_GAUGE_MAX, speed = false, d, live, modifier, click)
        SportWidget.RPM_SIMPLE -> SimpleDial(rpmText(d.rpm), "RPM", gaugeFraction(d.rpm, RPM_GAUGE_MAX, live), live, modifier, click)
        SportWidget.RPM_DIGITAL -> RpmDigital(d, live, modifier, click)
        SportWidget.RPM_SCIFI -> SciFiDial(d, live, modifier, click)
        SportWidget.RPM_FIRE -> FireDial(d.rpm, RPM_GAUGE_MAX, speed = false, d, live, modifier, click)
        SportWidget.RPM_ARC -> RpmArcLine(d, live, modifier, click)
        SportWidget.RPM_STANDARD -> SportDial(d.rpm, RPM_GAUGE_MAX, speed = false, d, live, modifier, click)
        SportWidget.RPM_RING -> RpmRing(d, live, modifier, click)
        else -> {}
    }
}

/** Velocidad en un anillo exterior y RPM en uno interior; abajo RPM y marcha. */
@Composable
private fun SpeedRings(d: DriveValues, live: Boolean, modifier: Modifier, onClick: (() -> Unit)?) {
    val fs by gaugeFraction(d.speed, SPEED_GAUGE_MAX, live)
    val fr by gaugeFraction(d.rpm, RPM_GAUGE_MAX, live)
    val ink = Civic.colors.text
    DialBox(modifier, onClick, live, draw = { c, r ->
        val outer = r - 6.dp.toPx()
        val inner = outer - 14.dp.toPx()
        plainArc(c, outer, 135f, 270f, ink.copy(alpha = 0.08f), 6.dp.toPx())
        gradientArc(c, outer, 135f, 270f, 270f * fs, 6.dp.toPx(), STOPS_BLUE)
        plainArc(c, inner, 135f, 270f, ink.copy(alpha = 0.06f), 3.dp.toPx())
        gradientArc(c, inner, 135f, 270f, 270f * fr, 3.dp.toPx(), STOPS_WHITE_RED)
    }) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(speedText(d.speed), style = big(46), color = Civic.colors.text)
            UnitLabel("KM/H")
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(rpmText(d.rpm), style = big(14, FontWeight.Medium), color = G_SKY)
                    UnitLabel("RPM")
                }
                Spacer(Modifier.width(16.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(d.gear?.toString() ?: "–", style = big(14, FontWeight.Medium), color = Civic.colors.text)
                    UnitLabel("MARCHA")
                }
            }
        }
    }
}

/** Número grande y dos barras de segmentos: velocidad (gruesa) y RPM (fina). */
@Composable
private fun SpeedBars(d: DriveValues, live: Boolean, modifier: Modifier, onClick: (() -> Unit)?) {
    val fs by gaugeFraction(d.speed, SPEED_GAUGE_MAX, live)
    val fr by gaugeFraction(d.rpm, RPM_GAUGE_MAX, live)
    val ink = Civic.colors.text
    GaugeSurface(modifier, onClick) {
        Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.Center) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(speedText(d.speed), style = big(58, FontWeight.SemiBold), color = G_SKY)
                Spacer(Modifier.width(6.dp))
                Text("km/h", style = Civic.type.label, color = Civic.colors.textDim, modifier = Modifier.padding(bottom = 10.dp))
            }
            Spacer(Modifier.height(10.dp))
            Canvas(Modifier.fillMaxWidth().height(24.dp)) { segments(fs, 28, ink, listOf(G_BLUE, G_SKY)) }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(rpmText(d.rpm), style = big(18, FontWeight.Medium), color = Civic.colors.text)
                Spacer(Modifier.width(6.dp))
                UnitLabel("RPM")
            }
            Spacer(Modifier.height(6.dp))
            Canvas(Modifier.fillMaxWidth().height(8.dp)) {
                segments(fr, 28, ink, listOf(Color.White.copy(alpha = 0.75f), Color.White), redFrom = RPM_GAUGE_RED / RPM_GAUGE_MAX)
            }
        }
    }
}

private fun DrawScope.segments(f: Float, n: Int, ink: Color, colors: List<Color>, redFrom: Float = 2f) {
    val gap = 2.5.dp.toPx()
    val w = (size.width - gap * (n - 1)) / n
    for (i in 0 until n) {
        val at = (i + 0.5f) / n
        val lit = at <= f
        val col = when {
            !lit -> ink.copy(alpha = 0.08f)
            at >= redFrom -> G_RED
            else -> androidx.compose.ui.graphics.lerp(colors[0], colors[1], at)
        }
        drawRoundRect(col, Offset(i * (w + gap), 0f), Size(w, size.height), androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
    }
}

/** Clásico: escala con números, banda de colores (verde → ámbar → rojo), aguja y lectura digital. */
@Composable
private fun ClassicDial(value: Float?, max: Float, speed: Boolean, d: DriveValues, live: Boolean, modifier: Modifier, onClick: (() -> Unit)?) {
    val f by gaugeFraction(value, max, live)
    val paint = rememberLabelPaint()
    val ink = Civic.colors.text
    val labels = if (speed) SPEED_LABELS else RPM_LABELS
    DialBox(modifier, onClick, live, static = { c, r ->
        val start = 150f
        val sweep = 240f
        val rTicks = r - 2.dp.toPx()
        drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.05f), Color.Transparent), c, r), r, c)
        ticks(c, rTicks, start, sweep, labels.size * 5 - 5, len = { if (it % 5 == 0) 9.dp.toPx() else 4.dp.toPx() }, width = { if (it % 5 == 0) 2.dp.toPx() else 1.dp.toPx() }) { _, at ->
            if (!speed && at * max >= RPM_GAUGE_RED) G_RED else ink.copy(alpha = 0.7f)
        }
        // Banda de zonas por dentro de las marcas.
        val band = rTicks - 13.dp.toPx()
        gradientArc(c, band, start, sweep, sweep, 4.dp.toPx(), if (speed) ZONES_SPEED else ZONES_RPM, alpha = 0.9f, round = false)
        scaleLabels(paint, c, band - 15.dp.toPx(), start, sweep, labels, ink.copy(alpha = 0.75f), 10.dp.toPx())
    }, draw = { c, r ->
        val band = r - 15.dp.toPx()
        needle(c, band + 2.dp.toPx(), 150f + 240f * f, Color.White, 2.5.dp.toPx(), tail = 10.dp.toPx())
        drawCircle(Color(0xFF2A2F38), 9.dp.toPx(), c)
        drawCircle(Color.White, 4.dp.toPx(), c)
    }) {
        Column(Modifier.offset(y = 44.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (speed) speedText(d.speed) else rpmText(d.rpm), style = big(26, FontWeight.Medium), color = Civic.colors.text)
            UnitLabel(if (speed) "KM/H · ${rpmText(d.rpm)} RPM" else "RPM")
        }
    }
}

/** Simple: un círculo fino con un punto que marca el valor; el número al centro. */
@Composable
private fun SimpleDial(text: String, unit: String, fState: androidx.compose.runtime.State<Float>, live: Boolean, modifier: Modifier, onClick: (() -> Unit)?) {
    val f by fState
    val ink = Civic.colors.text
    DialBox(modifier, onClick, live, draw = { c, r ->
        val rr = r - 6.dp.toPx()
        drawCircle(G_SKY.copy(alpha = 0.35f), rr, c, style = Stroke(1.5.dp.toPx()))
        ticks(c, rr + 3.dp.toPx(), -90f, 360f, 60, len = { 3.dp.toPx() }, width = { 1.dp.toPx() }) { i, _ -> if (i % 5 == 0) ink.copy(alpha = 0.18f) else null }
        val p = polar(c, rr, -90f + 360f * f)
        drawCircle(G_SKY.copy(alpha = 0.3f), 9.dp.toPx(), p)
        drawCircle(Color.White, 4.dp.toPx(), p)
    }) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text, style = big(44, FontWeight.Normal), color = G_SKY)
            UnitLabel(unit, G_SKY.copy(alpha = 0.7f))
        }
    }
}

/** Digital: el número enorme, la marcha en un círculo y las RPM con una línea abajo. */
@Composable
private fun SpeedDigital(d: DriveValues, live: Boolean, modifier: Modifier, onClick: (() -> Unit)?) {
    val fr by gaugeFraction(d.rpm, RPM_GAUGE_MAX, live)
    val ink = Civic.colors.text
    GaugeSurface(modifier, onClick) {
        Column(Modifier.fillMaxSize().padding(18.dp)) {
            Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(speedText(d.speed), style = big(82, FontWeight.Bold).copy(letterSpacing = (-0.04).em), color = Civic.colors.text, modifier = Modifier.weight(1f))
                Box(
                    Modifier.size(52.dp).clip(CircleShape).background(Color.White).padding(4.dp).clip(CircleShape).background(G_BLUE),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(d.gear?.toString() ?: "–", style = big(20, FontWeight.Bold), color = Color.White)
                    }
                }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                UnitLabel("KM/H")
                Spacer(Modifier.weight(1f))
                UnitLabel("RPM  ")
                Text(rpmText(d.rpm), style = big(20, FontWeight.SemiBold), color = Civic.colors.text)
            }
            Spacer(Modifier.height(8.dp))
            Canvas(Modifier.fillMaxWidth().height(3.dp)) {
                drawRect(ink.copy(alpha = 0.10f))
                drawRect(if (fr * RPM_GAUGE_MAX >= RPM_GAUGE_RED) G_RED else Color.White, size = Size(size.width * fr, size.height))
            }
        }
    }
}

/** Estándar: escala con números, barrido azul con resplandor y aguja blanca. */
@Composable
private fun SportDial(value: Float?, max: Float, speed: Boolean, d: DriveValues, live: Boolean, modifier: Modifier, onClick: (() -> Unit)?) {
    val f by gaugeFraction(value, max, live)
    val paint = rememberLabelPaint()
    val ink = Civic.colors.text
    val labels = if (speed) SPEED_LABELS else RPM_LABELS
    DialBox(modifier, onClick, live, static = { c, r ->
        val start = 135f
        val sweep = 270f
        val rr = r - 3.dp.toPx()
        drawCircle(Color.Black.copy(alpha = 0.25f), rr, c)
        ticks(c, rr, start, sweep, (labels.size - 1) * 4, len = { if (it % 4 == 0) 8.dp.toPx() else 4.dp.toPx() }, width = { if (it % 4 == 0) 1.6.dp.toPx() else 1.dp.toPx() }) { _, at ->
            if (!speed && at * max >= RPM_GAUGE_RED) G_RED.copy(alpha = 0.9f) else ink.copy(alpha = 0.55f)
        }
        scaleLabels(paint, c, rr - 18.dp.toPx(), start, sweep, labels, ink.copy(alpha = 0.6f), 9.dp.toPx())
        plainArc(c, rr - 32.dp.toPx(), start, sweep, ink.copy(alpha = 0.06f), 7.dp.toPx(), round = false)
    }, draw = { c, r ->
        val start = 135f
        val sweep = 270f
        val rr = r - 3.dp.toPx()
        val band = rr - 32.dp.toPx()
        gradientArc(c, band, start, sweep, sweep * f, 14.dp.toPx(), STOPS_BLUE, alpha = 0.18f, round = false)
        gradientArc(c, band, start, sweep, sweep * f, 7.dp.toPx(), STOPS_SOFT_BLUE, round = false)
        needle(c, rr - 4.dp.toPx(), start + sweep * f, Color.White, 2.dp.toPx(), tail = 0f)
    }) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (speed) speedText(d.speed) else rpmText(d.rpm), style = big(if (speed) 34 else 26), color = Civic.colors.text)
            UnitLabel(if (speed) "KM/H" else "RPM")
            if (speed) {
                Spacer(Modifier.height(6.dp))
                Text("${rpmText(d.rpm)} RPM", style = Civic.type.caption.copy(fontSize = 10.sp), color = Civic.colors.textDim)
            }
        }
    }
}

/** Ardiente: escala que pasa de ámbar a rojo, barrido encendido y número con brillo. */
@Composable
private fun FireDial(value: Float?, max: Float, speed: Boolean, d: DriveValues, live: Boolean, modifier: Modifier, onClick: (() -> Unit)?) {
    val f by gaugeFraction(value, max, live)
    val paint = rememberLabelPaint()
    val labels = if (speed) SPEED_LABELS else RPM_LABELS
    DialBox(modifier, onClick, live, static = { c, r ->
        val start = 135f
        val sweep = 270f
        val rr = r - 3.dp.toPx()
        drawCircle(Brush.radialGradient(listOf(G_ORANGE.copy(alpha = 0.10f), Color.Transparent), c, rr), rr, c)
        ticks(c, rr, start, sweep, (labels.size - 1) * 4, len = { if (it % 4 == 0) 9.dp.toPx() else 4.dp.toPx() }, width = { if (it % 4 == 0) 2.dp.toPx() else 1.dp.toPx() }) { _, at ->
            androidx.compose.ui.graphics.lerp(G_AMBER, G_RED, at).copy(alpha = 0.35f)
        }
        scaleLabels(paint, c, rr - 19.dp.toPx(), start, sweep, labels, G_AMBER.copy(alpha = 0.8f), 9.dp.toPx())
    }, draw = { c, r ->
        val start = 135f
        val sweep = 270f
        val rr = r - 3.dp.toPx()
        // Solo las marcas ya alcanzadas; las apagadas están en la capa fija.
        ticks(c, rr, start, sweep, (labels.size - 1) * 4, len = { if (it % 4 == 0) 9.dp.toPx() else 4.dp.toPx() }, width = { if (it % 4 == 0) 2.dp.toPx() else 1.dp.toPx() }) { _, at ->
            if (at <= f) androidx.compose.ui.graphics.lerp(G_AMBER, G_RED, at) else null
        }
        gradientArc(c, rr - 4.dp.toPx(), start, sweep, sweep * f, 12.dp.toPx(), STOPS_FIRE, alpha = 0.22f)
        needle(c, rr - 6.dp.toPx(), start + sweep * f, G_ORANGE, 2.5.dp.toPx(), tail = 0f)
    }) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (speed) speedText(d.speed) else rpmText(d.rpm), style = big(if (speed) 38 else 28, FontWeight.Medium, glow = G_ORANGE), color = G_AMBER)
            UnitLabel(if (speed) "KM/H" else "RPM", G_AMBER.copy(alpha = 0.75f))
            if (speed) {
                Spacer(Modifier.height(6.dp))
                Text("${rpmText(d.rpm)} RPM", style = Civic.type.caption.copy(fontSize = 10.sp), color = G_AMBER.copy(alpha = 0.7f))
            }
        }
    }
}

/** RPM digital: el número grande y una fila de luces de cambio. */
@Composable
private fun RpmDigital(d: DriveValues, live: Boolean, modifier: Modifier, onClick: (() -> Unit)?) {
    val f by gaugeFraction(d.rpm, RPM_GAUGE_MAX, live)
    val ink = Civic.colors.text
    GaugeSurface(modifier, onClick) {
        Column(Modifier.fillMaxSize().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(rpmText(d.rpm), style = big(56, FontWeight.SemiBold), color = if (d.rpm != null && d.rpm >= RPM_GAUGE_RED) G_RED else Civic.colors.text)
            UnitLabel("RPM")
            Spacer(Modifier.height(16.dp))
            Canvas(Modifier.fillMaxWidth(0.8f).height(10.dp)) {
                val n = 10
                val gap = 6.dp.toPx()
                val w = (size.width - gap * (n - 1)) / n
                for (i in 0 until n) {
                    val at = (i + 0.5f) / n
                    val col = when {
                        at >= 0.85f -> G_RED
                        at >= 0.6f -> G_AMBER
                        else -> G_GREEN
                    }
                    val lit = at <= f
                    val center = Offset(i * (w + gap) + w / 2f, size.height / 2f)
                    if (lit) drawCircle(col.copy(alpha = 0.3f), size.height, center)
                    drawCircle(if (lit) col else ink.copy(alpha = 0.10f), size.height / 2f, center)
                }
            }
        }
    }
}

/** Ciencia ficción: muchas marcas finas que se encienden en cian, con resplandor, y una aguja fina. */
@Composable
private fun SciFiDial(d: DriveValues, live: Boolean, modifier: Modifier, onClick: (() -> Unit)?) {
    val f by gaugeFraction(d.rpm, RPM_GAUGE_MAX, live)
    val paint = rememberLabelPaint()
    val ink = Civic.colors.text
    DialBox(modifier, onClick, live, static = { c, r ->
        val start = 135f
        val sweep = 270f
        val rr = r - 3.dp.toPx()
        drawCircle(Brush.radialGradient(listOf(G_CYAN.copy(alpha = 0.12f), Color.Transparent), c, rr), rr, c)
        drawCircle(G_CYAN.copy(alpha = 0.15f), rr * 0.62f, c, style = Stroke(1.dp.toPx()))
        ticks(c, rr, start, sweep, 80, len = { if (it % 10 == 0) 10.dp.toPx() else 6.dp.toPx() }, width = { 1.2.dp.toPx() }) { _, _ -> ink.copy(alpha = 0.12f) }
        scaleLabels(paint, c, rr - 22.dp.toPx(), start, sweep, RPM_LABELS, G_CYAN.copy(alpha = 0.6f), 9.dp.toPx())
    }, draw = { c, r ->
        val start = 135f
        val sweep = 270f
        val rr = r - 3.dp.toPx()
        ticks(c, rr, start, sweep, 80, len = { if (it % 10 == 0) 10.dp.toPx() else 6.dp.toPx() }, width = { 1.2.dp.toPx() }) { _, at ->
            when {
                at > f -> null
                at * RPM_GAUGE_MAX >= RPM_GAUGE_RED -> G_RED
                else -> G_CYAN
            }
        }
        gradientArc(c, rr - 5.dp.toPx(), start, sweep, sweep * f, 14.dp.toPx(), STOPS_CYAN, alpha = 0.12f, round = false)
        needle(c, rr - 2.dp.toPx(), start + sweep * f, G_CYAN, 1.5.dp.toPx(), tail = 0f)
    }) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(rpmText(d.rpm), style = big(28, FontWeight.Light, glow = G_CYAN), color = Color.White)
            UnitLabel("RPM", G_CYAN.copy(alpha = 0.8f))
        }
    }
}

/** RPM arco: el número grande y una línea con quiebre que se ilumina (como el tacómetro de líneas). */
@Composable
private fun RpmArcLine(d: DriveValues, live: Boolean, modifier: Modifier, onClick: (() -> Unit)?) {
    val f by gaugeFraction(d.rpm, RPM_GAUGE_MAX, live)
    val ink = Civic.colors.text
    GaugeSurface(modifier, onClick) {
        Box(Modifier.fillMaxSize().padding(18.dp)) {
            // Trazo, medidor y degradado se crean una vez por tamaño; al dibujar solo cambia cuánto se llena.
            Spacer(Modifier.fillMaxSize().drawWithCache {
                val w = size.width
                val h = size.height
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(0f, h * 0.80f); lineTo(w * 0.18f, h * 0.94f); lineTo(w, h * 0.94f)
                }
                val m = androidx.compose.ui.graphics.PathMeasure().apply { setPath(path, false) }
                val part = androidx.compose.ui.graphics.Path()
                val brush = Brush.horizontalGradient(listOf(Color.White, Color(0xFF7CFFD8), G_GREEN), startX = 0f, endX = w)
                val thin = Stroke(3.dp.toPx(), cap = StrokeCap.Round)
                val wide = Stroke(9.dp.toPx(), cap = StrokeCap.Round)
                onDrawBehind {
                    drawLine(ink.copy(alpha = 0.10f), Offset(0f, h * 0.06f), Offset(w, h * 0.06f), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
                    drawPath(path, ink.copy(alpha = 0.10f), style = thin)
                    if (f > 0.004f) {
                        part.reset()
                        m.getSegment(0f, m.length * f, part, true)
                        drawPath(part, brush, alpha = 0.2f, style = wide)
                        drawPath(part, brush, style = thin)
                        val tip = m.getPosition(m.length * f)
                        drawCircle(Color(0xFF7CFFD8).copy(alpha = 0.35f), 8.dp.toPx(), tip)
                        drawCircle(Color.White, 4.dp.toPx(), tip)
                    }
                }
            })
            Column(Modifier.align(Alignment.Center).offset(y = (-6).dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(d.rpm?.let { Math.round(it).toString() } ?: "--", style = big(56, FontWeight.Normal), color = Civic.colors.text)
                UnitLabel("RPM")
            }
        }
    }
}

/** RPM anillo: un anillo de 300° que se llena, con el número fino al centro. */
@Composable
private fun RpmRing(d: DriveValues, live: Boolean, modifier: Modifier, onClick: (() -> Unit)?) {
    val f by gaugeFraction(d.rpm, RPM_GAUGE_MAX, live)
    val ink = Civic.colors.text
    DialBox(modifier, onClick, live, draw = { c, r ->
        val rr = r - 8.dp.toPx()
        plainArc(c, rr, 120f, 300f, ink.copy(alpha = 0.07f), 8.dp.toPx())
        gradientArc(c, rr, 120f, 300f, 300f * f, 8.dp.toPx(), STOPS_RING)
    }) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(rpmText(d.rpm), style = big(36, FontWeight.ExtraLight), color = Civic.colors.text)
            UnitLabel("RPM")
        }
    }
}

// =================================================================== Reloj y fecha

private fun timeParts(now: Long): Triple<Int, Int, Int> {
    val cal = Calendar.getInstance().apply { timeInMillis = now }
    return Triple(cal.get(Calendar.HOUR), cal.get(Calendar.MINUTE), cal.get(Calendar.DAY_OF_MONTH))
}

/** Carátula analógica de líneas: marcas de las horas, minutero y horario finos, un arco de los minutos. */
private fun DrawScope.analogFace(c: Offset, r: Float, hour: Int, minute: Int, ink: Color, accent: Color) {
    drawCircle(ink.copy(alpha = 0.06f), r, c, style = Stroke(2.dp.toPx()))
    // Arco de los minutos que van de la hora (como un anillo de progreso).
    plainArc(c, r, -90f, 360f * minute / 60f, accent, 2.5.dp.toPx())
    ticks(c, r - 6.dp.toPx(), -90f, 360f, 12, len = { 5.dp.toPx() }, width = { 1.5.dp.toPx() }) { i, _ -> if (i < 12) ink.copy(alpha = 0.45f) else null }
    val hAngle = -90f + 30f * (hour % 12) + 0.5f * minute
    val mAngle = -90f + 6f * minute
    needle(c, r * 0.48f, hAngle, ink, 3.5.dp.toPx(), glow = false)
    needle(c, r * 0.74f, mAngle, accent, 2.dp.toPx(), glow = false)
    drawCircle(ink, 3.5.dp.toPx(), c)
}

/** Reloj: la hora grande con la fecha y, a un lado, un relojito de manecillas. */
@Composable
internal fun ClockWidget(modifier: Modifier) {
    val time = rememberClockText()
    val now by LocalContext.current.container.clock.now.collectAsStateWithLifecycle()
    val date = remember(now / 60_000) { SimpleDateFormat("EEE d MMM yyyy", ES).format(Date(now)).replace(".", "") }
    val (h, m, _) = timeParts(now)
    val ink = Civic.colors.text
    GaugeSurface(modifier, null) {
        Row(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(time, style = big(52, FontWeight.SemiBold).copy(letterSpacing = (-0.03).em), color = ink, maxLines = 1)
                Text(date, style = Civic.type.label, color = Civic.colors.textDim, maxLines = 1)
            }
            Canvas(Modifier.size(64.dp)) { analogFace(center, size.minDimension / 2f - 2.dp.toPx(), h, m, ink, G_SKY) }
        }
    }
}

/** Reloj analógico grande con la hora digital y la fecha abajo. */
@Composable
internal fun AnalogClockWidget(modifier: Modifier) {
    val time = rememberClockText()
    val now by LocalContext.current.container.clock.now.collectAsStateWithLifecycle()
    val date = remember(now / 60_000) { SimpleDateFormat("d MMM yyyy", ES).format(Date(now)).replace(".", "") }
    val (h, m, _) = timeParts(now)
    val ink = Civic.colors.text
    GaugeSurface(modifier, null) {
        Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(Modifier.weight(1f).aspectRatio(1f)) { analogFace(center, size.minDimension / 2f - 3.dp.toPx(), h, m, ink, G_SKY) }
            Spacer(Modifier.height(8.dp))
            Text(time, style = big(26, FontWeight.SemiBold), color = ink)
            Text(date, style = Civic.type.caption, color = Civic.colors.textDim)
        }
    }
}

/** Calendario: mes y año, el día grande, el día de la semana y la semana con hoy marcado. */
@Composable
internal fun CalendarWidget(modifier: Modifier) {
    val now by LocalContext.current.container.clock.now.collectAsStateWithLifecycle()
    val cal = remember(now / 3_600_000) { Calendar.getInstance().apply { timeInMillis = now } }
    val month = remember(cal) { SimpleDateFormat("MMMM", ES).format(cal.time).replaceFirstChar { it.uppercase() } }
    val weekday = remember(cal) { SimpleDateFormat("EEEE", ES).format(cal.time).replaceFirstChar { it.uppercase() } }
    // Semana de lunes a domingo.
    val todayIdx = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7
    GaugeSurface(modifier, null) {
        Column(Modifier.fillMaxSize().padding(18.dp)) {
            Row {
                Text(month, style = Civic.type.label.copy(fontWeight = FontWeight.SemiBold), color = G_SKY)
                Spacer(Modifier.weight(1f))
                Text(cal.get(Calendar.YEAR).toString(), style = Civic.type.label, color = Civic.colors.textDim)
            }
            Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(cal.get(Calendar.DAY_OF_MONTH).toString(), style = big(64, FontWeight.Light), color = Civic.colors.text)
                Text(weekday, style = Civic.type.label, color = Civic.colors.textDim)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("L", "M", "M", "J", "V", "S", "D").forEachIndexed { i, l ->
                    val today = i == todayIdx
                    Box(
                        Modifier.size(24.dp).clip(CircleShape).background(if (today) G_BLUE else Color.Transparent),
                        contentAlignment = Alignment.Center,
                    ) { Text(l, style = Civic.type.caption.copy(fontSize = 11.sp, fontWeight = if (today) FontWeight.Bold else FontWeight.Normal), color = if (today) Color.White else Civic.colors.textDim) }
                }
            }
        }
    }
}

/** Botón del salvapantallas. */
@Composable
internal fun ScreensaverWidget(modifier: Modifier, live: Boolean, strip: Boolean = false) {
    GaugeSurface(modifier, if (live) ({ SportLayout.screensaver.value = true }) else null) {
        if (strip) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(P.Moon, null, tint = G_SKY, modifier = Modifier.size(34.dp))
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("Salvapantallas", style = Civic.type.title.copy(fontSize = 19.sp), color = Civic.colors.text)
                    Text("Toca para iniciar", style = Civic.type.caption, color = Civic.colors.textDim)
                }
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(P.Moon, null, tint = G_SKY, modifier = Modifier.size(44.dp))
                Spacer(Modifier.height(12.dp))
                Text("Salvapantallas", style = Civic.type.title.copy(fontSize = 19.sp), color = Civic.colors.text)
                Text("Toca para iniciar", style = Civic.type.caption, color = Civic.colors.textDim)
            }
        }
    }
}

/**
 * Salvapantallas: fondo negro con la hora fina y la fecha. Cambia una vez por minuto (y de lugar un
 * poco cada minuto, para no marcar la pantalla). Tocar en cualquier lado lo quita.
 */
@Composable
internal fun Screensaver(onExit: () -> Unit) {
    val now by LocalContext.current.container.clock.now.collectAsStateWithLifecycle()
    val time = rememberClockText()
    val date = remember(now / 60_000) { SimpleDateFormat("EEEE d 'de' MMMM", ES).format(Date(now)).replaceFirstChar { it.uppercase() } }
    val minute = (now / 60_000).toInt()
    // Si vas navegando, la siguiente vuelta se sigue viendo.
    val navigating = LocalContext.current.container.nav.state.collectAsStateWithLifecycle().value as? com.civiclauncher.app.launcher.nav.NavState.Navigating
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(role = Role.Button, onClickLabel = "Salir del salvapantallas", onClick = onExit),
    ) {
        val dx = ((minute * 37) % 120 - 60).dp
        val dy = ((minute * 53) % 80 - 40).dp
        Column(Modifier.align(Alignment.Center).offset(dx, dy), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(time, style = big(120, FontWeight.ExtraLight).copy(letterSpacing = (-0.03).em), color = Color.White.copy(alpha = 0.85f))
            Text(date, style = Civic.type.title.copy(fontSize = 20.sp, fontWeight = FontWeight.Light), color = Color.White.copy(alpha = 0.5f))
            if (navigating != null) {
                Spacer(Modifier.height(24.dp))
                com.civiclauncher.app.launcher.nav.NavBanner(navigating.progress, navigating.rerouting, compact = true)
            }
        }
    }
}
