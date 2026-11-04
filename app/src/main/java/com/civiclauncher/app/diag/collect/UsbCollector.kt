package com.civiclauncher.app.diag.collect

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import com.civiclauncher.app.diag.ReportSection
import com.civiclauncher.app.diag.Row
import com.civiclauncher.app.diag.Status

object UsbCollector {

    /** Chips USB-serial que usan los adaptadores OBD2 por USB. Todos los soporta usb-serial-for-android. */
    fun chipGuess(vendorId: Int): String? = when (vendorId) {
        0x0403 -> "FTDI (adaptador USB-serial, ideal para OBD)"
        0x1A86 -> "WCH CH340/CH341 (adaptador USB-serial)"
        0x10C4 -> "Silicon Labs CP210x (adaptador USB-serial)"
        0x067B -> "Prolific PL2303 (adaptador USB-serial)"
        0x0483 -> "STMicroelectronics"
        else -> null
    }

    private fun hex(v: Int) = "%04X".format(v)

    fun collect(context: Context): ReportSection {
        val pm = context.packageManager
        val host = pm.hasSystemFeature(PackageManager.FEATURE_USB_HOST)
        val um = context.getSystemService(Context.USB_SERVICE) as? UsbManager
        val devices = runCatching { um?.deviceList?.values?.toList().orEmpty() }.getOrDefault(emptyList())

        val rows = mutableListOf(
            Row("Modo USB host (conectar dispositivos)", if (host) "Sí" else "No declarado", if (host) Status.OK else Status.WARN),
            Row("Modo accesorio", if (pm.hasSystemFeature(PackageManager.FEATURE_USB_ACCESSORY)) "Sí" else "No"),
            Row("Dispositivos conectados", devices.size.toString()),
        )
        devices.forEach { d ->
            val product = runCatching { d.productName }.getOrNull()
            val maker = runCatching { d.manufacturerName }.getOrNull()
            val chip = chipGuess(d.vendorId)
            rows += Row(
                "  · ${product ?: d.deviceName}",
                "VID ${hex(d.vendorId)} PID ${hex(d.productId)} · clase ${d.deviceClass} · interfaces ${d.interfaceCount}" +
                    (maker?.let { " · $it" } ?: "") + (chip?.let { " · $it" } ?: ""),
                if (chip != null) Status.OK else Status.INFO,
            )
        }
        val notes = listOf(
            "Conecta una memoria USB o un adaptador OBD por USB y toca Actualizar.",
            "Si una memoria funciona pero no aparece aquí, el sistema controla ese puerto y no lo comparte con las apps.",
        )
        return ReportSection("USB", rows, notes)
    }
}
