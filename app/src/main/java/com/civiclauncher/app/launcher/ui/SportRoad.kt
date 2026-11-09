package com.civiclauncher.app.launcher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.civiclauncher.app.ui.design.Civic
import com.civiclauncher.app.ui.design.bevelBorder
import kotlin.math.min

/** Fondo común (el de las tarjetas de Inicio). */
@Composable
private fun StripSurface(modifier: Modifier, onClick: (() -> Unit)?, content: @Composable () -> Unit) {
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

private val thin = TextStyle(fontWeight = FontWeight.Light, fontFeatureSettings = "tnum", letterSpacing = (-0.02).em)

/** Velocidad en franja: el número grande y fino, al centro. */
@Composable
internal fun SpeedStrip(d: DriveValues, modifier: Modifier, onClick: (() -> Unit)?) {
    StripSurface(modifier, onClick) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(d.speed?.let { "%.0f".format(it) } ?: "--", style = thin.copy(fontSize = 58.sp, lineHeight = 60.sp), color = if (d.speed != null) Civic.colors.text else Civic.colors.textFaint)
            Text("km/h", style = Civic.type.caption.copy(fontSize = 12.sp), color = Civic.colors.textDim)
        }
    }
}

/** RPM en franja: el número a la izquierda y una barra de segmentos que se llena. */
@Composable
internal fun RpmStrip(d: DriveValues, modifier: Modifier, onClick: (() -> Unit)?) {
    val f = ((d.rpm ?: 0f) / 8000f).coerceIn(0f, 1f)
    val ink = Civic.colors.text
    StripSurface(modifier, onClick) {
        Row(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(d.rpm?.let { "%,d".format(java.util.Locale("es", "MX"), it.toInt()) } ?: "--", style = thin.copy(fontSize = 40.sp, lineHeight = 42.sp), color = if (d.rpm != null) Civic.colors.text else Civic.colors.textFaint)
                Text("RPM", style = Civic.type.caption.copy(fontSize = 11.sp), color = Civic.colors.textDim)
            }
            Spacer(Modifier.width(18.dp))
            Canvas(Modifier.weight(1f).height(18.dp)) {
                val n = 20
                val gap = 3.dp.toPx()
                val w = (size.width - gap * (n - 1)) / n
                for (i in 0 until n) {
                    val at = (i + 0.5f) / n
                    val col = when {
                        at > f -> ink.copy(alpha = 0.09f)
                        at >= 6800f / 8000f -> Color(0xFFFF453A)
                        at >= 0.6f -> Color(0xFFFFB020)
                        else -> Color(0xFF8FC3FF)
                    }
                    drawRoundRect(col, Offset(i * (w + gap), 0f), Size(w, size.height), CornerRadius(2.dp.toPx()))
                }
            }
        }
    }
}

/**
 * En carretera: el camino en perspectiva con tu carro visto por detrás. Las rayas vienen hacia ti a
 * la velocidad a la que vas (detenido, quietas). Blindado: solo se anima si Inicio está a la vista,
 * a ~20 cuadros por segundo, y en las miniaturas del menú nunca.
 */
@Composable
internal fun RoadWidget(speed: Float?, live: Boolean, modifier: Modifier, onClick: (() -> Unit)?) {
    val active = LocalScreenActive.current
    val phase = remember { mutableFloatStateOf(0f) }
    val v = speed ?: 0f
    val moving = live && active && v > 2f
    LaunchedEffect(moving, (v / 5).toInt()) {
        if (!moving) return@LaunchedEffect
        var last = 0L
        // Fracción del camino por segundo: a 100 km/h, ~1.4 rayas por segundo.
        val perSec = v / 100f * 1.4f
        while (true) {
            kotlinx.coroutines.delay(50)
            withFrameNanos { t ->
                if (last != 0L) phase.floatValue = (phase.floatValue + perSec * (t - last) / 1e9f) % 1f
                last = t
            }
        }
    }
    val shape = RoundedCornerShape(Civic.radii.xl)
    Box(
        modifier
            .clip(shape)
            .bevelBorder(shape)
            .let { if (onClick != null) it.clickable(role = Role.Button, onClick = onClick) else it },
    ) {
        // Tres capas: lo fijo de abajo (camino), las rayas (lo único que se mueve) y lo fijo de arriba
        // (oscuridad y el carro). Cada capa tiene su propio registro de dibujo: al moverse las rayas
        // solo se vuelve a dibujar esa capa, no el carro con todas sus curvas.
        Canvas(Modifier.fillMaxSize().graphicsLayer()) { drawRoadBase() }
        Canvas(Modifier.fillMaxSize().graphicsLayer()) { drawRoadStripes(phase.floatValue) }
        Canvas(Modifier.fillMaxSize().graphicsLayer()) { drawRoadTop() }
    }
}

