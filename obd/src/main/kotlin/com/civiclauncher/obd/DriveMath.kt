package com.civiclauncher.obd

import kotlin.math.abs

/**
 * Consumo de gasolina a partir de lo que dé el carro, en orden de precisión:
 *  1. PID 5E (litros por hora, pocos carros lo tienen),
 *  2. flujo de aire MAF (PID 10),
 *  3. "densidad de velocidad": presión del múltiple + RPM + temperatura de admisión. Es lo que
 *     tiene tu Civic 2008 (motor R18 de 1.8 L, con sensor MAP y sin MAF).
 * Gasolina: mezcla estequiométrica 14.7:1 y densidad 0.745 kg/L.
 */
object FuelModel {
    const val DISPLACEMENT_L = 1.8
    /** Eficiencia volumétrica promedio de un motor atmosférico como el R18. */
    const val VOLUMETRIC_EFFICIENCY = 0.85
    private const val AFR = 14.7
    private const val FUEL_DENSITY_G_PER_L = 745.0
    private const val R_AIR = 287.05

    /** Gramos de aire por segundo. */
    fun airFlow(data: VehicleData, nowMs: Long): Double? {
        data[Pids.MAF].freshAt(nowMs)?.let { return it.value }
        val map = data[Pids.INTAKE_MAP].freshAt(nowMs)?.value ?: return null
        val rpm = data[Pids.RPM].freshAt(nowMs)?.value ?: return null
        val iat = data[Pids.INTAKE_AIR_TEMP].freshAt(nowMs, 30_000)?.value ?: 30.0
        // m = P·V·VE / (R·T) por cada 2 vueltas (motor de 4 tiempos).
        val kgPerIntake = map * 1000 * (DISPLACEMENT_L / 1000) * VOLUMETRIC_EFFICIENCY / (R_AIR * (iat + 273.15))
        return kgPerIntake * (rpm / 120.0) * 1000
    }

    /** Litros por hora en este momento. 0 con el motor apagado. */
    fun litersPerHour(data: VehicleData, nowMs: Long): Double? {
        data[Pids.FUEL_RATE].freshAt(nowMs)?.let { return it.value }
        val rpm = data[Pids.RPM].freshAt(nowMs)?.value
        if (rpm != null && rpm < 100) return 0.0
        val air = airFlow(data, nowMs) ?: return null
        // Corte de inyección al soltar el acelerador en movimiento (el motor frena sin gastar).
        val throttle = data[Pids.THROTTLE].freshAt(nowMs)?.value
        val speed = data[Pids.SPEED].freshAt(nowMs)?.value ?: 0.0
        val load = data[Pids.ENGINE_LOAD].freshAt(nowMs)?.value
        if (speed > 20 && rpm != null && rpm > 1_300 && (load != null && load < 12) && (throttle == null || throttle < 16)) return 0.0
        return air / AFR / FUEL_DENSITY_G_PER_L * 3600
    }

    /** Kilómetros por litro al instante (lo que se usa en México). null si va detenido. */
    fun kmPerLiter(data: VehicleData, nowMs: Long): Double? {
        val speed = data[Pids.SPEED].freshAt(nowMs)?.value ?: return null
        if (speed < 3) return null
        val lph = litersPerHour(data, nowMs) ?: return null
        if (lph <= 0.05) return 99.0 // motor frenando: no gasta
        return (speed / lph).coerceAtMost(99.0)
    }
}

/**
 * Marcha estimada por la relación RPM/velocidad. Valores del Civic 2008 (1.8 L, llanta 205/55R16,
 * ~1.99 m de circunferencia), manual de 5 velocidades. Con automática sale parecido.
 * null si el clutch está pisado, va en neutral o se está cambiando.
 */
object GearEstimator {
    /** RPM por cada km/h en cada marcha. */
    private val RPM_PER_KMH = doubleArrayOf(117.8, 67.8, 43.7, 33.2, 26.6)

    /** Tabla de fábrica (copia), punto de partida de [GearTracker]. */
    fun defaultRatios(): DoubleArray = RPM_PER_KMH.copyOf()

    fun estimate(rpm: Double?, speedKmh: Double?): Int? {
        if (rpm == null || speedKmh == null || speedKmh < 8 || rpm < 900) return null
        val ratio = rpm / speedKmh
        val best = RPM_PER_KMH.indices.minBy { abs(RPM_PER_KMH[it] - ratio) }
        val error = abs(RPM_PER_KMH[best] - ratio) / RPM_PER_KMH[best]
        // 8 %: más flojo confundiría el clutch pisado con la marcha de al lado.
        return if (error < 0.08) best + 1 else null
    }
}

