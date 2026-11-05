package com.civiclauncher.app.launcher.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.civiclauncher.app.container
import com.civiclauncher.app.launcher.obd.LiveDerived
import com.civiclauncher.app.launcher.obd.ObdMode
import com.civiclauncher.app.ui.design.ButtonKind
import com.civiclauncher.app.ui.design.Civic
import com.civiclauncher.app.ui.design.CivicButton
import com.civiclauncher.app.ui.design.CivicCard
import com.civiclauncher.app.ui.design.Gauge
import com.civiclauncher.app.ui.design.PhosphorIcons as P
import com.civiclauncher.app.ui.design.Tone
import com.civiclauncher.app.ui.design.bevelBorder
import com.civiclauncher.app.ui.design.color
import com.civiclauncher.obd.AlertLevel
import com.civiclauncher.obd.ClearResult
import com.civiclauncher.obd.Diagnostics
import com.civiclauncher.obd.DtcCatalog
import com.civiclauncher.obd.DtcSeverity
import com.civiclauncher.obd.EngineState
import com.civiclauncher.obd.PidGroup
import com.civiclauncher.obd.PidSpec
import com.civiclauncher.obd.Pids
import com.civiclauncher.obd.TripStats
import com.civiclauncher.obd.VehicleAlert
import com.civiclauncher.obd.VehicleData
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class VehicleTab(val label: String) { LIVE("En vivo"), TRIP("Viaje"), DIAG("Diagnóstico"), SENSORS("Sensores") }

/**
 * Todo lo que dice el OBD, en cuatro pestañas:
 *  - En vivo: relojes de RPM, velocidad y temperatura; batería, consumo, marcha y acelerador.
 *  - Viaje: distancia, tiempo, promedios, gasolina usada y velocidad máxima.
 *  - Diagnóstico: foco Check Engine, códigos de falla explicados (y borrarlos), VIN.
 *  - Sensores: cada dato que tu carro reporta, agrupado.
 * Rendimiento: los datos llegan como State y cada pieza lee solo lo suyo.
 */
@Composable
fun VehicleScreen() {
    val repo = LocalContext.current.container.obd
    val config by repo.config.collectAsStateWithLifecycle()
    val state by repo.state.collectAsStateWithLifecycle()
    val data = repo.data.collectAsStateWithLifecycle()
    val live by repo.live.collectAsStateWithLifecycle()
    var picker by remember { mutableStateOf(false) }
    var tab by rememberSaveable { mutableStateOf(VehicleTab.LIVE) }

    Column(Modifier.fillMaxSize().padding(horizontal = PAGE_GUTTER, vertical = Civic.space.m), verticalArrangement = Arrangement.spacedBy(Civic.space.m)) {
        // Encabezado: título · pestañas · estado y adaptador.
        Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Vehículo", style = Civic.type.headline.copy(fontSize = 28.sp), color = Civic.colors.text)
            Spacer(Modifier.width(Civic.space.l))
            if (config.mode != ObdMode.NONE) {
                GlassTabs(VehicleTab.entries, tab, { it.label }, { tab = it }, Modifier.weight(1f).widthIn(max = 520.dp))
            } else {
                Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.width(Civic.space.m))
            ObdStatusPill(state, config)
            Spacer(Modifier.width(Civic.space.s))
            CivicButton("Adaptador", { picker = true }, kind = ButtonKind.Secondary, icon = P.Bluetooth, compact = true)
        }

        if (config.mode == ObdMode.NONE) {
            SetupCard(onChoose = { picker = true }, onSimulator = { repo.useSimulator() })
        } else {
            ConnectionBanner(state, data.value)
            live.alerts.firstOrNull()?.let { AlertStrip(it) }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    VehicleTab.LIVE -> LiveTab(data, live)
                    VehicleTab.TRIP -> TripTab()
                    VehicleTab.DIAG -> DiagTab(state)
                    VehicleTab.SENSORS -> SensorsTab(data.value)
                }
            }
        }
    }

    if (picker) {
        AdapterPickerDialog(
            onDismiss = { picker = false },
            onPick = { repo.useBluetooth(it.address, it.name); picker = false },
            onSimulator = { repo.useSimulator(); picker = false },
        )
    }
}

