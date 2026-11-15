package com.civiclauncher.obd

import kotlin.math.abs

/**
 * Consumo de gasolina a partir de lo que dé el carro, en orden de precisión:
 *  1. PID 5E (litros por hora, pocos carros lo tienen),
 *  2. flujo de aire MAF (PID 10),
 *  3. carga absoluta (PID 43): el aire por ciclo que calcula la propia computadora, ya con el
 *     tiempo de válvulas del R18 (en crucero mete menos aire del que la presión sugiere),
 *  4. "densidad de velocidad": presión del múltiple + RPM + temperatura de admisión, con una
 *     eficiencia fija. Tu Civic 2008 (R18 de 1.8 L) tiene sensor MAP y no MAF.
 * Si el carro da la mezcla que pide (PID 44), se usa: en frío y a fondo es más rica que 14.7.
 * Gasolina: mezcla estequiométrica 14.7:1 y densidad 0.745 kg/L.
 */
object FuelModel {
    const val DISPLACEMENT_L = 1.8
    /** Eficiencia volumétrica promedio de un motor atmosférico como el R18. */
    const val VOLUMETRIC_EFFICIENCY = 0.85
    private const val AFR = 14.7
    private const val FUEL_DENSITY_G_PER_L = 745.0
    private const val R_AIR = 287.05
    /** Gramos por litro de aire a 25 °C y 1 atm: la referencia de la carga absoluta (SAE J1979). */
    private const val AIR_G_PER_L_STP = 1.184

    /** Gramos de aire por segundo. */
    fun airFlow(data: VehicleData, nowMs: Long): Double? {
        data[Pids.MAF].freshAt(nowMs)?.let { return it.value }
        val rpm = data[Pids.RPM].freshAt(nowMs)?.value ?: return null
        data[Pids.ABS_LOAD].freshAt(nowMs)?.value?.takeIf { it > 0 }?.let { absLoad ->
            // Carga absoluta = aire por ciclo / (1.184 g/L × cilindrada). Un ciclo son 2 vueltas.
            return absLoad / 100.0 * AIR_G_PER_L_STP * DISPLACEMENT_L * (rpm / 120.0)
        }
        val map = data[Pids.INTAKE_MAP].freshAt(nowMs)?.value ?: return null
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
        // La computadora lo dice: sistema de combustible en 4 (lazo abierto) con el pie fuera del acelerador
        // y en movimiento = corte de inyección. Sin ese dato, se adivina con la carga baja.
        val fuelSystem = data[Pids.FUEL_SYSTEM].freshAt(nowMs, 3_000)?.value?.toInt()
        val footOff = throttle == null || throttle < 16
        if (speed > 15 && rpm != null && rpm > 1_100 && footOff && fuelSystem == 4) return 0.0
        if (fuelSystem == null && speed > 20 && rpm != null && rpm > 1_300 && (load != null && load < 12) && footOff) return 0.0
        val lambda = data[Pids.COMMANDED_AFR].freshAt(nowMs)?.value?.takeIf { it in 0.7..1.3 } ?: 1.0
        return air / (AFR * lambda) / FUEL_DENSITY_G_PER_L * 3600
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
 * ~1.99 m de circunferencia), automático de 5 velocidades (2.666, 1.534, 1.022, 0.721 y 0.525, con
 * diferencial de 4.437). null si va en neutral, se está cambiando o el convertidor patina de más.
 */
object GearEstimator {
    /** RPM por cada km/h en cada marcha (antes era la tabla de la manual: en tu automático no cuadraba la 1a). */
    private val RPM_PER_KMH = doubleArrayOf(99.3, 57.1, 38.1, 26.9, 19.6)

    /** En D, avanzando despacio, el motor va a ~700 rpm: con 900 la marcha no salía en tráfico. */
    const val MIN_RPM = 650.0

    /** Cambia si cambia la tabla: así no se cargan relaciones aprendidas con otra. */
    const val TABLE_ID = "at5"

    /**
     * Margen para decir "esta marcha": 12 % (el convertidor de torque patina un poco al acelerar), más
     * lo que mueve la velocidad entera del OBD (±1 km/h pesa mucho más a 10 km/h que a 80). Gana la
     * marcha más cercana; el margen solo descarta lo que no cuadra con ninguna.
     */
    internal fun tolerance(speedKmh: Double): Double = (0.12 + 1.0 / speedKmh).coerceAtMost(0.25)

