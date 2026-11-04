package com.civiclauncher.app.launcher.ui

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
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
fun SettingsScreen(onOpenDiagnostics: () -> Unit) {
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

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Civic.space.xl),
        verticalArrangement = Arrangement.spacedBy(Civic.space.s),
    ) {
        item { Text("Ajustes", style = Civic.type.headline, color = Civic.colors.text) }

        item { SectionHeader("Apariencia") }
        item {
            CivicCard(Modifier.fillMaxWidth()) {
                Text("Tema", style = Civic.type.label, color = Civic.colors.text)
                Text(
                    "Automático: noche de 19:00 a 7:00, día el resto del tiempo.",
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

        item { SectionHeader("Fecha y hora") }
        item {
            SettingRow(
                P.Clock,
                "Zona horaria",
                if (tz == "Asia/Shanghai") "$tz · la pantalla venía con la hora de China. Cámbiala a la de tu ciudad." else tz,
                tone = if (tz == "Asia/Shanghai") Tone.Warn else Tone.Neutral,
            ) { open(Intent(Settings.ACTION_DATE_SETTINGS)) }
        }

        item { SectionHeader("Carro") }
        item { SettingRow(P.SteeringWheel, "Botones del volante", "App del fabricante para aprender los botones (SWC)") { openVendor(VendorApps.STEERING_WHEEL, "SWC") } }
        item { SettingRow(P.CarProfile, "Configuraciones del vehículo", "Ajustes del fabricante para el carro") { openVendor(VendorApps.CAR_SETTINGS, "La app") } }
        item { SettingRow(P.Cpu, "CAN del vehículo", "Para ver si hay caja decodificadora CAN instalada") { openVendor(VendorApps.CAN, "Can Vehiculo") } }

        item { SectionHeader("Sistema") }
        item {
            SettingRow(P.House, "Launcher predeterminado", "Elige Civic o el launcher del fabricante") {
                open(Intent(Settings.ACTION_HOME_SETTINGS))
            }
        }
        item { SettingRow(P.GearSix, "Ajustes de Android", "Configuración general de la pantalla") { open(Intent(Settings.ACTION_SETTINGS)) } }
        item { SettingRow(P.WifiHigh, "WiFi", "Para buscar actualizaciones con el WiFi de tu casa") { open(Intent(Settings.ACTION_WIFI_SETTINGS)) } }

        item { SectionHeader("Actualizaciones") }
        item { UpdateCard() }

        item { SectionHeader("Avanzado") }
        item { SettingRow(P.Wrench, "Diagnóstico", "Las pruebas de hardware de la Fase 0") { onOpenDiagnostics() } }
        item {
            SettingRow(
                P.Info,
                "Acerca de",
                "Civic Launcher ${BuildConfig.VERSION_NAME} · Inter (SIL OFL) · Phosphor Icons (MIT)",
                showChevron = false,
            ) { }
        }
    }
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
