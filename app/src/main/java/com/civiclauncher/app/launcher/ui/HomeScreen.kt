package com.civiclauncher.app.launcher.ui

import android.content.Intent
import android.provider.Settings
import android.text.format.DateFormat
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.civiclauncher.app.container
import com.civiclauncher.app.launcher.data.VendorApps
import com.civiclauncher.app.ui.design.AlertBanner
import com.civiclauncher.app.ui.design.ButtonKind
import com.civiclauncher.app.ui.design.Civic
import com.civiclauncher.app.ui.design.CivicButton
import com.civiclauncher.app.ui.design.CivicCard
import com.civiclauncher.app.ui.design.LaunchTile
import com.civiclauncher.app.ui.design.PhosphorIcons as P
import com.civiclauncher.app.ui.design.StatusPill
import com.civiclauncher.app.ui.design.Tone
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(onOpen: (Dest) -> Unit) {
    val ctx = LocalContext.current
    val c = ctx.container
    val gap = Civic.space.xl

    fun openVendor(pkg: String, name: String) {
        if (!c.apps.launchPackage(pkg)) {
            Toast.makeText(ctx, "$name no está instalada en esta pantalla", Toast.LENGTH_SHORT).show()
        }
    }

    Row(Modifier.fillMaxSize().padding(gap), horizontalArrangement = Arrangement.spacedBy(gap)) {
        // Izquierda: hora, fecha y velocidad.
        Column(Modifier.width(400.dp).fillMaxHeight()) {
            ClockBlock()
            Spacer(Modifier.weight(1f))
            TimeZoneWarning()
            SpeedCard()
        }
        // Derecha: accesos grandes, 2 columnas × 3 filas.
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(Civic.space.l)) {
            TileRow {
                LaunchTile(P.NavigationArrow, "Android Auto", Tone.Accent, { openVendor(VendorApps.ANDROID_AUTO, "AutoKit") }, tileModifier())
                LaunchTile(P.Radio, "Radio", Tone.Warn, { openVendor(VendorApps.RADIO, "La radio") }, tileModifier())
            }
            TileRow {
                LaunchTile(P.Phone, "Teléfono", Tone.Cool, { openVendor(VendorApps.BLUETOOTH, "Bluetooth") }, tileModifier())
                LaunchTile(P.Camera, "Cámara", Tone.Ok, { openVendor(VendorApps.CAMERA, "La cámara") }, tileModifier())
            }
            TileRow {
                LaunchTile(P.Gauge, "Vehículo", Tone.Bad, { onOpen(Dest.VEHICLE) }, tileModifier())
                LaunchTile(P.SquaresFour, "Apps", Tone.Neutral, { onOpen(Dest.APPS) }, tileModifier())
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.TileRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Civic.space.l), content = content)
}

private fun RowScope.tileModifier() = Modifier.weight(1f).fillMaxHeight()

@Composable
private fun ClockBlock() {
    val ctx = LocalContext.current
    val now by ctx.container.clock.now.collectAsStateWithLifecycle()
    val tz by ctx.container.clock.timeZone.collectAsStateWithLifecycle()
    val is24 = DateFormat.is24HourFormat(ctx)
    val es = remember { Locale("es", "MX") }
    val time = remember(now, tz, is24) { SimpleDateFormat(if (is24) "HH:mm" else "h:mm", es).format(Date(now)) }
    val amPm = remember(now, tz, is24) { if (is24) "" else SimpleDateFormat("a", es).format(Date(now)).replace(".", "").uppercase() }
    val date = remember(now, tz) {
        SimpleDateFormat("EEEE d 'de' MMMM", es).format(Date(now)).replaceFirstChar { it.titlecase(es) }
    }
    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(time, style = Civic.type.clock, color = Civic.colors.text)
            if (amPm.isNotEmpty()) {
                Spacer(Modifier.width(Civic.space.s))
                Text(amPm, style = Civic.type.title, color = Civic.colors.textDim, modifier = Modifier.padding(bottom = 18.dp))
            }
        }
        Text(date, style = Civic.type.title, color = Civic.colors.textDim)
    }
}

@Composable
private fun TimeZoneWarning() {
    val ctx = LocalContext.current
    val tz by ctx.container.clock.timeZone.collectAsStateWithLifecycle()
    if (tz != "Asia/Shanghai") return
    AlertBanner(
        P.Clock,
        "Zona horaria de China",
        "La hora puede estar mal. Toca para cambiarla.",
        Tone.Warn,
        onClick = { ctx.startActivity(Intent(Settings.ACTION_DATE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
    )
    Spacer(Modifier.height(Civic.space.l))
}

/** Velocímetro GPS. En la Fase 3 la velocidad del OBD tendrá prioridad. */
@Composable
private fun SpeedCard() {
    val ctx = LocalContext.current
    val c = ctx.container
    val gps by c.gps.speed.collectAsStateWithLifecycle()

    CivicCard(Modifier.fillMaxWidth()) {
        Text("VELOCIDAD", style = Civic.type.caption, color = Civic.colors.textFaint)
        when {
            !gps.permission -> {
                Spacer(Modifier.height(Civic.space.s))
                Text("Permite la ubicación para ver la velocidad por GPS.", style = Civic.type.bodySmall, color = Civic.colors.textDim)
                Spacer(Modifier.height(Civic.space.m))
                CivicButton("Permitir ubicación", {
                    ctx.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${ctx.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }, kind = ButtonKind.Secondary, icon = P.NavigationArrow)
            }
            !gps.enabled -> {
                Spacer(Modifier.height(Civic.space.s))
                Text("El GPS está apagado.", style = Civic.type.bodySmall, color = Civic.colors.textDim)
                Spacer(Modifier.height(Civic.space.m))
                CivicButton("Encender GPS", {
                    ctx.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }, kind = ButtonKind.Secondary)
            }
            else -> {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        gps.kmh?.let { "%.0f".format(it) } ?: "--",
                        style = Civic.type.gaugeValue.copy(fontSize = 72.sp, lineHeight = 80.sp),
                        color = if (gps.kmh != null) Civic.colors.text else Civic.colors.textFaint,
                    )
                    Spacer(Modifier.width(Civic.space.s))
                    Text("km/h", style = Civic.type.label, color = Civic.colors.textDim, modifier = Modifier.padding(bottom = 14.dp))
                }
                StatusPill(
                    when {
                        gps.kmh != null -> "GPS · ${gps.satsUsed} satélites"
                        gps.satsVisible > 0 -> "Buscando ubicación · ${gps.satsVisible} visibles"
                        else -> "Buscando satélites"
                    },
                    if (gps.kmh != null && gps.satsUsed >= 4) Tone.Ok else Tone.Warn,
                )
            }
        }
    }
}
