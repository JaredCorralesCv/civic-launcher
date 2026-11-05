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
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
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
import com.civiclauncher.app.ui.design.Civic
import com.civiclauncher.app.ui.design.CivicButton
import com.civiclauncher.app.ui.design.PhosphorIcons as P
import com.civiclauncher.app.ui.design.bevelBorder
import kotlinx.coroutines.delay

/**
 * Reproductor de Civic a pantalla completa: portada, canción, avance y controles, con el mismo
 * vidrio y tipografía que el resto. Abajo, el selector de fuente (Bluetooth / Radio).
 * El fondo toma el color de la portada, como el reproductor de CarPlay.
 */
@Composable
fun MusicScreen(onOpen: (Dest) -> Unit) {
    val ctx = LocalContext.current
    val c = ctx.container
    val state by c.media.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { c.media.refresh() }
    val now = (state as? MediaState.Active)?.now

    PlayerFrame(tint = now?.artColor) {
        // Arriba: regresar (círculo de vidrio) y, a la derecha, de dónde viene la música.
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            BackButton { onOpen(Dest.HOME) }
            Spacer(Modifier.weight(1f))
            // De dónde viene la música (no se muestra con la música de prueba, que sale de Civic).
            if (now != null && now.pkg != ctx.packageName) {
                val label = appLabel(now.source, now.pkg, ctx.packageName, ctx.container.apps.labelOf(now.pkg))
                Text(
                    if (now.source == MusicSource.APP) (ctx.container.apps.labelOf(now.pkg) ?: "Reproduciendo") else sourceLabel(now.source),
                    style = Civic.type.title.copy(fontSize = 19.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Normal),
                    color = Civic.colors.textDim,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .let { m -> if (label != null) m.clickable { openMusicApp(ctx, now.source, now.pkg) } else m }
                        .padding(horizontal = Civic.space.s, vertical = 6.dp),
                )
            }
        }
        when (val s = state) {
            is MediaState.Active -> NowPlayingLayout(s.now)
            else -> Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                when (s) {
                    is MediaState.Idle -> if (s.source == MusicSource.ANDROID_AUTO) AndroidAutoPlaying() else Nothing()
                    else -> NeedsPermission()
                }
            }
        }
        Spacer(Modifier.height(Civic.space.m))
        SourceSwitch(
            current = if (now?.source == MusicSource.RADIO) MusicSource.RADIO else MusicSource.BLUETOOTH,
            onBluetooth = { if (!c.media.playBluetooth()) openMusicApp(ctx, MusicSource.BLUETOOTH, VendorApps.BLUETOOTH) },
            onRadio = { onOpen(Dest.RADIO) },
            modifier = Modifier.align(Alignment.CenterHorizontally).width(380.dp).height(50.dp),
        )
    }
}

/**
 * Lo que suena, acomodado como el reproductor de CarPlay: a la izquierda la canción (centrada) y
 * los controles sueltos debajo; a la derecha la portada; abajo, a todo lo ancho, el avance.
 */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.NowPlayingLayout(now: NowPlaying) {
    val repo = LocalContext.current.container.media
    Row(Modifier.weight(1f).fillMaxWidth().padding(vertical = Civic.space.s), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Spacer(Modifier.weight(1f))
            Text(
                now.title.orEmpty(),
                // Sin portada el texto es el protagonista: un poco más grande.
                style = Civic.type.headline.copy(
                    fontSize = if (now.art == null) 40.sp else 32.sp,
                    lineHeight = if (now.art == null) 46.sp else 38.sp,
                    letterSpacing = (-0.02).em,
                ),
                color = Civic.colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            Text(now.artist.orEmpty(), style = Civic.type.title.copy(fontSize = 22.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Normal), color = Civic.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            now.album?.takeIf { it.isNotBlank() && it != now.title }?.let {
                Text(it, style = Civic.type.title.copy(fontSize = 22.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Normal), color = Civic.colors.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.weight(1f))
            // A todo lo ancho los botones quedarían demasiado separados: máximo 520 dp, centrados.
            Row(Modifier.widthIn(max = 520.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                ControlButton(P.RewindFill, "Anterior", 84.dp, glass = false) { repo.previous() }
                ControlButton(if (now.playing) P.PauseFill else P.PlayFill, if (now.playing) "Pausa" else "Reproducir", 96.dp, glass = false) { repo.playPause() }
                ControlButton(P.FastForwardFill, "Siguiente", 84.dp, glass = false) { repo.next() }
            }
            Spacer(Modifier.weight(0.4f))
        }
        // Solo si la música trae portada (Spotify en la pantalla sí; el Bluetooth no manda imagen).
        // Sin portada, la canción y los controles ocupan todo el ancho.
        if (now.art != null) {
            Spacer(Modifier.width(Civic.space.xl))
            BigCover(now.art, sourceIcon(now.source))
        }
    }
    TimedProgressRow(now)
}

/** Botón de regresar: flecha en un círculo de vidrio, como en CarPlay. */
@Composable
internal fun BackButton(onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(if (pressed) Civic.colors.surfacePressed else Civic.colors.surfaceRaised)
            .bevelBorder(CircleShape)
            .clickable(source, indication = null, role = Role.Button, onClickLabel = "Regresar", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(P.CaretLeftBold, "Regresar", tint = Civic.colors.text, modifier = Modifier.size(22.dp))
    }
}

/** Avance a todo lo ancho, con los tiempos a los lados (como CarPlay). */
@Composable
private fun TimedProgressRow(now: NowPlaying) {
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
    val timeStyle = Civic.type.title.copy(fontSize = 19.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Normal, fontFeatureSettings = "tnum")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(formatTime(pos), style = timeStyle, color = Civic.colors.text)
        Spacer(Modifier.width(Civic.space.m))
        Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(50)).background(Civic.colors.surfacePressed)) {
            Box(Modifier.fillMaxWidth(pos.toFloat() / duration).height(8.dp).clip(RoundedCornerShape(50)).background(Civic.colors.text.copy(alpha = 0.85f)))
        }
        Spacer(Modifier.width(Civic.space.m))
        Text("-" + formatTime(duration - pos), style = timeStyle, color = Civic.colors.text)
    }
}

