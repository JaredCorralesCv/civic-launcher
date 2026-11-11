package com.civiclauncher.app.launcher.data

import kotlin.math.max
import kotlin.math.min

/**
 * Saca del fondo de pantalla un color para el acento. Se agrupan los pixeles por tono, pesando más
 * los vivos, y el tono ganador se lleva a una saturación y un brillo que se lean bien sobre el
 * tablero. Sin ARGB de Android, para poder probarlo en la PC.
 */
object AccentPicker {
    private const val BUCKETS = 24

    /** ARGB, o null si el fondo es casi gris (ahí se queda el azul de siempre). */
    fun pick(pixels: IntArray, night: Boolean): Int? {
        if (pixels.isEmpty()) return null
        val weight = FloatArray(BUCKETS)
        val hueSum = FloatArray(BUCKETS)
        for (px in pixels) {
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            val b = px and 0xFF
            val hi = max(r, max(g, b))
            val lo = min(r, min(g, b))
            if (hi < 40 || hi == lo) continue
            val saturation = (hi - lo).toFloat() / hi
            if (saturation < 0.25f) continue
            val hue = hueOf(r, g, b, hi, lo)
            // El rojo se reserva para los avisos y la zona roja del tacómetro.
            if (hue < 18f || hue > 342f) continue
            val w = saturation * (hi / 255f)
            val bucket = (hue / 360f * BUCKETS).toInt().coerceAtMost(BUCKETS - 1)
            weight[bucket] += w
            hueSum[bucket] += hue * w
        }
        val best = weight.indices.maxByOrNull { weight[it] } ?: return null
        if (weight[best] / pixels.size < MIN_SHARE) return null
        return hsvToArgb(hueSum[best] / weight[best], 0.72f, if (night) 1f else 0.72f)
    }

    /** ¿El texto encima del acento debe ser oscuro? */
    fun needsDarkText(argb: Int): Boolean {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return 0.299f * r + 0.587f * g + 0.114f * b > 170f
    }

    private fun hueOf(r: Int, g: Int, b: Int, hi: Int, lo: Int): Float {
        val d = (hi - lo).toFloat()
        val h = when (hi) {
            r -> ((g - b) / d) % 6f
            g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        } * 60f
        return if (h < 0f) h + 360f else h
    }

    internal fun hsvToArgb(h: Float, s: Float, v: Float): Int {
        val c = v * s
        val x = c * (1f - kotlin.math.abs((h / 60f) % 2f - 1f))
        val m = v - c
        val (r, g, b) = when ((h / 60f).toInt().coerceIn(0, 5)) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        fun channel(f: Float) = ((f + m) * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
    }

    /** Peso mínimo promedio por pixel para considerar que el fondo tiene color. */
    private const val MIN_SHARE = 0.03f
}
