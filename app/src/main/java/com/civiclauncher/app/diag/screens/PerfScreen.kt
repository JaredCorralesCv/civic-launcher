package com.civiclauncher.app.diag.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.rememberCoroutineScope
import com.civiclauncher.app.container
import com.civiclauncher.app.diag.ResultKeys
import com.civiclauncher.app.diag.Row as InfoRowData
import com.civiclauncher.app.diag.util.Shell
import com.civiclauncher.app.ui.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.civiclauncher.app.diag.collect.SystemCollector
import com.civiclauncher.app.diag.perf.FrameMeter
import com.civiclauncher.app.diag.perf.PerfResult
import com.civiclauncher.app.ui.ActionButton
import com.civiclauncher.app.ui.ButtonKind
import com.civiclauncher.app.ui.CivicColors
import com.civiclauncher.app.ui.InfoCard
import com.civiclauncher.app.ui.SectionScaffold
import com.civiclauncher.app.ui.findActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

enum class PerfMode(val label: String, val key: String, val heavy: Boolean) {
    LIGHT("Ligera (como será el launcher)", ResultKeys.PERF_LIGHT, false),
    HEAVY("Pesada (sombras y transparencias)", ResultKeys.PERF_HEAVY, true),
}

private const val TEST_MS = 12_000L
private const val WARMUP_MS = 4_000L

/** Los resultados con la app precompilada (AOT) se guardan aparte para poder compararlos. */
fun perfKey(baseKey: String, aot: Boolean) = if (aot) "${baseKey}_aot" else baseKey

@Composable
fun PerfScreen() {
    val ctx = LocalContext.current
    val c = ctx.container
    val store = c.diagStore
    val scope = rememberCoroutineScope()
    val results by store.results.collectAsStateWithLifecycle()
    var running by remember { mutableStateOf<PerfMode?>(null) }
    var aot by remember { mutableStateOf(c.prefs.aotCompiled) }
    var busy by remember { mutableStateOf(false) }

    val mode = running
    if (mode != null) {
        PerfRun(mode) { r ->
            store.put(perfKey(mode.key, r.aot), r.toSection())
            running = null
        }
        return
    }

    fun compile(toAot: Boolean) {
        busy = true
        scope.launch {
            val cmd = if (toAot) "cmd package compile -m speed -f ${ctx.packageName}" else "cmd package compile --reset ${ctx.packageName}"
            val r = withContext(Dispatchers.IO) { Shell.su(cmd, 120_000) }
            busy = false
            if (r.ok) {
                c.prefs.aotCompiled = toAot
                aot = toAot
                ctx.toast("Listo. Cierra la app por completo y ábrela otra vez antes de repetir las pruebas.")
            } else {
                ctx.toast("Falló: ${r.summary()} ${r.stderr.take(120)}")
            }
        }
    }

    SectionScaffold(
        title = "Prueba de fluidez",
        subtitle = "Mide si Compose corre fluido en tu chip. Cada prueba calienta 4 s y mide 12 s; no toques la pantalla.",
        actions = {
            PerfMode.entries.forEach { m ->
                ActionButton("Probar: ${m.label}", { running = m }, enabled = !busy, kind = if (m.heavy) ButtonKind.Secondary else ButtonKind.Primary)
            }
            if (c.prefs.rootOk) {
                if (!aot) {
                    ActionButton(if (busy) "Compilando… (hasta 2 min)" else "Precompilar app (AOT)", { compile(true) }, enabled = !busy, kind = ButtonKind.Secondary)
                } else {
                    ActionButton("Quitar precompilación", { compile(false) }, enabled = !busy, kind = ButtonKind.Secondary)
                }
            }
        },
    ) {
        item {
            InfoCard(
                rows = listOf(
                    InfoRowData("Compilación actual", if (aot) "AOT (precompilada)" else "Normal (JIT)"),
                ),
                notes = listOf(
                    "Tu pantalla instala las apps sin precompilarlas (modo \"quicken\"), así que el código arranca lento hasta que Android lo optimiza.",
                    "Haz las dos pruebas en modo Normal, luego precompila y repítelas. La diferencia nos dice cuánto ayudarán los Baseline Profiles.",
                    if (c.prefs.rootOk) "Tienes root: usa el botón \"Precompilar app\"."
                    else "Sin root, precompila desde la PC con: adb shell cmd package compile -m speed -f ${ctx.packageName}",
                ),
            )
        }
        PerfMode.entries.forEach { m ->
            listOf(false, true).forEach { compiled ->
                val s = results[perfKey(m.key, compiled)]
                val title = "${m.label} · ${if (compiled) "AOT" else "normal"}"
                item {
                    if (s == null) {
                        InfoCard(title = title, rows = emptyList(), notes = listOf("Sin resultados todavía."))
                    } else {
                        InfoCard(title = title, rows = s.rows)
                    }
                }
            }
        }
        item {
            InfoCard(
                title = "Qué significa",
                rows = emptyList(),
                notes = listOf(
                    "Fluido: el launcher se sentirá suave.",
                    "Aceptable: reduciremos animaciones en algunas partes.",
                    "Se traba: hay que replantear la tecnología de la interfaz antes de la Fase 1.",
                    "Si la pesada sale mucho peor que la ligera, confirma que hay que evitar sombras y transparencias.",
                ),
            )
        }
    }
}

