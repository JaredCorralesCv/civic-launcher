package com.civiclauncher.app.launcher.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.civiclauncher.app.AppContainer
import com.civiclauncher.app.container
import com.civiclauncher.app.launcher.media.MediaState
import com.civiclauncher.app.launcher.media.MusicSource

/**
 * Lo que suena, en cualquier fuente, listo para las piezas chicas (barra Sport, widget de música).
 * Antes esas piezas solo entendían "hay canción" o "no hay": con la radio o Android Auto sonando
 * decían "Sin música", y el play le quitaba el audio a la radio.
 */
@Immutable
internal data class NowLine(
    val title: String,
    val subtitle: String,
    val source: MusicSource?,
    /** true sonando, false en pausa, null si no se sabe (radio, Android Auto). */
    val playing: Boolean?,
    /** Anterior/siguiente tienen a quién mandarse. */
    val canSkip: Boolean,
    val art: ImageBitmap?,
    val color: Color?,
)

private val RADIO_GLOW = Color(0xFFD42F3A)

/** "Celular conectado: …", "Sin celular conectado", o null si esta pantalla no deja saberlo. */
@android.annotation.SuppressLint("MissingPermission")
private fun phoneLinkText(context: android.content.Context): String? = runCatching {
    val adapter = (context.getSystemService(android.content.Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter ?: return null
    if (!adapter.isEnabled) return "Bluetooth apagado"
    // isConnected() no es pública, pero existe en todos los Android de estas pantallas.
    val isConnected = android.bluetooth.BluetoothDevice::class.java.getMethod("isConnected")
    val phone = adapter.bondedDevices.orEmpty().firstOrNull { isConnected.invoke(it) == true }
    if (phone != null) "Celular conectado: ${phone.name ?: "sin nombre"}" else "Sin celular conectado"
}.getOrNull()

/** Se pregunta cada pocos segundos, y solo mientras la pantalla que lo muestra está a la vista. */
@Composable
internal fun rememberPhoneLink(): String? {
    val context = LocalContext.current
    val active = LocalScreenActive.current
    val text = androidx.compose.runtime.produceState<String?>(null, active) {
        while (active) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { phoneLinkText(context) }
            kotlinx.coroutines.delay(5_000)
        }
    }
    return text.value
}

@Composable
internal fun rememberNowLine(): NowLine {
    val c = LocalContext.current.container
    val state by c.media.state.collectAsStateWithLifecycle()
    val connecting by c.media.connecting.collectAsStateWithLifecycle()
    return when (val s = state) {
        is MediaState.Active -> NowLine(
            title = s.now.title.orEmpty(),
            subtitle = s.now.artist ?: sourceLabel(s.now.source),
            source = s.now.source,
            playing = s.now.playing,
            canSkip = true,
            art = s.now.art,
            color = s.now.artColor,
        )
        is MediaState.Idle -> when (s.source) {
            MusicSource.RADIO -> {
                // La frecuencia se pregunta solo mientras esto se ve y la radio es la fuente.
                WatchRadio()
                val info by c.radio.info.collectAsStateWithLifecycle()
                val freq = info.frequency
                NowLine(
                    title = freq?.let { "$it ${info.unit.orEmpty()}".trim() } ?: "Radio",
                    // Sin frecuencia (el servicio de la radio no contestó) no se repite "Radio".
                    subtitle = if (freq != null) "Radio" + (info.band?.let { " · $it" } ?: "") else "Cambia de estación con las flechas",
                    source = MusicSource.RADIO, playing = null, canSkip = true, art = null, color = RADIO_GLOW,
                )
            }
            MusicSource.ANDROID_AUTO -> NowLine(
                "Android Auto", "La canción se ve en Android Auto", MusicSource.ANDROID_AUTO, null, true, null, null,
            )
            else -> idleLine(connecting)
        }
        MediaState.NoPermission -> NowLine("Música", "Falta un permiso · toca para darlo", null, false, false, null, null)
    }
}

private fun idleLine(connecting: Boolean) =
    if (connecting) NowLine("Conectando…", "Pidiendo música al celular", MusicSource.BLUETOOTH, null, false, null, null)
    else NowLine("Sin música", "Toca para elegir", null, false, false, null, null)

/** Play/pausa según lo que suena: la radio a la radio, la canción a su app, y sin nada, el celular. */
internal fun playPauseAny(c: AppContainer, onOpen: () -> Unit) {
    when (val s = c.media.state.value) {
        is MediaState.Active -> c.media.playPause()
        is MediaState.Idle -> when (s.source) {
            MusicSource.RADIO -> c.radio.playPause()
            MusicSource.ANDROID_AUTO -> c.media.playPause()
            else -> if (!c.media.playBluetooth()) onOpen()
        }
        MediaState.NoPermission -> onOpen()
    }
}

/** Anterior/siguiente: en la radio cambia de estación. */
internal fun skipAny(c: AppContainer, next: Boolean) {
    val s = c.media.state.value
    if (s is MediaState.Idle && s.source == MusicSource.RADIO) {
        if (next) c.radio.seekUp() else c.radio.seekDown()
    } else {
        if (next) c.media.next() else c.media.previous()
    }
}
