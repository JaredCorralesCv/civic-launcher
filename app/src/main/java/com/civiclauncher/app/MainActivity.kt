package com.civiclauncher.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.civiclauncher.app.launcher.ui.LauncherRoot
import kotlinx.coroutines.flow.MutableSharedFlow

class MainActivity : ComponentActivity() {
    /** Se emite cuando presionas el botón de inicio estando ya en Civic. */
    private val homePresses = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemBars()
        keepSystemBarsHidden()
        setContent { LauncherRoot(homePresses) }
        // Compose ya pinta toda la pantalla: el fondo de la ventana solo era otra capa completa por cuadro.
        window.decorView.post { window.setBackgroundDrawable(null) }
    }

    /**
     * El teclado y algunos avisos del sistema vuelven a mostrar las barras y no las quitan solos.
     * Si aparecen, las escondemos otra vez 2 s después (tiempo de sobra para usarlas si las buscabas).
     */
    @Suppress("DEPRECATION")
    private fun keepSystemBarsHidden() {
        val decor = window.decorView
        decor.setOnSystemUiVisibilityChangeListener { flags ->
            if (flags and android.view.View.SYSTEM_UI_FLAG_FULLSCREEN == 0) {
                decor.removeCallbacks(rehide)
                decor.postDelayed(rehide, 2_000)
            }
        }
    }

    private val rehide = Runnable { hideSystemBars() }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Volver desde la radio (u otra app que Civic abrió de paso) no es "presionar inicio":
        // se queda en la pantalla donde estabas.
        if (intent.hasCategory(Intent.CATEGORY_HOME) && !intent.getBooleanExtra(EXTRA_STAY, false)) homePresses.tryEmit(Unit)
    }

    private val frameHandler by lazy { android.os.Handler(mainLooper) }
    private val slowFrames by lazy { com.civiclauncher.app.launcher.SlowFrameDetector(android.os.SystemClock.elapsedRealtime()) }

    // Solo informa de cuadros que ya se dibujaron: medir no provoca ninguno.
    private val frameListener = android.view.Window.OnFrameMetricsAvailableListener { _, m, _ ->
        val low = com.civiclauncher.app.launcher.LowPower
        if (!low.watching || m.getMetric(android.view.FrameMetrics.FIRST_DRAW_FRAME) == 1L) return@OnFrameMetricsAvailableListener
        val nanos = m.getMetric(android.view.FrameMetrics.ANIMATION_DURATION) + m.getMetric(android.view.FrameMetrics.LAYOUT_MEASURE_DURATION) +
            m.getMetric(android.view.FrameMetrics.DRAW_DURATION) + m.getMetric(android.view.FrameMetrics.SYNC_DURATION) +
            m.getMetric(android.view.FrameMetrics.COMMAND_ISSUE_DURATION) + m.getMetric(android.view.FrameMetrics.SWAP_BUFFERS_DURATION)
        if (slowFrames.onFrame(nanos / 1_000_000f, android.os.SystemClock.elapsedRealtime())) low.onSlowDetected()
    }

    override fun onResume() {
        super.onResume()
        // Al despertar de la suspensión, actualiza la hora sin esperar al siguiente minuto.
        container.clock.refresh()
        // Vuelve a revisar qué suena (y si "Música BT" pausó al salir de ella, reanuda la canción).
        container.media.onLauncherResumed()
        container.obd.setForeground(true)
        window.addOnFrameMetricsAvailableListener(frameListener, frameHandler)
    }

    override fun onPause() {
        super.onPause()
        container.media.onLauncherPaused()
        container.obd.setForeground(false)
        runCatching { window.removeOnFrameMetricsAvailableListener(frameListener) }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    /** Pantalla completa: en el carro no queremos barra de estado ni de navegación. */
    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    companion object {
        /** En un intent de inicio: regresar a Civic sin ir a la pantalla de Inicio. */
        const val EXTRA_STAY = "com.civiclauncher.app.STAY"
    }
}
