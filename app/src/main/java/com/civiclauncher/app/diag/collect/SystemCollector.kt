package com.civiclauncher.app.diag.collect

import android.app.ActivityManager
import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import com.civiclauncher.app.diag.ReportSection
import com.civiclauncher.app.diag.Row
import com.civiclauncher.app.diag.Status
import com.civiclauncher.app.diag.util.SysProp
import com.civiclauncher.app.diag.util.formatBytes
import com.civiclauncher.app.diag.util.formatDuration
import com.civiclauncher.app.diag.util.readFileOrNull
import java.io.File
import java.util.Locale
import java.util.TimeZone
import kotlin.math.sqrt

object SystemCollector {

    /** El SDK lo pone el sistema y casi nunca se falsifica; el texto "Versión de Android" sí. */
    fun androidNameForSdk(sdk: Int): String = when (sdk) {
        24 -> "7.0"; 25 -> "7.1"; 26 -> "8.0"; 27 -> "8.1"; 28 -> "9"; 29 -> "10"
        30 -> "11"; 31 -> "12"; 32 -> "12L"; 33 -> "13"; 34 -> "14"; 35 -> "15"; 36 -> "16"
        else -> "desconocida (API $sdk)"
    }

    fun isVersionSpoofed(): Boolean {
        val realMajor = androidNameForSdk(Build.VERSION.SDK_INT).substringBefore('.').substringBefore('L')
        val shownMajor = Build.VERSION.RELEASE.substringBefore('.')
        return realMajor != shownMajor
    }

