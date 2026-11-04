package com.civiclauncher.app.diag.collect

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.civiclauncher.app.diag.ReportSection
import com.civiclauncher.app.diag.Row
import com.civiclauncher.app.diag.Status

data class AppEntry(
    val label: String,
    val pkg: String,
    val version: String,
    val system: Boolean,
    val updatedSystem: Boolean,
    val enabled: Boolean,
    val hasLauncherIcon: Boolean,
    val isHome: Boolean,
    val hint: String?,
)

data class AppsReport(val apps: List<AppEntry>, val homePackages: List<String>, val defaultHome: String?)

object AppsCollector {

    /** Pistas para reconocer las apps del fabricante por su nombre de paquete o etiqueta. */
    private val HINTS = listOf(
        "Radio" to listOf("radio", "fm", "tuner"),
        "Bluetooth / llamadas" to listOf("bt", "bluetooth", "phone", "dial", "handsfree"),
        "CAN / MCU (comunicación con el carro)" to listOf("canbus", "can.", "mcu", "carinfo", "carservice", "vehicle"),
        "Cámara / reversa" to listOf("dvr", "camera", "avin", "reverse", "backcar", "aux"),
        "Launcher" to listOf("launcher", "home"),
        "Música / video" to listOf("music", "media", "video", "player"),
        "Navegación" to listOf("navi", "gps", "map"),
        "Ajustes del fabricante" to listOf("setting", "factory", "carsetting", "config"),
        "Proyección de celular" to listOf("carlife", "carplay", "autolink", "zlink", "easyconnect", "mirror"),
        "Actualización" to listOf("update", "ota", "upgrade"),
    )

    fun hintFor(pkg: String, label: String): String? {
        val text = "$pkg $label".lowercase()
        return HINTS.firstOrNull { (_, keys) -> keys.any { text.contains(it) } }?.first
    }

    fun homeIntent(): Intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)

    fun defaultHome(context: Context): String? {
        val ri = context.packageManager.resolveActivity(homeIntent(), PackageManager.MATCH_DEFAULT_ONLY) ?: return null
        val pkg = ri.activityInfo?.packageName ?: return null
        // "android" significa que el sistema muestra el selector: no hay predeterminado.
        return if (pkg == "android" || ri.activityInfo.name.contains("Resolver")) null else pkg
    }

    @Suppress("DEPRECATION")
    fun collect(context: Context): AppsReport {
        val pm = context.packageManager
        val launcherPkgs = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName }.toSet()
        val homePkgs = pm.queryIntentActivities(homeIntent(), 0).map { it.activityInfo.packageName }.distinct()
        val apps = pm.getInstalledPackages(0).mapNotNull { p ->
            val ai = p.applicationInfo ?: return@mapNotNull null
            val label = runCatching { ai.loadLabel(pm).toString() }.getOrDefault(p.packageName)
            AppEntry(
                label = label,
                pkg = p.packageName,
                version = p.versionName ?: "—",
                system = ai.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                updatedSystem = ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0,
                enabled = ai.enabled,
                hasLauncherIcon = p.packageName in launcherPkgs,
                isHome = p.packageName in homePkgs,
                hint = hintFor(p.packageName, label),
            )
        }.sortedWith(compareBy<AppEntry> { it.hint == null }.thenBy { !it.system }.thenBy { it.label.lowercase() })
        return AppsReport(apps, homePkgs, defaultHome(context))
    }

    fun toSection(r: AppsReport): ReportSection {
        val rows = mutableListOf(
            Row("Total de apps", r.apps.size.toString()),
            Row("Del sistema", r.apps.count { it.system }.toString()),
            Row("Instaladas por ti", r.apps.count { !it.system }.toString()),
            Row("Desactivadas", r.apps.count { !it.enabled }.toString()),
            Row("Launcher predeterminado", r.defaultHome ?: "Ninguno (el sistema pregunta)"),
            Row("Apps que pueden ser launcher", r.homePackages.joinToString()),
        )
        val notes = r.apps.map { a ->
            listOf(
                a.pkg,
                a.label,
                "v${a.version}",
                if (a.system) (if (a.updatedSystem) "sistema-actualizada" else "sistema") else "instalada",
                if (!a.enabled) "DESACTIVADA" else "",
                if (a.hasLauncherIcon) "icono" else "",
                if (a.isHome) "HOME" else "",
                a.hint ?: "",
            ).filter { it.isNotEmpty() }.joinToString(" | ")
        }
        return ReportSection("Apps instaladas", rows, notes)
    }

    // ----- Launcher -----

    fun launcherSection(context: Context): ReportSection {
        val pm = context.packageManager
        val homes = pm.queryIntentActivities(homeIntent(), 0)
        val def = defaultHome(context)
        val rows = mutableListOf(
            Row("Launcher predeterminado", def ?: "Ninguno (el sistema pregunta)"),
            Row(
                "Civic es el predeterminado",
                if (def == context.packageName) "Sí" else "No",
                if (def == context.packageName) Status.OK else Status.INFO,
            ),
        )
        homes.forEach { ri ->
            rows += Row("  · ${runCatching { ri.loadLabel(pm).toString() }.getOrDefault("?")}", "${ri.activityInfo.packageName}/${ri.activityInfo.name}")
        }
        return ReportSection("Launcher", rows)
    }
}
