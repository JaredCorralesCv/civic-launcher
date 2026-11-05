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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.civiclauncher.app.container
import com.civiclauncher.app.launcher.data.VendorApps
import com.civiclauncher.app.ui.design.Civic
import com.civiclauncher.app.ui.design.PhosphorIcons as P
import com.civiclauncher.app.ui.design.bevelBorder

/**
 * Barra de accesos inferior (siempre visible), sin rayas divisorias.
 * Secciones de Civic: Inicio, Vehículo, Mapa y Apps (se marcan con una línea azul).
 * Apps del fabricante: Android Auto (AutoKit), Radio y Teléfono (Bluetooth).
 * Ajustes está en el engrane de la barra superior; Archivos, dentro de Apps.
 */
@Composable
fun BottomBar(current: Dest, onSelect: (Dest) -> Unit) {
    val ctx = LocalContext.current
    val apps = ctx.container.apps
    fun open(pkg: String) {
        if (!apps.launchPackage(pkg)) Toast.makeText(ctx, "No está instalada en esta pantalla", Toast.LENGTH_SHORT).show()
    }
    val c = Civic.colors
    val shape = RoundedCornerShape(Civic.radii.xl)
    Row(
        Modifier
            .padding(start = Civic.space.l, end = Civic.space.l, bottom = Civic.space.m)
            .fillMaxWidth()
            .height(92.dp)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(lerp(c.surface, c.surfaceRaised, 0.6f), c.surface)))
            .bevelBorder(shape)
            .padding(horizontal = Civic.space.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Civic.space.xs),
    ) {
        BarItem(P.House, P.HouseFill, "Inicio", current == Dest.HOME || current == Dest.SETTINGS || current == Dest.CLEANUP) { onSelect(Dest.HOME) }
        BarItem(P.Gauge, P.GaugeFill, "Vehículo", current == Dest.VEHICLE) { onSelect(Dest.VEHICLE) }
        BarItem(P.MapTrifold, P.MapTrifoldFill, "Mapa", current == Dest.MAP) { onSelect(Dest.MAP) }
        BarItem(P.NavigationArrow, P.NavigationArrowFill, "Android Auto", false) { open(VendorApps.ANDROID_AUTO) }
        BarItem(P.Radio, P.RadioFill, "Radio", false) { open(VendorApps.RADIO) }
        BarItem(P.Phone, P.PhoneFill, "Teléfono", false) { open(VendorApps.BLUETOOTH) }
        BarItem(P.SquaresFour, P.SquaresFourFill, "Apps", current == Dest.APPS) { onSelect(Dest.APPS) }
    }
}

@Composable
private fun RowScope.BarItem(icon: ImageVector, selectedIcon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val c = Civic.colors
    Column(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .padding(vertical = Civic.space.s)
            .clip(RoundedCornerShape(Civic.radii.l))
            .background(
                when {
                    pressed -> c.surfacePressed
                    selected -> lerp(c.surfaceRaised, c.accent, 0.18f)
                    else -> Color.Transparent
                },
            )
            .clickable(source, indication = null, role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(if (selected) selectedIcon else icon, label, tint = if (selected) c.text else c.textDim, modifier = Modifier.size(30.dp))
        Spacer(Modifier.height(Civic.space.xs))
        Text(label, style = Civic.type.label, color = if (selected) c.text else c.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(Civic.space.xs))
        Box(Modifier.width(36.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(if (selected) c.accent else Color.Transparent))
    }
}
