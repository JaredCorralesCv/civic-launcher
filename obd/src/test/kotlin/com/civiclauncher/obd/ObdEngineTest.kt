package com.civiclauncher.obd

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ObdEngineTest {

    /** Envuelve un transporte para contar comandos y simular que se cae la conexión. */
    private class SpyTransport(private val inner: ObdTransport, private val failOnSend: Int? = null) : ObdTransport {
        val commands = mutableListOf<String>()
        override suspend fun open() = inner.open()
        override suspend fun close() = inner.close()
        override suspend fun send(command: String, timeoutMs: Long): String {
            commands += command
            if (failOnSend != null && commands.size == failOnSend) throw IOException("Bluetooth desconectado")
            return inner.send(command, timeoutMs)
        }
    }

    private fun TestScope.engine(
        ignition: () -> Boolean = { true },
        factory: (() -> ObdTransport)? = null,
    ): ObdEngine {
        val clock = { testScheduler.currentTime }
        return ObdEngine(
            transportFactory = factory ?: { SimulatedElm(latencyMs = 20, clockMs = clock, ignitionOn = ignition) },
            scope = backgroundScope,
            clock = clock,
        )
    }

    @Test
    fun `se conecta, detecta el protocolo y lee datos`() = runTest {
        val e = engine()
        e.start()
        advanceTimeBy(5_000)

        val s = e.state.value
        assertTrue(s is EngineState.Connected, "Estado: $s")
        s as EngineState.Connected
        assertEquals("CAN 11 bits 500 kbps", s.protocol)
        assertTrue(Pids.RPM.pid in s.supported)
        assertFalse(Pids.FUEL_LEVEL.pid in s.supported, "Un Civic 2008 no reporta gasolina")

        val d = e.data.value
        assertNotNull(d[Pids.RPM])
        assertNotNull(d[Pids.COOLANT_TEMP])
        assertNotNull(d.battery)
        e.stop()
    }

    @Test
    fun `no pregunta PIDs que el carro no tiene`() = runTest {
        var spy: SpyTransport? = null
        val clock = { testScheduler.currentTime }
        val e = engine(factory = { SpyTransport(SimulatedElm(latencyMs = 20, clockMs = clock)).also { spy = it } })
        e.start()
        advanceTimeBy(20_000)
        val asked = spy!!.commands
        assertTrue(asked.count { it == "010C" } > 10, "RPM se lee seguido")
        assertEquals(0, asked.count { it == "012F" }, "Nivel de gasolina no se pide")
        // Prioridades: RPM (rápido) se pide mucho más que la temperatura (lento).
        assertTrue(asked.count { it == "010C" } > asked.count { it == "0105" } * 5)
        e.stop()
    }

    @Test
    fun `llave quitada y luego puesta`() = runTest {
        var ignition = false
        val e = engine(ignition = { ignition })
        e.start()
        advanceTimeBy(3_000)
        assertTrue(e.state.value is EngineState.CarOff, "Estado: ${e.state.value}")
        assertEquals(12.4, e.data.value.battery?.value, "Con la llave quitada sigue leyendo la batería")

        ignition = true
        advanceTimeBy(8_000)
        assertTrue(e.state.value is EngineState.Connected, "Estado: ${e.state.value}")

        ignition = false
        advanceTimeBy(3_000)
        assertTrue(e.state.value is EngineState.CarOff, "Debe notar que se apagó: ${e.state.value}")
        e.stop()
    }

    @Test
    fun `si se cae el Bluetooth se reconecta solo`() = runTest {
        val clock = { testScheduler.currentTime }
        var created = 0
        val e = engine(factory = {
            created++
            // La primera conexión se cae en el comando 30; la segunda funciona normal.
            SpyTransport(SimulatedElm(latencyMs = 20, clockMs = clock), failOnSend = if (created == 1) 30 else null)
        })
        e.state.test {
            e.start()
            val seen = mutableListOf<EngineState>()
            while (true) {
                val s = awaitItem()
                seen += s
                if (s is EngineState.Connected && seen.any { it is EngineState.Reconnecting }) break
            }
            val reconnect = seen.filterIsInstance<EngineState.Reconnecting>().first()
            assertEquals("Bluetooth desconectado", reconnect.reason)
            assertEquals(1_000, reconnect.retryInMs, "Primer reintento al segundo")
            assertEquals(2, created, "Abrió una conexión nueva")
            cancelAndIgnoreRemainingEvents()
        }
        e.stop()
    }

    @Test
    fun `los PIDs soportados se leen bien`() {
        // Ejemplo típico de un Honda: 0100 → BE 3E B8 13
        val s = ElmParser.parseSupported("4100BE3EB813\r\r>", 0x00)!!
        assertTrue(0x01 in s && 0x0C in s && 0x0D in s && 0x20 in s)
        assertFalse(0x02 in s)
        assertEquals(null, ElmParser.parseSupported("SEARCHING...\rUNABLE TO CONNECT\r\r>", 0x00))
        assertEquals("CAN 11 bits 500 kbps", ElmParser.protocolName("A6\r\r>"))
    }
}
