package com.civiclauncher.app.launcher.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.civiclauncher.app.container
import com.civiclauncher.app.launcher.data.VendorApps
import com.civiclauncher.app.launcher.media.MusicSource
import com.civiclauncher.app.launcher.media.RadioInfo
import com.civiclauncher.app.ui.design.Civic
import com.civiclauncher.app.ui.design.PhosphorIcons as P
import com.civiclauncher.app.ui.design.bevelBorder

/**
 * Pregunta la frecuencia a la radio cada segundo, pero solo mientras esto se ve: no con Inicio
 * escondido detrás de otra pantalla, ni en Android Auto, ni con la pantalla apagada.
 */
@Composable
internal fun WatchRadio() {
    val radio = LocalContext.current.container.radio
    val active = LocalScreenActive.current
    androidx.lifecycle.compose.LifecycleStartEffect(active) {
        if (active) radio.startWatching()
        onStopOrDispose { if (active) radio.stopWatching() }
    }
}

/**
 * Radio de Civic: la frecuencia grande, un dial con aguja y los controles (buscar estación hacia
 * atrás / adelante, silencio). Para guardar o elegir estaciones guardadas se abre la app del
 * fabricante con "Estaciones". Al entrar, la radio se vuelve la fuente de audio.
 */
@Composable
fun RadioScreen(onOpen: (Dest) -> Unit) {
    val ctx = LocalContext.current
    val c = ctx.container
    val radio = c.radio
    val info by radio.info.collectAsStateWithLifecycle()
    WatchRadio()
    // Entrar a la radio = escuchar la radio.
    LaunchedEffect(Unit) { radio.activate() }

    // Misma estructura que el reproductor de música: arriba regresar + acción; en medio el
    // contenido; abajo el selector de fuente compacto y centrado.
    PlayerFrame(tint = RADIO_TINT) {
        PlayerTopBar(onBack = { onOpen(Dest.HOME) }) {
            PlayerHeaderAction("Estaciones") {
                if (!radio.openStations()) Toast.makeText(ctx, "La radio no está instalada", Toast.LENGTH_SHORT).show()
            }
        }
        Row(Modifier.weight(1f).fillMaxWidth().padding(vertical = Civic.space.s), verticalAlignment = Alignment.CenterVertically) {
            DialPanel(info, Modifier.weight(1.1f).fillMaxHeight())
            Spacer(Modifier.width(Civic.space.xl))
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Spacer(Modifier.weight(1f))
                Text(
                    if (info.isSource) "En el aire" else "Encendiendo…",
                    style = Civic.type.headline.copy(fontSize = 32.sp, letterSpacing = (-0.02).em),
                    color = Civic.colors.text,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (info.isSource) "Usa las flechas para cambiar de estación" else "Un momento, cambiando a la radio",
                    style = Civic.type.body,
                    color = Civic.colors.textDim,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Spacer(Modifier.weight(1f))
                PlayerControls(
                    playing = null,
                    onPrevious = radio::seekDown,
                    onPlayPause = radio::playPause,
                    onNext = radio::seekUp,
                    previousIcon = P.SkipBackFill,
                    nextIcon = P.SkipForwardFill,
                    previousLabel = "Estación anterior",
                    nextLabel = "Estación siguiente",
                )
                Spacer(Modifier.weight(0.4f))
            }
        }
        Spacer(Modifier.height(Civic.space.m))
        SourceSwitch(
            current = MusicSource.RADIO,
            onBluetooth = {
                if (c.media.playBluetooth()) onOpen(Dest.MUSIC)
                else openMusicApp(ctx, MusicSource.BLUETOOTH, VendorApps.BLUETOOTH)
            },
            onRadio = {},
            modifier = Modifier.align(Alignment.CenterHorizontally).width(380.dp).height(50.dp),
        )
    }
}

/** Tono cálido para el fondo de la radio (no hay portada de la que sacar color). */
private val RADIO_TINT = androidx.compose.ui.graphics.Color(0xFFB3412F)

/** Panel de vidrio con la frecuencia grande y el dial. */
@Composable
private fun DialPanel(info: RadioInfo, modifier: Modifier) {
    val shape = RoundedCornerShape(Civic.radii.xl)
    Column(
        modifier
            .clip(shape)
            .background(Civic.colors.surface)
            .bevelBorder(shape)
            .padding(Civic.space.xl),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            (info.band ?: "FM").let { "$it · Estéreo" }.uppercase(),
            style = Civic.type.caption.copy(letterSpacing = 0.12.em),
            color = Civic.colors.textDim,
        )
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                info.frequency ?: if (info.band == "AM") "----" else "--.-",
                // Letra ligera y números del mismo ancho, como el tacómetro de Inicio: elegante y sin "brincos".
                style = Civic.type.clock.copy(fontSize = 116.sp, lineHeight = 116.sp, fontWeight = FontWeight.Light, letterSpacing = (-0.03).em, fontFeatureSettings = "tnum"),
                color = Civic.colors.text,
                maxLines = 1,
            )
            Spacer(Modifier.width(Civic.space.s))
            Text(
                info.unit ?: "MHz",
                style = Civic.type.title,
                color = Civic.colors.textDim,
                modifier = Modifier.padding(bottom = 18.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        Dial(info.dial, info.band == "AM")
    }
}

/**
 * Dial: rayitas cada paso, más largas en los números redondos, y una aguja en la frecuencia actual.
 * Se dibuja con primitivas en un solo Canvas (barato).
 */
@Composable
private fun Dial(position: Float?, am: Boolean) {
    val tick = Civic.colors.textFaint
    val major = Civic.colors.textDim
    val needle = Civic.colors.text
    // La aguja se desliza al cambiar de estación (se lee en el dibujo: no recompone la pantalla).
    val animated by androidx.compose.animation.core.animateFloatAsState(position ?: 0f, androidx.compose.animation.core.tween(450), label = "dial")
    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .drawBehind {
                    val n = 41
                    val step = size.width / (n - 1)
                    for (i in 0 until n) {
                        val big = i % 5 == 0
                        val h = if (big) size.height * 0.62f else size.height * 0.32f
                        drawRoundRect(
                            if (big) major else tick,
                            topLeft = Offset(i * step - 1f, size.height - h),
                            size = Size(2f, h),
                            cornerRadius = CornerRadius(1f),
                        )
                    }
                    if (position != null) {
                        // Aguja de luz: halo suave y una línea clara, igual que la del tacómetro.
                        val x = animated * size.width
                        drawRoundRect(RADIO_TINT.copy(alpha = 0.45f), Offset(x - 6f, -4f), Size(12f, size.height + 8f), CornerRadius(6f))
                        drawRoundRect(needle, Offset(x - 1.5f, -4f), Size(3f, size.height + 8f), CornerRadius(1.5f))
                    }
                },
        )
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val labels = if (am) listOf("530", "830", "1120", "1420", "1710") else listOf("88", "93", "98", "103", "108")
            labels.forEach { Text(it, style = Civic.type.caption, color = Civic.colors.textFaint) }
        }
    }
}
