package com.civiclauncher.app.launcher.ui

import android.os.SystemClock
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
import com.civiclauncher.app.ui.design.bevelBorder
import com.civiclauncher.app.ui.design.Tone
import com.civiclauncher.app.ui.design.color
import kotlinx.coroutines.delay

/**
 * Tarjeta de música de Inicio: encabezado, portada, canción, avance y controles.
 * Sin música: accesos grandes a cada fuente, con la última que usaste resaltada.
 * Fondo: degradado OPACO del color de la portada hacia la superficie (una sola capa, barato).
 */
@Composable
fun MusicCard(modifier: Modifier = Modifier, onOpen: () -> Unit) {
    val repo = LocalContext.current.container.media
    val state by repo.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { repo.refresh() }

    val surface = Civic.colors.surface
    val tint = (state as? MediaState.Active)?.now?.artColor
    val top = if (tint != null) lerp(surface, tint, if (Civic.colors.isDark) 0.30f else 0.22f) else lerp(surface, Civic.colors.surfaceRaised, 0.6f)

    HomeCard(modifier, Brush.verticalGradient(listOf(top, surface))) {
        when (val s = state) {
            MediaState.NoPermission -> {
                CardHeader(P.MusicNotes, "Música", "Sin permiso", Tone.Warn)
                NoPermission()
            }
            is MediaState.Idle -> {
                if (s.source == MusicSource.ANDROID_AUTO) {
                    CardHeader(P.MusicNotes, "Música", "Android Auto", Tone.Accent)
                    AutoKitPlaying()
                } else {
                    CardHeader(P.MusicNotes, "Música", "Sin música", Tone.Neutral)
                    Sources()
                }
            }
            is MediaState.Active -> {
                CardHeader(P.MusicNotes, "Música", sourceLabel(s.now.source), sourceTone(s.now.source))
                Active(s.now, onOpen)
            }
        }
    }
}

@Composable
private fun ColumnScope.NoPermission() {
    val ctx = LocalContext.current
    Spacer(Modifier.weight(1f))
    CoverCircle(null, P.MusicNotes, 104.dp)
    Spacer(Modifier.height(Civic.space.l))
    CenteredTitle("Tu música aquí")
    CenteredSubtitle("Android pide un permiso para que Civic vea la canción.")
    Spacer(Modifier.weight(1f))
    CivicButton("Dar permiso", {
        runCatching { ctx.startActivity(NotificationCollector.settingsIntent()) }
            .onFailure { Toast.makeText(ctx, "Esta pantalla no tiene ese ajuste", Toast.LENGTH_SHORT).show() }
    }, Modifier.fillMaxWidth(), icon = P.CheckCircle)
}

/** Fase 0: AutoKit no comparte la canción, pero sí acepta los controles. */
@Composable
private fun ColumnScope.AutoKitPlaying() {
    Spacer(Modifier.weight(1f))
    CoverCircle(null, P.NavigationArrowFill, 104.dp)
    Spacer(Modifier.height(Civic.space.l))
    CenteredTitle("Android Auto")
    CenteredSubtitle("La canción se ve en Android Auto")
    Spacer(Modifier.weight(1f))
    Controls(playing = null)
}

/** Sin música: cuatro accesos grandes. La última fuente que usaste aparece resaltada. */
@Composable
private fun ColumnScope.Sources() {
    val ctx = LocalContext.current
    val c = ctx.container
    val last by c.media.lastSource.collectAsStateWithLifecycle()
    fun open(pkg: String) {
        if (!c.apps.launchPackage(pkg)) Toast.makeText(ctx, "No está instalada en esta pantalla", Toast.LENGTH_SHORT).show()
    }
    Spacer(Modifier.height(Civic.space.m))
    Text(
        last?.let { "La última vez: ${it.label}" } ?: "¿Qué quieres escuchar?",
        style = Civic.type.bodySmall,
        color = Civic.colors.textDim,
    )
    Spacer(Modifier.height(Civic.space.m))
    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Civic.space.s)) {
        SourceTile(P.NavigationArrowFill, "Android Auto", last == MusicSource.ANDROID_AUTO, Modifier.weight(1f)) { open(VendorApps.ANDROID_AUTO) }
        // Con el celular conectado suena directo; si no, se abre la app para conectarlo.
        SourceTile(P.BluetoothFill, "Bluetooth", last == MusicSource.BLUETOOTH, Modifier.weight(1f)) {
            if (!c.media.playBluetooth()) openMusicApp(ctx, MusicSource.BLUETOOTH, VendorApps.BLUETOOTH)
        }
    }
    Spacer(Modifier.height(Civic.space.s))
    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Civic.space.s)) {
        SourceTile(P.RadioFill, "Radio", last == MusicSource.RADIO, Modifier.weight(1f)) { open(VendorApps.RADIO) }
        SourceTile(P.MusicNotes, "USB", last == MusicSource.USB, Modifier.weight(1f)) { open(VendorApps.MUSIC) }
    }
}

