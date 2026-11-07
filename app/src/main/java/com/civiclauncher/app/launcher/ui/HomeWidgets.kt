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
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.graphics.drawscope.rotate
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
    val dataState = c.obd.data.collectAsStateWithLifecycle()
    val live by c.obd.live.collectAsStateWithLifecycle()
    val diag by c.obd.diagnostics.collectAsStateWithLifecycle()
    val (speed, fromObd) = rememberSpeed()
    val connected = obdState is EngineState.Connected
    val hasObd = config.mode != ObdMode.NONE
    // El OBD manda decenas de datos por segundo, pero esta tarjeta solo muestra tres, redondeados.
    // derivedStateOf hace que la tarjeta se vuelva a dibujar solo cuando cambia lo que se ve.
    val coolant by remember(connected) {
        androidx.compose.runtime.derivedStateOf { if (connected) dataState.value[Pids.COOLANT_TEMP].fresh()?.value?.let { Math.round(it).toDouble() } else null }
    }
    val battery by remember { androidx.compose.runtime.derivedStateOf { dataState.value.battery.fresh(30_000)?.value?.let { Math.round(it * 10) / 10.0 } } }
    val engineRunning by remember { androidx.compose.runtime.derivedStateOf { (dataState.value[Pids.RPM].fresh()?.value ?: 0.0) > 500 } }
    // RPM redondeadas de 50 en 50: el arco se anima solo entre valores que se notan.
    val rpm by remember(connected) {
        androidx.compose.runtime.derivedStateOf { if (connected) dataState.value[Pids.RPM].fresh()?.value?.let { Math.round(it / 50.0) * 50f } else null }
    }
    val alert = live.alerts.firstOrNull()

    HomeCard(modifier, onClick = onClick) {
        CardHeader(P.CarSimpleLight, "Vehículo", if (fromObd) "OBD" else "GPS", if (speed != null) Tone.Ok else Tone.Warn)
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    speed?.let { "%.0f".format(it) } ?: "--",
                    style = Civic.type.gaugeValue.copy(fontSize = 46.sp, lineHeight = 50.sp),
                    color = if (speed != null) Civic.colors.text else Civic.colors.textFaint,
                )
                Spacer(Modifier.width(6.dp))
                Text("km/h", style = Civic.type.label, color = Civic.colors.textDim, modifier = Modifier.padding(bottom = 8.dp))
            }
            Spacer(Modifier.weight(1f))
            RpmArc(rpm, Modifier.size(width = 132.dp, height = 100.dp))
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
            Stat("Batería", battery?.let { "%.1f V".format(it) } ?: "--", batteryColor(battery, if (engineRunning) 1_000.0 else 0.0))
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
            val warning = tone == Tone.Warn || tone == Tone.Bad
            if (warning) {
                // Un aviso de verdad: triángulo y la parte importante en su color; "toca para verlo", gris.
                Icon(P.WarningFill, null, tint = tone.color(), modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                val main = text.substringBefore(" · ")
                val rest = text.substringAfter(" · ", "")
                Text(
                    androidx.compose.ui.text.buildAnnotatedString {
                        withStyle(androidx.compose.ui.text.SpanStyle(color = tone.color())) { append(main) }
                        if (rest.isNotEmpty()) append(" · $rest")
                    },
                    style = Civic.type.caption,
                    color = Civic.colors.textDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Box(Modifier.size(7.dp).clip(CircleShape).background(tone.color()))
                Spacer(Modifier.width(Civic.space.s))
                Text(text, style = Civic.type.caption, color = Civic.colors.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * Tacómetro: por fuera, una banda gruesa que se va llenando al subir las RPM y cambia de color con
 * ellas (azul profundo → azul claro → ámbar cerca del corte → rojo en la zona roja), con un halo.
 * Por dentro, la escala fina (rayitas cada 500 RPM, más largas cada 1000, rojas en la zona roja) y
 * una aguja de luz. En medio, las RPM en miles. Se mueve suave. Sin OBD: la banda vacía y "--".
 */
@Composable
private fun RpmArc(rpm: Float?, modifier: Modifier) {
    val fraction by androidx.compose.animation.core.animateFloatAsState(
        targetValue = ((rpm ?: 0f) / RPM_MAX).coerceIn(0f, 1f),
        animationSpec = androidx.compose.animation.core.tween(400, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        label = "rpm",
    )
    val dark = Civic.colors.isDark
    val ink = if (dark) Color.White else Color(0xFF15181D)
    val minor = ink.copy(alpha = 0.22f)
    val major = ink.copy(alpha = 0.55f)
    val track = ink.copy(alpha = 0.08f)
    val redline = rpm != null && rpm >= RPM_REDLINE
    Box(modifier) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val band = 6.dp.toPx()
            val halo = 3.dp.toPx()
            val bandR = size.width / 2f - halo - band / 2f
            val center = androidx.compose.ui.geometry.Offset(size.width / 2f, halo + band / 2f + bandR)
            fun at(radius: Float, deg: Float): androidx.compose.ui.geometry.Offset {
                val a = Math.toRadians(deg.toDouble())
                return androidx.compose.ui.geometry.Offset(center.x + radius * kotlin.math.cos(a).toFloat(), center.y + radius * kotlin.math.sin(a).toFloat())
            }
            fun angleOf(value: Float) = RPM_START + RPM_SWEEP * value / RPM_MAX
            val bTop = androidx.compose.ui.geometry.Offset(center.x - bandR, center.y - bandR)
            val bSize = androidx.compose.ui.geometry.Size(2 * bandR, 2 * bandR)
            val round = androidx.compose.ui.graphics.drawscope.Stroke(band, cap = androidx.compose.ui.graphics.StrokeCap.Round)

            // Banda vacía; la parte de la zona roja ya se ve rojiza aunque no llegues.
            drawArc(track, RPM_START, RPM_SWEEP, false, bTop, bSize, style = round)
            drawArc(RPM_RED.copy(alpha = 0.22f), angleOf(RPM_REDLINE), RPM_START + RPM_SWEEP - angleOf(RPM_REDLINE), false, bTop, bSize, style = androidx.compose.ui.graphics.drawscope.Stroke(band))

            // Escala fina por dentro de la banda.
            val tickOut = bandR - band / 2f - 4.dp.toPx()
            var v = 0f
            while (v <= RPM_MAX) {
                val isMajor = v % 1000f == 0f
                val len = if (isMajor) 7.dp.toPx() else 3.5.dp.toPx()
                val color = when {
                    v >= RPM_REDLINE -> RPM_RED.copy(alpha = if (isMajor) 0.95f else 0.6f)
                    isMajor -> major
                    else -> minor
                }
                drawLine(color, at(tickOut, angleOf(v)), at(tickOut - len, angleOf(v)), strokeWidth = if (isMajor) 1.5.dp.toPx() else 1.dp.toPx())
                v += 500f
            }

            // Se lee aquí, en el dibujo: la animación solo redibuja, no recompone la tarjeta.
            val sweep = RPM_SWEEP * fraction
            if (sweep < 0.5f) return@Canvas
            // Los colores están fijos en la escala: al subir, la banda "descubre" el ámbar y el rojo.
            val span = RPM_SWEEP / 360f
            val fill = Brush.sweepGradient(
                0f to Color(0xFF1F4FE0),
                span * 0.55f to Color(0xFF3B8BFF),
                span * (RPM_SHIFT / RPM_MAX) to Color(0xFF7CC8FF),
                span * (5300f / RPM_MAX) to Color(0xFFFFB020),
                span * (RPM_REDLINE / RPM_MAX) to RPM_RED,
                1f to RPM_RED,
                center = center,
            )
            rotate(RPM_START, center) {
                // Halo y banda; el degradado empieza en 0° (por eso se gira el lienzo).
                drawArc(fill, 0f, sweep, false, bTop, bSize, alpha = 0.28f, style = androidx.compose.ui.graphics.drawscope.Stroke(band + halo * 2f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                drawArc(fill, 0f, sweep, false, bTop, bSize, style = round)
            }
            // Aguja de luz: de la escala hasta la banda.
            val tipAngle = RPM_START + sweep
            val needle = if (redline) RPM_RED else Color.White
            val from = at(tickOut - 10.dp.toPx(), tipAngle)
            val to = at(bandR, tipAngle)
            drawLine(needle.copy(alpha = 0.25f), from, to, strokeWidth = 6.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(needle, from, to, strokeWidth = 2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
        }
        Column(
            Modifier.align(Alignment.TopCenter).padding(top = 47.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                rpm?.let { "%.1f".format(it / 1000f) } ?: "--",
                style = Civic.type.title.copy(fontSize = 24.sp, lineHeight = 26.sp, fontWeight = FontWeight.Light, fontFeatureSettings = "tnum", letterSpacing = (-0.01).em),
                color = when {
                    rpm == null -> Civic.colors.textFaint
                    redline -> RPM_RED
                    else -> Civic.colors.text
                },
            )
            Text(
                "RPM ×1000",
                style = Civic.type.caption.copy(fontSize = 8.sp, lineHeight = 10.sp, letterSpacing = 0.18.em),
                color = Civic.colors.textFaint,
            )
        }
    }
}

private const val RPM_MAX = 7000f
/** Zona roja del motor del Civic (corta cerca de 6800). */
private const val RPM_REDLINE = 6000f
/** Desde aquí la banda empieza a ponerse ámbar: "ya cambia". */
private const val RPM_SHIFT = 4500f
private val RPM_RED = Color(0xFFFF453A)
/** El arco empieza abajo a la izquierda (150°) y da 240° pasando por arriba. */
private const val RPM_START = 150f
private const val RPM_SWEEP = 240f

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
