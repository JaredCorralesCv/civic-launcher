package com.civiclauncher.app.ui.design

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Sistema de diseño de Civic Launcher.
 *
 * Reglas para la Mali-450 de tu pantalla (medido en la Fase 0):
 *  - Todo con colores OPACOS. Nada de desenfoques, sombras ni capas semitransparentes encimadas.
 *  - Los estados (presionado, seleccionado) cambian el color de fondo, no agregan capas.
 *  - Pantalla de 1024×600 a 160 dpi: 1 dp = 1 px, así que los tamaños son exactos.
 */
@Immutable
data class CivicPalette(
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val surfacePressed: Color,
    val line: Color,
    val text: Color,
    val textDim: Color,
    val textFaint: Color,
    val accent: Color,
    val accentPressed: Color,
    val onAccent: Color,
    val ok: Color,
    val warn: Color,
    val bad: Color,
    val cool: Color,
    /** Con fondo de pantalla: los mismos colores pero opacos, para diálogos y ventanas encima. */
    val opaque: CivicPalette? = null,
    /** Modo vidrio líquido: los bordes dibujan reflejo y brillo (ver bevelBorder). */
    val isGlass: Boolean = false,
    /**
     * Fondo de lo SELECCIONADO (pestaña activa, botón de la barra abierto). De noche, un vidrio más
     * claro; de día, casi blanco: nunca un bloque gris oscuro sobre un fondo claro.
     */
    val selected: Color = surfaceRaised,
    /** Pista de los relojes y barras: tiene que verse sobre cualquier fondo. */
    val track: Color = surfaceRaised,
)

/**
 * Versión "vidrio" (estilo CarPlay) para cuando hay fondo de pantalla: las superficies dejan ver la
 * imagen. Es transparencia simple (sin desenfoque: la Mali-450 no puede), y solo una o dos capas
 * encimadas, así que sigue siendo barato de dibujar.
 */
fun CivicPalette.glass(): CivicPalette = if (isDark) {
    copy(
        background = Color.Transparent,
        surface = Color(0x660B0D11),
        surfaceRaised = Color(0x14FFFFFF),
        surfacePressed = Color(0x2EFFFFFF),
        line = Color(0x24FFFFFF),
        opaque = this,
        isGlass = true,
        selected = Color(0x33FFFFFF),
        track = Color(0x24FFFFFF),
    )
} else {
    copy(
        background = Color.Transparent,
        surface = Color(0x8CFFFFFF),
        surfaceRaised = Color(0x66FFFFFF),
        surfacePressed = Color(0x1A000000),
        line = Color(0x66FFFFFF),
        opaque = this,
        isGlass = true,
        selected = Color(0xE6FFFFFF),
        track = Color(0x1F000000),
    )
}

/** Colores de noche (por defecto): fondo casi negro para no deslumbrar al manejar. */
val NightPalette = CivicPalette(
    isDark = true,
    background = Color(0xFF0A0C0F),
    surface = Color(0xFF15181D),
    surfaceRaised = Color(0xFF1D2127),
    surfacePressed = Color(0xFF272C34),
    line = Color(0xFF2A2F37),
    text = Color(0xFFF1F3F6),
    textDim = Color(0xFF9BA4B0),
    textFaint = Color(0xFF656D79),
    accent = Color(0xFF3B8BFF),
    accentPressed = Color(0xFF2F70CC),
    onAccent = Color(0xFFFFFFFF),
    ok = Color(0xFF2FD27A),
    warn = Color(0xFFFFB224),
    bad = Color(0xFFFF5247),
    cool = Color(0xFF4CC3FF),
)

/** Colores de día: alto contraste para leer con el sol encima de la pantalla. */
val DayPalette = CivicPalette(
    isDark = false,
    background = Color(0xFFF2F4F7),
    surface = Color(0xFFFFFFFF),
    surfaceRaised = Color(0xFFE9EDF2),
    surfacePressed = Color(0xFFDCE2E9),
    line = Color(0xFFD5DBE3),
    text = Color(0xFF0D1117),
    textDim = Color(0xFF515B69),
    textFaint = Color(0xFF8A939F),
    accent = Color(0xFF1F6FE5),
    accentPressed = Color(0xFF1858B8),
    onAccent = Color(0xFFFFFFFF),
    ok = Color(0xFF14A85A),
    warn = Color(0xFFD98A00),
    bad = Color(0xFFE0392E),
    cool = Color(0xFF0E8FCC),
)

/** Espaciados en múltiplos de 4. */
@Immutable
data class CivicSpacing(
    val xs: Dp = 4.dp,
    val s: Dp = 8.dp,
    val m: Dp = 12.dp,
    val l: Dp = 16.dp,
    val xl: Dp = 24.dp,
    val xxl: Dp = 32.dp,
)

/** Tamaños pensados para tocar sin mirar mucho mientras el carro está detenido. */
@Immutable
data class CivicSizes(
    /** Mínimo para cualquier cosa que se toque en el carro (más que los 48 dp de un celular). */
    val touch: Dp = 64.dp,
    val icon: Dp = 28.dp,
    val iconLarge: Dp = 40.dp,
    val dockWidth: Dp = 104.dp,
    val tile: Dp = 132.dp,
)

@Immutable
data class CivicRadii(
    // Esquinas contenidas: más "instrumento" que "app de juguete".
    val s: Dp = 8.dp,
    val m: Dp = 12.dp,
    val l: Dp = 16.dp,
    val xl: Dp = 22.dp,
)

internal val LocalPalette = staticCompositionLocalOf { NightPalette }
internal val LocalSpacing = staticCompositionLocalOf { CivicSpacing() }
internal val LocalSizes = staticCompositionLocalOf { CivicSizes() }
internal val LocalRadii = staticCompositionLocalOf { CivicRadii() }
internal val LocalCivicType = staticCompositionLocalOf { CivicTypography() }
