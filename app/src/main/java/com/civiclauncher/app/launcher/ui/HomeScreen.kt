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
import com.civiclauncher.app.ui.design.Civic
import com.civiclauncher.app.ui.design.PhosphorIcons as P
import com.civiclauncher.app.ui.design.StatusPill
import com.civiclauncher.app.ui.design.Tone
import com.civiclauncher.app.ui.design.bevelBorder
import com.civiclauncher.app.ui.design.color
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
        // Solo la hora y accesos discretos: el estado del GPS y del OBD ya lo dicen sus tarjetas.
        RailClock()
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

// ------------------------------------------------------------------ Barra superior

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

// ------------------------------------------------------------------ Mapa

/**
 * Tarjeta de mapa: el mapa ocupa TODA la tarjeta (como te gustaba). Encima, una cápsula pequeña con
 * el estado y, abajo, la calle actual. Un toque abre el mapa completo.
 */
@Composable
private fun MapCard(modifier: Modifier, onOpen: () -> Unit) {
    val c = LocalContext.current.container
    val status by c.map.status.collectAsStateWithLifecycle()
    val gpsState = c.gps.speed.collectAsStateWithLifecycle()
    // Con Inicio escondido (estás en otra pantalla) no se lee el GPS: la tarjeta se queda con la última
    // ubicación y no se vuelve a armar cada segundo para nada. Al volver, retoma al instante.
    val active = LocalScreenActive.current
    val lastGps = remember { arrayOf(gpsState.value) }
    val gps = if (active) gpsState.value.also { lastGps[0] = it } else lastGps[0]
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
            headingUp = c.launcherPrefs.mapHeadingUp.collectAsStateWithLifecycle().value,
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
                .bevelBorder(RoundedCornerShape(50))
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
                // Filo azul: es la "ruta" del mapa, del mismo color que la flecha del carro.
                .border(1.dp, Civic.colors.accent.copy(alpha = 0.55f), RoundedCornerShape(50))
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

