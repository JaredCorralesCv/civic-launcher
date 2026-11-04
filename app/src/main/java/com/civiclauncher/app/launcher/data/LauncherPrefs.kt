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

    private val _githubRepo = MutableStateFlow(sp.getString("github_repo", "").orEmpty())
    /** Repositorio de GitHub con las versiones, en formato "usuario/repositorio". */
    val githubRepo: StateFlow<String> = _githubRepo.asStateFlow()

    fun setGithubRepo(repo: String) {
        val clean = repo.trim().removePrefix("https://github.com/").trim('/')
        _githubRepo.value = clean
        sp.edit().putString("github_repo", clean).apply()
    }

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
