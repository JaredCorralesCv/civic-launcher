package com.civiclauncher.obd

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SmoothingTest {
    private val charge = VehicleAlert("charge", AlertLevel.WARN, "La batería no está cargando", "12.8 V")
    private val hot = VehicleAlert("hot", AlertLevel.DANGER, "Motor sobrecalentado", "110 °C")

    @Test
    fun `un bajon corto de voltaje no avisa`() {
        val f = AlertFilter()
        assertTrue(f.update(listOf(charge), 0).isEmpty())
        assertTrue(f.update(listOf(charge), 20_000).isEmpty())
        // Se recuperó: la cuenta empieza de cero.
        assertTrue(f.update(emptyList(), 21_000).isEmpty())
        assertTrue(f.update(listOf(charge), 30_000).isEmpty())
        assertTrue(f.update(listOf(charge), 80_000).isEmpty(), "Lleva 50 s, no 60")
        assertEquals(listOf(charge), f.update(listOf(charge), 90_000))
    }

    @Test
    fun `un aviso mostrado no parpadea`() {
        val f = AlertFilter()
        f.update(listOf(hot), 0)
        assertEquals(listOf(hot), f.update(listOf(hot), 2_000))
        // Baja un momento del umbral: el aviso se queda.
        assertEquals(listOf(hot), f.update(emptyList(), 3_000))
        assertEquals(listOf(hot), f.update(emptyList(), 11_000))
        assertTrue(f.update(emptyList(), 12_500).isEmpty(), "Diez segundos sin cumplirse: se quita")
    }

    @Test
    fun `el mas grave va primero`() {
        val f = AlertFilter(onDelayMs = { 0 })
        val shown = f.update(listOf(charge, hot), 0)
        assertEquals(listOf(hot, charge), shown)
    }

    @Test
    fun `la marcha no parpadea al cambiar`() {
        val g = GearTracker()
        assertEquals(3, g.update(2_185.0, 50.0, 0))
        // Clutch pisado: no coincide con nada, pero la tercera se queda un momento.
        assertEquals(3, g.update(3_000.0, 50.0, 500))
        // Entra cuarta: se confirma tras sostenerse.
        assertEquals(3, g.update(1_660.0, 50.0, 1_000))
        assertEquals(4, g.update(1_660.0, 50.0, 1_500))
        // Detenido mucho rato: ya no hay marcha.
        assertEquals(4, g.update(800.0, 0.0, 2_000))
        assertNull(g.update(800.0, 0.0, 4_000))
    }

    @Test
    fun `aprende llantas mas grandes`() {
        // Llantas 5 % más grandes: a la misma RPM vas 5 % más rápido (la relación baja 5 %).
        val g = GearTracker()
        val real = GearEstimator.defaultRatios()[2] * 0.95
        repeat(400) { i -> g.update(real * 60.0, 60.0, i * 200L) }
        assertEquals(real, g.learned[2], real * 0.01)
        // Y no se sale de lo razonable aunque le den datos raros.
        val weird = GearTracker(doubleArrayOf(1.0, 1.0, 1.0, 1.0, 1.0))
        assertTrue(weird.learned[0] >= GearEstimator.defaultRatios()[0] * 0.85)
    }
}
