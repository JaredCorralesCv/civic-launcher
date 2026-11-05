package com.civiclauncher.app.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.civiclauncher.app.MainActivity

/**
 * Pone Civic al frente cuando se enciende el carro.
 *
 * Al quitar la llave, el arranque rápido de Autochips (QuickBootReceiver.switchToLauncher) abre a la
 * fuerza el launcher original (com.android.launcher3/.testing.LauncherExtension), así que al volver a
 * encender eso es lo que se ve. Civic sigue vivo mientras el carro está apagado (el sistema lo revive
 * como launcher), así que escuchamos el aviso de encendido y volvemos a Inicio.
 *
 * Los avisos son implícitos: en Android 8 solo llegan a un receptor registrado en código, no en el
 * manifiesto. Si Android Auto se abre solo al conectar el celular, llega después y queda encima.
 */
object WakeWatcher {
    /** El arranque rápido terminó de encender. */
    private const val QB_POWERON = "autochips.intent.action.QB_POWERON"
    /** La llave (ACC) cambió; el extra AccStatus dice si se encendió. */
    private const val MCU_ACC = "android.mcu.device.action.ACC"

    private var registered = false
    private var lastLaunch = 0L

    fun register(context: Context) {
        if (registered) return
        val app = context.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val on = when (intent.action) {
                    QB_POWERON -> true
                    MCU_ACC -> intent.getBooleanExtra("AccStatus", false)
                    else -> false
                }
                if (on) bringToFront(app)
            }
        }
        val filter = IntentFilter().apply {
            addAction(QB_POWERON)
            addAction(MCU_ACC)
        }
        // Exportado: los avisos vienen del sistema y del MCU, no de Civic.
        registered = runCatching {
            androidx.core.content.ContextCompat.registerReceiver(app, receiver, filter, androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
            true
        }.getOrDefault(false)
    }

    private fun bringToFront(context: Context) {
        // Los dos avisos llegan casi juntos: un solo salto a Inicio.
        val now = SystemClock.elapsedRealtime()
        if (now - lastLaunch < 5_000) return
        lastLaunch = now
        // Un instante después, para quedar encima del launcher original cuando este termine de volver.
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching {
                context.startActivity(
                    Intent(context, MainActivity::class.java)
                        .setAction(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
                )
            }
        }, 400)
    }
}
