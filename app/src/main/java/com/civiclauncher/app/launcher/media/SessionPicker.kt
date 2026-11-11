package com.civiclauncher.app.launcher.media

import com.civiclauncher.app.launcher.data.VendorApps

/** Lo mínimo de una sesión de música para decidir qué mostrar (sin Android: se puede probar). */
data class SessionInfo(val pkg: String, val playing: Boolean, val hasTitle: Boolean)

/** De qué fuente es una app de música. */
fun musicSourceOf(pkg: String): MusicSource = when (pkg) {
    VendorApps.ANDROID_AUTO -> MusicSource.ANDROID_AUTO
    "com.android.bluetooth", VendorApps.BLUETOOTH -> MusicSource.BLUETOOTH
    VendorApps.RADIO -> MusicSource.RADIO
    VendorApps.MUSIC, "com.hcn.automediaplayerservice" -> MusicSource.USB
    else -> MusicSource.APP
}

/**
 * Las reglas de "qué suena". Separadas de Android para poder probarlas.
 */
object SessionPicker {
    /**
     * La sesión a mostrar: primero la que está sonando; luego la de la fuente que el fabricante marca
     * como activa; luego la que tiene canción. -1 si no hay ninguna.
     */
    fun pick(list: List<SessionInfo>, vendorSource: String): Int {
        if (list.isEmpty()) return -1
        return list.indices.sortedWith(
            compareByDescending<Int> { list[it].playing }
                .thenByDescending { list[it].pkg == vendorSource }
                .thenByDescending { list[it].hasTitle },
        ).first()
    }

    /**
     * Si nada está sonando y la fuente del fabricante es la radio o Android Auto, eso es lo que hay
     * que mostrar, aunque haya una canción del celular en pausa (antes ganaba la canción en pausa: la
     * radio sonaba y la tarjeta decía "Bluetooth · En pausa"). null = mostrar la sesión elegida.
     */
    fun idleOverride(best: SessionInfo?, vendorSource: String): MusicSource? {
        if (best?.playing == true) return null
        if (vendorSource.isBlank()) return null
        val vendor = musicSourceOf(vendorSource)
        if (vendor != MusicSource.RADIO && vendor != MusicSource.ANDROID_AUTO) return null
        return if (best == null || musicSourceOf(best.pkg) != vendor) vendor else null
    }
}
