package com.civiclauncher.app.diag.screens

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.civiclauncher.app.container
import com.civiclauncher.app.diag.collect.BluetoothCollector
import com.civiclauncher.app.diag.collect.BluetoothScanner
import com.civiclauncher.app.diag.collect.SppClient
import com.civiclauncher.app.diag.collect.SppServer
import com.civiclauncher.app.diag.collect.SppState
import com.civiclauncher.app.ui.ActionButton
import com.civiclauncher.app.ui.ButtonKind
import com.civiclauncher.app.ui.Card
import com.civiclauncher.app.ui.CivicColors
import com.civiclauncher.app.ui.InfoCard
import com.civiclauncher.app.ui.MonoText
import com.civiclauncher.app.ui.Note
import com.civiclauncher.app.ui.SectionScaffold
import com.civiclauncher.app.ui.rememberPermissionAction

@Composable
fun SppScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val scanner = remember { BluetoothScanner(ctx.applicationContext, ctx.container.diagStore) }
    val client = remember { SppClient(ctx.applicationContext, ctx.container.diagStore) }
    val server = remember { SppServer(ctx.applicationContext) }
    DisposableEffect(Unit) {
        onDispose {
            scanner.stop()
            client.close()
            server.close()
        }
    }
    val scan by scanner.state.collectAsStateWithLifecycle()
    val clientState by client.state.collectAsStateWithLifecycle()
    val serverState by server.state.collectAsStateWithLifecycle()

    val btPerms = BluetoothCollector.scanPermissions()
    val serverPerms = if (Build.VERSION.SDK_INT >= 31) {
        arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
    } else {
        emptyArray()
    }
    val startScan = rememberPermissionAction(btPerms) { scanner.start(scope, 15_000) }
    // Los celulares solo son visibles mientras su pantalla de Bluetooth está abierta.
    // Por eso pedimos al sistema que lo haga visible 2 minutos y luego empezamos a esperar.
    val discoverable = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        server.start(scope)
    }
    val startServer = rememberPermissionAction(serverPerms) {
        runCatching {
            discoverable.launch(
                Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                    .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120),
            )
        }.onFailure { server.start(scope) }
    }

    SectionScaffold(
        title = "Prueba SPP (OBD clásico)",
        subtitle = "Tu celular finge ser un adaptador OBD y la pantalla se conecta a él. Así sabemos si un adaptador Bluetooth clásico funcionaría, sin comprar nada.",
    ) {
        item {
            InfoCard(
                title = "Paso 1 · En tu celular",
                rows = emptyList(),
                notes = listOf(
                    "En el celular, abre esta sección y toca \"Esperar conexión\".",
                    "Acepta el aviso de \"hacer visible el dispositivo\": el celular será visible 2 minutos.",
                    "Deja el celular en esta pantalla mientras haces el paso 2.",
                ),
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionButton(
                    if (serverState.running) "Esperando…" else "Esperar conexión (celular)",
                    startServer,
                    enabled = !serverState.running,
                    kind = ButtonKind.Secondary,
                )
            }
        }
        if (serverState.log.isNotEmpty()) item { LogCard(serverState) }

        item {
            InfoCard(
                title = "Paso 2 · En la pantalla del carro",
                rows = emptyList(),
                notes = listOf(
                    "Con el celular ya esperando, toca \"Buscar\". Cuando aparezca tu celular, toca \"Conectar\".",
                    "Si no aparece, repite \"Buscar\": el escaneo dura 15 segundos.",
                    "Si el celular pide emparejar, acepta en ambos lados.",
                ),
            )
        }
        item {
            ActionButton(if (scan.running) "Buscando…" else "Buscar", startScan, enabled = !scan.running)
        }
        items(scan.devices, key = { it.address }) { d ->
            Card {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(d.name ?: "sin nombre", style = MaterialTheme.typography.titleMedium)
                        Text("${d.address} · ${d.kind} · ${d.rssi ?: "?"} dBm", color = CivicColors.TextDim)
                    }
                    ActionButton(
                        "Conectar",
                        {
                            scanner.stop()
                            client.connect(scope, d.address, d.name)
                        },
                        enabled = !clientState.running && d.kind == "clásico",
                    )
                }
            }
        }
        if (clientState.log.isNotEmpty()) item { LogCard(clientState) }
        item { Note("El resultado de la pantalla se guarda en el reporte.") }
    }
}

@Composable
private fun LogCard(s: SppState) {
    Card {
        Text(
            when (s.success) {
                true -> "Éxito"
                false -> "Sin éxito"
                null -> if (s.running) "En curso…" else "—"
            },
            style = MaterialTheme.typography.titleMedium,
            color = when (s.success) {
                true -> CivicColors.Ok
                false -> CivicColors.Bad
                null -> CivicColors.TextDim
            },
        )
        MonoText(s.log.joinToString("\n"))
    }
}