@Composable
private fun PerfRun(mode: PerfMode, onDone: (PerfResult) -> Unit) {
    val ctx = LocalContext.current
    val activity = remember { ctx.findActivity() }
    val refresh = remember { SystemCollector.screen(ctx).refreshHz.takeIf { it in 20f..240f } ?: 60f }
    val aot = remember { ctx.container.prefs.aotCompiled }
    val meter = remember { activity?.let { FrameMeter(it, refresh, aot) } }
    val grid = rememberLazyGridState()
    var secondsLeft by remember { mutableIntStateOf(((TEST_MS + WARMUP_MS) / 1000).toInt()) }

    DisposableEffect(Unit) { onDispose { meter?.cancel() } }

    LaunchedEffect(Unit) {
        val scroller = launch {
            while (isActive) {
                grid.animateScrollBy(3_000f, tween(3_000, easing = LinearEasing))
                grid.animateScrollBy(-3_000f, tween(3_000, easing = LinearEasing))
            }
        }
        val countdown = launch {
            while (isActive && secondsLeft > 0) {
                delay(1_000)
                secondsLeft--
            }
        }
        // Calentamiento: la primera vez el código corre sin optimizar y sesgaría la medición.
        delay(WARMUP_MS)
        meter?.start()
        delay(TEST_MS)
        scroller.cancel()
        countdown.cancel()
        val result = meter?.stop(mode.label)
            ?: PerfResult(mode.label, 0.0, 0.0, 0, 0.0, 0, 0.0, 0.0, 0.0, 0.0, refresh, aot)
        onDone(result)
    }

    Row(Modifier.fillMaxSize().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(Modifier.width(280.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Prueba ${if (mode.heavy) "pesada" else "ligera"}", style = MaterialTheme.typography.titleLarge)
            Text("$secondsLeft s", color = CivicColors.TextDim)
            Spacer(Modifier.height(16.dp))
            Gauge(heavy = mode.heavy, modifier = Modifier.size(240.dp))
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(120.dp),
            state = grid,
            userScrollEnabled = false,
            contentPadding = PaddingValues(4.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.weight(1f).fillMaxHeight(),
        ) {
            items(240) { i -> Tile(i, mode.heavy) }
        }
    }
}

private val TileColors = listOf(
    Color(0xFF3D8BFF), Color(0xFF34C759), Color(0xFFFF9F0A), Color(0xFFFF453A),
    Color(0xFFBF5AF2), Color(0xFF64D2FF), Color(0xFFFFD60A), Color(0xFF30D158),
)

@Composable
private fun Tile(i: Int, heavy: Boolean) {
    val shape = RoundedCornerShape(24.dp)
    val color = TileColors[i % TileColors.size]
    val base = if (heavy) {
        Modifier.shadow(12.dp, shape).graphicsLayer { alpha = 0.96f }
    } else {
        Modifier
    }
    Column(base.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).background(color, shape)) {
            if (heavy) {
                // Tres capas semitransparentes encimadas: justo lo que evitaremos en el launcher.
                Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.10f), shape))
                Box(Modifier.fillMaxSize().padding(8.dp).background(Color.Black.copy(alpha = 0.15f), shape))
                Box(Modifier.fillMaxSize().padding(16.dp).background(Color.White.copy(alpha = 0.10f), shape))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text("App ${i + 1}", fontSize = 15.sp, color = CivicColors.Text)
    }
}

/**
 * Medidor dibujado con Canvas. El valor animado solo se lee en la fase de dibujo,
 * así no se recompone nada en cada cuadro: es la técnica que usaremos en los medidores OBD.
 */
@Composable
private fun Gauge(heavy: Boolean, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "gauge")
    val progress = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_800, easing = LinearEasing), RepeatMode.Reverse),
        label = "progress",
    )
    val base = if (heavy) modifier.shadow(20.dp, RoundedCornerShape(120.dp)) else modifier
    Box(
        base.drawWithCache {
            val strokeW = 18.dp.toPx()
            val stroke = Stroke(width = strokeW, cap = StrokeCap.Round)
            val inset = strokeW / 2 + 4.dp.toPx()
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
            val topLeft = Offset(inset, inset)
            val center = Offset(size.width / 2, size.height / 2)
            val needleLen = size.minDimension / 2 - strokeW * 2
            onDrawBehind {
                val p = progress.value
                drawArc(CivicColors.Surface2, 150f, 240f, false, topLeft, arcSize, style = stroke)
                drawArc(CivicColors.Accent, 150f, 240f * p, false, topLeft, arcSize, style = stroke)
                val angle = Math.toRadians((150.0 + 240.0 * p))
                drawLine(
                    CivicColors.Text,
                    center,
                    Offset(center.x + (needleLen * cos(angle)).toFloat(), center.y + (needleLen * sin(angle)).toFloat()),
                    strokeWidth = 5.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                drawCircle(CivicColors.Text, 8.dp.toPx(), center)
            }
        },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Text("RPM", fontWeight = FontWeight.SemiBold, color = CivicColors.TextDim, modifier = Modifier.padding(bottom = 24.dp))
    }
}
