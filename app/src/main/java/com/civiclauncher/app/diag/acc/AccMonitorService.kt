package com.civiclauncher.app.diag.acc

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.civiclauncher.app.MainActivity
import com.civiclauncher.app.R
import com.civiclauncher.app.container
import com.civiclauncher.app.diag.util.Shell
import com.civiclauncher.app.diag.util.formatDuration
import kotlin.concurrent.thread

/**
 * Monitor de la llave (ACC). Registra:
 *  - avisos de pantalla encendida/apagada, corriente y apagado ordenado,
 *  - "latidos" cada 5 s: si entre dos latidos el reloj total avanza más que el reloj activo,
 *    la pantalla estuvo en suspensión profunda (así se detecta aunque no haya aviso),
 *  - reinicios completos y cierres del servicio por el sistema,
 *  - con root: los avisos propios del fabricante (dumpsys activity broadcasts).
 */
class AccMonitorService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var lastElapsed = 0L
    private var lastUptime = 0L

    private val log get() = container.accLog
    private val prefs get() = container.prefs

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return
            log.log("Aviso: ${label(action)}")
            if (action == Intent.ACTION_SCREEN_ON) discoverVendorActions("pantalla encendida")
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            heartbeat()
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        goForeground()
        checkGapSinceLastRun()
        val filter = IntentFilter().apply {
            WATCHED.keys.forEach { addAction(it) }
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        lastElapsed = SystemClock.elapsedRealtime()
        lastUptime = SystemClock.uptimeMillis()
        handler.postDelayed(tick, TICK_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val reason = intent?.getStringExtra(EXTRA_REASON) ?: "reinicio automático del servicio"
        log.log("Monitor activo ($reason)")
        if (reason.contains("arranque", ignoreCase = true)) discoverVendorActions("arranque")
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        runCatching { unregisterReceiver(receiver) }
        log.log("Monitor detenido")
        super.onDestroy()
    }

    private fun heartbeat() {
        val e = SystemClock.elapsedRealtime()
        val u = SystemClock.uptimeMillis()
        val totalDelta = e - lastElapsed
        val slept = totalDelta - (u - lastUptime)
        when {
            slept > SLEEP_THRESHOLD_MS -> {
                log.log("SUSPENSIÓN PROFUNDA: la pantalla durmió ~${formatDuration(slept)} y despertó")
                discoverVendorActions("despertó de suspensión")
            }
            totalDelta > TICK_MS * 4 -> log.log("PAUSA: el sistema congeló la app ~${formatDuration(totalDelta)}")
        }
        lastElapsed = e
        lastUptime = u
        prefs.saveHeartbeat(System.currentTimeMillis(), e)
    }

    private fun checkGapSinceLastRun() {
        val lastWall = prefs.lastHeartbeatWall
        if (lastWall == 0L) return
        val nowElapsed = SystemClock.elapsedRealtime()
        val lastElapsedSaved = prefs.lastHeartbeatElapsed
        if (nowElapsed < lastElapsedSaved) {
            log.log(
                "REINICIO COMPLETO detectado. Último latido: ${log.formatWall(lastWall)} " +
                    "(hace ${formatDuration(System.currentTimeMillis() - lastWall)}). La pantalla se apagó por completo.",
            )
        } else {
            val gap = nowElapsed - lastElapsedSaved
            if (gap > TICK_MS * 4) log.log("El monitor estuvo cerrado ~${formatDuration(gap)} sin reinicio (el sistema lo cerró)")
        }
    }

    /** Solo con root: busca en el historial de avisos del sistema los que no son estándar de Android. */
    private fun discoverVendorActions(reason: String) {
        if (!prefs.rootOk) return
        thread(isDaemon = true, name = "acc-discover") {
            val r = Shell.su("dumpsys activity broadcasts", 15_000)
            if (!r.ok) {
                log.log("No se pudo leer el historial de avisos (${r.summary()})")
                return@thread
            }
            val actions = Regex("act=([A-Za-z0-9_.\\-]+)").findAll(r.stdout).map { it.groupValues[1] }.toSet()
            val interesting = actions.filter { a ->
                val upper = a.uppercase()
                val standard = a.startsWith("android.") || a.startsWith("com.android.") || a.startsWith("com.google.")
                !standard || KEYWORDS.any { upper.contains(it) }
            }.toSet()
            val known = prefs.knownActions
            val fresh = interesting - known
            if (fresh.isNotEmpty()) {
                prefs.knownActions = known + fresh
                log.log("Avisos nuevos del sistema ($reason): ${fresh.sorted().joinToString()}")
            }
        }
    }

    private fun goForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Monitor de la llave", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_fg)
            .setContentTitle("Civic: monitor de la llave activo")
            .setContentText("Registrando encendido y apagado de la pantalla")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
    }

    companion object {
        private const val CHANNEL = "acc_monitor"
        private const val NOTIFICATION_ID = 42
        private const val TICK_MS = 5_000L
        private const val SLEEP_THRESHOLD_MS = 3_000L
        private const val EXTRA_REASON = "reason"
        private val KEYWORDS = listOf("ACC", "MCU", "SLEEP", "STANDBY", "POWER", "WAKE", "ILL", "CANBUS", "BOOT", "SHUTDOWN", "KEY")

        private val WATCHED = mapOf(
            Intent.ACTION_SCREEN_ON to "pantalla encendida",
            Intent.ACTION_SCREEN_OFF to "pantalla apagada",
            Intent.ACTION_USER_PRESENT to "usuario presente",
            Intent.ACTION_POWER_CONNECTED to "corriente conectada",
            Intent.ACTION_POWER_DISCONNECTED to "corriente desconectada",
            Intent.ACTION_SHUTDOWN to "apagado ordenado",
            Intent.ACTION_DREAMING_STARTED to "protector de pantalla iniciado",
            Intent.ACTION_DREAMING_STOPPED to "protector de pantalla detenido",
        )

        fun label(action: String): String = WATCHED[action] ?: action

        fun start(context: Context, reason: String) {
            val i = Intent(context, AccMonitorService::class.java).putExtra(EXTRA_REASON, reason)
            ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AccMonitorService::class.java))
        }
    }
}
