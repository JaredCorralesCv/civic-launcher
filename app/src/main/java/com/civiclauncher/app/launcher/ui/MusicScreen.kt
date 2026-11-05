package com.civiclauncher.app.launcher.ui

import android.os.SystemClock
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.civiclauncher.app.container
import com.civiclauncher.app.diag.notif.NotificationCollector
import com.civiclauncher.app.launcher.data.VendorApps
import com.civiclauncher.app.launcher.media.MediaState
import com.civiclauncher.app.launcher.media.MusicSource
import com.civiclauncher.app.launcher.media.NowPlaying
import com.civiclauncher.app.ui.design.ButtonKind
import com.civiclauncher.app.ui.design.Civic
import com.civiclauncher.app.ui.design.CivicButton
import com.civiclauncher.app.ui.design.PhosphorIcons as P
import com.civiclauncher.app.ui.design.StatusPill
import com.civiclauncher.app.ui.design.Tone
import com.civiclauncher.app.ui.design.bevelBorder
import kotlinx.coroutines.delay

/**
 * Reproductor de Civic a pantalla completa (estilo CarPlay): portada grande, canción, avance con
 * tiempos, controles grandes y las fuentes abajo. Sirve para cualquier música que comparta sus datos
 * (Bluetooth del celular, Spotify en la pantalla, USB). Las apps del fabricante solo se abren si
 * de verdad hacen falta (buscar estaciones, conectar el celular…).
 */
@Composable
fun MusicScreen() {
    val c = LocalContext.current.container
    val state by c.media.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { c.media.refresh() }

    val now = (state as? MediaState.Active)?.now
    val surface = Civic.colors.background
    val tint = now?.artColor
    val top = if (tint != null) lerp(surface, tint, if (Civic.colors.isDark) 0.35f else 0.25f) else lerp(surface, Civic.colors.surfaceRaised, 0.7f)

    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(top, surface)))
            .padding(horizontal = Civic.space.xl, vertical = Civic.space.l),
    ) {
        Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            when (val s = state) {
                is MediaState.Active -> Playing(s.now)
                is MediaState.Idle -> if (s.source == MusicSource.ANDROID_AUTO) AndroidAutoPlaying() else Nothing()
                MediaState.NoPermission -> NeedsPermission()
            }
        }
        Spacer(Modifier.height(Civic.space.l))
        SourcesBar(current = now?.source ?: (state as? MediaState.Idle)?.source)
    }
}

// ------------------------------------------------------------------ Estados

@Composable
private fun androidx.compose.foundation.layout.RowScope.Playing(now: NowPlaying) {
    BigCover(now.art, sourceIcon(now.source))
    Spacer(Modifier.width(Civic.space.xxl))
    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusPill(sourceLabel(now.source), sourceTone(now.source))
            Spacer(Modifier.weight(1f))
            OpenAppButton(now.source, now.pkg)
        }
        Spacer(Modifier.weight(1f))
        Text(
            now.title.orEmpty(),
            style = Civic.type.headline.copy(fontSize = 34.sp, lineHeight = 42.sp),
            color = Civic.colors.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(Civic.space.s))
        Text(now.artist.orEmpty(), style = Civic.type.title, color = Civic.colors.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        // El álbum solo si dice algo distinto de la canción (en sencillos suelen ser iguales).
        now.album?.takeIf { it.isNotBlank() && it != now.title }?.let {
            Text(it, style = Civic.type.bodySmall, color = Civic.colors.textFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.weight(1f))
        TimedProgress(now)
        Spacer(Modifier.height(Civic.space.l))
        BigControls(playing = now.playing)
    }
}

/** AutoKit no comparte la canción, pero sí acepta los controles. */
@Composable
private fun androidx.compose.foundation.layout.RowScope.AndroidAutoPlaying() {
    BigCover(null, P.NavigationArrowFill)
    Spacer(Modifier.width(Civic.space.xxl))
    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusPill("Android Auto", Tone.Accent)
            Spacer(Modifier.weight(1f))
            OpenAppButton(MusicSource.ANDROID_AUTO, VendorApps.ANDROID_AUTO)
        }
        Spacer(Modifier.weight(1f))
        Text("Android Auto", style = Civic.type.headline, color = Civic.colors.text)
        Spacer(Modifier.height(Civic.space.s))
        Text("La canción se ve en Android Auto. Desde aquí puedes pausar o cambiarla.", style = Civic.type.body, color = Civic.colors.textDim)
        Spacer(Modifier.weight(1f))
        BigControls(playing = null)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Nothing() {
    BigCover(null, P.MusicNotes)
    Spacer(Modifier.width(Civic.space.xxl))
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
        Text("Nada sonando", style = Civic.type.headline, color = Civic.colors.text)
        Spacer(Modifier.height(Civic.space.s))
        Text(
            "Elige abajo de dónde quieres escuchar. Con el celular conectado por Bluetooth, la música empieza sola.",
            style = Civic.type.body,
            color = Civic.colors.textDim,
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.NeedsPermission() {
    val ctx = LocalContext.current
    BigCover(null, P.MusicNotes)
    Spacer(Modifier.width(Civic.space.xxl))
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
        Text("Tu música aquí", style = Civic.type.headline, color = Civic.colors.text)
        Spacer(Modifier.height(Civic.space.s))
        Text("Android pide un permiso para que Civic vea la canción y la controle.", style = Civic.type.body, color = Civic.colors.textDim)
        Spacer(Modifier.height(Civic.space.xl))
        CivicButton("Dar permiso", {
            runCatching { ctx.startActivity(NotificationCollector.settingsIntent()) }
                .onFailure { Toast.makeText(ctx, "Esta pantalla no tiene ese ajuste", Toast.LENGTH_SHORT).show() }
        }, icon = P.CheckCircle)
    }
}

// ------------------------------------------------------------------ Piezas

/** Portada grande en "squircle". Sin portada: el ícono de la fuente sobre la superficie. */
@Composable
private fun BigCover(art: ImageBitmap?, icon: ImageVector) {
    val shape = RoundedCornerShape(36.dp)
    Box(
        Modifier
            .fillMaxHeight()
            .aspectRatio(1f)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(lerp(Civic.colors.surfaceRaised, Civic.colors.text, 0.05f), Civic.colors.surfaceRaised)))
            .bevelBorder(shape),
        contentAlignment = Alignment.Center,
    ) {
        if (art != null) {
            Image(art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(icon, null, tint = Civic.colors.accent, modifier = Modifier.fillMaxSize(0.34f))
        }
    }
}

