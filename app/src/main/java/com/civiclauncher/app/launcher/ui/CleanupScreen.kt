package com.civiclauncher.app.launcher.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.civiclauncher.app.container
import com.civiclauncher.app.launcher.cleanup.ResolvedEntry
import com.civiclauncher.app.launcher.cleanup.Risk
import com.civiclauncher.app.launcher.cleanup.RootStatus
import com.civiclauncher.app.ui.design.ButtonKind
import com.civiclauncher.app.ui.design.Civic
import com.civiclauncher.app.ui.design.CivicButton
import com.civiclauncher.app.ui.design.CivicCard
import com.civiclauncher.app.ui.design.CivicIconButton
import com.civiclauncher.app.ui.design.PhosphorIcons as P
import com.civiclauncher.app.ui.design.SectionHeader
import com.civiclauncher.app.ui.design.StatusPill
import com.civiclauncher.app.ui.design.Tone

@Composable
fun CleanupScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val repo = ctx.container.cleanup
    val entries by repo.entries.collectAsStateWithLifecycle()
    val root by repo.root.collectAsStateWithLifecycle()
    val busy by repo.busy.collectAsStateWithLifecycle()
    var confirmSafe by remember { mutableStateOf(false) }
    var showAdb by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { repo.refresh() }

    val present = entries.filter { it.isPresent }
    val safe = present.filter { it.entry.risk == Risk.SAFE }
    val canAct = root == RootStatus.AVAILABLE && !busy
    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Civic.space.xl),
        verticalArrangement = Arrangement.spacedBy(Civic.space.s),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CivicIconButton(P.CaretLeft, "Volver a Ajustes", onBack)
                Spacer(Modifier.width(Civic.space.l))
                Column(Modifier.weight(1f)) {
                    Text("Limpieza", style = Civic.type.headline, color = Civic.colors.text)
                    Text(
                        "Desactiva apps preinstaladas que no usas para dejarle más memoria a Android Auto. No se borra nada: todo se puede reactivar.",
                        style = Civic.type.bodySmall,
                        color = Civic.colors.textDim,
                    )
                }
            }
        }

        item { RootCard(root, busy, onCheck = { repo.checkRoot() }, onShowAdb = { showAdb = !showAdb }) }

        if (showAdb || root == RootStatus.UNAVAILABLE) {
            item { AdbCard(repo.adbCommands(safe, disable = true), repo.adbCommands(present, disable = false)) }
        }

        if (safe.any { !it.allDisabled }) {
            item {
                Spacer(Modifier.height(Civic.space.s))
                CivicButton(
                    "Desactivar todas las seguras (${safe.sumOf { it.installed.size - it.disabledCount }})",
                    { confirmSafe = true },
                    icon = P.CheckCircle,
                    enabled = canAct,
                )
            }
        }

        Risk.entries.forEach { risk ->
            val list = present.filter { it.entry.risk == risk }
            if (list.isEmpty()) return@forEach
            item {
                SectionHeader(risk.title)
                Text(risk.description, style = Civic.type.bodySmall, color = Civic.colors.textDim)
                Spacer(Modifier.height(Civic.space.s))
            }
            items(list, key = { it.entry.id }) { r ->
                EntryRow(r, canAct) { disable ->
                    repo.apply(listOf(r), disable, ::toast)
                }
            }
        }

        item {
            SectionHeader("Protegidas")
            Text(
                "Radio, CAN, MCU, botones del volante, cámara, audio, Bluetooth, arranque rápido, AutoKit y el launcher de respaldo " +
                    "no aparecen aquí y Civic se niega a desactivarlas: sin ellas podrías quedarte sin sonido o sin cámara.",
                style = Civic.type.bodySmall,
                color = Civic.colors.textDim,
            )
        }

        if (present.any { !it.noneDisabled }) {
            item {
                Spacer(Modifier.height(Civic.space.m))
                CivicButton(
                    "Reactivar todo",
                    { repo.apply(present.filter { !it.noneDisabled }, disable = false, ::toast) },
                    kind = ButtonKind.Secondary,
                    icon = P.ArrowsClockwise,
                    enabled = canAct,
                )
            }
        }
    }

    if (confirmSafe) {
        AlertDialog(
            onDismissRequest = { confirmSafe = false },
            title = { Text("¿Desactivar las apps seguras?") },
            text = {
                Text(
                    safe.joinToString("\n") { "• ${it.entry.title} (${it.installed.size})" } +
                        "\n\nPuedes reactivarlas cuando quieras desde esta misma pantalla.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmSafe = false
                    repo.apply(safe, disable = true, ::toast)
                }) { Text("Desactivar") }
            },
            dismissButton = { TextButton(onClick = { confirmSafe = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun RootCard(root: RootStatus, busy: Boolean, onCheck: () -> Unit, onShowAdb: () -> Unit) {
    CivicCard(Modifier.fillMaxWidth()) {
        when (root) {
            RootStatus.UNKNOWN -> {
                Text("Primero comprobemos el root", style = Civic.type.label, color = Civic.colors.text)
                Text(
                    "Tu pantalla trae root de fábrica. Si funciona, desactivas todo desde aquí con un toque. " +
                        "Si aparece un aviso de superusuario, acéptalo.",
                    style = Civic.type.bodySmall,
                    color = Civic.colors.textDim,
                )
                Spacer(Modifier.height(Civic.space.m))
                Row(horizontalArrangement = Arrangement.spacedBy(Civic.space.m)) {
                    CivicButton(if (busy) "Comprobando…" else "Comprobar root", onCheck, icon = P.Cpu, enabled = !busy)
                    CivicButton("Usar la PC en su lugar", onShowAdb, kind = ButtonKind.Secondary)
                }
            }
            RootStatus.AVAILABLE -> {
                StatusPill("Root disponible", Tone.Ok)
                Spacer(Modifier.height(Civic.space.s))
                Text("Puedes desactivar y reactivar desde aquí.", style = Civic.type.bodySmall, color = Civic.colors.textDim)
            }
            RootStatus.UNAVAILABLE -> {
                StatusPill("Sin root", Tone.Warn)
                Spacer(Modifier.height(Civic.space.s))
                Text(
                    "Desde la pantalla no se puede. Usa los comandos de abajo desde tu PC: es igual de seguro.",
                    style = Civic.type.bodySmall,
                    color = Civic.colors.textDim,
                )
                Spacer(Modifier.height(Civic.space.s))
                CivicButton("Volver a intentar root", onCheck, kind = ButtonKind.Secondary, enabled = !busy)
            }
        }
    }
}

@Composable
private fun AdbCard(disableCmds: String, enableCmds: String) {
    // Tu pantalla trae adb por WiFi en el puerto 8555 de fábrica (lo vimos en la Fase 0).
    val ip = remember {
        com.civiclauncher.app.diag.collect.RootCollector.ipv4Addresses()
            .firstOrNull { it.startsWith("wlan") }?.substringAfter(": ")
    }
    CivicCard(Modifier.fillMaxWidth()) {
        Text("Desde la PC (sin root)", style = Civic.type.label, color = Civic.colors.text)
        Text(
            "1. En la pantalla: Opciones de desarrollador → activa Depuración USB.\n" +
                "2. Conecta la pantalla y la PC al mismo WiFi.\n" +
                "3. En la PC: adb connect ${ip ?: "<IP de la pantalla>"}:8555 y acepta el aviso en la pantalla.\n" +
                (if (ip == null) "   (La IP aparecerá aquí cuando la pantalla esté conectada al WiFi.)\n" else "") +
                "4. Copia y pega estos comandos:",
            style = Civic.type.bodySmall,
            color = Civic.colors.textDim,
        )
        Spacer(Modifier.height(Civic.space.s))
        Code(disableCmds.ifEmpty { "(nada pendiente)" })
        Spacer(Modifier.height(Civic.space.m))
        Text("Para reactivar todo:", style = Civic.type.bodySmall, color = Civic.colors.textDim)
        Spacer(Modifier.height(Civic.space.s))
        Code(enableCmds.ifEmpty { "(nada)" })
    }
}

@Composable
private fun Code(text: String) {
    SelectionContainer {
        Text(
            text,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            color = Civic.colors.text,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Civic.radii.s))
                .background(Civic.colors.surfaceRaised)
                .padding(Civic.space.m),
        )
    }
}

@Composable
private fun EntryRow(r: ResolvedEntry, canAct: Boolean, onToggle: (disable: Boolean) -> Unit) {
    CivicCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(r.entry.title, style = Civic.type.label, color = Civic.colors.text)
                    Spacer(Modifier.width(Civic.space.m))
                    StatusPill(
                        when {
                            r.allDisabled -> "Desactivada"
                            r.noneDisabled -> if (r.installed.size > 1) "${r.installed.size} activas" else "Activa"
                            else -> "${r.disabledCount} de ${r.installed.size} desactivadas"
                        },
                        when {
                            r.allDisabled -> Tone.Ok
                            r.noneDisabled -> Tone.Neutral
                            else -> Tone.Warn
                        },
                    )
                }
                Spacer(Modifier.height(Civic.space.xs))
                Text(r.entry.note, style = Civic.type.bodySmall, color = Civic.colors.textDim)
            }
            Spacer(Modifier.width(Civic.space.l))
            if (r.allDisabled) {
                CivicButton("Reactivar", { onToggle(false) }, kind = ButtonKind.Secondary, enabled = canAct)
            } else {
                CivicButton(
                    "Desactivar",
                    { onToggle(true) },
                    kind = if (r.entry.risk == Risk.SAFE) ButtonKind.Primary else ButtonKind.Secondary,
                    enabled = canAct,
                )
            }
        }
    }
}
