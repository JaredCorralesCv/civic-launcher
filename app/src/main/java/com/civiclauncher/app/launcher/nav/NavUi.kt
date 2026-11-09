package com.civiclauncher.app.launcher.nav

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.civiclauncher.app.ui.design.ButtonKind
import com.civiclauncher.app.ui.design.Civic
import com.civiclauncher.app.ui.design.CivicButton
import com.civiclauncher.app.ui.design.PhosphorIcons as P
import com.civiclauncher.app.ui.design.bevelBorder
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.sin

// =================================================================== Textos

/** "350 m", "1.2 km", "12 km". Redondeado como lo diría un copiloto (de 50 en 50 m cerca). */
fun formatDistance(m: Double): Pair<String, String> = when {
    // Hasta 975 m: de 975 a 999 se redondearía a "1000 m"; mejor "1.0 km".
    m < 975 -> ((Math.round(m / 50.0) * 50).coerceAtLeast(10)).toString() to "m"
    m < 10_000 -> "%.1f".format(m / 1_000) to "km"
    else -> "%.0f".format(m / 1_000) to "km"
}

fun formatDuration(s: Int): String = when {
    s < 60 -> "1 min"
    s < 3_600 -> "${s / 60} min"
    else -> "${s / 3_600} h ${(s % 3_600) / 60} min"
}

/** Hora de llegada, "14:35". */
fun arrivalTime(seconds: Int): String {
    val cal = Calendar.getInstance().apply { add(Calendar.SECOND, seconds) }
    return "%d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
}

fun Turn.instruction(exit: Int): String = when (this) {
    Turn.STRAIGHT -> "Sigue derecho"
    Turn.LEFT -> "Gira a la izquierda"
    Turn.SLIGHT_LEFT -> "Ligeramente a la izquierda"
    Turn.SHARP_LEFT -> "Vuelta cerrada a la izquierda"
    Turn.RIGHT -> "Gira a la derecha"
    Turn.SLIGHT_RIGHT -> "Ligeramente a la derecha"
    Turn.SHARP_RIGHT -> "Vuelta cerrada a la derecha"
    Turn.KEEP_LEFT -> "Mantente a la izquierda"
    Turn.KEEP_RIGHT -> "Mantente a la derecha"
    Turn.U_TURN -> "Da vuelta en U"
    Turn.ROUNDABOUT -> if (exit > 0) "En la glorieta, toma la salida $exit" else "Entra a la glorieta"
    Turn.EXIT_LEFT -> "Sal por la izquierda"
    Turn.EXIT_RIGHT -> "Sal por la derecha"
    Turn.ARRIVE -> "Llegas a tu destino"
}

// =================================================================== Flecha de la vuelta

/**
 * Flecha de la siguiente indicación, dibujada (no un ícono): un tallo que viene de abajo y se dobla
 * hacia el lado de la vuelta, con punta. Glorieta: un círculo con la salida.
 */
