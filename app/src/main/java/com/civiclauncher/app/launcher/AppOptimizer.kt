package com.civiclauncher.app.launcher

import android.content.Context
import com.civiclauncher.app.BuildConfig
import com.civiclauncher.app.diag.util.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Compila Civic por completo a código nativo (AOT, filtro "speed").
 *
 * Por qué: en Android 8.1 una app recién instalada o actualizada corre casi toda "interpretada" y
 * con JIT; el sistema solo la optimiza en mantenimiento nocturno (pantalla apagada, cargando y sin
 * uso), algo que una pantalla de carro casi nunca cumple. Compose es mucho código: interpretado en
 * un Cortex-A7 a 1.1 GHz se siente pesado en todo (tocar, cambiar de pantalla, animaciones).
 *
 * Cómo: `cmd package compile -m speed -f` con el root que trae la pantalla (/system/xbin/su). Se
 * hace una sola vez por versión, en segundo plano y unos segundos después de arrancar, para no
 * competir con el inicio. El código optimizado se usa desde el siguiente arranque de Civic.
 * Sin root no hace nada (se puede hacer por adb con el mismo comando).
 */
class AppOptimizer(private val context: Context, private val scope: CoroutineScope) {
    private val sp = context.getSharedPreferences("optimizer", Context.MODE_PRIVATE)

    sealed interface Status {
        data object Idle : Status
        data object Running : Status
        data class Done(val version: Int) : Status
        data class Failed(val reason: String) : Status
    }

    private val _status = MutableStateFlow(initial())
    val status: StateFlow<Status> = _status.asStateFlow()

    private fun initial(): Status = when {
        sp.getInt("compiled_version", -1) == BuildConfig.VERSION_CODE -> Status.Done(BuildConfig.VERSION_CODE)
        sp.getString("last_error", null) != null && sp.getInt("failed_version", -1) == BuildConfig.VERSION_CODE ->
            Status.Failed(sp.getString("last_error", null).orEmpty())
        else -> Status.Idle
    }

    /** Al arrancar: si esta versión no se ha compilado, hacerlo en segundo plano. */
    fun runIfNeeded() {
        if (_status.value is Status.Done || _status.value is Status.Failed) return
        scope.launch {
            delay(START_DELAY_MS)
            compile()
        }
    }

    /** Botón de Ajustes: volver a intentarlo aunque ya se haya hecho. */
    fun compileNow() {
        scope.launch { compile() }
    }

    private suspend fun compile() {
        if (_status.value is Status.Running) return
        _status.value = Status.Running
        val result = kotlinx.coroutines.withContext(Dispatchers.IO) {
            // Prioridad baja: que no le quite procesador a la música ni al mapa mientras compila.
            Shell.su("nice -n 10 cmd package compile -m speed -f ${context.packageName}", timeoutMs = 300_000)
        }
        val ok = result.ok && result.stdout.contains("Success", ignoreCase = true)
        if (ok) {
            sp.edit().putInt("compiled_version", BuildConfig.VERSION_CODE).remove("last_error").apply()
            _status.value = Status.Done(BuildConfig.VERSION_CODE)
        } else {
            val reason = "${result.summary()} ${result.stderr.trim().take(120)}".trim()
            sp.edit().putString("last_error", reason).putInt("failed_version", BuildConfig.VERSION_CODE).apply()
            _status.value = Status.Failed(reason)
        }
        android.util.Log.i("CivicOpt", "compilación: ok=$ok · ${result.summary()} · ${result.stdout.take(200)} ${result.stderr.take(200)}")
    }

    private companion object {
        /** Esperar a que pase el arranque (mapa, música, OBD) antes de compilar. */
        const val START_DELAY_MS = 45_000L
    }
}
