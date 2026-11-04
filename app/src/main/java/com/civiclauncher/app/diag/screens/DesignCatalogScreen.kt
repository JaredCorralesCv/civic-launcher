package com.civiclauncher.app.diag.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.civiclauncher.app.ui.design.AlertBanner
import com.civiclauncher.app.ui.design.ButtonKind
import com.civiclauncher.app.ui.design.Civic
import com.civiclauncher.app.ui.design.CivicButton
import com.civiclauncher.app.ui.design.CivicCard
import com.civiclauncher.app.ui.design.CivicDesignTheme
import com.civiclauncher.app.ui.design.CivicIconButton
import com.civiclauncher.app.ui.design.DockItem
import com.civiclauncher.app.ui.design.Gauge
import com.civiclauncher.app.ui.design.LaunchTile
import com.civiclauncher.app.ui.design.LinearMeter
import com.civiclauncher.app.ui.design.PhosphorIcons as P
import com.civiclauncher.app.ui.design.SectionHeader
import com.civiclauncher.app.ui.design.StatusPill
import com.civiclauncher.app.ui.design.Tone
import com.civiclauncher.app.ui.design.ValueCard
import com.civiclauncher.obd.ElmSession
import com.civiclauncher.obd.ObdResult
import com.civiclauncher.obd.Pids
import com.civiclauncher.obd.SimulatedElm
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Catálogo del sistema de diseño (entregable de la Fase 1).
 * Los medidores se alimentan del simulador OBD del módulo :obd, igual que lo harán con el carro.
 */
@Composable
fun DesignCatalogScreen() {
    var night by rememberSaveable { mutableStateOf(true) }
    CivicDesignTheme(night = night) {
        CatalogContent(night = night, onToggle = { night = !night })
    }
}

