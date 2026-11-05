package com.civiclauncher.app.launcher.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.civiclauncher.app.container
import com.civiclauncher.app.launcher.data.VendorApps
import com.civiclauncher.app.ui.design.Civic
import com.civiclauncher.app.ui.design.PhosphorIcons as P
import com.civiclauncher.app.ui.design.bevelBorder

/**
 * Barra de accesos inferior (siempre visible). Estilo actual de autos premium / iOS 26: íconos de
 * línea fina, sin pastillas de colores, y el seleccionado con el ícono relleno sobre una cápsula de
 * vidrio. Secciones de Civic: Inicio, Vehículo, Mapa y Apps. Apps del fabricante: Android Auto
 * (AutoKit), Radio y Bluetooth (llamadas y conexión del celular). Ajustes está arriba, en el engrane.
 */
@Composable
fun BottomBar(current: Dest, onSelect: (Dest) -> Unit) {
    val ctx = LocalContext.current
    val apps = ctx.container.apps
    fun open(pkg: String) {
        if (!apps.launchPackage(pkg)) Toast.makeText(ctx, "No está instalada en esta pantalla", Toast.LENGTH_SHORT).show()
    }
    // Fila de cápsulas sueltas (como la fila de controles del diseño de CarPlay): Inicio en un
    // círculo, el volumen en una cápsula con − / nivel / +, y los accesos en círculos de vidrio. El
    // que está abierto se vuelve una cápsula más ancha y clara.
    Row(
        Modifier
            .padding(start = Civic.space.l, end = Civic.space.l, top = 12.dp, bottom = 14.dp)
            .fillMaxWidth()
            .height(66.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Civic.space.m),
    ) {
        val home = current == Dest.HOME || current == Dest.APPS || current == Dest.SETTINGS || current == Dest.CLEANUP || current == Dest.MUSIC
        GlassCircle(if (home) P.HouseFill else P.HouseLight, "Inicio", home) { onSelect(Dest.HOME) }
        VolumeCapsule(Modifier.width(232.dp))
        Spacer(Modifier.weight(1f))
        NavPill(P.SpeedometerLight, P.SpeedometerFill, "Vehículo", current == Dest.VEHICLE) { onSelect(Dest.VEHICLE) }
        NavPill(P.MapTrifoldLight, P.MapTrifoldFill, "Mapa", current == Dest.MAP) { onSelect(Dest.MAP) }
        NavPill(P.AndroidLogoLight, P.AndroidLogoFill, "Android Auto", false) { open(VendorApps.ANDROID_AUTO) }
        NavPill(P.RadioLight, P.RadioFill, "Radio", current == Dest.RADIO) { onSelect(Dest.RADIO) }
        NavPill(P.BluetoothLight, P.BluetoothFill, "Bluetooth", false) { open(VendorApps.BLUETOOTH) }
    }
}

/** Círculo de vidrio con un ícono (Inicio). */
@Composable
private fun GlassCircle(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val c = Civic.colors
    Box(
        Modifier
            .size(66.dp)
            .clip(CircleShape)
            .background(if (pressed) c.surfacePressed else c.surface)
            .bevelBorder(CircleShape)
            .clickable(source, indication = null, role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = if (selected) c.text else c.textDim, modifier = Modifier.size(28.dp))
    }
}

/**
 * Acceso de la fila: círculo de vidrio con el ícono. El abierto se estira a cápsula con su nombre
 * y un vidrio más claro (como el botón resaltado del diseño).
 */
@Composable
private fun NavPill(icon: ImageVector, selectedIcon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val c = Civic.colors
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .height(66.dp)
            .let { if (selected) it else it.width(66.dp) }
            .clip(shape)
            .background(
                when {
                    pressed -> c.surfacePressed
                    selected -> c.surfacePressed
                    else -> c.surface
                },
            )
            .bevelBorder(shape)
            .clickable(source, indication = null, role = Role.Button, onClickLabel = label, onClick = onClick)
            .padding(horizontal = if (selected) 22.dp else 0.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (selected) selectedIcon else icon, label, tint = if (selected) c.text else c.textDim, modifier = Modifier.size(28.dp))
        if (selected) {
            Spacer(Modifier.width(Civic.space.s))
            Text(label, style = Civic.type.label.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = c.text, maxLines = 1)
        }
    }
}

