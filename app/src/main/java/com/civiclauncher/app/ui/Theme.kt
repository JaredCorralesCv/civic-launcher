package com.civiclauncher.app.ui

import androidx.compose.runtime.Composable
import com.civiclauncher.app.ui.design.CivicDesignTheme
import com.civiclauncher.app.ui.design.NightPalette

/**
 * Colores fijos de noche para las pantallas de diagnóstico de la Fase 0.
 * El código nuevo usa `Civic.colors`, que cambia entre día y noche.
 */
object CivicColors {
    val Bg = NightPalette.background
    val Surface = NightPalette.surface
    val Surface2 = NightPalette.surfaceRaised
    val Line = NightPalette.line
    val Text = NightPalette.text
    val TextDim = NightPalette.textDim
    val Accent = NightPalette.accent
    val Ok = NightPalette.ok
    val Warn = NightPalette.warn
    val Bad = NightPalette.bad
}

@Composable
fun CivicTheme(content: @Composable () -> Unit) {
    CivicDesignTheme(night = true, content = content)
}
