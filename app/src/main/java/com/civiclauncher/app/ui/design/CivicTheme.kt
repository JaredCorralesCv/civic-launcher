package com.civiclauncher.app.ui.design

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.text.TextStyle

/** Acceso a los tokens: `Civic.colors.accent`, `Civic.type.title`, `Civic.space.l`... */
object Civic {
    val colors: CivicPalette
        @Composable @ReadOnlyComposable get() = LocalPalette.current
    val type: CivicTypography
        @Composable @ReadOnlyComposable get() = LocalCivicType.current
    val space: CivicSpacing
        @Composable @ReadOnlyComposable get() = LocalSpacing.current
    val sizes: CivicSizes
        @Composable @ReadOnlyComposable get() = LocalSizes.current
    val radii: CivicRadii
        @Composable @ReadOnlyComposable get() = LocalRadii.current
}

/**
 * Tema de la app. [night] elige la paleta; en la Fase 2 cambiará solo según la hora
 * o las luces del carro. También configura Material 3 para que sus componentes usen Inter y estos colores.
 */
@Composable
fun CivicDesignTheme(night: Boolean = true, content: @Composable () -> Unit) {
    val p = if (night) NightPalette else DayPalette
    val type = CivicTypography()
    val scheme = if (night) {
        darkColorScheme(
            primary = p.accent, onPrimary = p.onAccent, background = p.background, onBackground = p.text,
            surface = p.surface, onSurface = p.text, surfaceVariant = p.surfaceRaised, onSurfaceVariant = p.textDim,
            outline = p.line, error = p.bad,
        )
    } else {
        lightColorScheme(
            primary = p.accent, onPrimary = p.onAccent, background = p.background, onBackground = p.text,
            surface = p.surface, onSurface = p.text, surfaceVariant = p.surfaceRaised, onSurfaceVariant = p.textDim,
            outline = p.line, error = p.bad,
        )
    }
    val material = Typography(
        displayLarge = type.display, headlineMedium = type.headline, titleLarge = type.title,
        titleMedium = type.title.copy(fontSize = type.label.fontSize), bodyLarge = type.body,
        bodyMedium = type.bodySmall, labelLarge = type.label, labelMedium = type.caption,
    )
    CompositionLocalProvider(
        LocalPalette provides p,
        LocalCivicType provides type,
        LocalSpacing provides CivicSpacing(),
        LocalSizes provides CivicSizes(),
        LocalRadii provides CivicRadii(),
    ) {
        MaterialTheme(colorScheme = scheme, typography = material) {
            CompositionLocalProvider(LocalContentColor provides p.text, content = content)
        }
    }
}

internal fun TextStyle.colored(c: androidx.compose.ui.graphics.Color) = copy(color = c)
