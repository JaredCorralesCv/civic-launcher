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
 * Sin root se conecta al adb de la propia pantalla (la primera vez, Android pregunta si lo permite).
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
        if (_status.value is Status.Done) return
        // Con adb, el primer intento puede fallar mientras aceptas el permiso: se reintenta en otros arranques.
        if (_status.value is Status.Failed && sp.getInt("attempts_${BuildConfig.VERSION_CODE}", 0) >= MAX_ATTEMPTS) return
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
        val key = "attempts_${BuildConfig.VERSION_CODE}"
        sp.edit().putInt(key, sp.getInt(key, 0) + 1).apply()
        val result = kotlinx.coroutines.withContext(Dispatchers.IO) {
            // Prioridad baja: que no le quite procesador a la música ni al mapa mientras compila.
            Shell.su("nice -n 10 cmd package compile -m speed -f ${context.packageName}", timeoutMs = 900_000)
        }
        var ok = result.ok && result.stdout.contains("Success", ignoreCase = true)
        var adbError: String? = null
        if (!ok) {
            // Estas pantallas casi nunca dan root: el adb local sí puede compilar.
            val viaAdb = kotlinx.coroutines.withContext(Dispatchers.IO) { compileByAdb() }
            ok = viaAdb == null
            adbError = viaAdb
        }
        if (ok) {
            sp.edit().putInt("compiled_version", BuildConfig.VERSION_CODE).remove("last_error").apply()
            _status.value = Status.Done(BuildConfig.VERSION_CODE)
        } else {
            val reason = adbError ?: "${result.summary()} ${result.stderr.trim().take(120)}".trim()
            sp.edit().putString("last_error", reason).putInt("failed_version", BuildConfig.VERSION_CODE).apply()
            _status.value = Status.Failed(reason)
        }
        android.util.Log.i("CivicOpt", "compilación: ok=$ok · ${result.summary()} · ${result.stdout.take(200)} ${result.stderr.take(200)}")
    }

    /** Null si salió bien; si no, por qué. */
    private suspend fun compileByAdb(): String? = runCatching {
        val dir = java.io.File(context.filesDir, "adb").apply { mkdirs() }
        val priv = java.io.File(dir, "adbkey")
        val pub = java.io.File(dir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) dadb.AdbKeyPair.generate(priv, pub)
        val keys = dadb.AdbKeyPair.read(priv, pub)
        fun shell(port: Int, command: String): String =
            dadb.Dadb.create(LOCALHOST, port, keys, CONNECT_TIMEOUT_MS, ADB_TIMEOUT_MS).use { it.shell(command).allOutput }
        val port = ADB_PORTS.firstOrNull { p -> runCatching { shell(p, "echo listo").contains("listo") }.getOrDefault(false) }
            ?: return@runCatching "No se pudo entrar al adb de la pantalla"
        // En segundo plano y a un archivo: compilar tarda minutos y la conexión no aguanta tanto en silencio.
        val log = "/data/local/tmp/civic-compilar.txt"
        shell(port, "rm -f $log; nohup sh -c 'nice -n 10 cmd package compile -m speed -f ${context.packageName} > $log 2>&1' > /dev/null 2>&1 &")
        repeat(ADB_POLLS) {
            delay(ADB_POLL_MS)
            val out = runCatching { shell(port, "cat $log") }.getOrDefault("")
            if (out.contains("Success", ignoreCase = true)) return@runCatching null
            if (out.contains("Failure", ignoreCase = true) || out.contains("Error", ignoreCase = true)) return@runCatching out.trim().take(120)
        }
        "La compilación tardó demasiado"
    }.getOrElse { it.message ?: it.javaClass.simpleName }

    private companion object {
        /** Esperar a que pase el arranque (mapa, música, OBD) antes de compilar. */
        const val START_DELAY_MS = 45_000L
        const val MAX_ATTEMPTS = 3
        const val LOCALHOST = "127.0.0.1"
        /** 8555: el puerto de adb por red de esta pantalla. */
        val ADB_PORTS = intArrayOf(8555, 5555).toList()
        const val CONNECT_TIMEOUT_MS = 5_000
        /** Incluye el tiempo para aceptar el permiso en pantalla la primera vez. */
        const val ADB_TIMEOUT_MS = 60_000
        const val ADB_POLL_MS = 15_000L
        const val ADB_POLLS = 60
    }
}
