package com.civiclauncher.app.diag.screens

import android.Manifest
import android.os.Build
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.civiclauncher.app.container
import com.civiclauncher.app.diag.Row as InfoRowData
import com.civiclauncher.app.diag.Status
import com.civiclauncher.app.diag.acc.AccMonitorService
import com.civiclauncher.app.ui.ActionButton
import com.civiclauncher.app.ui.ButtonKind
import com.civiclauncher.app.ui.Card
import com.civiclauncher.app.ui.InfoCard
import com.civiclauncher.app.ui.MonoText
import com.civiclauncher.app.ui.Note
import com.civiclauncher.app.ui.SectionScaffold
import com.civiclauncher.app.ui.rememberPermissionAction

@Composable
fun AccScreen() {
    val ctx = LocalContext.current
    val c = ctx.container
    var enabled by remember { mutableStateOf(c.prefs.accMonitorEnabled) }
    val lines by c.accLog.lines.collectAsStateWithLifecycle()
    val notifPerm = if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()

    val turnOn = rememberPermissionAction(notifPerm, proceedIfDenied = true) {
        c.prefs.clearHeartbeat()
        c.prefs.accMonitorEnabled = true
        c.accLog.log("Monitor activado por ti")
        AccMonitorService.start(ctx, "activado por ti")
        enabled = true
    }

    SectionScaffold(
        title = "Prueba de la llave",
        subtitle = "Descubre qué hace tu pantalla cuando quitas y pones la llave, para que el OBD se reconecte solo.",
        actions = {
            if (!enabled) {
                ActionButton("Activar monitor", turnOn)
            } else {
                ActionButton("Desactivar monitor", {
                    c.prefs.accMonitorEnabled = false
                    AccMonitorService.stop(ctx)
                    enabled = false
                }, kind = ButtonKind.Danger)
            }
            ActionButton("Marcar: voy a apagar", { c.accLog.log("MARCA: vas a quitar la llave") }, kind = ButtonKind.Secondary)
            ActionButton("Marcar: ya prendí", { c.accLog.log("MARCA: acabas de poner la llave") }, kind = ButtonKind.Secondary)
            ActionButton("Limpiar bitácora", { c.accLog.clear() }, kind = ButtonKind.Secondary)
        },
    ) {
        item {
            InfoCard(
                rows = listOf(
                    InfoRowData("Monitor", if (enabled) "Activo" else "Apagado", if (enabled) Status.OK else Status.INFO),
                    InfoRowData("Eventos registrados", lines.size.toString()),
                    InfoRowData("Detección de avisos del fabricante", if (c.prefs.rootOk) "Sí (hay root)" else "No (necesita root)"),
                ),
                notes = listOf(
                    "1. Activa el monitor y toca \"Marcar: voy a apagar\".",
                    "2. Quita la llave, espera 1 minuto y vuelve a ponerla. Toca \"Marcar: ya prendí\".",
                    "3. Repite esperando 10 minutos, y otra vez dejando el carro apagado toda la noche.",
                    "4. Revisa abajo qué pasó: suspensión profunda, reinicio completo o la app cerrada por el sistema.",
                ),
            )
        }
        item { Note("Bitácora (lo más reciente primero)") }
        if (lines.isEmpty()) {
            item { Card { Note("Sin eventos todavía.") } }
        } else {
            items(lines.asReversed()) { line -> Card { MonoText(line) } }
        }
    }
}
