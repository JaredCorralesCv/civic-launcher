package com.civiclauncher.app.launcher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.civiclauncher.app.container
import com.civiclauncher.app.launcher.nav.NavBanner
import com.civiclauncher.app.launcher.nav.NavState
import com.civiclauncher.app.ui.design.Civic
import com.civiclauncher.app.ui.design.bevelBorder
import com.civiclauncher.obd.EngineState
import com.civiclauncher.obd.Pids
import kotlin.math.min

// =================================================================== Datos del motor

/** Lo que muestran las pantallas completas además de velocidad y RPM (null = sin dato). */
@Immutable
data class EngineValues(
    val throttle: Float?, val load: Float?, val coolant: Float?, val intake: Float?,
    val ambient: Float?, val oil: Float?, val maf: Float?, val battery: Float?,
)

private val SAMPLE_ENGINE = EngineValues(throttle = 38f, load = 42f, coolant = 88f, intake = 31f, ambient = 24f, oil = null, maf = 12.4f, battery = 14.1f)

/**
 * Datos del motor para las pantallas completas. Igual que [rememberDriveValues]: congelados si Inicio
 * no se ve, y redondeados para que solo se redibuje cuando cambia lo que se ve. Solo se leen mientras
 * una de estas pantallas está puesta.
 */
@Composable
private fun rememberEngineValues(): EngineValues {
    val c = LocalContext.current.container
    val active = LocalScreenActive.current
    val obdState by c.obd.state.collectAsStateWithLifecycle()
    val data = c.obd.data.collectAsStateWithLifecycle()
    val connected = obdState is EngineState.Connected
    val last = remember { arrayOf(EngineValues(null, null, null, null, null, null, null, null)) }
    val values by remember(connected, active) {
        derivedStateOf {
            if (!active) return@derivedStateOf last[0]
            val d = data.value
            fun pid(p: com.civiclauncher.obd.PidSpec, step: Double = 1.0) =
                if (connected) d[p].fresh()?.value?.let { (Math.round(it / step) * step).toFloat() } else null
            EngineValues(
                throttle = pid(Pids.THROTTLE), load = pid(Pids.ENGINE_LOAD), coolant = pid(Pids.COOLANT_TEMP),
                intake = pid(Pids.INTAKE_AIR_TEMP), ambient = pid(Pids.AMBIENT_TEMP), oil = pid(Pids.OIL_TEMP),
                maf = pid(Pids.MAF, 0.1),
                battery = d.battery.fresh(30_000)?.value?.let { (Math.round(it * 10) / 10.0).toFloat() },
            ).also { last[0] = it }
        }
    }
    return values
}

private fun fmt(v: Float?, decimals: Int = 0) = v?.let { if (decimals == 0) "%.0f".format(it) else "%.${decimals}f".format(it) } ?: "--"

/** Fondo de las pantallas completas: casi negro, con un brillo suave arriba. */
@Composable
private fun FullSurface(modifier: Modifier, onClick: (() -> Unit)?, bare: Boolean = false, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    val shape = RoundedCornerShape(Civic.radii.xl)
    Box(
        modifier
            .clip(shape)
            // En Inicio, sin tarjeta: los medidores flotan sobre el fondo de pantalla (como un tablero).
            // En la miniatura del menú sí lleva su tarjeta, para que se distinga.
            .let { if (bare) it else it.background(Brush.verticalGradient(listOf(Color(0xFF15181D), Color(0xFF0A0B0E)))).bevelBorder(shape) }
            .let { if (onClick != null) it.clickable(role = Role.Button, onClick = onClick) else it },
    ) { content() }
}

/** La siguiente vuelta arriba al centro, si vas navegando (para no perderla en pantalla completa). */
@Composable
private fun NavOverlay(live: Boolean, modifier: Modifier) {
    if (!live) return
    val nav by LocalContext.current.container.nav.state.collectAsStateWithLifecycle()
    val n = nav as? NavState.Navigating ?: return
    NavBanner(n.progress, n.rerouting, modifier, compact = true)
}

// =================================================================== Medidor premium

/**
 * Medidor redondo elegante: carátula oscura con profundidad (se lee sobre cualquier fondo), bisel
 * metálico fino, escala con muchas marcas finas y números, la zona roja marcada, un arco de luz que
 * se llena con degradado y resplandor, y un indicador luminoso sobre la escala (sin aguja que tape
 * el número del centro).
 */
