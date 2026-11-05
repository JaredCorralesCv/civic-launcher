package com.civiclauncher.app.launcher.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.civiclauncher.app.diag.autokit.MediaKeys
import com.civiclauncher.app.diag.notif.NotificationCollector
import com.civiclauncher.app.diag.util.SysProp
import com.civiclauncher.app.launcher.data.VendorApps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class NowPlaying(
    val pkg: String,
    val source: MusicSource,
    val title: String?,
    val artist: String?,
    val album: String?,
    val art: ImageBitmap?,
    /** Color promedio de la portada, para teñir el fondo de la tarjeta. */
    val artColor: Color?,
    val playing: Boolean,
    val positionMs: Long?,
    val durationMs: Long?,
    /** Momento (elapsedRealtime) en que se midió [positionMs]: con esto la barra avanza sola. */
    val positionAt: Long,
    val speed: Float,
)

enum class MusicSource(val label: String) {
    ANDROID_AUTO("Android Auto"),
    BLUETOOTH("Bluetooth"),
    RADIO("Radio"),
    USB("USB"),
    APP("App"),
}

sealed interface MediaState {
    /** Falta el permiso de notificaciones (Android lo exige para ver qué suena). */
    data object NoPermission : MediaState
    /** Nada con datos. [source] es lo que el fabricante dice que está sonando, si algo. */
    data class Idle(val source: MusicSource?) : MediaState
    data class Active(val now: NowPlaying) : MediaState
}

/**
 * Qué está sonando, para la tarjeta de música de Inicio.
 *
 * Lee las "sesiones de música" de Android (como las ve el volante). Si suenan varias, elige la que
 * esté reproduciendo, o la que el fabricante marca como fuente de audio (sys.sourcing.package).
 *
 * Límite conocido (Fase 0): AutoKit no comparte la canción. En ese caso la tarjeta dice
 * "Android Auto" y ofrece los controles, sin título.
 */
