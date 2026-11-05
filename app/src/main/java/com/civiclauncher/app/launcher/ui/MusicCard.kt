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
import androidx.compose.ui.graphics.SolidColor
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
fun MusicCard(modifier: Modifier = Modifier, onOpen: () -> Unit, onOpenRadio: () -> Unit) {
    val repo = LocalContext.current.container.media
    val state by repo.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { repo.refresh() }

    val surface = Civic.colors.surface
    val tint = (state as? MediaState.Active)?.now?.artColor
    val top = if (tint != null) lerp(surface, tint, if (Civic.colors.isDark) 0.30f else 0.22f) else lerp(surface, Civic.colors.surfaceRaised, 0.6f)

    HomeCard(modifier, Brush.verticalGradient(listOf(top, surface))) {
        when (val s = state) {
            MediaState.NoPermission -> {
                CardHeader(P.MusicNotesLight, "Música", "Sin permiso", Tone.Warn)
                NoPermission()
            }
            is MediaState.Idle -> {
                when (s.source) {
                    MusicSource.ANDROID_AUTO -> {
                        CardHeader(P.MusicNotesLight, "Música", "Android Auto", Tone.Accent)
                        AutoKitPlaying()
                    }
                    MusicSource.RADIO -> {
                        CardHeader(P.MusicNotesLight, "Música", "Radio", Tone.Warn)
                        RadioPlaying(onOpenRadio)
                    }
                    else -> {
                        CardHeader(P.MusicNotesLight, "Música", "Sin música", Tone.Neutral)
                        Sources(onOpenRadio)
                    }
                }
            }
            // Sonando: sin encabezado, como el widget de música del diseño (solo la canción y controles).
            is MediaState.Active -> Active(s.now, onOpen)
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
    CoverCircle(null, P.AndroidLogoLight, 104.dp)
    Spacer(Modifier.height(Civic.space.l))
    CenteredTitle("Android Auto")
    CenteredSubtitle("La canción se ve en Android Auto")
    Spacer(Modifier.weight(1f))
    Controls(playing = null)
}

/**
 * Sin música: las dos fuentes que de verdad se usan, Bluetooth (el celular) y Radio. Android Auto ya
 * tiene su botón en la barra, y "USB" (el reproductor de memorias USB) queda en Apps.
 */
@Composable
private fun ColumnScope.Sources(onOpenRadio: () -> Unit) {
    val ctx = LocalContext.current
    val c = ctx.container
    val last by c.media.lastSource.collectAsStateWithLifecycle()
    // Sin texto extra: el encabezado y las dos opciones ya lo dicen todo.
    Spacer(Modifier.height(Civic.space.m))
    // Con el celular conectado suena directo; si no, se abre la app para conectarlo.
    SourceRow(P.BluetoothLight, BarColors.INDIGO, "Bluetooth", "Tu celular", last == MusicSource.BLUETOOTH, Modifier.weight(1f)) {
        if (!c.media.playBluetooth()) openMusicApp(ctx, MusicSource.BLUETOOTH, VendorApps.BLUETOOTH)
    }
    Spacer(Modifier.height(Civic.space.s))
    SourceRow(P.RadioLight, BarColors.RED, "Radio", "FM y AM", last == MusicSource.RADIO, Modifier.weight(1f), onClick = onOpenRadio)
}

/** Con la radio sonando: la frecuencia grande y los controles. Un toque abre la radio de Civic. */
@Composable
private fun ColumnScope.RadioPlaying(onOpenRadio: () -> Unit) {
    val radio = LocalContext.current.container.radio
    val info by radio.info.collectAsStateWithLifecycle()
    androidx.compose.runtime.DisposableEffect(Unit) {
        radio.startWatching()
        onDispose { radio.stopWatching() }
    }
    val source = remember { MutableInteractionSource() }
    Column(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .clickable(source, indication = null, role = Role.Button, onClickLabel = "Abrir la radio", onClick = onOpenRadio),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            (info.band ?: "Radio").uppercase(),
            style = Civic.type.caption.copy(letterSpacing = androidx.compose.ui.unit.TextUnit(0.12f, androidx.compose.ui.unit.TextUnitType.Em)),
            color = Civic.colors.textDim,
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                info.frequency ?: "--.-",
                style = Civic.type.clock.copy(fontSize = 64.sp, lineHeight = 68.sp),
                color = Civic.colors.text,
                maxLines = 1,
            )
            Spacer(Modifier.width(Civic.space.xs))
            Text(info.unit ?: "MHz", style = Civic.type.label, color = Civic.colors.textDim, modifier = Modifier.padding(bottom = 12.dp))
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        RoundButton(P.SkipBackFill, "Estación anterior", 60.dp, null) { radio.seekDown() }
        RoundButton(P.PlayFill, "Silencio", 76.dp, Civic.colors.surfaceRaised) { radio.playPause() }
        RoundButton(P.SkipForwardFill, "Estación siguiente", 60.dp, null) { radio.seekUp() }
    }
}

/** Fuente de música: ícono en pastilla de color (como la barra), nombre, descripción y flecha. */
@Composable
internal fun SourceRow(
    icon: ImageVector,
    colors: Pair<Color, Color>,
    title: String,
    subtitle: String,
    highlighted: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val c = Civic.colors
    val shape = RoundedCornerShape(Civic.radii.l)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                when {
                    pressed -> c.surfacePressed
                    highlighted -> lerp(c.surfaceRaised, c.accent, 0.18f)
                    else -> c.surfaceRaised
                },
            )
            .bevelBorder(shape)
            .clickable(source, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = Civic.space.l),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppTile(icon, colors, 48.dp)
        Spacer(Modifier.width(Civic.space.l))
        Column(Modifier.weight(1f)) {
            Text(title, style = Civic.type.title.copy(fontSize = 20.sp), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (highlighted) "La última vez" else subtitle,
                style = Civic.type.bodySmall,
                color = c.textDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(P.CaretRightLight, null, tint = c.textDim, modifier = Modifier.size(22.dp))
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
        // Portada solo si la hay (el Bluetooth no manda imagen: entonces solo texto, más grande).
        if (now.art != null) {
            CoverCircle(now.art, sourceIcon(now.source), 112.dp)
            Spacer(Modifier.height(Civic.space.l))
        } else {
            Text(
                sourceLabel(now.source).uppercase(),
                style = Civic.type.caption.copy(letterSpacing = androidx.compose.ui.unit.TextUnit(0.12f, androidx.compose.ui.unit.TextUnitType.Em)),
                color = Civic.colors.textDim,
            )
            Spacer(Modifier.height(Civic.space.s))
        }
        Text(
            now.title.orEmpty(),
            style = Civic.type.title.copy(fontSize = if (now.art == null) 28.sp else 22.sp, lineHeight = if (now.art == null) 34.sp else 28.sp),
            color = Civic.colors.text,
            maxLines = if (now.art == null) 2 else 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(2.dp))
        CenteredSubtitle(now.artist.orEmpty())
    }
    // Línea fina que separa la canción de los controles, como en el diseño.
    Box(Modifier.fillMaxWidth().height(1.dp).background(Civic.colors.line))
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
    val shape = RoundedCornerShape(Civic.radii.l)
    Box(
        Modifier
            .align(Alignment.CenterHorizontally)
            .size(size)
            .clip(shape)
            .background(Civic.colors.surfaceRaised)
            .bevelBorder(shape),
        contentAlignment = Alignment.Center,
    ) {
        if (art != null) {
            Image(art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(icon, null, tint = iconTint ?: Civic.colors.textDim, modifier = Modifier.size(size * 0.36f))
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
    Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50)).background(Civic.colors.surfacePressed)) {
        Box(Modifier.fillMaxWidth(pos.toFloat() / duration).height(4.dp).clip(RoundedCornerShape(50)).background(Civic.colors.text))
    }
}

/** [playing] null = no sabemos el estado (AutoKit). */
@Composable
private fun Controls(playing: Boolean?) {
    val repo = LocalContext.current.container.media
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        // Íconos sueltos (sin círculos), como el widget de música del diseño.
        RoundButton(P.RewindFill, "Anterior", 64.dp, null) { repo.previous() }
        RoundButton(
            if (playing == true) P.PauseFill else P.PlayFill,
            if (playing == true) "Pausa" else "Reproducir",
            72.dp,
            null,
        ) { repo.playPause() }
        RoundButton(P.FastForwardFill, "Siguiente", 64.dp, null) { repo.next() }
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
            .background(if (pressed) Civic.colors.surfacePressed else base)
            // El botón principal (con fondo) lleva canto de vidrio; los secundarios van sueltos.
            .let { if (bg != null) it.bevelBorder(CircleShape) else it }
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
    MusicSource.ANDROID_AUTO -> P.AndroidLogoFill
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
    // En vidrio el relleno es liso: el reflejo de arriba lo pone bevelBorder.
    val brush = background ?: if (c.isGlass) SolidColor(c.surface) else Brush.verticalGradient(listOf(lerp(c.surface, c.surfaceRaised, 0.6f), c.surface))
    Column(
        modifier
            .clip(RoundedCornerShape(Civic.radii.xl))
            .background(if (pressed) Brush.verticalGradient(listOf(c.surfacePressed, c.surfacePressed)) else brush)
            .bevelBorder(RoundedCornerShape(Civic.radii.xl))
            .let { if (onClick != null) it.clickable(source, indication = null, role = Role.Button, onClick = onClick) else it }
            .padding(20.dp),
        content = content,
    )
}

/**
 * Encabezado común de las tarjetas de Inicio: ícono, título y una cápsula de estado a la derecha.
 * Así Mi vehículo, Mapa y Música se leen igual.
 */
@Composable
fun CardHeader(icon: ImageVector, title: String, status: String?, tone: Tone) {
    // Etiqueta discreta (como los widgets de CarPlay): ícono chico y título en mayúsculas grises.
    // Lo importante de la tarjeta es su contenido, no su nombre.
    Row(Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Civic.colors.textDim, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(Civic.space.s))
        Text(
            title.uppercase(),
            style = Civic.type.caption.copy(letterSpacing = androidx.compose.ui.unit.TextUnit(0.10f, androidx.compose.ui.unit.TextUnitType.Em)),
            color = Civic.colors.textDim,
            maxLines = 1,
            softWrap = false,
        )
        Spacer(Modifier.weight(1f).widthIn(min = Civic.space.s))
        if (status != null) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(tone.color()))
            Spacer(Modifier.width(6.dp))
            Text(status, style = Civic.type.caption, color = Civic.colors.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 120.dp))
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