@Composable
private fun PremiumDial(
    value: Float?,
    min: Float,
    max: Float,
    labels: List<String>,
    redFrom: Float?,
    text: String,
    unit: String,
    stops: Array<Pair<Float, Color>>,
    live: Boolean,
    modifier: Modifier,
    title: String? = null,
) {
    val f by gaugeFraction(value?.let { it - min }, max - min, live)
    val paint = rememberLabelPaint()
    val red = redFrom?.let { ((it - min) / (max - min)).coerceIn(0f, 1f) }
    // El número se pone rojo por el valor (no por la animación: así no se recompone en cada cuadro).
    val hot = red != null && value != null && value >= redFrom!!
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        val dim = minOf(maxWidth, maxHeight)
        val showLabels = dim > 150.dp
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val r = minOf(size.width, size.height) / 2f - 2.dp.toPx()
            // Sin espacio todavía (al acomodarse la pantalla): no se dibuja. Un degradado de radio 0
            // cierra la app en Android 8.
            if (r < 8.dp.toPx()) return@Canvas
            val start = 135f
            val sweep = 270f
            // Carátula con profundidad y bisel metálico.
            drawCircle(Brush.radialGradient(0f to Color(0xF21C2028), 0.7f to Color(0xF20E1014), 1f to Color(0xF2060708), center = c, radius = r), r, c)
            drawCircle(
                Brush.sweepGradient(listOf(Color.White.copy(alpha = 0.30f), Color.White.copy(alpha = 0.04f), Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.03f), Color.White.copy(alpha = 0.30f)), c),
                r - 1.dp.toPx(), c, style = Stroke(1.5.dp.toPx()),
            )
            val arcR = r - r * 0.09f
            val arcW = (r * 0.035f).coerceAtLeast(3.dp.toPx())
            // Pista y zona roja.
            plainArc(c, arcR, start, sweep, Color.White.copy(alpha = 0.07f), arcW)
            if (red != null) plainArc(c, arcR, start + sweep * red, sweep * (1 - red), G_RED.copy(alpha = 0.30f), arcW, round = false)
            // Arco de luz con resplandor.
            gradientArc(c, arcR, start, sweep, sweep * f, arcW * 5f, stops, alpha = 0.10f)
            gradientArc(c, arcR, start, sweep, sweep * f, arcW * 2.6f, stops, alpha = 0.22f)
            gradientArc(c, arcR, start, sweep, sweep * f, arcW, stops)
            // Escala.
            val tickR = arcR - arcW * 2.2f
            val majors = labels.size - 1
            ticks(c, tickR, start, sweep, majors * 10,
                len = { i -> when { i % 10 == 0 -> r * 0.09f; i % 5 == 0 -> r * 0.06f; else -> r * 0.035f } },
                width = { i -> if (i % 10 == 0) (r * 0.014f).coerceAtLeast(1.5.dp.toPx()) else 1.dp.toPx() },
            ) { i, at ->
                val inRed = red != null && at >= red - 0.0001f
                val passed = at <= f
                when {
                    inRed -> G_RED.copy(alpha = if (i % 10 == 0) 0.95f else 0.6f)
                    i % 10 == 0 -> Color.White.copy(alpha = if (passed) 0.95f else 0.7f)
                    i % 5 == 0 -> Color.White.copy(alpha = if (passed) 0.7f else 0.4f)
                    else -> Color.White.copy(alpha = if (passed) 0.45f else 0.2f)
                }
            }
            if (showLabels) scaleLabels(paint, c, tickR - r * 0.19f, start, sweep, labels, Color.White.copy(alpha = 0.75f), r * 0.085f)
            // Indicador luminoso sobre la escala.
            val a = start + sweep * f
            val tip = if (red != null && f >= red) G_RED else colorAt(stops, f)
            drawLine(tip.copy(alpha = 0.30f), polar(c, tickR - r * 0.11f, a), polar(c, arcR + arcW, a), strokeWidth = r * 0.05f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(Color.White, polar(c, tickR - r * 0.11f, a), polar(c, arcR + arcW * 0.5f, a), strokeWidth = (r * 0.016f).coerceAtLeast(2.dp.toPx()), cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawCircle(tip.copy(alpha = 0.35f), arcW * 2.2f, polar(c, arcR, a))
            drawCircle(Color.White, arcW * 0.9f, polar(c, arcR, a))
        }
        val density = androidx.compose.ui.platform.LocalDensity.current
        // El número se achica si es largo (2,400): no choca con los números de la escala.
        val big = with(density) { (dim * if (text.length > 3) 0.17f else 0.21f).coerceAtLeast(4.dp).toSp() }
        val small = with(density) { (dim * 0.055f).coerceAtLeast(4.dp).toSp() }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (title != null && !showLabels) Text(title, style = Civic.type.caption.copy(fontSize = small), color = Color.White.copy(alpha = 0.5f))
            Text(text, style = big(10, FontWeight.Medium).copy(fontSize = big, lineHeight = big), color = if (hot) G_RED else Color.White, maxLines = 1)
            Text(unit, style = Civic.type.caption.copy(fontSize = small, letterSpacing = 0.12.em), color = Color.White.copy(alpha = 0.55f))
        }
    }
}

