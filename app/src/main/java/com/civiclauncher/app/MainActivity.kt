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

    override fun onResume() {
        super.onResume()
        // Al despertar de la suspensión, actualiza la hora sin esperar al siguiente minuto.
        container.clock.refresh()
        // Si "Música BT" pausó al salir de ella, Civic reanuda la canción.
        container.media.onLauncherResumed()
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
