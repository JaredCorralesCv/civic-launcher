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
    private var lastBtState: Int? = null
    private var lastBtPauseAt = 0L
    private var lastLauncherResume = 0L

    /**
     * Civic volvió al frente. La app "Música BT" del fabricante manda PAUSA al celular cuando sales
     * con "atrás" (se vio en el registro: onBackPressed → tecla 70 dos veces). Si la música se pausó
     * en ese mismo instante, la reanudamos: tú no pediste pausarla.
     */
    fun onLauncherResumed() {
        lastLauncherResume = SystemClock.elapsedRealtime()
        if (lastLauncherResume - lastBtPauseAt < PAUSE_ON_EXIT_MS) undoExitPause()
    }

    /**
     * Elegiste otra fuente (la radio): durante [ms] Civic no vuelve a tomar el Bluetooth por su cuenta
     * (ni al ver que el celular suena, ni para deshacer una pausa).
     */
    fun yieldToOtherSource(ms: Long = 8_000) {
        yieldUntil = SystemClock.elapsedRealtime() + ms
        handler.removeCallbacksAndMessages(AUTOPLAY)
    }

    private var yieldUntil = 0L

    private fun undoExitPause() {
        // Solo si el audio sigue siendo del Bluetooth: si la pausa fue porque cambiaste a la radio,
        // no se toca.
        if (SystemClock.elapsedRealtime() < yieldUntil || SysProp.get("sys.sourcing.package") != VendorApps.BLUETOOTH) {
            lastBtPauseAt = 0L
            return
        }
        lastBtPauseAt = 0L
        android.util.Log.i(TAG, "Música BT pausó al salir: reanudando")
        handler.postDelayed({ resumeBluetooth() }, 400)
    }

    private val btCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            // Registro para diagnosticar pausas inesperadas (adb logcat -s CivicBT). El Bluetooth avisa
            // cada segundo (por el avance de la canción): solo se anota cuando cambia el estado.
            // ¿El celular PASÓ a sonar ahora mismo? (no solo "sigue sonando": al cambiar a la radio,
            // el Bluetooth todavía avisa "sonando" medio segundo y no hay que confundirlo).
            val becamePlaying = state?.state == PlaybackState.STATE_PLAYING && lastBtState != PlaybackState.STATE_PLAYING
            if (state?.state != lastBtState) {
                val wasPlaying = lastBtState == PlaybackState.STATE_PLAYING
                lastBtState = state?.state
                android.util.Log.i(TAG, "BT estado=${state?.state} fuente=${SysProp.get("sys.sourcing.package")}")
                if (wasPlaying && state?.state == PlaybackState.STATE_PAUSED) {
                    lastBtPauseAt = SystemClock.elapsedRealtime()
                    // La pausa llegó justo cuando volviste a Civic: es la de "Música BT" al salir.
                    if (lastBtPauseAt - lastLauncherResume < PAUSE_ON_EXIT_MS) undoExitPause()
                }
            }
            // El celular empezó a sonar (le diste play en el teléfono). Si la pantalla no tiene al
            // Bluetooth como fuente, lo pausaría enseguida: lo tomamos y confirmamos el play.
            if (becamePlaying && SysProp.get("sys.sourcing.package") != VendorApps.BLUETOOTH) {
                val now = SystemClock.elapsedRealtime()
                // Si acabas de elegir otra fuente (la radio), esa decisión manda.
                if (now - lastBtResume > 4_000 && now > yieldUntil) {
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

    /**
     * Toma la fuente y le pide al celular que suene. false si no hay celular conectado.
     * Si la sesión de Bluetooth todavía no aparece como "activa" (pasa justo al conectar), se pide
     * directo al servicio de música del Bluetooth (A2dpMediaBrowserService).
     */
    private fun resumeBluetooth(): Boolean {
        val c = bt ?: runCatching { msm.getActiveSessions(NotificationCollector.component(context)) }
            .getOrDefault(emptyList()).firstOrNull { it.packageName == BT_SESSION }
        if (c == null) return playThroughBrowser()
        android.util.Log.i(TAG, "tomando el Bluetooth y pidiendo play")
        claimBluetooth()
        // Un instante para que el gestor de fuentes cambie la ruta del audio antes del play.
        handler.postDelayed({ runCatching { c.transportControls.play() } }, 350)
        return true
    }

    /** Plan B: conectarse al servicio de música del Bluetooth para obtener su sesión y dar play. */
    private fun playThroughBrowser(): Boolean {
        if (!isPhoneAudioConnected()) return false
        android.util.Log.i(TAG, "sin sesión activa: pidiendo play al servicio A2DP")
        claimBluetooth()
        var browser: android.media.browse.MediaBrowser? = null
        browser = android.media.browse.MediaBrowser(
            context,
            android.content.ComponentName(BT_SESSION, "$BT_SESSION.a2dpsink.mbs.A2dpMediaBrowserService"),
            object : android.media.browse.MediaBrowser.ConnectionCallback() {
                override fun onConnected() {
                    runCatching { MediaController(context, browser!!.sessionToken).transportControls.play() }
                        .onFailure { android.util.Log.w(TAG, "play por el servicio falló", it) }
                    handler.postDelayed({ runCatching { browser?.disconnect() } }, 2_000)
                }
                override fun onConnectionFailed() {
                    android.util.Log.w(TAG, "no se pudo conectar al servicio A2DP")
                }
            },
            null,
        )
        runCatching { browser.connect() }.onFailure { return false }
        return true
    }

    /** ¿Hay un celular conectado como bocina (A2DP sink)? Lo dice el gestor de fuentes del fabricante. */
    @android.annotation.SuppressLint("MissingPermission", "WrongConstant")
    private fun isPhoneAudioConnected(): Boolean = phoneConnected || runCatching {
        // 11 = BluetoothProfile.A2DP_SINK (oculto en la API pública).
        android.bluetooth.BluetoothAdapter.getDefaultAdapter()?.getProfileConnectionState(11) ==
            android.bluetooth.BluetoothAdapter.STATE_CONNECTED
    }.getOrDefault(false)

    /** Lo actualiza [PhoneWatcher] con cada conexión/desconexión del celular. */
    @Volatile var phoneConnected = false

    /**
     * El celular se acaba de conectar por Bluetooth: que la música empiece sola, como en cualquier
     * carro. Justo al conectar, el celular a veces ignora el play (se vio en el registro), así que se
     * insiste unas veces hasta que de verdad suene.
     */
    fun onPhoneConnected() {
        phoneConnected = true
        refresh()
        val delays = longArrayOf(3_000, 6_000, 10_000, 15_000)
        handler.removeCallbacksAndMessages(AUTOPLAY)
        val start = SystemClock.uptimeMillis()
        delays.forEachIndexed { i, d ->
            handler.postAtTime({
                if (lastBtState == PlaybackState.STATE_PLAYING) return@postAtTime
                // Si estás escuchando la radio (o la acabas de elegir), conectar el celular no la quita.
                if (SystemClock.elapsedRealtime() < yieldUntil || SysProp.get("sys.sourcing.package") == VendorApps.RADIO) return@postAtTime
                val ok = resumeBluetooth()
                android.util.Log.i(TAG, "celular conectado: intento ${i + 1}, pedido=$ok")
            }, AUTOPLAY, start + d)
        }
    }

    fun onPhoneDisconnected() {
        phoneConnected = false
        handler.removeCallbacksAndMessages(AUTOPLAY)
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
            // El Bluetooth avisa el avance cada segundo. La barra ya avanza sola con positionAt, así
            // que si la canción y el estado son los mismos y la posición cuadra, no se avisa nada:
            // antes la tarjeta de música se volvía a armar cada segundo.
            val previous = (_state.value as? MediaState.Active)?.now
            if (previous != null && sameExceptProgress(previous, base)) return
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

    /**
     * ¿Es lo mismo que ya se muestra, salvo el avance que la barra ya calcula sola? Se compara la
     * posición que la barra estaría marcando ahora con la que manda la app (menos de 1.5 s de
     * diferencia = no hubo salto, solo el reloj).
     */
    private fun sameExceptProgress(a: NowPlaying, b: NowPlaying): Boolean {
        if (a.copy(positionMs = null, positionAt = 0, art = null, artColor = null) != b.copy(positionMs = null, positionAt = 0, art = null, artColor = null)) return false
        val pa = a.positionMs ?: return b.positionMs == null
        val pb = b.positionMs ?: return false
        val expected = if (a.playing) pa + ((b.positionAt - a.positionAt) * a.speed).toLong() else pa
        return kotlin.math.abs(expected - pb) < 1_500
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
        const val TAG = "CivicBT"
        /** Ventana para decidir que una pausa fue la de "Música BT" al salir (se vieron ~50 ms). */
        const val PAUSE_ON_EXIT_MS = 1_500L
        /** Marca para poder cancelar los reintentos de reproducir al conectar. */
        val AUTOPLAY = Any()
    }
}
