package com.civiclauncher.app.launcher.map

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.civiclauncher.app.container
import com.civiclauncher.app.launcher.data.GpsSpeed
import com.civiclauncher.app.launcher.data.VendorApps
import com.civiclauncher.app.ui.design.ButtonKind
import com.civiclauncher.app.ui.design.Civic
import com.civiclauncher.app.ui.design.CivicButton
import com.civiclauncher.app.ui.design.LinearMeter
import com.civiclauncher.app.ui.design.PhosphorIcons as P
import com.civiclauncher.app.ui.design.Tone
import org.mapsforge.core.model.LatLong

/**
 * Nombres de la caché de mosaicos del mapa. Si cambia el estilo (capas visibles), sube la versión:
 * así no se muestran mosaicos viejos guardados en disco.
 */
const val MAP_CACHE_CARD = "map-card-v2"
const val MAP_CACHE_FULL = "map-full-v2"

/** Centro de México: solo si no hay GPS ni una posición guardada. */
val MEXICO_CENTER = LatLong(23.6345, -102.5528)

/** Posición a mostrar: la del GPS, o la última guardada, o el centro del país. */
fun mapCenter(gps: GpsSpeed, last: LatLong?): Pair<LatLong, Boolean> {
    val lat = gps.lat
    val lon = gps.lon
    return if (lat != null && lon != null) LatLong(lat, lon) to gps.fresh else (last ?: MEXICO_CENTER) to false
}

/** Calle actual: se recalcula cuando avanzas unos ~20 m. */
@Composable
fun rememberStreet(gps: GpsSpeed): String? {
    val repo = LocalContext.current.container.map
    var street by remember { mutableStateOf<String?>(null) }
    val lat = gps.lat
    val lon = gps.lon
    val key = if (lat != null && lon != null) Math.round(lat * 5_000) to Math.round(lon * 5_000) else null
    LaunchedEffect(key) {
        if (lat != null && lon != null) street = repo.streetAt(lat, lon, gps.bearing)
    }
    return street
}

/** Pantalla de mapa completa (botón "Navegador"). */
@Composable
fun MapScreen(night: Boolean) {
    val ctx = LocalContext.current
    val c = ctx.container
    val status by c.map.status.collectAsStateWithLifecycle()
    val ready = status as? MapStatus.Ready
    if (ready == null) {
        MapSetup(status, Modifier.fillMaxSize().padding(Civic.space.xl))
        return
    }
    val gps by c.gps.speed.collectAsStateWithLifecycle()
    LaunchedEffect(gps.lat, gps.lon) { c.map.rememberPosition(gps) }
    val (center, hasFix) = mapCenter(gps, remember { c.map.lastPosition() })
    val street = rememberStreet(gps)
    var follow by remember { mutableStateOf(true) }
    val handle = remember { MapHandle() }
    val headingUp by c.launcherPrefs.mapHeadingUp.collectAsStateWithLifecycle()

    // Encima del mapa, todo opaco aunque haya fondo de pantalla: si no, las calles se ven a través del texto.
    com.civiclauncher.app.ui.design.OpaqueSurfaces { Box(Modifier.fillMaxSize().padding(start = Civic.space.l, end = Civic.space.l, top = Civic.space.m, bottom = Civic.space.m)) {
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(Civic.radii.xl)).border(1.dp, Civic.colors.line, RoundedCornerShape(Civic.radii.xl))) {
            CivicMap(
                file = ready.file,
                cacheName = MAP_CACHE_FULL,
                center = center,
                bearing = gps.bearing,
                hasFix = hasFix,
                follow = follow,
                night = night,
                initialZoom = if (gps.lat != null) 16 else 13,
                modifier = Modifier.fillMaxSize(),
                handle = handle,
                onUserMoved = { follow = false },
                headingUp = headingUp,
            )
        }
        // Calle actual y velocidad
        Row(Modifier.align(Alignment.TopStart).padding(Civic.space.l), verticalAlignment = Alignment.CenterVertically) {
            Bubble {
                Icon(P.MapPin, null, tint = Civic.colors.text, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(Civic.space.s))
                Text(
                    street ?: if (hasFix) "Calle sin nombre" else "Buscando tu ubicación…",
                    style = Civic.type.label,
                    color = Civic.colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 420.dp),
                )
            }
        }
        Column(Modifier.align(Alignment.BottomStart).padding(Civic.space.l)) {
            val (speed, _) = com.civiclauncher.app.launcher.ui.rememberSpeed()
            Bubble {
                Text(speed?.let { "%.0f".format(it) } ?: "--", style = Civic.type.number.copy(fontSize = 30.sp), color = Civic.colors.text)
                Spacer(Modifier.width(Civic.space.xs))
                Text("km/h", style = Civic.type.caption, color = Civic.colors.textDim)
            }
        }
        // Controles
        Column(Modifier.align(Alignment.CenterEnd).padding(Civic.space.l), verticalArrangement = Arrangement.spacedBy(Civic.space.m)) {
            MapButton(P.ArrowsClockwise, "Acercar", label = "+") { handle.zoomIn() }
            MapButton(P.ArrowsClockwise, "Alejar", label = "−") { handle.zoomOut() }
            MapButton(P.NavigationArrowFill, "Centrar en mi ubicación", highlighted = !follow) { follow = true }
            // Brújula: rumbo arriba (lo de enfrente arriba) o norte arriba. Se recuerda para la próxima vez.
            MapButton(
                P.CompassFill,
                if (headingUp) "Cambiar a norte arriba" else "Cambiar a rumbo arriba",
                label = if (headingUp) null else "N",
            ) { c.launcherPrefs.setMapHeadingUp(!headingUp) }
        }
        Row(Modifier.align(Alignment.BottomEnd).padding(Civic.space.l)) {
            CivicButton("Ruta en Android Auto", {
                if (!c.apps.launchPackage(VendorApps.ANDROID_AUTO)) Toast.makeText(ctx, "AutoKit no está instalado", Toast.LENGTH_SHORT).show()
            }, icon = P.NavigationArrow)
        }
    } }
}