class MediaRepository(private val context: Context, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow<MediaState>(MediaState.Idle(null))
    val state: StateFlow<MediaState> = _state.asStateFlow()

    private val sp = context.getSharedPreferences("media", Context.MODE_PRIVATE)
    private val _lastSource = MutableStateFlow(runCatching { MusicSource.valueOf(sp.getString("last_source", null) ?: "") }.getOrNull())
    /** Última fuente que sonó (Android Auto, Bluetooth, Radio o USB), para resaltarla en Inicio. */
    val lastSource: StateFlow<MusicSource?> = _lastSource.asStateFlow()

    private fun rememberSource(s: MusicSource?) {
        if (s == null || s == MusicSource.APP || s == _lastSource.value) return
        _lastSource.value = s
        sp.edit().putString("last_source", s.name).apply()
    }

    private val handler = Handler(Looper.getMainLooper())
    private val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private var listening = false
    private var current: MediaController? = null
    private var lastArtKey: Any? = null
    private var lastArt: Pair<ImageBitmap, Color>? = null

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list -> choose(list.orEmpty()) }

    // ------------------------------------------------------------------ Bluetooth del celular
    //
    // Lo que se vio en la pantalla (8.1, Autochips):
    //  * La sesión "com.android.bluetooth" (A2dpMediaBrowserService) trae canción, artista y avance.
    //  * Si otra app (la radio) toma el audio, el Bluetooth pierde el "foco" y, desde ese momento,
    //    cada vez que el celular empieza a sonar la pantalla le manda pausa (A2dpSinkStreamHandler).
    //    Por eso Spotify se detenía al darle play en el celular.
    //  * Un play desde la pantalla (transportControls.play) recupera el foco y sí suena.
    //  * Además, el gestor de fuentes del fabricante (SourceService) debe tener al Bluetooth como
    //    fuente; el aviso "net.easyconn.a2dp.acquire" lo cambia sin abrir la app "Música BT".

    private var bt: MediaController? = null
    private var lastBtResume = 0L

    private val btCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            // El celular empezó a sonar (le diste play en el teléfono). Si la pantalla no tiene al
            // Bluetooth como fuente, lo pausaría enseguida: lo tomamos y confirmamos el play.
            if (state?.state == PlaybackState.STATE_PLAYING && SysProp.get("sys.sourcing.package") != VendorApps.BLUETOOTH) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastBtResume > 4_000) {
                    lastBtResume = now
                    resumeBluetooth()
                }
            }
        }
        override fun onSessionDestroyed() {
            bt = null
        }
    }

    private fun trackBluetooth(list: List<MediaController>) {
        val found = list.firstOrNull { it.packageName == BT_SESSION }
        if (found?.sessionToken == bt?.sessionToken) return
        bt?.unregisterCallback(btCallback)
        bt = found
        found?.registerCallback(btCallback, handler)
    }

    /** Hace del Bluetooth la fuente de audio de la pantalla (sin abrir la app "Música BT"). */
    private fun claimBluetooth() {
        runCatching { context.sendBroadcast(android.content.Intent(ACQUIRE_A2DP)) }
    }

    /** Toma la fuente y le pide al celular que suene. false si no hay celular conectado. */
    private fun resumeBluetooth(): Boolean {
        val c = bt ?: runCatching { msm.getActiveSessions(NotificationCollector.component(context)) }
            .getOrDefault(emptyList()).firstOrNull { it.packageName == BT_SESSION }
            ?: return false
        claimBluetooth()
        // Un instante para que el gestor de fuentes cambie la ruta del audio antes del play.
        handler.postDelayed({ runCatching { c.transportControls.play() } }, 350)
        return true
    }

    /**
     * Tocar "Bluetooth" en Inicio: suena directo, sin abrir la app del fabricante.
     * false si el celular no está conectado (entonces conviene abrir la app para conectarlo).
     */
    fun playBluetooth(): Boolean = resumeBluetooth()

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            // Si esta dejó de sonar y otra empezó, cambiamos a la otra.
            if (state?.state != PlaybackState.STATE_PLAYING) rechoose() else publish()
        }
        override fun onSessionDestroyed() = rechoose()
    }

    /** Llamar al volver a la pantalla: por si se concedió el permiso o cambió la fuente. */
    fun refresh() {
        if (!NotificationCollector.isEnabled(context)) {
            stopListening()
            _state.value = MediaState.NoPermission
            return
        }
        if (!listening) {
            listening = runCatching {
                msm.addOnActiveSessionsChangedListener(sessionsListener, NotificationCollector.component(context), handler)
                true
            }.getOrDefault(false)
        }
        rechoose()
    }

    private fun stopListening() {
        if (listening) runCatching { msm.removeOnActiveSessionsChangedListener(sessionsListener) }
        listening = false
        current?.unregisterCallback(controllerCallback)
        current = null
        bt?.unregisterCallback(btCallback)
        bt = null
    }

    private fun rechoose() {
        val list = runCatching { msm.getActiveSessions(NotificationCollector.component(context)) }.getOrDefault(emptyList())
        choose(list)
    }

    private fun choose(list: List<MediaController>) {
        trackBluetooth(list)
        val vendorSource = SysProp.get("sys.sourcing.package")
        val best = list.sortedWith(
            compareByDescending<MediaController> { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                .thenByDescending { it.packageName == vendorSource }
                .thenByDescending { it.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) != null },
        ).firstOrNull()
        if (best?.sessionToken != current?.sessionToken) {
            current?.unregisterCallback(controllerCallback)
            current = best
            best?.registerCallback(controllerCallback, handler)
        }
        publish()
    }

    private fun publish() {
        val c = current
        val md = c?.metadata
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
        if (c == null || title == null) {
            // Sin datos de la canción: al menos decir de dónde viene el audio.
            val src = (c?.packageName ?: SysProp.get("sys.sourcing.package")).takeIf { it.isNotBlank() }?.let(::sourceOf)
            if (src == MusicSource.ANDROID_AUTO) rememberSource(src)
            _state.value = MediaState.Idle(src)
            return
        }
        rememberSource(sourceOf(c.packageName))
        val ps = c.playbackState
        val base = NowPlaying(
            pkg = c.packageName,
            source = sourceOf(c.packageName),
            title = title,
            artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
            album = md.getString(MediaMetadata.METADATA_KEY_ALBUM),
            art = lastArt?.first,
            artColor = lastArt?.second,
            playing = ps?.state == PlaybackState.STATE_PLAYING,
            positionMs = ps?.position?.takeIf { it >= 0 },
            durationMs = md.getLong(MediaMetadata.METADATA_KEY_DURATION).takeIf { it > 0 },
            positionAt = ps?.lastPositionUpdateTime?.takeIf { it > 0 } ?: SystemClock.elapsedRealtime(),
            speed = ps?.playbackSpeed ?: 1f,
        )
        val bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val artKey = bmp?.let { System.identityHashCode(it) } ?: title
        if (artKey == lastArtKey) {
            _state.value = MediaState.Active(base)
            return
        }
        lastArtKey = artKey
        lastArt = null
        _state.value = MediaState.Active(base.copy(art = null, artColor = null))
        if (bmp != null) {
            // Reducir y sacar el color en segundo plano: una portada puede pesar varios MB.
            scope.launch {
                val prepared = withContext(Dispatchers.Default) { prepareArt(bmp) }
                if (lastArtKey != artKey) return@launch
                lastArt = prepared
                (_state.value as? MediaState.Active)?.let { _state.value = MediaState.Active(it.now.copy(art = prepared.first, artColor = prepared.second)) }
            }
        }
    }

    private fun prepareArt(src: Bitmap): Pair<ImageBitmap, Color> {
        val size = 240
        val scaled = if (src.width > size || src.height > size) Bitmap.createScaledBitmap(src, size, size, true) else src
        val one = Bitmap.createScaledBitmap(scaled, 1, 1, true)
        return scaled.asImageBitmap() to Color(one.getPixel(0, 0))
    }

    fun sourceOf(pkg: String): MusicSource = when (pkg) {
        VendorApps.ANDROID_AUTO -> MusicSource.ANDROID_AUTO
        BT_SESSION, VendorApps.BLUETOOTH -> MusicSource.BLUETOOTH
        VendorApps.RADIO -> MusicSource.RADIO
        VendorApps.MUSIC, "com.hcn.automediaplayerservice" -> MusicSource.USB
        else -> MusicSource.APP
    }

    // ------------------------------------------------------------------ Controles

    fun playPause() {
        val c = current
        val playing = c?.playbackState?.state == PlaybackState.STATE_PLAYING
        val actions = c?.playbackState?.actions ?: 0L
        when {
            c == null -> MediaKeys.global(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            // Bluetooth en pausa: además del play hay que recuperar la fuente (si no, se oye la radio).
            !playing && c.packageName == BT_SESSION -> resumeBluetooth()
            playing && actions and (PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE) != 0L -> c.transportControls.pause()
            !playing && actions and (PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PLAY_PAUSE) != 0L -> c.transportControls.play()
            // Apps que no declaran sus controles (como AutoKit) suelen entender la tecla física.
            else -> MediaKeys.toSession(c, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        }
    }

    fun next() = skip(PlaybackState.ACTION_SKIP_TO_NEXT, KeyEvent.KEYCODE_MEDIA_NEXT) { it.skipToNext() }

    fun previous() = skip(PlaybackState.ACTION_SKIP_TO_PREVIOUS, KeyEvent.KEYCODE_MEDIA_PREVIOUS) { it.skipToPrevious() }

    private inline fun skip(action: Long, key: Int, direct: (MediaController.TransportControls) -> Unit) {
        val c = current
        when {
            c == null -> MediaKeys.global(context, key)
            // En la Fase 0, AutoKit respondió a "siguiente" por orden directa aunque no la declara.
            // Mandar también la tecla podría saltar dos canciones, así que solo la orden directa.
            else -> runCatching { direct(c.transportControls) }.onFailure { MediaKeys.toSession(c, key) }
        }
    }

    private companion object {
        /** Sesión de música del Bluetooth (el celular conectado), del servicio A2DP de Android. */
        const val BT_SESSION = "com.android.bluetooth"
        /** Aviso que el gestor de fuentes del fabricante entiende como "el audio ahora es Bluetooth". */
        const val ACQUIRE_A2DP = "net.easyconn.a2dp.acquire"
    }
}
