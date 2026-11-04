package com.civiclauncher.app.diag.perf

import android.app.Activity
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Choreographer
import android.view.FrameMetrics
import android.view.Window
import com.civiclauncher.app.diag.ReportSection
import com.civiclauncher.app.diag.Row
import com.civiclauncher.app.diag.Status
import kotlin.math.roundToInt

data class PerfResult(
    val label: String,
    val seconds: Double,
    /** Refrescos de pantalla que sí alcanzamos (medido con Choreographer, nunca pasa de [refreshHz]). */
    val displayFps: Double,
    val droppedFrames: Int,
    val droppedPct: Double,
    /** Congelones de más de 50 ms: los tirones que sí se notan a simple vista. */
    val stalls: Int,
    val worstGapMs: Double,
    /** Trabajo por cuadro CPU+GPU (FrameMetrics). Puede pasar de 16 ms sin perder cuadros, por el trabajo en paralelo. */
    val workP50Ms: Double,
    val workP90Ms: Double,
    val workP99Ms: Double,
    val refreshHz: Float,
    val aot: Boolean,
) {
    val verdict: Status
        get() = when {
            displayFps >= refreshHz * 0.9 && droppedPct < 5 && stalls <= 3 -> Status.OK
            displayFps >= refreshHz * 0.75 && stalls <= 12 -> Status.WARN
            else -> Status.BAD
        }

    fun toSection(): ReportSection = ReportSection(
        "Prueba de fluidez: $label",
        listOf(
            Row(
                "Resultado",
                when (verdict) { Status.OK -> "Fluido"; Status.WARN -> "Aceptable, con tirones"; else -> "Se traba" },
                verdict,
            ),
            Row("Cuadros por segundo en pantalla", "%.1f de %.0f".format(displayFps, refreshHz), verdict),
            Row("Cuadros perdidos", "$droppedFrames (%.1f %%)".format(droppedPct), if (droppedPct < 5) Status.OK else Status.WARN),
            Row("Tirones notables (> 50 ms)", "$stalls · peor: %.0f ms".format(worstGapMs), if (stalls <= 3) Status.OK else Status.WARN),
            Row("Trabajo por cuadro p50 / p90 / p99", "%.1f / %.1f / %.1f ms".format(workP50Ms, workP90Ms, workP99Ms)),
            Row("Duración medida", "%.1f s (después de 4 s de calentamiento)".format(seconds)),
            Row("Compilación de la app", if (aot) "AOT (precompilada)" else "Normal (JIT)"),
        ),
    )
}

/**
 * Mide la fluidez con dos fuentes:
 *  - Choreographer: un aviso por cada refresco de pantalla. Si el hilo principal se atrasa, hay huecos
 *    = cuadros perdidos. Esta es la medida real de lo que ves.
 *  - FrameMetrics (Android 7+): cuánto trabajo costó cada cuadro, incluida la GPU. Solo informativo:
 *    esta ROM desactiva la "contrapresión" de SurfaceFlinger, así que la app puede generar más
 *    cuadros de los que la pantalla muestra (por eso la v0.0.1 marcó 68 de 60).
 */
class FrameMeter(private val activity: Activity, private val refreshHz: Float, private val aot: Boolean) {
    private val workNs = ArrayList<Long>(4096)
    private val gapsNs = ArrayList<Long>(4096)
    private var thread: HandlerThread? = null
    private var listener: Window.OnFrameMetricsAvailableListener? = null
    @Volatile private var running = false
    private var lastVsync = 0L
    private var startMs = 0L

    private val choreo = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            if (lastVsync != 0L) gapsNs.add(frameTimeNanos - lastVsync)
            lastVsync = frameTimeNanos
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun start() {
        if (running) return
        workNs.clear()
        gapsNs.clear()
        lastVsync = 0L
        running = true
        startMs = SystemClock.elapsedRealtime()
        val ht = HandlerThread("frame-metrics").also { it.start() }
        thread = ht
        val l = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            if (running) synchronized(workNs) { workNs.add(metrics.getMetric(FrameMetrics.TOTAL_DURATION)) }
        }
        listener = l
        runCatching { activity.window.addOnFrameMetricsAvailableListener(l, Handler(ht.looper)) }
        Choreographer.getInstance().postFrameCallback(choreo)
    }

    fun stop(label: String): PerfResult {
        running = false
        Choreographer.getInstance().removeFrameCallback(choreo)
        listener?.let { runCatching { activity.window.removeOnFrameMetricsAvailableListener(it) } }
        listener = null
        thread?.quitSafely()
        thread = null

        val seconds = (SystemClock.elapsedRealtime() - startMs) / 1000.0
        val budgetNs = 1_000_000_000.0 / refreshHz
        val gaps = gapsNs.toList()
        val work = synchronized(workNs) { workNs.sorted() }
        // Un hueco de 1.5 refrescos o más cuenta como cuadro perdido.
        val dropped = gaps.sumOf { g -> ((g / budgetNs).roundToInt() - 1).coerceAtLeast(0) }
        val shown = gaps.size
        val stalls = gaps.count { it > 50_000_000L }
        fun pct(p: Double): Double = if (work.isEmpty()) 0.0 else work[((work.size - 1) * p).roundToInt()] / 1_000_000.0
        return PerfResult(
            label = label,
            seconds = seconds,
            displayFps = if (seconds > 0) (shown / seconds).coerceAtMost(refreshHz.toDouble()) else 0.0,
            droppedFrames = dropped,
            droppedPct = if (shown + dropped > 0) dropped * 100.0 / (shown + dropped) else 0.0,
            stalls = stalls,
            worstGapMs = (gaps.maxOrNull() ?: 0L) / 1_000_000.0,
            workP50Ms = pct(0.5),
            workP90Ms = pct(0.9),
            workP99Ms = pct(0.99),
            refreshHz = refreshHz,
            aot = aot,
        )
    }

    fun cancel() {
        if (running) stop("cancelada")
    }
}
