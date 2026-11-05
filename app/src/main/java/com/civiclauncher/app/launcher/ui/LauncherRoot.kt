package com.civiclauncher.app.launcher.ui

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.civiclauncher.app.container
import com.civiclauncher.app.diag.DiagApp
import com.civiclauncher.app.launcher.data.isNight
import com.civiclauncher.app.ui.CivicTheme
import com.civiclauncher.app.ui.design.Civic
import com.civiclauncher.app.ui.design.CivicDesignTheme
import com.civiclauncher.app.ui.design.CivicIconButton
import com.civiclauncher.app.ui.design.DockItem
import com.civiclauncher.app.ui.design.PhosphorIcons as P
import kotlinx.coroutines.flow.Flow
import java.util.Calendar

enum class Dest { HOME, VEHICLE, MAP, APPS, SETTINGS, CLEANUP, DIAG }

/**
 * Raíz del launcher: dock lateral + pantalla activa.
 * [homePresses] avisa cuando se presiona el botón de inicio estando ya en Civic: regresamos a Inicio.
 */
@Composable
fun LauncherRoot(homePresses: Flow<Unit>) {
    val ctx = LocalContext.current
    val c = ctx.container
    val themeMode by c.launcherPrefs.themeMode.collectAsStateWithLifecycle()
    val now by c.clock.now.collectAsStateWithLifecycle()
    val hour = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)
    var dest by rememberSaveable { mutableStateOf(Dest.HOME) }

    LaunchedEffect(Unit) { homePresses.collect { dest = Dest.HOME } }
    // En un launcher, "atrás" nunca debe sacarte de la pantalla de inicio.
    BackHandler {
        dest = when (dest) {
            Dest.CLEANUP -> Dest.SETTINGS
            else -> Dest.HOME
        }
    }

    // Pedimos la ubicación una sola vez para el velocímetro GPS.
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        c.gps.onPermissionChanged()
    }
    LaunchedEffect(Unit) {
        if (!c.gps.hasPermission() && !c.launcherPrefs.askedLocation) {
            c.launcherPrefs.askedLocation = true
            locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    if (dest == Dest.DIAG) {
        DiagHost(onBack = { dest = Dest.SETTINGS })
        return
    }

    val night = themeMode.isNight(hour)
    CivicDesignTheme(night = night) {
        Column(Modifier.fillMaxSize().background(Civic.colors.background)) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (dest) {
                    Dest.HOME -> HomeScreen(onOpen = { dest = it })
                    Dest.VEHICLE -> VehicleScreen()
                    Dest.MAP -> com.civiclauncher.app.launcher.map.MapScreen(night = night)
                    Dest.APPS -> AppsScreen()
                    Dest.SETTINGS -> SettingsScreen(
                        onOpenDiagnostics = { dest = Dest.DIAG },
                        onOpenCleanup = { dest = Dest.CLEANUP },
                    )
                    Dest.CLEANUP -> CleanupScreen(onBack = { dest = Dest.SETTINGS })
                    Dest.DIAG -> Unit
                }
            }
            BottomBar(dest) { dest = it }
        }
    }
}

/** Herramienta de diagnóstico de la Fase 0, dentro de Ajustes. */
@Composable
private fun DiagHost(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    CivicTheme {
        Column(Modifier.fillMaxSize().background(Civic.colors.background)) {
            Row(
                Modifier.fillMaxWidth().background(Civic.colors.surface).padding(horizontal = Civic.space.m, vertical = Civic.space.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CivicIconButton(P.CaretLeft, "Volver a Ajustes", onBack)
                Spacer(Modifier.width(Civic.space.m))
                Text("Diagnóstico", style = Civic.type.title, color = Civic.colors.text)
            }
            Box(Modifier.weight(1f)) { DiagApp() }
        }
    }
}
