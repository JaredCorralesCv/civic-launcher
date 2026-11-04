package com.civiclauncher.app.diag.autokit

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.view.KeyEvent
import com.civiclauncher.app.diag.util.SysProp
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import com.civiclauncher.app.diag.DiagStore
import com.civiclauncher.app.diag.ReportSection
import com.civiclauncher.app.diag.Row
import com.civiclauncher.app.diag.Status
import com.civiclauncher.app.diag.notif.NotificationCollector
import com.civiclauncher.app.diag.util.formatDuration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Envía teclas multimedia como las del volante: abajo y arriba, igual que un botón físico. */
object MediaKeys {
    private fun events(code: Int): List<KeyEvent> {
        val now = SystemClock.uptimeMillis()
        return listOf(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0), KeyEvent(now, now + 50, KeyEvent.ACTION_UP, code, 0))
    }

    /** Solo a una app en particular (su sesión de música). */
    fun toSession(controller: MediaController, code: Int) {
        events(code).forEach { runCatching { controller.dispatchMediaButtonEvent(it) } }
    }

    /** A quien tenga el control de la música en ese momento, como hace el volante. */
    fun global(context: Context, code: Int) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        events(code).forEach { runCatching { am.dispatchMediaKeyEvent(it) } }
    }
}

object AutoKitCollector {
    /** AutoKit de Carlinkit/Manstep, la app que proyecta Android Auto con el adaptador "Auto Box". */
    const val PACKAGE = "cn.manstep.phonemirrorBox"

    fun version(context: Context): String? = runCatching {
        context.packageManager.getPackageInfo(PACKAGE, 0).versionName ?: "?"
    }.getOrNull()

    fun launchIntent(context: Context): Intent? = context.packageManager.getLaunchIntentForPackage(PACKAGE)

    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun overlaySettingsIntent(context: Context) =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))

    data class SessionInfo(
        val pkg: String,
        val state: String,
        val title: String?,
        val artist: String?,
        val actions: List<String>,
        val controller: MediaController,
    )

    fun sessions(context: Context): List<SessionInfo> {
        if (!NotificationCollector.isEnabled(context)) return emptyList()
        val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        return runCatching { msm.getActiveSessions(NotificationCollector.component(context)) }.getOrDefault(emptyList()).map { c ->
            val ps = c.playbackState
            val md = c.metadata
            SessionInfo(
                pkg = c.packageName,
                state = stateName(ps?.state),
                title = md?.getString(MediaMetadata.METADATA_KEY_TITLE),
                artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST),
                actions = actionNames(ps?.actions ?: 0L),
                controller = c,
            )
        }
    }

    private fun stateName(s: Int?) = when (s) {
        PlaybackState.STATE_PLAYING -> "reproduciendo"
        PlaybackState.STATE_PAUSED -> "en pausa"
        PlaybackState.STATE_STOPPED -> "detenida"
        PlaybackState.STATE_BUFFERING -> "cargando"
        PlaybackState.STATE_NONE -> "sin reproducir"
        null -> "sin estado"
        else -> "estado $s"
    }

    private fun actionNames(a: Long): List<String> = buildList {
        if (a and PlaybackState.ACTION_PLAY != 0L) add("play")
        if (a and PlaybackState.ACTION_PAUSE != 0L) add("pausa")
        if (a and PlaybackState.ACTION_PLAY_PAUSE != 0L) add("play/pausa")
        if (a and PlaybackState.ACTION_SKIP_TO_NEXT != 0L) add("siguiente")
        if (a and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L) add("anterior")
        if (a and PlaybackState.ACTION_SEEK_TO != 0L) add("adelantar")
    }

    /** Para el reporte: si hay título o no, pero sin escribir qué canción era. */
    fun collect(context: Context): ReportSection {
        val ver = version(context)
        val overlay = canDrawOverlays(context)
        val all = sessions(context)
        val ak = all.filter { it.pkg == PACKAGE }
        val rows = mutableListOf(
            Row("AutoKit instalado", ver?.let { "Sí (v$it)" } ?: "No", if (ver != null) Status.OK else Status.WARN),
            Row("Permiso de ventana flotante", if (overlay) "Concedido" else "Falta", if (overlay) Status.OK else Status.WARN),
            Row(
                "Sesión de música de AutoKit",
                if (ak.isEmpty()) "No aparece" else ak.joinToString { it.state },
                if (ak.isNotEmpty()) Status.OK else Status.WARN,
            ),
            Row(
                "AutoKit comparte la canción",
                when {
                    ak.isEmpty() -> "—"
                    ak.any { it.title != null } -> "Sí (título y artista)"
                    else -> "No (sin datos de la canción)"
                },
                when {
                    ak.any { it.title != null } -> Status.OK
                    ak.isEmpty() -> Status.INFO
                    else -> Status.WARN
                },
            ),
            Row("Controles que acepta AutoKit", ak.flatMap { it.actions }.distinct().joinToString().ifEmpty { "—" }),
            // Propiedad del fabricante: qué app tiene el audio del carro (radio, Bluetooth, AutoKit...).
            Row("Fuente de audio actual (fabricante)", SysProp.get("sys.sourcing.package").ifEmpty { "—" }),
        )
        all.filter { it.pkg != PACKAGE }.forEach {
            rows += Row("  · otra sesión: ${it.pkg}", "${it.state} · datos: ${if (it.title != null) "sí" else "no"} · ${it.actions.joinToString()}")
        }
        val notes = listOf("Esta prueba solo sirve con el celular conectado y música sonando en Android Auto.")
        return ReportSection("AutoKit / Android Auto", rows, notes)
    }
}

