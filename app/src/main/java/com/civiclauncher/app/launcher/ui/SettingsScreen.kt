package com.civiclauncher.app.launcher.ui

import androidx.compose.foundation.layout.height
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.unit.sp
import com.civiclauncher.app.ui.design.bevelBorder
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.civiclauncher.app.BuildConfig
import com.civiclauncher.app.container
import com.civiclauncher.app.launcher.data.ThemeMode
import com.civiclauncher.app.launcher.data.VendorApps
import com.civiclauncher.app.launcher.obd.ObdMode
import com.civiclauncher.app.launcher.update.UpdateState
import com.civiclauncher.app.ui.design.ButtonKind
import com.civiclauncher.app.ui.design.Civic
import com.civiclauncher.app.ui.design.CivicButton
import com.civiclauncher.app.ui.design.CivicCard
import com.civiclauncher.app.ui.design.LinearMeter
import com.civiclauncher.app.ui.design.PhosphorIcons as P
import com.civiclauncher.app.ui.design.SectionHeader
import com.civiclauncher.app.ui.design.Tone

@Composable
fun SettingsScreen(onOpenDiagnostics: () -> Unit, onOpenCleanup: () -> Unit) {
    val ctx = LocalContext.current
    val c = ctx.container
    val theme by c.launcherPrefs.themeMode.collectAsStateWithLifecycle()
    val tz by c.clock.timeZone.collectAsStateWithLifecycle()

    fun open(intent: Intent) {
        runCatching { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Toast.makeText(ctx, "Esta pantalla no tiene esa opción", Toast.LENGTH_SHORT).show() }
    }

    fun openVendor(pkg: String, name: String) {
        if (!c.apps.launchPackage(pkg)) Toast.makeText(ctx, "$name no está instalada", Toast.LENGTH_SHORT).show()
    }

    var section by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(SettingsSection.APPEARANCE) }

    // Como los ajustes de los autos: categorías a la izquierda y su contenido a la derecha, sin tener
    // que recorrer una lista larguísima manejando.
    Row(Modifier.fillMaxSize().padding(horizontal = PAGE_GUTTER, vertical = Civic.space.m), horizontalArrangement = Arrangement.spacedBy(Civic.space.l)) {
        Column(Modifier.width(232.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(Civic.space.xs)) {
            Text("Ajustes", style = Civic.type.pageTitle, color = Civic.colors.text, modifier = Modifier.padding(start = 8.dp, bottom = Civic.space.m))
            SettingsSection.entries.forEach { s ->
                SectionTab(s, selected = s == section) { section = s }
            }
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(Civic.space.s),
        ) {
            item { Text(section.title, style = Civic.type.title, color = Civic.colors.text, modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = Civic.space.s)) }
            when (section) {
                SettingsSection.APPEARANCE -> {
                    item {
                        CivicCard(Modifier.fillMaxWidth()) {
                            Text("Tema", style = Civic.type.label, color = Civic.colors.text)
                            val sportOn = c.launcherPrefs.homeStyle.collectAsStateWithLifecycle().value == com.civiclauncher.app.launcher.data.HomeStyle.SPORT
                            Text(
                                if (sportOn) "Con el estilo Sport, Civic siempre usa los colores de noche (es un tablero)."
                                else "Automático: noche de 19:00 a 7:00, día el resto del tiempo.",
                                style = Civic.type.bodySmall,
                                color = Civic.colors.textDim,
                            )
                            Spacer(Modifier.size(Civic.space.m))
                            Row(horizontalArrangement = Arrangement.spacedBy(Civic.space.m)) {
                                ThemeMode.entries.forEach { mode ->
                                    CivicButton(
                                        mode.label,
                                        { c.launcherPrefs.setThemeMode(mode) },
                                        kind = if (mode == theme) ButtonKind.Primary else ButtonKind.Secondary,
                                        icon = when (mode) {
                                            ThemeMode.AUTO -> P.Clock
                                            ThemeMode.NIGHT -> P.Moon
                                            ThemeMode.DAY -> P.Sun
                                        },
                                    )
                                }
                            }
                        }
                    }
                    item {
                        val style by c.launcherPrefs.homeStyle.collectAsStateWithLifecycle()
                        CivicCard(Modifier.fillMaxWidth()) {
                            Text("Estilo de Inicio", style = Civic.type.label, color = Civic.colors.text)
                            Text(style.description, style = Civic.type.bodySmall, color = Civic.colors.textDim)
                            Spacer(Modifier.size(Civic.space.m))
                            Row(horizontalArrangement = Arrangement.spacedBy(Civic.space.m)) {
                                com.civiclauncher.app.launcher.data.HomeStyle.entries.forEach { s ->
                                    CivicButton(
                                        s.label,
                                        { c.launcherPrefs.setHomeStyle(s) },
                                        kind = if (s == style) ButtonKind.Primary else ButtonKind.Secondary,
                                        icon = if (s == com.civiclauncher.app.launcher.data.HomeStyle.SPORT) P.Gauge else P.House,
                                    )
                                }
                            }
                        }
                    }
                    item { WallpaperPicker() }
                    item {
                        val useAccent by c.launcherPrefs.accentFromWallpaper.collectAsStateWithLifecycle()
                        SettingRow(
                            P.Sun,
                            if (useAccent) "Color según el fondo: sí" else "Color según el fondo: no",
                            "Botones y medidores toman el color principal de tu fondo de pantalla. Toca para cambiar.",
                            showChevron = false,
                        ) { c.launcherPrefs.setAccentFromWallpaper(!useAccent) }
                    }
                    item {
                        val mode by c.launcherPrefs.animationMode.collectAsStateWithLifecycle()
                        SettingRow(P.Gauge, "Animaciones: ${mode.label}", mode.description, showChevron = false) {
                            val all = com.civiclauncher.app.launcher.AnimationMode.entries
                            c.launcherPrefs.setAnimationMode(all[(mode.ordinal + 1) % all.size])
                        }
                    }
                    item {
                        val autoHide by c.launcherPrefs.autoHideBar.collectAsStateWithLifecycle()
                        SettingRow(
                            P.SquaresFourLight,
                            if (autoHide) "Esconder la barra de abajo: sí" else "Esconder la barra de abajo: no",
                            "Se esconde sola tras 6 s sin tocar la pantalla. Para abrirla, desliza hacia arriba desde el borde de abajo o toca la rayita.",
                            showChevron = false,
                        ) { c.launcherPrefs.setAutoHideBar(!autoHide) }
                    }
                    item { CarPhotoRow() }
                }
                SettingsSection.MAP -> {
                    item { MapSettings() }
                    item { RoutingSettings() }
                    item { PlacesSettings() }
                }
                SettingsSection.MUSIC -> {
                    item {
                        SettingRow(P.BluetoothLight, "Bluetooth del celular", "Conectar el teléfono, llamadas y contactos (app de la pantalla)") {
                            openVendor(VendorApps.BLUETOOTH, "Bluetooth")
                        }
                    }
                    item {
                        var autoplay by remember { mutableStateOf(c.launcherPrefs.autoplayBluetooth) }
                        SettingRow(
                            P.BluetoothLight,
                            if (autoplay) "Reproducir al conectar: sí" else "Reproducir al conectar: no",
                            "Cuando tu celular se conecta por Bluetooth, la música empieza sola. Toca para cambiar.",
                            showChevron = false,
                        ) {
                            autoplay = !autoplay
                            c.launcherPrefs.autoplayBluetooth = autoplay
                        }
                    }
                    item {
                        var interrupts by remember { mutableStateOf(c.launcherPrefs.phoneInterruptsRadio) }
                        SettingRow(
                            P.RadioLight,
                            if (interrupts) "El celular interrumpe la radio: sí" else "El celular interrumpe la radio: no",
                            "Si suena algo en tu celular (música o un audio de WhatsApp) con la radio puesta, se oye el celular. Toca para cambiar.",
                            showChevron = false,
                        ) {
                            interrupts = !interrupts
                            c.launcherPrefs.phoneInterruptsRadio = interrupts
                        }
                    }
                    item {
                        val demo = c.demoMedia
                        val on by demo.active.collectAsStateWithLifecycle()
                        SettingRow(
                            P.MusicNotes,
                            if (on) "Música de prueba: encendida" else "Música de prueba",
                            "Canciones inventadas, sin sonido, para ver la tarjeta de música de Inicio sin el celular",
                            showChevron = false,
                        ) {
                            if (on) demo.stop() else demo.start()
                            c.media.refresh()
                        }
                    }
                }
                SettingsSection.VEHICLE -> {
                    item { ObdSettings() }
                    item { SettingRow(P.SteeringWheel, "Botones del volante", "App del fabricante para aprender los botones (SWC)") { openVendor(VendorApps.STEERING_WHEEL, "SWC") } }
                    item { SettingRow(P.CarProfile, "Configuraciones del vehículo", "Ajustes del fabricante para el carro") { openVendor(VendorApps.CAR_SETTINGS, "La app") } }
                    item { SettingRow(P.Cpu, "CAN del vehículo", "Para ver si hay caja decodificadora CAN instalada") { openVendor(VendorApps.CAN, "Can Vehiculo") } }
                }
                SettingsSection.SYSTEM -> {
                    item {
                        SettingRow(
                            P.Clock,
                            "Zona horaria",
                            if (tz == "Asia/Shanghai") "$tz · la pantalla venía con la hora de China. Cámbiala a la de tu ciudad." else tz,
                            tone = if (tz == "Asia/Shanghai") Tone.Warn else Tone.Neutral,
                        ) { open(Intent(Settings.ACTION_DATE_SETTINGS)) }
                    }
                    item { SettingRow(P.House, "Launcher predeterminado", "Elige Civic o el launcher del fabricante") { open(Intent(Settings.ACTION_HOME_SETTINGS)) } }
                    item { SettingRow(P.Wrench, "Limpieza", "Desactiva apps preinstaladas que no usas para liberar memoria") { onOpenCleanup() } }
                    item { SettingRow(P.WifiHigh, "WiFi", "Para buscar actualizaciones y descargar mapas con el WiFi de tu casa") { open(Intent(Settings.ACTION_WIFI_SETTINGS)) } }
                    item { SettingRow(P.GearSix, "Ajustes de Android", "Configuración general de la pantalla") { open(Intent(Settings.ACTION_SETTINGS)) } }
                }
                SettingsSection.UPDATES -> {
                    item { UpdateCard() }
                    item {
                        val opt by c.optimizer.status.collectAsStateWithLifecycle()
                        SettingRow(
                            P.Cpu,
                            when (opt) {
                                is com.civiclauncher.app.launcher.AppOptimizer.Status.Done -> "Optimizado para esta pantalla"
                                com.civiclauncher.app.launcher.AppOptimizer.Status.Running -> "Optimizando… (hasta 10 min)"
                                is com.civiclauncher.app.launcher.AppOptimizer.Status.Failed -> "Se optimiza solo en unas horas"
                                com.civiclauncher.app.launcher.AppOptimizer.Status.Idle -> "Optimizar para esta pantalla"
                            },
                            when (opt) {
                                is com.civiclauncher.app.launcher.AppOptimizer.Status.Done -> "Civic está compilado a código nativo. Se nota al reiniciar Civic. Toca para repetirlo."
                                // Medido en tu pantalla: su no da root a nadie, pero Android sí optimiza Civic solo en
                                // su mantenimiento de fondo (corre con la pantalla apagada, más o menos una vez al día).
                                is com.civiclauncher.app.launcher.AppOptimizer.Status.Failed -> "Tras actualizar, Android optimiza Civic solo en su mantenimiento (aprox. un día). Por la PC se puede hacer al momento."
                                else -> "Compila Civic a código nativo para que vaya más fluido. Se hace solo una vez por versión."
                            },
                            showChevron = false,
                        ) { c.optimizer.compileNow() }
                    }
                }
                SettingsSection.ABOUT -> {
                    item { SettingRow(P.Wrench, "Diagnóstico", "Las pruebas de hardware de la Fase 0") { onOpenDiagnostics() } }
                    item {
                        SettingRow(
                            P.Info,
                            "Civic Launcher ${BuildConfig.VERSION_NAME}",
                            "Inter (SIL OFL) · Phosphor Icons (MIT) · Mapas y rutas © colaboradores de OpenStreetMap · Mapsforge (LGPL) · BRouter (MIT)",
                            showChevron = false,
                        ) { }
                    }
                }
            }
        }
    }
}

