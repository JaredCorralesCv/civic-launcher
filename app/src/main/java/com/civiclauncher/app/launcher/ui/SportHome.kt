package com.civiclauncher.app.launcher.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.round
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.civiclauncher.app.container
import com.civiclauncher.obd.AlertLevel
import com.civiclauncher.obd.EngineState
import com.civiclauncher.app.launcher.obd.ObdMode
import com.civiclauncher.app.ui.design.Civic
import com.civiclauncher.app.ui.design.PhosphorIcons as P
import com.civiclauncher.app.ui.design.bevelBorder
import com.civiclauncher.app.ui.design.Tone
import com.civiclauncher.app.ui.design.color
import com.civiclauncher.obd.Pids
import com.civiclauncher.obd.TripStats

/**
 * Inicio "Sport": un tablero deportivo en tres columnas.
 *  - Izquierda: tacómetro de líneas (RPM arriba, velocidad abajo, luz de cambio en medio) y el viaje.
 *  - Centro: estado y hora, el carro visto desde arriba sobre el camino (las líneas avanzan con la
 *    velocidad), batería, motor y consumo, y el rendimiento del viaje.
 *  - Derecha: el mapa (con la siguiente vuelta si navegas). La música va en la barra de abajo.
 * Se elige en Ajustes → Apariencia → Estilo de Inicio.
 */
