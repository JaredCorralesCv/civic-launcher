package com.civiclauncher.app.launcher.ui

import android.Manifest
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.civiclauncher.app.container
import com.civiclauncher.app.diag.DiagApp
import com.civiclauncher.app.launcher.data.isNight
import com.civiclauncher.app.ui.CivicTheme
import com.civiclauncher.app.ui.design.Civic
import com.civiclauncher.app.ui.design.CivicDesignTheme
import com.civiclauncher.app.ui.design.CivicIconButton
import com.civiclauncher.app.ui.design.DockItem
import com.civiclauncher.app.ui.design.PhosphorIcons as P
import kotlinx.coroutines.flow.Flow
import java.util.Calendar

enum class Dest { HOME, VEHICLE, MAP, MUSIC, RADIO, APPS, SETTINGS, CLEANUP, DIAG }

/**
 * Raíz del launcher: dock lateral + pantalla activa.
 * [homePresses] avisa cuando se presiona el botón de inicio estando ya en Civic: regresamos a Inicio.
 */
@Composable
fun LauncherRoot(homePresses: Flow<Unit>) {
    val ctx = LocalContext.current
    val c = ctx.container
    val themeMode by c.launcherPrefs.themeMode.collectAsStateWithLifecycle()
    val now by c.clock.now.collectAsStateWithLifecycle()
    val hour = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)
    var dest by rememberSaveable { mutableStateOf(Dest.HOME) }

    LaunchedEffect(Unit) { homePresses.collect { dest = Dest.HOME } }
    // En un launcher, "atrás" nunca debe sacarte de la pantalla de inicio.
    BackHandler {
        dest = when (dest) {
            Dest.CLEANUP -> Dest.SETTINGS
            else -> Dest.HOME
        }
    }

    // Pedimos la ubicación una sola vez para el velocímetro GPS.
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        c.gps.onPermissionChanged()
    }
    LaunchedEffect(Unit) {
        if (!c.gps.hasPermission() && !c.launcherPrefs.askedLocation) {
            c.launcherPrefs.askedLocation = true
            locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    if (dest == Dest.DIAG) {
        DiagHost(onBack = { dest = Dest.SETTINGS })
        return
    }

    val night = themeMode.isNight(hour)
    val wallpaper by c.wallpapers.image.collectAsStateWithLifecycle()
    val brightness by c.wallpapers.brightness.collectAsStateWithLifecycle()
    CivicDesignTheme(night = night, glass = wallpaper != null) {
        Box(Modifier.fillMaxSize().background((Civic.colors.opaque ?: Civic.colors).background)) {
            wallpaper?.let { img ->
                // Ya viene del tamaño exacto de la pantalla: se dibuja tal cual, sin escalar.
                androidx.compose.foundation.Image(
                    img,
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                // Velo para que el texto se lea con cualquier fondo: de noche oscurece más los fondos
                // claros; de día aclara más los oscuros.
                Box(
                    Modifier.fillMaxSize().background(
                        if (night) androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.30f + 0.45f * brightness)
                        else androidx.compose.ui.graphics.Color.White.copy(alpha = 0.15f + 0.45f * (1f - brightness)),
                    ),
                )
            }
            LauncherContent(dest, night) { dest = it }
        }
    }
}

/**
 * Pantalla activa + barra inferior (sin fondo propio: encima va el fondo de pantalla o el color liso).
 *
 * La barra se esconde sola tras [BAR_TIMEOUT_MS] sin tocar la pantalla (si está activado en Ajustes)
 * y la pantalla aprovecha ese espacio. Para volver a abrirla: deslizar hacia arriba desde el borde
 * de abajo, o tocar la rayita que queda visible.
 */
@Composable
private fun LauncherContent(dest: Dest, night: Boolean, go: (Dest) -> Unit) {
    val prefs = LocalContext.current.container.launcherPrefs
    val autoHide by prefs.autoHideBar.collectAsStateWithLifecycle()
    var barVisible by remember { mutableStateOf(true) }
    var lastTouch by remember { mutableLongStateOf(0L) }
    LaunchedEffect(barVisible, lastTouch, autoHide, dest) {
        if (barVisible && autoHide) {
            delay(BAR_TIMEOUT_MS)
            barVisible = false
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            // Cualquier toque reinicia la cuenta (sin consumir el toque: la pantalla lo recibe igual).
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        lastTouch = SystemClock.uptimeMillis()
                    }
                }
            },
    ) {
        // Con la barra escondida, las tarjetas NO se estiran: conservan su tamaño y bajan a quedar
        // centradas en la pantalla (la mitad del espacio de la barra arriba y la otra mitad abajo).
        val shown = barVisible || !autoHide
        val shift by androidx.compose.animation.core.animateDpAsState(
            if (shown) 0.dp else BAR_SPACE / 2,
            androidx.compose.animation.core.tween(250),
            label = "centrar",
        )
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(bottom = BAR_SPACE)
                    // Inicio mueve solo sus tarjetas (la hora se queda arriba); las demás, la pantalla entera.
                    .offset(y = shift)
                    // Inicio y Apps son dos "páginas": deslizar a la izquierda abre Apps; a la derecha, vuelve.
                    .pageSwipe(enabled = dest == Dest.HOME || dest == Dest.APPS) { toLeft ->
                        if (toLeft && dest == Dest.HOME) go(Dest.APPS)
                        if (!toLeft && dest == Dest.APPS) go(Dest.HOME)
                    },
            ) {
                androidx.compose.animation.AnimatedContent(
                    targetState = dest,
                    transitionSpec = {
                        val pages = setOf(Dest.HOME, Dest.APPS)
                        if (initialState in pages && targetState in pages) {
                            // Como pasar de página: Apps entra por la derecha.
                            val forward = targetState == Dest.APPS
                            (androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(260)) { w -> if (forward) w else -w } togetherWith
                                androidx.compose.animation.slideOutHorizontally(androidx.compose.animation.core.tween(260)) { w -> if (forward) -w else w })
                        } else {
                            // El resto cambia al instante (cada animación cuesta en la Mali-450).
                            androidx.compose.animation.EnterTransition.None togetherWith androidx.compose.animation.ExitTransition.None
                        }
                    },
                    label = "pantalla",
                ) { target ->
                    androidx.compose.runtime.CompositionLocalProvider(LocalBarShift provides shift) { Screen(target, night, go) }
                }
                if (dest == Dest.HOME || dest == Dest.APPS) {
                    // En Inicio el contenedor no se mueve (la hora se queda arriba): los puntitos bajan con las tarjetas.
                    PageDots(
                        selected = if (dest == Dest.HOME) 0 else 1,
                        Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp),
                    )
                }
            }
            // La barra entra y sale deslizándose por abajo, encima del espacio que le corresponde.
            AnimatedVisibility(
                visible = shown,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = androidx.compose.animation.slideInVertically { it } + fadeIn(),
                exit = androidx.compose.animation.slideOutVertically { it } + fadeOut(),
            ) {
                BottomBar(dest, go)
            }
        }
        if (!barVisible && autoHide) {
            BarHandle(Modifier.align(Alignment.BottomCenter)) { barVisible = true }
        }
    }
}