@Composable
fun TurnIcon(turn: Turn, modifier: Modifier = Modifier, size: Dp = 56.dp, color: Color = Civic.colors.text) {
    if (turn == Turn.ARRIVE) {
        Icon(P.MapPinFill, null, tint = Color(0xFFFF453A), modifier = modifier.size(size))
        return
    }
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val stroke = Stroke(w * 0.11f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val angle = when (turn) {
            Turn.STRAIGHT -> 0f
            Turn.SLIGHT_LEFT, Turn.KEEP_LEFT, Turn.EXIT_LEFT -> -40f
            Turn.LEFT -> -90f
            Turn.SHARP_LEFT -> -135f
            Turn.SLIGHT_RIGHT, Turn.KEEP_RIGHT, Turn.EXIT_RIGHT -> 40f
            Turn.RIGHT -> 90f
            Turn.SHARP_RIGHT -> 135f
            Turn.U_TURN -> 180f
            else -> 0f
        }
        val base = Offset(w * 0.5f, w * 0.92f)
        val knee = Offset(w * 0.5f, w * 0.5f)
        if (turn == Turn.ROUNDABOUT) {
            drawCircle(color, radius = w * 0.2f, center = Offset(w * 0.5f, w * 0.45f), style = stroke)
            drawLine(color, base, Offset(w * 0.5f, w * 0.65f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            rotate(45f, Offset(w * 0.5f, w * 0.45f)) {
                drawLine(color, Offset(w * 0.5f, w * 0.25f), Offset(w * 0.5f, w * 0.06f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            }
            return@Canvas
        }
        val len = w * 0.36f
        val a = Math.toRadians(angle.toDouble())
        val tip = if (turn == Turn.U_TURN) Offset(w * 0.22f, w * 0.75f) else Offset(knee.x + (len * sin(a)).toFloat(), knee.y - (len * cos(a)).toFloat())
        val path = Path().apply {
            moveTo(base.x, base.y)
            lineTo(knee.x, knee.y)
            if (turn == Turn.U_TURN) {
                quadraticTo(w * 0.5f, w * 0.12f, w * 0.36f, w * 0.12f)
                quadraticTo(w * 0.22f, w * 0.12f, tip.x, w * 0.45f)
                lineTo(tip.x, tip.y)
            } else {
                lineTo(tip.x, tip.y)
            }
        }
        drawPath(path, color, style = stroke)
        // Punta: dos rayas en ángulo desde el extremo, siguiendo la dirección final.
        val dir = if (turn == Turn.U_TURN) Math.toRadians(180.0) else a
        val head = w * 0.17f
        for (side in listOf(-1, 1)) {
            val back = dir + Math.toRadians(180.0 + side * 40.0)
            drawLine(
                color,
                tip,
                Offset(tip.x + (head * sin(back)).toFloat(), tip.y - (head * cos(back)).toFloat()),
                strokeWidth = stroke.width,
                cap = StrokeCap.Round,
            )
        }
    }
}

// =================================================================== Navegando

/** Arriba a la izquierda: la flecha, cuánto falta para la vuelta y a qué calle entras. */
@Composable
fun NavBanner(progress: NavProgress, rerouting: Boolean, modifier: Modifier = Modifier, compact: Boolean = false) {
    val shape = RoundedCornerShape(Civic.radii.xl)
    Row(
        modifier
            .widthIn(max = if (compact) 460.dp else 520.dp)
            .clip(shape)
            .background(Color(0xFF0E3B8C))
            .bevelBorder(shape)
            .padding(horizontal = if (compact) 14.dp else 18.dp, vertical = if (compact) 10.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val next = progress.next
        if (rerouting || next == null) {
            Text(if (rerouting) "Recalculando la ruta…" else "Sigue la ruta", style = Civic.type.title, color = Color.White)
            return@Row
        }
        TurnIcon(next.kind, size = if (compact) 40.dp else 56.dp, color = Color.White)
        Spacer(Modifier.width(if (compact) 12.dp else 16.dp))
        Column {
            val (value, unit) = formatDistance(progress.toNextM)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, style = Civic.type.headline.copy(fontSize = if (compact) 26.sp else 34.sp, fontWeight = FontWeight.SemiBold), color = Color.White)
                Spacer(Modifier.width(4.dp))
                Text(unit, style = Civic.type.label, color = Color.White.copy(alpha = 0.75f), modifier = Modifier.padding(bottom = 4.dp))
            }
            Text(
                progress.nextStreet?.let { if (next.kind == Turn.ARRIVE) "Llegas a tu destino" else "a $it" } ?: next.kind.instruction(next.exit),
                style = Civic.type.label.copy(fontSize = if (compact) 15.sp else 17.sp),
                color = Color.White.copy(alpha = 0.9f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Abajo: hora de llegada, minutos y kilómetros que faltan, y terminar. */
@Composable
fun NavFooter(progress: NavProgress, onStop: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .height(64.dp)
            .clip(shape)
            .background(Civic.colors.surface)
            .bevelBorder(shape)
            .padding(start = 22.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(arrivalTime(progress.remainingS), style = Civic.type.title.copy(fontSize = 24.sp, fontWeight = FontWeight.SemiBold), color = Color(0xFF34C759))
        Spacer(Modifier.width(14.dp))
        Text(
            "${formatDuration(progress.remainingS)} · ${formatDistance(progress.remainingM).let { "${it.first} ${it.second}" }}",
            style = Civic.type.label,
            color = Civic.colors.textDim,
        )
        Spacer(Modifier.width(18.dp))
        RoundAction(P.X, "Terminar la ruta", Color(0xFFFF453A), onStop)
    }
}

@Composable
private fun RoundAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(color)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = Color.White, modifier = Modifier.size(22.dp)) }
}

// =================================================================== Antes de arrancar

/**
 * El destino elegido: nombre, cuánto tarda y "Ir". Si faltan los datos de rutas de esa zona, ofrece
 * descargarlos (por WiFi, una vez).
 */
@Composable
fun PreviewPanel(
    state: NavState.Preview,
    download: TilesDownload,
    onGo: () -> Unit,
    /** Mantener presionado "Ir": recorrido simulado (ver la ruta antes de manejarla). */
    onSimulate: () -> Unit,
    onCancel: () -> Unit,
    onDownload: () -> Unit,
    onSaveHome: () -> Unit,
    onSaveWork: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(Civic.radii.xl)
    Column(
        modifier
            .width(460.dp)
            .blockTouches()
            .clip(shape)
            .background(Civic.colors.surface)
            .bevelBorder(shape)
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(P.MapPinFill, null, tint = Color(0xFFFF453A), modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(10.dp))
            Text(state.dest.name.ifBlank { "Punto en el mapa" }, style = Civic.type.title, color = Civic.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(6.dp))
        val route = state.route
        when {
            download is TilesDownload.Running -> Text(
                "Descargando datos de rutas (${download.index + 1} de ${download.count})… ${(download.progress * 100).toInt()} %",
                style = Civic.type.bodySmall, color = Civic.colors.text,
            )
            state.calculating -> Text("Calculando la ruta…", style = Civic.type.bodySmall, color = Civic.colors.textDim)
            route != null -> Text(
                "${formatDuration(route.totalSeconds)} · ${formatDistance(route.totalMeters).let { "${it.first} ${it.second}" }} · llegas a las ${arrivalTime(route.totalSeconds)}",
                style = Civic.type.body, color = Civic.colors.text,
            )
            else -> Text(
                (download as? TilesDownload.Failed)?.message ?: state.error.orEmpty(),
                style = Civic.type.bodySmall, color = Civic.colors.warn,
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                route != null -> GoButton(onGo, onSimulate)
                state.missing.isNotEmpty() && download !is TilesDownload.Running ->
                    CivicButton("Descargar rutas de la zona", onDownload, icon = P.DownloadSimple)
            }
            CivicButton("Cancelar", onCancel, kind = ButtonKind.Secondary)
        }
        Spacer(Modifier.height(10.dp))
        // Guardar este destino como un lugar fijo (aparece abajo del mapa y en la búsqueda).
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Guardar como", style = Civic.type.caption, color = Civic.colors.textFaint)
            Spacer(Modifier.width(4.dp))
            SmallChip(P.HouseFill, "Casa", onSaveHome)
            SmallChip(P.MapPinFill, "Trabajo", onSaveWork)
        }
    }
}

/** "Ir": un toque arranca; mantenerlo presionado hace el recorrido simulado. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun GoButton(onGo: () -> Unit, onSimulate: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .height(56.dp)
            .clip(shape)
            .background(Civic.colors.accent)
            .combinedClickable(role = Role.Button, onClickLabel = "Ir", onLongClickLabel = "Ver el recorrido simulado", onLongClick = onSimulate, onClick = onGo)
            .padding(horizontal = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(P.NavigationArrowFill, null, tint = Civic.colors.onAccent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Text("Ir", style = Civic.type.title.copy(fontSize = 20.sp), color = Civic.colors.onAccent)
    }
}

/** Botoncito "guardar como Casa/Trabajo". */
@Composable
private fun SmallChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .clickable(role = Role.Button, onClickLabel = "Guardar como $label", onClick = onClick)
            .background(Civic.colors.surfaceRaised, RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Civic.colors.textDim, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = Civic.type.caption.copy(fontWeight = FontWeight.SemiBold), color = Civic.colors.text, maxLines = 1)
    }
}

/** Llegaste. */
@Composable
fun ArrivedPanel(dest: Place, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(Civic.radii.xl)
    Row(
        modifier
            .clip(shape)
            .background(Civic.colors.surface)
            .bevelBorder(shape)
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(P.CheckCircleFill, null, tint = Color(0xFF34C759), modifier = Modifier.size(32.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.widthIn(max = 360.dp)) {
            Text("Llegaste", style = Civic.type.title, color = Civic.colors.text)
            Text(dest.name, style = Civic.type.bodySmall, color = Civic.colors.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(16.dp))
        CivicButton("Listo", onDone)
    }
}
