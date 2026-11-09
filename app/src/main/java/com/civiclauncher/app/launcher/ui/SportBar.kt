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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.civiclauncher.app.container
import com.civiclauncher.app.launcher.data.VendorApps
import com.civiclauncher.app.launcher.media.MediaState
import com.civiclauncher.app.launcher.media.MusicSource
import com.civiclauncher.app.ui.design.Civic
import com.civiclauncher.app.ui.design.PhosphorIcons as P
import com.civiclauncher.app.ui.design.bevelBorder
import com.civiclauncher.obd.EngineState

/**
 * Barra de abajo del estilo Sport: una sola franja con la música a la izquierda (anterior, pausa,
 * siguiente y la canción), Inicio grande al centro rodeado de los accesos, y a la derecha el estado
 * del OBD y el volumen. Siempre a la vista: es el control de la música.
 */
@Composable
fun SportBottomBar(current: Dest, onSelect: (Dest) -> Unit) {
    val ctx = LocalContext.current
    val apps = ctx.container.apps
    fun open(pkg: String) {
        if (!apps.launchPackage(pkg)) Toast.makeText(ctx, "No está instalada en esta pantalla", Toast.LENGTH_SHORT).show()
    }
    val shape = RoundedCornerShape(38.dp)
    Row(
        Modifier
            .padding(start = PAGE_GUTTER, end = PAGE_GUTTER, top = 8.dp, bottom = 10.dp)
            .fillMaxWidth()
            .height(74.dp)
            .clip(shape)
            .background(Civic.colors.surface)
            .bevelBorder(shape)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Los dos lados pesan lo mismo: así Inicio queda justo en medio de la pantalla.
        BarMusic(Modifier.weight(1f).fillMaxHeight().padding(end = 6.dp)) { onSelect(Dest.MUSIC) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            BarIcon(P.SpeedometerLight, P.SpeedometerFill, "Vehículo", current == Dest.VEHICLE) { onSelect(Dest.VEHICLE) }
            BarIcon(P.AndroidLogoLight, P.AndroidLogoFill, "Android Auto", false) { open(VendorApps.ANDROID_AUTO) }
            Spacer(Modifier.width(4.dp))
            HomeButton { onSelect(Dest.HOME) }
            Spacer(Modifier.width(4.dp))
            BarIcon(P.RadioLight, P.RadioFill, "Radio", current == Dest.RADIO) { onSelect(Dest.RADIO) }
            // Editar Inicio (el Bluetooth del celular quedó en Ajustes → Música).
            val editing by SportLayout.editing.collectAsStateWithLifecycle()
            BarIcon(P.PencilSimple, P.CheckCircleFill, if (editing) "Terminar de editar" else "Editar Inicio", editing) {
                if (current != Dest.HOME) onSelect(Dest.HOME)
                if (editing) SportLayout.stopEditing() else SportLayout.startEditing()
            }
        }
        Row(
            Modifier.weight(1f).padding(start = 6.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ObdPill()
            Spacer(Modifier.width(8.dp))
            VolumeCapsule(Modifier.width(164.dp), height = 56.dp)
        }
    }
}

/** La música: controles grandes y la canción, sobre un brillo con el color de la portada. */
@Composable
private fun BarMusic(modifier: Modifier, onOpen: () -> Unit) {
    val c = LocalContext.current.container
    val state by c.media.state.collectAsStateWithLifecycle()
    val now = (state as? MediaState.Active)?.now
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        MusicButton(P.SkipBackFill, "Anterior", 26.dp, enabled = now != null) { c.media.previous() }
        MusicButton(if (now?.playing == true) P.PauseFill else P.PlayFill, if (now?.playing == true) "Pausar" else "Reproducir", 32.dp, enabled = true) {
            if (now != null) c.media.playPause() else if (!c.media.playBluetooth()) onOpen()
        }
        MusicButton(P.SkipForwardFill, "Siguiente", 26.dp, enabled = now != null) { c.media.next() }
        Spacer(Modifier.width(6.dp))
        val glow = now?.artColor ?: if (now?.source == MusicSource.RADIO) Color(0xFFD42F3A) else null
        Column(
            Modifier
                .weight(1f)
                .height(56.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    if (glow != null) Brush.horizontalGradient(listOf(glow.copy(alpha = 0.40f), Color.Transparent))
                    else Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent)),
                )
                .clickable(role = Role.Button, onClickLabel = "Abrir la música", onClick = onOpen)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                now?.title ?: "Sin música",
                style = Civic.type.label.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                color = Civic.colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                now?.let { it.artist ?: sourceLabel(it.source) } ?: "Toca para elegir",
                style = Civic.type.caption.copy(fontSize = 13.sp),
                color = Civic.colors.textDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MusicButton(icon: ImageVector, label: String, iconSize: Dp, enabled: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box(
        Modifier
            .size(54.dp)
            .clip(CircleShape)
            .background(if (pressed) Civic.colors.surfacePressed else Color.Transparent)
            .clickable(source, indication = null, enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = if (enabled) Civic.colors.text else Civic.colors.textFaint, modifier = Modifier.size(iconSize))
    }
}

/** Inicio: un círculo claro, el botón principal de la barra. */
@Composable
private fun HomeButton(onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box(
        Modifier
            .size(62.dp)
            .clip(CircleShape)
            .background(if (pressed) Color(0xFFB9BEC6) else Color(0xFFDCDFE4))
            .clickable(source, indication = null, role = Role.Button, onClickLabel = "Inicio", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(P.HouseFill, "Inicio", tint = Color(0xFF15181D), modifier = Modifier.size(28.dp))
    }
}

@Composable
private fun BarIcon(icon: ImageVector, selectedIcon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(
                when {
                    pressed -> Civic.colors.surfacePressed
                    selected -> Civic.colors.selected
                    else -> Color.Transparent
                },
            )
            .clickable(source, indication = null, role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(if (selected) selectedIcon else icon, label, tint = if (selected) Civic.colors.text else Civic.colors.textDim, modifier = Modifier.size(26.dp))
    }
}

/** El OBD de un vistazo: punto verde si está conectado. */
@Composable
private fun ObdPill() {
    val state by LocalContext.current.container.obd.state.collectAsStateWithLifecycle()
    val on = state is EngineState.Connected
    Row(
        Modifier
            .height(40.dp)
            .clip(RoundedCornerShape(50))
            .background(Civic.colors.surfaceRaised)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (on) Color(0xFF34C759) else Civic.colors.textFaint))
        Spacer(Modifier.width(8.dp))
        Text("OBD", style = Civic.type.label.copy(fontSize = 15.sp), color = if (on) Civic.colors.text else Civic.colors.textDim)
    }
}