/** Datos del viaje actual. */
data class TripStats(
    val startedAtMs: Long = 0,
    val drivingMs: Long = 0,
    val distanceKm: Double = 0.0,
    val fuelLiters: Double = 0.0,
    val maxSpeedKmh: Double = 0.0,
    val idleMs: Long = 0,
) {
    val avgSpeedKmh: Double? get() = if (drivingMs > 60_000) distanceKm / (drivingMs / 3_600_000.0) else null
    /** Rendimiento promedio del viaje en km/L. */
    val avgKmPerLiter: Double? get() = if (distanceKm > 0.5 && fuelLiters > 0.02) distanceKm / fuelLiters else null
}

/**
 * Computadora de viaje: va sumando distancia (velocidad × tiempo), gasolina (L/h × tiempo), tiempo
 * con el motor encendido y en ralentí, y la velocidad máxima. Se alimenta con cada lectura del OBD.
 * Los huecos de más de 5 s (se cayó la conexión) no se integran para no inventar kilómetros.
 */
class TripComputer(initial: TripStats = TripStats()) {
    var stats: TripStats = initial
        private set
    private var lastMs: Long? = null

    fun update(data: VehicleData, nowMs: Long): TripStats {
        val rpm = data[Pids.RPM].freshAt(nowMs)?.value
        val running = rpm != null && rpm > 300
        val prev = lastMs
        lastMs = nowMs
        if (!running) return stats
        if (stats.startedAtMs == 0L) stats = stats.copy(startedAtMs = nowMs)
        if (prev == null) return stats
        val dt = nowMs - prev
        if (dt <= 0 || dt > 5_000) return stats
        val speed = data[Pids.SPEED].freshAt(nowMs)?.value ?: 0.0
        val lph = FuelModel.litersPerHour(data, nowMs) ?: 0.0
        stats = stats.copy(
            drivingMs = stats.drivingMs + dt,
            distanceKm = stats.distanceKm + speed * dt / 3_600_000.0,
            fuelLiters = stats.fuelLiters + lph * dt / 3_600_000.0,
            maxSpeedKmh = maxOf(stats.maxSpeedKmh, speed),
            idleMs = stats.idleMs + if (speed < 2) dt else 0,
        )
        return stats
    }

    fun reset() {
        stats = TripStats()
        lastMs = null
    }
}

/** Avisos que conviene mostrar mientras manejas. */
enum class AlertLevel { INFO, WARN, DANGER }

data class VehicleAlert(val id: String, val level: AlertLevel, val title: String, val detail: String)

object VehicleAlerts {
    fun evaluate(data: VehicleData, diag: Diagnostics, nowMs: Long): List<VehicleAlert> = buildList {
        val coolant = data[Pids.COOLANT_TEMP].freshAt(nowMs, 30_000)?.value
        val rpm = data[Pids.RPM].freshAt(nowMs)?.value
        val battery = data.battery?.takeIf { nowMs - it.atMs < 30_000 }?.value
        if (diag.milOn) add(VehicleAlert("mil", AlertLevel.WARN, "Foco Check Engine encendido", "${diag.stored.size.coerceAtLeast(diag.dtcCount)} código(s) de falla. Revisa Diagnóstico."))
        if (coolant != null && coolant >= 108) add(VehicleAlert("hot", AlertLevel.DANGER, "Motor sobrecalentado", "%.0f °C. Oríllate y apaga el motor.".format(coolant)))
        else if (coolant != null && coolant >= 102) add(VehicleAlert("warm", AlertLevel.WARN, "Motor muy caliente", "%.0f °C. Vigila la temperatura.".format(coolant)))
        if (battery != null && rpm != null && rpm > 500 && battery < 13.0) add(VehicleAlert("charge", AlertLevel.WARN, "La batería no está cargando", "%.1f V con el motor encendido. Revisa el alternador.".format(battery)))
        if (battery != null && (rpm == null || rpm < 100) && battery < 11.9) add(VehicleAlert("lowbat", AlertLevel.WARN, "Batería baja", "%.1f V. Puede costarle arrancar.".format(battery)))
        if (rpm != null && rpm > 6_500) add(VehicleAlert("rev", AlertLevel.INFO, "RPM muy altas", "%,d rpm".format(rpm.toInt())))
    }
}

/** Lo que se sabe del diagnóstico del carro. */
data class Diagnostics(
    val vin: String? = null,
    val protocol: String? = null,
    val milOn: Boolean = false,
    /** Cantidad que reporta la computadora (PID 01). */
    val dtcCount: Int = 0,
    val stored: List<String> = emptyList(),
    val pending: List<String> = emptyList(),
    val checkedAtMs: Long = 0,
    /** Resultado del último intento de borrar códigos, para mostrarlo. */
    val lastClear: ClearResult? = null,
)

enum class ClearResult { OK, FAILED }

/** ¿La lectura es reciente? (para no mostrar datos viejos como si fueran de ahora). */
fun Reading?.freshAt(nowMs: Long, maxAgeMs: Long = 5_000): Reading? = this?.takeIf { nowMs - it.atMs <= maxAgeMs }