private fun DrawScope.drawRoadBase() {
    val w = size.width
    val h = size.height
    // El camino empieza desde arriba (sin franja de cielo): lo lejano solo se oscurece.
    val horizon = 0f
    drawRect(Color(0xFF0D1014))
    // El camino: de angosto en el horizonte a ancho abajo.
    val topHalf = w * 0.11f
    val botHalf = w * 0.62f
    val cx = w / 2f
    drawPath(
        Path().apply { moveTo(cx - topHalf, horizon); lineTo(cx + topHalf, horizon); lineTo(cx + botHalf, h); lineTo(cx - botHalf, h); close() },
        Brush.verticalGradient(listOf(Color(0xFF1A1E24), Color(0xFF2A2F37)), startY = horizon, endY = h),
    )
    // Orillas.
    val edge = Color(0xFFDDE1E6)
    drawLine(edge.copy(alpha = 0.8f), Offset(cx - topHalf, horizon), Offset(cx - botHalf, h), strokeWidth = 2.5.dp.toPx())
    drawLine(edge.copy(alpha = 0.8f), Offset(cx + topHalf, horizon), Offset(cx + botHalf, h), strokeWidth = 2.5.dp.toPx())
}

private fun DrawScope.drawRoadStripes(phase: Float) {
    val w = size.width
    val h = size.height
    val horizon = 0f
    val topHalf = w * 0.11f
    val botHalf = w * 0.62f
    val cx = w / 2f
    val edge = Color(0xFFDDE1E6)
    fun xAt(lane: Float, y: Float): Float {
        val t = ((y - horizon) / (h - horizon)).coerceIn(0f, 1f)
        val half = topHalf + (botHalf - topHalf) * t
        return cx + lane * half
    }
    // Rayas de los carriles (en ±1/3 del ancho), en perspectiva: crecen y se separan al acercarse.
    val count = 7
    for (lane in listOf(-1f / 3f, 1f / 3f)) {
        for (k in 0 until count) {
            val z0 = ((k + phase) / count) % 1f
            val z1 = (z0 + 0.45f / count).coerceAtMost(1f)
            // z² da la perspectiva: lejos se juntan, cerca se separan.
            val y0 = horizon + (h - horizon) * z0 * z0
            val y1 = horizon + (h - horizon) * z1 * z1
            if (y1 <= y0) continue
            val width = (1f + 4f * z0).dp.toPx()
            drawLine(edge.copy(alpha = 0.25f + 0.6f * z0), Offset(xAt(lane, y0), y0), Offset(xAt(lane, y1), y1), strokeWidth = width, cap = StrokeCap.Butt)
        }
    }
}

private fun DrawScope.drawRoadTop() {
    val w = size.width
    val h = size.height
    val cx = w / 2f
    // Lo lejano se pierde en la oscuridad.
    drawRect(Brush.verticalGradient(0f to Color(0xF20D1014), 1f to Color(0x000D1014), startY = 0f, endY = h * 0.38f), size = Size(w, h * 0.38f))
    // El carro, por detrás, en el carril de en medio.
    val carW = min(w * 0.40f, h * 0.50f)
    rearCar(Offset(cx, h * 0.84f), carW)
}

