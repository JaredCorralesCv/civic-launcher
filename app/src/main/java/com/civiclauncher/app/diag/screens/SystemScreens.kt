package com.civiclauncher.app.diag.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.civiclauncher.app.container
import com.civiclauncher.app.diag.ReportSection
import com.civiclauncher.app.diag.ResultKeys
import com.civiclauncher.app.diag.Row as InfoRowData
import com.civiclauncher.app.diag.Status
import com.civiclauncher.app.diag.collect.AppEntry
import com.civiclauncher.app.diag.collect.AppsCollector
import com.civiclauncher.app.diag.collect.RootCollector
import com.civiclauncher.app.diag.notif.NotificationCollector
import com.civiclauncher.app.diag.util.Shell
import com.civiclauncher.app.ui.ActionButton
import com.civiclauncher.app.ui.ButtonKind
import com.civiclauncher.app.ui.Card
import com.civiclauncher.app.ui.CivicColors
import com.civiclauncher.app.ui.InfoCard
import com.civiclauncher.app.ui.LoadingCard
import com.civiclauncher.app.ui.MonoText
import com.civiclauncher.app.ui.SectionScaffold
import com.civiclauncher.app.ui.produceAsync
import com.civiclauncher.app.ui.sectionCard
import com.civiclauncher.app.ui.startActivitySafe
import com.civiclauncher.app.ui.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------- Música / notificaciones ----------------

@Composable
fun NotificationScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    val section = produceAsync(refresh) { NotificationCollector.collect(ctx) }
    val rootOk = ctx.container.prefs.rootOk

    SectionScaffold(
        title = "Música",
        subtitle = "Para mostrar lo que suena en YouTube Music con sus controles (Fase 3).",
        actions = {
            ActionButton("Abrir ajuste de notificaciones", { ctx.startActivitySafe(NotificationCollector.settingsIntent()) })
            if (rootOk) {
                ActionButton("Dar permiso con root", {
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { Shell.su(NotificationCollector.rootCommand(ctx)) }
                        ctx.toast(if (r.ok) "Permiso concedido" else "Falló: ${r.summary()} ${r.stderr}")
                        refresh++
                    }
                }, kind = ButtonKind.Secondary)
            }
            ActionButton("Actualizar", { refresh++ }, kind = ButtonKind.Secondary)
        },
    ) {
        sectionCard(section)
        item {
            InfoCard(
                title = "Cómo probar",
                rows = emptyList(),
                notes = listOf(
                    "1. Toca \"Abrir ajuste de notificaciones\" y activa Civic Launcher.",
                    "2. Pon una canción en YouTube Music.",
                    "3. Regresa y toca Actualizar: debe aparecer una sesión \"reproduciendo\".",
                    "Si el ajuste no existe, el plan B es con la computadora:",
                ) + NotificationCollector.adbCommands(ctx),
            )
        }
    }
}

// ---------------- Root y ADB ----------------

@Composable
fun RootScreen() {
    val ctx = LocalContext.current
    val c = ctx.container
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var output by remember { mutableStateOf<String?>(null) }
    var confirmAdb by remember { mutableStateOf(false) }
    val section = produceAsync(refresh) { RootCollector.collect(ctx, c.prefs) }

    fun run(label: String, block: () -> Shell.Result, after: (Shell.Result) -> Unit = {}) {
        busy = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { block() }
            output = "$label → ${r.summary()}\n${r.stdout}\n${r.stderr}".trim()
            after(r)
            busy = false
            refresh++
        }
    }

    SectionScaffold(
        title = "Root y ADB",
        subtitle = "Root sirve para desactivar apps del fabricante e instalar sin cables. No es obligatorio.",
        actions = {
            ActionButton("Probar root", {
                run("su id", RootCollector::testRoot) { r ->
                    val ok = r.ok && r.stdout.contains("uid=0")
                    c.prefs.rootOk = ok
                    c.diagStore.put(
                        ResultKeys.ROOT,
                        ReportSection(
                            "Prueba de root",
                            listOf(
                                InfoRowData("Resultado", if (ok) "Root funciona" else "Sin root (${r.summary()})", if (ok) Status.OK else Status.INFO),
                                InfoRowData("Salida", r.stdout.ifEmpty { r.stderr }.ifEmpty { "—" }),
                            ),
                        ),
                    )
                }
            }, enabled = !busy)
            ActionButton("Activar adb por WiFi", { confirmAdb = true }, enabled = !busy && c.prefs.rootOk, kind = ButtonKind.Secondary)
            ActionButton("Apagar adb por WiFi", { run("adb WiFi off", RootCollector::disableAdbWifi) }, enabled = !busy && c.prefs.rootOk, kind = ButtonKind.Secondary)
            ActionButton("Opciones de desarrollador", { ctx.startActivitySafe(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }, kind = ButtonKind.Secondary)
            ActionButton("Actualizar", { refresh++ }, kind = ButtonKind.Secondary)
        },
    ) {
        sectionCard(section)
        output?.let { out -> item { Card { MonoText(out) } } }
        if (RootCollector.adbTcpPort() == "5555") {
            item {
                val ip = RootCollector.ipv4Addresses().firstOrNull { it.startsWith("wlan") }?.substringAfter(": ")
                InfoCard(
                    title = "adb por WiFi encendido",
                    rows = listOf(InfoRowData("En tu PC ejecuta", "adb connect ${ip ?: "<IP de la pantalla>"}:5555", Status.WARN)),
                    notes = listOf("Apágalo cuando termines de programar."),
                )
            }
        }
    }

    if (confirmAdb) {
        AlertDialog(
            onDismissRequest = { confirmAdb = false },
            title = { Text("¿Encender adb por WiFi?") },
            text = {
                Text(
                    "Android Studio podrá instalar la app sin cables. Mientras esté encendido, " +
                        "cualquier dispositivo en la misma red WiFi podría controlar la pantalla. Úsalo solo mientras programas.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmAdb = false
                    run("adb WiFi on", RootCollector::enableAdbWifi)
                }) { Text("Encender") }
            },
            dismissButton = { TextButton(onClick = { confirmAdb = false }) { Text("Cancelar") } },
        )
    }
}

