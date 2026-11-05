package com.civiclauncher.app.launcher.ui

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.font.FontWeight
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
import com.civiclauncher.app.launcher.obd.ObdMode
import com.civiclauncher.app.ui.design.Civic
import com.civiclauncher.app.ui.design.PhosphorIcons as P
import com.civiclauncher.app.ui.design.Tone
import com.civiclauncher.app.ui.design.bevelBorder
import com.civiclauncher.app.ui.design.color
import com.civiclauncher.obd.AlertLevel
import com.civiclauncher.obd.EngineState
import com.civiclauncher.obd.Pids

// =================================================================== Música (compacta)

/**
 * Música en media columna: encabezado, lo que suena y los controles. Sin música, el selector
 * Bluetooth | Radio en una sola línea (antes ocupaba la tarjeta completa con dos botones enormes).
 */
@Composable
fun CompactMusicCard(modifier: Modifier, onOpen: () -> Unit, onOpenRadio: () -> Unit) {
    val ctx = LocalContext.current
    val c = ctx.container
    val state by c.media.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { c.media.refresh() }

    val surface = Civic.colors.surface
    val tint = (state as? MediaState.Active)?.now?.artColor
    val background = if (tint != null) Brush.verticalGradient(listOf(lerp(surface, tint, if (Civic.colors.isDark) 0.30f else 0.22f), surface)) else null

    HomeCard(modifier, background) {
        when (val s = state) {
            MediaState.NoPermission -> {
                CardHeader(P.MusicNotesLight, "Música", "Sin permiso", Tone.Warn)
                Spacer(Modifier.weight(1f))
                Text("Civic necesita un permiso para ver la canción.", style = Civic.type.bodySmall, color = Civic.colors.textDim)
                Spacer(Modifier.height(Civic.space.s))
                SmallPill("Dar permiso", P.CheckCircle) {
                    runCatching { ctx.startActivity(NotificationCollector.settingsIntent()) }
                        .onFailure { Toast.makeText(ctx, "Esta pantalla no tiene ese ajuste", Toast.LENGTH_SHORT).show() }
                }
            }
            is MediaState.Active -> Playing(s.now, onOpen)
            is MediaState.Idle -> when (s.source) {
                MusicSource.RADIO -> RadioNow(onOpenRadio)
                MusicSource.ANDROID_AUTO -> {
                    CardHeader(P.MusicNotesLight, "Música", "Android Auto", Tone.Accent)
                    Spacer(Modifier.weight(1f))
                    Title("Android Auto", "La canción se ve en Android Auto")
                    Spacer(Modifier.weight(1f))
                    val repo = c.media
                    TransportRow(null, repo::previous, repo::playPause, repo::next)
                }
                else -> {
                    CardHeader(P.MusicNotesLight, "Música", "Sin música", Tone.Neutral)
                    Spacer(Modifier.weight(1f))
                    Title("¿Qué escuchamos?", null)
                    Spacer(Modifier.weight(1f))
                    SourceSwitch(
                        current = c.media.lastSource.collectAsStateWithLifecycle().value ?: MusicSource.BLUETOOTH,
                        onBluetooth = { if (!c.media.playBluetooth()) openMusicApp(ctx, MusicSource.BLUETOOTH, VendorApps.BLUETOOTH) },
                        onRadio = onOpenRadio,
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.Playing(now: NowPlaying, onOpen: () -> Unit) {
    val repo = LocalContext.current.container.media
    val source = remember { MutableInteractionSource() }
    CardHeader(sourceIcon(now.source), if (now.source == MusicSource.APP) "Música" else sourceLabel(now.source), if (now.playing) "Sonando" else "En pausa", if (now.playing) Tone.Ok else Tone.Neutral)
    // Tocar la canción abre el reproductor completo.
    Row(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .clickable(source, indication = null, role = Role.Button, onClickLabel = "Abrir el reproductor", onClick = onOpen),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        now.art?.let {
            Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.size(72.dp).clip(RoundedCornerShape(Civic.radii.m)))
            Spacer(Modifier.width(Civic.space.m))
        }
        Column(Modifier.weight(1f)) { Title(now.title.orEmpty(), now.artist) }
    }
    ProgressLine(now)
    Spacer(Modifier.height(Civic.space.xs))
    TransportRow(now.playing, repo::previous, repo::playPause, repo::next)
}

/** La radio sonando: frecuencia grande y cambio de estación. Un toque abre la radio de Civic. */
@Composable
private fun ColumnScope.RadioNow(onOpenRadio: () -> Unit) {
    val radio = LocalContext.current.container.radio
    val info by radio.info.collectAsStateWithLifecycle()
    DisposableEffect(Unit) {
        radio.startWatching()
        onDispose { radio.stopWatching() }
    }
    val source = remember { MutableInteractionSource() }
    CardHeader(P.RadioLight, "Radio", info.band ?: "FM", Tone.Warn)
    Row(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .clickable(source, indication = null, role = Role.Button, onClickLabel = "Abrir la radio", onClick = onOpenRadio),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(info.frequency ?: "--.-", style = Civic.type.clock.copy(fontSize = 52.sp, lineHeight = 56.sp), color = Civic.colors.text, maxLines = 1)
        Spacer(Modifier.width(6.dp))
        Text(info.unit ?: "MHz", style = Civic.type.label, color = Civic.colors.textDim, modifier = Modifier.padding(top = 18.dp))
    }
    TransportRow(null, radio::seekDown, radio::playPause, radio::seekUp, P.SkipBackFill, P.SkipForwardFill)
}

@Composable
private fun Title(title: String, subtitle: String?) {
    Text(title, style = Civic.type.title.copy(fontSize = 22.sp, lineHeight = 27.sp), color = Civic.colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
    if (!subtitle.isNullOrBlank()) {
        Text(subtitle, style = Civic.type.bodySmall, color = Civic.colors.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Barra de avance muy fina (sin tiempos: en media tarjeta no caben con dignidad). */
@Composable
private fun ProgressLine(now: NowPlaying) {
    val duration = now.durationMs ?: return
    val start = now.positionMs ?: return
    var tick by remember { androidx.compose.runtime.mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    androidx.compose.runtime.LaunchedEffect(now.playing, now.positionAt) {
        while (now.playing) {
            tick = android.os.SystemClock.elapsedRealtime()
            kotlinx.coroutines.delay(1_000)
        }
    }
    val pos = (if (now.playing) start + ((tick - now.positionAt) * now.speed).toLong() else start).coerceIn(0, duration)
    Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(50)).background(Civic.colors.track)) {
        Box(Modifier.fillMaxWidth(pos.toFloat() / duration).height(3.dp).clip(RoundedCornerShape(50)).background(Civic.colors.text))
    }
}

@Composable
private fun TransportRow(
    playing: Boolean?,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    previousIcon: ImageVector = P.RewindFill,
    nextIcon: ImageVector = P.FastForwardFill,
) {
    Row(Modifier.fillMaxWidth().height(56.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        IconTap(previousIcon, "Anterior", 56.dp, onPrevious)
        IconTap(if (playing == true) P.PauseFill else P.PlayFill, if (playing == true) "Pausa" else "Reproducir", 56.dp, onPlayPause)
        IconTap(nextIcon, "Siguiente", 56.dp, onNext)
    }
}

@Composable
private fun IconTap(icon: ImageVector, label: String, size: Dp, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (pressed) Civic.colors.surfacePressed else Color.Transparent)
            .clickable(source, indication = null, role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = Civic.colors.text, modifier = Modifier.size(size * 0.5f))
    }
}

@Composable
private fun SmallPill(label: String, icon: ImageVector, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .height(44.dp)
            .clip(shape)
            .background(Civic.colors.surfaceRaised)
            .bevelBorder(shape)
            .clickable(onClick = onClick)
            .padding(horizontal = Civic.space.l),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Civic.colors.text, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(Civic.space.s))
        Text(label, style = Civic.type.label, color = Civic.colors.text)
    }
}

// =================================================================== Vehículo (resumen)

/**
 * Todo lo del carro en una tarjeta: velocidad y marcha, y abajo tres datos (motor, batería,
 * consumo). La última línea dice el estado: aviso, "Sin fallas" o cómo conectar el OBD.
 */
@Composable
fun VehicleSummaryCard(modifier: Modifier, onClick: () -> Unit) {
    val c = LocalContext.current.container
    val config by c.obd.config.collectAsStateWithLifecycle()
    val obdState by c.obd.state.collectAsStateWithLifecycle()
    val data by c.obd.data.collectAsStateWithLifecycle()
    val live by c.obd.live.collectAsStateWithLifecycle()
    val diag by c.obd.diagnostics.collectAsStateWithLifecycle()
    val (speed, fromObd) = rememberSpeed()
    val connected = obdState is EngineState.Connected
    val hasObd = config.mode != ObdMode.NONE
    val coolant = if (connected) data[Pids.COOLANT_TEMP].fresh()?.value else null
    val battery = data.battery.fresh(30_000)?.value
    val alert = live.alerts.firstOrNull()

    HomeCard(modifier, onClick = onClick) {
        CardHeader(P.CarSimpleLight, "Vehículo", if (fromObd) "OBD" else "GPS", if (speed != null) Tone.Ok else Tone.Warn)
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                speed?.let { "%.0f".format(it) } ?: "--",
                style = Civic.type.gaugeValue.copy(fontSize = 46.sp, lineHeight = 50.sp),
                color = if (speed != null) Civic.colors.text else Civic.colors.textFaint,
            )
            Spacer(Modifier.width(6.dp))
            Text("km/h", style = Civic.type.label, color = Civic.colors.textDim, modifier = Modifier.padding(bottom = 8.dp))
            Spacer(Modifier.weight(1f))
            live.gear?.let { g ->
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(Civic.radii.s)).background(Civic.colors.surfaceRaised).bevelBorder(RoundedCornerShape(Civic.radii.s)),
                    contentAlignment = Alignment.Center,
                ) { Text("$g", style = Civic.type.title.copy(fontSize = 20.sp), color = Civic.colors.text) }
            }
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth()) {
            Stat("Motor", coolant?.let { "%.0f°".format(it) } ?: "--", tempColor(coolant))
            Stat("Batería", battery?.let { "%.1f V".format(it) } ?: "--", batteryColor(battery, data[Pids.RPM].fresh()?.value))
            Stat(
                "Consumo",
                // En movimiento km/L; detenido con el motor encendido, litros por hora (ralentí).
                live.kmPerLiter?.let { if (it >= 99) "∞" else "%.1f".format(it) }
                    ?: live.litersPerHour?.let { "%.1f".format(it) } ?: "--",
                Civic.colors.text,
                unit = when {
                    live.kmPerLiter != null -> "km/L"
                    live.litersPerHour != null -> "L/h"
                    else -> null
                },
            )
        }
        Spacer(Modifier.height(Civic.space.s))
        // Una línea de estado: lo más importante primero.
        val (text, tone) = when {
            !hasObd -> "Sin adaptador OBD · toca para conectarlo" to Tone.Neutral
            alert != null -> alert.title to if (alert.level == AlertLevel.DANGER) Tone.Bad else Tone.Warn
            !connected -> obdStatus(obdState, config).let { it.first to it.second }
            diag.stored.isNotEmpty() -> diag.stored.size.let { if (it == 1) "1 código de falla · toca para verlo" else "$it códigos de falla · toca para verlos" } to Tone.Warn
            else -> "Sin fallas" to Tone.Ok
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(tone.color()))
            Spacer(Modifier.width(Civic.space.s))
            Text(text, style = Civic.type.caption, color = Civic.colors.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun RowScope.Stat(label: String, value: String, color: Color, unit: String? = null) {
    Column(Modifier.weight(1f)) {
        Text(label.uppercase(), style = Civic.type.caption.copy(fontSize = 11.sp, letterSpacing = 0.08.em), color = Civic.colors.textFaint, maxLines = 1)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = Civic.type.title.copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"), color = color, maxLines = 1)
            if (unit != null) {
                Spacer(Modifier.width(3.dp))
                Text(unit, style = Civic.type.caption.copy(fontSize = 11.sp), color = Civic.colors.textDim, modifier = Modifier.padding(bottom = 3.dp))
            }
        }
    }
}

@Composable
private fun tempColor(c: Double?): Color = when {
    c == null -> Civic.colors.textFaint
    c >= 105 -> Civic.colors.bad
    c >= 100 -> Civic.colors.warn
    else -> Civic.colors.text
}

@Composable
private fun batteryColor(v: Double?, rpm: Double?): Color = when {
    v == null -> Civic.colors.textFaint
    rpm != null && rpm > 500 && v < 13.0 -> Civic.colors.warn
    v < 11.9 -> Civic.colors.bad
    else -> Civic.colors.text
}