// ------------------------------------------------------------------ Piezas comunes

/** Pestañas en una cápsula de vidrio (como el selector de fuente de la música). */
@Composable
private fun <T> GlassTabs(items: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .height(46.dp)
            .clip(shape)
            .background(Civic.colors.surface)
            .bevelBorder(shape)
            .padding(4.dp),
    ) {
        items.forEach { item ->
            val source = remember { MutableInteractionSource() }
            val pressed by source.collectIsPressedAsState()
            val on = item == selected
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(shape)
                    .background(
                        when {
                            pressed -> Civic.colors.surfacePressed
                            on -> Civic.colors.surfacePressed
                            else -> Color.Transparent
                        },
                    )
                    .let { if (on) it.bevelBorder(shape) else it }
                    .clickable(source, indication = null, role = Role.Tab) { onSelect(item) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(item),
                    style = Civic.type.label.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal),
                    color = if (on) Civic.colors.text else Civic.colors.textDim,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Tarjeta de vidrio con etiqueta arriba (mismo estilo que las de Inicio). */
@Composable
private fun Panel(modifier: Modifier, padding: PaddingValues = PaddingValues(16.dp), content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(Civic.radii.l)
    Column(
        modifier
            .clip(shape)
            .background(Civic.colors.surface)
            .bevelBorder(shape)
            .padding(padding),
        content = content,
    )
}

@Composable
private fun Label(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = Civic.type.caption.copy(letterSpacing = 0.10.em), color = Civic.colors.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
}

/** Un número grande con su unidad. */
@Composable
private fun BigValue(value: String, unit: String, color: Color = Civic.colors.text, size: Int = 30) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(value, style = Civic.type.number.copy(fontSize = size.sp, lineHeight = (size + 4).sp), color = color, maxLines = 1)
        if (unit.isNotEmpty()) {
            Spacer(Modifier.width(4.dp))
            Text(unit, style = Civic.type.gaugeUnit, color = Civic.colors.textDim, modifier = Modifier.padding(bottom = (size / 6).dp))
        }
    }
}

/** Mientras no hay datos (conectando, carro apagado…), qué está pasando, en una línea. */
@Composable
private fun ConnectionBanner(state: EngineState, data: VehicleData) {
    val (text, tone) = when (state) {
        is EngineState.CarOff -> "Carro apagado: el adaptador responde, pero el carro no. Pon la llave en ON." +
            (data.battery?.value?.let { " Batería: %.1f V".format(it) } ?: "") to Tone.Cool
        is EngineState.Reconnecting -> "Sin conexión con el adaptador (${state.reason}). Reintento en ${state.retryInMs / 1000} s." to Tone.Bad
        is EngineState.Detecting -> "Hablando con el carro… la primera vez puede tardar hasta 10 segundos." to Tone.Warn
        is EngineState.Connecting -> "Conectando con el adaptador…" to Tone.Warn
        else -> return
    }
    Strip(text, tone)
}

@Composable
private fun AlertStrip(alert: VehicleAlert) {
    Strip(
        "${alert.title} · ${alert.detail}",
        when (alert.level) {
            AlertLevel.DANGER -> Tone.Bad
            AlertLevel.WARN -> Tone.Warn
            AlertLevel.INFO -> Tone.Cool
        },
    )
}

