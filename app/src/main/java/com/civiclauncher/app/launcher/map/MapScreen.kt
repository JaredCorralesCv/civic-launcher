package com.civiclauncher.app.launcher.map

import androidx.compose.ui.text.font.FontWeight
import com.civiclauncher.app.launcher.nav.SearchPanel
import com.civiclauncher.app.launcher.nav.ArrivedPanel
import com.civiclauncher.app.launcher.nav.PreviewPanel
import com.civiclauncher.app.launcher.nav.NavFooter
import com.civiclauncher.app.launcher.nav.NavBanner
import com.civiclauncher.app.launcher.nav.Place
import com.civiclauncher.app.launcher.nav.NavState
import com.civiclauncher.app.launcher.nav.meters
import com.civiclauncher.app.launcher.nav.blockTouches
import com.civiclauncher.app.ui.design.bevelBorder
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

/** Centro de México: solo si no hay GPS ni una posición guardada. */
val MEXICO_CENTER = LatLong(23.6345, -102.5528)

/** Posición a mostrar: la del GPS, o la última guardada, o el centro del país. */
fun mapCenter(gps: GpsSpeed, last: LatLong?): Pair<LatLong, Boolean> {
    val lat = gps.lat
    val lon = gps.lon
    return if (lat != null && lon != null) LatLong(lat, lon) to gps.fresh else (last ?: MEXICO_CENTER) to false
}

