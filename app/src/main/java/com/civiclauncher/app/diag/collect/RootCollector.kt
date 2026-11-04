package com.civiclauncher.app.diag.collect

import android.content.Context
import android.provider.Settings
import com.civiclauncher.app.diag.DiagPrefs
import com.civiclauncher.app.diag.ReportSection
import com.civiclauncher.app.diag.Row
import com.civiclauncher.app.diag.Status
import com.civiclauncher.app.diag.util.Shell
import com.civiclauncher.app.diag.util.SysProp
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

object RootCollector {

    fun suPathsFound(): List<String> = Shell.SU_PATHS.filter { runCatching { File(it).exists() }.getOrDefault(false) }

    fun ipv4Addresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { ni -> ni.inetAddresses.toList().filterIsInstance<Inet4Address>().map { "${ni.name}: ${it.hostAddress}" } }
    }.getOrDefault(emptyList())

    fun adbTcpPort(): String = SysProp.get("service.adb.tcp.port").ifEmpty { SysProp.get("persist.adb.tcp.port") }

    fun collect(context: Context, prefs: DiagPrefs): ReportSection {
        val paths = suPathsFound()
        val which = Shell.sh("command -v su || which su", 3_000).stdout
        val adbSecure = SysProp.get("ro.adb.secure")
        val adbOn = runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1 }.getOrDefault(false)
        val devOn = runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1 }.getOrDefault(false)
        val port = adbTcpPort()
        val rows = listOf(
            Row("Archivo su", if (paths.isEmpty()) "No encontrado" else paths.joinToString(), if (paths.isNotEmpty()) Status.OK else Status.INFO),
            Row("su en PATH", which.ifEmpty { "No" }),
            Row("Root confirmado (prueba)", if (prefs.rootOk) "Sí" else "No probado o sin root", if (prefs.rootOk) Status.OK else Status.INFO),
            Row("Tipo de compilación", "${SysProp.getOr("ro.build.type")} / ${SysProp.getOr("ro.build.tags")}"),
            Row("ro.debuggable", SysProp.getOr("ro.debuggable")),
            Row("ro.secure", SysProp.getOr("ro.secure")),
            Row(
                "ro.adb.secure",
                adbSecure.ifEmpty { "—" } + if (adbSecure == "0") " (adb NO pide autorización)" else "",
                if (adbSecure == "0") Status.WARN else Status.INFO,
            ),
            Row("Opciones de desarrollador", if (devOn) "Activadas" else "Desactivadas"),
            Row("Depuración USB (adb)", if (adbOn) "Activada" else "Desactivada"),
            Row("adb por WiFi (puerto)", port.ifEmpty { "apagado" }, if (port == "5555") Status.WARN else Status.INFO),
            Row("Configuración USB", "${SysProp.getOr("sys.usb.config")} (persist: ${SysProp.getOr("persist.sys.usb.config")})"),
            Row("Direcciones IP", ipv4Addresses().joinToString().ifEmpty { "Sin red" }),
        )
        val notes = buildList {
            if (adbSecure == "0") add("Con adb por WiFi encendido, cualquiera en la misma red tendría acceso total. Apágalo al terminar de programar.")
        }
        return ReportSection("Root y ADB", rows, notes)
    }

    fun testRoot(): Shell.Result = Shell.su("id")

    fun enableAdbWifi(): Shell.Result = Shell.su("setprop service.adb.tcp.port 5555\nstop adbd\nstart adbd")

    fun disableAdbWifi(): Shell.Result = Shell.su("setprop service.adb.tcp.port -1\nstop adbd\nstart adbd")
}
