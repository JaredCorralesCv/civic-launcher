package com.civiclauncher.app.launcher.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode(val label: String) {
    AUTO("Automático"),
    NIGHT("Noche"),
    DAY("Día"),
}

/** Cómo se ve Inicio. */
enum class HomeStyle(val label: String, val description: String) {
    ORIGINAL("Original", "Mapa grande, música y vehículo en tarjetas"),
    SPORT("Sport", "Tablero deportivo: tacómetro, viaje y el carro al centro"),
}

/** Ajustes del launcher. Cada valor es un flujo: la interfaz se actualiza sola al cambiarlo. */
class LauncherPrefs(context: Context) {
    private val sp = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    private val _themeMode = MutableStateFlow(
        runCatching { ThemeMode.valueOf(sp.getString("theme", null) ?: "") }.getOrDefault(ThemeMode.AUTO),
    )
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        sp.edit().putString("theme", mode.name).apply()
    }

    private val _homeStyle = MutableStateFlow(
        runCatching { HomeStyle.valueOf(sp.getString("home_style", null) ?: "") }.getOrDefault(HomeStyle.ORIGINAL),
    )
    val homeStyle: StateFlow<HomeStyle> = _homeStyle.asStateFlow()

    fun setHomeStyle(style: HomeStyle) {
        _homeStyle.value = style
        sp.edit().putString("home_style", style.name).apply()
    }

    private val _sportSlots = MutableStateFlow(
        sp.getString("sport_slots", null).orEmpty().split(',').mapNotNull { e ->
            e.split('=').takeIf { it.size == 2 }?.let { it[0] to it[1] }
        }.toMap(),
    )
    /** Inicio Sport: qué widget va en cada lugar (lugar → widget). Lo que falta usa el de fábrica. */
    val sportSlots: StateFlow<Map<String, String>> = _sportSlots.asStateFlow()

    fun setSportSlots(slots: Map<String, String>) {
        _sportSlots.value = slots
        // commit (no apply): se escribe en el momento; al quitar la llave la pantalla se apaga de golpe.
        sp.edit().putString("sport_slots", slots.entries.joinToString(",") { "${it.key}=${it.value}" }).commit()
    }

    private val _sportArrangement = MutableStateFlow(sp.getString("sport_arrangement", "grid") ?: "grid")
    /** Inicio Sport: diseño elegido ("grid" cuadrícula, "map" mapa grande). */
    val sportArrangement: StateFlow<String> = _sportArrangement.asStateFlow()

    fun setSportArrangement(id: String) {
        _sportArrangement.value = id
        sp.edit().putString("sport_arrangement", id).commit()
    }

    private val _githubRepo = MutableStateFlow(sp.getString("github_repo", "").orEmpty())
    /** Repositorio de GitHub con las versiones, en formato "usuario/repositorio". */
    val githubRepo: StateFlow<String> = _githubRepo.asStateFlow()

    fun setGithubRepo(repo: String) {
        val clean = repo.trim().removePrefix("https://github.com/").trim('/')
        _githubRepo.value = clean
        sp.edit().putString("github_repo", clean).apply()
    }

    private val _mapHeadingUp = MutableStateFlow(sp.getBoolean("map_heading_up", true))
    /** El mapa gira para que lo que tienes enfrente quede arriba (por defecto). Falso = norte arriba. */
    val mapHeadingUp: StateFlow<Boolean> = _mapHeadingUp.asStateFlow()

    fun setMapHeadingUp(on: Boolean) {
        _mapHeadingUp.value = on
        sp.edit().putBoolean("map_heading_up", on).apply()
    }

    private val _autoHideBar = MutableStateFlow(sp.getBoolean("auto_hide_bar", true))
    /** La barra de abajo se esconde sola tras unos segundos sin tocar la pantalla. */
    val autoHideBar: StateFlow<Boolean> = _autoHideBar.asStateFlow()

    fun setAutoHideBar(on: Boolean) {
        _autoHideBar.value = on
        sp.edit().putBoolean("auto_hide_bar", on).apply()
    }

    /** Que la música empiece sola al conectar el celular por Bluetooth (activado por defecto). */
    var autoplayBluetooth: Boolean
        get() = sp.getBoolean("autoplay_bt", true)
        set(v) = sp.edit().putBoolean("autoplay_bt", v).apply()

    private val _accelTimer = MutableStateFlow(sp.getBoolean("accel_timer", false))
    /** Desempeño: medir de 0 a 100 al arrancar desde parado (apagado de fábrica). */
    val accelTimer: StateFlow<Boolean> = _accelTimer.asStateFlow()

    fun setAccelTimer(on: Boolean) {
        _accelTimer.value = on
        sp.edit().putBoolean("accel_timer", on).commit()
    }

    /** Mejor tiempo de 0 a 100 km/h en milisegundos (0 = todavía no hay). */
    var best0to100Ms: Long
        get() = sp.getLong("best_0_100_ms", 0L)
        set(v) { sp.edit().putLong("best_0_100_ms", v).commit() }

    /** Ya pedimos el permiso de ubicación una vez; no insistir en cada arranque. */
    var askedLocation: Boolean
        get() = sp.getBoolean("asked_location", false)
        set(v) = sp.edit().putBoolean("asked_location", v).apply()
}

/** Modo automático: de noche entre las 19:00 y las 7:00. */
fun ThemeMode.isNight(hour: Int): Boolean = when (this) {
    ThemeMode.NIGHT -> true
    ThemeMode.DAY -> false
    ThemeMode.AUTO -> hour >= 19 || hour < 7
}