@Composable
private fun Strip(text: String, tone: Tone) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(shape)
            .background(Civic.colors.surface)
            .bevelBorder(shape)
            .padding(horizontal = Civic.space.l),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(tone.color()))
        Spacer(Modifier.width(Civic.space.m))
        Text(text, style = Civic.type.bodySmall, color = Civic.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ------------------------------------------------------------------ En vivo

@Composable
private fun LiveTab(data: State<VehicleData>, live: LiveDerived) {
    fun value(spec: PidSpec, default: Float): () -> Float = { data.value[spec].fresh()?.value?.toFloat() ?: default }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Civic.space.m)) {
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(Civic.space.m)) {
            GaugePanel { Gauge(value(Pids.RPM, 0f), 0f, 7000f, "Motor", "RPM", Modifier.align(Alignment.CenterHorizontally), size = 196.dp, warnAt = 6000f, dangerAt = 6800f, majorTicks = 7) }
            GaugePanel { Gauge(value(Pids.SPEED, 0f), 0f, 180f, "Velocidad", "km/h", Modifier.align(Alignment.CenterHorizontally), size = 196.dp) }
            GaugePanel { Gauge(value(Pids.COOLANT_TEMP, 40f), 40f, 120f, "Temperatura", "°C", Modifier.align(Alignment.CenterHorizontally), size = 196.dp, warnAt = 100f, dangerAt = 108f, majorTicks = 4) }
        }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Civic.space.m)) {
            val battery = data.value.battery.fresh(30_000)?.value
            val rpm = data.value[Pids.RPM].fresh()?.value
            Metric(
                "Batería",
                battery?.let { "%.1f".format(it) } ?: "--", "V",
                when {
                    battery == null -> Civic.colors.textFaint
                    rpm != null && rpm > 500 && battery < 13.0 -> Civic.colors.warn
                    battery < 11.9 -> Civic.colors.bad
                    else -> Civic.colors.text
                },
                Modifier.weight(1f).fillMaxHeight(),
                if (rpm != null && rpm > 500) "Cargando" else "Motor apagado",
            )
            // Consumo: km/L en movimiento; detenido, litros por hora (ralentí).
            val kml = live.kmPerLiter
            val lph = live.litersPerHour
            Metric(
                "Consumo",
                when {
                    kml != null -> if (kml >= 99) "∞" else "%.1f".format(kml)
                    lph != null -> "%.1f".format(lph)
                    else -> "--"
                },
                if (kml != null) "km/L" else if (lph != null) "L/h" else "",
                Civic.colors.text,
                Modifier.weight(1f).fillMaxHeight(),
                if (kml != null && kml >= 99) "Sin gastar (motor frenando)" else if (kml != null) "Al instante" else "En ralentí",
            )
            Metric(
                "Marcha",
                live.gear?.toString() ?: if ((data.value[Pids.SPEED].fresh()?.value ?: 0.0) < 3) "P/N" else "–",
                "",
                Civic.colors.text,
                Modifier.weight(1f).fillMaxHeight(),
                "Estimada",
            )
            val throttle = data.value[Pids.THROTTLE].fresh()?.value
            Metric(
                "Acelerador",
                throttle?.let { "%.0f".format(it) } ?: "--", "%",
                Civic.colors.text,
                Modifier.weight(1f).fillMaxHeight(),
                Pids.fuelSystemText(data.value[Pids.FUEL_SYSTEM].fresh(30_000)?.value) ?: "Posición",
            )
        }
    }
}