// ---------------- Launcher ----------------

@Composable
fun LauncherScreen() {
    val ctx = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    val section = produceAsync(refresh) { AppsCollector.launcherSection(ctx) }

    SectionScaffold(
        title = "Launcher",
        subtitle = "Desde la Fase 2, Civic es un launcher completo. Aquí ves cuál está como predeterminado.",
        actions = {
            ActionButton("Presionar Inicio", { ctx.startActivitySafe(AppsCollector.homeIntent()) })
            ActionButton("Ajuste de launcher", {
                if (!ctx.startActivitySafe(Intent(Settings.ACTION_HOME_SETTINGS))) {
                    ctx.startActivitySafe(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
                }
            }, kind = ButtonKind.Secondary)
        },
    ) {
        sectionCard(section)
        item {
            InfoCard(
                title = "Cómo probar",
                rows = emptyList(),
                notes = listOf(
                    "1. Toca \"Presionar Inicio\" o el botón de inicio de la pantalla.",
                    "2. Si te pregunta qué app usar, elige Civic Launcher y \"Siempre\".",
                    "3. Si no pregunta, toca \"Ajuste de launcher\" y elígelo ahí.",
                    "Para volver al launcher del fabricante, elige \"Inicio\" en el mismo ajuste.",
                ),
            )
        }
    }
}

// ---------------- Apps instaladas ----------------

private enum class AppFilter(val label: String) {
    VENDOR("Del fabricante (pistas)"), SYSTEM("Del sistema"), USER("Instaladas por ti"), DISABLED("Desactivadas"), ALL("Todas")
}

@Composable
fun AppsScreen() {
    val ctx = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    var filter by remember { mutableStateOf(AppFilter.VENDOR) }
    val report = produceAsync(refresh) { AppsCollector.collect(ctx) }
    val list = report?.apps?.filter {
        when (filter) {
            AppFilter.VENDOR -> it.hint != null
            AppFilter.SYSTEM -> it.system
            AppFilter.USER -> !it.system
            AppFilter.DISABLED -> !it.enabled
            AppFilter.ALL -> true
        }
    }.orEmpty()

    SectionScaffold(
        title = "Apps instaladas",
        subtitle = "Para encontrar la radio, las llamadas y la cámara del fabricante. Toca una app para ver su información.",
        actions = {
            AppFilter.entries.forEach { f ->
                ActionButton(f.label, { filter = f }, kind = if (f == filter) ButtonKind.Primary else ButtonKind.Secondary)
            }
            ActionButton("Actualizar", { refresh++ }, kind = ButtonKind.Secondary)
        },
    ) {
        if (report == null) {
            item { LoadingCard("Leyendo apps… puede tardar unos segundos") }
        } else {
            item {
                Text(
                    "${list.size} de ${report.apps.size} apps · launcher predeterminado: ${report.defaultHome ?: "ninguno"}",
                    color = CivicColors.TextDim,
                )
            }
            items(list, key = { it.pkg }) { app -> AppRow(app) }
        }
    }
}

@Composable
private fun AppRow(app: AppEntry) {
    val ctx = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(CivicColors.Surface, RoundedCornerShape(14.dp))
            .clickable {
                ctx.startActivitySafe(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.pkg}")))
            }
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(app.label, style = MaterialTheme.typography.titleMedium)
            Text(app.pkg, color = CivicColors.TextDim, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(4.dp))
        val tags = listOfNotNull(
            app.hint,
            if (app.system) "sistema" else "instalada",
            if (!app.enabled) "desactivada" else null,
            if (app.isHome) "launcher" else null,
            "v${app.version}",
        )
        Text(tags.joinToString(" · "), color = if (app.hint != null) CivicColors.Accent else CivicColors.TextDim, style = MaterialTheme.typography.bodyMedium)
    }
}