// ------------------------------------------------------------------ Estados

/** AutoKit no comparte la canción, pero sí acepta los controles. */
@Composable
private fun RowScope.AndroidAutoPlaying() {
    val ctx = LocalContext.current
    BigCover(null, P.AndroidLogoLight)
    Spacer(Modifier.width(Civic.space.xxl))
    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
        PlayerHeader("Reproduciendo · Android Auto", action = "Abrir") { openMusicApp(ctx, MusicSource.ANDROID_AUTO, VendorApps.ANDROID_AUTO) }
        Spacer(Modifier.weight(1f))
        Text("Android Auto", style = Civic.type.headline.copy(fontSize = 34.sp, letterSpacing = (-0.02).em), color = Civic.colors.text)
        Spacer(Modifier.height(6.dp))
        Text("La canción se ve en Android Auto", style = Civic.type.title.copy(fontSize = 20.sp), color = Civic.colors.textDim)
        Spacer(Modifier.weight(1f))
        val repo = ctx.container.media
        PlayerControls(null, onPrevious = repo::previous, onPlayPause = repo::playPause, onNext = repo::next)
    }
}

@Composable
private fun RowScope.Nothing() {
    BigCover(null, P.MusicNotesLight)
    Spacer(Modifier.width(Civic.space.xxl))
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
        PlayerHeader("Música", action = null) {}
        Spacer(Modifier.height(Civic.space.xl))
        Text("Nada sonando", style = Civic.type.headline.copy(fontSize = 34.sp, letterSpacing = (-0.02).em), color = Civic.colors.text)
        Spacer(Modifier.height(Civic.space.s))
        Text(
            "Elige abajo de dónde escuchar. Con el celular conectado por Bluetooth, la música empieza sola.",
            style = Civic.type.body,
            color = Civic.colors.textDim,
        )
    }
}

@Composable
private fun RowScope.NeedsPermission() {
    val ctx = LocalContext.current
    BigCover(null, P.MusicNotesLight)
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

/** Texto del botón para abrir la app de esa música, o null si no hay otra app que abrir. */
private fun appLabel(source: MusicSource, pkg: String, ownPkg: String, appName: String?): String? = when {
    pkg == ownPkg -> null // la música de prueba sale del propio Civic
    source == MusicSource.BLUETOOTH -> "Música BT"
    source == MusicSource.ANDROID_AUTO -> "Abrir"
    source == MusicSource.USB -> "Archivos"
    source == MusicSource.RADIO -> "Estaciones"
    else -> appName ?: "Abrir app"
}

// ------------------------------------------------------------------ Piezas compartidas (música y radio)

/** Fondo de las pantallas de reproducción: degradado del color de la portada hacia el fondo. */
@Composable
internal fun PlayerFrame(tint: Color?, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val base = Civic.colors.background
    val top = if (tint != null) lerp(base, tint, if (Civic.colors.isDark) 0.30f else 0.22f) else base
    // El contenido baja a centrarse cuando la barra de abajo se esconde, pero el fondo teñido debe
    // cubrir SIEMPRE toda la pantalla: si se moviera con el contenido, arriba quedaría una franja
    // sin teñir (y abajo otra). Se dibuja hacia afuera de sus propios bordes para compensar.
    val shift = LocalBarShift.current
    val brush = Brush.verticalGradient(listOf(top, base))
    Column(
        Modifier
            .fillMaxSize()
            .drawBehind {
                val up = shift.toPx()
                val down = BAR_SPACE.toPx() - up
                drawRect(
                    brush,
                    topLeft = androidx.compose.ui.geometry.Offset(0f, -up),
                    size = androidx.compose.ui.geometry.Size(size.width, size.height + up + down),
                )
            }
            .padding(horizontal = PAGE_GUTTER, vertical = Civic.space.m),
        content = content,
    )
}

/** Línea de arriba: "REPRODUCIENDO · BLUETOOTH" y, a la derecha, un acceso discreto a la app. */
@Composable
internal fun PlayerHeader(text: String, action: String?, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text.uppercase(),
            style = Civic.type.caption.copy(letterSpacing = 0.12.em),
            color = Civic.colors.textDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (action != null) GlassPill(action, P.CaretRightLight, onAction)
    }
}