/** Rayita al borde de abajo: tocarla o deslizar hacia arriba desde ahí abre la barra. */
@Composable
private fun BarHandle(modifier: Modifier, onOpen: () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .height(44.dp)
            .pointerInput(Unit) {
                detectVerticalDragGestures { _, dragAmount -> if (dragAmount < -4f) onOpen() }
            }
            .pointerInput(Unit) { detectTapGestures { onOpen() } },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            Modifier
                .padding(bottom = 7.dp)
                .width(96.dp)
                .height(5.dp)
                .clip(RoundedCornerShape(50))
                .background(Civic.colors.textDim),
        )
    }
}

private const val BAR_TIMEOUT_MS = 6_000L

/**
 * Cuánto bajar el contenido para quedar centrado cuando la barra de abajo está escondida
 * (0 con la barra visible). Inicio lo usa solo en sus tarjetas, para que la hora no se mueva.
 */
val LocalBarShift = androidx.compose.runtime.compositionLocalOf { 0.dp }

/** Alto que ocupa la barra de abajo: 80 dp de barra + 12 dp de margen inferior. */
private val BAR_SPACE = 92.dp

/** Dos puntitos que dicen en qué página estás (Inicio / Apps), como en CarPlay. */
@Composable
private fun PageDots(selected: Int, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(2) { i ->
            Box(
                Modifier
                    .height(6.dp)
                    .width(if (i == selected) 16.dp else 6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (i == selected) Civic.colors.text else Civic.colors.textFaint),
            )
        }
    }
}

/**
 * Detecta un deslizamiento horizontal claro (no un toque ni un desplazamiento vertical) sin estorbar
 * a lo de adentro: escucha antes que los hijos y solo "se queda" con el gesto cuando ya es
 * evidentemente horizontal, para que no cuente como toque en la tarjeta donde empezó.
 */
@Composable
private fun Modifier.pageSwipe(enabled: Boolean, onSwipe: (toLeft: Boolean) -> Unit): Modifier {
    // Siempre la versión más nueva (la pantalla actual), sin reiniciar el detector a medio gesto.
    val current by androidx.compose.runtime.rememberUpdatedState(onSwipe)
    return if (!enabled) this else this.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var dx = 0f
            var dy = 0f
            var horizontal = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                val delta = change.position - change.previousPosition
                dx += delta.x
                dy += delta.y
                if (!horizontal && kotlin.math.abs(dx) > 32.dp.toPx() && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 2) horizontal = true
                // Ya es un deslizamiento: que los hijos no lo tomen como toque.
                if (horizontal) change.consume()
            }
            if (horizontal && kotlin.math.abs(dx) > 90.dp.toPx()) current(dx < 0)
        }
    }
}

@Composable
private fun Screen(dest: Dest, night: Boolean, go: (Dest) -> Unit) {
    when (dest) {
        Dest.HOME -> HomeScreen(onOpen = go)
        Dest.VEHICLE -> VehicleScreen()
        Dest.MAP -> com.civiclauncher.app.launcher.map.MapScreen(night = night)
        Dest.MUSIC -> MusicScreen(onOpen = go)
        Dest.RADIO -> RadioScreen(onOpen = go)
        Dest.APPS -> AppsScreen()
        Dest.SETTINGS -> SettingsScreen(
            onOpenDiagnostics = { go(Dest.DIAG) },
            onOpenCleanup = { go(Dest.CLEANUP) },
        )
        Dest.CLEANUP -> CleanupScreen(onBack = { go(Dest.SETTINGS) })
        Dest.DIAG -> Unit
    }
}

/** Herramienta de diagnóstico de la Fase 0, dentro de Ajustes. */
@Composable
private fun DiagHost(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    CivicTheme {
        Column(Modifier.fillMaxSize().background(Civic.colors.background)) {
            Row(
                Modifier.fillMaxWidth().background(Civic.colors.surface).padding(horizontal = Civic.space.m, vertical = Civic.space.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CivicIconButton(P.CaretLeft, "Volver a Ajustes", onBack)
                Spacer(Modifier.width(Civic.space.m))
                Text("Diagnóstico", style = Civic.type.title, color = Civic.colors.text)
            }
            Box(Modifier.weight(1f)) { DiagApp() }
        }
    }
}
