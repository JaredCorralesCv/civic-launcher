package com.civiclauncher.app.launcher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.civiclauncher.app.launcher.nav.blockTouches
import com.civiclauncher.app.ui.design.Civic
import com.civiclauncher.app.ui.design.PhosphorIcons as P
import com.civiclauncher.app.ui.design.bevelBorder

/**
 * Menú "Editar" del Inicio Sport: todos los widgets, por categorías, cada uno con su vista previa en
 * vivo, su tamaño y dónde está puesto. Al elegir uno se escoge el lugar (solo los de su tamaño).
 * Si se abrió tocando un lugar de Inicio ([target]), elegir un widget lo pone directo ahí.
 */
@Composable
internal fun EditMenu(
    layout: Map<Slot, SportWidget>,
    arrangement: HomeArrangement,
    target: Slot?,
    onPlace: (Slot, SportWidget) -> Unit,
    onArrangement: (HomeArrangement) -> Unit,
    onReset: () -> Unit,
    onClearTarget: () -> Unit,
    onClose: () -> Unit,
    onDone: () -> Unit,
    preview: @Composable (SportWidget, SlotSize) -> Unit,
) {
    // Opaco aunque haya fondo de pantalla.
    com.civiclauncher.app.ui.design.OpaqueSurfaces {
        var category by remember { mutableStateOf<WidgetCategory?>(null) }
        var choosing by remember { mutableStateOf<SportWidget?>(null) }
        /** 0: Widgets · 1: Diseño. */
        var tab by remember { mutableStateOf(0) }
        val shape = RoundedCornerShape(Civic.radii.xl)
        Box(Modifier.fillMaxSize().blockTouches().padding(horizontal = PAGE_GUTTER, vertical = Civic.space.m)) {
            Column(
                Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .background(Civic.colors.surface)
                    .bevelBorder(shape)
                    .padding(horizontal = 18.dp, vertical = 14.dp),
            ) {
                // Encabezado: pestañas, restablecer, listo y cerrar.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MenuTab("Widgets", tab == 0) { tab = 0 }
                    Spacer(Modifier.width(18.dp))
                    MenuTab("Diseño", tab == 1) { tab = 1 }
                    Spacer(Modifier.weight(1f))
                    HeaderButton("Restablecer", primary = false, onClick = onReset)
                    Spacer(Modifier.width(8.dp))
                    HeaderButton("Listo", primary = true, onClick = onDone)
                    Spacer(Modifier.width(4.dp))
                    Box(
                        Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = "Ver Inicio", onClick = onClose),
                        contentAlignment = Alignment.Center,
                    ) { Icon(P.X, "Cerrar el menú", tint = Civic.colors.text, modifier = Modifier.size(22.dp)) }
                }
                Spacer(Modifier.height(10.dp))
                if (tab == 1) {
                    ArrangementPicker(arrangement, onArrangement)
                } else {
                // Si se abrió desde un lugar: para cuál se está eligiendo.
                if (target != null) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Civic.colors.accent.copy(alpha = 0.16f)).padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Eligiendo para: ${target.label} (${target.size.label}). Solo se pueden poner los que caben ahí.",
                            style = Civic.type.caption.copy(fontSize = 13.sp),
                            color = Civic.colors.text,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "Ver todos",
                            style = Civic.type.label.copy(fontSize = 13.sp),
                            color = Civic.colors.accent,
                            modifier = Modifier.clip(RoundedCornerShape(50)).clickable(role = Role.Button, onClick = onClearTarget).padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                }
                // Categorías.
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Civic.colors.surfaceRaised).horizontalScroll(rememberScrollState()).padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    CategoryChip("Todos", category == null) { category = null }
                    WidgetCategory.entries.forEach { cat -> CategoryChip(cat.label, category == cat) { category = cat } }
                }
                Spacer(Modifier.height(12.dp))
                // Por secciones (una por categoría), 4 por renglón. Si se eligió un lugar, solo lo que cabe.
                // La lista es perezosa: solo existen (y se dibujan) las miniaturas que están a la vista.
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Primero las categorías con algo que cabe en este diseño; las demás, al final.
                    val sizesHere = SportLayout.slotsOf(arrangement).map { it.size }.toSet()
                    val cats = if (category == null) WidgetCategory.entries.sortedBy { cat ->
                        when {
                            // En pantalla completa, primero las pantallas completas.
                            arrangement == HomeArrangement.FULL && cat == WidgetCategory.FULLSCREEN -> 0
                            SportWidget.entries.any { it.category == cat && it.sizes.any { s -> s in sizesHere } } -> 1
                            else -> 2
                        }
                    } else listOf(category!!)
                    for (cat in cats) {
                    val widgets = SportWidget.entries.filter { it.category == cat && (target == null || target.size in it.sizes) }
                    if (widgets.isEmpty()) continue
                    item(key = "h" + cat.name) {
                        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 4.dp)) {
                            Text(cat.label, style = Civic.type.title.copy(fontSize = 18.sp), color = Civic.colors.text)
                            Spacer(Modifier.width(8.dp))
                            Text("${widgets.size}", style = Civic.type.caption, color = Civic.colors.textFaint, modifier = Modifier.padding(bottom = 2.dp))
                        }
                    }
                    widgets.chunked(4).forEachIndexed { i, row ->
                        item(key = "r" + cat.name + i) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                row.forEach { w ->
                                    val usedIn = layout.entries.firstOrNull { it.value == w }?.key
                                    val fits = if (target != null) target.size in w.sizes else SportLayout.slotsOf(arrangement).any { it.size in w.sizes }
                                    WidgetCard(w, usedIn, fits, target, arrangement, preview) {
                                        when {
                                            target != null -> if (fits) onPlace(target, w)
                                            else -> {
                                                // Si solo cabe en un lugar de este diseño, se pone ahí sin preguntar.
                                                val spots = SportLayout.slotsOf(arrangement).filter { it.size in w.sizes }
                                                if (spots.size == 1) onPlace(spots.first(), w) else choosing = w
                                            }
                                        }
                                    }
                                }
                                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                    }
                }
                }
            }
            choosing?.let { w ->
                SlotChooser(w, layout, arrangement, onPick = { slot -> onPlace(slot, w); choosing = null }, onClose = { choosing = null })
            }
        }
    }
}