/** Un sedán gris plata visto por detrás, con [width] de ancho y la parte de abajo en [bottom]. */
private fun DrawScope.rearCar(bottom: Offset, width: Float) {
    val wd = width
    val ht = wd * 0.62f
    val left = bottom.x - wd / 2f
    val top = bottom.y - ht
    fun x(f: Float) = left + wd * f
    fun y(f: Float) = top + ht * f
    // Sombra.
    for (i in 0 until 4) {
        val g = 0.05f * (4 - i)
        drawOval(Color.Black.copy(alpha = 0.16f), Offset(x(-g), y(0.86f)), Size(wd * (1 + 2 * g), ht * (0.22f + g)))
    }
    // Llantas.
    val tire = Color(0xFF08090B)
    drawRoundRect(tire, Offset(x(0.04f), y(0.74f)), Size(wd * 0.16f, ht * 0.26f), CornerRadius(wd * 0.03f))
    drawRoundRect(tire, Offset(x(0.80f), y(0.74f)), Size(wd * 0.16f, ht * 0.26f), CornerRadius(wd * 0.03f))
    // Cabina (más angosta arriba) con el medallón oscuro.
    val cabin = Path().apply {
        moveTo(x(0.16f), y(0.42f)); lineTo(x(0.26f), y(0.06f))
        quadraticTo(x(0.5f), y(-0.02f), x(0.74f), y(0.06f))
        lineTo(x(0.84f), y(0.42f)); close()
    }
    drawPath(cabin, Brush.verticalGradient(listOf(Color(0xFFB4BAC3), Color(0xFF858C96)), startY = y(0f), endY = y(0.42f)))
    val window = Path().apply {
        moveTo(x(0.21f), y(0.39f)); lineTo(x(0.29f), y(0.11f))
        quadraticTo(x(0.5f), y(0.05f), x(0.71f), y(0.11f))
        lineTo(x(0.79f), y(0.39f)); close()
    }
    drawPath(window, Brush.verticalGradient(listOf(Color(0xFF2A3445), Color(0xFF0B0F15)), startY = y(0.05f), endY = y(0.39f)))
    drawPath(window, Brush.linearGradient(0f to Color(0x00FFFFFF), 0.45f to Color(0x00FFFFFF), 0.52f to Color(0x2AFFFFFF), 0.6f to Color(0x00FFFFFF), start = Offset(x(0.2f), y(0.1f)), end = Offset(x(0.8f), y(0.4f))))
    // Carrocería: cajuela y defensa, con brillo arriba y sombra en los costados.
    val body = Path().apply {
        moveTo(x(0.03f), y(0.50f))
        quadraticTo(x(0.02f), y(0.40f), x(0.12f), y(0.40f))
        lineTo(x(0.88f), y(0.40f))
        quadraticTo(x(0.98f), y(0.40f), x(0.97f), y(0.50f))
        lineTo(x(0.97f), y(0.80f))
        quadraticTo(x(0.97f), y(0.88f), x(0.90f), y(0.88f))
        lineTo(x(0.10f), y(0.88f))
        quadraticTo(x(0.03f), y(0.88f), x(0.03f), y(0.80f))
        close()
    }
    drawPath(body, Brush.verticalGradient(listOf(Color(0xFFC5CAD1), Color(0xFF9198A2), Color(0xFF6B727C)), startY = y(0.40f), endY = y(0.88f)))
    drawPath(body, Brush.horizontalGradient(0f to Color(0x66000000), 0.15f to Color(0x00000000), 0.85f to Color(0x00000000), 1f to Color(0x66000000), startX = left, endX = left + wd))
    // Línea de la cajuela.
    drawLine(Color(0x44000000), Offset(x(0.10f), y(0.47f)), Offset(x(0.90f), y(0.47f)), strokeWidth = ht * 0.012f)
    // Calaveras encendidas (con resplandor) unidas por una franja fina.
    val red = Color(0xFFFF3B30)
    for ((a, b) in listOf(x(0.05f) to x(0.30f), x(0.70f) to x(0.95f))) {
        drawRoundRect(red.copy(alpha = 0.25f), Offset(a - wd * 0.02f, y(0.50f)), Size(b - a + wd * 0.04f, ht * 0.14f), CornerRadius(ht * 0.05f))
        drawRoundRect(red, Offset(a, y(0.53f)), Size(b - a, ht * 0.08f), CornerRadius(ht * 0.03f))
    }
    drawLine(red.copy(alpha = 0.7f), Offset(x(0.30f), y(0.57f)), Offset(x(0.70f), y(0.57f)), strokeWidth = ht * 0.015f)
    // Placa y defensa.
    drawRoundRect(Color(0xFFE6E8EB), Offset(x(0.38f), y(0.63f)), Size(wd * 0.24f, ht * 0.11f), CornerRadius(ht * 0.015f))
    drawRoundRect(Color(0xFF2A2E35), Offset(x(0.06f), y(0.80f)), Size(wd * 0.88f, ht * 0.06f), CornerRadius(ht * 0.03f))
}