/**
 * Burbuja flotante encima de AutoKit. Prueba dos cosas a la vez:
 *  1. que podemos mostrar alertas del OBD mientras ves Android Auto;
 *  2. cuánta RAM queda libre con Android Auto corriendo (con 1 GB, es nuestro presupuesto real).
 * No bloquea los toques: Android Auto sigue funcionando debajo.
 */
class OverlayProbe(private val context: Context, private val store: DiagStore) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val handler = Handler(Looper.getMainLooper())
    private var view: TextView? = null
    private var startedAt = 0L
    private var endsAt = 0L
    private var minFree = Long.MAX_VALUE
    private var maxFree = 0L
    private var samples = 0
    private var lowMemorySeen = false
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val tick = object : Runnable {
        override fun run() {
            val v = view ?: return
            val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            minFree = minOf(minFree, mi.availMem)
            maxFree = maxOf(maxFree, mi.availMem)
            if (mi.lowMemory) lowMemorySeen = true
            samples++
            val left = ((endsAt - SystemClock.elapsedRealtime()) / 1000).coerceAtLeast(0)
            v.text = "Civic · RAM libre ${mi.availMem / 1_048_576} MB · ${left}s"
            if (left <= 0) stop() else handler.postDelayed(this, 1_000)
        }
    }

    @SuppressLint("SetTextI18n")
    fun start(durationMs: Long = 120_000): Boolean {
        if (view != null) return true
        if (!AutoKitCollector.canDrawOverlays(context)) return false
        val tv = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            setPadding(28, 14, 28, 14)
            // Fondo casi opaco y una sola capa: barato para la Mali-450.
            background = GradientDrawable().apply {
                setColor(0xF0101418.toInt())
                cornerRadius = 32f
            }
            text = "Civic · midiendo…"
        }
        @Suppress("DEPRECATION")
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16
            y = 16
        }
        val added = runCatching { wm.addView(tv, lp) }.isSuccess
        if (!added) return false
        view = tv
        minFree = Long.MAX_VALUE
        maxFree = 0L
        samples = 0
        lowMemorySeen = false
        startedAt = SystemClock.elapsedRealtime()
        endsAt = startedAt + durationMs
        _running.value = true
        handler.post(tick)
        return true
    }

    fun stop() {
        val v = view ?: return
        handler.removeCallbacks(tick)
        runCatching { wm.removeView(v) }
        view = null
        _running.value = false
        if (samples > 0) {
            val minMb = minFree / 1_048_576
            store.put(
                KEY,
                ReportSection(
                    "Prueba de burbuja sobre Android Auto",
                    listOf(
                        Row("La burbuja se mostró", "Sí", Status.OK),
                        Row("Duración", formatDuration(SystemClock.elapsedRealtime() - startedAt)),
                        Row(
                            "RAM libre mínima",
                            "$minMb MB",
                            when {
                                minMb < 150 -> Status.BAD
                                minMb < 250 -> Status.WARN
                                else -> Status.OK
                            },
                        ),
                        Row("RAM libre máxima", "${maxFree / 1_048_576} MB"),
                        Row("Android avisó de memoria baja", if (lowMemorySeen) "Sí" else "No", if (lowMemorySeen) Status.BAD else Status.OK),
                    ),
                    listOf("Si viste la burbuja encima de Android Auto, podremos mostrar alertas del motor sin salir de Android Auto."),
                ),
            )
        }
    }

    companion object {
        const val KEY = "overlay_carplay"
    }
}
