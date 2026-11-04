package com.civiclauncher.app.diag.export

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.civiclauncher.app.BuildConfig
import com.civiclauncher.app.container
import com.civiclauncher.app.diag.ReportSection
import com.civiclauncher.app.diag.ResultKeys
import com.civiclauncher.app.diag.Row
import com.civiclauncher.app.diag.Status
import com.civiclauncher.app.diag.autokit.AutoKitCollector
import com.civiclauncher.app.diag.autokit.OverlayProbe
import com.civiclauncher.app.diag.collect.AppsCollector
import com.civiclauncher.app.diag.collect.BluetoothCollector
import com.civiclauncher.app.diag.collect.GpsCollector
import com.civiclauncher.app.diag.collect.RootCollector
import com.civiclauncher.app.diag.collect.Spp
import com.civiclauncher.app.diag.collect.SystemCollector
import com.civiclauncher.app.diag.collect.UsbCollector
import com.civiclauncher.app.diag.collect.hasPermissions
import com.civiclauncher.app.diag.notif.NotificationCollector
import com.civiclauncher.app.diag.util.SysProp
import com.civiclauncher.app.diag.util.formatBytes
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Resumen de una sola vista con lo más importante de cada prueba. */
object SummaryCollector {
    fun rows(context: Context): List<Row> {
        val c = context.container
        val results = c.diagStore.results.value
        val sdk = Build.VERSION.SDK_INT
        val screen = SystemCollector.screen(context)
        val ram = SystemCollector.totalRamBytes(context)
        val gpu = SystemCollector.gpu()
        val btAdapter = BluetoothCollector.adapter(context) != null
        val btScan = results[ResultKeys.BT_SCAN]?.rows?.firstOrNull { it.key == "Dispositivos encontrados" }
        val gps = results[ResultKeys.GPS]?.rows?.firstOrNull { it.key == "Ubicación obtenida" }
        val spp = results[Spp.RESULT_KEY]?.rows?.firstOrNull()
        val home = AppsCollector.defaultHome(context)
        val perfLight = results[ResultKeys.PERF_LIGHT]?.rows?.firstOrNull()
        val perfHeavy = results[ResultKeys.PERF_HEAVY]?.rows?.firstOrNull()
        val perfLightAot = results[ResultKeys.PERF_LIGHT + "_aot"]?.rows?.firstOrNull()
        val perfHeavyAot = results[ResultKeys.PERF_HEAVY + "_aot"]?.rows?.firstOrNull()
        val hasUsbHost = context.packageManager.hasSystemFeature("android.hardware.usb.host")

        return listOf(
            Row(
                "Android real",
                "${SystemCollector.androidNameForSdk(sdk)} (API $sdk)" + if (SystemCollector.isVersionSpoofed()) " · Configuración dice ${Build.VERSION.RELEASE}" else "",
                if (SystemCollector.isVersionSpoofed()) Status.WARN else Status.OK,
            ),
            Row("Chip", SystemCollector.chipName()),
            Row("Pantalla", "${screen.widthPx}×${screen.heightPx} · ${screen.densityDpi} dpi · ${"%.0f".format(screen.refreshHz)} Hz"),
            Row("RAM", formatBytes(ram), if (ram < 1_600_000_000L) Status.WARN else Status.OK),
            Row("GPU", gpu?.renderer ?: "—"),
            Row(
                "Bluetooth para apps",
                when {
                    !btAdapter -> "No accesible"
                    btScan == null -> "Accesible · falta la prueba de escaneo"
                    else -> "Escaneo: ${btScan.value} dispositivos"
                },
                when {
                    !btAdapter -> Status.BAD
                    btScan == null -> Status.INFO
                    else -> btScan.status
                },
            ),
            Row("USB host", if (hasUsbHost) "Sí" else "No declarado", if (hasUsbHost) Status.OK else Status.WARN),
            Row("Bluetooth clásico SPP (OBD)", spp?.value ?: "Falta la prueba", spp?.status ?: Status.INFO),
            Row("GPS", gps?.let { "Ubicación: ${it.value}" } ?: "Falta la prueba", gps?.status ?: Status.INFO),
            Row(
                "Ajuste de notificaciones",
                if (NotificationCollector.settingsAvailable(context)) "Existe" else "No existe (plan B con adb)",
                if (NotificationCollector.settingsAvailable(context)) Status.OK else Status.WARN,
            ),
            Row("Permiso de música", if (NotificationCollector.isEnabled(context)) "Concedido" else "Pendiente", if (NotificationCollector.isEnabled(context)) Status.OK else Status.INFO),
            Row("AutoKit", AutoKitCollector.version(context)?.let { "Instalado (v$it)" } ?: "No instalado"),
            Row(
                "Burbuja sobre Android Auto",
                results[OverlayProbe.KEY]?.rows?.firstOrNull { it.key == "RAM libre mínima" }?.let { "Probada · RAM libre mínima ${it.value}" } ?: "Falta la prueba",
                results[OverlayProbe.KEY]?.rows?.firstOrNull { it.key == "RAM libre mínima" }?.status ?: Status.INFO,
            ),
            Row(
                "Root",
                when {
                    c.prefs.rootOk -> "Confirmado"
                    RootCollector.suPathsFound().isNotEmpty() -> "Hay archivo su · falta la prueba"
                    else -> "No encontrado"
                },
                if (c.prefs.rootOk) Status.OK else Status.INFO,
            ),
            Row(
                "Launcher predeterminado",
                home ?: "Ninguno (el sistema pregunta)",
                if (home == context.packageName) Status.OK else Status.INFO,
            ),
            Row("Fluidez ligera", perfLight?.value ?: "Falta la prueba", perfLight?.status ?: Status.INFO),
            Row("Fluidez pesada", perfHeavy?.value ?: "Falta la prueba", perfHeavy?.status ?: Status.INFO),
            Row("Fluidez ligera (AOT)", perfLightAot?.value ?: "Falta la prueba", perfLightAot?.status ?: Status.INFO),
            Row("Fluidez pesada (AOT)", perfHeavyAot?.value ?: "Falta la prueba", perfHeavyAot?.status ?: Status.INFO),
            Row(
                "Monitor de la llave",
                if (c.prefs.accMonitorEnabled) "Activo · ${c.accLog.lines.value.size} eventos" else "Apagado",
                if (c.prefs.accMonitorEnabled) Status.OK else Status.INFO,
            ),
        )
    }
}