@Composable
fun SportHome(onOpen: (Dest) -> Unit) {
    val c = LocalContext.current.container
    // Velocidad, RPM y marcha: una sola lectura para todos los widgets, congelada si Inicio no se ve.
    val driveState = rememberDriveValues()

    val saved by c.launcherPrefs.sportSlots.collectAsStateWithLifecycle()
    val arrangementId by c.launcherPrefs.sportArrangement.collectAsStateWithLifecycle()
    val arrangement = HomeArrangement.entries.firstOrNull { it.id == arrangementId } ?: HomeArrangement.GRID
    val layout = remember(saved, arrangement) { SportLayout.resolve(saved, arrangement) }
    val editing by SportLayout.editing.collectAsStateWithLifecycle()
    val menuOpen by SportLayout.menuOpen.collectAsStateWithLifecycle()
    /** El lugar para el que se está eligiendo (si se abrió el menú tocando un lugar). */
    var picking by remember { mutableStateOf<Slot?>(null) }
    // Al salir de Inicio se termina de editar.
    val active = LocalScreenActive.current
    LaunchedEffect(active) { if (!active) { SportLayout.stopEditing(); picking = null } }
    // Si cambias al estilo Original a media edición, no se queda abierta para la próxima vez.
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { SportLayout.stopEditing() } }

    val onEdit = { slot: Slot ->
        picking = slot
        SportLayout.menuOpen.value = true
    }

    // Arrastrar un widget sobre otro: se intercambian si cada uno cabe en el lugar del otro.
    var dragFrom by remember { mutableStateOf<Slot?>(null) }
    var dragPos by remember { mutableStateOf(Offset.Zero) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val bounds = remember { HashMap<Slot, androidx.compose.ui.geometry.Rect>() }
    val hover by remember { derivedStateOf { if (dragFrom == null) null else bounds.entries.firstOrNull { it.value.contains(dragPos) }?.key } }

    @Composable
    fun Place(slot: Slot, modifier: Modifier) {
        val from = dragFrom
        val hint = when {
            from == null -> DropHint.NONE
            slot == from -> DropHint.SOURCE
            SportLayout.place(layout, slot, layout.getValue(from)) == null -> DropHint.BLOCKED
            slot == hover -> DropHint.HOVER
            else -> DropHint.CAN_DROP
        }
        val drag = if (!editing || menuOpen) Modifier else Modifier.dragToSwap(
            slot to layout,
            onStart = {
                dragFrom = slot
                dragPos = (bounds[slot]?.topLeft ?: Offset.Zero) + it
            },
            onMove = { dragPos += it },
            onCancel = { dragFrom = null },
            onEnd = {
                val target = hover
                dragFrom = null
                if (target != null && target != slot) {
                    SportLayout.place(layout, target, layout.getValue(slot))?.let { c.launcherPrefs.setSportSlots(SportLayout.save(saved, it)) }
                }
            },
        )
        SportPlace(
            slot, layout.getValue(slot), editing, driveState, onOpen, onEdit, hint, drag,
            if (editing) modifier.onGloballyPositioned { bounds[slot] = it.boundsInRoot() } else modifier,
        )
    }

    Box(if (editing) Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() } else Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = PAGE_GUTTER, vertical = Civic.space.m),
            horizontalArrangement = Arrangement.spacedBy(Civic.space.m),
        ) {
            when (arrangement) {
                HomeArrangement.GRID -> {
                    Column(Modifier.weight(0.95f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(Civic.space.m)) {
                        Place(Slot.TOP_LEFT, Modifier.weight(1.1f).fillMaxWidth())
                        Place(Slot.BOTTOM_LEFT, Modifier.weight(1f).fillMaxWidth())
                    }
                    Place(Slot.CENTER, Modifier.weight(1.45f).fillMaxHeight())
                    Place(Slot.RIGHT, Modifier.weight(1.05f).fillMaxHeight())
                }
                // Mapa grande: una columna a la izquierda (franja, chico, franja) y el lugar panorámico.
                HomeArrangement.MAP -> {
                    Column(Modifier.weight(0.36f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(Civic.space.m)) {
                        Place(Slot.MAP_TOP, Modifier.weight(0.25f).fillMaxWidth())
                        Place(Slot.MAP_MIDDLE, Modifier.weight(0.5f).fillMaxWidth())
                        Place(Slot.MAP_BOTTOM, Modifier.weight(0.25f).fillMaxWidth())
                    }
                    Place(Slot.MAP_BIG, Modifier.weight(0.64f).fillMaxHeight())
                }
                HomeArrangement.FULL -> Place(Slot.FULL_SLOT, Modifier.fillMaxSize())
            }
        }
        dragFrom?.let { from ->
            Text(
                layout.getValue(from).label,
                style = Civic.type.label.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White,
                modifier = Modifier
                    .offset { (dragPos - origin - Offset(60f, 56f)).round() }
                    .clip(RoundedCornerShape(50))
                    .background(Civic.colors.accent)
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }
        if (editing && !menuOpen) {
            Row(
                Modifier.align(Alignment.BottomEnd).padding(end = PAGE_GUTTER + 12.dp, bottom = Civic.space.m + 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AllWidgetsButton { picking = null; SportLayout.menuOpen.value = true }
                DoneEditing(Modifier)
            }
        }
        if (menuOpen) {
            EditMenu(
                layout = layout,
                arrangement = arrangement,
                target = picking,
                onPlace = { slot, w ->
                    SportLayout.place(layout, slot, w)?.let { c.launcherPrefs.setSportSlots(SportLayout.save(saved, it)) }
                    picking = null
                },
                onArrangement = { a ->
                    picking = null
                    c.launcherPrefs.setSportArrangement(a.id)
                },
                onReset = { c.launcherPrefs.setSportSlots(SportLayout.reset(saved, arrangement)) },
                onClearTarget = { picking = null },
                onClose = { SportLayout.menuOpen.value = false; picking = null },
                onDone = { SportLayout.stopEditing(); picking = null },
                preview = { w, size -> SportSlotContent(w, size, live = false, driveState, onOpen) },
            )
        }
    }
}

/** Un lugar de Inicio: su widget y, al editar, la capa para cambiarlo. */
@Composable
private fun SportPlace(slot: Slot, widget: SportWidget, editing: Boolean, drive: State<DriveValues>, onOpen: (Dest) -> Unit, onEdit: (Slot) -> Unit, hint: DropHint, drag: Modifier, modifier: Modifier) {
    // Las pantallas completas usan el toque largo para otras cosas (borrar el récord).
    val press = if (!editing && slot.size != SlotSize.FULL) Modifier.longPressToEdit(ENTER_EDITING) else Modifier
    Box(modifier.then(press)) {
        SportSlotContent(widget, slot.size, live = true, drive, onOpen)
        if (editing) EditOverlay(widget, hint, drag) { onEdit(slot) }
    }
}

private val ENTER_EDITING = { SportLayout.editing.value = true }


/**
 * Un widget de cierto tamaño. [live] falso: miniatura del menú (valores fijos, sin animación, y el
 * mapa va dibujado). Velocidad y RPM solo se leen en los widgets que las usan: un reloj o el mapa ya
 * no se vuelven a armar cada vez que cambia la velocidad.
 */
@Composable
private fun SportSlotContent(widget: SportWidget, size: SlotSize, live: Boolean, drive: State<DriveValues>, onOpen: (Dest) -> Unit) {
    val fill = Modifier.fillMaxSize()
    fun d() = if (live) drive.value else SAMPLE_DRIVE
    val toVehicle = if (live) ({ onOpen(Dest.VEHICLE) }) else null
    val roadSpeed = { if (live) drive.value.speed ?: 0f else 0f }
    when (widget) {
        SportWidget.TACH -> TachCard(d().rpm, d().speed, live, fill) { onOpen(Dest.VEHICLE) }
        SportWidget.TRIP -> TripCard(fill, live) { onOpen(Dest.VEHICLE) }
        SportWidget.CLOCK -> ClockWidget(fill)
        SportWidget.CLOCK_ANALOG -> AnalogClockWidget(fill)
        SportWidget.CALENDAR -> CalendarWidget(fill)
        SportWidget.SCREENSAVER -> ScreensaverWidget(fill, live, strip = size == SlotSize.STRIP)
        SportWidget.SPEED_STRIP -> SpeedStrip(d(), fill, toVehicle)
        SportWidget.RPM_STRIP -> RpmStrip(d(), fill, toVehicle)
        SportWidget.ROAD -> RoadWidget(roadSpeed, live, fill, toVehicle)
        SportWidget.FULL_CLUSTER -> ClusterScreen(d(), live, fill, toVehicle)
        SportWidget.FULL_GAUGES -> GaugesScreen(d(), live, fill, toVehicle)
        SportWidget.FULL_PERFORMANCE -> PerformanceScreen(d(), live, fill, null)
        SportWidget.STATS -> StatsWidget(fill, live) { onOpen(Dest.VEHICLE) }
        SportWidget.PHOTO -> CarPhotoWidget(d().speed, live, fill) { onOpen(Dest.VEHICLE) }
        SportWidget.MUSIC -> MusicWidget(fill, size) { onOpen(Dest.MUSIC) }
        SportWidget.ACCESS -> AccessWidget(fill, strip = size == SlotSize.STRIP, live) { onOpen(Dest.APPS) }
        SportWidget.CAR -> CenterCard(roadSpeed, onOpen, fill, live)
        SportWidget.MAP -> if (live) MapCard(fill, onOpen = { onOpen(Dest.MAP) }) else MapPreview()
        else -> GaugeWidget(widget, d(), live, fill) { onOpen(Dest.VEHICLE) }
    }
}

// =================================================================== Piezas comunes

/** Superficie de las tarjetas Sport: la misma de Inicio (borde de vidrio), sin relleno interno. */
@Composable
private fun SportSurface(modifier: Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(Civic.radii.xl)
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box(
        modifier
            .clip(shape)
            .background(if (pressed) Civic.colors.surfacePressed else Civic.colors.surface)
            .bevelBorder(shape)
            .let { if (onClick != null) it.clickable(source, indication = null, role = Role.Button, onClick = onClick) else it },
    ) { content() }
}

private val SPORT_BLUE = Color(0xFF3B8BFF)
private val SPORT_DEEP = Color(0xFF1F4FE0)
private val SPORT_LIGHT = Color(0xFF7CC8FF)
private val SPORT_AMBER = Color(0xFFFFB020)
private val SPORT_RED = Color(0xFFFF453A)
private val SPORT_TEAL = Color(0xFF30D5C8)

private const val TACH_MAX = 7000f
private const val TACH_SHIFT = 4500f
private const val TACH_RED = 6000f
private const val SPEED_MAX = 180f

/** Etiqueta chica en mayúsculas espaciadas. */
@Composable
private fun Caps(text: String, color: Color = Civic.colors.textFaint) {
    Text(text.uppercase(), style = Civic.type.caption.copy(fontSize = 10.sp, lineHeight = 12.sp, letterSpacing = 0.2.em), color = color, maxLines = 1)
}

// =================================================================== Tacómetro de líneas

/**
 * Como en los tableros deportivos modernos: dos "corchetes" de línea fina. El de arriba se ilumina
 * con las RPM (blanco → rosa → ámbar → rojo) y el de abajo con la velocidad (blanco → verde), con
 * una punta de luz. En medio, una línea de marcas que se encienden con las RPM (luz de cambio).
 */
@Composable
private fun TachCard(rpm: Float?, speed: Float?, live: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val rpmF by gaugeFraction(rpm, TACH_MAX, live)
    val spdF by gaugeFraction(speed, SPEED_MAX, live)
    val ink = if (Civic.colors.isDark) Color.White else Color(0xFF15181D)
    val redline = rpm != null && rpm >= TACH_RED
    SportSurface(modifier, if (live) onClick else null) {
        val inset = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp)
        // Capa fija: las líneas apagadas y las marcas sin encender.
        Canvas(inset.staticLayer(live)) {
            val stroke = 3.dp.toPx()
            for (top in listOf(true, false)) {
                val g = bracketGeometry(top, size.width, size.height, 3.5.dp.toPx())
                drawPath(g.path, ink.copy(alpha = 0.13f), style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawCircle(ink.copy(alpha = 0.30f), g.dot, g.end)
                drawCircle(ink.copy(alpha = 0.30f), g.dot, g.start)
            }
            forEachMark(size.width) { _, x0, w -> drawLine(ink.copy(alpha = 0.12f), Offset(x0, size.height / 2f), Offset(x0 + w, size.height / 2f), strokeWidth = stroke) }
        }
        // Capa que se mueve. Trazos, medidores y degradados se crean una vez por tamaño.
        Spacer(inset.drawWithCache {
            val w = size.width
            val h = size.height
            val stroke = 3.dp.toPx()
            val top = bracketGeometry(true, w, h, 3.5.dp.toPx())
            val bottom = bracketGeometry(false, w, h, 3.5.dp.toPx())
            val rpmBrush = Brush.horizontalGradient(
                0f to Color.White, 0.40f to Color(0xFFFFC9C9), 0.62f to Color(0xFFFFB3A6),
                0.74f to SPORT_AMBER, 0.86f to SPORT_RED, 1f to SPORT_RED,
                startX = 0f, endX = w,
            )
            val speedBrush = Brush.horizontalGradient(0f to Color.White, 0.5f to Color(0xFFB8FFD6), 1f to Color(0xFF5BF0A0), startX = 0f, endX = w)
            val strokes = BracketStrokes(stroke)
            onDrawBehind {
                bracketFill(top, rpmF, rpmBrush, if (redline) SPORT_RED else Color(0xFFFFD6D6), strokes)
                bracketFill(bottom, spdF, speedBrush, Color(0xFF7CFFB8), strokes)
                val fr = rpmF
                forEachMark(w) { at, x0, mw ->
                    if (at > fr) return@forEachMark
                    val col = when {
                        at * TACH_MAX >= TACH_RED -> SPORT_RED
                        at * TACH_MAX >= TACH_SHIFT -> SPORT_AMBER
                        at > 0.45f -> Color(0xFFFFE2B8)
                        else -> Color.White
                    }
                    drawLine(col, Offset(x0, h / 2f), Offset(x0 + mw, h / 2f), strokeWidth = stroke)
                }
            }
        })
        val number = Civic.type.title.copy(fontSize = 34.sp, lineHeight = 36.sp, fontWeight = FontWeight.Normal, fontFeatureSettings = "tnum", letterSpacing = 0.01.em)
        val unit = Civic.type.caption.copy(fontSize = 9.sp, lineHeight = 11.sp, letterSpacing = 0.3.em)
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(
                    rpm?.let { Math.round(it).toString() } ?: "--",
                    style = number,
                    color = when {
                        rpm == null -> Civic.colors.textFaint
                        redline -> SPORT_RED
                        else -> Civic.colors.text
                    },
                )
                Text("RPM", style = unit, color = Civic.colors.textDim)
            }
            Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(speed?.let { Math.round(it).toString() } ?: "--", style = number, color = if (speed != null) Civic.colors.text else Civic.colors.textFaint)
                Text("KM/H", style = unit, color = Civic.colors.textDim)
            }
        }
    }
}

