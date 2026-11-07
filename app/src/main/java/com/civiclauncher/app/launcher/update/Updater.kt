package com.civiclauncher.app.launcher.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.civiclauncher.app.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val latest: String) : UpdateState
    data class Available(val version: String, val apkUrl: String, val sizeBytes: Long, val notes: String) : UpdateState
    data class Downloading(val version: String, val progress: Float) : UpdateState
    data class Ready(val version: String, val file: File) : UpdateState
    data class Error(val message: String) : UpdateState
}

/**
 * Actualizador desde GitHub Releases.
 *
 * Flujo: buscar la última versión → si es más nueva, descargar el .apk → abrir el instalador de
 * Android. Sin root, Android siempre pide confirmar la instalación (es una protección del sistema).
 *
 * Requisitos: el repositorio debe ser público, el release debe tener un archivo .apk adjunto,
 * y ese APK debe estar firmado con la misma llave que la versión instalada.
 */
class Updater(private val context: Context, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    private var job: Job? = null

    fun check(repo: String) {
        if (repo.isBlank() || !repo.contains('/')) {
            _state.value = UpdateState.Error("Escribe el repositorio como usuario/repositorio")
            return
        }
        job?.cancel()
        _state.value = UpdateState.Checking
        job = scope.launch(Dispatchers.IO) {
            _state.value = runCatching {
                val json = JSONObject(httpGet("https://api.github.com/repos/$repo/releases/latest"))
                val tag = json.getString("tag_name")
                val version = tag.removePrefix("v").removePrefix("V")
                val assets = json.getJSONArray("assets")
                val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                    .firstOrNull { it.getString("name").endsWith(".apk", ignoreCase = true) }
                when {
                    !isNewer(version, BuildConfig.VERSION_NAME) -> UpdateState.UpToDate(version)
                    apk == null -> UpdateState.Error("El release $tag no tiene un archivo .apk adjunto")
                    else -> UpdateState.Available(
                        version = version,
                        apkUrl = apk.getString("browser_download_url"),
                        sizeBytes = apk.optLong("size", 0),
                        notes = json.optString("body", "").take(600),
                    )
                }
            }.getOrElse { UpdateState.Error(friendly(it)) }
        }
    }

    fun download() {
        val available = _state.value as? UpdateState.Available ?: return
        job?.cancel()
        _state.value = UpdateState.Downloading(available.version, 0f)
        job = scope.launch(Dispatchers.IO) {
            _state.value = runCatching {
                val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val file = File(dir, "CivicLauncher-${available.version}.apk")
                val conn = open(available.apkUrl)
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: available.sizeBytes
                conn.inputStream.use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var read = 0L
                        var lastReport = 0L
                        while (true) {
                            // Si cancelaste, no se marca como "lista" una descarga a medias.
                            if (!isActive) throw kotlinx.coroutines.CancellationException()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            read += n
                            if (total > 0 && read - lastReport > 128 * 1024) {
                                lastReport = read
                                _state.value = UpdateState.Downloading(available.version, read.toFloat() / total)
                            }
                        }
                    }
                }
                // Un APK incompleto fallaría al instalar con un error confuso: mejor decirlo aquí.
                if (total > 0 && file.length() != total) {
                    file.delete()
                    error("La descarga quedó incompleta; vuelve a intentarlo")
                }
                UpdateState.Ready(available.version, file)
            }.getOrElse {
                if (it is kotlinx.coroutines.CancellationException) throw it
                UpdateState.Error(friendly(it))
            }
        }
    }

    /** Android 8+ pide permitir "instalar apps desconocidas" para Civic una sola vez. */
    fun canInstall(): Boolean = Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    fun allowInstallsIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun installIntent(): Intent? {
        val ready = _state.value as? UpdateState.Ready ?: return null
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", ready.file)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            // GitHub exige un User-Agent; usamos un nombre genérico de la app.
            setRequestProperty("User-Agent", "CivicLauncher/${BuildConfig.VERSION_NAME}")
            setRequestProperty("Accept", "application/vnd.github+json")
        }

    private fun httpGet(url: String): String {
        val conn = open(url)
        val code = conn.responseCode
        if (code == 404) error("No se encontró el repositorio o no tiene releases publicados")
        if (code == 403) error("GitHub limitó las consultas; intenta en una hora")
        if (code !in 200..299) error("GitHub respondió $code")
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    private fun friendly(e: Throwable): String = when (e) {
        is java.net.UnknownHostException -> "Sin internet. Conecta la pantalla al WiFi de tu casa."
        is java.net.SocketTimeoutException -> "La conexión tardó demasiado"
        is javax.net.ssl.SSLException -> "Error de conexión segura (¿la hora de la pantalla es correcta?)"
        else -> e.message ?: e.javaClass.simpleName
    }

    companion object {
        /** Compara "0.2.10" con "0.2.9" por números, no como texto. Ignora sufijos como "-diag". */
        fun isNewer(candidate: String, current: String): Boolean {
            fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
            val a = parts(candidate)
            val b = parts(current)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}