@Composable
private fun RowScope.GaugePanel(content: @Composable ColumnScope.() -> Unit) {
    Panel(Modifier.weight(1f).fillMaxHeight(), PaddingValues(Civic.space.m)) {
        Spacer(Modifier.weight(1f))
        content()
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun Metric(label: String, value: String, unit: String, color: Color, modifier: Modifier, hint: String) {
    Panel(modifier) {
        Label(label)
        Spacer(Modifier.height(4.dp))
        BigValue(value, unit, color)
        Text(hint, style = Civic.type.caption, color = Civic.colors.textFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ------------------------------------------------------------------ Viaje

@Composable
private fun TripTab() {
    val repo = LocalContext.current.container.obd
    val trip by repo.tripStats.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Civic.space.m)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (trip.startedAtMs == 0L) "Viaje nuevo: empieza al encender el motor"
                else "Viaje desde las ${SimpleDateFormat("h:mm a", Locale("es", "MX")).format(Date(trip.startedAtMs))} · se reinicia solo tras 30 min apagado",
                style = Civic.type.bodySmall,
                color = Civic.colors.textDim,
                modifier = Modifier.weight(1f),
            )
            CivicButton("Nuevo viaje", { repo.resetTrip() }, kind = ButtonKind.Secondary, icon = P.ArrowsClockwise, compact = true)
        }
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(Civic.space.m)) {
            TripStat("Distancia", "%.1f".format(trip.distanceKm), "km", Modifier.weight(1f).fillMaxHeight())
            TripStat("Tiempo", duration(trip.drivingMs), "", Modifier.weight(1f).fillMaxHeight(), "En ralentí: ${duration(trip.idleMs)}")
            TripStat("Rendimiento", trip.avgKmPerLiter?.let { "%.1f".format(it) } ?: "--", "km/L", Modifier.weight(1f).fillMaxHeight(), "Promedio del viaje")
        }
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(Civic.space.m)) {
            TripStat("Velocidad promedio", trip.avgSpeedKmh?.let { "%.0f".format(it) } ?: "--", "km/h", Modifier.weight(1f).fillMaxHeight())
            TripStat("Velocidad máxima", "%.0f".format(trip.maxSpeedKmh), "km/h", Modifier.weight(1f).fillMaxHeight())
            TripStat("Gasolina usada", "%.2f".format(trip.fuelLiters), "L", Modifier.weight(1f).fillMaxHeight(), "Estimada por el motor")
        }
    }
}

@Composable
private fun TripStat(label: String, value: String, unit: String, modifier: Modifier, hint: String? = null) {
    Panel(modifier, PaddingValues(20.dp)) {
        Label(label)
        Spacer(Modifier.weight(1f))
        BigValue(value, unit, size = 40)
        if (hint != null) Text(hint, style = Civic.type.caption, color = Civic.colors.textFaint, maxLines = 1)
    }
}

private fun duration(ms: Long): String {
    val min = ms / 60_000
    return if (min >= 60) "%d h %02d min".format(min / 60, min % 60) else "$min min"
}

// ------------------------------------------------------------------ Diagnóstico