/**
 * Volumen de la música (en el lugar de la temperatura del diseño): − a la izquierda, el nivel con
 * una barrita en medio y + a la derecha. Si cambias el volumen con la perilla o el volante, se
 * actualiza solo.
 */
@Composable
private fun VolumeCapsule(modifier: Modifier) {
    val ctx = LocalContext.current
    val am = remember { ctx.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager }
    val max = remember { am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var level by remember { androidx.compose.runtime.mutableIntStateOf(am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context, i: android.content.Intent) {
                level = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
            }
        }
        androidx.core.content.ContextCompat.registerReceiver(
            ctx,
            receiver,
            android.content.IntentFilter("android.media.VOLUME_CHANGED_ACTION"),
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED,
        )
        onDispose { runCatching { ctx.unregisterReceiver(receiver) } }
    }
    fun change(direction: Int) {
        runCatching { am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, direction, 0) }
        level = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
    }
    val c = Civic.colors
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .height(66.dp)
            .clip(shape)
            .background(c.surface)
            .bevelBorder(shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VolumeButton(P.CaretLeft, "Bajar volumen") { change(android.media.AudioManager.ADJUST_LOWER) }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(P.SpeakerHighFill, null, tint = c.textDim, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    level.toString(),
                    style = Civic.type.title.copy(fontSize = 26.sp, fontFeatureSettings = "tnum"),
                    color = c.text,
                )
            }
            Spacer(Modifier.height(4.dp))
            Box(Modifier.width(72.dp).height(3.dp).clip(RoundedCornerShape(50)).background(c.surfacePressed)) {
                Box(Modifier.fillMaxWidth(level.toFloat() / max).height(3.dp).clip(RoundedCornerShape(50)).background(c.text))
            }
        }
        VolumeButton(P.CaretRight, "Subir volumen") { change(android.media.AudioManager.ADJUST_RAISE) }
    }
}

@Composable
private fun VolumeButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box(
        Modifier
            .size(66.dp)
            .clip(CircleShape)
            .background(if (pressed) Civic.colors.surfacePressed else Color.Transparent)
            .clickable(source, indication = null, role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = Civic.colors.text, modifier = Modifier.size(22.dp))
    }
}

/** Tono de cada fuente (el ícono se tiñe con el primero), para distinguirlas de un vistazo. */
internal object BarColors {
    val INDIGO = Color(0xFF8E9BFF) to Color(0xFF3F4BD8)
    val RED = Color(0xFFFF7A7A) to Color(0xFFD42F3A)
    val GREEN = Color(0xFF5ED47E) to Color(0xFF1E9E4A)
    val ANDROID = Color(0xFF7BE3A8) to Color(0xFF2BA866)
}

/** Ícono de línea fina dentro de un círculo de vidrio, teñido con el tono de la fuente. */
@Composable
internal fun AppTile(icon: ImageVector, colors: Pair<Color, Color>, size: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(colors.second.copy(alpha = 0.22f))
            .bevelBorder(CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = colors.first, modifier = Modifier.size(size * 0.55f))
    }
}

@Composable
private fun RowScope.BarItem(icon: ImageVector, selectedIcon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val c = Civic.colors
    val pill = RoundedCornerShape(Civic.radii.l)
    Column(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .padding(vertical = 7.dp)
            .clip(pill)
            .background(
                when {
                    pressed -> c.surfacePressed
                    selected -> c.surfaceRaised
                    else -> Color.Transparent
                },
            )
            .let { if (selected) it.bevelBorder(pill) else it }
            .clickable(source, indication = null, role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            if (selected) selectedIcon else icon,
            label,
            tint = if (selected) c.text else c.textDim,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            style = Civic.type.caption.copy(fontSize = 13.sp, letterSpacing = 0.01.em),
            color = if (selected) c.text else c.textDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}