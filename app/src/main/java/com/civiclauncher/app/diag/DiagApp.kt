package com.civiclauncher.app.diag

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.civiclauncher.app.BuildConfig
import com.civiclauncher.app.container
import com.civiclauncher.app.diag.collect.SystemCollector
import com.civiclauncher.app.diag.collect.UsbCollector
import com.civiclauncher.app.diag.export.SummaryCollector
import com.civiclauncher.app.diag.screens.AccScreen
import com.civiclauncher.app.diag.screens.AppsScreen
import com.civiclauncher.app.diag.screens.AutoKitScreen
import com.civiclauncher.app.diag.screens.BluetoothScreen
import com.civiclauncher.app.diag.screens.DesignCatalogScreen
import com.civiclauncher.app.diag.screens.ExportScreen
import com.civiclauncher.app.diag.screens.GpsScreen
import com.civiclauncher.app.diag.screens.LauncherScreen
import com.civiclauncher.app.diag.screens.NotificationScreen
import com.civiclauncher.app.diag.screens.PerfScreen
import com.civiclauncher.app.diag.screens.RootScreen
import com.civiclauncher.app.diag.screens.SppScreen
import com.civiclauncher.app.ui.ActionButton
import com.civiclauncher.app.ui.ButtonKind
import com.civiclauncher.app.ui.CivicColors
import com.civiclauncher.app.ui.InfoCard
import com.civiclauncher.app.ui.LoadingCard
import com.civiclauncher.app.ui.SectionScaffold
import com.civiclauncher.app.ui.produceAsync
import com.civiclauncher.app.ui.sectionCard
import kotlinx.coroutines.delay

enum class DiagSection(val title: String) {
    RESUMEN("Resumen"),
    CATALOGO("Catálogo de diseño"),
    SISTEMA("Sistema"),
    PANTALLA("Pantalla y GPU"),
    BLUETOOTH("Bluetooth"),
    SPP("Prueba SPP (OBD)"),
    USB("USB"),
    GPS("GPS"),
    AUTOKIT("AutoKit / Android Auto"),
    NOTIF("Música"),
    ROOT("Root y ADB"),
    LAUNCHER("Launcher"),
    APPS("Apps instaladas"),
    LLAVE("Prueba de la llave"),
    FLUIDEZ("Prueba de fluidez"),
    EXPORTAR("Exportar reporte"),
}

@Composable
fun DiagApp() {
    var current by rememberSaveable { mutableStateOf(DiagSection.RESUMEN) }
    Row(Modifier.fillMaxSize().background(CivicColors.Bg)) {
        NavRail(current) { current = it }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            when (current) {
                DiagSection.RESUMEN -> SummaryScreen { current = it }
                DiagSection.CATALOGO -> DesignCatalogScreen()
                DiagSection.SISTEMA -> SystemScreen()
                DiagSection.PANTALLA -> DisplayScreen()
                DiagSection.BLUETOOTH -> BluetoothScreen()
                DiagSection.SPP -> SppScreen()
                DiagSection.USB -> UsbScreen()
                DiagSection.GPS -> GpsScreen()
                DiagSection.AUTOKIT -> AutoKitScreen()
                DiagSection.NOTIF -> NotificationScreen()
                DiagSection.ROOT -> RootScreen()
                DiagSection.LAUNCHER -> LauncherScreen()
                DiagSection.APPS -> AppsScreen()
                DiagSection.LLAVE -> AccScreen()
                DiagSection.FLUIDEZ -> PerfScreen()
                DiagSection.EXPORTAR -> ExportScreen()
            }
        }
    }
}

