package com.civiclauncher.app.launcher.ui

import android.content.Intent
import android.provider.Settings
import android.text.format.DateFormat
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.civiclauncher.app.container
import androidx.compose.foundation.border
import androidx.compose.runtime.LaunchedEffect
import com.civiclauncher.app.launcher.map.CivicMap
import com.civiclauncher.app.launcher.map.MAP_CACHE_CARD
import com.civiclauncher.app.launcher.map.MapSetup
import com.civiclauncher.app.launcher.map.MapStatus
import com.civiclauncher.app.launcher.map.mapCenter
import com.civiclauncher.app.launcher.map.rememberStreet
import com.civiclauncher.app.launcher.obd.ObdMode
import com.civiclauncher.app.ui.design.Civic
import com.civiclauncher.app.ui.design.PhosphorIcons as P
import com.civiclauncher.app.ui.design.StatusPill
import com.civiclauncher.app.ui.design.Tone
import com.civiclauncher.app.ui.design.bevelBorder
import com.civiclauncher.app.ui.design.color
import com.civiclauncher.obd.EngineState
import com.civiclauncher.obd.Pids
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Inicio estilo tablero: barra superior (hora, fecha, estado, ajustes) y tres tarjetas:
 * Mi vehículo, Navegación y Música. La barra de accesos está en [BottomBar].
 */
@Composable
fun HomeScreen(onOpen: (Dest) -> Unit) {
    // Distribución inspirada en el Inicio de CarPlay (iOS 27): riel a la izquierda con la hora y
    // accesos; a la derecha el mapa, la música y una columna con dos tarjetas chicas.
    // Un solo bloque: riel y tarjetas comparten borde de arriba y de abajo (sin franja vacía arriba).
    // Al esconderse la barra de abajo, todo el bloque baja junto a quedar centrado.
    // Tres zonas con jerarquía clara: riel fino (hora y estado), el MAPA grande (lo más importante
    // al manejar) y una sola columna a la derecha con dos tarjetas del mismo estilo: Música y Vehículo.
    // Sin accesos repetidos: Mapa, Radio, Android Auto y Bluetooth ya están en la barra de abajo.
    Column(Modifier.fillMaxSize().padding(horizontal = PAGE_GUTTER, vertical = Civic.space.m)) {
        TimezoneWarning()
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(Civic.space.m)) {
            HomeRail(onOpen)
            MapCard(Modifier.weight(1.3f).fillMaxHeight(), onOpen = { onOpen(Dest.MAP) })
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(Civic.space.m)) {
                CompactMusicCard(Modifier.weight(1f).fillMaxWidth(), onOpen = { onOpen(Dest.MUSIC) }, onOpenRadio = { onOpen(Dest.RADIO) })
                VehicleSummaryCard(Modifier.weight(1f).fillMaxWidth()) { onOpen(Dest.VEHICLE) }
            }
        }
    }
}

// ------------------------------------------------------------------ Riel izquierdo

/**
 * Riel izquierdo (como el de CarPlay): la hora arriba, el estado del GPS/OBD, tres accesos y, hasta
 * abajo, el botón de todas las apps.
 */
@Composable
private fun HomeRail(onOpen: (Dest) -> Unit) {
    val ctx = LocalContext.current
    val c = ctx.container
    val shape = RoundedCornerShape(Civic.radii.xl)
    Column(
        Modifier
            .width(76.dp)
            .fillMaxHeight()
            .clip(shape)
            .background(Civic.colors.surface)
            .bevelBorder(shape)
            .padding(vertical = Civic.space.m),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Solo la hora, el estado y dos accesos discretos: lo demás vive en la barra de abajo.
        RailClock()
        Spacer(Modifier.height(Civic.space.xs))
        RailStatus()
        Spacer(Modifier.weight(1f))
        // En medio, tus últimas apps (como el riel de CarPlay). Vacío hasta que abras alguna.
        RecentApps()
        Spacer(Modifier.weight(1f))
        // Abajo, discretos: todas las apps y Ajustes (antes el engrane ocupaba una franja arriba).
        TopIcon(P.SquaresFourLight, "Todas las apps") { onOpen(Dest.APPS) }
        TopIcon(P.GearSixLight, "Ajustes") { onOpen(Dest.SETTINGS) }
    }
}

