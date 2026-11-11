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
        // Las preguntas ahora van de varios PIDs juntos: se cuenta cuántas incluyen cada PID.
        fun count(pid: Int) = asked.count { c -> c.startsWith("01") && c.length > 2 && c.substring(2).let { it.substring(0, it.length - it.length % 2) }.chunked(2).contains("%02X".format(pid)) }
        assertTrue(count(0x0C) > 10, "RPM se lee seguido")
        assertEquals(0, count(0x2F), "Nivel de gasolina no se pide")
        // Prioridades: RPM (rápido) se pide mucho más que la temperatura (lento).
        assertTrue(count(0x0C) > count(0x05) * 5)
        e.stop()
    }

    @Test
    fun `pide de varios PIDs a la vez y con respuesta rapida`() = runTest {
        var spy: SpyTransport? = null
        val clock = { testScheduler.currentTime }
        val e = engine(factory = { SpyTransport(SimulatedElm(latencyMs = 20, clockMs = clock)).also { spy = it } })
        e.start()
        advanceTimeBy(10_000)
        val asked = spy!!.commands.filter { it.startsWith("01") && it.length > 4 }
        assertTrue(asked.any { it.length > 6 }, "Pregunta varios PIDs juntos")
        assertTrue(asked.all { it.length % 2 == 1 }, "Todas llevan el '1' de respuesta rápida")
        assertNotNull(e.data.value[Pids.RPM])
        assertNotNull(e.data.value[Pids.SPEED])
        e.stop()
    }

    @Test
    fun `adaptador viejo sin varios PIDs ni respuesta rapida`() = runTest {
        val clock = { testScheduler.currentTime }
        val fast = ObdEngine({ SimulatedElm(latencyMs = 20, clockMs = clock) }, backgroundScope, clock)
        val old = ObdEngine({ SimulatedElm(latencyMs = 20, clockMs = clock, multiPid = false, fastReply = false) }, backgroundScope, clock)
        fast.start(); old.start()
        advanceTimeBy(10_000)
        // Los dos leen todo; el viejo solo más lento.
        assertTrue(old.state.value is EngineState.Connected, "Estado: ${old.state.value}")
        assertNotNull(old.data.value[Pids.RPM])
        assertNotNull(old.data.value[Pids.COOLANT_TEMP])
        val updatesFast = fast.data.value[Pids.RPM]!!.atMs
        assertTrue(updatesFast > 0)
        fast.stop(); old.stop()
    }

    @Test
    fun `varios PIDs por pregunta actualiza mucho mas seguido`() = runTest {
        val clock = { testScheduler.currentTime }
        fun rpmReads(multi: Boolean): Int {
            var spy: SpyTransport? = null
            val e = ObdEngine(
                { SpyTransport(SimulatedElm(latencyMs = 60, clockMs = clock, multiPid = multi, fastReply = multi)).also { spy = it } },
                backgroundScope,
                clock,
            )
            e.start()
            testScheduler.advanceTimeBy(30_000)
            e.stop()
            return spy!!.commands.count { c -> c.startsWith("01") && c.length > 2 && c.substring(2).let { it.substring(0, it.length - it.length % 2) }.chunked(2).contains("0C") }
        }
        val old = rpmReads(multi = false)
        val fast = rpmReads(multi = true)
        println("RPM en 30 s: de uno en uno = $old, de varios a la vez = $fast")
        assertTrue(fast >= old * 2, "Debe ser al menos el doble de rápido (uno en uno $old, varios $fast)")
    }

    @Test
    fun `la respuesta de varios PIDs se lee completa`() {
        val r = ElmParser.parseMulti("410C1AF80D3211\r\r>", listOf(Pids.RPM, Pids.SPEED, Pids.THROTTLE))!!
        assertEquals(1726.0, r[Pids.RPM.pid])
        assertEquals(50.0, r[Pids.SPEED.pid])
        assertFalse(r.containsKey(Pids.THROTTLE.pid), "El acelerador no vino: el carro no lo tiene")
        // En varias tramas (CAN con más de 7 bytes).
        val multi = ElmParser.parseMulti("00A\r0:410C1AF80D32\r1:115A055A000000\r\r>", listOf(Pids.RPM, Pids.SPEED, Pids.THROTTLE, Pids.COOLANT_TEMP))!!
        assertEquals(4, multi.size)
        assertEquals(50.0, multi[Pids.COOLANT_TEMP.pid])
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

    private fun spyRun(scope: TestScope, setup: (ObdEngine) -> Unit, run: (ObdEngine) -> Unit): List<String> {
        var spy: SpyTransport? = null
        val clock = { scope.testScheduler.currentTime }
        val e = ObdEngine({ SpyTransport(SimulatedElm(latencyMs = 20, clockMs = clock)).also { spy = it } }, scope.backgroundScope, clock)
        setup(e)
        e.start()
        run(e)
        e.stop()
        return spy!!.commands.filter { it.startsWith("01") && it.length > 4 }
    }

    private fun List<String>.asking(pid: Int) =
        count { c -> c.substring(2).let { it.substring(0, it.length - it.length % 2) }.chunked(2).contains("%02X".format(pid)) }

    @Test
    fun `en segundo plano lee mucho menos pero sigue con viaje y avisos`() = runTest {
        val live = spyRun(this, { it.demand = PollDemand.LIVE }) { testScheduler.advanceTimeBy(30_000) }
        val background = spyRun(this, { it.demand = PollDemand.BACKGROUND }) { testScheduler.advanceTimeBy(30_000) }
        val rpmLive = live.asking(Pids.RPM.pid)
        val rpmBackground = background.asking(Pids.RPM.pid)
        assertTrue(rpmBackground in 40..65, "Unas 2 vueltas por segundo: $rpmBackground")
        assertTrue(rpmLive > rpmBackground * 3, "En vivo $rpmLive, fondo $rpmBackground")
        assertTrue(background.asking(Pids.COOLANT_TEMP.pid) > 0, "Los avisos necesitan la temperatura")
    }

    @Test
    fun `lo exclusivo de Sensores solo se pide con Sensores abierto`() = runTest {
        val closed = spyRun(this, { it.demand = PollDemand.LIVE }) { testScheduler.advanceTimeBy(20_000) }
        assertEquals(0, closed.asking(Pids.O2_B1S1.pid), "Con Sensores cerrado no se piden las sondas")
        val opened = spyRun(this, { it.demand = PollDemand.LIVE }) { e ->
            testScheduler.advanceTimeBy(8_000)
            e.demand = PollDemand.ALL
            testScheduler.advanceTimeBy(3_000)
        }
        assertTrue(opened.asking(Pids.O2_B1S1.pid) > 0, "Al abrir Sensores se piden")
    }

    @Test
    fun `un salto de la hora no cuenta como tiempo apagado`() {
        val before = TimeMark("a", elapsedMs = 1_000, wallMs = 1_000_000)
        assertEquals(60_000, before.msUntil(TimeMark("a", 61_000, 9_000_000_000)), "Hacia adelante: manda el contador")
        assertEquals(60_000, before.msUntil(TimeMark("a", 61_000, 5)), "Hacia atrás: manda el contador")
    }

    @Test
    fun `entre reinicios se usa la hora y una hora sin corregir no reinicia el viaje`() {
        val before = TimeMark("a", elapsedMs = 900_000, wallMs = 1_000_000)
        assertEquals(40 * 60_000L, before.msUntil(TimeMark("b", 5_000, 1_000_000 + 40 * 60_000L)))
        assertEquals(0, before.msUntil(TimeMark("b", 5_000, 500)), "Hora de fábrica tras reiniciar")
        val legacy = TimeMark("", 0, 1_000_000)
        assertEquals(1_000, legacy.msUntil(TimeMark("b", 5_000, 1_001_000)), "Datos de una versión anterior")
    }

    @Test
    fun `el viaje guarda la hora real aunque las lecturas usen el contador`() {
        val rpm = VehicleData(values = mapOf(Pids.RPM.pid to Reading(2_000.0, 50)))
        val trip = TripComputer()
        val s = trip.update(rpm, nowMs = 50, wallMs = 1_700_000_000_000)
        assertEquals(1_700_000_000_000, s.startedAtMs)
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
