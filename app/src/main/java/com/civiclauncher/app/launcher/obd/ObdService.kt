package com.civiclauncher.app.launcher.obd

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.civiclauncher.app.MainActivity
import com.civiclauncher.app.R
import com.civiclauncher.app.container
import com.civiclauncher.obd.EngineState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Servicio en primer plano: mantiene viva la conexión OBD aunque estés en Android Auto
 * (sin él, Android cerraría la app en segundo plano). Muestra una notificación discreta.
 */
class ObdService : Service() {
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        goForeground("Iniciando…")
        val repo = container.obd
        repo.setRunning(true)
        watcher = container.appScope.launch {
            repo.state.map(::describe).distinctUntilChanged().collect { goForeground(it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        watcher?.cancel()
        container.obd.setRunning(false)
        super.onDestroy()
    }

    private fun describe(s: EngineState): String = when (s) {
        EngineState.Stopped -> "En pausa (pantalla apagada)"
        is EngineState.Connecting -> "Conectando con el adaptador…"
        is EngineState.Detecting -> "Hablando con el carro…"
        is EngineState.Connected -> "Conectado · ${s.protocol}"
        is EngineState.CarOff -> "Carro apagado · esperando la llave"
        is EngineState.Reconnecting -> "Reconectando en ${s.retryInMs / 1000} s"
    }

    private fun goForeground(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Conexión OBD", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_fg)
            .setContentTitle("Civic · OBD")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification,
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
        )
    }

    companion object {
        private const val CHANNEL = "obd"
        private const val NOTIFICATION_ID = 7

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, ObdService::class.java)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ObdService::class.java))
        }
    }
}
