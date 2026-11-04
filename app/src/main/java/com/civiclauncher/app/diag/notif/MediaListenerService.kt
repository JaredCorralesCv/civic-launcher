package com.civiclauncher.app.diag.notif

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat
import com.civiclauncher.app.diag.ReportSection
import com.civiclauncher.app.diag.Row
import com.civiclauncher.app.diag.Status

/**
 * Servicio vacío por ahora. Su única función en la Fase 0 es existir: con este permiso
 * Android nos deja leer qué app reproduce música (Fase 3).
 */
class MediaListenerService : NotificationListenerService()

object NotificationCollector {

    fun component(context: Context) = ComponentName(context, MediaListenerService::class.java)

    fun settingsIntent() = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun settingsAvailable(context: Context): Boolean =
        settingsIntent().resolveActivity(context.packageManager) != null

    fun isEnabled(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    fun activeSessions(context: Context): List<String> {
        if (!isEnabled(context)) return emptyList()
        return runCatching {
            val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            msm.getActiveSessions(component(context)).map { c -> "${c.packageName} · ${stateName(c.playbackState?.state)}" }
        }.getOrElse { listOf("Error: ${it.message}") }
    }

    fun adbCommands(context: Context): List<String> {
        val flat = component(context).flattenToShortString()
        return listOf(
            "adb shell cmd notification allow_listener $flat",
            "adb shell settings put secure enabled_notification_listeners $flat",
        )
    }

    fun rootCommand(context: Context): String = "cmd notification allow_listener ${component(context).flattenToShortString()}"

    private fun stateName(s: Int?) = when (s) {
        PlaybackState.STATE_PLAYING -> "reproduciendo"
        PlaybackState.STATE_PAUSED -> "en pausa"
        PlaybackState.STATE_STOPPED -> "detenida"
        PlaybackState.STATE_BUFFERING -> "cargando"
        null -> "sin estado"
        else -> "estado $s"
    }

    fun collect(context: Context): ReportSection {
        val available = settingsAvailable(context)
        val enabled = isEnabled(context)
        val sessions = activeSessions(context)
        val rows = mutableListOf(
            Row(
                "Pantalla de ajuste de notificaciones",
                if (available) "Existe" else "El fabricante la quitó",
                if (available) Status.OK else Status.WARN,
            ),
            Row("Permiso concedido a Civic", if (enabled) "Sí" else "No", if (enabled) Status.OK else Status.WARN),
            Row(
                "Lectura de música (sesiones activas)",
                if (!enabled) "Requiere el permiso" else if (sessions.isEmpty()) "Funciona (no hay música sonando)" else "Funciona: ${sessions.size}",
                if (enabled) Status.OK else Status.INFO,
            ),
        )
        sessions.forEach { rows += Row("  · sesión", it) }
        val notes = if (!available) listOf("Plan B con adb o root:") + adbCommands(context) else emptyList()
        return ReportSection("Música / notificaciones", rows, notes)
    }
}