/** El color del degradado en el punto [f] (para que el indicador tenga el color del arco ahí). */
private fun colorAt(stops: Array<Pair<Float, Color>>, f: Float): Color {
    val i = stops.indexOfFirst { it.first >= f }
    if (i <= 0) return stops.first().second
    val (a, ca) = stops[i - 1]
    val (b, cb) = stops[i]
    return lerp(ca, cb, if (b > a) (f - a) / (b - a) else 0f)
}

private val BLUE_STOPS = arrayOf(0f to G_BLUE, 1f to G_SKY)
private val RPM_STOPS = arrayOf(0f to G_BLUE, 0.6f to G_SKY, 0.78f to G_AMBER, 0.85f to G_RED, 1f to G_RED)
private val WARM_STOPS = arrayOf(0f to G_AMBER, 1f to G_ORANGE)
private val GREEN_STOPS = arrayOf(0f to Color(0xFF1FA971), 1f to G_GREEN)

// =================================================================== Tablero

/** Tablero: tacómetro y velocímetro grandes a los lados, y al centro cuatro medidores del motor. */
@Composable
internal fun ClusterScreen(d: DriveValues, live: Boolean, modifier: Modifier, onClick: (() -> Unit)?) {
    val e = if (live) rememberEngineValues() else SAMPLE_ENGINE
    FullSurface(modifier, onClick, bare = live) {
        Row(Modifier.fillMaxSize().padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            PremiumDial(d.rpm, 0f, 8000f, (0..8).map { "$it" }, 6800f, rpmText(d.rpm), "RPM", RPM_STOPS, live, Modifier.weight(1f))
            Column(Modifier.weight(0.78f).fillMaxHeight().padding(vertical = 26.dp, horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    SmallDial("Batería", e.battery, 10f, 16f, fmt(e.battery, 1), "V", null, GREEN_STOPS, live)
                    SmallDial("Motor", e.coolant, 40f, 120f, fmt(e.coolant), "°C", 105f, GREEN_STOPS, live)
                }
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    SmallDial("Acelerador", e.throttle, 0f, 100f, fmt(e.throttle), "%", null, WARM_STOPS, live)
                    SmallDial("Carga", e.load, 0f, 100f, fmt(e.load), "%", null, WARM_STOPS, live)
                }
            }
            PremiumDial(d.speed, 0f, 220f, (0..11).map { "${it * 20}" }, null, speedText(d.speed), "KM/H", BLUE_STOPS, live, Modifier.weight(1f))
        }
        NavOverlay(live, Modifier.align(Alignment.TopCenter).padding(top = 8.dp))
    }
}

/** Medidor chico con su nombre arriba (para el centro del Tablero). */
@Composable
private fun RowScope.SmallDial(label: String, value: Float?, min: Float, max: Float, text: String, unit: String, redFrom: Float?, stops: Array<Pair<Float, Color>>, live: Boolean) {
    Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label.uppercase(), style = Civic.type.caption.copy(fontSize = 10.sp, letterSpacing = 0.12.em, fontWeight = FontWeight.SemiBold), color = Color.White.copy(alpha = 0.7f), maxLines = 1, softWrap = false)
        Spacer(Modifier.height(4.dp))
        PremiumDial(value, min, max, listOf("", ""), redFrom, text, unit, stops, live, Modifier.weight(1f))
    }
}