/** ¿Va lo bastante rápido como para alejar el mapa un nivel? */
@Composable
internal fun rememberFastForMap(kmh: Float?): Boolean {
    val last = remember { booleanArrayOf(false) }
    return isFastForMap(last[0], kmh).also { last[0] = it }
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

/**
 * Mapa completo con navegación propia (sin Android Auto ni internet):
 *  - Arriba: "¿A dónde vamos?" (buscar), o la siguiente vuelta mientras navegas.
 *  - Mantener el dedo en el mapa elige ese punto como destino.
 *  - Abajo: tus lugares (Casa, Trabajo), el destino elegido con "Ir", o la llegada y "Terminar".
 */
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
    val nav by c.nav.state.collectAsStateWithLifecycle()
    val download by c.nav.download.collectAsStateWithLifecycle()
    val home by c.nav.home.collectAsStateWithLifecycle()
    val work by c.nav.work.collectAsStateWithLifecycle()
    var searching by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    val navigating = nav as? NavState.Navigating
    val preview = nav as? NavState.Preview
    // Al arrancar la ruta, el mapa vuelve a seguirte.
    LaunchedEffect(navigating != null) { if (navigating != null) follow = true }

    // Encima del mapa, todo opaco aunque haya fondo de pantalla: si no, las calles se ven a través del texto.
    com.civiclauncher.app.ui.design.OpaqueSurfaces { Box(Modifier.fillMaxSize().padding(start = Civic.space.l, end = Civic.space.l, top = Civic.space.m, bottom = Civic.space.m)) {
        val mapShape = RoundedCornerShape(Civic.radii.xl)
        Box(Modifier.fillMaxSize().clip(mapShape)) {
            CivicMap(
                file = ready.file,
                // Navegando, el carro va pegado a la ruta (no tiembla fuera de la calle).
                center = navigating?.progress?.position ?: center,
                bearing = navigating?.progress?.bearing ?: gps.bearing,
                hasFix = hasFix || navigating != null,
                follow = follow,
                night = night,
                initialZoom = if (gps.lat != null) 16 else 13,
                modifier = Modifier.fillMaxSize(),
                handle = handle,
                onUserMoved = { follow = false },
                headingUp = headingUp,
                route = navigating?.route ?: preview?.route,
                routeFrom = navigating?.progress?.segment ?: 0,
                destination = (navigating?.dest ?: preview?.dest)?.point,
                // Mantener el dedo: pone el pin ahí y entra a "elegir en el mapa" (como Uber o DiDi):
                // puedes arrastrar el mapa para afinar el punto y luego confirmar.
                onLongPress = { p ->
                    follow = false
                    picking = true
                    handle.moveTo(p)
                },
                fast = rememberFastForMap(gps.kmh),
            )
            if (picking) CenterPin(Modifier.align(Alignment.Center))
            // El borde va encima del mapa (el mapa es una vista de Android y taparía un borde de fondo).
            Box(Modifier.matchParentSize().bevelBorder(mapShape))
        }

        // ---- Arriba a la izquierda: buscar, o la siguiente vuelta.
        Column(Modifier.align(Alignment.TopStart).padding(Civic.space.l), verticalArrangement = Arrangement.spacedBy(Civic.space.s)) {
            if (navigating != null) {
                NavBanner(navigating.progress, navigating.rerouting)
            } else {
                SearchPill { searching = true }
            }
            Bubble {
                Icon(P.MapPin, null, tint = Civic.colors.textDim, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Civic.space.s))
                Text(
                    street ?: if (hasFix) "Calle sin nombre" else "Buscando tu ubicación…",
                    style = Civic.type.label,
                    color = Civic.colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 380.dp),
                )
            }
        }

        // ---- Derecha: acercar, alejar, centrar y brújula.
        // (Mientras buscas se esconden: el teclado ocupa esa esquina.)
        if (!searching) Column(Modifier.align(Alignment.CenterEnd).padding(Civic.space.l), verticalArrangement = Arrangement.spacedBy(Civic.space.m)) {
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

        // ---- Abajo a la izquierda: velocidad.
        Column(Modifier.align(Alignment.BottomStart).padding(Civic.space.l)) {
            val (speed, _) = com.civiclauncher.app.launcher.ui.rememberSpeed()
            Bubble {
                Text(speed?.let { "%.0f".format(it) } ?: "--", style = Civic.type.number.copy(fontSize = 30.sp), color = Civic.colors.text)
                Spacer(Modifier.width(Civic.space.xs))
                Text("km/h", style = Civic.type.caption, color = Civic.colors.textDim)
            }
        }

        // ---- Abajo a la derecha (junto a los botones): según lo que estés haciendo.
        Box(Modifier.align(Alignment.BottomEnd).padding(end = 104.dp, bottom = Civic.space.l)) {
            if (picking) {
                PickPanel(
                    handle = handle,
                    navigating = navigating != null,
                    onConfirm = { place ->
                        picking = false
                        follow = true
                        if (navigating != null) c.nav.redirect(place) else c.nav.preview(place)
                    },
                    onCancel = {
                        picking = false
                        follow = true
                    },
                )
            } else when (val s = nav) {
                is NavState.Navigating -> NavFooter(s.progress, onStop = { c.nav.stop() })
                is NavState.Preview -> PreviewPanel(
                    s, download,
                    onGo = { c.nav.start() },
                    onSimulate = { c.nav.start(simulate = true) },
                    onCancel = { c.nav.stop() },
                    onDownload = { c.nav.downloadTiles(s.missing) { c.nav.preview(s.dest) } },
                    onSaveHome = {
                        c.nav.setHome(s.dest)
                        Toast.makeText(ctx, "Guardado como Casa", Toast.LENGTH_SHORT).show()
                    },
                    onSaveWork = {
                        c.nav.setWork(s.dest)
                        Toast.makeText(ctx, "Guardado como Trabajo", Toast.LENGTH_SHORT).show()
                    },
                )
                is NavState.Arrived -> ArrivedPanel(s.dest, onDone = { c.nav.stop() })
                NavState.Idle -> Row(horizontalArrangement = Arrangement.spacedBy(Civic.space.s)) {
                    home?.let { PlaceChip(P.HouseFill, "Casa") { c.nav.preview(it) } }
                    work?.let { PlaceChip(P.MapPinFill, "Trabajo") { c.nav.preview(it) } }
                    if (home == null && work == null) {
                        Bubble { Text("Mantén el dedo en el mapa para elegir un destino", style = Civic.type.caption, color = Civic.colors.textDim) }
                    }
                }
            }
        }

        if (searching) {
            SearchPanel(
                near = center,
                onPick = { place ->
                    searching = false
                    c.nav.preview(place)
                },
                onPickOnMap = {
                    searching = false
                    follow = false
                    picking = true
                },
                onClose = { searching = false },
                modifier = Modifier.align(Alignment.TopStart),
            )
        }
    } }
}

/** El pin fijo al centro del mapa: su punta marca el lugar exacto (el mapa se mueve debajo). */
@Composable
private fun CenterPin(modifier: Modifier) {
    Box(modifier.size(56.dp, 112.dp)) {
        // Sombra en el punto exacto, y el pin encima con la punta justo en el centro.
        Box(Modifier.align(Alignment.Center).size(12.dp, 6.dp).clip(CircleShape).background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f)))
        Icon(
            P.MapPinFill,
            null,
            tint = androidx.compose.ui.graphics.Color(0xFFFF453A),
            // La punta del dibujo queda ~5 dp arriba de su borde inferior: se baja para que toque el centro.
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 5.dp).size(56.dp),
        )
    }
}