@Composable
private fun NavRail(current: DiagSection, onSelect: (DiagSection) -> Unit) {
    LazyColumn(
        modifier = Modifier.width(210.dp).fillMaxHeight().background(CivicColors.Surface),
        contentPadding = PaddingValues(vertical = 16.dp, horizontal = 10.dp),
    ) {
        item {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text("Civic Launcher", style = MaterialTheme.typography.titleLarge)
                Text("Diagnóstico · v${BuildConfig.VERSION_NAME}", color = CivicColors.TextDim, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(12.dp))
        }
        items(DiagSection.entries) { s ->
            val selected = s == current
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .background(if (selected) CivicColors.Surface2 else CivicColors.Surface, RoundedCornerShape(12.dp))
                    .clickable { onSelect(s) }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.width(4.dp).height(22.dp)
                        .background(if (selected) CivicColors.Accent else CivicColors.Surface, RoundedCornerShape(2.dp)),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    s.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (selected) CivicColors.Text else CivicColors.TextDim,
                )
            }
        }
    }
}

@Composable
private fun SummaryScreen(onGo: (DiagSection) -> Unit) {
    val ctx = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val results by ctx.container.diagStore.results.collectAsStateWithLifecycle()
    val rows = produceAsync(refresh, results) { SummaryCollector.rows(ctx) }
    SectionScaffold(
        title = "Resumen",
        subtitle = "Lo más importante de tu pantalla. Haz las pruebas en orden y al final exporta el reporte.",
        actions = {
            ActionButton("Actualizar", { refresh++ }, kind = ButtonKind.Secondary)
            ActionButton("Exportar reporte", { onGo(DiagSection.EXPORTAR) })
        },
    ) {
        item { if (rows == null) LoadingCard() else InfoCard(rows) }
        item {
            InfoCard(
                title = "Pruebas que necesito que hagas",
                rows = emptyList(),
                notes = listOf(
                    "1. Sistema y Pantalla: solo revisa que carguen. Son automáticas.",
                    "2. Bluetooth: pon tu celular en modo visible (y el Vgate conectado al carro, si ya lo tienes) y escanea.",
                    "3. USB: conecta una memoria USB y toca Actualizar.",
                    "4. GPS: con el carro afuera, inicia la prueba y espera a que haya satélites en uso.",
                    "5. AutoKit / Android Auto: con música sonando en Android Auto, prueba los controles y la burbuja flotante.",
                    "6. Root y ADB: toca Probar root. Si sale un aviso de superusuario, acéptalo.",
                    "7. Launcher: activa la prueba y presiona Inicio para ver si te deja elegir Civic.",
                    "8. Fluidez: corre la ligera y la pesada; si hay root, precompila y repítelas.",
                    "9. Llave: activa el monitor, apaga el carro 1 minuto, préndelo; luego apágalo 10 minutos y préndelo.",
                    "10. Exportar: genera el reporte y pásamelo.",
                ),
            )
        }
    }
}

@Composable
private fun SystemScreen() {
    val ctx = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val section = produceAsync(refresh) { SystemCollector.system(ctx) }
    SectionScaffold(
        title = "Sistema",
        subtitle = "Versión real de Android, chip, memoria y almacenamiento.",
        actions = { ActionButton("Actualizar", { refresh++ }, kind = ButtonKind.Secondary) },
    ) { sectionCard(section) }
}

@Composable
private fun DisplayScreen() {
    val ctx = LocalContext.current
    val section = produceAsync(Unit) { SystemCollector.display(ctx) }
    SectionScaffold(
        title = "Pantalla y GPU",
        subtitle = "Con esto diseño el launcher al tamaño exacto de tu pantalla.",
    ) { sectionCard(section) }
}

@Composable
private fun UsbScreen() {
    val ctx = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    // Se actualiza sola cada 3 s para que veas aparecer lo que conectes.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            delay(3_000)
            refresh++
        }
    }
    val section = produceAsync(refresh) { UsbCollector.collect(ctx) }
    SectionScaffold(
        title = "USB",
        subtitle = "Para saber si un adaptador OBD por USB funcionaría.",
        actions = { ActionButton("Actualizar", { refresh++ }, kind = ButtonKind.Secondary) },
    ) { sectionCard(section) }
}
