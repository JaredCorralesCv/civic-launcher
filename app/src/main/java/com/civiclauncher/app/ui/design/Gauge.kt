package com.civiclauncher.app.ui.design

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private const val START_ANGLE = 135f
private const val SWEEP = 270f

/**
 * Medidor circular para datos del motor (RPM, velocidad, temperatura).
 *
 * Rendimiento (clave en la Mali-450):
 *  - Las figuras fijas (pista, marcas) se calculan una vez con drawWithCache.
 *  - La aguja se anima en la fase de DIBUJO: el valor animado no provoca recomposición.
 *  - Solo el número del centro se recompone, y solo cuando cambia el valor redondeado.
 *
 * [value] es una función para que leer el dato no recomponga a quien llama.
 * [warnAt]/[dangerAt]: desde ahí el arco cambia a amarillo/rojo. [reverse] = el peligro está abajo (voltaje).
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
        // Los datos del OBD llegan a saltos (≈5 por segundo); interpolamos para que la aguja se mueva suave.
        snapshotFlow { value().coerceIn(min, max) }.collectLatest { target ->
            animated.animateTo(target, tween(durationMillis = 220, easing = LinearEasing))
        }
    }

    Box(
        modifier
            .size(size)
            .drawWithCache {
                val stroke = size.toPx() * 0.075f
                val inset = stroke / 2 + 2.dp.toPx()
                val arcSize = Size(this.size.width - inset * 2, this.size.height - inset * 2)
                val topLeft = Offset(inset, inset)
                val center = Offset(this.size.width / 2, this.size.height / 2)
                val radius = arcSize.width / 2
                val trackStroke = Stroke(width = stroke, cap = StrokeCap.Round)
                val tickOuter = radius - stroke * 0.9f
                val tickInner = tickOuter - stroke * 0.55f
                val ticks = (0..majorTicks).map { i ->
                    val a = Math.toRadians((START_ANGLE + SWEEP * i / majorTicks).toDouble())
                    Offset(center.x + (tickInner * cos(a)).toFloat(), center.y + (tickInner * sin(a)).toFloat()) to
                        Offset(center.x + (tickOuter * cos(a)).toFloat(), center.y + (tickOuter * sin(a)).toFloat())
                }
                fun fractionOf(v: Float) = ((v - min) / (max - min)).coerceIn(0f, 1f)
                onDrawBehind {
                    drawArc(c.surfaceRaised, START_ANGLE, SWEEP, false, topLeft, arcSize, style = trackStroke)
                    ticks.forEach { (a, b) -> drawLine(c.line, a, b, strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round) }
                    val v = animated.value
                    val color = when {
                        dangerAt != null && v >= dangerAt -> c.bad
                        warnAt != null && v >= warnAt -> c.warn
                        else -> c.accent
                    }
                    val f = fractionOf(v)
                    if (f > 0.001f) drawArc(color, START_ANGLE, SWEEP * f, false, topLeft, arcSize, style = trackStroke)
                    // Punto al final del arco: indica el valor exacto sin dibujar una aguja completa.
                    val a = Math.toRadians((START_ANGLE + SWEEP * f).toDouble())
                    drawCircle(
                        c.text,
                        radius = stroke * 0.32f,
                        center = Offset(center.x + (radius * cos(a)).toFloat(), center.y + (radius * sin(a)).toFloat()),
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            GaugeReadout(value, decimals)
            Text(unit, style = Civic.type.gaugeUnit.colored(c.textDim))
            Text(label, style = Civic.type.caption.colored(c.textFaint))
        }
    }
}

/** Separado para que solo este texto se recomponga cuando cambia el número. */
@Composable
private fun GaugeReadout(value: () -> Float, decimals: Int) {
    val v = value()
    val text = if (decimals == 0) v.roundToInt().toString() else "%.${decimals}f".format(v)
    Text(text, style = Civic.type.gaugeValue.colored(Civic.colors.text))
}