/** Barra de avance con tiempos; se mueve sola entre avisos de la app. */
@Composable
private fun TimedProgress(now: NowPlaying) {
    val duration = now.durationMs
    val start = now.positionMs
    if (duration == null || start == null) return
    var tick by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(now.playing, now.positionAt) {
        while (now.playing) {
            tick = SystemClock.elapsedRealtime()
            delay(1_000)
        }
    }
    val pos = (if (now.playing) start + ((tick - now.positionAt) * now.speed).toLong() else start).coerceIn(0, duration)
    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(Civic.colors.surfacePressed)) {
        Box(Modifier.fillMaxWidth(pos.toFloat() / duration).height(6.dp).clip(RoundedCornerShape(50)).background(Civic.colors.text))
    }
    Spacer(Modifier.height(Civic.space.s))
    Row(Modifier.fillMaxWidth()) {
        Text(formatTime(pos), style = Civic.type.caption, color = Civic.colors.textDim)
        Spacer(Modifier.weight(1f))
        Text("-" + formatTime(duration - pos), style = Civic.type.caption, color = Civic.colors.textDim)
    }
}

private fun formatTime(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** [playing] null = no sabemos el estado (AutoKit). */
@Composable
private fun BigControls(playing: Boolean?) {
    val repo = LocalContext.current.container.media
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        RoundButton(P.SkipBackFill, "Anterior", 76.dp, null) { repo.previous() }
        RoundButton(
            if (playing == true) P.PauseFill else P.PlayFill,
            if (playing == true) "Pausa" else "Reproducir",
            96.dp,
            Civic.colors.surfacePressed,
        ) { repo.playPause() }
        RoundButton(P.SkipForwardFill, "Siguiente", 76.dp, null) { repo.next() }
    }
}

/** Para lo que Civic no hace (buscar estaciones, listas de Spotify…): abre la app de esa música. */
@Composable
private fun OpenAppButton(source: MusicSource, pkg: String) {
    val ctx = LocalContext.current
    // La música de prueba sale del propio Civic: no hay otra app que abrir.
    if (pkg == ctx.packageName) return
    val label = when (source) {
        MusicSource.BLUETOOTH -> "Música BT"
        MusicSource.ANDROID_AUTO -> "Abrir Android Auto"
        MusicSource.RADIO -> "Estaciones"
        MusicSource.USB -> "Archivos"
        MusicSource.APP -> ctx.container.apps.labelOf(pkg) ?: "Abrir app"
    }
    CivicButton(label, { openMusicApp(ctx, source, pkg) }, kind = ButtonKind.Secondary, icon = P.SquaresFour, compact = true)
}

/** Fuentes: cambia lo que suena sin salir de Civic (salvo las que necesitan su propia app). */
@Composable
private fun SourcesBar(current: MusicSource?) {
    val ctx = LocalContext.current
    val c = ctx.container
    Row(Modifier.fillMaxWidth().height(72.dp), horizontalArrangement = Arrangement.spacedBy(Civic.space.m)) {
        SourceChip(P.BluetoothFill, "Bluetooth", current == MusicSource.BLUETOOTH, Modifier.weight(1f)) {
            if (!c.media.playBluetooth()) openMusicApp(ctx, MusicSource.BLUETOOTH, VendorApps.BLUETOOTH)
        }
        SourceChip(P.RadioFill, "Radio", current == MusicSource.RADIO, Modifier.weight(1f)) {
            openMusicApp(ctx, MusicSource.RADIO, VendorApps.RADIO)
        }
        SourceChip(P.MusicNotes, "USB", current == MusicSource.USB, Modifier.weight(1f)) {
            openMusicApp(ctx, MusicSource.USB, VendorApps.MUSIC)
        }
        SourceChip(P.NavigationArrowFill, "Android Auto", current == MusicSource.ANDROID_AUTO, Modifier.weight(1f)) {
            openMusicApp(ctx, MusicSource.ANDROID_AUTO, VendorApps.ANDROID_AUTO)
        }
    }
}

@Composable
private fun SourceChip(icon: ImageVector, label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val c = Civic.colors
    val shape = RoundedCornerShape(Civic.radii.l)
    Row(
        modifier
            .fillMaxHeight()
            .clip(shape)
            .background(
                when {
                    pressed -> c.surfacePressed
                    selected -> lerp(c.surfaceRaised, c.accent, 0.25f)
                    else -> c.surface
                },
            )
            .bevelBorder(shape)
            .clickable(source, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = Civic.space.l),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = if (selected) c.text else c.accent, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(Civic.space.m))
        Text(label, style = Civic.type.label, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
