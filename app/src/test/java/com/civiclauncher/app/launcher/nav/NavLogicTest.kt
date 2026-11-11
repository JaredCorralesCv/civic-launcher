package com.civiclauncher.app.launcher.nav

import com.civiclauncher.app.launcher.map.isFastForMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavLogicTest {
    @Test
    fun `recalcular espera cada vez mas y se reinicia al volver a la ruta`() {
        val b = RerouteBackoff()
        assertTrue(b.canTry(0))
        b.failed(0)
        assertFalse(b.canTry(3_999))
        assertTrue(b.canTry(4_000))
        b.failed(4_000)
        assertFalse(b.canTry(18_999))
        assertTrue(b.canTry(19_000))
        b.failed(19_000)
        assertFalse(b.canTry(78_999))
        assertTrue(b.canTry(79_000))
        b.failed(79_000)
        assertFalse("No pasa de 60 s de espera, pero tampoco baja", b.canTry(138_999))
        b.reset()
        assertTrue(b.canTry(79_001))
    }

    @Test
    fun `el zoom por velocidad no brinca en el umbral`() {
        assertFalse(isFastForMap(false, 59f))
        assertTrue(isFastForMap(false, 60f))
        assertTrue("Entre 45 y 60 se queda como estaba", isFastForMap(true, 50f))
        assertFalse(isFastForMap(true, 45f))
        assertTrue("Sin dato, se queda como estaba", isFastForMap(true, null))
    }

    @Test
    fun `el conjunto de huellas crece y no repite`() {
        val set = LongHashSet(4)
        for (i in 1L..10_000L) assertTrue(set.add(i * 1_000_003L))
        for (i in 1L..10_000L) assertFalse(set.add(i * 1_000_003L))
        assertEquals(10_000, set.size)
        assertTrue(set.add(0L))
        assertFalse(set.add(0L))
    }
}
