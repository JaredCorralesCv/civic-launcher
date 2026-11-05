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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
    Column(Modifier.fillMaxSize().padding(start = Civic.space.l, end = Civic.space.l, top = Civic.space.m, bottom = Civic.space.m)) {
        TopBar(onSettings = { onOpen(Dest.SETTINGS) })
        Spacer(Modifier.height(Civic.space.m))
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(Civic.space.l)) {
            VehicleCard(Modifier.weight(1f).fillMaxHeight(), onClick = { onOpen(Dest.VEHICLE) })
            MapCard(Modifier.weight(1.2f).fillMaxHeight(), onOpen = { onOpen(Dest.MAP) })
            MusicCard(Modifier.weight(1f).fillMaxHeight(), onOpen = { onOpen(Dest.MUSIC) })
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
    val date = remember(now, tz) { SimpleDateFormat("EEEE, d 'de' MMMM", es).format(Date(now)).replaceFirstChar { it.titlecase(es) } }

    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(time, style = Civic.type.headline.copy(fontSize = 38.sp, lineHeight = 44.sp, fontFeatureSettings = "tnum"), color = Civic.colors.text)
        Spacer(Modifier.width(Civic.space.xl))
        Icon(P.CalendarBlank, null, tint = Civic.colors.textDim, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(Civic.space.s))
        Text(date, style = Civic.type.title.copy(fontSize = 20.sp), color = Civic.colors.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (tz == "Asia/Shanghai") {
            // La pantalla venía con la hora de China: un toque lleva al ajuste.
            Box(
                Modifier.clip(RoundedCornerShape(50)).clickable {
                    runCatching { ctx.startActivity(Intent(Settings.ACTION_DATE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                },
            ) { StatusPill("Zona horaria de China", Tone.Warn) }
            Spacer(Modifier.width(Civic.space.m))
        }
        StatusCapsule()
        Spacer(Modifier.width(Civic.space.m))
        TopIcon(P.GearSix, "Ajustes", onSettings)
    }
}

/** Estado del OBD y del GPS juntos en una sola cápsula, sin rayas divisorias. */
@Composable
private fun StatusCapsule() {
    val c = LocalContext.current.container
    val obdConfig by c.obd.config.collectAsStateWithLifecycle()
    val obdState by c.obd.state.collectAsStateWithLifecycle()
    val gps by c.gps.speed.collectAsStateWithLifecycle()
    Row(
        Modifier
            .height(44.dp)
            .clip(RoundedCornerShape(50))
            .background(Civic.colors.surfaceRaised)
            .padding(horizontal = Civic.space.l),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (obdConfig.mode != ObdMode.NONE) {
            val (text, tone) = when (obdState) {
                is EngineState.Connected -> "OBD" to Tone.Ok
                is EngineState.CarOff -> "OBD · apagado" to Tone.Cool
                is EngineState.Reconnecting -> "OBD · sin conexión" to Tone.Bad
                EngineState.Stopped -> "OBD · pausa" to Tone.Neutral
                else -> "OBD · conectando" to Tone.Warn
            }
            Dot(tone)
            Spacer(Modifier.width(Civic.space.s))
            Text(text, style = Civic.type.label, color = Civic.colors.textDim)
            Spacer(Modifier.width(Civic.space.l))
        }
        Icon(P.MapPin, null, tint = if (gps.fresh) Civic.colors.ok else Civic.colors.textDim, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(Civic.space.s))
        Text(
            when {
                !gps.permission -> "GPS sin permiso"
                !gps.enabled -> "GPS apagado"
                gps.fresh && gps.satsUsed > 0 -> "GPS · ${gps.satsUsed}"
                gps.fresh -> "GPS"
                else -> "Buscando GPS"
            },
            style = Civic.type.label,
            color = Civic.colors.textDim,
        )
    }
}

@Composable
private fun Dot(tone: Tone) {
    Box(Modifier.size(10.dp).clip(CircleShape).background(tone.color()))
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
        Icon(icon, label, tint = Civic.colors.text, modifier = Modifier.size(30.dp))
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
        CardHeader(P.Car, "Mi vehículo", if (fromObd) "OBD" else "GPS", if (speed != null) Tone.Ok else Tone.Warn)
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
                                i >= lit -> c.surfaceRaised
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
            CardHeader(P.MapTrifold, "Mapa", "Sin descargar", Tone.Warn)
            MapSetup(status, Modifier.weight(1f).fillMaxWidth(), compact = true)
        }
        return
    }
    LaunchedEffect(gps.lat, gps.lon) { c.map.rememberPosition(gps) }
    val (center, hasFix) = mapCenter(gps, remember { c.map.lastPosition() })
    val street = rememberStreet(gps)
    val shape = RoundedCornerShape(Civic.radii.xl)

    Box(modifier.clip(shape).background(Civic.colors.surface)) {
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
            Icon(P.MapTrifold, null, tint = Civic.colors.text, modifier = Modifier.size(18.dp))
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
    }
}

