package com.civiclauncher.app.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Borde "biselado" de 1 dp: más claro arriba y más tenue abajo, como luz que cae desde arriba.
 * Es un solo trazo con degradado: no agrega capas ni sombras (barato para la Mali-450).
 */
@Composable
fun Modifier.bevelBorder(shape: androidx.compose.ui.graphics.Shape): Modifier {
    val c = Civic.colors
    val top = lerp(c.line, c.text, if (c.isDark) 0.22f else 0.08f)
    val bottom = lerp(c.surface, c.line, 0.45f)
    return this.border(1.dp, androidx.compose.ui.graphics.Brush.verticalGradient(listOf(top, bottom)), shape)
}

/** Tono semántico: el color sale de la paleta activa (día o noche). */
enum class Tone { Neutral, Accent, Ok, Warn, Bad, Cool }

@Composable
fun Tone.color(): Color = when (this) {
    Tone.Neutral -> Civic.colors.textDim
    Tone.Accent -> Civic.colors.accent
    Tone.Ok -> Civic.colors.ok
    Tone.Warn -> Civic.colors.warn
    Tone.Bad -> Civic.colors.bad
    Tone.Cool -> Civic.colors.cool
}

/**
 * Clic sin capa de "ripple": al presionar solo cambia el color de fondo (una capa opaca, barata para la GPU).
 * Devuelve el modifier y si está presionado.
 */
@Composable
private fun rememberPress(): Pair<MutableInteractionSource, Boolean> {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    return source to pressed
}

private fun Modifier.press(source: MutableInteractionSource, enabled: Boolean, role: Role, onClick: (() -> Unit)?): Modifier =
    if (onClick == null) this else clickable(source, indication = null, enabled = enabled, role = role, onClick = onClick)

// ---------------------------------------------------------------- Tarjeta

@Composable
fun CivicCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: PaddingValues = PaddingValues(Civic.space.l),
    content: @Composable ColumnScope.() -> Unit,
) {
    val (source, pressed) = rememberPress()
    val bg = if (pressed) Civic.colors.surfacePressed else Civic.colors.surface
    Column(
        modifier
            .clip(RoundedCornerShape(Civic.radii.l))
            .background(bg)
            .press(source, true, Role.Button, onClick)
            .padding(padding),
        content = content,
    )
}

// ---------------------------------------------------------------- Botones

enum class ButtonKind { Primary, Secondary, Danger }