// =================================================================== Medidores del motor

/** Ocho medidores del motor en dos filas, cada uno con su nombre en una pastilla. */
@Composable
internal fun GaugesScreen(d: DriveValues, live: Boolean, modifier: Modifier, onClick: (() -> Unit)?) {
    val e = if (live) rememberEngineValues() else SAMPLE_ENGINE
    FullSurface(modifier, onClick, bare = live) {
        Column(Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                NamedDial("RPM", d.rpm, 0f, 8000f, (0..8).map { "$it" }, 6800f, rpmText(d.rpm), "rpm", RPM_STOPS, live)
                NamedDial("Velocidad", d.speed, 0f, 200f, listOf("0", "50", "100", "150", "200"), null, speedText(d.speed), "km/h", BLUE_STOPS, live)
                NamedDial("Acelerador", e.throttle, 0f, 100f, listOf("0", "25", "50", "75", "100"), null, fmt(e.throttle), "%", WARM_STOPS, live)
                NamedDial("Carga del motor", e.load, 0f, 100f, listOf("0", "25", "50", "75", "100"), null, fmt(e.load), "%", WARM_STOPS, live)
            }
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                NamedDial("Motor", e.coolant, 40f, 120f, listOf("40", "60", "80", "100", "120"), 105f, fmt(e.coolant), "°C", GREEN_STOPS, live)
                NamedDial("Admisión", e.intake, 0f, 80f, listOf("0", "20", "40", "60", "80"), 60f, fmt(e.intake), "°C", GREEN_STOPS, live)
                NamedDial("Ambiente", e.ambient, -10f, 50f, listOf("-10", "5", "20", "35", "50"), null, fmt(e.ambient), "°C", BLUE_STOPS, live)
                NamedDial("Flujo de aire", e.maf, 0f, 60f, listOf("0", "15", "30", "45", "60"), null, fmt(e.maf, 1), "g/s", arrayOf(0f to Color(0xFF7B6CFF), 1f to Color(0xFFB18CFF)), live)
            }
        }
    }
}