/** Las últimas 3 apps abiertas desde Civic, con su ícono real. */
@Composable
private fun RecentApps() {
    val apps = LocalContext.current.container.apps
    val recent by apps.recent.collectAsStateWithLifecycle()
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Civic.space.m)) {
        recent.forEach { app ->
            val icon by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, app.pkg) { value = apps.icon(app) }
            val source = remember { MutableInteractionSource() }
            val pressed by source.collectIsPressedAsState()
            Box(
                Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (pressed) Civic.colors.surfacePressed else Color.Transparent)
                    .clickable(source, indication = null, role = Role.Button, onClickLabel = app.label) { apps.launch(app) },
                contentAlignment = Alignment.Center,
            ) {
                icon?.let { Image(it, app.label, modifier = Modifier.size(44.dp)) }
            }
        }
    }
}

/** Margen lateral de todas las pantallas (igual en todas, para que nada "brinque" al cambiar). */
internal val PAGE_GUTTER = 16.dp

@Composable
private fun RailClock() {
    val ctx = LocalContext.current
    val now by ctx.container.clock.now.collectAsStateWithLifecycle()
    val is24 = DateFormat.is24HourFormat(ctx)
    val text = remember(now, is24) {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        if (is24) "%d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
        else "%d:%02d".format(cal.get(Calendar.HOUR).let { if (it == 0) 12 else it }, cal.get(Calendar.MINUTE))
    }
    Text(text, style = Civic.type.title.copy(fontSize = 20.sp, fontFeatureSettings = "tnum", letterSpacing = (-0.01).em), color = Civic.colors.text)
}

/** Debajo de la hora: GPS y OBD como dos puntitos con su letra. */
@Composable
private fun RailStatus() {
    val c = LocalContext.current.container
    val gps by c.gps.speed.collectAsStateWithLifecycle()
    val obdConfig by c.obd.config.collectAsStateWithLifecycle()
    val obdState by c.obd.state.collectAsStateWithLifecycle()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Dot(if (gps.fresh) Tone.Ok else Tone.Warn, 7)
        Spacer(Modifier.width(3.dp))
        Text("GPS", style = Civic.type.caption.copy(fontSize = 10.sp), color = Civic.colors.textDim)
    }
    if (obdConfig.mode != ObdMode.NONE) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(if (obdState is EngineState.Connected) Tone.Ok else Tone.Warn, 7)
            Spacer(Modifier.width(3.dp))
            Text("OBD", style = Civic.type.caption.copy(fontSize = 10.sp), color = Civic.colors.textDim)
        }
    }
}

/** Acceso del riel: ícono blanco sobre "squircle" de color con degradado (como los íconos de CarPlay). */
@Composable
private fun RailButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, colors: Pair<Color, Color>, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier
            .size(52.dp)
            .clip(shape)
            .background(Brush.verticalGradient(if (pressed) listOf(colors.second, colors.second) else listOf(colors.first, colors.second)))
            .bevelBorder(shape)
            .clickable(source, indication = null, role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(28.dp))
    }
}

