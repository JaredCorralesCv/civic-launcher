package com.civiclauncher.app.launcher

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AnimationMode(val label: String, val description: String) {
    AUTO("Automáticas", "Completas, y se reducen solas si la pantalla no alcanza a dibujarlas. Toca para cambiar."),
    FULL("Completas", "Camino en movimiento, resplandores y agujas suaves, siempre. Toca para cambiar."),
    REDUCED("Reducidas", "Sin camino en movimiento ni resplandores, y agujas directas. Toca para cambiar."),
}

/** ¿Se dibuja con todo o en modo ligero? Lo leen los medidores y la animación del camino. */
object LowPower {
    /** Para leerlo al dibujar sin observar nada: basta con que el siguiente cuadro lo note. */
    @Volatile var reduced = false
        private set
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private var mode = AnimationMode.AUTO
    private var detected = false

    fun setMode(value: AnimationMode) {
        mode = value
        apply()
    }

    val watching: Boolean get() = mode == AnimationMode.AUTO && !detected

    /** La pantalla no da abasto: bajar lo que resta de esta sesión. */
    fun onSlowDetected() {
        detected = true
        android.util.Log.i("CivicPerf", "cuadros lentos sostenidos: modo ligero")
        apply()
    }

    private fun apply() {
        val on = mode == AnimationMode.REDUCED || (mode == AnimationMode.AUTO && detected)
        reduced = on
        _active.value = on
    }
}

/**
 * Decide si los cuadros van lentos de forma sostenida: en dos ventanas seguidas de 5 s, con suficientes
 * cuadros, la mayoría costó más de lo que permite ~25 por segundo. Se ignora el arranque, que siempre es lento.
 */
internal class SlowFrameDetector(startMs: Long) {
    private val ignoreUntil = startMs + GRACE_MS
    private var windowStart = 0L
    private var frames = 0
    private var slow = 0
    private var slowWindows = 0

    /** true cuando se cumple la condición. */
    fun onFrame(costMs: Float, nowMs: Long): Boolean {
        if (nowMs < ignoreUntil) return false
        if (windowStart == 0L) windowStart = nowMs
        if (nowMs - windowStart >= WINDOW_MS) {
            val isSlow = frames >= MIN_FRAMES && slow >= frames * SLOW_SHARE
            slowWindows = if (isSlow) slowWindows + 1 else 0
            windowStart = nowMs
            frames = 0
            slow = 0
            if (slowWindows >= WINDOWS_NEEDED) return true
        }
        frames++
        if (costMs > SLOW_FRAME_MS) slow++
        return false
    }

    private companion object {
        const val GRACE_MS = 30_000L
        const val WINDOW_MS = 5_000L
        const val MIN_FRAMES = 20
        const val SLOW_SHARE = 0.6f
        const val SLOW_FRAME_MS = 40f
        const val WINDOWS_NEEDED = 2
    }
}