    fun totalRamBytes(context: Context): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return mi.totalMem
    }

    fun chipName(): String = listOf("ro.mediatek.platform", "ro.board.platform", "ro.hardware.chipname", "ro.chipname", "ro.hardware")
        .map { SysProp.get(it) }
        .firstOrNull { it.isNotBlank() }
        ?: cpuInfoField("Hardware")
        ?: "—"

    private fun cpuInfoField(name: String): String? =
        readFileOrNull("/proc/cpuinfo")?.lineSequence()
            ?.firstOrNull { it.startsWith(name, ignoreCase = true) }
            ?.substringAfter(':')?.trim()

    private fun maxCpuFreqMhz(): String {
        val freqs = (0 until Runtime.getRuntime().availableProcessors()).mapNotNull {
            readFileOrNull("/sys/devices/system/cpu/cpu$it/cpufreq/cpuinfo_max_freq")?.toLongOrNull()
        }
        return if (freqs.isEmpty()) "—" else "${freqs.max() / 1000} MHz"
    }

    fun system(context: Context): ReportSection {
        val sdk = Build.VERSION.SDK_INT
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val data = StatFs(Environment.getDataDirectory().path)
        val spoofed = isVersionSpoofed()
        val elapsed = SystemClock.elapsedRealtime()
        val uptime = SystemClock.uptimeMillis()

        val rows = listOf(
            Row("Marca", Build.BRAND),
            Row("Fabricante", Build.MANUFACTURER),
            Row("Modelo", Build.MODEL),
            Row("Dispositivo", Build.DEVICE),
            Row("Producto", Build.PRODUCT),
            Row("Placa", Build.BOARD),
            Row("Hardware", Build.HARDWARE),
            Row("Android que muestra Configuración", Build.VERSION.RELEASE),
            Row(
                "Android real (por API)",
                "${androidNameForSdk(sdk)} (API $sdk)",
                if (spoofed) Status.WARN else Status.OK,
            ),
            Row("Versión falsificada", if (spoofed) "Sí: Configuración muestra una versión que no es la real" else "No", if (spoofed) Status.WARN else Status.OK),
            Row("Parche de seguridad", Build.VERSION.SECURITY_PATCH),
            Row("Compilación", Build.DISPLAY),
            Row("Huella", Build.FINGERPRINT),
            Row("Chip / plataforma", chipName()),
            Row("ABIs", Build.SUPPORTED_ABIS.joinToString()),
            Row("CPU (cpuinfo)", cpuInfoField("Hardware") ?: cpuInfoField("model name") ?: cpuInfoField("Processor") ?: "—"),
            Row("Núcleos", Runtime.getRuntime().availableProcessors().toString()),
            Row("Frecuencia máxima", maxCpuFreqMhz()),
            Row("Kernel", readFileOrNull("/proc/version") ?: System.getProperty("os.version").orEmpty()),
            Row("RAM total", formatBytes(mi.totalMem), if (mi.totalMem < 1_600_000_000L) Status.WARN else Status.OK),
            Row("RAM disponible", formatBytes(mi.availMem)),
            Row("Memoria por app", "${am.memoryClass} MB (grande: ${am.largeMemoryClass} MB)"),
            Row("Dispositivo de poca RAM", if (am.isLowRamDevice) "Sí" else "No"),
            Row("Almacenamiento interno", "${formatBytes(data.availableBytes)} libres de ${formatBytes(data.totalBytes)}"),
            Row("OpenGL ES", am.deviceConfigurationInfo.glEsVersion),
            Row("Encendida desde hace", formatDuration(elapsed)),
            Row("Tiempo dormida desde el arranque", formatDuration(elapsed - uptime)),
            Row("Idioma / zona", "${Locale.getDefault()} / ${TimeZone.getDefault().id}"),
            Row("Root (archivo su)", if (RootCollector.suPathsFound().isNotEmpty()) "Encontrado" else "No encontrado"),
        )
        val notes = buildList {
            if (spoofed) add("La versión real es la de la API. Diseñamos para Android ${androidNameForSdk(sdk)}.")
            if (File("/proc/cpuinfo").canRead().not()) add("No se pudo leer /proc/cpuinfo.")
        }
        return ReportSection("Sistema", rows, notes)
    }

    data class Gpu(val renderer: String, val vendor: String, val version: String, val extensions: Int)

    /** Crea un contexto OpenGL invisible de 1×1 solo para preguntar qué GPU hay. */
    fun gpu(): Gpu? = runCatching {
        val dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        EGL14.eglInitialize(dpy, ver, 0, ver, 1)
        val attribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        EGL14.eglChooseConfig(dpy, attribs, 0, configs, 0, 1, num, 0)
        val config = configs[0] ?: return@runCatching null
        val ctx = EGL14.eglCreateContext(
            dpy, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        val surf = EGL14.eglCreatePbufferSurface(dpy, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(dpy, surf, surf, ctx)
        val gpu = Gpu(
            renderer = GLES20.glGetString(GLES20.GL_RENDERER).orEmpty(),
            vendor = GLES20.glGetString(GLES20.GL_VENDOR).orEmpty(),
            version = GLES20.glGetString(GLES20.GL_VERSION).orEmpty(),
            extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS).orEmpty().split(' ').count { it.isNotBlank() },
        )
        EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(dpy, surf)
        EGL14.eglDestroyContext(dpy, ctx)
        // No llamamos eglTerminate: la pantalla EGL la comparte el renderizador de la app.
        gpu
    }.getOrNull()

    data class Screen(val widthPx: Int, val heightPx: Int, val densityDpi: Int, val density: Float, val refreshHz: Float)

    @Suppress("DEPRECATION")
    fun screen(context: Context): Screen {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val d = wm.defaultDisplay
        val m = DisplayMetrics().also { d.getRealMetrics(it) }
        return Screen(m.widthPixels, m.heightPixels, m.densityDpi, m.density, d.refreshRate)
    }

    @Suppress("DEPRECATION")
    fun display(context: Context): ReportSection {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val d = wm.defaultDisplay
        val m = DisplayMetrics().also { d.getRealMetrics(it) }
        val inches = sqrt((m.widthPixels / m.xdpi).let { it * it } + (m.heightPixels / m.ydpi).let { it * it })
        val gpu = gpu()
        val gpuWeak = gpu?.renderer?.let { r -> listOf("Mali-400", "Mali-450", "Mali-T720", "PowerVR SGX", "Adreno (TM) 3").any { r.contains(it, true) } } ?: false

        val rows = listOf(
            Row("Resolución real", "${m.widthPixels} × ${m.heightPixels} px"),
            Row("Densidad", "${m.densityDpi} dpi (factor ${"%.2f".format(m.density)})"),
            Row("Tamaño en dp", "${(m.widthPixels / m.density).toInt()} × ${(m.heightPixels / m.density).toInt()} dp"),
            Row("Escala de texto", "%.2f".format(m.scaledDensity / m.density)),
            Row("Frecuencia de refresco", "${"%.1f".format(d.refreshRate)} Hz"),
            Row("Modos soportados", d.supportedModes.joinToString { "${it.physicalWidth}×${it.physicalHeight}@${"%.0f".format(it.refreshRate)}" }),
            Row("DPI físico (x / y)", "${"%.0f".format(m.xdpi)} / ${"%.0f".format(m.ydpi)}"),
            Row("Diagonal estimada", "${"%.1f".format(inches)}\" (poco fiable en pantallas chinas)"),
            Row("GPU", gpu?.renderer ?: "No se pudo leer", if (gpuWeak) Status.WARN else Status.INFO),
            Row("Fabricante GPU", gpu?.vendor ?: "—"),
            Row("Versión OpenGL", gpu?.version ?: "—"),
            Row("Extensiones OpenGL", gpu?.extensions?.toString() ?: "—"),
        )
        val notes = buildList {
            if (gpuWeak) add("GPU de gama baja: el launcher evitará desenfoques, sombras y capas semitransparentes encimadas.")
        }
        return ReportSection("Pantalla y GPU", rows, notes)
    }
}
