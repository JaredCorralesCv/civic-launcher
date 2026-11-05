package com.civiclauncher.obd

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsTest {

    @Test
    fun `codigos de falla en CAN de una sola trama`() {
        // 43 · 2 códigos · P0133 · P0420
        assertEquals(listOf("P0133", "P0420"), ElmParser.parseDtcs("4302013304 20\r\r>", 0x43, can = true))
        assertEquals(emptyList<String>(), ElmParser.parseDtcs("4300\r\r>", 0x43, can = true))
        assertEquals(emptyList<String>(), ElmParser.parseDtcs("NO DATA\r\r>", 0x43, can = true))
    }

    @Test
    fun `codigos de falla en varias tramas`() {
        val raw = "00A\r0:43040133042003\r1:0001710000\r\r>"
        assertEquals(listOf("P0133", "P0420", "P0300", "P0171"), ElmParser.parseDtcs(raw, 0x43, can = true))
    }

    @Test
    fun `codigos de falla en protocolos viejos (de 3 en 3)`() {
        assertEquals(listOf("P0133"), ElmParser.parseDtcs("43013300000000\r\r>", 0x43, can = false))
    }

    @Test
    fun `nombre de los codigos por sistema`() {
        assertEquals("P0420", ElmParser.dtcName(0x04, 0x20))
        assertEquals("C0035", ElmParser.dtcName(0x40, 0x35))
        assertEquals("B1234", ElmParser.dtcName(0x92, 0x34))
        assertEquals("U0100", ElmParser.dtcName(0xC1, 0x00))
        assertEquals("Catalizador con eficiencia baja", DtcCatalog.describe("P0420").description)
        assertTrue(DtcCatalog.describe("P0399").description.contains("encendido"))
    }

    @Test
    fun `VIN en varias tramas`() {
        val raw = "014\r0:490201314847\r1:46413136353838\r2:4C303030313233\r\r>"
        assertEquals("1HGFA16588L000123", ElmParser.parseVin(raw))
        assertNull(ElmParser.parseVin("NO DATA\r\r>"))
    }

    @Test
    fun `consumo estimado con sensor MAP (sin MAF)`() {
        val now = 10_000L
        fun data(vararg pairs: Pair<PidSpec, Double>) = VehicleData(pairs.associate { (p, v) -> p.pid to Reading(v, now) })
        // Ralentí: ~1 L/h.
        val idle = FuelModel.litersPerHour(data(Pids.RPM to 750.0, Pids.INTAKE_MAP to 30.0, Pids.INTAKE_AIR_TEMP to 30.0, Pids.SPEED to 0.0), now)!!
        assertTrue(idle in 0.7..1.5, "Ralentí: $idle L/h")
        // Carretera a 100 km/h: entre 10 y 20 km/L para un Civic 1.8.
        val road = data(Pids.RPM to 2_700.0, Pids.INTAKE_MAP to 55.0, Pids.INTAKE_AIR_TEMP to 30.0, Pids.SPEED to 100.0, Pids.THROTTLE to 25.0, Pids.ENGINE_LOAD to 45.0)
        val kml = FuelModel.kmPerLiter(road, now)!!
        assertTrue(kml in 10.0..20.0, "Carretera: $kml km/L")
        // Bajando sin acelerar: corte de inyección.
        val coast = data(Pids.RPM to 2_000.0, Pids.INTAKE_MAP to 22.0, Pids.SPEED to 60.0, Pids.THROTTLE to 8.0, Pids.ENGINE_LOAD to 5.0)
        assertEquals(0.0, FuelModel.litersPerHour(coast, now))
    }

    @Test
    fun `marcha estimada`() {
        assertEquals(1, GearEstimator.estimate(2_350.0, 20.0))
        assertEquals(3, GearEstimator.estimate(2_185.0, 50.0))
        assertEquals(5, GearEstimator.estimate(2_660.0, 100.0))
        assertNull(GearEstimator.estimate(3_000.0, 50.0), "Embragando: no coincide con ninguna marcha")
        assertNull(GearEstimator.estimate(800.0, 0.0))
    }

    @Test
    fun `computadora de viaje integra distancia y gasolina`() {
        val trip = TripComputer()
        var t = 0L
        repeat(3_600) { // una hora a 60 km/h, una lectura por segundo
            t += 1_000
            val d = VehicleData(
                mapOf(
                    Pids.RPM.pid to Reading(2_000.0, t),
                    Pids.SPEED.pid to Reading(60.0, t),
                    Pids.INTAKE_MAP.pid to Reading(45.0, t),
                    Pids.THROTTLE.pid to Reading(20.0, t),
                    Pids.ENGINE_LOAD.pid to Reading(35.0, t),
                ),
            )
            trip.update(d, t)
        }
        val s = trip.stats
        assertEquals(60.0, s.distanceKm, 0.5)
        assertEquals(60.0, s.avgSpeedKmh!!, 0.5)
        assertTrue(s.avgKmPerLiter!! in 10.0..25.0, "Promedio: ${s.avgKmPerLiter}")
        // Un hueco de 30 s (se cayó la conexión) no suma kilómetros inventados.
        val before = trip.stats.distanceKm
        trip.update(VehicleData(mapOf(Pids.RPM.pid to Reading(2_000.0, t + 30_000), Pids.SPEED.pid to Reading(60.0, t + 30_000))), t + 30_000)
        assertEquals(before, trip.stats.distanceKm)
    }

    @Test
    fun `el motor lee VIN y codigos, y los puede borrar`() = runTest {
        val clock = { testScheduler.currentTime }
        val e = ObdEngine({ SimulatedElm(latencyMs = 20, clockMs = clock) }, backgroundScope, clock)
        e.start()
        advanceTimeBy(8_000)
        val d = e.diagnostics.value
        assertEquals("1HGFA16588L000123", d.vin)
        assertEquals(listOf("P0133"), d.stored)
        assertFalse(d.milOn)
        assertEquals(1, d.dtcCount)
        assertNotNull(d.protocol)

        assertTrue(e.clearCodes())
        advanceTimeBy(3_000)
        assertEquals(emptyList<String>(), e.diagnostics.value.stored)
        assertEquals(ClearResult.OK, e.diagnostics.value.lastClear)
        e.stop()
    }

    @Test
    fun `avisos de manejo`() {
        val now = 1_000L
        val hot = VehicleData(mapOf(Pids.COOLANT_TEMP.pid to Reading(110.0, now), Pids.RPM.pid to Reading(900.0, now)), battery = Reading(12.4, now))
        val alerts = VehicleAlerts.evaluate(hot, Diagnostics(milOn = true, dtcCount = 1), now)
        assertTrue(alerts.any { it.id == "hot" })
        assertTrue(alerts.any { it.id == "charge" }, "12.4 V con el motor encendido = no carga")
        assertTrue(alerts.any { it.id == "mil" })
    }
}
