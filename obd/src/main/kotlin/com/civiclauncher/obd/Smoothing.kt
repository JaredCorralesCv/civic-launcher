package com.civiclauncher.obd

import kotlin.math.abs

/**
 * Filtro de avisos: un aviso aparece solo si la condición se sostiene un rato y desaparece solo si
 * deja de cumplirse un rato. Sin esto, una temperatura que ronda los 102 °C hacía aparecer y
 * desaparecer el aviso una y otra vez, y un bajón de voltaje de un segundo (al prender el aire,
 * por ejemplo) ya decía "la batería no está cargando".
 *
 * Cuánto tiene que durar cada aviso antes de mostrarse ([onDelayMs]) y cuánto se queda después
 * ([offDelayMs]) depende de qué tan grave es y de qué tan rápido cambia el dato.
 */
class AlertFilter(
    private val onDelayMs: (String) -> Long = ::defaultOnDelay,
    private val offDelayMs: (String) -> Long = ::defaultOffDelay,
) {
    private val firstSeen = mutableMapOf<String, Long>()
    private val lastSeen = mutableMapOf<String, Long>()
    private val shown = linkedMapOf<String, VehicleAlert>()

    /** Recibe los avisos "crudos" de este momento y devuelve los que se deben mostrar, el más grave primero. */
    fun update(raw: List<VehicleAlert>, nowMs: Long): List<VehicleAlert> {
        val current = raw.associateBy { it.id }
        for ((id, alert) in current) {
            val since = firstSeen.getOrPut(id) { nowMs }
            lastSeen[id] = nowMs
            when {
                // Ya se mostraba: se actualiza el texto (la temperatura o el voltaje de ahora).
                id in shown -> shown[id] = alert
                nowMs - since >= onDelayMs(id) -> shown[id] = alert
            }
        }
        // Lo que dejó de cumplirse antes de mostrarse se olvida: tiene que volver a sostenerse completo.
        firstSeen.keys.filter { it !in current && it !in shown }.forEach {
            firstSeen.remove(it)
            lastSeen.remove(it)
        }
        // Lo que se mostraba se quita solo cuando lleva un rato sin cumplirse.
        shown.keys.filter { it !in current && nowMs - (lastSeen[it] ?: 0L) >= offDelayMs(it) }.forEach {
            shown.remove(it)
            firstSeen.remove(it)
            lastSeen.remove(it)
        }
        return shown.values.sortedByDescending { it.level }
    }

    companion object {
        fun defaultOnDelay(id: String): Long = when (id) {
            // El voltaje baja un momento con cada carga eléctrica: solo si dura un minuto es de verdad.
            "charge" -> 60_000
            "lowbat" -> 10_000
            "warm" -> 5_000
            "hot" -> 2_000
            else -> 0 // foco Check Engine y RPM altas: de inmediato
        }

        fun defaultOffDelay(id: String): Long = when (id) {
            "rev" -> 1_500
            else -> 10_000
        }
    }
}

/**
 * Marcha estimada, con memoria:
 *  - No parpadea: una marcha nueva se muestra cuando se sostiene un instante, y al pisar el clutch
 *    (cuando las RPM ya no cuadran con ninguna marcha) se queda la anterior un momento.
 *  - Aprende tu carro: cuando vas parejo en una marcha (la relación RPM/km/h no cambia entre
 *    lecturas seguidas, o sea, sin clutch ni cambio a medias), esa relación se acerca un poco a la
 *    medida. Así se ajusta al tamaño de tus llantas o a tu transmisión, sin salirse más de 15 % de
 *    la tabla de fábrica (para no "aprender" algo absurdo).
 */
class GearTracker(initial: DoubleArray = GearEstimator.defaultRatios()) {
    private val base = GearEstimator.defaultRatios()
    private val ratios = DoubleArray(base.size) { i -> initial.getOrElse(i) { base[i] }.coerceIn(base[i] * MIN_FACTOR, base[i] * MAX_FACTOR) }
    private var shown: Int? = null
    private var candidate: Int? = null
    private var candidateSince = 0L
    private var lastMatchAt = Long.MIN_VALUE / 2
    private var previousRatio = 0.0
    private var steady = 0

    /** Las relaciones aprendidas (RPM por km/h de cada marcha), para guardarlas. */
    val learned: DoubleArray get() = ratios.copyOf()

    fun update(rpm: Double?, speedKmh: Double?, nowMs: Long): Int? {
        val gear = if (rpm == null || speedKmh == null || speedKmh < 8 || rpm < 900) null else match(rpm / speedKmh)
        if (gear != null) {
            lastMatchAt = nowMs
            if (gear != candidate) {
                candidate = gear
                candidateSince = nowMs
            }
            if (shown == null || nowMs - candidateSince >= CONFIRM_MS) shown = gear
        } else if (nowMs - lastMatchAt > HOLD_MS) {
            shown = null
            candidate = null
        }
        return shown
    }

    private fun match(ratio: Double): Int? {
        steady = if (previousRatio > 0 && abs(ratio - previousRatio) / previousRatio < STEADY_ERROR) steady + 1 else 0
        previousRatio = ratio
        val best = ratios.indices.minBy { abs(ratios[it] - ratio) }
        val error = abs(ratios[best] - ratio) / ratios[best]
        if (error >= MATCH_ERROR) return null
        if (steady >= STEADY_READINGS) {
            ratios[best] = (ratios[best] * (1 - LEARN_RATE) + ratio * LEARN_RATE)
                .coerceIn(base[best] * MIN_FACTOR, base[best] * MAX_FACTOR)
        }
        return best + 1
    }

    private companion object {
        /** Una marcha nueva tiene que sostenerse este tiempo para mostrarse. */
        const val CONFIRM_MS = 400L
        /** Sin coincidir (clutch pisado, cambiando), la marcha de antes se queda este tiempo. */
        const val HOLD_MS = 1_500L
        const val MATCH_ERROR = 0.08
        /** "Parejo": la relación cambia menos de 1.5 % entre lecturas, varias lecturas seguidas. */
        const val STEADY_ERROR = 0.015
        const val STEADY_READINGS = 3
        const val LEARN_RATE = 0.02
        const val MIN_FACTOR = 0.85
        const val MAX_FACTOR = 1.15
    }
}