/**
 * "Elegir en el mapa": dice qué calle hay bajo el pin mientras mueves el mapa, y confirma el destino.
 * Navegando, confirmar cambia la ruta hacia ese punto sin dejar de navegar.
 */
@Composable
private fun PickPanel(handle: MapHandle, navigating: Boolean, onConfirm: (Place) -> Unit, onCancel: () -> Unit) {
    val repo = LocalContext.current.container.map
    var point by remember { mutableStateOf<LatLong?>(null) }
    var street by remember { mutableStateOf<String?>(null) }
    var lookedUp by remember { mutableStateOf(false) }
    // El mapa es una vista de Android: se pregunta su centro unas veces por segundo (es barato).
    LaunchedEffect(Unit) {
        while (true) {
            val p = handle.center()
            if (p != null && (point == null || meters(point!!, p) > 3)) point = p
            kotlinx.coroutines.delay(250)
        }
    }
    // La calle se busca cuando dejas de mover el mapa un momento.
    LaunchedEffect(point) {
        val p = point ?: return@LaunchedEffect
        street = null
        lookedUp = false
        kotlinx.coroutines.delay(350)
        lookedUp = true
        street = runCatching { repo.streetAt(p.latitude, p.longitude, null, sticky = false) }.getOrNull()
    }
    val shape = RoundedCornerShape(Civic.radii.xl)
    Column(
        Modifier
            .width(460.dp)
            .blockTouches()
            .clip(shape)
            .background(Civic.colors.surface)
            .bevelBorder(shape)
            .padding(20.dp),
    ) {
        Text(if (navigating) "Cambiar el destino" else "Elegir en el mapa", style = Civic.type.caption, color = Civic.colors.textDim)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(P.MapPinFill, null, tint = androidx.compose.ui.graphics.Color(0xFFFF453A), modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(10.dp))
            Text(street ?: if (lookedUp) "Punto en el mapa" else "Mueve el mapa para poner el pin", style = Civic.type.title, color = Civic.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(Civic.space.m)) {
            CivicButton(
                if (navigating) "Ir aquí ahora" else "Confirmar destino",
                { point?.let { onConfirm(Place(street.orEmpty(), it)) } },
                Modifier.weight(1f),
                icon = P.NavigationArrowFill,
                enabled = point != null,
            )
            CivicButton("Cancelar", onCancel, kind = ButtonKind.Secondary)
        }
    }
}

/** "¿A dónde vamos?": abre la búsqueda. */
@Composable
private fun SearchPill(onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .height(56.dp)
            .widthIn(min = 300.dp)
            .clip(shape)
            .background(Civic.colors.surface)
            .bevelBorder(shape)
            .clickable(role = Role.Button, onClickLabel = "Buscar destino", onClick = onClick)
            .padding(horizontal = Civic.space.l),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(P.MagnifyingGlass, null, tint = Civic.colors.textDim, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(Civic.space.m))
        Text("¿A dónde vamos?", style = Civic.type.title.copy(fontSize = 19.sp), color = Civic.colors.textDim)
    }
}

/** Un lugar guardado (Casa, Trabajo): un toque calcula la ruta. */
@Composable
private fun PlaceChip(icon: ImageVector, label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .height(56.dp)
            .clip(shape)
            .background(Civic.colors.surface)
            .bevelBorder(shape)
            .clickable(role = Role.Button, onClickLabel = "Ir a $label", onClick = onClick)
            .padding(horizontal = Civic.space.l),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Civic.colors.accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(Civic.space.s))
        Text(label, style = Civic.type.label, color = Civic.colors.text)
    }
}

@Composable
private fun Bubble(content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .clip(shape)
            .background(Civic.colors.surface)
            .bevelBorder(shape)
            .padding(horizontal = Civic.space.l, vertical = Civic.space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

/** Botón redondo de vidrio sobre el mapa (mismo canto que las tarjetas de Inicio). */
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
            .bevelBorder(CircleShape)
            .clickable(source, indication = null, role = Role.Button, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (label != null) {
            Text(label, style = Civic.type.headline.copy(fontSize = 32.sp, lineHeight = 34.sp, fontWeight = FontWeight.Light), color = Civic.colors.text)
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
