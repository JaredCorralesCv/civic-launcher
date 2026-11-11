package com.civiclauncher.app.launcher

import com.civiclauncher.app.launcher.data.AccentPicker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Fase5LogicTest {
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun `un fondo gris no da acento`() {
        assertNull(AccentPicker.pick(IntArray(100) { rgb(90, 92, 95) }, night = true))
    }

    @Test
    fun `un fondo morado da un acento morado y vivo`() {
        val a = assertNotNullValue(AccentPicker.pick(IntArray(100) { rgb(70, 40, 160) }, night = true))
        val r = (a shr 16) and 0xFF
        val g = (a shr 8) and 0xFF
        val b = a and 0xFF
        assertEquals(255, b)
        assertTrue("Morado: más rojo que verde ($r, $g, $b)", r > g)
    }

    @Test
    fun `el rojo se reserva para los avisos`() {
        assertNull(AccentPicker.pick(IntArray(100) { rgb(200, 20, 25) }, night = true))
    }

    private fun assertNotNullValue(v: Int?): Int {
        assertNotNull(v)
        return v!!
    }

    @Test
    fun `cuadros lentos sostenidos activan el modo ligero`() {
        val d = SlowFrameDetector(0)
        var t = 31_000L
        var fired = false
        repeat(400) {
            if (d.onFrame(60f, t)) fired = true
            t += 50
        }
        assertTrue(fired)
    }

    @Test
    fun `cuadros rapidos o el arranque no lo activan`() {
        val fast = SlowFrameDetector(0)
        var t = 31_000L
        repeat(400) {
            assertFalse(fast.onFrame(12f, t))
            t += 50
        }
        val start = SlowFrameDetector(0)
        t = 0
        repeat(500) {
            assertFalse(start.onFrame(90f, t))
            t += 50
        }
    }
}
