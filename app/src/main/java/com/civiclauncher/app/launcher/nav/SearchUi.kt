package com.civiclauncher.app.launcher.nav

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.civiclauncher.app.container
import com.civiclauncher.app.ui.design.Civic
import com.civiclauncher.app.ui.design.LinearMeter
import com.civiclauncher.app.ui.design.PhosphorIcons as P
import com.civiclauncher.app.ui.design.Tone
import com.civiclauncher.app.ui.design.bevelBorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.mapsforge.core.model.LatLong

/**
 * Buscar un destino. A la izquierda lo que escribes y los resultados; a la derecha un teclado propio,
 * compacto y con teclas grandes (el de Android tapaba media pantalla, incluidos los resultados).
 * Sin escribir nada, muestra "Elegir en el mapa", Casa, Trabajo y los destinos recientes.
 */
@Composable
fun SearchPanel(
    near: LatLong,
    onPick: (Place) -> Unit,
    onPickOnMap: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalContext.current.container
    val index = c.nav.search
    // El índice se carga en la memoria solo mientras buscas (y un par de minutos después).
    androidx.compose.runtime.DisposableEffect(Unit) {
        index.prepare(near)
        onDispose { index.release() }
    }
    val indexState by index.state.collectAsStateWithLifecycle()
    val home by c.nav.home.collectAsStateWithLifecycle()
    val work by c.nav.work.collectAsStateWithLifecycle()
    val recent by c.nav.recent.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    // Busca poco después de dejar de escribir (no en cada letra) y fuera del hilo de la pantalla.
    LaunchedEffect(query, indexState) {
        delay(200)
        hits = withContext(Dispatchers.Default) { index.search(query, near) }
    }

    val shape = RoundedCornerShape(Civic.radii.xl)
    Row(modifier.fillMaxSize().padding(Civic.space.l), horizontalArrangement = Arrangement.spacedBy(Civic.space.m)) {
        // ---- Izquierda: lo escrito y los resultados.
        Column(
            Modifier
                .width(400.dp)
                .fillMaxHeight()
                .blockTouches()
                .clip(shape)
                .background(Civic.colors.surface)
                .bevelBorder(shape)
                .padding(14.dp),
        ) {
            QueryField(query, onClose)
            Spacer(Modifier.height(10.dp))
            if (indexState == IndexState.Loading || (indexState !is IndexState.Building && !index.loaded && query.length >= 2)) {
                Text("Cargando la búsqueda…", style = Civic.type.caption, color = Civic.colors.textDim)
                Spacer(Modifier.height(10.dp))
            }
            (indexState as? IndexState.Building)?.let { b ->
                Text("Preparando la búsqueda de tu zona (solo la primera vez)… ${(b.progress * 100).toInt()} %", style = Civic.type.caption, color = Civic.colors.textDim)
                Spacer(Modifier.height(6.dp))
                LinearMeter(b.progress, Tone.Accent)
                Spacer(Modifier.height(10.dp))
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (query.length < 2) {
                    item(key = "map") { ResultRow(P.MapPinFill, "Elegir en el mapa", "Mueve el mapa y pon el pin", onPickOnMap) }
                    home?.let { p -> item(key = "home") { ResultRow(P.HouseFill, "Casa", p.name) { onPick(p) } } }
                    work?.let { p -> item(key = "work") { ResultRow(P.MapPinFill, "Trabajo", p.name) { onPick(p) } } }
                    items(recent, key = { "r" + it.point.latitude + it.point.longitude }) { p -> ResultRow(P.Clock, p.name, "Reciente") { onPick(p) } }
                } else {
                    items(hits, key = { it.name + it.point.latitude + it.point.longitude }) { h ->
                        val (d, u) = formatDistance(h.meters)
                        ResultRow(if (h.kind == "Calle") P.NavigationArrow else P.MapPin, h.name, "${h.kind} · $d $u") { onPick(Place(h.name, h.point)) }
                    }
                    if (hits.isEmpty() && indexState is IndexState.Ready && index.loaded) {
                        item { Text("Sin resultados cerca. Prueba con otra palabra.", style = Civic.type.bodySmall, color = Civic.colors.textDim, modifier = Modifier.padding(8.dp)) }
                    }
                }
            }
        }

        // ---- Derecha, abajo: el teclado, compacto (arriba se sigue viendo el mapa).
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.BottomCenter) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .blockTouches()
                    .clip(shape)
                    .background(Civic.colors.surface)
                    .bevelBorder(shape)
                    .padding(10.dp),
            ) {
                CivicKeyboard(
                    onKey = { if (query.length < 40) query += it },
                    onBackspace = { query = query.dropLast(1) },
                    onClear = { query = "" },
                )
            }
        }
    }
}