@Composable
private fun MenuTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Column(Modifier.width(IntrinsicMax).clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onClick).padding(top = 4.dp)) {
        Text(label, style = Civic.type.label.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold), color = if (selected) Civic.colors.accent else Civic.colors.textDim)
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(2.dp).background(if (selected) Civic.colors.accent else Color.Transparent))
    }
}

private val IntrinsicMax = 74.dp

/**
 * Pestaña Diseño: cómo se reparte Inicio. Cada diseño con su dibujito; cada uno conserva su propio
 * acomodo de widgets (cambiar de diseño no borra lo que pusiste en el otro).
 */
@Composable
private fun ArrangementPicker(current: HomeArrangement, onPick: (HomeArrangement) -> Unit) {
    Text("Elige cómo se reparte Inicio. Cada diseño guarda sus propios widgets.", style = Civic.type.bodySmall, color = Civic.colors.textDim)
    Spacer(Modifier.height(14.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        HomeArrangement.entries.forEach { a ->
            val selected = a == current
            val shape = RoundedCornerShape(Civic.radii.l)
            Column(
                Modifier
                    .weight(1f)
                    .clip(shape)
                    .background(if (selected) Civic.colors.accent.copy(alpha = 0.16f) else Civic.colors.surfaceRaised)
                    .border(2.dp, if (selected) Civic.colors.accent else Color.Transparent, shape)
                    .clickable(role = Role.Button, onClickLabel = a.label) { onPick(a) }
                    .padding(16.dp),
            ) {
                // Dibujito del diseño.
                Row(Modifier.fillMaxWidth().height(150.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val box = Modifier.clip(RoundedCornerShape(8.dp)).background(if (selected) Civic.colors.accent.copy(alpha = 0.35f) else Civic.colors.line)
                    when (a) {
                        HomeArrangement.GRID -> {
                            Column(Modifier.weight(0.95f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Box(box.weight(1.1f).fillMaxWidth())
                                Box(box.weight(1f).fillMaxWidth())
                            }
                            Box(box.weight(1.45f).fillMaxHeight())
                            Box(box.weight(1.05f).fillMaxHeight())
                        }
                        HomeArrangement.MAP -> {
                            Column(Modifier.weight(0.36f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Box(box.weight(0.25f).fillMaxWidth())
                                Box(box.weight(0.5f).fillMaxWidth())
                                Box(box.weight(0.25f).fillMaxWidth())
                            }
                            Box(box.weight(0.64f).fillMaxHeight())
                        }
                        HomeArrangement.FULL -> Box(box.fillMaxSize())
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(a.label, style = Civic.type.label.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold), color = Civic.colors.text, modifier = Modifier.weight(1f))
                    if (selected) Icon(P.CheckCircleFill, "Elegido", tint = Civic.colors.accent, modifier = Modifier.size(22.dp))
                }
                Text(a.description, style = Civic.type.caption, color = Civic.colors.textDim)
            }
        }
    }
}

@Composable
private fun HeaderButton(label: String, primary: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = Civic.type.label.copy(fontWeight = FontWeight.SemiBold),
        color = if (primary) Civic.colors.onAccent else Civic.colors.text,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (primary) Civic.colors.accent else Civic.colors.surfaceRaised)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
    )
}

@Composable
private fun CategoryChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = Civic.type.label.copy(fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal),
        color = if (selected) Civic.colors.onAccent else Civic.colors.text,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Civic.colors.accent else Color.Transparent)
            .border(1.dp, if (selected) Color.Transparent else Civic.colors.line, RoundedCornerShape(50))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/**
 * Tarjeta de un widget: su vista previa en vivo (el widget real, en miniatura), el nombre, los
 * tamaños en que cabe y dónde está puesto.
 */
@Composable
private fun RowScope.WidgetCard(
    w: SportWidget,
    usedIn: Slot?,
    fits: Boolean,
    target: Slot?,
    arrangement: HomeArrangement,
    preview: @Composable (SportWidget, SlotSize) -> Unit,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(Civic.radii.l)
    // La miniatura, en el tamaño en que se usaría aquí (el del lugar elegido o uno del diseño actual).
    val here = SportLayout.slotsOf(arrangement).map { it.size }
    val size = if (target != null && target.size in w.sizes) target.size else w.sizes.firstOrNull { it in here } ?: w.sizes.first()
    Column(
        Modifier
            .weight(1f)
            .alpha(if (fits) 1f else 0.35f)
            .clip(shape)
            .background(Civic.colors.surfaceRaised)
            .border(if (usedIn != null) 1.5.dp else 0.dp, if (usedIn != null) Civic.colors.accent.copy(alpha = 0.6f) else Color.Transparent, shape),
    ) {
        BoxWithConstraints(
            Modifier.fillMaxWidth().height(150.dp).background(Color.Black.copy(alpha = 0.25f)),
            contentAlignment = Alignment.Center,
        ) {
            val s = minOf(maxWidth.value / size.previewW, maxHeight.value / size.previewH) * 0.88f
            Box(Modifier.requiredSize(size.previewW.dp, size.previewH.dp).graphicsLayer { scaleX = s; scaleY = s }) {
                preview(w, size)
            }
            // Encima, para que la miniatura no responda a toques: toda la tarjeta es un solo botón.
            Box(Modifier.matchParentSize().clickable(enabled = fits, role = Role.Button, onClickLabel = "Elegir ${w.label}", onClick = onClick))
        }
        Column(Modifier.fillMaxWidth().clickable(enabled = fits, role = Role.Button, onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(w.label, style = Civic.type.label.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), color = Civic.colors.text, maxLines = 1, modifier = Modifier.weight(1f))
                Text(if (w.sizes.size > 2) "${w.sizes.size} tamaños" else w.sizes.joinToString(" · ") { it.label }, style = Civic.type.caption.copy(fontSize = 11.sp), color = Civic.colors.textDim, maxLines = 1)
            }
            Spacer(Modifier.height(2.dp))
            Text(
                when {
                    usedIn != null -> "En uso · ${usedIn.label}"
                    !fits && target == null -> "No cabe en este diseño"
                    else -> w.description
                },
                style = Civic.type.caption.copy(fontSize = 12.sp),
                color = if (usedIn != null) Civic.colors.accent else Civic.colors.textDim,
                maxLines = 1,
            )
        }
    }
}