@Composable
private fun GlassPill(label: String, icon: ImageVector, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .height(40.dp)
            .clip(shape)
            .background(if (pressed) Civic.colors.surfacePressed else Civic.colors.surfaceRaised)
            .bevelBorder(shape)
            .clickable(source, indication = null, role = Role.Button, onClick = onClick)
            .padding(start = Civic.space.l, end = Civic.space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = Civic.type.label.copy(fontSize = 15.sp), color = Civic.colors.text, maxLines = 1)
        Spacer(Modifier.width(Civic.space.xs))
        Icon(icon, null, tint = Civic.colors.textDim, modifier = Modifier.size(18.dp))
    }
}

/** Portada grande. Sin portada: el ícono fino de la fuente sobre vidrio. */
@Composable
private fun BigCover(art: ImageBitmap?, icon: ImageVector) {
    val shape = RoundedCornerShape(Civic.radii.xl)
    Box(
        Modifier
            .fillMaxHeight()
            .aspectRatio(1f)
            .clip(shape)
            .background(Civic.colors.surface)
            .bevelBorder(shape),
        contentAlignment = Alignment.Center,
    ) {
        if (art != null) {
            Image(art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(icon, null, tint = Civic.colors.textDim, modifier = Modifier.fillMaxSize(0.30f))
        }
    }
}

private fun formatTime(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/**
 * Controles: anterior y siguiente como íconos sueltos (sin fondo), y play/pausa en un círculo de
 * vidrio. [playing] null = no sabemos el estado (AutoKit, radio).
 */
@Composable
internal fun PlayerControls(
    playing: Boolean?,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    previousIcon: ImageVector = P.RewindFill,
    nextIcon: ImageVector = P.FastForwardFill,
    previousLabel: String = "Anterior",
    nextLabel: String = "Siguiente",
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        // Íconos sueltos y grandes, igual que el reproductor de música (sin círculos).
        ControlButton(previousIcon, previousLabel, 84.dp, glass = false, onPrevious)
        ControlButton(
            if (playing == true) P.PauseFill else P.PlayFill,
            if (playing == true) "Pausa" else "Reproducir",
            96.dp,
            glass = false,
            onPlayPause,
        )
        ControlButton(nextIcon, nextLabel, 84.dp, glass = false, onNext)
    }
}

@Composable
private fun ControlButton(icon: ImageVector, label: String, size: Dp, glass: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val c = Civic.colors
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(
                when {
                    pressed -> c.surfacePressed
                    glass -> c.surfaceRaised
                    else -> Color.Transparent
                },
            )
            .let { if (glass) it.bevelBorder(CircleShape) else it }
            .clickable(source, indication = null, role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = c.text, modifier = Modifier.size(size * if (glass) 0.40f else 0.42f))
    }
}

/**
 * Selector de fuente: una cápsula de vidrio redonda con dos opciones. La activa es una "lente" más
 * clara y con canto, y su ícono va en el color de la fuente; la otra queda tenue, solo texto e ícono.
 */
@Composable
internal fun SourceSwitch(
    current: MusicSource,
    onBluetooth: () -> Unit,
    onRadio: () -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth().height(60.dp),
) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .clip(shape)
            .background(Civic.colors.surface)
            .bevelBorder(shape)
            .padding(4.dp),
    ) {
        Segment(P.BluetoothLight, P.BluetoothFill, "Bluetooth", BarColors.INDIGO.first, current == MusicSource.BLUETOOTH, onBluetooth)
        Segment(P.RadioLight, P.RadioFill, "Radio", BarColors.RED.first, current == MusicSource.RADIO, onRadio)
    }
}

@Composable
private fun RowScope.Segment(icon: ImageVector, selectedIcon: ImageVector, label: String, tint: Color, selected: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val c = Civic.colors
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .clip(shape)
            .background(
                when {
                    pressed -> c.surfacePressed
                    selected -> c.surfacePressed
                    else -> Color.Transparent
                },
            )
            .let { if (selected) it.bevelBorder(shape) else it }
            .clickable(source, indication = null, role = Role.Tab, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (selected) selectedIcon else icon, null, tint = if (selected) tint else c.textDim, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(Civic.space.s))
        Text(
            label,
            style = Civic.type.label.copy(fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Normal),
            color = if (selected) c.text else c.textDim,
            maxLines = 1,
        )
    }
}

/** Acción discreta arriba a la derecha (por ejemplo "Estaciones" en la radio). */
@Composable
internal fun PlayerHeaderAction(label: String, onClick: () -> Unit) = GlassPill(label, P.CaretRightLight, onClick)