/** Categorías de Ajustes (columna izquierda). */
private enum class SettingsSection(val title: String, val icon: ImageVector) {
    APPEARANCE("Apariencia", P.SunFill),
    MAP("Mapa y rutas", P.MapTrifoldFill),
    MUSIC("Música", P.MusicNotesFill),
    VEHICLE("Vehículo", P.CarSimpleFill),
    SYSTEM("Sistema", P.GearSixFill),
    UPDATES("Actualizaciones", P.DownloadSimpleFill),
    ABOUT("Acerca de", P.InfoFill),
}

/** Una categoría: ícono y nombre; la elegida se resalta como cápsula de vidrio claro. */
@Composable
private fun SectionTab(s: SettingsSection, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(shape)
            .let { if (selected) it.background(Civic.colors.selected).bevelBorder(shape) else it }
            .clickable(role = Role.Tab, onClickLabel = s.title, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(s.icon, null, tint = if (selected) Civic.colors.accent else Civic.colors.textDim, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(
            s.title,
            style = Civic.type.label.copy(fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Normal),
            color = if (selected) Civic.colors.text else Civic.colors.textDim,
            maxLines = 1,
        )
    }
}

/**
 * Datos de rutas (para navegar sin internet): qué zonas están descargadas, bajar las de tu región y
 * el estado de la búsqueda de lugares.
 */
@Composable
private fun RoutingSettings() {
    val c = LocalContext.current.container
    val download by c.nav.download.collectAsStateWithLifecycle()
    val indexState by c.nav.search.state.collectAsStateWithLifecycle()
    var tiles by remember { mutableStateOf(c.nav.installedTiles()) }
    LaunchedEffect(download) { tiles = c.nav.installedTiles() }
    // Lee el estado del índice de búsqueda guardado (sin cargarlo en la memoria).
    LaunchedEffect(Unit) { c.map.lastPosition()?.let { c.nav.search.prebuild(it) } }
    var confirmDelete by remember { mutableStateOf(false) }
    CivicCard(Modifier.fillMaxWidth()) {
        Text("Rutas sin internet", style = Civic.type.label, color = Civic.colors.text)
        Text(
            if (tiles.isEmpty()) "Para navegar sin Android Auto hacen falta los datos de rutas de tu zona (una sola vez, por WiFi). Se descargan solos la primera vez que pides una ruta."
            else "${tiles.size} zona(s) · ${tiles.values.sum()} MB · ${tiles.keys.sorted().joinToString { it.removeSuffix(".rd5").replace('_', ' ') }}",
            style = Civic.type.bodySmall,
            color = Civic.colors.textDim,
        )
        when (val d = download) {
            is com.civiclauncher.app.launcher.nav.TilesDownload.Running -> {
                Spacer(Modifier.size(Civic.space.s))
                Text("Descargando zona ${d.index + 1} de ${d.count}… ${(d.progress * 100).toInt()} %", style = Civic.type.label, color = Civic.colors.text)
                Spacer(Modifier.size(Civic.space.xs))
                LinearMeter(d.progress, Tone.Accent)
            }
            is com.civiclauncher.app.launcher.nav.TilesDownload.Failed -> Text(d.message, style = Civic.type.bodySmall, color = Civic.colors.bad)
            else -> Unit
        }
        Spacer(Modifier.size(Civic.space.m))
        Row(horizontalArrangement = Arrangement.spacedBy(Civic.space.m)) {
            CivicButton("Descargar mi región", {
                val p = c.map.lastPosition() ?: com.civiclauncher.app.launcher.map.MEXICO_CENTER
                c.nav.downloadTiles(com.civiclauncher.app.launcher.nav.RoutingData.regionAround(p))
            }, icon = P.DownloadSimple, enabled = download !is com.civiclauncher.app.launcher.nav.TilesDownload.Running)
            if (tiles.isNotEmpty()) CivicButton("Borrar", { confirmDelete = true }, kind = ButtonKind.Secondary)
        }
        Spacer(Modifier.size(Civic.space.m))
        Text(
            when (val s = indexState) {
                is com.civiclauncher.app.launcher.nav.IndexState.Ready -> "Búsqueda lista: ${"%,d".format(s.count)} lugares y calles de tu zona."
                is com.civiclauncher.app.launcher.nav.IndexState.Building -> "Preparando la búsqueda de tu zona… ${(s.progress * 100).toInt()} %"
                com.civiclauncher.app.launcher.nav.IndexState.Loading -> "Cargando la búsqueda…"
                com.civiclauncher.app.launcher.nav.IndexState.Empty ->"La búsqueda de lugares se prepara sola en segundo plano cuando hay ubicación."
            },
            style = Civic.type.bodySmall,
            color = Civic.colors.textDim,
        )
    }
    if (confirmDelete) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("¿Borrar los datos de rutas?") },
            text = { Text("Libera ${tiles.values.sum()} MB. Para navegar otra vez se vuelven a descargar.") },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { c.nav.deleteTiles(); tiles = c.nav.installedTiles(); confirmDelete = false }) { Text("Borrar") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } },
        )
    }
}

