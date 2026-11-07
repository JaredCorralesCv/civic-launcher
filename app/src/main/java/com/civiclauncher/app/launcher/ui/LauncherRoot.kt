package com.civiclauncher.app.launcher.ui

import android.Manifest
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.onStart
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
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
    // La hora cambia cada minuto, pero el tema solo dos veces al día: con derivedStateOf, el
    // minuto nuevo ya no vuelve a dibujar toda la pantalla (antes lo hacía 1,440 veces al día).
    val now = c.clock.now.collectAsStateWithLifecycle()
    val nightState = remember(themeMode) {
        androidx.compose.runtime.derivedStateOf {
            themeMode.isNight(Calendar.getInstance().apply { timeInMillis = now.value }.get(Calendar.HOUR_OF_DAY))
        }
    }
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

    val night by nightState
    val wallpaper by c.wallpapers.image.collectAsStateWithLifecycle()
    val brightness by c.wallpapers.brightness.collectAsStateWithLifecycle()
    // El velo se "hornea" una sola vez dentro de la imagen (en segundo plano): así la pantalla dibuja
    // una capa completa menos en CADA cuadro. La Mali-450 tiene poca capacidad de relleno.
    val backdrop by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, wallpaper, night, brightness) {
        val img = wallpaper
        value = if (img == null) null else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val veil = if (night) android.graphics.Color.argb(((0.30f + 0.45f * brightness) * 255).toInt(), 0, 0, 0)
            else android.graphics.Color.argb(((0.15f + 0.45f * (1f - brightness)) * 255).toInt(), 255, 255, 255)
            val bmp = img.asAndroidBitmap().copy(android.graphics.Bitmap.Config.ARGB_8888, true)
            android.graphics.Canvas(bmp).drawColor(veil)
            bmp.asImageBitmap()
        }
    }
    CivicDesignTheme(night = night, glass = wallpaper != null) {
        Box(Modifier.fillMaxSize().background((Civic.colors.opaque ?: Civic.colors).background)) {
            // Fondo con el velo ya incluido: de noche oscurece más los fondos claros; de día aclara
            // los oscuros. Viene del tamaño exacto de la pantalla, así que se dibuja sin escalar.
            backdrop?.let { img ->
                androidx.compose.foundation.Image(
                    img,
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // Con Diagnóstico abierto, el launcher sigue armado pero sin dibujarse: al volver, Inicio y su
            // mapa siguen ahí (antes se destruían y el mapa se cargaba desde cero).
            Box(Modifier.fillMaxSize().visibleOnly(dest != Dest.DIAG)) {
                LauncherContent(dest, night) { dest = it }
            }
            if (dest == Dest.DIAG) DiagHost(onBack = { dest = Dest.SETTINGS })
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
    // Los toques van por un canal, no por un estado: así tocar la pantalla ya no vuelve a componer
    // toda la pantalla (solo reinicia la cuenta regresiva).
    val touches = remember { kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1) }
    LaunchedEffect(barVisible, autoHide, dest) {
        if (barVisible && autoHide) {
            touches.onStart { emit(Unit) }.collectLatest {
                delay(BAR_TIMEOUT_MS)
                barVisible = false
            }
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            // Cualquier toque reinicia la cuenta (sin consumir el toque: la pantalla lo recibe igual).
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        // Solo al apoyar el dedo: cada movimiento redibujaría la pantalla sin necesidad.
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        if (e.type == androidx.compose.ui.input.pointer.PointerEventType.Press) touches.tryEmit(Unit)
                    }
                }
            },
    ) {
        // Con la barra escondida, las tarjetas NO se estiran: conservan su tamaño y bajan a quedar
        // centradas en la pantalla (la mitad del espacio de la barra arriba y la otra mitad abajo).
        val shown = barVisible || !autoHide
        // Se guarda como State (sin "by"): el valor animado se lee solo al acomodar y al dibujar, así
        // los 15 cuadros de la animación mueven la pantalla sin volver a componerla entera.
        val shift = androidx.compose.animation.core.animateDpAsState(
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
                    .offset { androidx.compose.ui.unit.IntOffset(0, shift.value.roundToPx()) }
                    // Inicio y Apps son dos "páginas": deslizar a la izquierda abre Apps; a la derecha, vuelve.
                    .pageSwipe(enabled = dest == Dest.HOME || dest == Dest.APPS) { toLeft ->
                        if (toLeft && dest == Dest.HOME) go(Dest.APPS)
                        if (!toLeft && dest == Dest.APPS) go(Dest.HOME)
                    },
            ) {
                // Medido en tu pantalla: cada vez que salías de Inicio, el mapa se destruía y al volver se
                // volvía a abrir y a dibujar entero (decenas de mosaicos subidos a la GPU, tirones de
                // 150 ms). Ahora Inicio vive siempre: solo deja de dibujarse mientras ves otra pantalla.
                androidx.compose.runtime.CompositionLocalProvider(LocalBarShift provides shift) {
                    Box(Modifier.fillMaxSize().visibleOnly(dest == Dest.HOME)) {
                        androidx.compose.runtime.CompositionLocalProvider(LocalScreenActive provides (dest == Dest.HOME)) {
                            HomeScreen(onOpen = go)
                        }
                    }
                    // Apps también vive siempre: armar la cuadrícula con todos los íconos tardaba ~300 ms
                    // cada vez que deslizabas desde Inicio. Se crea la primera vez que la abres.
                    var appsCreated by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
                    if (dest == Dest.APPS) appsCreated = true
                    if (appsCreated) {
                        Box(Modifier.fillMaxSize().visibleOnly(dest == Dest.APPS)) { AppsScreen() }
                    }
                    // Las demás pantallas cambian al instante: deslizarlas costaba más de lo que lucía.
                    if (dest != Dest.HOME && dest != Dest.APPS) Screen(dest, night, go)
                }
                if (dest == Dest.HOME || dest == Dest.APPS) {
                    // En Inicio el contenedor no se mueve (la hora se queda arriba): los puntitos bajan con las tarjetas.
                    PageDots(
                        selected = if (dest == Dest.HOME) 0 else 1,
                        // Arriba y discretos: abajo ya están la barra o su rayita.
                        Modifier.align(Alignment.TopCenter).padding(top = 3.dp),
                    )
                }
            }
            // La barra entra y sale deslizándose por abajo, encima del espacio que le corresponde.
            AnimatedVisibility(
                visible = shown,
                modifier = Modifier.align(Alignment.BottomCenter),
                // Solo deslizar: un desvanecido de elementos traslúcidos encimados obliga a la GPU a pintar en
                // una capa aparte y mezclarla, caro en la Mali-450.
                enter = androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(220)) { it },
                exit = androidx.compose.animation.slideOutVertically(androidx.compose.animation.core.tween(220)) { it },
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
 * ¿La pantalla que contiene esto se está viendo? Inicio sigue compuesto aunque no se vea; con esto
 * el mapa deja de seguir al GPS y de dibujarse mientras tanto.
 */
val LocalScreenActive = androidx.compose.runtime.staticCompositionLocalOf { true }

/**
 * Sigue compuesto (conserva su estado: el mapa abierto, los mosaicos ya dibujados) pero, si no está
 * activo, no se coloca: no se dibuja ni recibe toques.
 */
private fun Modifier.visibleOnly(active: Boolean): Modifier = this.layout { measurable, constraints ->
    val p = measurable.measure(constraints)
    layout(p.width, p.height) { if (active) p.place(0, 0) }
}

/**
 * Cuánto bajar el contenido para quedar centrado cuando la barra de abajo está escondida
 * (0 con la barra visible). Inicio lo usa solo en sus tarjetas, para que la hora no se mueva.
 */
val LocalBarShift = androidx.compose.runtime.staticCompositionLocalOf<androidx.compose.runtime.State<androidx.compose.ui.unit.Dp>> { androidx.compose.runtime.mutableStateOf(0.dp) }

/** Alto que ocupa la barra de abajo: 80 dp de barra + 12 dp de margen inferior. */
internal val BAR_SPACE = 92.dp

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
        Dest.HOME -> Unit // Inicio vive aparte (ver LauncherContent)
        Dest.VEHICLE -> VehicleScreen()
        Dest.MAP -> com.civiclauncher.app.launcher.map.MapScreen(night = night)
        Dest.MUSIC -> MusicScreen(onOpen = go)
        Dest.RADIO -> RadioScreen(onOpen = go)
        Dest.APPS -> Unit // vive aparte (ver LauncherContent)
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
