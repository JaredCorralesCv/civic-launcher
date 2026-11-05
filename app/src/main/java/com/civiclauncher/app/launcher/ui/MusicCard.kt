package com.civiclauncher.app.launcher.ui

import android.os.SystemClock
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.civiclauncher.app.container
import com.civiclauncher.app.diag.notif.NotificationCollector
import com.civiclauncher.app.launcher.data.VendorApps
import com.civiclauncher.app.launcher.media.MediaState
import com.civiclauncher.app.launcher.media.MusicSource
import com.civiclauncher.app.launcher.media.NowPlaying
import com.civiclauncher.app.ui.design.ButtonKind
import com.civiclauncher.app.ui.design.Civic
import com.civiclauncher.app.ui.design.CivicButton
import com.civiclauncher.app.ui.design.PhosphorIcons as P
import com.civiclauncher.app.ui.design.bevelBorder
import com.civiclauncher.app.ui.design.Tone
import com.civiclauncher.app.ui.design.color
import kotlinx.coroutines.delay

/*
 * Piezas compartidas por las tarjetas y pantallas de Civic: la tarjeta base de Inicio (HomeCard),
 * su encabezado (CardHeader) y utilidades de música (abrir la app que suena, ícono y nombre de la
 * fuente). Las tarjetas de Inicio en sí están en HomeWidgets.kt.
 */

/**
 * Abre la app de la música que suena. El Bluetooth abre directo la pantalla de música del
 * fabricante (su ícono principal abre los ajustes del Bluetooth).
 */
internal fun openMusicApp(ctx: android.content.Context, source: MusicSource, pkg: String) {
    val opened = when (source) {
        MusicSource.BLUETOOTH -> runCatching {
            ctx.startActivity(
                android.content.Intent()
                    .setClassName(VendorApps.BLUETOOTH, "${VendorApps.BLUETOOTH}.BtMusicActivity")
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        }.getOrDefault(false) || ctx.container.apps.launchPackage(VendorApps.BLUETOOTH)
        else -> ctx.container.apps.launchPackage(pkg)
    }
    if (!opened) Toast.makeText(ctx, "No está instalada en esta pantalla", Toast.LENGTH_SHORT).show()
}

internal fun sourceIcon(s: MusicSource) = when (s) {
    MusicSource.ANDROID_AUTO -> P.AndroidLogoFill
    MusicSource.BLUETOOTH -> P.BluetoothFill
    MusicSource.RADIO -> P.RadioFill
    else -> P.MusicNotes
}

/**
 * Tarjeta base de Inicio: esquinas grandes, degradado opaco y un borde fino que da el efecto
 * "cristal" del diseño sin transparencias reales.
 */
@Composable
fun HomeCard(
    modifier: Modifier = Modifier,
    background: Brush? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Civic.colors
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    // En vidrio el relleno es liso: el reflejo de arriba lo pone bevelBorder.
    val brush = background ?: if (c.isGlass) SolidColor(c.surface) else Brush.verticalGradient(listOf(lerp(c.surface, c.surfaceRaised, 0.6f), c.surface))
    Column(
        modifier
            .clip(RoundedCornerShape(Civic.radii.xl))
            .background(if (pressed) Brush.verticalGradient(listOf(c.surfacePressed, c.surfacePressed)) else brush)
            .bevelBorder(RoundedCornerShape(Civic.radii.xl))
            .let { if (onClick != null) it.clickable(source, indication = null, role = Role.Button, onClick = onClick) else it }
            .padding(20.dp),
        content = content,
    )
}

/**
 * Encabezado común de las tarjetas de Inicio: ícono, título y una cápsula de estado a la derecha.
 * Así Mi vehículo, Mapa y Música se leen igual.
 */
@Composable
fun CardHeader(icon: ImageVector, title: String, status: String?, tone: Tone) {
    // Etiqueta discreta (como los widgets de CarPlay): ícono chico y título en mayúsculas grises.
    // Lo importante de la tarjeta es su contenido, no su nombre.
    Row(Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Civic.colors.textDim, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(Civic.space.s))
        Text(
            title.uppercase(),
            style = Civic.type.caption.copy(letterSpacing = androidx.compose.ui.unit.TextUnit(0.10f, androidx.compose.ui.unit.TextUnitType.Em)),
            color = Civic.colors.textDim,
            maxLines = 1,
            softWrap = false,
        )
        Spacer(Modifier.weight(1f).widthIn(min = Civic.space.s))
        if (status != null) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(tone.color()))
            Spacer(Modifier.width(6.dp))
            Text(status, style = Civic.type.caption, color = Civic.colors.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 120.dp))
        }
    }
}

internal fun sourceLabel(s: MusicSource) = if (s == MusicSource.APP) "Sonando" else s.label