@Composable
private fun DiagTab(state: EngineState) {
    val ctx = LocalContext.current
    val repo = ctx.container.obd
    val diag by repo.diagnostics.collectAsStateWithLifecycle()
    val data by repo.data.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    val connected = state is EngineState.Connected

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(Civic.space.m)) {
        // Izquierda: el foco y las acciones.
        Panel(Modifier.weight(0.9f).fillMaxHeight(), PaddingValues(20.dp)) {
            Label("Check Engine")
            Spacer(Modifier.weight(1f))
            val codes = diag.stored.size
            val (title, tone) = when {
                diag.checkedAtMs == 0L -> "Sin revisar" to Tone.Neutral
                diag.milOn -> "Foco encendido" to Tone.Bad
                codes > 0 -> "Hay códigos guardados" to Tone.Warn
                diag.pending.isNotEmpty() -> "Fallas en observación" to Tone.Warn
                else -> "Todo bien" to Tone.Ok
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(14.dp).clip(CircleShape).background(tone.color()))
                Spacer(Modifier.width(Civic.space.m))
                Text(title, style = Civic.type.headline.copy(fontSize = 28.sp), color = Civic.colors.text)
            }
            Spacer(Modifier.height(Civic.space.s))
            Text(
                when {
                    diag.checkedAtMs == 0L -> "Se revisa al conectar con el carro."
                    codes == 0 && diag.pending.isEmpty() -> "La computadora del motor no reporta fallas."
                    else -> "$codes guardado(s) · ${diag.pending.size} pendiente(s). Toca un código para ver qué significa."
                },
                style = Civic.type.bodySmall,
                color = Civic.colors.textDim,
            )
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(Civic.space.s)) {
                CivicButton("Revisar", { repo.refreshCodes() }, kind = ButtonKind.Secondary, icon = P.ArrowsClockwise, compact = true, enabled = connected)
                CivicButton("Borrar códigos", { confirm = true }, kind = ButtonKind.Danger, icon = P.Wrench, compact = true, enabled = connected && (codes > 0 || diag.pending.isNotEmpty() || diag.milOn))
            }
            diag.lastClear?.let {
                Spacer(Modifier.height(Civic.space.s))
                Text(
                    if (it == ClearResult.OK) "Códigos borrados." else "El carro no aceptó el borrado. Prueba con la llave en ON y el motor apagado.",
                    style = Civic.type.caption,
                    color = if (it == ClearResult.OK) Civic.colors.ok else Civic.colors.warn,
                )
            }
        }
        // Derecha: los códigos explicados y los datos del carro.
        Column(Modifier.weight(1.1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(Civic.space.m)) {
            Panel(Modifier.weight(1f).fillMaxWidth()) {
                Label("Códigos de falla")
                Spacer(Modifier.height(Civic.space.s))
                val all = diag.stored.map { it to "Guardado" } + diag.pending.filter { it !in diag.stored }.map { it to "Pendiente" }
                if (all.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(if (diag.checkedAtMs == 0L) "Aún no se revisan" else "Sin códigos", style = Civic.type.body, color = Civic.colors.textDim)
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(Civic.space.s)) {
                        items(all, key = { it.first }) { (code, kind) -> DtcRow(code, kind) }
                    }
                }
            }
            Panel(Modifier.fillMaxWidth()) {
                InfoRow("VIN", diag.vin ?: "--")
                InfoRow("Protocolo", diag.protocol ?: "--")
                val sinceClear = data[Pids.DIST_SINCE_CLEAR].fresh(600_000)?.value
                InfoRow("Desde el último borrado", sinceClear?.let { "%,d km".format(it.toInt()) } ?: "--")
            }
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("¿Borrar los códigos de falla?") },
            text = {
                Text(
                    "Se apaga el foco Check Engine, pero si la falla sigue, el foco volverá a encender. " +
                        "También se reinician las revisiones de emisiones (el carro tarda unos días en completarlas: " +
                        "no lo lleves a verificar justo después). Hazlo con la llave en ON y el motor apagado.",
                )
            },
            confirmButton = {
                TextButton({
                    confirm = false
                    scope.launch {
                        val ok = repo.clearCodes()
                        Toast.makeText(ctx, if (ok) "Códigos borrados" else "El carro no aceptó el borrado", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Borrar") }
            },
            dismissButton = { TextButton({ confirm = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun DtcRow(code: String, kind: String) {
    val info = remember(code) { DtcCatalog.describe(code) }
    var open by remember { mutableStateOf(false) }
    val tone = when (info.severity) {
        DtcSeverity.SERIOUS -> Tone.Bad
        DtcSeverity.WARN -> Tone.Warn
        DtcSeverity.INFO -> Tone.Cool
    }
    val shape = RoundedCornerShape(Civic.radii.m)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Civic.colors.surfaceRaised)
            .clickable { open = !open }
            .padding(horizontal = Civic.space.m, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(tone.color()))
            Spacer(Modifier.width(Civic.space.s))
            Text(info.code, style = Civic.type.label.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"), color = Civic.colors.text)
            Spacer(Modifier.width(Civic.space.s))
            Text(info.description, style = Civic.type.bodySmall, color = Civic.colors.text, maxLines = if (open) 3 else 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(Civic.space.s))
            Text(kind, style = Civic.type.caption, color = Civic.colors.textDim)
        }
        if (open) {
            Spacer(Modifier.height(4.dp))
            Text(info.advice, style = Civic.type.caption, color = Civic.colors.textDim)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, style = Civic.type.bodySmall, color = Civic.colors.textDim, modifier = Modifier.weight(1f))
        Text(value, style = Civic.type.bodySmall.copy(fontFeatureSettings = "tnum"), color = Civic.colors.text, maxLines = 1)
    }
}

// ------------------------------------------------------------------ Sensores

/** Todo lo que el carro reporta, agrupado. Solo aparecen los datos que tu carro sí tiene. */
@Composable
private fun SensorsTab(data: VehicleData) {
    val groups = remember(data) {
        PidGroup.entries.mapNotNull { g ->
            val items = Pids.all.filter { it.group == g && it != Pids.MONITOR_STATUS }
                .mapNotNull { spec -> data[spec].fresh(60_000)?.let { spec to it.value } }
            items.takeIf { it.isNotEmpty() }?.let { g to it }
        }
    }
    if (groups.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Esperando datos del carro…", style = Civic.type.body, color = Civic.colors.textDim)
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        horizontalArrangement = Arrangement.spacedBy(Civic.space.s),
        verticalArrangement = Arrangement.spacedBy(Civic.space.s),
        modifier = Modifier.fillMaxSize(),
    ) {
        groups.forEach { (group, items) ->
            item(span = { GridItemSpan(maxLineSpan) }, key = group.name) {
                Label(group.label, Modifier.padding(top = Civic.space.s, start = 4.dp))
            }
            items.forEach { (spec, v) ->
                item(key = spec.pid) { SensorTile(spec, v) }
            }
            if (group == PidGroup.ELECTRIC) {
                data.battery.fresh(60_000)?.let { b ->
                    item(key = "atrv") { SensorTile("Batería (conector OBD)", "%.1f".format(b.value), "V") }
                }
            }
        }
    }
}

@Composable
private fun SensorTile(spec: PidSpec, value: Double) {
    val text = if (spec == Pids.FUEL_SYSTEM) Pids.fuelSystemText(value) ?: "--" else spec.format(value)
    SensorTile(spec.name, text, if (spec == Pids.FUEL_SYSTEM) "" else spec.unit)
}

@Composable
private fun SensorTile(name: String, value: String, unit: String) {
    Panel(Modifier.fillMaxWidth().height(92.dp), PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
        Text(name, style = Civic.type.caption, color = Civic.colors.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = Civic.type.title.copy(fontSize = if (value.length > 12) 16.sp else 24.sp, fontFeatureSettings = "tnum"), color = Civic.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (unit.isNotEmpty()) {
                Spacer(Modifier.width(4.dp))
                Text(unit, style = Civic.type.caption, color = Civic.colors.textDim, modifier = Modifier.padding(bottom = 3.dp))
            }
        }
    }
}

// ------------------------------------------------------------------ Sin adaptador

@Composable
private fun SetupCard(onChoose: () -> Unit, onSimulator: () -> Unit) {
    CivicCard(Modifier.fillMaxWidth()) {
        Text("Conecta tu adaptador OBD", style = Civic.type.title, color = Civic.colors.text)
        Spacer(Modifier.height(Civic.space.s))
        Text(
            "Con tu Vgate iCar (Bluetooth 3.0) verás aquí RPM, velocidad, temperatura, consumo, la computadora " +
                "de viaje, el foco Check Engine con sus códigos explicados y todos los sensores de tu Civic. " +
                "Mientras llega, puedes probar con el simulador.",
            style = Civic.type.body,
            color = Civic.colors.textDim,
        )
        Spacer(Modifier.height(Civic.space.l))
        Row(horizontalArrangement = Arrangement.spacedBy(Civic.space.m)) {
            CivicButton("Buscar adaptador", onChoose, icon = P.Bluetooth)
            CivicButton("Usar simulador (demo)", onSimulator, kind = ButtonKind.Secondary, icon = P.Cpu)
        }
    }
}
