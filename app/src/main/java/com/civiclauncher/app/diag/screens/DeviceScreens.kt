package com.civiclauncher.app.diag.screens

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.civiclauncher.app.container
import com.civiclauncher.app.diag.Row as InfoRowData
import com.civiclauncher.app.diag.Status
import com.civiclauncher.app.diag.collect.BluetoothCollector
import com.civiclauncher.app.diag.collect.BluetoothScanner
import com.civiclauncher.app.diag.collect.GpsCollector
import com.civiclauncher.app.diag.collect.GpsProbe
import com.civiclauncher.app.diag.util.formatDuration
import com.civiclauncher.app.ui.ActionButton
import com.civiclauncher.app.ui.ButtonKind
import com.civiclauncher.app.ui.Card
import com.civiclauncher.app.ui.CivicColors
import com.civiclauncher.app.ui.InfoCard
import com.civiclauncher.app.ui.SectionScaffold
import com.civiclauncher.app.ui.produceAsync
import com.civiclauncher.app.ui.rememberPermissionAction
import com.civiclauncher.app.ui.sectionCard
import com.civiclauncher.app.ui.startActivitySafe
import com.civiclauncher.app.ui.toast

@Composable
fun BluetoothScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val scanner = remember { BluetoothScanner(ctx.applicationContext, ctx.container.diagStore) }
    DisposableEffect(Unit) { onDispose { scanner.stop() } }
    val scan by scanner.state.collectAsStateWithLifecycle()
    var refresh by remember { mutableIntStateOf(0) }
    val info = produceAsync(refresh, scan.finished) { BluetoothCollector.collect(ctx) }

    val startScan = rememberPermissionAction(BluetoothCollector.scanPermissions()) {
        if (!BluetoothCollector.locationEnabled(ctx) && Build.VERSION.SDK_INT < 31) {
            ctx.toast("Activa la ubicación: Android la pide para escanear Bluetooth")
        }
        scanner.start(scope)
    }
    val enableLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh++ }
    val connectPerm = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyArray()
    val enableBt = rememberPermissionAction(connectPerm) {
        runCatching { enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
            .onFailure { ctx.toast("No se pudo encender desde aquí. Hazlo desde los ajustes de la pantalla.") }
    }

    SectionScaffold(
        title = "Bluetooth",
        subtitle = "La prueba clave: si aparece algún dispositivo, el Vgate iCar Pro 4.0 va a funcionar.",
        actions = {
            ActionButton(if (scan.running) "Escaneando…" else "Escanear 15 s", startScan, enabled = !scan.running)
            if (scan.running) ActionButton("Detener", { scanner.stop() }, kind = ButtonKind.Secondary)
            ActionButton("Encender Bluetooth", enableBt, kind = ButtonKind.Secondary)
            ActionButton("Ajustes de ubicación", { ctx.startActivitySafe(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }, kind = ButtonKind.Secondary)
            ActionButton("Actualizar", { refresh++ }, kind = ButtonKind.Secondary)
        },
    ) {
        sectionCard(info)
        item {
            val rows = buildList {
                add(
                    InfoRowData(
                        "Estado del escaneo",
                        when {
                            scan.running -> "Buscando… (${scan.devices.size})"
                            scan.finished -> "Terminado: ${scan.devices.size} encontrados"
                            else -> "Sin iniciar"
                        },
                        when {
                            scan.finished && scan.devices.isNotEmpty() -> Status.OK
                            scan.finished -> Status.BAD
                            else -> Status.INFO
                        },
                    ),
                )
                scan.error?.let { add(InfoRowData("Error", it, Status.BAD)) }
                scan.bleError?.let { add(InfoRowData("Error BLE", "código $it", Status.WARN)) }
                scan.devices.forEach { d ->
                    add(
                        InfoRowData(
                            d.name ?: "sin nombre",
                            "${d.kind} · ${d.rssi ?: "?"} dBm · ${d.address}" + if (d.looksLikeObd) " · POSIBLE OBD" else "",
                            if (d.looksLikeObd) Status.OK else Status.INFO,
                        ),
                    )
                }
            }
            InfoCard(
                title = "Dispositivos encontrados",
                rows = rows,
                notes = listOf(
                    "El Vgate iCar Pro 4.0 suele aparecer como \"IOS-Vlink\" o \"V-LINK\". No lo emparejes desde los ajustes: el launcher se conectará directo.",
                    "Si no aparece nada, ni siquiera tu celular, el Bluetooth de la pantalla no es accesible y usaremos un adaptador por USB.",
                ),
            )
        }
    }
}

@Composable
fun GpsScreen() {
    val ctx = LocalContext.current
    val probe = remember { GpsProbe(ctx.applicationContext, ctx.container.diagStore) }
    DisposableEffect(Unit) { onDispose { probe.stop() } }
    val s by probe.state.collectAsStateWithLifecycle()
    val info = produceAsync(s.running) { GpsCollector.collect(ctx) }
    val start = rememberPermissionAction(GpsCollector.permissions) { probe.start() }

    SectionScaffold(
        title = "GPS",
        subtitle = "Para el velocímetro de la Fase 2. Hazla con el carro al aire libre.",
        actions = {
            if (s.running) {
                ActionButton("Detener y guardar", { probe.stop() })
            } else {
                ActionButton("Iniciar prueba", start)
            }
            ActionButton("Ajustes de ubicación", { ctx.startActivitySafe(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }, kind = ButtonKind.Secondary)
        },
    ) {
        sectionCard(info)
        item {
            Card {
                Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                    BigStat("Velocidad", s.speedKmh?.let { "%.0f".format(it) } ?: "—", "km/h")
                    BigStat("Satélites", "${s.satsUsed}/${s.satsVisible}", "en uso / visibles")
                    BigStat("Primera ubicación", s.ttffMs?.let { formatDuration(it.toLong()) } ?: "—", "tiempo")
                    BigStat("Precisión", s.accuracyM?.let { "%.0f".format(it) } ?: "—", "metros")
                }
            }
        }
        item {
            InfoCard(
                rows = listOfNotNull(
                    InfoRowData("Estado", if (s.running) "Buscando satélites…" else "Detenido"),
                    InfoRowData("Lecturas", s.fixes.toString()),
                    s.lat?.let { InfoRowData("Posición (solo en pantalla)", "%.5f, %.5f".format(s.lat, s.lon)) },
                    s.error?.let { InfoRowData("Error", it, Status.BAD) },
                ),
                notes = listOf("La primera ubicación puede tardar 1 a 5 minutos si la antena está dentro del tablero. Tu posición no se guarda en el reporte."),
            )
        }
    }
}

@Composable
fun BigStat(label: String, value: String, unit: String) {
    Column {
        Text(label, color = CivicColors.TextDim, style = MaterialTheme.typography.bodyMedium)
        Text(value, fontSize = 40.sp, fontWeight = FontWeight.SemiBold, color = CivicColors.Text)
        Text(unit, color = CivicColors.TextDim, style = MaterialTheme.typography.bodyMedium)
    }
}
