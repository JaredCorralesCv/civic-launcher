package com.civiclauncher.app.launcher.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.civiclauncher.app.container
import com.civiclauncher.app.launcher.obd.AdapterCandidate
import com.civiclauncher.app.launcher.obd.AdapterScanner
import com.civiclauncher.app.launcher.obd.ObdConfig
import com.civiclauncher.app.launcher.obd.ObdMode
import com.civiclauncher.app.ui.design.ButtonKind
import com.civiclauncher.app.ui.design.Civic
import com.civiclauncher.app.ui.design.CivicButton
import com.civiclauncher.app.ui.design.PhosphorIcons as P
import com.civiclauncher.app.ui.design.StatusPill
import com.civiclauncher.app.ui.design.Tone
import com.civiclauncher.app.ui.rememberPermissionAction
import com.civiclauncher.obd.EngineState
import com.civiclauncher.obd.Reading

/**
 * Velocidad a mostrar en toda la app: la del OBD si está conectado y es reciente (más precisa,
 * funciona en túneles), si no la del GPS. Devuelve el valor y si vino del OBD.
 */
@Composable
fun rememberSpeed(): Pair<Double?, Boolean> {
    val c = LocalContext.current.container
    val gps = c.gps.speed.collectWhenVisible()
    // Del GPS solo la velocidad entera: cada ubicación nueva (una por segundo, aunque estés parado)
    // ya no vuelve a dibujar la tarjeta de Vehículo si el número que se ve es el mismo.
    val gpsKmh by remember { androidx.compose.runtime.derivedStateOf { gps.value.kmh?.let { Math.round(it).toDouble() } } }
    val obdState by c.obd.state.collectWhenVisible()
    val obdData = c.obd.data.collectWhenVisible()
    val connected = obdState is EngineState.Connected
    // Solo la velocidad entera: los otros 30+ datos del OBD no deben redibujar quien la muestra.
    val obd by remember(connected) {
        androidx.compose.runtime.derivedStateOf {
            if (connected) obdData.value[com.civiclauncher.obd.Pids.SPEED].fresh(5_000)?.value?.let { Math.round(it).toDouble() } else null
        }
    }
    return (obd ?: gpsKmh) to (obd != null)
}

/** Un dato del OBD solo se muestra si llegó hace menos de 10 s; si no, es viejo y engañaría. */
fun Reading?.fresh(maxAgeMs: Long = 10_000): Reading? =
    this?.takeIf { android.os.SystemClock.elapsedRealtime() - it.atMs < maxAgeMs }

fun obdStatus(state: EngineState, config: ObdConfig): Pair<String, Tone> = when {
    config.mode == ObdMode.NONE -> "OBD sin configurar" to Tone.Neutral
    else -> {
        val src = if (config.mode == ObdMode.SIMULATOR) "Simulador" else (config.name ?: "OBD")
        when (state) {
            EngineState.Stopped -> "$src · en pausa" to Tone.Neutral
            is EngineState.Connecting -> "$src · conectando…" to Tone.Warn
            is EngineState.Detecting -> "$src · hablando con el carro…" to Tone.Warn
            is EngineState.Connected -> "$src · conectado" to Tone.Ok
            is EngineState.CarOff -> "$src · carro apagado" to Tone.Cool
            is EngineState.Reconnecting -> "$src · reintentando en ${state.retryInMs / 1000} s" to Tone.Bad
        }
    }
}

@Composable
fun ObdStatusPill(state: EngineState, config: ObdConfig, modifier: Modifier = Modifier) {
    val (text, tone) = obdStatus(state, config)
    StatusPill(text, tone, modifier)
}

/**
 * Ventana para elegir el adaptador. Busca por Bluetooth clásico y pone primero los que tienen
 * nombre de OBD (OBDII, V-LINK, Vgate…). También permite usar el simulador.
 */
@Composable
fun AdapterPickerDialog(
    onDismiss: () -> Unit,
    onPick: (AdapterCandidate) -> Unit,
    onSimulator: () -> Unit,
) {
    val ctx = LocalContext.current
    val scanner = remember { AdapterScanner(ctx.applicationContext) }
    val devices by scanner.devices.collectAsStateWithLifecycle()
    val scanning by scanner.scanning.collectAsStateWithLifecycle()
    DisposableEffect(Unit) { onDispose { scanner.stop() } }
    val scan = rememberPermissionAction(scanner.permissions()) { scanner.start() }

    // Ventana encima: opaca aunque haya fondo de pantalla.
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) { com.civiclauncher.app.ui.design.OpaqueSurfaces {
        Column(
            Modifier
                .width(640.dp)
                .heightIn(max = 540.dp)
                .clip(RoundedCornerShape(Civic.radii.xl))
                .background(Civic.colors.surface)
                .padding(Civic.space.xl),
        ) {
            Text("Elegir adaptador OBD", style = Civic.type.headline, color = Civic.colors.text)
            Text(
                "Conecta el adaptador al puerto OBD (abajo del volante) y pon la llave en ON. " +
                    "Debe ser Bluetooth clásico; si pide PIN, suele ser 1234.",
                style = Civic.type.bodySmall,
                color = Civic.colors.textDim,
            )
            Spacer(Modifier.height(Civic.space.l))
            Row(horizontalArrangement = Arrangement.spacedBy(Civic.space.m)) {
                if (!scanner.bluetoothReady()) {
                    CivicButton("Encender Bluetooth", {
                        runCatching { ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }, icon = P.Bluetooth)
                } else {
                    CivicButton(if (scanning) "Buscando…" else "Buscar", scan, icon = P.ArrowsClockwise, enabled = !scanning)
                }
                CivicButton("Usar simulador (demo)", onSimulator, kind = ButtonKind.Secondary, icon = P.Cpu)
                Spacer(Modifier.weight(1f))
                CivicButton("Cancelar", onDismiss, kind = ButtonKind.Secondary)
            }
            Spacer(Modifier.height(Civic.space.l))
            if (devices.isEmpty()) {
                Text(
                    if (scanning) "Buscando dispositivos…" else "Toca Buscar para ver los adaptadores cercanos.",
                    style = Civic.type.bodySmall,
                    color = Civic.colors.textFaint,
                )
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(Civic.space.s)) {
                items(devices, key = { it.address }) { d -> CandidateRow(d) { onPick(d) } }
            }
        }
    } }
}

@Composable
private fun CandidateRow(d: AdapterCandidate, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Civic.sizes.touch)
            .clip(RoundedCornerShape(Civic.radii.m))
            .background(if (pressed) Civic.colors.surfacePressed else Civic.colors.surfaceRaised)
            .clickable(source, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = Civic.space.l, vertical = Civic.space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (d.looksLikeObd) P.CarProfile else P.Bluetooth,
            null,
            tint = if (d.looksLikeObd) Civic.colors.accent else Civic.colors.textDim,
            modifier = Modifier.size(Civic.sizes.icon),
        )
        Spacer(Modifier.width(Civic.space.l))
        Column(Modifier.weight(1f)) {
            Text(d.name ?: "Sin nombre", style = Civic.type.label, color = Civic.colors.text)
            Text(d.address, style = Civic.type.caption, color = Civic.colors.textFaint)
        }
        if (d.looksLikeObd) StatusPill("Posible OBD", Tone.Ok)
        if (d.paired) {
            Spacer(Modifier.width(Civic.space.s))
            StatusPill("Emparejado", Tone.Neutral)
        }
    }
}
