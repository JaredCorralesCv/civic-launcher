package com.civiclauncher.app.diag.screens

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.civiclauncher.app.diag.Row as InfoRowData
import com.civiclauncher.app.diag.Status
import com.civiclauncher.app.diag.export.ReportBuilder
import com.civiclauncher.app.diag.export.ReportExporter
import com.civiclauncher.app.diag.util.formatBytes
import com.civiclauncher.app.ui.ActionButton
import com.civiclauncher.app.ui.ButtonKind
import com.civiclauncher.app.ui.Card
import com.civiclauncher.app.ui.InfoCard
import com.civiclauncher.app.ui.MonoText
import com.civiclauncher.app.ui.SectionScaffold
import com.civiclauncher.app.ui.rememberPermissionAction
import com.civiclauncher.app.ui.startActivitySafe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ExportScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var exported by remember { mutableStateOf<ReportExporter.Exported?>(null) }
    var preview by remember { mutableStateOf<String?>(null) }

    val perms = if (Build.VERSION.SDK_INT < 29) ReportExporter.legacyStoragePermission else emptyArray()
    val generate = rememberPermissionAction(perms, proceedIfDenied = true) {
        busy = true
        scope.launch {
            val (text, result) = withContext(Dispatchers.IO) {
                val t = ReportBuilder.build(ctx)
                t to ReportExporter.save(ctx, t)
            }
            exported = result
            preview = text.take(6_000)
            busy = false
        }
    }

    SectionScaffold(
        title = "Exportar reporte",
        subtitle = "Junta todo en un archivo de texto para que me lo pases. No incluye tu ubicación ni números de serie.",
        actions = {
            ActionButton(if (busy) "Generando…" else "Generar reporte", generate, enabled = !busy)
            exported?.let { e ->
                ActionButton("Compartir", { ctx.startActivitySafe(ReportExporter.shareIntent(ctx, e.internal)) }, kind = ButtonKind.Secondary)
            }
        },
    ) {
        exported?.let { e ->
            item {
                InfoCard(
                    title = "Reporte listo",
                    rows = listOf(
                        InfoRowData("Tamaño", formatBytes(e.sizeBytes)),
                        InfoRowData(
                            "Guardado en Descargas",
                            e.publicPath ?: "No se pudo (${e.publicError})",
                            if (e.publicPath != null) Status.OK else Status.WARN,
                        ),
                        InfoRowData("Copia interna", e.internal.absolutePath),
                    ),
                    notes = listOf(
                        "Cómo pasármelo: copia el archivo de Descargas/CivicLauncher a una memoria USB con el administrador de archivos de la pantalla, " +
                            "o usa Compartir si tienes WhatsApp o Telegram instalado en la pantalla.",
                    ),
                )
            }
        }
        preview?.let { p -> item { Card { MonoText(p + if (p.length >= 6_000) "\n…" else "") } } }
    }
}