@Composable
fun CivicButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    kind: ButtonKind = ButtonKind.Primary,
    enabled: Boolean = true,
    /** Menos relleno a los lados, para botones lado a lado en espacios angostos. */
    compact: Boolean = false,
) {
    val (source, pressed) = rememberPress()
    val c = Civic.colors
    val (bg, fg) = when (kind) {
        ButtonKind.Primary -> (if (pressed) c.accentPressed else c.accent) to c.onAccent
        ButtonKind.Secondary -> (if (pressed) c.surfacePressed else c.surfaceRaised) to c.text
        ButtonKind.Danger -> (if (pressed) lerp(c.bad, Color.Black, 0.2f) else c.bad) to Color.White
    }
    Row(
        modifier
            .heightIn(min = Civic.sizes.touch)
            .clip(RoundedCornerShape(Civic.radii.m))
            .background(if (enabled) bg else c.surfaceRaised)
            .press(source, enabled, Role.Button, onClick)
            .padding(horizontal = if (compact) Civic.space.m else Civic.space.xl),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        val color = if (enabled) fg else c.textFaint
        if (icon != null) {
            Icon(icon, null, tint = color, modifier = Modifier.size(Civic.sizes.icon))
            Spacer(Modifier.width(Civic.space.m))
        }
        // Un botón nunca debe partir su texto en varias líneas: se recorta con "…".
        Text(text, style = Civic.type.label.colored(color), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun CivicIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Civic.colors.text,
) {
    val (source, pressed) = rememberPress()
    Box(
        modifier
            .size(Civic.sizes.touch)
            .clip(CircleShape)
            .background(if (pressed) Civic.colors.surfacePressed else Civic.colors.surfaceRaised)
            .press(source, true, Role.Button, onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(Civic.sizes.icon))
    }
}

// ---------------------------------------------------------------- Accesos y dock

/** Acceso grande de la pantalla principal (Android Auto, radio, cámara...). */
@Composable
fun LaunchTile(
    icon: ImageVector,
    label: String,
    tone: Tone,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = Civic.sizes.tile,
) {
    val (source, pressed) = rememberPress()
    Column(
        modifier
            .width(size)
            .clip(RoundedCornerShape(Civic.radii.l))
            .background(if (pressed) Civic.colors.surfacePressed else Civic.colors.surface)
            .press(source, true, Role.Button, onClick)
            .padding(vertical = Civic.space.l, horizontal = Civic.space.s),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(64.dp).clip(CircleShape).background(tone.color()),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(Civic.sizes.iconLarge - 6.dp))
        }
        Spacer(Modifier.height(Civic.space.m))
        Text(
            label,
            style = Civic.type.label.colored(Civic.colors.text),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/** Botón del dock lateral. Seleccionado: fondo elevado + ícono relleno + barra de acento. */
@Composable
fun DockItem(
    icon: ImageVector,
    selectedIcon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (source, pressed) = rememberPress()
    val c = Civic.colors
    val bg = when {
        pressed -> c.surfacePressed
        selected -> c.surfaceRaised
        else -> c.surface
    }
    Row(modifier.fillMaxWidth().height(84.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(4.dp).height(40.dp).clip(RoundedCornerShape(2.dp)).background(if (selected) c.accent else c.surface))
        Column(
            Modifier
                .padding(horizontal = Civic.space.s)
                .fillMaxWidth()
                .fillMaxHeight()
                .clip(RoundedCornerShape(Civic.radii.m))
                .background(bg)
                .press(source, true, Role.Tab, onClick),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                if (selected) selectedIcon else icon,
                label,
                tint = if (selected) c.accent else c.textDim,
                modifier = Modifier.size(Civic.sizes.icon + 4.dp),
            )
            Spacer(Modifier.height(Civic.space.xs))
            Text(label, style = Civic.type.caption.colored(if (selected) c.text else c.textDim), maxLines = 1)
        }
    }
}

// ---------------------------------------------------------------- Datos

/** Tarjeta de un dato: ícono, nombre y valor grande. Para temperatura, voltaje, consumo... */
@Composable
fun ValueCard(
    icon: ImageVector,
    label: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.Neutral,
) {
    CivicCard(modifier.widthIn(min = 160.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = tone.color(), modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(Civic.space.s))
            Text(label, style = Civic.type.caption.colored(Civic.colors.textDim), maxLines = 1)
        }
        Spacer(Modifier.height(Civic.space.s))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = Civic.type.number.colored(if (tone == Tone.Neutral) Civic.colors.text else tone.color()))
            Spacer(Modifier.width(Civic.space.xs))
            Text(unit, style = Civic.type.gaugeUnit.colored(Civic.colors.textDim), modifier = Modifier.padding(bottom = 4.dp))
        }
    }
}

@Composable
fun StatusPill(text: String, tone: Tone, modifier: Modifier = Modifier) {
    val c = tone.color()
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(Civic.colors.surfaceRaised)
            .padding(horizontal = Civic.space.m, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(c))
        Spacer(Modifier.width(Civic.space.s))
        Text(text, style = Civic.type.caption.colored(Civic.colors.text))
    }
}

/** Aviso importante (motor caliente, voltaje bajo, código de falla nuevo). */
@Composable
fun AlertBanner(
    icon: ImageVector,
    title: String,
    message: String,
    tone: Tone,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val (source, pressed) = rememberPress()
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Civic.radii.l))
            .background(if (pressed) Civic.colors.surfacePressed else Civic.colors.surface)
            .press(source, true, Role.Button, onClick)
            .padding(Civic.space.l),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(tone.color()), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.width(Civic.space.l))
        Column(Modifier.weight(1f)) {
            Text(title, style = Civic.type.title.colored(Civic.colors.text))
            Text(message, style = Civic.type.bodySmall.colored(Civic.colors.textDim))
        }
    }
}

/** Barra horizontal (nivel, carga del motor, acelerador). [fraction] de 0 a 1. */
@Composable
fun LinearMeter(fraction: Float, tone: Tone, modifier: Modifier = Modifier, height: Dp = 10.dp) {
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(50))
            .background(Civic.colors.surfaceRaised),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(RoundedCornerShape(50))
                .background(tone.color()),
        )
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(vertical = Civic.space.s), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = Civic.type.title.colored(Civic.colors.text), modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}
