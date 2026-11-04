package com.civiclauncher.app

import android.app.Application
import android.content.Context
import com.civiclauncher.app.diag.DiagPrefs
import com.civiclauncher.app.diag.DiagStore
import com.civiclauncher.app.diag.acc.AccLog
import com.civiclauncher.app.diag.acc.AccMonitorService
import com.civiclauncher.app.diag.autokit.OverlayProbe
import com.civiclauncher.app.launcher.data.AppsRepository
import com.civiclauncher.app.launcher.data.ClockRepository
import com.civiclauncher.app.launcher.data.GpsSpeedRepository
import com.civiclauncher.app.launcher.data.LauncherPrefs
import com.civiclauncher.app.launcher.obd.ObdMode
import com.civiclauncher.app.launcher.obd.ObdRepository
import com.civiclauncher.app.launcher.obd.ObdService
import com.civiclauncher.app.launcher.update.Updater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class CivicApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Cada vez que la app arranca (encendido, despertar, o si el sistema la cerró), reanuda el OBD.
        if (container.obd.config.value.mode != ObdMode.NONE) ObdService.start(this)
        // El arranque rápido de Autochips mata las apps al quitar la llave y no siempre las revive.
        // Cada vez que el proceso renace (por la pantalla, un aviso o el sistema), reanudamos el monitor.
        if (container.prefs.accMonitorEnabled) {
            container.accLog.log("Proceso de la app iniciado")
            runCatching { AccMonitorService.start(this, "proceso reiniciado") }
                .onFailure { container.accLog.log("No se pudo reanudar el monitor: ${it.message}") }
        }
    }
}

/**
 * Inyección de dependencias manual: un solo lugar donde se crean los objetos compartidos.
 * Todo es `lazy`: nada se crea hasta que una pantalla lo necesita, para arrancar rápido.
 */
class AppContainer(context: Context) {
    /** Vive lo mismo que la app. Para trabajo en segundo plano de los repositorios. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Launcher (Fase 2)
    val launcherPrefs by lazy { LauncherPrefs(context) }
    val clock by lazy { ClockRepository(context) }
    val gps by lazy { GpsSpeedRepository(context, appScope) }
    val apps by lazy { AppsRepository(context, appScope) }
    val updater by lazy { Updater(context, appScope) }

    // OBD (Fase 3)
    val obd by lazy { ObdRepository(context, appScope) }

    // Diagnóstico (Fase 0)
    val prefs = DiagPrefs(context)
    val diagStore by lazy { DiagStore(context) }
    val accLog by lazy { AccLog(context) }
    val overlayProbe by lazy { OverlayProbe(context, diagStore) }
}

val Context.container: AppContainer
    get() = (applicationContext as CivicApp).container
