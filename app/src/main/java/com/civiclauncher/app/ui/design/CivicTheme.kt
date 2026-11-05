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
fun CivicDesignTheme(night: Boolean = true, glass: Boolean = false, content: @Composable () -> Unit) {
    // Material (diálogos, campos de texto) siempre con los colores opacos; lo de Civic, en vidrio si hay fondo.
    val base = if (night) NightPalette else DayPalette
    val p = if (glass) base.glass() else base
    val type = CivicTypography()
    val scheme = if (night) {
        darkColorScheme(
            primary = base.accent, onPrimary = base.onAccent, background = base.background, onBackground = base.text,
            surface = base.surface, onSurface = base.text, surfaceVariant = base.surfaceRaised, onSurfaceVariant = base.textDim,
            outline = base.line, error = base.bad,
        )
    } else {
        lightColorScheme(
            primary = base.accent, onPrimary = base.onAccent, background = base.background, onBackground = base.text,
            surface = base.surface, onSurface = base.text, surfaceVariant = base.surfaceRaised, onSurfaceVariant = base.textDim,
            outline = base.line, error = base.bad,
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

/** Para diálogos y ventanas que flotan encima: superficies opacas aunque haya fondo de pantalla. */
@Composable
fun OpaqueSurfaces(content: @Composable () -> Unit) {
    val p = LocalPalette.current
    CompositionLocalProvider(LocalPalette provides (p.opaque ?: p), content = content)
}

internal fun TextStyle.colored(c: androidx.compose.ui.graphics.Color) = copy(color = c)
