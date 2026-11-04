package com.civiclauncher.app.diag.util

import java.io.File
import kotlin.concurrent.thread

/** Ejecuta comandos de shell con límite de tiempo. Compatible con Android 7 (sin Process.waitFor(timeout)). */
object Shell {
    data class Result(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean,
        val error: String? = null,
    ) {
        val ok: Boolean get() = !timedOut && error == null && exitCode == 0

        fun summary(): String = when {
            error != null -> "no se pudo ejecutar ($error)"
            timedOut -> "tiempo agotado"
            else -> "código $exitCode"
        }
    }

    fun sh(script: String, timeoutMs: Long = 8_000): Result = exec(listOf("sh"), script, timeoutMs)

    val SU_PATHS = listOf(
        "/system/xbin/su", "/system/bin/su", "/sbin/su", "/su/bin/su", "/system/sbin/su",
        "/vendor/bin/su", "/data/local/xbin/su", "/data/local/bin/su", "/system/xbin/daemonsu",
    )

    /** Ruta absoluta de su. En tu pantalla está en /system/xbin/su, que no está en el PATH de las apps. */
    fun suBinary(): String = SU_PATHS.firstOrNull { runCatching { File(it).exists() }.getOrDefault(false) } ?: "su"

    /** Pide root. La primera vez puede aparecer un aviso de permiso de superusuario en pantalla. */
    fun su(script: String, timeoutMs: Long = 20_000): Result = exec(listOf(suBinary()), script, timeoutMs)

    private fun exec(cmd: List<String>, stdin: String, timeoutMs: Long): Result {
        val process = try {
            ProcessBuilder(cmd).start()
        } catch (e: Exception) {
            return Result(-1, "", "", false, e.message ?: e.javaClass.simpleName)
        }
        val out = StringBuffer()
        val err = StringBuffer()
        val tOut = thread(isDaemon = true) {
            runCatching { process.inputStream.bufferedReader().forEachLine { out.append(it).append('\n') } }
        }
        val tErr = thread(isDaemon = true) {
            runCatching { process.errorStream.bufferedReader().forEachLine { err.append(it).append('\n') } }
        }
        runCatching {
            process.outputStream.bufferedWriter().use {
                it.write(stdin)
                it.write("\nexit\n")
            }
        }
        var code = -1
        val tWait = thread(isDaemon = true) { code = runCatching { process.waitFor() }.getOrDefault(-1) }
        tWait.join(timeoutMs)
        if (tWait.isAlive) {
            process.destroy()
            return Result(-1, out.toString().trim(), err.toString().trim(), true)
        }
        tOut.join(1_000)
        tErr.join(1_000)
        return Result(code, out.toString().trim(), err.toString().trim(), false)
    }
}

object SysProp {
    private val getter by lazy {
        runCatching { Class.forName("android.os.SystemProperties").getMethod("get", String::class.java) }.getOrNull()
    }

    fun get(key: String): String =
        runCatching { getter?.invoke(null, key) as? String }.getOrNull().orEmpty()

    fun getOr(key: String, fallback: String = "—"): String = get(key).ifEmpty { fallback }

    fun dump(): String = Shell.sh("getprop").stdout
}

fun readFileOrNull(path: String): String? = runCatching { File(path).readText().trim() }.getOrNull()

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.0f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.0f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}

fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60}m ${s % 60}s"
        else -> "${s / 3600}h ${(s % 3600) / 60}m"
    }
}