    /** Tabla de fábrica (copia), punto de partida de [GearTracker]. */
    fun defaultRatios(): DoubleArray = RPM_PER_KMH.copyOf()

    fun estimate(rpm: Double?, speedKmh: Double?): Int? {
        if (rpm == null || speedKmh == null || speedKmh < 8 || rpm < MIN_RPM) return null
        val ratio = rpm / speedKmh
        val best = RPM_PER_KMH.indices.minBy { abs(RPM_PER_KMH[it] - ratio) }
        val error = abs(RPM_PER_KMH[best] - ratio) / RPM_PER_KMH[best]
        // 8 %: más flojo confundiría el clutch pisado con la marcha de al lado.
        return if (error < tolerance(speedKmh)) best + 1 else null
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

    /** [nowMs]: el reloj de las lecturas (no salta). [wallMs]: la hora de verdad, solo para mostrar cuándo empezó. */
    fun update(data: VehicleData, nowMs: Long, wallMs: Long = nowMs): TripStats {
        val rpm = data[Pids.RPM].freshAt(nowMs)?.value
        val running = rpm != null && rpm > 300
        val prev = lastMs
        lastMs = nowMs
        if (!running) return stats
        if (stats.startedAtMs == 0L) stats = stats.copy(startedAtMs = wallMs)
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

/**
 * Un momento medido de dos formas. Estas pantallas corrigen la hora al agarrar GPS, así que el reloj
 * de pared puede saltar a media vuelta; el contador desde el encendido no salta, pero vuelve a cero
 * con cada reinicio.
 */
data class TimeMark(val bootId: String, val elapsedMs: Long, val wallMs: Long) {
    /**
     * Tiempo transcurrido hasta [now]. En el mismo encendido, por el contador. Entre reinicios no queda
     * más que el reloj de pared; si sale negativo (hora aún sin corregir), se toma como 0.
     */
    fun msUntil(now: TimeMark): Long =
        if (bootId.isNotEmpty() && bootId == now.bootId) now.elapsedMs - elapsedMs
        else (now.wallMs - wallMs).coerceAtLeast(0)
}

const val WARM_C = 105.0
const val HOT_C = 110.0

/** Con el motor encendido y por debajo de esto un buen rato, el alternador no está cargando. */
const val CHARGE_WARN_V = 12.2

/** Avisos que conviene mostrar mientras manejas. */
enum class AlertLevel { INFO, WARN, DANGER }

data class VehicleAlert(val id: String, val level: AlertLevel, val title: String, val detail: String)

object VehicleAlerts {
    fun evaluate(data: VehicleData, diag: Diagnostics, nowMs: Long): List<VehicleAlert> = buildList {
        val coolant = data[Pids.COOLANT_TEMP].freshAt(nowMs, 30_000)?.value
        val rpm = data[Pids.RPM].freshAt(nowMs)?.value
        val battery = data.battery?.takeIf { nowMs - it.atMs < 30_000 }?.value
        if (diag.milOn) add(VehicleAlert("mil", AlertLevel.WARN, "Foco Check Engine encendido", "${diag.stored.size.coerceAtLeast(diag.dtcCount)} código(s) de falla. Revisa Diagnóstico."))
        // En tráfico con el aire puesto un Honda llega a ~100-103 °C antes de que entre el ventilador:
        // eso es normal. Aviso desde 105 °C sostenidos; peligro desde 110 °C.
        if (coolant != null && coolant >= HOT_C) add(VehicleAlert("hot", AlertLevel.DANGER, "Motor sobrecalentado", "%.0f °C. Oríllate y apaga el motor.".format(coolant)))
        else if (coolant != null && coolant >= WARM_C) add(VehicleAlert("warm", AlertLevel.WARN, "Motor muy caliente", "%.0f °C. Vigila la temperatura.".format(coolant)))
        // Los Honda de esos años bajan el alternador a propósito (~12.4-12.8 V) con poca carga eléctrica,
        // y el voltaje lo mide el adaptador (±0.3 V): con 13.0 V el aviso salía sin falla de verdad.
        if (battery != null && rpm != null && rpm > 500 && battery < CHARGE_WARN_V) add(VehicleAlert("charge", AlertLevel.WARN, "La batería no está cargando", "%.1f V con el motor encendido. Revisa el alternador.".format(battery)))
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
