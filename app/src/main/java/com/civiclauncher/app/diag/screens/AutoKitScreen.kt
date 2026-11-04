package com.civiclauncher.app.diag.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.civiclauncher.app.container
import android.view.KeyEvent
import com.civiclauncher.app.diag.autokit.AutoKitCollector
import com.civiclauncher.app.diag.autokit.MediaKeys
import com.civiclauncher.app.diag.autokit.OverlayProbe
import com.civiclauncher.app.diag.notif.NotificationCollector
import com.civiclauncher.app.ui.ActionButton
import com.civiclauncher.app.ui.ButtonKind
import com.civiclauncher.app.ui.Card
import com.civiclauncher.app.ui.CivicColors
import com.civiclauncher.app.ui.InfoCard
import com.civiclauncher.app.ui.Note
import com.civiclauncher.app.ui.SectionScaffold
import com.civiclauncher.app.ui.produceAsync
import com.civiclauncher.app.ui.sectionCard
import com.civiclauncher.app.ui.startActivitySafe
import com.civiclauncher.app.ui.toast
import kotlinx.coroutines.delay

@Composable
fun AutoKitScreen() {
    val ctx = LocalContext.current
    val c = ctx.container
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    // Se actualiza cada 2 s para ver la canción cambiar en vivo.
    LaunchedEffect(Unit) {
        while (true) {
            delay(2_000)
            refresh++
        }
    }
    val section = produceAsync(refresh) { AutoKitCollector.collect(ctx) }
    val sessions = produceAsync(refresh) { AutoKitCollector.sessions(ctx) }.orEmpty()
    val overlayRunning by c.overlayProbe.running.collectAsStateWithLifecycle()
    val results by c.diagStore.results.collectAsStateWithLifecycle()

    SectionScaffold(
        title = "AutoKit / Android Auto",
        subtitle = "Como usas Maps y YouTube Music por Android Auto, el launcher se integra con AutoKit en lugar de abrir esas apps.",
        actions = {
            ActionButton("Abrir AutoKit", {
                val i = AutoKitCollector.launchIntent(ctx)
                if (i == null) ctx.toast("AutoKit no está instalado") else ctx.startActivitySafe(i)
            })
            if (!AutoKitCollector.canDrawOverlays(ctx)) {
                ActionButton("Permitir ventana flotante", { ctx.startActivitySafe(AutoKitCollector.overlaySettingsIntent(ctx)) }, kind = ButtonKind.Secondary)
            } else if (!overlayRunning) {
                ActionButton("Burbuja sobre Android Auto (2 min)", {
                    if (c.overlayProbe.start()) {
                        AutoKitCollector.launchIntent(ctx)?.let { ctx.startActivitySafe(it) }
                    } else {
                        ctx.toast("No se pudo mostrar la burbuja")
                    }
                }, kind = ButtonKind.Secondary)
            } else {
                ActionButton("Quitar burbuja", { c.overlayProbe.stop() }, kind = ButtonKind.Danger)
            }
            if (!NotificationCollector.isEnabled(ctx)) {
                ActionButton("Permiso de música", { ctx.startActivitySafe(NotificationCollector.settingsIntent()) }, kind = ButtonKind.Secondary)
            }
        },
    ) {
        sectionCard(section)
        results[OverlayProbe.KEY]?.let { s -> item { InfoCard(title = s.title, rows = s.rows, notes = s.notes) } }
        item { Note("Sesiones de música en vivo (los botones mandan la orden a esa app)") }
        if (sessions.isEmpty()) {
            item { Card { Note("No hay sesiones. Conecta el celular, pon música en Android Auto y regresa a esta pantalla.") } }
        }
        items(sessions, key = { it.pkg }) { s ->
            Card {
                Text(
                    if (s.pkg == AutoKitCollector.PACKAGE) "AutoKit (Android Auto)" else s.pkg,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "${s.state} · ${s.title ?: "sin título"}${s.artist?.let { " — $it" } ?: ""}",
                    color = CivicColors.TextDim,
                )
                Text("Acepta: ${s.actions.joinToString().ifEmpty { "nada declarado" }}", color = CivicColors.TextDim)
                Spacer(Modifier.height(10.dp))
                Note("A · Órdenes directas")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val t = s.controller.transportControls
                    ActionButton("A⏮", { runCatching { t.skipToPrevious() } }, kind = ButtonKind.Secondary)
                    ActionButton("A▶", { runCatching { t.play() } }, kind = ButtonKind.Secondary)
                    ActionButton("A⏸", { runCatching { t.pause() } }, kind = ButtonKind.Secondary)
                    ActionButton("A⏭", { runCatching { t.skipToNext() } }, kind = ButtonKind.Secondary)
                }
                Spacer(Modifier.height(8.dp))
                // Muchas apps de proyección solo entienden las teclas físicas (como las del volante).
                Note("B · Teclas multimedia a esta app")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ActionButton("B⏮", { MediaKeys.toSession(s.controller, KeyEvent.KEYCODE_MEDIA_PREVIOUS) }, kind = ButtonKind.Secondary)
                    ActionButton("B⏯", { MediaKeys.toSession(s.controller, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }, kind = ButtonKind.Secondary)
                    ActionButton("B⏭", { MediaKeys.toSession(s.controller, KeyEvent.KEYCODE_MEDIA_NEXT) }, kind = ButtonKind.Secondary)
                }
            }
        }
        item {
            Card {
                Note("C · Teclas multimedia globales (como si fueran los botones del volante)")
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ActionButton("C⏮", { MediaKeys.global(ctx, KeyEvent.KEYCODE_MEDIA_PREVIOUS) }, kind = ButtonKind.Secondary)
                    ActionButton("C⏯", { MediaKeys.global(ctx, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }, kind = ButtonKind.Secondary)
                    ActionButton("C⏭", { MediaKeys.global(ctx, KeyEvent.KEYCODE_MEDIA_NEXT) }, kind = ButtonKind.Secondary)
                }
            }
        }
        item {
            InfoCard(
                title = "Cómo probar",
                rows = emptyList(),
                notes = listOf(
                    "1. Conecta el celular y pon música en YouTube Music por Android Auto.",
                    "2. Regresa aquí: debe aparecer \"AutoKit (Android Auto)\" con la canción.",
                    "3. Prueba los grupos A, B y C. Anota cuáles botones sí hacen algo (por ejemplo: \"B⏯ pausa, C⏮ regresa\").",
                    "   El botón ⏮ suele reiniciar la canción la primera vez; tócalo dos veces seguidas para ir a la anterior.",
                    "4. Toca \"Burbuja sobre Android Auto\". Se abrirá AutoKit con una burbuja arriba a la derecha.",
                    "5. Usa Android Auto normal 2 minutos (Maps y música) y regresa. Dime si la burbuja se veía y si Android Auto se sintió más lento.",
                ),
            )
        }
    }
}
