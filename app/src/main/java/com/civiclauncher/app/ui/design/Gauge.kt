package com.civiclauncher.app.ui.design

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Abierto abajo, como el tacómetro de Inicio: empieza abajo a la izquierda y da 240°. */
private const val START_ANGLE = 150f
private const val SWEEP = 240f

/** Colores de la banda: azul que se aclara, ámbar al acercarse al límite y rojo en la zona de peligro. */
private val DEEP = Color(0xFF1F4FE0)
private val BLUE = Color(0xFF3B8BFF)
private val LIGHT = Color(0xFF7CC8FF)
private val AMBER = Color(0xFFFFB020)
private val RED = Color(0xFFFF453A)

/**
 * Medidor para datos del motor (RPM, velocidad, temperatura), con el mismo estilo que el tacómetro
 * de Inicio: una banda gruesa por fuera que se llena con el valor y cambia de color con él (los
 * colores están fijos en la escala: al subir, la banda "descubre" el ámbar y el rojo), una escala de
 * rayitas finas por dentro, una aguja de luz y el número en medio.
 *
 * Rendimiento (clave en la Mali-450):
 *  - Lo fijo (escala, degradado, zona roja) se calcula una vez con drawWithCache.
 *  - La banda y la aguja se animan en la fase de DIBUJO: el valor animado no recompone nada.
 *  - Solo el número del centro se recompone, y solo cuando cambia el valor redondeado.
 *
 * [value] es una función para que leer el dato no recomponga a quien llama.
 * [warnAt]/[dangerAt]: desde ahí la banda se pone ámbar/roja y la escala se marca en rojo.
 */
@Composable
fun Gauge(
    value: () -> Float,
    min: Float,
    max: Float,
    label: String,
    unit: String,
    modifier: Modifier = Modifier,
    size: Dp = 220.dp,
    warnAt: Float? = null,
    dangerAt: Float? = null,
    decimals: Int = 0,
    majorTicks: Int = 6,
) {
    val c = Civic.colors
    val animated = remember { Animatable(min) }
    LaunchedEffect(Unit) {
        // Los datos del OBD llegan a saltos (≈5 por segundo); se interpolan para que la banda se mueva suave.
        snapshotFlow { value().coerceIn(min, max) }.collectLatest { target ->
            animated.animateTo(target, tween(durationMillis = 300, easing = FastOutSlowInEasing))
        }
    }
    val ink = if (c.isDark) Color.White else Color(0xFF15181D)

    Box(
        modifier
            .size(size)
            .drawWithCache {
                val band = this.size.width * 0.05f
                val halo = band * 0.5f
                val bandR = this.size.width / 2f - halo - band / 2f
                // El arco está abierto abajo: se baja un poco para que quede centrado a la vista.
                val center = Offset(this.size.width / 2f, this.size.height / 2f + bandR * 0.22f)
                val bTop = Offset(center.x - bandR, center.y - bandR)
                val bSize = Size(bandR * 2, bandR * 2)
                val round = Stroke(band, cap = StrokeCap.Round)
                val glow = Stroke(band + halo * 2f, cap = StrokeCap.Round)
                fun fraction(v: Float) = ((v - min) / (max - min)).coerceIn(0f, 1f)
                fun angleOf(v: Float) = START_ANGLE + SWEEP * fraction(v)
                fun at(r: Float, deg: Float): Offset {
                    val a = Math.toRadians(deg.toDouble())
                    return Offset(center.x + r * cos(a).toFloat(), center.y + r * sin(a).toFloat())
                }

                // Degradado fijo a lo largo de la escala (empieza en 0°: al dibujar se gira el lienzo).
                val span = SWEEP / 360f
                val stops = buildList {
                    add(0f to DEEP)
                    add(span * 0.45f to BLUE)
                    val warnF = warnAt?.let(::fraction)
                    val dangerF = dangerAt?.let(::fraction)
                    if (warnF != null) {
                        add(span * (warnF * 0.92f) to LIGHT)
                        add(span * warnF to AMBER)
                    } else {
                        add(span to LIGHT)
                    }
                    if (dangerF != null) add(span * dangerF to RED)
                    add(span to (if (dangerF != null) RED else if (warnF != null) AMBER else LIGHT))
                    // Después del final vuelve al azul: la punta redonda del inicio (que "da la vuelta"
                    // a 360°) toma este color y no el rojo del final.
                    add(1f to DEEP)
                }.sortedBy { it.first }.distinctBy { it.first }.toTypedArray()
                val fill = Brush.sweepGradient(*stops, center = center)

                // Escala: rayitas por dentro de la banda; mayores en [majorTicks], menores a la mitad.
                val tickOut = bandR - band / 2f - 4.dp.toPx()
                val ticks = (0..majorTicks * 2).map { i ->
                    val v = min + (max - min) * i / (majorTicks * 2)
                    val major = i % 2 == 0
                    val len = if (major) 8.dp.toPx() else 4.dp.toPx()
                    val color = when {
                        dangerAt != null && v >= dangerAt -> RED.copy(alpha = if (major) 0.95f else 0.6f)
                        major -> ink.copy(alpha = 0.55f)
                        else -> ink.copy(alpha = 0.22f)
                    }
                    Triple(at(tickOut, angleOf(v)), at(tickOut - len, angleOf(v)), color to if (major) 1.5.dp.toPx() else 1.dp.toPx())
                }
                val track = ink.copy(alpha = 0.08f)
                val dangerStart = dangerAt?.let(::angleOf)

                onDrawBehind {
                    drawArc(track, START_ANGLE, SWEEP, false, bTop, bSize, style = round)
                    if (dangerStart != null) {
                        drawArc(RED.copy(alpha = 0.22f), dangerStart, START_ANGLE + SWEEP - dangerStart, false, bTop, bSize, style = Stroke(band))
                    }
                    ticks.forEach { (a, b, style) -> drawLine(style.first, a, b, strokeWidth = style.second) }

                    val v = animated.value
                    val sweep = SWEEP * fraction(v)
                    if (sweep < 0.5f) return@onDrawBehind
                    rotate(START_ANGLE, center) {
                        drawArc(fill, 0f, sweep, false, bTop, bSize, alpha = 0.28f, style = glow)
                        drawArc(fill, 0f, sweep, false, bTop, bSize, style = round)
                    }
                    // Aguja de luz: de la escala a la banda.
                    val tip = START_ANGLE + sweep
                    val needle = if (dangerAt != null && v >= dangerAt) RED else Color.White
                    val from = at(tickOut - 14.dp.toPx(), tip)
                    val to = at(bandR, tip)
                    drawLine(needle.copy(alpha = 0.25f), from, to, strokeWidth = 7.dp.toPx(), cap = StrokeCap.Round)
                    drawLine(needle, from, to, strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = size * 0.2f)) {
            GaugeReadout(value, decimals, dangerAt)
            Text(unit, style = Civic.type.gaugeUnit.colored(c.textDim))
            Text(label, style = Civic.type.caption.colored(c.textFaint))
        }
    }
}

/** Separado para que solo este texto se recomponga cuando cambia el número. */
@Composable
private fun GaugeReadout(value: () -> Float, decimals: Int, dangerAt: Float?) {
    val v = value()
    val text = if (decimals == 0) v.roundToInt().toString() else "%.${decimals}f".format(v)
    val color = if (dangerAt != null && v >= dangerAt) RED else Civic.colors.text
    Text(text, style = Civic.type.gaugeValue.copy(fontWeight = FontWeight.Light).colored(color))
}