/** La fila de marcas del centro: [block] recibe posición (0–1), x y ancho de cada marca. */
private inline fun forEachMark(w: Float, block: (at: Float, x0: Float, width: Float) -> Unit) {
    val marks = 44
    val step = w / marks
    val markW = step * 0.45f
    for (i in 0 until marks) block((i + 0.5f) / marks, step * i + (step - markW) / 2f, markW)
}

private class BracketGeometry(val path: Path, val measure: PathMeasure, val start: Offset, val end: Offset, val dot: Float) {
    /** Se reutiliza en cada cuadro: crear un trazo nuevo era basura para el recolector. */
    val part = Path()
}

private class BracketStrokes(stroke: Float) {
    val line = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val glow = Stroke(stroke * 2.6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val wide = Stroke(stroke * 5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
}

/** Un corchete de línea fina (diagonal, recta, diagonal), con un punto en cada extremo. */
private fun bracketGeometry(top: Boolean, w: Float, h: Float, dot: Float): BracketGeometry {
    val edgeY = if (top) dot else h - dot
    val endY = if (top) h * 0.24f else h * 0.76f
    val start = Offset(dot, endY)
    val end = Offset(w - dot, endY)
    val path = Path().apply {
        moveTo(start.x, start.y)
        lineTo(w * 0.24f, edgeY)
        lineTo(w * 0.76f, edgeY)
        lineTo(end.x, end.y)
    }
    return BracketGeometry(path, PathMeasure().apply { setPath(path, false) }, start, end, dot)
}

/** La parte iluminada del corchete, de izquierda a derecha, con resplandor y una punta de luz. */
private fun DrawScope.bracketFill(g: BracketGeometry, fraction: Float, fill: Brush, tip: Color, strokes: BracketStrokes) {
    if (fraction < 0.004f) return
    val len = g.measure.length * fraction
    g.part.reset()
    g.measure.getSegment(0f, len, g.part, true)
    // Resplandor en capas (la Mali-450 no difumina por hardware): de ancho y tenue a fino y brillante.
    drawPath(g.part, fill, alpha = 0.10f, style = strokes.wide)
    drawPath(g.part, fill, alpha = 0.22f, style = strokes.glow)
    drawPath(g.part, fill, style = strokes.line)
    drawCircle(Color.White, g.dot, g.start)
    val p = g.measure.getPosition(len)
    drawCircle(tip.copy(alpha = 0.18f), g.dot * 3.2f, p)
    drawCircle(tip.copy(alpha = 0.40f), g.dot * 1.9f, p)
    drawCircle(Color.White, g.dot * 1.05f, p)
}

// =================================================================== Viaje

/** El viaje ya convertido a lo que se ve: si los textos no cambian, la tarjeta no se vuelve a armar. */
@androidx.compose.runtime.Immutable
private data class TripView(val km: String, val driving: String, val avg: String, val max: String, val idle: String)

private fun TripStats.view() = TripView(
    km = if (distanceKm >= 100) Math.round(distanceKm).toString() else "%.1f".format(distanceKm),
    driving = sportDuration(drivingMs),
    avg = avgSpeedKmh?.let { Math.round(it).toString() } ?: "--",
    max = if (maxSpeedKmh > 0) Math.round(maxSpeedKmh).toString() else "--",
    idle = sportDuration(idleMs),
)

private val SAMPLE_TRIP = TripStats(startedAtMs = 1, drivingMs = 3_300_000, distanceKm = 42.7, fuelLiters = 3.4, maxSpeedKmh = 118.0, idleMs = 420_000)

@Composable
private fun rememberTrip(live: Boolean): State<TripStats> =
    if (live) LocalContext.current.container.obd.tripStats.collectWhenVisible() else remember { mutableStateOf(SAMPLE_TRIP) }

@Composable
private fun TripCard(modifier: Modifier, live: Boolean, onClick: () -> Unit) {
    val tripState = rememberTrip(live)
    val trip by remember { derivedStateOf { tripState.value.view() } }
    SportSurface(modifier, if (live) onClick else null) {
        Row(Modifier.fillMaxSize()) {
            // Bloque claro con la distancia: lo que más se lee del viaje.
            Column(
                Modifier
                    .weight(1.05f)
                    .fillMaxHeight()
                    .background(Brush.verticalGradient(listOf(Color(0xFFE4E7EC), Color(0xFFAEB4BD))))
                    .padding(16.dp),
                verticalArrangement = Arrangement.Bottom,
            ) {
                Text("Viaje", style = Civic.type.label.copy(fontSize = 14.sp), color = Color(0xFF2A2F37))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        trip.km,
                        style = Civic.type.title.copy(fontSize = 34.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum", letterSpacing = (-0.02).em),
                        color = Color(0xFF0E1116),
                        maxLines = 1,
                        softWrap = false,
                    )
                    Spacer(Modifier.width(3.dp))
                    Text("km", style = Civic.type.label, color = Color(0xFF2A2F37), maxLines = 1, softWrap = false, modifier = Modifier.padding(bottom = 5.dp))
                }
                Text(trip.driving, style = Civic.type.bodySmall.copy(fontFeatureSettings = "tnum"), color = Color(0xFF2A2F37))
            }
            Column(
                Modifier.weight(1f).fillMaxHeight().padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.SpaceEvenly,
            ) {
                TripLine(trip.avg, "Prom. km/h")
                TripLine(trip.max, "Máx. km/h")
                TripLine(trip.idle, "En ralentí")
            }
        }
    }
}

@Composable
private fun TripLine(value: String, label: String) {
    Column {
        Text(value, style = Civic.type.title.copy(fontSize = 22.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal, fontFeatureSettings = "tnum"), color = Civic.colors.text, maxLines = 1)
        Text(label, style = Civic.type.caption.copy(fontSize = 11.sp), color = Civic.colors.textDim, maxLines = 1)
    }
}

private fun sportDuration(ms: Long): String {
    val min = ms / 60_000
    return if (min >= 60) "${min / 60}h ${"%02d".format(min % 60)}m" else "$min min"
}

// =================================================================== Centro: el carro

/**
 * El centro, en piezas que leen cada una lo suyo: antes, cualquier dato nuevo volvía a armar toda la
 * tarjeta, camino y carro incluidos.
 */
@Composable
private fun CenterCard(speed: () -> Float, onOpen: (Dest) -> Unit, modifier: Modifier, live: Boolean) {
    SportSurface(modifier) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            // Arriba: el estado del carro y la hora.
            Row(verticalAlignment = Alignment.CenterVertically) {
                CarStatusChip(live) { onOpen(Dest.VEHICLE) }
                Spacer(Modifier.weight(1f))
                ClockChip()
            }
            Spacer(Modifier.height(12.dp))
            Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(Civic.radii.l))) {
                RoadView(speed, live, Modifier.fillMaxSize())
                // Accesos discretos en las esquinas del camino (este Inicio no tiene riel).
                CornerButton(P.SquaresFourLight, "Todas las apps", Modifier.align(Alignment.TopStart).padding(10.dp)) { onOpen(Dest.APPS) }
                CornerButton(P.GearSixLight, "Ajustes", Modifier.align(Alignment.TopEnd).padding(10.dp)) { onOpen(Dest.SETTINGS) }
            }
            Spacer(Modifier.height(14.dp))
            CarReadouts(live) { onOpen(Dest.VEHICLE) }
            Spacer(Modifier.height(12.dp))
            EfficiencyBar(live)
        }
    }
}