@Composable
private fun Bubble(content: @Composable () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(Civic.colors.surface)
            .border(1.dp, Civic.colors.line, RoundedCornerShape(50))
            .padding(horizontal = Civic.space.l, vertical = Civic.space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
private fun MapButton(icon: ImageVector, description: String, label: String? = null, highlighted: Boolean = false, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val bg = when {
        pressed -> Civic.colors.surfacePressed
        highlighted -> Civic.colors.accent
        else -> Civic.colors.surface
    }
    Box(
        Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(bg)
            .border(1.dp, Civic.colors.line, CircleShape)
            .clickable(source, indication = null, role = Role.Button, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (label != null) {
            Text(label, style = Civic.type.headline.copy(fontSize = 32.sp, lineHeight = 34.sp), color = Civic.colors.text)
        } else {
            Icon(icon, description, tint = if (highlighted) Civic.colors.onAccent else Civic.colors.text, modifier = Modifier.size(28.dp))
        }
    }
}

/** Cuando todavía no hay mapa: descargar por WiFi o importar desde USB. */
@Composable
fun MapSetup(status: MapStatus, modifier: Modifier = Modifier, compact: Boolean = false) {
    val ctx = LocalContext.current
    val repo = ctx.container.map
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) repo.import(uri) { ok -> if (ok) Toast.makeText(ctx, "Mapa listo", Toast.LENGTH_SHORT).show() }
    }
    Column(modifier, verticalArrangement = Arrangement.Center) {
        Icon(P.MapTrifold, null, tint = Civic.colors.textDim, modifier = Modifier.size(if (compact) 36.dp else 48.dp))
        Spacer(Modifier.height(Civic.space.m))
        Text("Mapa sin internet", style = if (compact) Civic.type.title else Civic.type.headline, color = Civic.colors.text)
        Text(
            if (compact) "Descárgalo una vez y funciona sin datos."
            else "El mapa de México (OpenStreetMap, ${MapRepository.MEXICO_SIZE_MB} MB) se descarga una sola vez con el WiFi de tu casa " +
                "y después funciona sin internet, con los nombres de las calles.",
            style = Civic.type.bodySmall,
            color = Civic.colors.textDim,
        )
        Spacer(Modifier.height(Civic.space.l))
        when (status) {
            is MapStatus.Downloading -> {
                Text(
                    "Descargando… ${status.downloadedMb} de ${MapRepository.MEXICO_SIZE_MB} MB" +
                        (status.progress?.let { " (${(it * 100).toInt()} %)" } ?: ""),
                    style = Civic.type.label,
                    color = Civic.colors.text,
                )
                Spacer(Modifier.height(Civic.space.s))
                LinearMeter(status.progress ?: 0f, Tone.Accent)
                Spacer(Modifier.height(Civic.space.m))
                CivicButton("Cancelar", { repo.cancelDownload() }, kind = ButtonKind.Secondary)
            }
            is MapStatus.Importing -> {
                Text("Copiando el mapa… ${(status.progress * 100).toInt()} %", style = Civic.type.label, color = Civic.colors.text)
                Spacer(Modifier.height(Civic.space.s))
                LinearMeter(status.progress, Tone.Accent)
            }
            else -> {
                if (status is MapStatus.Error) {
                    Text(status.message, style = Civic.type.bodySmall, color = Civic.colors.bad)
                    Spacer(Modifier.height(Civic.space.m))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Civic.space.m)) {
                    CivicButton(
                        if (compact) "Descargar" else "Descargar por WiFi",
                        { repo.startDownload() },
                        if (compact) Modifier.weight(1f) else Modifier,
                        icon = P.DownloadSimple,
                    )
                    if (!compact) {
                        CivicButton("Importar desde USB", {
                            runCatching { picker.launch(arrayOf("*/*")) }
                                .onFailure { Toast.makeText(ctx, "No hay app para elegir archivos", Toast.LENGTH_SHORT).show() }
                        }, kind = ButtonKind.Secondary, icon = P.Folder)
                    }
                }
            }
        }
    }
}