@Composable
internal fun SourceTile(icon: ImageVector, label: String, highlighted: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val c = Civic.colors
    val shape = RoundedCornerShape(Civic.radii.l)
    Column(
        modifier
            .fillMaxSize()
            .clip(shape)
            .background(
                when {
                    pressed -> c.surfacePressed
                    highlighted -> lerp(c.surfaceRaised, c.accent, 0.22f)
                    else -> c.surfaceRaised
                },
            )
            .bevelBorder(shape)
            .clickable(source, indication = null, role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = if (highlighted) c.text else c.accent, modifier = Modifier.size(30.dp))
        Spacer(Modifier.height(Civic.space.s))
        Text(label, style = Civic.type.label, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ColumnScope.Active(now: NowPlaying, onOpen: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    // Tocar la portada o la canción abre el reproductor de Civic a pantalla completa.
    Column(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .clip(RoundedCornerShape(Civic.radii.l))
            .background(if (pressed) Civic.colors.surfaceRaised else Color.Transparent)
            .clickable(source, indication = null, role = Role.Button, onClickLabel = "Abrir el reproductor", onClick = onOpen),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CoverCircle(now.art, sourceIcon(now.source), 112.dp)
        Spacer(Modifier.height(Civic.space.l))
        CenteredTitle(now.title.orEmpty())
        CenteredSubtitle(now.artist.orEmpty())
    }
    Spacer(Modifier.height(Civic.space.s))
    Progress(now)
    Spacer(Modifier.height(Civic.space.s))
    Controls(playing = now.playing)
}


@Composable
private fun ColumnScope.CenteredTitle(text: String) {
    Text(
        text,
        style = Civic.type.title.copy(fontSize = 24.sp, lineHeight = 30.sp),
        color = Civic.colors.text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun CenteredSubtitle(text: String) {
    Text(
        text,
        style = Civic.type.bodySmall,
        color = Civic.colors.textDim,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Portada en "squircle" (cuadro de esquinas muy redondeadas). Sin portada: contenedor oscuro con
 * borde biselado y el ícono en el color de acento, en lugar de un degradado que rompa la paleta.
 */
@Composable
private fun ColumnScope.CoverCircle(art: androidx.compose.ui.graphics.ImageBitmap?, icon: ImageVector, size: Dp, iconTint: Color? = null) {
    val shape = RoundedCornerShape(size * 0.28f)
    Box(
        Modifier
            .align(Alignment.CenterHorizontally)
            .size(size)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(lerp(Civic.colors.surfaceRaised, Civic.colors.text, 0.04f), Civic.colors.surfaceRaised)))
            .bevelBorder(shape),
        contentAlignment = Alignment.Center,
    ) {
        if (art != null) {
            Image(art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(icon, null, tint = iconTint ?: Civic.colors.accent, modifier = Modifier.size(size * 0.40f))
        }
    }
}

/** Barra de avance: se mueve sola entre actualizaciones (las apps solo avisan al cambiar algo). */
@Composable
private fun Progress(now: NowPlaying) {
    val duration = now.durationMs ?: return
    val start = now.positionMs ?: return
    var tick by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(now.playing, now.positionAt) {
        while (now.playing) {
            tick = SystemClock.elapsedRealtime()
            delay(1_000)
        }
    }
    val pos = (if (now.playing) start + ((tick - now.positionAt) * now.speed).toLong() else start).coerceIn(0, duration)
    Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(50)).background(Civic.colors.surfacePressed)) {
        Box(Modifier.fillMaxWidth(pos.toFloat() / duration).height(5.dp).clip(RoundedCornerShape(50)).background(Civic.colors.text))
    }
}

/** [playing] null = no sabemos el estado (AutoKit). */
@Composable
private fun Controls(playing: Boolean?) {
    val repo = LocalContext.current.container.media
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        RoundButton(P.SkipBackFill, "Anterior", 60.dp, null) { repo.previous() }
        RoundButton(
            if (playing == true) P.PauseFill else P.PlayFill,
            if (playing == true) "Pausa" else "Reproducir",
            76.dp,
            Civic.colors.surfacePressed,
        ) { repo.playPause() }
        RoundButton(P.SkipForwardFill, "Siguiente", 60.dp, null) { repo.next() }
    }
}

@Composable
internal fun RoundButton(icon: ImageVector, label: String, size: Dp, bg: Color?, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val base = bg ?: Civic.colors.surface.copy(alpha = 0f)
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (pressed) Civic.colors.surfaceRaised else base)
            .clickable(source, indication = null, role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = Civic.colors.text, modifier = Modifier.size(size * 0.45f))
    }
}

/**
 * Abre la app de la música que suena. El Bluetooth abre directo la pantalla de música del
 * fabricante (su ícono principal abre los ajustes del Bluetooth).
 */
internal fun openMusicApp(ctx: android.content.Context, source: MusicSource, pkg: String) {
    val opened = when (source) {
        MusicSource.BLUETOOTH -> runCatching {
            ctx.startActivity(
                android.content.Intent()
                    .setClassName(VendorApps.BLUETOOTH, "${VendorApps.BLUETOOTH}.BtMusicActivity")
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        }.getOrDefault(false) || ctx.container.apps.launchPackage(VendorApps.BLUETOOTH)
        else -> ctx.container.apps.launchPackage(pkg)
    }
    if (!opened) Toast.makeText(ctx, "No está instalada en esta pantalla", Toast.LENGTH_SHORT).show()
}

internal fun sourceIcon(s: MusicSource) = when (s) {
    MusicSource.ANDROID_AUTO -> P.NavigationArrowFill
    MusicSource.BLUETOOTH -> P.BluetoothFill
    MusicSource.RADIO -> P.RadioFill
    else -> P.MusicNotes
}

/**
 * Tarjeta base de Inicio: esquinas grandes, degradado opaco y un borde fino que da el efecto
 * "cristal" del diseño sin transparencias reales.
 */
@Composable
fun HomeCard(
    modifier: Modifier = Modifier,
    background: Brush? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Civic.colors
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val brush = background ?: Brush.verticalGradient(listOf(lerp(c.surface, c.surfaceRaised, 0.6f), c.surface))
    Column(
        modifier
            .clip(RoundedCornerShape(Civic.radii.xl))
            .background(if (pressed) Brush.verticalGradient(listOf(c.surfacePressed, c.surfacePressed)) else brush)
            .bevelBorder(RoundedCornerShape(Civic.radii.xl))
            .let { if (onClick != null) it.clickable(source, indication = null, role = Role.Button, onClick = onClick) else it }
            .padding(Civic.space.xl),
        content = content,
    )
}

/**
 * Encabezado común de las tarjetas de Inicio: ícono, título y una cápsula de estado a la derecha.
 * Así Mi vehículo, Mapa y Música se leen igual.
 */
@Composable
fun CardHeader(icon: ImageVector, title: String, status: String?, tone: Tone) {
    Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Civic.colors.text, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(Civic.space.m))
        Text(title, style = Civic.type.title, color = Civic.colors.text, maxLines = 1, softWrap = false)
        Spacer(Modifier.weight(1f).widthIn(min = Civic.space.s))
        if (status != null) {
            // La cápsula cede espacio (se recorta con "…") antes que el título.
            Row(
                Modifier
                    .widthIn(max = 124.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Civic.colors.surfaceRaised)
                    .padding(horizontal = Civic.space.m, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(tone.color()))
                Spacer(Modifier.width(6.dp))
                Text(status, style = Civic.type.caption, color = Civic.colors.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

internal fun sourceLabel(s: MusicSource) = if (s == MusicSource.APP) "Sonando" else s.label

internal fun sourceTone(s: MusicSource) = when (s) {
    MusicSource.ANDROID_AUTO -> Tone.Accent
    MusicSource.BLUETOOTH -> Tone.Cool
    MusicSource.RADIO -> Tone.Warn
    MusicSource.USB -> Tone.Ok
    MusicSource.APP -> Tone.Neutral
}