@Composable
private fun CatalogContent(night: Boolean, onToggle: () -> Unit) {
    val rpm = remember { mutableFloatStateOf(0f) }
    val speed = remember { mutableFloatStateOf(0f) }
    val coolant = remember { mutableFloatStateOf(0f) }
    val load = remember { mutableFloatStateOf(0f) }
    var volts by remember { mutableStateOf("—") }

    LaunchedEffect(Unit) {
        val session = ElmSession(SimulatedElm(latencyMs = 25))
        session.start()
        while (isActive) {
            (session.query(Pids.RPM) as? ObdResult.Value)?.let { rpm.floatValue = it.value.toFloat() }
            (session.query(Pids.SPEED) as? ObdResult.Value)?.let { speed.floatValue = it.value.toFloat() }
            (session.query(Pids.COOLANT_TEMP) as? ObdResult.Value)?.let { coolant.floatValue = it.value.toFloat() }
            (session.query(Pids.ENGINE_LOAD) as? ObdResult.Value)?.let { load.floatValue = it.value.toFloat() }
            (session.batteryVoltage() as? ObdResult.Value)?.let { volts = "%.1f".format(it.value) }
            delay(120)
        }
    }

    val gap = Civic.space.l
    LazyColumn(
        Modifier.fillMaxSize().background(Civic.colors.background),
        contentPadding = PaddingValues(horizontal = Civic.space.xl, vertical = Civic.space.xl),
        verticalArrangement = Arrangement.spacedBy(Civic.space.xl),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Catálogo de diseño", style = Civic.type.headline, color = Civic.colors.text)
                    Text(
                        "Inter · Phosphor · colores opacos para la Mali-450. Datos en vivo del simulador OBD.",
                        style = Civic.type.bodySmall,
                        color = Civic.colors.textDim,
                    )
                }
                CivicButton(
                    if (night) "Ver modo día" else "Ver modo noche",
                    onToggle,
                    icon = if (night) P.Sun else P.Moon,
                    kind = ButtonKind.Secondary,
                )
            }
        }

        item {
            SectionHeader("Medidores (simulador)")
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                CivicCard {
                    Gauge({ rpm.floatValue }, 0f, 7000f, "Motor", "RPM", warnAt = 6000f, dangerAt = 6800f, majorTicks = 7)
                }
                CivicCard {
                    Gauge({ speed.floatValue }, 0f, 180f, "Velocidad", "km/h")
                }
                CivicCard {
                    Gauge({ coolant.floatValue }, 40f, 120f, "Temperatura", "°C", warnAt = 100f, dangerAt = 108f, majorTicks = 4)
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                ValueCard(P.CarBattery, "Batería", volts, "V", tone = Tone.Ok)
                ValueCard(
                    P.Thermometer, "Motor", coolant.floatValue.toInt().toString(), "°C",
                    tone = if (coolant.floatValue > 100) Tone.Bad else Tone.Neutral,
                )
                CivicCard(Modifier.width(280.dp)) {
                    Text("Carga del motor", style = Civic.type.caption, color = Civic.colors.textDim)
                    Spacer(Modifier.height(Civic.space.s))
                    Text("${load.floatValue.toInt()} %", style = Civic.type.number, color = Civic.colors.text)
                    Spacer(Modifier.height(Civic.space.s))
                    LinearMeter(load.floatValue / 100f, Tone.Accent)
                }
            }
        }

        item {
            SectionHeader("Accesos")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(gap)) {
                LaunchTile(P.NavigationArrow, "Android Auto", Tone.Accent, {})
                LaunchTile(P.Radio, "Radio", Tone.Warn, {})
                LaunchTile(P.Bluetooth, "Bluetooth", Tone.Cool, {})
                LaunchTile(P.Camera, "Cámara", Tone.Ok, {})
                LaunchTile(P.Gauge, "Vehículo", Tone.Bad, {})
                LaunchTile(P.SquaresFour, "Apps", Tone.Neutral, {})
            }
        }

        item {
            SectionHeader("Dock, botones y estados")
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                var sel by remember { mutableIntStateOf(0) }
                Column(
                    Modifier
                        .width(Civic.sizes.dockWidth)
                        .clip(RoundedCornerShape(Civic.radii.l))
                        .background(Civic.colors.surface)
                        .padding(vertical = Civic.space.s),
                ) {
                    DockItem(P.House, P.HouseFill, "Inicio", sel == 0, { sel = 0 })
                    DockItem(P.Gauge, P.GaugeFill, "Vehículo", sel == 1, { sel = 1 })
                    DockItem(P.SquaresFour, P.SquaresFourFill, "Apps", sel == 2, { sel = 2 })
                    DockItem(P.GearSix, P.GearSixFill, "Ajustes", sel == 3, { sel = 3 })
                }
                Column(verticalArrangement = Arrangement.spacedBy(Civic.space.m), modifier = Modifier.weight(1f)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Civic.space.m), verticalAlignment = Alignment.CenterVertically) {
                        CivicButton("Conectar OBD", {}, icon = P.Bluetooth)
                        CivicButton("Más tarde", {}, kind = ButtonKind.Secondary)
                        CivicButton("Borrar códigos", {}, kind = ButtonKind.Danger, icon = P.Warning)
                        CivicIconButton(P.Power, "Apagar", {})
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(Civic.space.s)) {
                        StatusPill("OBD conectado", Tone.Ok)
                        StatusPill("GPS: 5 satélites", Tone.Cool)
                        StatusPill("Reconectando…", Tone.Warn)
                        StatusPill("Sin adaptador", Tone.Bad)
                    }
                    AlertBanner(P.Thermometer, "Motor caliente", "108 °C. Detente en un lugar seguro y deja enfriar el motor.", Tone.Bad)
                    AlertBanner(P.CarBattery, "Voltaje bajo", "11.8 V con el motor encendido. Revisa el alternador.", Tone.Warn)
                }
            }
        }

        item {
            SectionHeader("Tipografía (Inter)")
            CivicCard(Modifier.fillMaxWidth()) {
                Text("12:45", style = Civic.type.clock, color = Civic.colors.text)
                Text("Pantalla 56 · Display", style = Civic.type.display, color = Civic.colors.text)
                Text("Encabezado 32 · Headline", style = Civic.type.headline, color = Civic.colors.text)
                Text("Título 22 · Title", style = Civic.type.title, color = Civic.colors.text)
                Text("Texto 18 · Body: legible de un vistazo mientras el carro está detenido.", style = Civic.type.body, color = Civic.colors.text)
                Text("Etiqueta 16 · Label", style = Civic.type.label, color = Civic.colors.textDim)
                Text("ETIQUETA PEQUEÑA 13 · CAPTION", style = Civic.type.caption, color = Civic.colors.textFaint)
                Text("1234567890 (números de ancho fijo)", style = Civic.type.number, color = Civic.colors.accent)
            }
        }

        item {
            SectionHeader("Colores")
            val c = Civic.colors
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Civic.space.m)) {
                listOf(
                    "Fondo" to c.background, "Superficie" to c.surface, "Elevada" to c.surfaceRaised, "Línea" to c.line,
                    "Texto" to c.text, "Texto 2" to c.textDim, "Acento" to c.accent, "Bien" to c.ok,
                    "Aviso" to c.warn, "Peligro" to c.bad, "Frío" to c.cool,
                ).forEach { (name, color) -> Swatch(name, color) }
            }
        }

        item {
            SectionHeader("Íconos (Phosphor)")
            val icons: List<Pair<String, ImageVector>> = listOf(
                "house" to P.House, "gauge" to P.Gauge, "engine" to P.Engine, "thermometer" to P.Thermometer,
                "car-battery" to P.CarBattery, "gas-pump" to P.GasPump, "steering-wheel" to P.SteeringWheel,
                "car-profile" to P.CarProfile, "radio" to P.Radio, "bluetooth" to P.Bluetooth, "camera" to P.Camera,
                "map-trifold" to P.MapTrifold, "phone" to P.Phone, "music-notes" to P.MusicNotes, "warning" to P.Warning,
                "wrench" to P.Wrench, "timer" to P.Timer, "drop" to P.Drop, "lightning" to P.Lightning, "wifi-high" to P.WifiHigh,
            )
            CivicCard(Modifier.fillMaxWidth()) {
                icons.chunked(7).forEach { row ->
                    Row(Modifier.fillMaxWidth().padding(vertical = Civic.space.s)) {
                        row.forEach { (name, icon) ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                                Icon(icon, name, tint = Civic.colors.text, modifier = Modifier.size(Civic.sizes.icon))
                                Spacer(Modifier.height(Civic.space.xs))
                                Text(
                                    name,
                                    style = Civic.type.caption,
                                    color = Civic.colors.textFaint,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        // Rellena la última fila para que los íconos queden alineados en columnas.
                        repeat(7 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Swatch(name: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // Borde fino para que el color "Fondo" se distinga del fondo real.
        Box(
            Modifier
                .size(width = 88.dp, height = 56.dp)
                .clip(RoundedCornerShape(Civic.radii.m))
                .background(color)
                .border(1.dp, Civic.colors.line, RoundedCornerShape(Civic.radii.m)),
        )
        Spacer(Modifier.height(Civic.space.xs))
        Text(name, style = Civic.type.caption, color = Civic.colors.textDim)
    }
}