object ReportBuilder {
    // "location" cubre gps.location.provider, que en esta ROM guarda la última posición GPS.
    private val SENSITIVE = listOf(
        "serial", "imei", "meid", "mac", "bdaddr", "bt.addr", "wifi.addr", "iccid", "imsi",
        "location", "latitude", "longitude", "barcode", "no.such.thing",
    )

    fun build(context: Context): String {
        val c = context.container
        val apps = AppsCollector.collect(context)
        val sections = mutableListOf(
            ReportSection("Resumen", SummaryCollector.rows(context)),
            SystemCollector.system(context),
            SystemCollector.display(context),
            BluetoothCollector.collect(context),
            UsbCollector.collect(context),
            GpsCollector.collect(context),
            NotificationCollector.collect(context),
            AutoKitCollector.collect(context),
            RootCollector.collect(context, c.prefs),
            AppsCollector.launcherSection(context),
        )
        sections += c.diagStore.results.value.values
        sections += AppsCollector.toSection(apps)

        val props = SysProp.dump().lineSequence().map { line ->
            val key = line.substringBefore(']').lowercase()
            if (SENSITIVE.any { key.contains(it) }) line.substringBefore("]: ") + "]: [oculto]" else line
        }.joinToString("\n")

        return buildString {
            append("CIVIC LAUNCHER · REPORTE DE DIAGNÓSTICO (Fase 0)\n")
            append("Generado: ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())).append('\n')
            append("Versión de la app: ").append(BuildConfig.VERSION_NAME).append("\n\n")
            sections.forEach { append(it.toText()) }
            append("== Bitácora de la llave ==\n").append(c.accLog.text().ifEmpty { "(vacía)\n" }).append('\n')
            append("== Propiedades del sistema (getprop, sin identificadores) ==\n").append(props).append('\n')
        }
    }
}

object ReportExporter {
    data class Exported(val internal: File, val publicPath: String?, val publicError: String?, val sizeBytes: Long)

    val legacyStoragePermission = arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE)

    fun save(context: Context, text: String): Exported {
        val name = "civic_diag_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.txt"
        val dir = File(context.filesDir, "reports").apply { mkdirs() }
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(5)?.forEach { it.delete() }
        val internal = File(dir, name).apply { writeText(text) }

        var publicPath: String? = null
        var publicError: String? = null
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/CivicLauncher")
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("MediaStore no devolvió destino")
                context.contentResolver.openOutputStream(uri)!!.use { it.write(text.toByteArray()) }
                publicPath = "Descargas/CivicLauncher/$name"
            } else {
                if (!context.hasPermissions(legacyStoragePermission)) error("Falta el permiso de almacenamiento")
                @Suppress("DEPRECATION")
                val pub = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "CivicLauncher").apply { mkdirs() }
                val f = File(pub, name).apply { writeText(text) }
                publicPath = f.absolutePath
            }
        }.onFailure { publicError = it.message }
        return Exported(internal, publicPath, publicError, internal.length())
    }

    fun shareIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "Reporte de diagnóstico Civic Launcher")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, "Compartir reporte").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
