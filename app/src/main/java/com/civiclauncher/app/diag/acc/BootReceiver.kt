package com.civiclauncher.app.diag.acc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.civiclauncher.app.container

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val c = context.container
        val label = when (action) {
            Intent.ACTION_BOOT_COMPLETED -> "arranque completado"
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> "arranque (bloqueado) completado"
            Intent.ACTION_SHUTDOWN -> "apagado ordenado"
            Intent.ACTION_MY_PACKAGE_REPLACED -> "app actualizada"
            else -> "arranque rápido ($action)"
        }
        if (!c.prefs.accMonitorEnabled) return
        c.accLog.log("Aviso del sistema: $label")
        if (action == Intent.ACTION_SHUTDOWN) return
        runCatching { AccMonitorService.start(context, "arranque: $label") }
            .onFailure { c.accLog.log("No se pudo iniciar el monitor: ${it.message}") }
    }
}