@Composable
private fun RowScope.NamedDial(label: String, value: Float?, min: Float, max: Float, labels: List<String>, redFrom: Float?, text: String, unit: String, stops: Array<Pair<Float, Color>>, live: Boolean) {
    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
        PremiumDial(value, min, max, labels, redFrom, text, unit, stops, live, Modifier.fillMaxHeight().padding(bottom = 10.dp))
        Text(
            label,
            style = Civic.type.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
            color = Color.White.copy(alpha = 0.85f),
            maxLines = 1,
            modifier = Modifier.align(Alignment.BottomCenter).clip(RoundedCornerShape(50)).background(Color(0xE6141820)).border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

// =================================================================== Desempeño (0 a 100)

private enum class AccelPhase { WAIT_STOP, READY, RUNNING, DONE }

/**
 * Cronómetro de 0 a 100 km/h: se arma al estar detenido, arranca al moverte y se detiene al llegar
 * a 100. Si frenas a medias o tarda más de 30 s, se cancela. Guarda el mejor tiempo.
 */
@Stable
private class Accel0to100(best: Long) {
    var phase by mutableStateOf(AccelPhase.WAIT_STOP)
    var startAt = 0L
    var doneAt = 0L
    var elapsed by mutableLongStateOf(0L)
    var result by mutableLongStateOf(0L)
    var best by mutableLongStateOf(best)
    var newRecord by mutableStateOf(false)

    /** Devuelve true si hay un récord nuevo que guardar. */
    fun update(speed: Float?, now: Long): Boolean {
        val v = speed ?: return false
        when (phase) {
            AccelPhase.WAIT_STOP, AccelPhase.DONE -> if (v <= 1f) phase = AccelPhase.READY
            AccelPhase.READY -> if (v > 1f) {
                phase = AccelPhase.RUNNING
                startAt = now
                newRecord = false
            }
            AccelPhase.RUNNING -> when {
                v >= 100f -> {
                    result = now - startAt
                    elapsed = result
                    doneAt = now
                    phase = AccelPhase.DONE
                    newRecord = best == 0L || result < best
                    if (newRecord) best = result
                    return newRecord
                }
                v <= 1f -> phase = AccelPhase.READY
                now - startAt > 30_000 -> phase = AccelPhase.WAIT_STOP
            }
        }
        return false
    }
}

private fun secs(ms: Long) = "%.2f s".format(ms / 1000f)

/**
 * Desempeño: la velocidad y las RPM en dos arcos grandes a los lados (como un tablero de carreras).
 * Al centro, normalmente, la marcha, las temperaturas y el acelerador. El 0 a 100 solo aparece
 * cuando arrancas desde parado (y se queda 20 s después de llegar, con tu récord).
 */
@Composable
internal fun PerformanceScreen(d: DriveValues, live: Boolean, modifier: Modifier, onClick: (() -> Unit)?) {
    val c = LocalContext.current.container
    val e = if (live) rememberEngineValues() else SAMPLE_ENGINE
    val t = remember { Accel0to100(if (live) c.launcherPrefs.best0to100Ms else 7_480L) }
    var showResult by remember { mutableStateOf(false) }
    // El cronómetro solo existe si lo activas (botón de arriba); apagado, ni siquiera vigila la velocidad.
    val timerOn by c.launcherPrefs.accelTimer.collectAsStateWithLifecycle()
    if (live && timerOn) {
        LaunchedEffect(d.speed) {
            if (t.update(d.speed, android.os.SystemClock.elapsedRealtime())) c.launcherPrefs.best0to100Ms = t.best
        }
        // El tiempo corre en pantalla solo mientras se está midiendo; el resultado se queda 20 s.
        LaunchedEffect(t.phase) {
            while (t.phase == AccelPhase.RUNNING) {
                t.elapsed = android.os.SystemClock.elapsedRealtime() - t.startAt
                kotlinx.coroutines.delay(50)
            }
            if (t.phase == AccelPhase.DONE) {
                showResult = true
                kotlinx.coroutines.delay(20_000)
                showResult = false
            }
        }
    }
    // Midiendo de verdad: ya pasó de 10 km/h (así no salta el cronómetro por arrancar en un semáforo).
    val measuring = live && timerOn && t.phase == AccelPhase.RUNNING && (d.speed ?: 0f) >= 10f
    val showAccel = measuring || (live && timerOn && showResult && t.phase != AccelPhase.RUNNING)
    val fs by gaugeFraction(d.speed, 220f, live)
    val fr by gaugeFraction(d.rpm, 8000f, live)
    val paint = rememberLabelPaint()
    FullSurface(modifier, onClick, bare = live) {
        Canvas(Modifier.fillMaxSize().padding(vertical = 6.dp, horizontal = 4.dp)) {
            val r = size.height / 2f
            if (r < 70.dp.toPx() || size.width < 4 * r) return@Canvas
            val cy = size.height / 2f
            fun side(cn: Offset, sign: Float, f: Float, labels: List<String>, red: Float?, stops: Array<Pair<Float, Color>>) {
                val start = 90f
                val sweep = 180f * sign
                // Sombra detrás del arco: se lee sobre cualquier fondo.
                drawCircle(Brush.radialGradient(0f to Color.Transparent, 0.72f to Color.Transparent, 0.86f to Color.Black.copy(alpha = 0.45f), 1f to Color.Transparent, center = cn, radius = r + 8.dp.toPx()), r + 8.dp.toPx(), cn)
                ticks(cn, r - 3.dp.toPx(), start, sweep, (labels.size - 1) * 5,
                    len = { if (it % 5 == 0) 14.dp.toPx() else 6.dp.toPx() },
                    width = { if (it % 5 == 0) 2.dp.toPx() else 1.dp.toPx() },
                ) { _, at ->
                    when {
                        red != null && at >= red -> G_RED.copy(alpha = 0.9f)
                        at <= f -> Color.White.copy(alpha = 0.9f)
                        else -> Color.White.copy(alpha = 0.35f)
                    }
                }
                scaleLabels(paint, cn, r - 34.dp.toPx(), start, sweep, labels, Color.White.copy(alpha = 0.8f), 15.dp.toPx())
                val band = r - 52.dp.toPx()
                val box = Offset(cn.x - band, cn.y - band)
                val bs = Size(2 * band, 2 * band)
                drawArc(Color.White.copy(alpha = 0.06f), start, sweep, false, box, bs, style = Stroke(5.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
                if (f > 0.003f) {
                    val col = if (red != null && f >= red) G_RED else lerp(stops.first().second, stops.last().second, f)
                    drawArc(col.copy(alpha = 0.12f), start, sweep * f, false, box, bs, style = Stroke(26.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
                    drawArc(col.copy(alpha = 0.25f), start, sweep * f, false, box, bs, style = Stroke(12.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
                    drawArc(col, start, sweep * f, false, box, bs, style = Stroke(5.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
                    val p = polar(cn, band, start + sweep * f)
                    drawCircle(col.copy(alpha = 0.4f), 11.dp.toPx(), p)
                    drawCircle(Color.White, 5.dp.toPx(), p)
                }
            }
            side(Offset(r, cy), 1f, fs, (0..11).map { "${it * 20}" }, null, BLUE_STOPS)
            side(Offset(size.width - r, cy), -1f, fr, (0..8).map { "$it" }, 6800f / 8000f, RPM_STOPS)
        }
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(0.28f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(speedText(d.speed), style = big(64, FontWeight.Medium), color = Color.White)
                Text("KM/H", style = Civic.type.label.copy(letterSpacing = 0.15.em), color = Color.White.copy(alpha = 0.55f))
            }
            Column(Modifier.weight(0.44f).fillMaxHeight().padding(vertical = 22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                if (live) {
                    TimerToggle(timerOn, t.best) {
                        c.launcherPrefs.setAccelTimer(!timerOn)
                        t.phase = AccelPhase.WAIT_STOP
                        showResult = false
                    }
                    Spacer(Modifier.height(10.dp))
                }
                if (showAccel) AccelPanel(t, d, measuring) else DrivePanel(d, e, live)
            }
            Column(Modifier.weight(0.28f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(rpmText(d.rpm), style = big(52, FontWeight.Medium), color = if ((d.rpm ?: 0f) >= 6800f) G_RED else Color.White)
                Text("RPM", style = Civic.type.label.copy(letterSpacing = 0.15.em), color = Color.White.copy(alpha = 0.55f))
            }
        }
    }
}

/** Botón chico para activar o quitar el cronómetro de 0 a 100 (con tu récord si lo hay). */
@Composable
private fun TimerToggle(on: Boolean, best: Long, onToggle: () -> Unit) {
    val color = if (on) G_GREEN else Color.White.copy(alpha = 0.6f)
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (on) G_GREEN.copy(alpha = 0.14f) else Color(0x99101317))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(50))
            .clickable(role = Role.Button, onClickLabel = if (on) "Quitar el cronómetro" else "Activar el cronómetro", onClick = onToggle)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(androidx.compose.foundation.shape.CircleShape).background(if (on) G_GREEN else Color.White.copy(alpha = 0.3f)))
        Spacer(Modifier.width(8.dp))
        Text(
            if (on) "Cronómetro 0–100 activo" + (if (best > 0) " · récord ${secs(best)}" else "") else "Cronómetro 0–100",
            style = Civic.type.caption.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
            color = color,
            maxLines = 1,
        )
    }
}

/** Centro de Desempeño de siempre: la marcha grande, temperaturas y el acelerador. */
@Composable
private fun DrivePanel(d: DriveValues, e: EngineValues, live: Boolean) {
    Text("MARCHA", style = Civic.type.caption.copy(fontSize = 11.sp, letterSpacing = 0.2.em, fontWeight = FontWeight.SemiBold), color = Color.White.copy(alpha = 0.55f))
    Text(d.gear?.toString() ?: "N", style = big(96, FontWeight.Light).copy(shadow = androidx.compose.ui.graphics.Shadow(G_SKY.copy(alpha = 0.6f), blurRadius = 24f)), color = Color.White)
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0x99101317)).border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(16.dp)).padding(vertical = 10.dp)) {
        TempCell("Motor", e.coolant, 105f)
        if (e.oil != null) TempCell("Aceite", e.oil, 130f) else TempCell("Admisión", e.intake, 60f)
    }
    Spacer(Modifier.height(14.dp))
    ThrottleArc(e.throttle, live, Modifier.fillMaxWidth(0.6f))
}

/** Centro de Desempeño al acelerar desde parado: el cronómetro de 0 a 100 y tu récord. */
@Composable
private fun AccelPanel(t: Accel0to100, d: DriveValues, measuring: Boolean) {
    val color = when {
        measuring -> G_AMBER
        t.newRecord -> G_GREEN
        else -> Color.White
    }
    Text("0 – 100 KM/H", style = Civic.type.caption.copy(fontSize = 12.sp, letterSpacing = 0.2.em, fontWeight = FontWeight.SemiBold), color = Color.White.copy(alpha = 0.7f))
    Text(secs(if (measuring) t.elapsed else t.result), style = big(64, FontWeight.Medium).copy(shadow = androidx.compose.ui.graphics.Shadow(color.copy(alpha = 0.6f), blurRadius = 24f)), color = color)
    Text(
        when {
            measuring -> "Midiendo…"
            t.newRecord -> "¡Nuevo récord!"
            else -> "Llegaste a 100"
        },
        style = Civic.type.label,
        color = color.copy(alpha = 0.85f),
    )
    Spacer(Modifier.height(14.dp))
    val f = if (measuring) ((d.speed ?: 0f) / 100f).coerceIn(0f, 1f) else 1f
    Canvas(Modifier.fillMaxWidth(0.85f).height(8.dp)) {
        val cr = CornerRadius(size.height / 2f)
        drawRoundRect(Color.White.copy(alpha = 0.08f), cornerRadius = cr)
        drawRoundRect(Brush.horizontalGradient(listOf(G_GREEN, G_LIME, G_AMBER)), size = Size(size.width * f, size.height), cornerRadius = cr)
    }
    Spacer(Modifier.height(12.dp))
    if (t.best > 0) Text("Récord: ${secs(t.best)}", style = Civic.type.label.copy(fontWeight = FontWeight.SemiBold), color = G_GREEN)
}

@Composable
private fun RowScope.TempCell(label: String, value: Float?, warnFrom: Float) {
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = Civic.type.caption.copy(fontSize = 12.sp), color = Color.White.copy(alpha = 0.6f))
        Text(
            value?.let { "%.0f °C".format(it) } ?: "--",
            style = big(24, FontWeight.Medium),
            color = when {
                value == null -> Color.White.copy(alpha = 0.4f)
                value >= warnFrom -> G_RED
                value < 60f -> G_SKY
                else -> G_GREEN
            },
        )
    }
}

/** Acelerador: medio arco con resplandor, de verde a naranja, con el porcentaje dentro. */
@Composable
private fun ThrottleArc(throttle: Float?, live: Boolean, modifier: Modifier) {
    val f by gaugeFraction(throttle, 100f, live)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("ACELERADOR", style = Civic.type.caption.copy(fontSize = 11.sp, letterSpacing = 0.2.em, fontWeight = FontWeight.SemiBold), color = Color.White.copy(alpha = 0.6f))
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().aspectRatio(1.85f), contentAlignment = Alignment.BottomCenter) {
            Canvas(Modifier.fillMaxSize()) {
                val r = size.width / 2f - 10.dp.toPx()
                if (r < 8.dp.toPx()) return@Canvas
                val c = Offset(size.width / 2f, size.height)
                plainArc(c, r, 180f, 180f, Color.White.copy(alpha = 0.08f), 6.dp.toPx())
                val stops = arrayOf(0f to G_GREEN, 0.6f to G_AMBER, 1f to G_ORANGE)
                gradientArc(c, r, 180f, 180f, 180f * f, 18.dp.toPx(), stops, alpha = 0.14f)
                gradientArc(c, r, 180f, 180f, 180f * f, 6.dp.toPx(), stops)
                if (f > 0.005f) {
                    val p = polar(c, r, 180f + 180f * f)
                    drawCircle(G_ORANGE.copy(alpha = 0.4f), 10.dp.toPx(), p)
                    drawCircle(Color.White, 4.5.dp.toPx(), p)
                }
            }
            Text(throttle?.let { "%.0f%%".format(it) } ?: "--", style = big(26, FontWeight.SemiBold), color = Color.White)
        }
    }
}