/**
 * Que los toques no lleguen al mapa de abajo: el mapa es una vista de Android y, si nadie se queda
 * con el toque, arrastrar sobre un panel movía el mapa.
 */
internal fun Modifier.blockTouches(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent().changes.forEach { it.consume() }
    }
}

/** Lo que llevas escrito, con un cursor que parpadea, y el botón para cerrar. */
@Composable
private fun QueryField(query: String, onClose: () -> Unit) {
    val blink = rememberInfiniteTransition(label = "cursor")
    val cursor by blink.animateFloat(1f, 0f, infiniteRepeatable(tween(530, delayMillis = 400), RepeatMode.Reverse), label = "cursor")
    Row(
        Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(50)).background(Civic.colors.surfaceRaised).padding(start = 16.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(P.MagnifyingGlass, null, tint = Civic.colors.textDim, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            if (query.isNotEmpty()) {
                Text(query, style = Civic.type.title.copy(fontSize = 19.sp), color = Civic.colors.text, maxLines = 1, overflow = TextOverflow.StartEllipsis, modifier = Modifier.weight(1f, fill = false))
            }
            Box(Modifier.width(2.dp).height(24.dp).alpha(cursor).background(Civic.colors.accent))
            if (query.isEmpty()) {
                Spacer(Modifier.width(4.dp))
                Text("Lugar o calle", style = Civic.type.title.copy(fontSize = 19.sp), color = Civic.colors.textFaint)
            }
        }
        Box(
            Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = "Cerrar la búsqueda", onClick = onClose),
            contentAlignment = Alignment.Center,
        ) { Icon(P.X, "Cerrar", tint = Civic.colors.textDim, modifier = Modifier.size(20.dp)) }
    }
}

private val LETTERS = listOf("qwertyuiop", "asdfghjklñ", "zxcvbnm")
private val NUMBERS = listOf("1234567890", "-/.,'&()#", "@+:")
private val KEY_H = 44.dp

/**
 * Teclado propio de la búsqueda: solo lo necesario (letras con ñ, números, espacio y borrar), del
 * tamaño de un teclado de teléfono grande. Sin acentos: la búsqueda los ignora.
 */
@Composable
private fun CivicKeyboard(onKey: (String) -> Unit, onBackspace: () -> Unit, onClear: () -> Unit) {
    var numbers by remember { mutableStateOf(false) }
    val rows = if (numbers) NUMBERS else LETTERS
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEachIndexed { i, row ->
            Row(Modifier.fillMaxWidth().height(KEY_H), horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally)) {
                // Filas cortas: mismo ancho de tecla que las largas (centradas, como cualquier teclado).
                val pad = (10 - row.length - if (i == 2) 1.5f else 0f).coerceAtLeast(0f)
                if (pad > 0) Spacer(Modifier.weight(pad / 2))
                for (ch in row) Key(ch.toString(), Modifier.weight(1f)) { onKey(ch.toString()) }
                if (i == 2) Key(null, Modifier.weight(1.5f), icon = P.Backspace, label = "Borrar", onLongClick = onClear, onClick = onBackspace)
                if (pad > 0) Spacer(Modifier.weight(pad / 2))
            }
        }
        Row(Modifier.fillMaxWidth().height(KEY_H), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Key(if (numbers) "abc" else "123", Modifier.weight(2f), muted = true) { numbers = !numbers }
            Key("espacio", Modifier.weight(8f), muted = true) { onKey(" ") }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun RowScope.Key(
    text: String?,
    modifier: Modifier,
    icon: ImageVector? = null,
    label: String? = null,
    muted: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val bg = when {
        pressed -> Civic.colors.accent
        muted -> Civic.colors.surfaceRaised.copy(alpha = 0.55f)
        else -> Civic.colors.surfaceRaised
    }
    Box(
        modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(9.dp))
            .background(bg)
            .combinedClickable(
                interactionSource = source,
                indication = null,
                role = Role.Button,
                onClickLabel = label ?: text,
                onLongClick = onLongClick,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Icon(icon, label, tint = if (pressed) Civic.colors.onAccent else Civic.colors.text, modifier = Modifier.size(22.dp))
        } else if (text != null) {
            val small = text.length > 1
            Text(
                text,
                style = Civic.type.title.copy(fontSize = if (small) 15.sp else 20.sp, fontWeight = if (small) FontWeight.Medium else FontWeight.Normal),
                color = if (pressed) Civic.colors.onAccent else if (muted) Civic.colors.textDim else Civic.colors.text,
            )
        }
    }
}

@Composable
private fun ResultRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Civic.radii.m))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Civic.colors.textDim, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = Civic.type.label, color = Civic.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = Civic.type.caption, color = Civic.colors.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
