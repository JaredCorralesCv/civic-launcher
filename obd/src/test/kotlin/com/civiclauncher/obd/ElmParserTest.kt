package com.civiclauncher.obd

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ElmParserTest {

    private fun value(r: ObdResult): Double {
        assertTrue(r is ObdResult.Value, "Se esperaba un valor y llegó $r")
        return (r as ObdResult.Value).value
    }

    @Test
    fun `RPM con y sin espacios`() {
        assertEquals(1726.0, value(ElmParser.parsePid("41 0C 1A F8\r\r>", Pids.RPM)))
        assertEquals(1726.0, value(ElmParser.parsePid("410C1AF8\r\r>", Pids.RPM)))
    }

    @Test
    fun `velocidad y temperatura`() {
        assertEquals(60.0, value(ElmParser.parsePid("410D3C\r>", Pids.SPEED)))
        assertEquals(83.0, value(ElmParser.parsePid("41057B\r>", Pids.COOLANT_TEMP)))
    }

    @Test
    fun `ignora SEARCHING y toma la primera computadora`() {
        val raw = "SEARCHING...\r410C0FA0\r410C1000\r\r>"
        assertEquals(1000.0, value(ElmParser.parsePid(raw, Pids.RPM)))
    }

    @Test
    fun `NO DATA cuando el carro no tiene el dato`() {
        assertEquals(ObdResult.NoData, ElmParser.parsePid("NO DATA\r\r>", Pids.FUEL_LEVEL))
    }

    @Test
    fun `errores del adaptador`() {
        val r = ElmParser.parsePid("UNABLE TO CONNECT\r\r>", Pids.RPM)
        assertTrue(r is ObdResult.Error && r.message == "UNABLE TO CONNECT")
        assertTrue(ElmParser.parsePid("?\r>", Pids.RPM) is ObdResult.Error)
    }

    @Test
    fun `respuesta incompleta no truena`() {
        assertTrue(ElmParser.parsePid("410C1A\r>", Pids.RPM) is ObdResult.Error)
    }

    @Test
    fun `voltaje de ATRV`() {
        assertEquals(12.6, value(ElmParser.parseVoltage("12.6V\r\r>")))
    }

    @Test
    fun `fórmulas de ajustes de combustible`() {
        assertEquals(0.0, value(ElmParser.parsePid("410680", Pids.SHORT_FUEL_TRIM_1)))
        assertEquals(-100.0, value(ElmParser.parsePid("410700", Pids.LONG_FUEL_TRIM_1)))
    }
}