@Composable
private fun CarStatusChip(live: Boolean, onClick: () -> Unit) {
    if (!live) {
        StatusChip("Sin fallas", Tone.Ok.color(), onClick)
        return
    }
    val c = LocalContext.current.container
    val config = c.obd.config.collectWhenVisible()
    val state = c.obd.state.collectWhenVisible()
    val derived = c.obd.live.collectWhenVisible()
    val diag = c.obd.diagnostics.collectWhenVisible()
    val status by remember {
        derivedStateOf {
            val alert = derived.value.alerts.firstOrNull()
            val stored = diag.value.stored.size
            when {
                config.value.mode == ObdMode.NONE -> "Sin adaptador OBD" to Tone.Neutral
                alert != null -> alert.title to if (alert.level == AlertLevel.DANGER) Tone.Bad else Tone.Warn
                state.value !is EngineState.Connected -> "OBD sin conexión" to Tone.Neutral
                stored > 0 -> (if (stored == 1) "1 código de falla" else "$stored códigos de falla") to Tone.Warn
                else -> "Sin fallas" to Tone.Ok
            }
        }
    }
    StatusChip(status.first, status.second.color(), onClick)
}

@Composable
private fun CarReadouts(live: Boolean, onClick: () -> Unit) {
    val now by rememberCarNow(live)
    val battery = now.battery
    val coolant = now.coolant
    Row(Modifier.fillMaxWidth().clickable(enabled = live, role = Role.Button, onClickLabel = "Abrir vehículo", onClick = onClick)) {
        Readout(
            "Batería", battery?.let { "%.1f".format(it) } ?: "--", "V",
            when {
                battery == null -> Civic.colors.textFaint
                battery < 11.9 -> Civic.colors.bad
                now.running && battery < 13.0 -> Civic.colors.warn
                else -> Civic.colors.ok
            },
        )
        Readout(
            "Motor", coolant?.let { Math.round(it).toString() } ?: "--", "°C",
            when {
                coolant == null -> Civic.colors.textFaint
                coolant >= 105 -> Civic.colors.bad
                coolant >= 100 -> Civic.colors.warn
                coolant < 70 -> Civic.colors.cool
                else -> Civic.colors.ok
            },
        )
        Readout(
            "Consumo",
            now.kmPerLiter?.let { if (it >= 99) "∞" else "%.1f".format(it) } ?: now.litersPerHour?.let { "%.1f".format(it) } ?: "--",
            when {
                now.kmPerLiter != null -> "km/L"
                now.litersPerHour != null -> "L/h"
                else -> ""
            },
            if (now.kmPerLiter != null || now.litersPerHour != null) Civic.colors.text else Civic.colors.textFaint,
        )
    }
}

