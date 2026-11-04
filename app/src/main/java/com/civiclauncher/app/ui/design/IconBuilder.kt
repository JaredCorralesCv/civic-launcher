package com.civiclauncher.app.ui.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Convierte las rutas SVG de Phosphor (lienzo de 256×256) en un ImageVector de 24 dp que se puede teñir. */
internal fun phosphor(name: String, vararg paths: String): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 256f,
        viewportHeight = 256f,
    ).apply {
        paths.forEach { d -> addPath(pathData = addPathNodes(d), fill = SolidColor(Color.Black)) }
    }.build()