/** Casa y Trabajo: se ven aquí y se pueden quitar (se guardan desde el mapa). */
@Composable
private fun PlacesSettings() {
    val c = LocalContext.current.container
    val home by c.nav.home.collectAsStateWithLifecycle()
    val work by c.nav.work.collectAsStateWithLifecycle()
    SettingRow(
        P.HouseFill,
        "Casa",
        home?.name ?: "Sin guardar · en el mapa, elige un destino y toca \"Guardar como Casa\"",
        showChevron = false,
    ) { if (home != null) c.nav.clearHome() }
    Spacer(Modifier.size(Civic.space.s))
    SettingRow(
        P.MapPinFill,
        "Trabajo",
        work?.name ?: "Sin guardar · en el mapa, elige un destino y toca \"Guardar como Trabajo\"",
        showChevron = false,
    ) { if (work != null) c.nav.clearWork() }
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    tone: Tone = Tone.Neutral,
    showChevron: Boolean = true,
    onClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .clip(RoundedCornerShape(Civic.radii.l))
            .background(if (pressed) Civic.colors.surfacePressed else Civic.colors.surface)
            .clickable(source, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = Civic.space.l, vertical = Civic.space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (tone == Tone.Neutral) Civic.colors.textDim else Civic.colors.warn, modifier = Modifier.size(Civic.sizes.icon))
        Spacer(Modifier.width(Civic.space.l))
        Column(Modifier.weight(1f)) {
            Text(title, style = Civic.type.label, color = Civic.colors.text)
            Text(subtitle, style = Civic.type.bodySmall, color = Civic.colors.textDim)
        }
        if (showChevron) Icon(P.CaretRight, null, tint = Civic.colors.textFaint, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun MapSettings() {
    val repo = LocalContext.current.container.map
    val status by repo.status.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    val ready = status as? com.civiclauncher.app.launcher.map.MapStatus.Ready
    if (ready == null) {
        CivicCard(Modifier.fillMaxWidth()) {
            com.civiclauncher.app.launcher.map.MapSetup(status, Modifier.fillMaxWidth())
        }
        return
    }
    SettingRow(
        P.MapTrifold,
        "Mapa instalado: ${ready.file.name.removeSuffix(".map")}",
        "${ready.file.length() / 1_048_576} MB · funciona sin internet · datos de OpenStreetMap",
        showChevron = false,
    ) { }
    Spacer(Modifier.size(Civic.space.s))
    SettingRow(P.Power, "Borrar el mapa", "Libera ${ready.file.length() / 1_048_576} MB. Puedes volver a descargarlo.", showChevron = false) {
        confirmDelete = true
    }
    if (confirmDelete) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("¿Borrar el mapa?") },
            text = { Text("Para volver a tenerlo habrá que descargar ${com.civiclauncher.app.launcher.map.MapRepository.MEXICO_SIZE_MB} MB por WiFi.") },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { confirmDelete = false; repo.delete() }) { Text("Borrar") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } },
        )
    }
}

