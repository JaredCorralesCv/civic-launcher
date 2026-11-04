package com.civiclauncher.obd

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ElmSessionTest {

    /** Transporte falso que guarda los comandos recibidos. */
    private class RecordingTransport(private val replies: Map<String, String>) : ObdTransport {
        val sent = mutableListOf<String>()
        override suspend fun open() = Unit
        override suspend fun close() = Unit
        override suspend fun send(command: String, timeoutMs: Long): String {
            sent += command
            return replies[command] ?: "OK\r\r>"
        }
    }

    @Test
    fun `configura el adaptador en el orden correcto`() = runTest {
        val t = RecordingTransport(mapOf("ATZ" to "ATZ\r\rELM327 v1.5\r\r>"))
        val session = ElmSession(t)
        session.start()
        assertEquals(listOf("ATZ") + ElmSession.INIT_COMMANDS, t.sent)
        assertEquals("ELM327 V1.5", session.adapterId)
    }

    @Test
    fun `el simulador responde como un Civic`() = runTest {
        var now = 0L
        val session = ElmSession(SimulatedElm(latencyMs = 0, clockMs = { now }))
        session.start()

        // Detenido en el semáforo (segundo 50 del ciclo): ralentí.
        now = 50_000
        val idle = session.query(Pids.RPM) as ObdResult.Value
        assertEquals(750.0, idle.value)
        assertEquals(0.0, (session.query(Pids.SPEED) as ObdResult.Value).value)

        // Manejando (segundo 10): hay velocidad y las RPM suben.
        now = 10_000
        assertTrue((session.query(Pids.SPEED) as ObdResult.Value).value > 0)
        assertTrue((session.query(Pids.RPM) as ObdResult.Value).value >= 800)

        // Igual que un Civic 2008: no reporta nivel de gasolina.
        assertEquals(ObdResult.NoData, session.query(Pids.FUEL_LEVEL))
        assertEquals(14.1, (session.batteryVoltage() as ObdResult.Value).value)
    }

    @Test
    fun `el motor se calienta en el simulador`() = runTest {
        var now = 0L
        val session = ElmSession(SimulatedElm(latencyMs = 0, clockMs = { now }))
        session.start()
        val cold = (session.query(Pids.COOLANT_TEMP) as ObdResult.Value).value
        now = 600_000
        val hot = (session.query(Pids.COOLANT_TEMP) as ObdResult.Value).value
        assertEquals(25.0, cold)
        assertEquals(90.0, hot)
    }
}
