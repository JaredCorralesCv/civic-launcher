package com.civiclauncher.app.launcher.media

import android.content.Context
import android.content.Intent
import android.media.session.MediaSessionManager
import android.os.IBinder
import android.view.KeyEvent
import com.civiclauncher.app.MainActivity
import com.civiclauncher.app.diag.autokit.MediaKeys
import com.civiclauncher.app.diag.notif.NotificationCollector
import com.civiclauncher.app.diag.util.SysProp
import com.civiclauncher.app.launcher.data.VendorApps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Lo que se sabe de la radio en este momento. Todo puede faltar si el servicio no responde. */
data class RadioInfo(
    /** "FM" o "AM". */
    val band: String?,
    /** Frecuencia lista para mostrar: "99.3" (FM, en MHz) o "1010" (AM, en kHz). */
    val frequency: String?,
    val unit: String?,
    /** Posición en el dial de 0 a 1, para la aguja. */
    val dial: Float?,
    /** La radio es la fuente de audio de la pantalla (está sonando o en silencio, pero activa). */
    val isSource: Boolean,
)

/**
 * La radio del fabricante, controlada desde Civic.
 *
 * La pantalla trae un servicio de sistema "fmradio" (android.radio.IRadioPlayer, en carservices)
 * con getFreq/getUiband. Lo leemos por reflexión: no es una API pública y no hay documentación, así
 * que todo está protegido; si algo no responde, la pantalla de radio sigue funcionando sin la
 * frecuencia. Los controles van como teclas de música a la sesión de la radio (la radio tiene su
 * receptor RadioMediaButtonReceiver): siguiente/anterior = buscar estación.
 *
 * PENDIENTE de comprobar en la pantalla real: las unidades de getFreq y el formato de la banda.
 */
class RadioRepository(
    private val context: Context,
    private val scope: CoroutineScope,
    /** Avisa a la música que la radio manda ahora (para que no recupere el Bluetooth). */
    private val onYield: () -> Unit = {},
) {
    private val _info = MutableStateFlow(RadioInfo(null, null, null, null, false))
    val info: StateFlow<RadioInfo> = _info.asStateFlow()

    private var pollJob: Job? = null
    private var watchers = 0
    private var player: Any? = null

    /** Llamar al mostrar la pantalla de radio; [stopWatching] al salir. Cuenta referencias. */
    fun startWatching() {
        watchers++
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                _info.value = withContext(Dispatchers.IO) { read() }
                delay(1_000)
            }
        }
    }

    fun stopWatching() {
        watchers = (watchers - 1).coerceAtLeast(0)
        if (watchers == 0) pollJob?.cancel()
    }

    private fun read(): RadioInfo {
        val isSource = SysProp.get("sys.sourcing.package") == VendorApps.RADIO
        val p = player ?: connect().also { player = it }
        val rawFreq = p?.let { call(it, "getFreq") as? Number }?.toInt()
        val rawBand = p?.let { call(it, "getUiband") ?: call(it, "getBand") }?.toString()
        val am = rawBand?.contains("AM", ignoreCase = true) == true || (rawFreq != null && rawFreq in 520..1710)
        val band = when {
            rawBand == null && rawFreq == null -> null
            am -> "AM"
            else -> "FM"
        }
        val (text, unit, dial) = format(rawFreq, am)
        return RadioInfo(band, text, unit, dial, isSource)
    }

    /** Convierte el número del servicio a "99.3 MHz" / "1010 kHz", sin saber de antemano su unidad. */
    private fun format(raw: Int?, am: Boolean): Triple<String?, String?, Float?> {
        if (raw == null || raw <= 0) return Triple(null, null, null)
        if (am) {
            val khz = if (raw > 100_000) raw / 1000 else raw
            return Triple(khz.toString(), "kHz", ((khz - 530f) / (1710f - 530f)).coerceIn(0f, 1f))
        }
        val mhz = when (raw) {
            in 87_500..108_000 -> raw / 1000f // kHz
            in 8_750..10_800 -> raw / 100f // decenas de kHz
            in 875..1_080 -> raw / 10f // cientos de kHz
            in 87_500_000..108_000_000 -> raw / 1_000_000f // Hz
            else -> return Triple(null, null, null)
        }
        return Triple("%.1f".format(mhz), "MHz", ((mhz - 87.5f) / (108f - 87.5f)).coerceIn(0f, 1f))
    }

    private fun connect(): Any? = runCatching {
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, "fmradio") as? IBinder ?: return null
        Class.forName("android.radio.IRadioPlayer\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)
    }.getOrNull()

    private fun call(target: Any, name: String): Any? = runCatching {
        target.javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.isEmpty() }?.invoke(target)
    }.getOrNull()

    private companion object {
        const val TAG = "CivicRadio"
    }

    // ------------------------------------------------------------------ Controles

    /**
     * Hace de la radio la fuente de audio. El gestor de fuentes del fabricante decide por la app que
     * está al frente, así que abrimos la radio un instante y volvemos a Civic.
     */
    fun activate() {
        // Elegiste la radio: que Civic no recupere el Bluetooth por su cuenta en los próximos segundos.
        onYield()
        if (SysProp.get("sys.sourcing.package") == VendorApps.RADIO) return
        val launch = context.packageManager.getLaunchIntentForPackage(VendorApps.RADIO) ?: return
        runCatching { context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        scope.launch {
            delay(900)
            android.util.Log.i(TAG, "volviendo a Civic tras abrir la radio")
            runCatching {
                // Igual que el botón de inicio: así Android lo trata como "volver al launcher".
                context.startActivity(
                    Intent(context, MainActivity::class.java)
                        .setAction(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_HOME)
                        .putExtra(MainActivity.EXTRA_STAY, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
                )
            }.onFailure { android.util.Log.w(TAG, "no se pudo volver a Civic", it) }
        }
    }

    fun seekUp() = key(KeyEvent.KEYCODE_MEDIA_NEXT)
    fun seekDown() = key(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
    fun playPause() = key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)

    /** A la sesión de la radio si existe; si no, a quien tenga la música (como el volante). */
    private fun key(code: Int) {
        val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val radio = runCatching { msm.getActiveSessions(NotificationCollector.component(context)) }
            .getOrDefault(emptyList())
            .firstOrNull { it.packageName == VendorApps.RADIO }
        if (radio != null) MediaKeys.toSession(radio, code) else MediaKeys.global(context, code)
    }

    /** Para buscar y guardar estaciones: la app de la radio. */
    fun openStations(): Boolean = runCatching {
        context.startActivity(context.packageManager.getLaunchIntentForPackage(VendorApps.RADIO)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)
}