@Composable
private fun StatusChip(text: String, color: Color, onClick: () -> Unit) {
    Row(
        Modifier
            .height(40.dp)
            .clip(RoundedCornerShape(50))
            .background(Civic.colors.surfaceRaised)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 12.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(16.dp).clip(CircleShape).background(color.copy(alpha = 0.22f)))
            Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        }
        Spacer(Modifier.width(10.dp))
        Text(text, style = Civic.type.label.copy(fontSize = 14.sp), color = Civic.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** La hora en dígitos finos, con los dos puntos tenues. */
@Composable
private fun ClockChip() {
    val t = rememberClockText()
    Row(
        Modifier.height(40.dp).clip(RoundedCornerShape(50)).background(Civic.colors.surfaceRaised).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val style = Civic.type.title.copy(fontSize = 22.sp, lineHeight = 24.sp, fontWeight = FontWeight.Light, fontFeatureSettings = "tnum")
        Text(t.substringBefore(':'), style = style, color = Civic.colors.text)
        Text(" : ", style = style, color = Civic.colors.textFaint)
        Text(t.substringAfter(':'), style = style, color = Civic.colors.text)
    }
}

@Composable
private fun CornerButton(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box(
        modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(if (pressed) Color.White.copy(alpha = 0.22f) else Color.Black.copy(alpha = 0.35f))
            .clickable(source, indication = null, role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(22.dp)) }
}

@Composable
private fun RowScope.Readout(label: String, value: String, unit: String, color: Color) {
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Caps(label)
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.Top) {
            Text(value, style = Civic.type.title.copy(fontSize = 30.sp, lineHeight = 32.sp, fontWeight = FontWeight.Light, fontFeatureSettings = "tnum"), color = color, maxLines = 1)
            if (unit.isNotEmpty()) {
                Spacer(Modifier.width(3.dp))
                Text(unit, style = Civic.type.caption.copy(fontSize = 11.sp), color = color.copy(alpha = 0.8f), modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

/** Rendimiento promedio del viaje en una barra (20 km/L = llena) y la gasolina usada. */
@Composable
private fun EfficiencyBar(live: Boolean) {
    val tripState = rememberTrip(live)
    val view by remember {
        derivedStateOf { tripState.value.avgKmPerLiter?.let { Math.round(it * 10) / 10.0 } to Math.round(tripState.value.fuelLiters * 10) / 10.0 }
    }
    val kmPerLiter = view.first
    val liters = view.second
    // El promedio cambia un poquito en cada lectura del OBD: los cambios chicos van de golpe (un
    // cuadro); antes reiniciaban una animación sin fin (~45 cuadros por segundo solo por esta barra).
    val f by gaugeFraction(kmPerLiter?.toFloat(), 20f, live)
    val ink = if (Civic.colors.isDark) Color.White else Color(0xFF15181D)
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(SPORT_AMBER))
            Spacer(Modifier.width(8.dp))
            Text("Rendimiento del viaje", style = Civic.type.caption.copy(fontSize = 12.sp), color = Civic.colors.textDim)
            Spacer(Modifier.weight(1f))
            Text(
                kmPerLiter?.let { "%.1f km/L".format(it) } ?: "--",
                style = Civic.type.label.copy(fontSize = 15.sp, fontFeatureSettings = "tnum"),
                color = if (kmPerLiter != null) SPORT_AMBER else Civic.colors.textFaint,
            )
        }
        Spacer(Modifier.height(8.dp))
        Canvas(Modifier.fillMaxWidth().height(6.dp)) {
            val r = CornerRadius(size.height / 2f)
            drawRoundRect(ink.copy(alpha = 0.10f), cornerRadius = r)
            if (f > 0.01f) drawRoundRect(Brush.horizontalGradient(listOf(SPORT_AMBER.copy(alpha = 0.7f), SPORT_AMBER)), size = Size(size.width * f, size.height), cornerRadius = r)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            if (liters > 0.05) "Gasolina usada: %.1f L".format(liters) else "Se calcula al manejar con el OBD conectado",
            style = Civic.type.caption.copy(fontSize = 11.sp),
            color = Civic.colors.textFaint,
        )
    }
}

/**
 * El camino visto desde arriba con tu carro en medio: asfalto con textura, orillas, banquetas y las
 * rayas del centro, que bajan a la velocidad a la que vas (detenido, quietas: no gasta cuadros).
 */
@Composable
private fun RoadView(speed: () -> Float, live: Boolean, modifier: Modifier) {
    val active = LocalScreenActive.current
    val phase = remember { mutableFloatStateOf(0f) }
    val latest = androidx.compose.runtime.rememberUpdatedState(speed)
    // Se lee la velocidad solo para saber si se mueve: cada km/h ya no vuelve a armar el camino.
    val moving by remember(live, active) { derivedStateOf { live && active && latest.value() > 2f } }
    LaunchedEffect(moving) {
        if (!moving) return@LaunchedEffect
        var last = 0L
        while (true) {
            // ~20 cuadros por segundo bastan para que se vea fluido y cuestan la mitad que 60.
            kotlinx.coroutines.delay(50)
            withFrameNanos { t ->
                // Rayas a ~1/6 de la velocidad real en pantalla: se nota que avanzas sin marear.
                val pxPerSec = latest.value() / 3.6f * 6f
                if (last != 0L) phase.floatValue = (phase.floatValue + pxPerSec * (t - last) / 1e9f) % 1000f
                last = t
            }
        }
    }
    // Grano del asfalto: una textura chica que se repite (se arma una sola vez).
    val grain = remember { androidx.compose.ui.graphics.ShaderBrush(androidx.compose.ui.graphics.ImageShader(asphaltGrain(), androidx.compose.ui.graphics.TileMode.Repeated, androidx.compose.ui.graphics.TileMode.Repeated)) }
    // Tres capas (ver RoadWidget): solo la de las rayas se redibuja al avanzar.
    Box(modifier) {
    Canvas(Modifier.fillMaxSize().staticLayer(live)) {
        val w = size.width
        val h = size.height
        // Asfalto.
        drawRect(Brush.verticalGradient(listOf(Color(0xFF111317), Color(0xFF1E2127), Color(0xFF181A1F))))
        drawRect(grain)
        // Banquetas: fuera de las orillas, un poco más claras, en perspectiva.
        val topL = w * 0.36f
        val topR = w * 0.64f
        val botL = w * 0.08f
        val botR = w * 0.92f
        drawPath(
            Path().apply { moveTo(0f, 0f); lineTo(topL, 0f); lineTo(botL, h); lineTo(0f, h); close() },
            Brush.horizontalGradient(listOf(Color(0xFF3A3F47), Color(0xFF23262C)), startX = 0f, endX = w * 0.3f),
        )
        drawPath(
            Path().apply { moveTo(w, 0f); lineTo(topR, 0f); lineTo(botR, h); lineTo(w, h); close() },
            Brush.horizontalGradient(listOf(Color(0xFF23262C), Color(0xFF3A3F47)), startX = w * 0.7f, endX = w),
        )
        // Orillas: línea blanca con un leve resplandor.
        val edge = Color(0xFFE9ECEF)
        for ((a, b) in listOf(Offset(topL, 0f) to Offset(botL, h), Offset(topR, 0f) to Offset(botR, h))) {
            drawLine(edge.copy(alpha = 0.10f), a, b, strokeWidth = 9.dp.toPx())
            drawLine(edge.copy(alpha = 0.85f), a, b, strokeWidth = 3.dp.toPx())
        }
        // Luz de los faros sobre el camino, delante del carro.
        val carLen = h * 0.70f
        val carTop = h * 0.54f - carLen / 2f
        drawPath(
            Path().apply {
                moveTo(w / 2f - carLen * 0.17f, carTop + carLen * 0.03f)
                lineTo(w / 2f + carLen * 0.17f, carTop + carLen * 0.03f)
                lineTo(w / 2f + carLen * 0.42f, carTop - h * 0.42f)
                lineTo(w / 2f - carLen * 0.42f, carTop - h * 0.42f)
                close()
            },
            Brush.verticalGradient(listOf(Color(0x33EAF4FF), Color(0x00EAF4FF)), startY = carTop, endY = carTop - h * 0.42f),
        )
    }
    Canvas(Modifier.fillMaxSize().graphicsLayer()) {
        val h = size.height
        val w = size.width
        // Rayas del centro: más grandes y separadas abajo (más cerca).
        val p = phase.floatValue.dp.toPx()
        val unit = 46.dp.toPx()
        var y = -unit + (p % unit)
        while (y < h) {
            val depth = (y / h).coerceIn(0f, 1f)
            val len = unit * (0.30f + 0.35f * depth)
            val width = (2f + 3f * depth).dp.toPx()
            drawLine(Color(0xFFF2F2F2).copy(alpha = 0.30f + 0.5f * depth), Offset(w / 2f, y), Offset(w / 2f, y + len), strokeWidth = width)
            y += unit
        }
    }
    Canvas(Modifier.fillMaxSize().staticLayer(live)) {
        val w = size.width
        val h = size.height
        topViewCar(Offset(w / 2f, h * 0.54f), h * 0.70f)
        // Lo lejano se pierde en la oscuridad (da profundidad).
        drawRect(Brush.verticalGradient(0f to Color(0xE60B0D11), 0.32f to Color(0x000B0D11)))
    }
    }
}

/** Textura de asfalto: puntitos claros y oscuros al azar, casi imperceptibles. */
private fun asphaltGrain(): androidx.compose.ui.graphics.ImageBitmap {
    val n = 96
    val px = IntArray(n * n)
    val rnd = java.util.Random(7)
    for (i in px.indices) {
        val v = rnd.nextInt(100)
        px[i] = when {
            v < 7 -> 0x16FFFFFF
            v < 14 -> 0x22000000
            else -> 0
        }
    }
    return android.graphics.Bitmap.createBitmap(px, n, n, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
}

/**
 * Un sedán gris plata visto desde arriba, mirando hacia arriba, de [len] de largo y centrado en [c]:
 * llantas, carrocería con cintura, reflejos del metal, cofre y cajuela con sus líneas, cristales con
 * reflejo, espejos, faros y calaveras encendidos.
 */
private fun DrawScope.topViewCar(c: Offset, len: Float) {
    val wid = len * 0.44f
    val left = c.x - wid / 2f
    val top = c.y - len / 2f
    fun x(f: Float) = left + wid * f
    fun y(f: Float) = top + len * f
    fun p(fx: Float, fy: Float) = Offset(x(fx), y(fy))
    // Sombra suave: varias capas (sin difuminado por hardware en Android 8).
    for (i in 0 until 5) {
        val g = 0.03f * (5 - i)
        drawRoundRect(
            Color.Black.copy(alpha = 0.10f),
            Offset(x(-g), y(0.01f - g * 0.4f)),
            Size(wid * (1 + 2 * g), len * (1 + g * 0.8f)),
            CornerRadius(wid * 0.45f),
        )
    }
    // Llantas (se asoman de la carrocería).
    val tire = Color(0xFF0B0C0E)
    for ((fx, fy) in listOf(-0.04f to 0.17f, 0.89f to 0.17f, -0.04f to 0.72f, 0.89f to 0.72f)) {
        drawRoundRect(tire, p(fx, fy), Size(wid * 0.15f, len * 0.13f), CornerRadius(wid * 0.05f))
    }
    // Carrocería con cintura (más angosta en las puertas, más ancha en los pasos de rueda).
    val body = Path().apply {
        moveTo(x(0.50f), y(0.0f))
        cubicTo(x(0.80f), y(0.0f), x(0.97f), y(0.03f), x(0.98f), y(0.12f))
        cubicTo(x(1.0f), y(0.22f), x(0.99f), y(0.30f), x(0.96f), y(0.40f))
        cubicTo(x(0.95f), y(0.50f), x(0.96f), y(0.62f), x(0.99f), y(0.72f))
        cubicTo(x(1.0f), y(0.84f), x(0.98f), y(0.96f), x(0.86f), y(0.99f))
        cubicTo(x(0.70f), y(1.0f), x(0.30f), y(1.0f), x(0.14f), y(0.99f))
        cubicTo(x(0.02f), y(0.96f), x(0.0f), y(0.84f), x(0.01f), y(0.72f))
        cubicTo(x(0.04f), y(0.62f), x(0.05f), y(0.50f), x(0.04f), y(0.40f))
        cubicTo(x(0.01f), y(0.30f), x(0.0f), y(0.22f), x(0.02f), y(0.12f))
        cubicTo(x(0.03f), y(0.03f), x(0.20f), y(0.0f), x(0.50f), y(0.0f))
        close()
    }
    // Pintura gris metálico: luz de frente, sombra en los costados y un brillo al centro.
    drawPath(body, Brush.verticalGradient(listOf(Color(0xFFAEB4BC), Color(0xFF878E98), Color(0xFF6F7680), Color(0xFF8A919A)), startY = top, endY = top + len))
    drawPath(body, Brush.horizontalGradient(0f to Color(0x99000000), 0.18f to Color(0x00000000), 0.5f to Color(0x22FFFFFF), 0.82f to Color(0x00000000), 1f to Color(0x99000000), startX = left, endX = left + wid))
    // Líneas del cofre y brillo del cofre.
    val seam = Color(0x55000000)
    drawLine(seam, p(0.30f, 0.06f), p(0.27f, 0.25f), strokeWidth = len * 0.004f)
    drawLine(seam, p(0.70f, 0.06f), p(0.73f, 0.25f), strokeWidth = len * 0.004f)
    drawRoundRect(Brush.verticalGradient(listOf(Color(0x55FFFFFF), Color(0x00FFFFFF)), startY = y(0.03f), endY = y(0.24f)), p(0.40f, 0.03f), Size(wid * 0.20f, len * 0.21f), CornerRadius(wid * 0.1f))
    // Espejos.
    for (fx in listOf(-0.10f, 0.96f)) {
        drawRoundRect(Color(0xFF7E858F), p(fx, 0.315f), Size(wid * 0.14f, len * 0.045f), CornerRadius(wid * 0.05f))
        drawRoundRect(Color(0xFF1A1F27), p(fx + 0.02f, 0.325f), Size(wid * 0.10f, len * 0.022f), CornerRadius(wid * 0.03f))
    }
    val glass = Brush.verticalGradient(listOf(Color(0xFF243042), Color(0xFF0C1118)))
    val reflection = Brush.linearGradient(0f to Color(0x00FFFFFF), 0.45f to Color(0x00FFFFFF), 0.5f to Color(0x30FFFFFF), 0.62f to Color(0x00FFFFFF), start = p(0.1f, 0.26f), end = p(0.9f, 0.42f))
    // Parabrisas.
    val windshield = Path().apply {
        moveTo(x(0.11f), y(0.42f)); lineTo(x(0.89f), y(0.42f)); lineTo(x(0.82f), y(0.285f))
        quadraticTo(x(0.5f), y(0.25f), x(0.18f), y(0.285f)); close()
    }
    drawPath(windshield, glass)
    drawPath(windshield, reflection)
    // Ventanas de los costados.
    for (side in listOf(0, 1)) {
        val win = Path().apply {
            if (side == 0) { moveTo(x(0.09f), y(0.44f)); lineTo(x(0.15f), y(0.45f)); lineTo(x(0.15f), y(0.68f)); lineTo(x(0.10f), y(0.69f)) }
            else { moveTo(x(0.91f), y(0.44f)); lineTo(x(0.85f), y(0.45f)); lineTo(x(0.85f), y(0.68f)); lineTo(x(0.90f), y(0.69f)) }
            close()
        }
        drawPath(win, glass)
    }
    // Techo con un brillo suave.
    drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF9399A2), Color(0xFF7B828C)), startY = y(0.43f), endY = y(0.70f)), p(0.16f, 0.43f), Size(wid * 0.68f, len * 0.27f), CornerRadius(wid * 0.10f))
    drawRoundRect(Brush.horizontalGradient(listOf(Color(0x00FFFFFF), Color(0x26FFFFFF), Color(0x00FFFFFF)), startX = x(0.16f), endX = x(0.84f)), p(0.16f, 0.43f), Size(wid * 0.68f, len * 0.27f), CornerRadius(wid * 0.10f))
    // Medallón (cristal de atrás) y cajuela.
    val rear = Path().apply {
        moveTo(x(0.16f), y(0.71f)); lineTo(x(0.84f), y(0.71f)); lineTo(x(0.79f), y(0.81f))
        quadraticTo(x(0.5f), y(0.835f), x(0.21f), y(0.81f)); close()
    }
    drawPath(rear, glass)
    drawLine(seam, p(0.22f, 0.86f), p(0.78f, 0.86f), strokeWidth = len * 0.004f)
    // Faros: blancos con resplandor.
    val head = Color(0xFFF2F8FF)
    for ((a, b) in listOf(p(0.10f, 0.055f) to p(0.30f, 0.022f), p(0.90f, 0.055f) to p(0.70f, 0.022f))) {
        drawLine(head.copy(alpha = 0.25f), a, b, strokeWidth = len * 0.035f, cap = StrokeCap.Round)
        drawLine(head, a, b, strokeWidth = len * 0.014f, cap = StrokeCap.Round)
    }
    // Calaveras: rojas con resplandor.
    val tail = Color(0xFFFF3B30)
    for ((a, b) in listOf(p(0.08f, 0.955f) to p(0.28f, 0.982f), p(0.92f, 0.955f) to p(0.72f, 0.982f))) {
        drawLine(tail.copy(alpha = 0.30f), a, b, strokeWidth = len * 0.04f, cap = StrokeCap.Round)
        drawLine(tail, a, b, strokeWidth = len * 0.016f, cap = StrokeCap.Round)
    }
}