/** Elegir la foto de tu carro para "Mi vehículo" en Inicio (desde la galería o una memoria USB). */
/** Fondo de pantalla: "Sin fondo", los que hay en la carpeta de Civic, y "Agregar" desde USB/galería. */
@Composable
private fun WallpaperPicker() {
    val ctx = LocalContext.current
    val repo = ctx.container.wallpapers
    val files by repo.files.collectAsStateWithLifecycle()
    val selected by repo.selected.collectAsStateWithLifecycle()
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { repo.refresh() }
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        // Varias a la vez: puedes elegir todos los fondos de tu memoria USB de una sola vez.
        androidx.activity.result.contract.ActivityResultContracts.GetMultipleContents(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            repo.import(uris) { n ->
                val msg = when (n) {
                    0 -> "No se pudo usar esa imagen"
                    1 -> "Fondo listo"
                    else -> "$n fondos agregados"
                }
                Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }
    CivicCard(Modifier.fillMaxWidth()) {
        Text("Fondo de pantalla", style = Civic.type.label, color = Civic.colors.text)
        Text(
            "Con fondo, las tarjetas se vuelven de vidrio y dejan ver la imagen.",
            style = Civic.type.bodySmall,
            color = Civic.colors.textDim,
        )
        Spacer(Modifier.size(Civic.space.m))
        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(Civic.space.m)) {
            item {
                WallpaperTile(selected == null, { repo.select(null) }) {
                    TileLabel(P.XFill, "Sin fondo")
                }
            }
            items(files.size, key = { files[it].name }) { i ->
                val f = files[i]
                val thumb by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, f.name) {
                    value = repo.thumbnail(f)
                }
                WallpaperTile(selected == f.name, { repo.select(f.name) }) {
                    thumb?.let {
                        androidx.compose.foundation.Image(
                            it,
                            contentDescription = "Fondo ${f.nameWithoutExtension}",
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
            item {
                WallpaperTile(false, {
                    runCatching { picker.launch("image/*") }
                        .onFailure { Toast.makeText(ctx, "No hay app para elegir imágenes", Toast.LENGTH_SHORT).show() }
                }) {
                    TileLabel(P.DownloadSimple, "Agregar")
                }
            }
        }
    }
}

@Composable
private fun WallpaperTile(selected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(Civic.radii.m)
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    androidx.compose.foundation.layout.Box(
        Modifier
            .size(width = 168.dp, height = 100.dp)
            .clip(shape)
            .background(if (pressed) Civic.colors.surfacePressed else Civic.colors.surfaceRaised)
            .then(
                if (selected) Modifier.border(3.dp, Civic.colors.accent, shape)
                else Modifier.border(1.dp, Civic.colors.line, shape),
            )
            .clickable(source, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun TileLabel(icon: ImageVector, text: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = Civic.colors.textDim, modifier = Modifier.size(24.dp))
        Spacer(Modifier.size(Civic.space.xs))
        Text(text, style = Civic.type.label, color = Civic.colors.text)
    }
}

@Composable
private fun CarPhotoRow() {
    val ctx = LocalContext.current
    val repo = ctx.container.carPhoto
    val photo by repo.photo.collectAsStateWithLifecycle()
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            repo.setFrom(uri) { ok ->
                Toast.makeText(ctx, if (ok) "Foto lista" else "No se pudo usar esa imagen", Toast.LENGTH_SHORT).show()
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(Civic.space.s)) {
        SettingRow(
            P.Car,
            if (photo == null) "Foto de tu carro" else "Cambiar foto de tu carro",
            "Aparece en el widget \"Foto de mi carro\" del Inicio Sport (Editar → Vehículo). Funciona mejor una foto de lado.",
        ) {
            runCatching { picker.launch("image/*") }
                .onFailure { Toast.makeText(ctx, "No hay app para elegir imágenes", Toast.LENGTH_SHORT).show() }
        }
        if (photo != null) {
            SettingRow(P.Car, "Quitar foto", "Vuelve al dibujo de un carro", showChevron = false) { repo.clear() }
        }
    }
}

@Composable
private fun ObdSettings() {
    val repo = LocalContext.current.container.obd
    val config by repo.config.collectAsStateWithLifecycle()
    val state by repo.state.collectAsStateWithLifecycle()
    var picker by remember { mutableStateOf(false) }
    val (status, tone) = obdStatus(state, config)

    Column(verticalArrangement = Arrangement.spacedBy(Civic.space.s)) {
        SettingRow(
            P.Bluetooth,
            when (config.mode) {
                ObdMode.NONE -> "Elegir adaptador"
                ObdMode.SIMULATOR -> "Simulador (demo)"
                ObdMode.BLUETOOTH -> config.name ?: config.address ?: "Adaptador"
            },
            if (config.mode == ObdMode.BLUETOOTH) "${config.address} · $status" else status,
            tone = if (tone == Tone.Bad) Tone.Warn else Tone.Neutral,
        ) { picker = true }
        if (config.mode != ObdMode.NONE) {
            SettingRow(P.Power, "Desconectar OBD", "Deja de buscar el adaptador y apaga la notificación", showChevron = false) { repo.disable() }
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

@Composable
private fun UpdateCard() {
    val ctx = LocalContext.current
    val c = ctx.container
    val savedRepo by c.launcherPrefs.githubRepo.collectAsStateWithLifecycle()
    val state by c.updater.state.collectAsStateWithLifecycle()
    var repo by remember(savedRepo) { mutableStateOf(savedRepo) }

    CivicCard(Modifier.fillMaxWidth()) {
        Text("Versión instalada: ${BuildConfig.VERSION_NAME}", style = Civic.type.label, color = Civic.colors.text)
        Text(
            "Busca nuevas versiones en GitHub. La pantalla necesita internet: conéctala al WiFi de tu casa.",
            style = Civic.type.bodySmall,
            color = Civic.colors.textDim,
        )
        Spacer(Modifier.size(Civic.space.m))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Civic.space.m)) {
            OutlinedTextField(
                value = repo,
                onValueChange = { repo = it },
                singleLine = true,
                label = { Text("Repositorio (usuario/repositorio)") },
                modifier = Modifier.weight(1f),
            )
            CivicButton("Buscar", {
                c.launcherPrefs.setGithubRepo(repo)
                c.updater.check(c.launcherPrefs.githubRepo.value)
            }, icon = P.ArrowsClockwise, enabled = state !is UpdateState.Checking && state !is UpdateState.Downloading)
        }
        Spacer(Modifier.size(Civic.space.m))
        when (val s = state) {
            UpdateState.Idle -> Unit
            UpdateState.Checking -> Text("Buscando…", style = Civic.type.bodySmall, color = Civic.colors.textDim)
            is UpdateState.UpToDate -> Text("Ya tienes la última versión (${s.latest}).", style = Civic.type.bodySmall, color = Civic.colors.ok)
            is UpdateState.Error -> Text(s.message, style = Civic.type.bodySmall, color = Civic.colors.bad)
            is UpdateState.Available -> {
                Text("Nueva versión ${s.version} (${s.sizeBytes / 1024} KB)", style = Civic.type.label, color = Civic.colors.accent)
                if (s.notes.isNotBlank()) Text(s.notes, style = Civic.type.bodySmall, color = Civic.colors.textDim)
                Spacer(Modifier.size(Civic.space.s))
                CivicButton("Descargar", { c.updater.download() }, icon = P.DownloadSimple)
            }
            is UpdateState.Downloading -> {
                Text("Descargando ${s.version}… ${(s.progress * 100).toInt()} %", style = Civic.type.bodySmall, color = Civic.colors.textDim)
                Spacer(Modifier.size(Civic.space.s))
                LinearMeter(s.progress, Tone.Accent)
            }
            is UpdateState.Ready -> {
                Text("Versión ${s.version} lista para instalar.", style = Civic.type.label, color = Civic.colors.ok)
                Spacer(Modifier.size(Civic.space.s))
                if (c.updater.canInstall()) {
                    CivicButton("Instalar", { c.updater.installIntent()?.let { runCatching { ctx.startActivity(it) } } }, icon = P.CheckCircle)
                } else {
                    Text(
                        "Primero permite que Civic instale apps (solo se pide una vez).",
                        style = Civic.type.bodySmall,
                        color = Civic.colors.textDim,
                    )
                    Spacer(Modifier.size(Civic.space.s))
                    CivicButton("Permitir", { runCatching { ctx.startActivity(c.updater.allowInstallsIntent()) } }, kind = ButtonKind.Secondary)
                }
            }
        }
    }
}
