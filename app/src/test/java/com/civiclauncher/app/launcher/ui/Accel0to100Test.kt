package com.civiclauncher.app.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Accel0to100Test {
    /** Lecturas una por segundo (como el GPS): [speeds][i] en el segundo i. */
    private fun run(t: Accel0to100, speeds: List<Float>): Boolean {
        var record = false
        speeds.forEachIndexed { i, v -> if (t.update(v, i * 1_000L)) record = true }
        return record
    }

    @Test
    fun interpolaArranqueYLlegada() {
        val t = Accel0to100(0L)
        // Parado, y luego +10 km/h cada segundo: cruza 0.5 km/h a los 50 ms y 100 justo en el segundo 11.
        val record = run(t, listOf(0f, 0f) + (1..10).map { it * 10f })
        assertTrue(record)
        assertEquals(AccelPhase.DONE, t.phase)
        assertEquals(9_950L, t.result)
        assertEquals(9_950L, t.best)
    }

    @Test
    fun cruceEntreLecturas() {
        val t = Accel0to100(0L)
        // Pasa de 96 a 104 entre dos lecturas: llegó a 100 a la mitad del segundo.
        run(t, listOf(0f, 8f, 16f, 24f, 32f, 40f, 48f, 56f, 64f, 72f, 80f, 88f, 96f, 104f))
        assertEquals(12_500L - 62L, t.result)
    }

    @Test
    fun brincoImposibleNoCuenta() {
        val t = Accel0to100(7_000L)
        val record = run(t, listOf(0f, 0f, 50f, 110f))
        assertFalse(record)
        assertEquals(7_000L, t.best)
        assertEquals(AccelPhase.WAIT_STOP, t.phase)
    }

    @Test
    fun noBateElRecord() {
        val t = Accel0to100(8_000L)
        val record = run(t, listOf(0f, 0f) + (1..10).map { it * 10f })
        assertFalse(record)
        assertEquals(8_000L, t.best)
    }
}
