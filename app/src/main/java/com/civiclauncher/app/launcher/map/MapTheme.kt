package com.civiclauncher.app.launcher.map

import android.content.Context
import org.mapsforge.map.rendertheme.StreamRenderTheme
import org.mapsforge.map.rendertheme.XmlRenderTheme

/**
 * Estilo de mapa propio de Civic (assets/map/civic_theme.xml) con dos paletas.
 * Noche: azul marino como los mapas de los carros modernos. Día: claro y de alto contraste.
 */
object MapTheme {
    /** Sube este número si cambias el estilo: así no se usan mosaicos viejos guardados en disco. */
    const val VERSION = 3

    private val NIGHT = mapOf(
        "land" to "#172030",
        "water" to "#0C1726",
        "park" to "#182A26",
        "urban" to "#1B2536",
        "building" to "#202B3C",
        "rail" to "#2A3446",
        "casing" to "#101824",
        "minor" to "#2E3A4F",
        "secondary" to "#3D4B66",
        "primary" to "#506383",
        "highway" to "#6C82A8",
        "label" to "#B4BFCF",
        "minorLabel" to "#7F8B9D",
        "placeLabel" to "#DDE3EC",
        "halo" to "#172030",
        "fuel" to "#FFB224",
        "hospital" to "#FF5A50",
    )

    private val DAY = mapOf(
        "land" to "#F1F3F6",
        "water" to "#A9CBEA",
        "park" to "#D2E8D4",
        "urban" to "#E7E9EE",
        "building" to "#E1E4EA",
        "rail" to "#C3C9D2",
        "casing" to "#C8CFD9",
        "minor" to "#FFFFFF",
        "secondary" to "#FFFFFF",
        "primary" to "#FFF0C2",
        "highway" to "#FFD470",
        "label" to "#3E4856",
        "minorLabel" to "#6A7381",
        "placeLabel" to "#222A36",
        "halo" to "#FFFFFF",
        "fuel" to "#C97C00",
        "hospital" to "#D9342A",
    )

    @Volatile private var template: String? = null

    private fun template(context: Context): String =
        template ?: context.assets.open("map/civic_theme.xml").bufferedReader().use { it.readText() }.also { template = it }

    /** Un tema nuevo cada vez: Mapsforge lee el flujo una sola vez por mapa. */
    fun get(context: Context, night: Boolean): XmlRenderTheme {
        var xml = template(context)
        (if (night) NIGHT else DAY).forEach { (k, v) -> xml = xml.replace("{{$k}}", v) }
        return StreamRenderTheme("", xml.byteInputStream(Charsets.UTF_8))
    }

    /** Color de fondo del mapa, para que la tarjeta no parpadee en blanco mientras carga. */
    fun landColor(night: Boolean): Long = if (night) 0xFF172030 else 0xFFF1F3F6
}
