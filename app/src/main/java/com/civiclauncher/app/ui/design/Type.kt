package com.civiclauncher.app.ui.design

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.civiclauncher.app.R

/** Inter (licencia SIL OFL 1.1, ver licenses/Inter-OFL.txt). Cuatro pesos estáticos: ligeros de cargar. */
val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

/** "tnum": todos los números del mismo ancho, para que los medidores no "bailen" al cambiar. */
private const val TABULAR = "tnum"

@Immutable
data class CivicTypography(
    /** Reloj grande de la pantalla principal. */
    val clock: TextStyle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 96.sp, lineHeight = 104.sp, letterSpacing = (-0.03).em, fontFeatureSettings = TABULAR),
    val display: TextStyle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 56.sp, lineHeight = 64.sp, letterSpacing = (-0.02).em),
    val headline: TextStyle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 40.sp, letterSpacing = (-0.01).em),
    val title: TextStyle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 30.sp),
    val body: TextStyle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 18.sp, lineHeight = 26.sp),
    val bodySmall: TextStyle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 22.sp),
    val label: TextStyle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp),
    val caption: TextStyle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.04.em),
    /** Número grande de un medidor (RPM, km/h, °C). */
    val gaugeValue: TextStyle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 44.sp, fontFeatureSettings = TABULAR),
    val gaugeUnit: TextStyle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 14.sp, letterSpacing = 0.04.em),
    /** Número mediano en tarjetas y pastillas. */
    val number: TextStyle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, fontFeatureSettings = TABULAR),
)