/** Solo si la pantalla sigue con la hora de China: un aviso que lleva al ajuste. */
@Composable
private fun TimezoneWarning() {
    val ctx = LocalContext.current
    val tz by ctx.container.clock.timeZone.collectAsStateWithLifecycle()
    if (tz != "Asia/Shanghai") return
    Box(
        Modifier.clip(RoundedCornerShape(50)).clickable {
            runCatching { ctx.startActivity(Intent(Settings.ACTION_DATE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        },
    ) { StatusPill("Zona horaria de China", Tone.Warn) }
    Spacer(Modifier.width(Civic.space.m))
}

// ------------------------------------------------------------------ Columna derecha

/** Velocidad grande y la barra de RPM (en lugar del clima del diseño original). */
@Composable
private fun SpeedCard(modifier: Modifier, onClick: () -> Unit) {
    val c = LocalContext.current.container
    val obdState by c.obd.state.collectAsStateWithLifecycle()
    val obdData by c.obd.data.collectAsStateWithLifecycle()
    val (speed, fromObd) = rememberSpeed()
    val rpm = if (obdState is EngineState.Connected) obdData[Pids.RPM].fresh()?.value else null
    HomeCard(modifier, onClick = onClick) {
        CardHeader(P.CarSimpleLight, "Vehículo", if (fromObd) "OBD" else "GPS", if (speed != null) Tone.Ok else Tone.Warn)
        Spacer(Modifier.weight(1f))
        BigSpeed(speed, 58)
        Spacer(Modifier.weight(1f))
        RpmBar(rpm)
    }
}

/** Motor y batería, con la raya de color a la izquierda (como la tarjeta de calendario del diseño). */
@Composable
private fun EngineCard(modifier: Modifier, onClick: () -> Unit) {
    val c = LocalContext.current.container
    val config by c.obd.config.collectAsStateWithLifecycle()
    val obdState by c.obd.state.collectAsStateWithLifecycle()
    val obdData by c.obd.data.collectAsStateWithLifecycle()
    val live by c.obd.live.collectAsStateWithLifecycle()
    val connected = obdState is EngineState.Connected
    val coolant = if (connected) obdData[Pids.COOLANT_TEMP].fresh()?.value else null
    val battery = obdData.battery.fresh(30_000)?.value
    val alert = live.alerts.firstOrNull()
    // La raya de color dice el estado de un vistazo: rojo peligro, ámbar aviso, verde todo bien.
    val tone = when {
        config.mode == ObdMode.NONE -> Civic.colors.textFaint
        alert?.level == com.civiclauncher.obd.AlertLevel.DANGER -> Civic.colors.bad
        alert != null -> Civic.colors.warn
        connected -> Civic.colors.ok
        else -> Civic.colors.textFaint
    }
    HomeCard(modifier, onClick = onClick) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(4.dp).fillMaxHeight(0.8f).clip(RoundedCornerShape(50)).background(tone))
            Spacer(Modifier.width(Civic.space.m))
            Column(Modifier.weight(1f)) {
                Text("MOTOR", style = Civic.type.caption.copy(letterSpacing = 0.10.em), color = Civic.colors.textDim)
                Spacer(Modifier.height(2.dp))
                when {
                    config.mode == ObdMode.NONE -> {
                        Text("Sin adaptador OBD", style = Civic.type.title.copy(fontSize = 20.sp), color = Civic.colors.text, maxLines = 1)
                        Text("Toca para conectarlo", style = Civic.type.caption, color = Civic.colors.textDim)
                    }
                    alert != null -> {
                        Text(alert.title, style = Civic.type.title.copy(fontSize = 20.sp), color = Civic.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(alert.detail, style = Civic.type.caption, color = Civic.colors.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    else -> {
                        Text(
                            "${coolant?.let { "%.0f °C".format(it) } ?: "-- °C"}   ·   ${battery?.let { "%.1f V".format(it) } ?: "-- V"}",
                            style = Civic.type.title.copy(fontSize = 22.sp, fontFeatureSettings = "tnum"),
                            color = Civic.colors.text,
                            maxLines = 1,
                        )
                        Text(
                            live.kmPerLiter?.let { if (it >= 99) "Sin gastar gasolina" else "%.1f km/L al instante".format(it) }
                                ?: if (connected) "Sin fallas" else obdStatus(obdState, config).first,
                            style = Civic.type.caption,
                            color = Civic.colors.textDim,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}
// ------------------------------------------------------------------ Barra superior

@Composable
private fun TopBar(onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val c = ctx.container
    val now by c.clock.now.collectAsStateWithLifecycle()
    val tz by c.clock.timeZone.collectAsStateWithLifecycle()
    val es = remember { Locale("es", "MX") }
    val is24 = DateFormat.is24HourFormat(ctx)
    val time = remember(now, tz, is24) {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        if (is24) {
            SimpleDateFormat("HH:mm", es).format(Date(now))
        } else {
            val h = cal.get(Calendar.HOUR).let { if (it == 0) 12 else it }
            "%d:%02d %s".format(h, cal.get(Calendar.MINUTE), if (cal.get(Calendar.AM_PM) == Calendar.AM) "a.m." else "p.m.")
        }
    }
    // Minimalista: solo la hora (el "a.m./p.m." más chico y tenue) y el engrane de Ajustes.
    val (clock, suffix) = remember(time) {
        val i = time.indexOf(' ')
        if (i > 0) time.substring(0, i) to time.substring(i + 1) else time to null
    }
    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            clock,
            style = Civic.type.headline.copy(fontSize = 34.sp, lineHeight = 40.sp, fontFeatureSettings = "tnum", letterSpacing = (-0.02).em),
            color = Civic.colors.text,
        )
        if (suffix != null) {
            Spacer(Modifier.width(6.dp))
            Text(suffix, style = Civic.type.label, color = Civic.colors.textDim, modifier = Modifier.padding(top = 10.dp))
        }
        Spacer(Modifier.weight(1f))
        if (tz == "Asia/Shanghai") {
            // La pantalla venía con la hora de China: un toque lleva al ajuste.
            Box(
                Modifier.clip(RoundedCornerShape(50)).clickable {
                    runCatching { ctx.startActivity(Intent(Settings.ACTION_DATE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                },
            ) { StatusPill("Zona horaria de China", Tone.Warn) }
            Spacer(Modifier.width(Civic.space.m))
        }
        TopIcon(P.GearSixLight, "Ajustes", onSettings)
    }
}

@Composable
private fun Dot(tone: Tone, size: Int = 10) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(tone.color()))
}

@Composable
private fun TopIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box(
        Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(if (pressed) Civic.colors.surfacePressed else Civic.colors.background)
            .clickable(source, indication = null, role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = Civic.colors.textDim, modifier = Modifier.size(26.dp))
    }
}

// ------------------------------------------------------------------ Mi vehículo

/**
 * "Mi vehículo". Sin foto: tablero digital (barra de RPM por segmentos, velocidad grande,
 * temperatura y batería). Con foto: tu carro y la velocidad.
 */
@Composable
private fun VehicleCard(modifier: Modifier, onClick: () -> Unit) {
    val c = LocalContext.current.container
    val photo by c.carPhoto.photo.collectAsStateWithLifecycle()
    val obdState by c.obd.state.collectAsStateWithLifecycle()
    val obdData by c.obd.data.collectAsStateWithLifecycle()
    val (speed, fromObd) = rememberSpeed()
    val connected = obdState is EngineState.Connected
    val rpm = if (connected) obdData[Pids.RPM].fresh()?.value else null
    val coolant = if (connected) obdData[Pids.COOLANT_TEMP].fresh()?.value else null
    val battery = obdData.battery.fresh(30_000)?.value

    HomeCard(modifier, onClick = onClick) {
        CardHeader(P.CarSimpleLight, "Mi vehículo", if (fromObd) "OBD" else "GPS", if (speed != null) Tone.Ok else Tone.Warn)
        val p = photo
        if (p != null) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = Civic.space.s), contentAlignment = Alignment.Center) {
                Image(p, contentDescription = "Tu carro", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
            BigSpeed(speed, 56)
        } else {
            Spacer(Modifier.height(Civic.space.l))
            RpmBar(rpm)
            Spacer(Modifier.weight(1f))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { BigSpeed(speed, 84) }
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(Civic.space.s)) {
                MiniStat(
                    "MOTOR",
                    coolant?.let { "%.0f".format(it) } ?: "--",
                    "°C",
                    when {
                        coolant == null -> Civic.colors.textFaint
                        coolant >= 105 -> Civic.colors.bad
                        coolant >= 100 -> Civic.colors.warn
                        else -> Civic.colors.text
                    },
                    Modifier.weight(1f),
                )
                MiniStat(
                    "BATERÍA",
                    battery?.let { "%.1f".format(it) } ?: "--",
                    "V",
                    when {
                        battery == null -> Civic.colors.textFaint
                        battery < 11.8 -> Civic.colors.bad
                        battery < 12.2 -> Civic.colors.warn
                        else -> Civic.colors.text
                    },
                    Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun BigSpeed(speed: Double?, sizeSp: Int) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            speed?.let { "%.0f".format(it) } ?: "--",
            style = Civic.type.gaugeValue.copy(fontSize = sizeSp.sp, lineHeight = (sizeSp + 6).sp),
            color = if (speed != null) Civic.colors.text else Civic.colors.textFaint,
        )
        Spacer(Modifier.width(Civic.space.s))
        Text("km/h", style = Civic.type.title.copy(fontSize = 18.sp), color = Civic.colors.textDim, modifier = Modifier.padding(bottom = (sizeSp / 7).dp))
    }
}

/** Barra de RPM en 24 segmentos: azul normal, ámbar desde 5 000 y rojo desde 6 000 rpm. */
@Composable
private fun RpmBar(rpm: Double?) {
    val c = Civic.colors
    val fraction = ((rpm ?: 0.0) / 7_000.0).coerceIn(0.0, 1.0).toFloat()
    Column {
        Row {
            Text("RPM", style = Civic.type.caption, color = c.textFaint)
            Spacer(Modifier.weight(1f))
            Text(rpm?.let { "%,d".format(it.toInt()) } ?: "--", style = Civic.type.caption, color = if (rpm != null) c.text else c.textFaint)
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(12.dp)
                .drawWithCache {
                    val n = 24
                    val gap = 3.dp.toPx()
                    val w = (size.width - gap * (n - 1)) / n
                    val radius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())
                    val lit = (fraction * n).let { kotlin.math.ceil(it.toDouble()).toInt() }
                    onDrawBehind {
                        for (i in 0 until n) {
                            val color = when {
                                i >= lit -> c.track
                                i >= n * 6 / 7 -> c.bad
                                i >= n * 5 / 7 -> c.warn
                                else -> c.accent
                            }
                            drawRoundRect(color, Offset(i * (w + gap), 0f), androidx.compose.ui.geometry.Size(w, size.height), radius)
                        }
                    }
                },
        )
    }
}

@Composable
private fun MiniStat(label: String, value: String, unit: String, valueColor: Color, modifier: Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(Civic.radii.l))
            .background(Civic.colors.surfaceRaised)
            .padding(horizontal = Civic.space.m, vertical = Civic.space.m),
    ) {
        Text(label, style = Civic.type.caption, color = Civic.colors.textFaint)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = Civic.type.number, color = valueColor)
            Spacer(Modifier.width(Civic.space.xs))
            Text(unit, style = Civic.type.gaugeUnit, color = Civic.colors.textDim, modifier = Modifier.padding(bottom = 4.dp))
        }
    }
}


// ------------------------------------------------------------------ Mapa

/**
 * Tarjeta de mapa: el mapa ocupa TODA la tarjeta (como te gustaba). Encima, una cápsula pequeña con
 * el estado y, abajo, la calle actual. Un toque abre el mapa completo.
 */
@Composable
private fun MapCard(modifier: Modifier, onOpen: () -> Unit) {
    val c = LocalContext.current.container
    val status by c.map.status.collectAsStateWithLifecycle()
    val gps by c.gps.speed.collectAsStateWithLifecycle()
    val ready = status as? MapStatus.Ready
    // Por si copiaste el mapa por adb o USB mientras Civic estaba abierto.
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { c.map.refresh() }

    if (ready == null) {
        HomeCard(modifier) {
            CardHeader(P.MapTrifoldLight, "Mapa", "Sin descargar", Tone.Warn)
            MapSetup(status, Modifier.weight(1f).fillMaxWidth(), compact = true)
        }
        return
    }
    LaunchedEffect(gps.lat, gps.lon) { c.map.rememberPosition(gps) }
    val (center, hasFix) = mapCenter(gps, remember { c.map.lastPosition() })
    val street = rememberStreet(gps)
    val shape = RoundedCornerShape(Civic.radii.xl)

    // Las cápsulas encima del mapa van opacas aunque haya fondo: si no, las calles tapan el texto.
    com.civiclauncher.app.ui.design.OpaqueSurfaces { Box(modifier.clip(shape).background(Civic.colors.surface)) {
        CivicMap(
            file = ready.file,
            cacheName = MAP_CACHE_CARD,
            center = center,
            bearing = gps.bearing,
            hasFix = hasFix,
            follow = true,
            night = Civic.colors.isDark,
            initialZoom = 16,
            modifier = Modifier.fillMaxSize(),
            interactive = false,
        )
        // Capa transparente encima del mapa: el toque abre el mapa completo en lugar de moverlo.
        Box(Modifier.fillMaxSize().clickable(onClick = onOpen))
        // Cápsula pequeña en la esquina (mismo estilo que el encabezado de las otras tarjetas).
        Row(
            Modifier
                .align(Alignment.TopStart)
                .padding(Civic.space.m)
                .clip(RoundedCornerShape(50))
                .background(Civic.colors.surface)
                .padding(horizontal = Civic.space.m, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(P.MapTrifoldLight, null, tint = Civic.colors.text, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Civic.space.s))
            Text("Mapa", style = Civic.type.label, color = Civic.colors.text)
            Spacer(Modifier.width(Civic.space.s))
            Dot(if (hasFix) Tone.Ok else Tone.Warn)
        }
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(Civic.space.m)
                .fillMaxWidth()
                .height(52.dp)
                .clip(RoundedCornerShape(50))
                .background(Civic.colors.surface)
                .padding(horizontal = Civic.space.l),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(P.NavigationArrowFill, null, tint = Civic.colors.accent, modifier = Modifier.size(22.dp).rotate(45f))
            Spacer(Modifier.width(Civic.space.m))
            Text(
                street ?: if (hasFix) "Calle sin nombre" else "Buscando tu ubicación…",
                style = Civic.type.label,
                color = Civic.colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(P.CaretRight, null, tint = Civic.colors.textDim, modifier = Modifier.size(22.dp))
        }
        // El borde va encima del mapa (el mapa es una vista de Android y taparía un borde de fondo).
        Box(Modifier.matchParentSize().bevelBorder(shape))
    } }
}