/**
 * ¿Dónde lo pongo? Un dibujito de Inicio con sus cuatro lugares; los que son del tamaño del widget
 * se pueden tocar. Si el widget ya está en otro lugar, se intercambian.
 */
@Composable
private fun SlotChooser(w: SportWidget, layout: Map<Slot, SportWidget>, arrangement: HomeArrangement, onPick: (Slot) -> Unit, onClose: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .blockTouches()
            .background(Color.Black.copy(alpha = 0.7f))
            .clickable(role = Role.Button, onClickLabel = "Cancelar", onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        val shape = RoundedCornerShape(Civic.radii.xl)
        Column(
            Modifier
                .width(560.dp)
                .clip(shape)
                .background(Civic.colors.surface)
                .bevelBorder(shape)
                .clickable(enabled = false) {}
                .padding(20.dp),
        ) {
            Text("¿Dónde pongo «${w.label}»?", style = Civic.type.title, color = Civic.colors.text)
            Text("Toca un lugar marcado. Solo caben los de su tamaño.", style = Civic.type.caption, color = Civic.colors.textDim)
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth().height(230.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (arrangement) {
                    HomeArrangement.GRID -> {
                        Column(Modifier.weight(0.95f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SlotBox(Slot.TOP_LEFT, w, layout, Modifier.weight(1.1f).fillMaxWidth(), onPick)
                            SlotBox(Slot.BOTTOM_LEFT, w, layout, Modifier.weight(1f).fillMaxWidth(), onPick)
                        }
                        SlotBox(Slot.CENTER, w, layout, Modifier.weight(1.45f).fillMaxHeight(), onPick)
                        SlotBox(Slot.RIGHT, w, layout, Modifier.weight(1.05f).fillMaxHeight(), onPick)
                    }
                    HomeArrangement.MAP -> {
                        Column(Modifier.weight(0.36f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SlotBox(Slot.MAP_TOP, w, layout, Modifier.weight(0.25f).fillMaxWidth(), onPick)
                            SlotBox(Slot.MAP_MIDDLE, w, layout, Modifier.weight(0.5f).fillMaxWidth(), onPick)
                            SlotBox(Slot.MAP_BOTTOM, w, layout, Modifier.weight(0.25f).fillMaxWidth(), onPick)
                        }
                        SlotBox(Slot.MAP_BIG, w, layout, Modifier.weight(0.64f).fillMaxHeight(), onPick)
                    }
                    HomeArrangement.FULL -> SlotBox(Slot.FULL_SLOT, w, layout, Modifier.fillMaxSize(), onPick)
                }
            }
            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { HeaderButton("Cancelar", primary = false, onClick = onClose) }
        }
    }
}

@Composable
private fun SlotBox(slot: Slot, w: SportWidget, layout: Map<Slot, SportWidget>, modifier: Modifier, onPick: (Slot) -> Unit) {
    val current = layout.getValue(slot)
    val here = current == w
    val ok = !here && SportLayout.place(layout, slot, w) != null
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier
            .clip(shape)
            .background(if (ok) Civic.colors.accent.copy(alpha = 0.18f) else Civic.colors.surfaceRaised)
            .border(if (ok || here) 2.dp else 0.dp, if (ok) Civic.colors.accent else if (here) Civic.colors.ok else Color.Transparent, shape)
            .clickable(enabled = ok, role = Role.Button, onClickLabel = "Poner en ${slot.label}") { onPick(slot) }
            .padding(8.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (slot.size != SlotSize.STRIP) {
            Icon(current.icon, null, tint = if (ok) Civic.colors.text else Civic.colors.textFaint, modifier = Modifier.size(22.dp))
            Spacer(Modifier.height(4.dp))
        }
        Text(current.label, style = Civic.type.caption.copy(fontSize = 12.sp), color = if (ok) Civic.colors.text else Civic.colors.textFaint, maxLines = 1)
        Text(
            when {
                here -> "Ya está aquí"
                ok -> "Poner aquí"
                else -> slot.size.label
            },
            style = Civic.type.caption.copy(fontSize = 10.sp),
            color = if (ok) Civic.colors.accent else if (here) Civic.colors.ok else Civic.colors.textFaint,
            maxLines = 1,
        )
    }
}

/**
 * Vista previa del mapa (un dibujo, no el mapa real: abrir otro mapa solo para la miniatura gastaría
 * mucha memoria).
 */
@Composable
internal fun MapPreview() {
    val shape = RoundedCornerShape(Civic.radii.xl)
    Canvas(Modifier.fillMaxSize().clip(shape).background(Color(0xFF1B2230))) {
        val w = size.width
        val h = size.height
        val street = Color(0xFF3A4458)
        for (i in 1..6) drawLine(street, Offset(0f, h * i / 7f), Offset(w, h * i / 7f - h * 0.08f), strokeWidth = 6f)
        for (i in 1..4) drawLine(street, Offset(w * i / 5f, 0f), Offset(w * i / 5f + w * 0.06f, h), strokeWidth = 6f)
        drawLine(Color(0xFF4A5570), Offset(0f, h * 0.15f), Offset(w, h * 0.85f), strokeWidth = 16f)
        drawLine(Color(0xFF0B3A8C), Offset(w * 0.5f, h * 0.8f), Offset(w * 0.62f, h * 0.2f), strokeWidth = 22f, cap = StrokeCap.Round)
        drawLine(Color(0xFF3B8BFF), Offset(w * 0.5f, h * 0.8f), Offset(w * 0.62f, h * 0.2f), strokeWidth = 13f, cap = StrokeCap.Round)
        drawCircle(Color(0x553B8BFF), 34f, Offset(w * 0.5f, h * 0.8f))
        drawCircle(Color.White, 18f, Offset(w * 0.5f, h * 0.8f))
        drawCircle(Color(0xFF3B8BFF), 12f, Offset(w * 0.5f, h * 0.8f))
    }
}
