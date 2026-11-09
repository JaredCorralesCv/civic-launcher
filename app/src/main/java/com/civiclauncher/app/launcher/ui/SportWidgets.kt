package com.civiclauncher.app.launcher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.civiclauncher.app.container
import com.civiclauncher.app.launcher.media.MediaState
import com.civiclauncher.app.launcher.nav.blockTouches
import com.civiclauncher.app.ui.design.Civic
import com.civiclauncher.app.ui.design.PhosphorIcons as P
import com.civiclauncher.app.ui.design.bevelBorder
import com.civiclauncher.obd.EngineState
import com.civiclauncher.obd.Pids
import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// =================================================================== Lugares y widgets

/** Tamaño de un lugar de Inicio Sport: un widget solo cabe en lugares de su tamaño. */
enum class SlotSize(val label: String, val previewW: Int, val previewH: Int) {
    // Medidas reales aproximadas de cada lugar en la pantalla de 1024×600 (para las vistas previas).
    SMALL("Chico", 265, 240),
    LARGE("Grande", 405, 480),
    TALL("Alto", 295, 480),
    STRIP("Franja", 340, 110),
    WIDE("Panorámico", 600, 480),
    FULL("Completa", 990, 470),
}

/** Grupos del menú de widgets. */
enum class WidgetCategory(val label: String) {
    SPEED("Velocidad"),
    RPM("RPM"),
    TIME("Reloj y fecha"),
    VEHICLE("Vehículo"),
    TRIP("Viaje"),
    MAP("Mapa"),
    MEDIA("Multimedia"),
    FULLSCREEN("Pantallas completas"),
}

/** Cómo se reparte Inicio Sport. Cada diseño tiene sus lugares y guarda su propio acomodo. */
enum class HomeArrangement(val id: String, val label: String, val description: String) {
    GRID("grid", "Cuadrícula", "Dos chicos a la izquierda, uno grande al centro y uno alto a la derecha"),
    MAP("map", "Mapa grande", "Tres a la izquierda (franja, chico, franja) y uno panorámico a la derecha"),
    FULL("full", "Pantalla completa", "Un solo widget en toda la pantalla: tablero, medidores, desempeño o el mapa"),
}

/** Los lugares de cada diseño de Inicio Sport. */
enum class Slot(val id: String, val size: SlotSize, val label: String, val arrangement: HomeArrangement) {
    TOP_LEFT("s1", SlotSize.SMALL, "Arriba a la izquierda", HomeArrangement.GRID),
    BOTTOM_LEFT("s2", SlotSize.SMALL, "Abajo a la izquierda", HomeArrangement.GRID),
    CENTER("c", SlotSize.LARGE, "Centro", HomeArrangement.GRID),
    RIGHT("t", SlotSize.TALL, "Derecha", HomeArrangement.GRID),
    MAP_TOP("m1", SlotSize.STRIP, "Franja de arriba", HomeArrangement.MAP),
    MAP_MIDDLE("m2", SlotSize.SMALL, "En medio", HomeArrangement.MAP),
    MAP_BOTTOM("m3", SlotSize.STRIP, "Franja de abajo", HomeArrangement.MAP),
    MAP_BIG("mw", SlotSize.WIDE, "Grande", HomeArrangement.MAP),
    FULL_SLOT("f1", SlotSize.FULL, "Pantalla completa", HomeArrangement.FULL),
}

/** Los widgets que se pueden poner, y en qué tamaños de lugar caben. */
enum class SportWidget(val id: String, val label: String, val description: String, val icon: ImageVector, val category: WidgetCategory, val sizes: Set<SlotSize>) {
    // Velocidad
    SPEED_STRIP("spd_strip", "Velocidad", "El número grande, fino", P.Gauge, WidgetCategory.SPEED, setOf(SlotSize.STRIP)),
    TACH("tach", "Velocidad y RPM", "Líneas que se iluminan y luz de cambio", P.Gauge, WidgetCategory.SPEED, SMALL),
    SPEED_RINGS("spd_rings", "Doble anillo", "Velocidad, RPM y marcha", P.Gauge, WidgetCategory.SPEED, SMALL),
    SPEED_BAR("spd_bar", "Barras", "Número grande y barras de segmentos", P.Gauge, WidgetCategory.SPEED, SMALL),
    SPEED_CLASSIC("spd_classic", "Clásico", "Escala con zonas de color y aguja", P.Gauge, WidgetCategory.SPEED, SMALL),
    SPEED_SIMPLE("spd_simple", "Simple", "Un círculo fino y el número", P.Gauge, WidgetCategory.SPEED, SMALL),
    SPEED_DIGITAL("spd_digital", "Digital", "El número enorme y la marcha", P.Gauge, WidgetCategory.SPEED, SMALL),
    SPEED_SPORT("spd_sport", "Estándar", "Escala con barrido azul", P.Gauge, WidgetCategory.SPEED, SMALL),
    SPEED_FIRE("spd_fire", "Ardiente", "Ámbar y rojo encendidos", P.Gauge, WidgetCategory.SPEED, SMALL),
    // RPM
    RPM_STRIP("rpm_strip", "RPM en franja", "El número y una barra que se llena", P.Speedometer, WidgetCategory.RPM, setOf(SlotSize.STRIP)),
    RPM_CLASSIC("rpm_classic", "Clásico", "Escala con zonas de color y aguja", P.Speedometer, WidgetCategory.RPM, SMALL),
    RPM_SIMPLE("rpm_simple", "Simple", "Un círculo fino y el número", P.Speedometer, WidgetCategory.RPM, SMALL),
    RPM_DIGITAL("rpm_digital", "Digital", "El número y luces de cambio", P.Speedometer, WidgetCategory.RPM, SMALL),
    RPM_SCIFI("rpm_scifi", "Ciencia ficción", "Marcas finas en cian", P.Speedometer, WidgetCategory.RPM, SMALL),
    RPM_FIRE("rpm_fire", "Ardiente", "Ámbar y rojo encendidos", P.Speedometer, WidgetCategory.RPM, SMALL),
    RPM_ARC("rpm_arc", "Línea", "El número y una línea que se ilumina", P.Speedometer, WidgetCategory.RPM, SMALL),
    RPM_STANDARD("rpm_standard", "Estándar", "Escala con barrido azul", P.Speedometer, WidgetCategory.RPM, SMALL),
    RPM_RING("rpm_ring", "Anillo", "Un anillo que se llena", P.Speedometer, WidgetCategory.RPM, SMALL),
    // Reloj y fecha
    CLOCK("clock", "Reloj", "Hora, fecha y manecillas", P.Clock, WidgetCategory.TIME, setOf(SlotSize.SMALL, SlotSize.STRIP)),
    CLOCK_ANALOG("clock_analog", "Reloj analógico", "Manecillas grandes y la hora", P.Clock, WidgetCategory.TIME, SMALL),
    CALENDAR("calendar", "Calendario", "El día, el mes y la semana", P.CalendarBlank, WidgetCategory.TIME, SMALL),
    SCREENSAVER("screensaver", "Salvapantallas", "Pantalla negra con la hora", P.Moon, WidgetCategory.TIME, setOf(SlotSize.SMALL, SlotSize.STRIP)),
    // Vehículo, viaje, mapa y música
    ROAD("road", "En carretera", "Tu carro por detrás, avanzando a tu velocidad", P.Car, WidgetCategory.VEHICLE, setOf(SlotSize.SMALL, SlotSize.LARGE, SlotSize.TALL, SlotSize.WIDE, SlotSize.FULL)),
    CAR("car", "Mi carro", "El carro desde arriba y sus datos", P.Car, WidgetCategory.VEHICLE, setOf(SlotSize.LARGE, SlotSize.TALL, SlotSize.WIDE)),
    STATS("stats", "Datos del carro", "Batería, motor y consumo", P.CarBattery, WidgetCategory.VEHICLE, SMALL),
    TRIP("trip", "Viaje", "Distancia, tiempo y promedios", P.Timer, WidgetCategory.TRIP, SMALL),
    MAP("map", "Mapa", "Tu ubicación y la navegación", P.MapTrifold, WidgetCategory.MAP, setOf(SlotSize.LARGE, SlotSize.TALL, SlotSize.WIDE, SlotSize.FULL)),
    MUSIC("music", "Música", "Lo que suena, con controles", P.MusicNotes, WidgetCategory.MEDIA, SlotSize.entries.toSet()),
    // Pantallas completas
    FULL_CLUSTER("full_cluster", "Tablero", "Tacómetro y velocímetro grandes, y 4 relojes del motor", P.Gauge, WidgetCategory.FULLSCREEN, setOf(SlotSize.FULL)),
    FULL_GAUGES("full_gauges", "Medidores del motor", "Ocho medidores: RPM, velocidad, acelerador, temperaturas…", P.Speedometer, WidgetCategory.FULLSCREEN, setOf(SlotSize.FULL)),
    FULL_PERFORMANCE("full_perf", "Desempeño", "Cronómetro de 0 a 100 con récord, temperaturas y acelerador", P.Timer, WidgetCategory.FULLSCREEN, setOf(SlotSize.FULL)),
    ;

    /** Velocidad o RPM en medidor: se dibuja con [GaugeWidget]. */
    val isGauge: Boolean get() = (category == WidgetCategory.SPEED || category == WidgetCategory.RPM) && this != TACH && this != SPEED_STRIP && this != RPM_STRIP
}

private val SMALL = setOf(SlotSize.SMALL)

/** Estado del modo edición y cómo se acomoda Inicio Sport. */
object SportLayout {
    /** Editando Inicio (lo enciende el botón "Editar" de la barra). */
    val editing = MutableStateFlow(false)

    /** El menú con todos los widgets está abierto. */
    val menuOpen = MutableStateFlow(false)

    /** El salvapantallas está puesto (lo inicia su widget; tocar la pantalla lo quita). */
    val screensaver = MutableStateFlow(false)

    /** Entrar a editar: se abre de una vez el menú con todos los widgets. */
    fun startEditing() {
        editing.value = true
        menuOpen.value = true
    }

    fun stopEditing() {
        editing.value = false
        menuOpen.value = false
    }

    private val DEFAULTS = mapOf(
        Slot.TOP_LEFT to SportWidget.TACH,
        Slot.BOTTOM_LEFT to SportWidget.TRIP,
        Slot.CENTER to SportWidget.CAR,
        Slot.RIGHT to SportWidget.MAP,
        Slot.MAP_TOP to SportWidget.SPEED_STRIP,
        Slot.MAP_MIDDLE to SportWidget.ROAD,
        Slot.MAP_BOTTOM to SportWidget.MUSIC,
        Slot.MAP_BIG to SportWidget.MAP,
        Slot.FULL_SLOT to SportWidget.FULL_CLUSTER,
    )

    fun slotsOf(arrangement: HomeArrangement) = Slot.entries.filter { it.arrangement == arrangement }

    /** Qué widget va en cada lugar del diseño (lo guardado, o el de fábrica si falta o no cabe). */
    fun resolve(saved: Map<String, String>, arrangement: HomeArrangement): Map<Slot, SportWidget> {
        val slots = slotsOf(arrangement)
        val out = HashMap<Slot, SportWidget>()
        for (slot in slots) {
            val w = SportWidget.entries.firstOrNull { it.id == saved[slot.id] }
            out[slot] = if (w != null && slot.size in w.sizes && w !in out.values) w else DEFAULTS.getValue(slot)
        }
        // Si quedó un widget repetido (por un dato viejo), se vuelve a lo de fábrica de este diseño.
        return if (out.values.toSet().size == out.size) out else slots.associateWith { DEFAULTS.getValue(it) }
    }

    /**
     * Pone [widget] en [slot]. Si ya estaba en otro lugar y lo que hay aquí cabe allá, se
     * intercambian. Devuelve el acomodo nuevo, o null si no se puede.
     */
    fun place(current: Map<Slot, SportWidget>, slot: Slot, widget: SportWidget): Map<Slot, SportWidget>? {
        if (slot.size !in widget.sizes) return null
        val other = current.entries.firstOrNull { it.value == widget && it.key != slot }?.key
        val mine = current.getValue(slot)
        if (other != null && other.size !in mine.sizes) return null
        return current.toMutableMap().apply {
            this[slot] = widget
            if (other != null) this[other] = mine
        }
    }

    /** Lo guardado con el acomodo nuevo de un diseño (el de los otros diseños se conserva). */
    fun save(saved: Map<String, String>, layout: Map<Slot, SportWidget>): Map<String, String> =
        saved + layout.entries.associate { it.key.id to it.value.id }

    /** Lo guardado sin el acomodo de [arrangement] (vuelve a lo de fábrica solo ese diseño). */
    fun reset(saved: Map<String, String>, arrangement: HomeArrangement): Map<String, String> =
        saved - slotsOf(arrangement).map { it.id }.toSet()
}

// =================================================================== Modo edición

/**
 * Encima de cada widget al editar: se oscurece, se marca su borde y al centro aparece el botón para
 * cambiarlo, con el nombre de lo que tiene ahora. Nada de abajo responde mientras tanto.
 */
@Composable
internal fun EditOverlay(widget: SportWidget, onChange: () -> Unit) {
    val shape = RoundedCornerShape(Civic.radii.xl)
    Box(
        Modifier
            .fillMaxSize()
            .blockTouches()
            .clip(shape)
            .background(Color.Black.copy(alpha = 0.55f))
            .border(2.dp, Civic.colors.accent.copy(alpha = 0.85f), shape)
            .clickable(role = Role.Button, onClickLabel = "Cambiar ${widget.label}", onClick = onChange),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(60.dp).clip(CircleShape).background(Civic.colors.accent),
                contentAlignment = Alignment.Center,
            ) { Icon(P.SquaresFourFill, null, tint = Civic.colors.onAccent, modifier = Modifier.size(28.dp)) }
            Spacer(Modifier.height(10.dp))
            // En una pastilla oscura: se lee aunque el widget de abajo sea claro (como el de Viaje).
            Column(
                Modifier.clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.7f)).padding(horizontal = 14.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(widget.label, style = Civic.type.label.copy(fontSize = 15.sp), color = Color.White)
                Text("Toca para cambiarlo", style = Civic.type.caption, color = Color.White.copy(alpha = 0.7f))
            }
        }
    }
}

/** Abre el menú con todos los widgets (al editar, junto a "Listo"). */
@Composable
internal fun AllWidgetsButton(onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Row(
        Modifier
            .height(52.dp)
            .clip(RoundedCornerShape(50))
            .background(if (pressed) Civic.colors.surfacePressed else Civic.colors.surfaceRaised)
            .bevelBorder(RoundedCornerShape(50))
            .clickable(source, indication = null, role = Role.Button, onClickLabel = "Todos los widgets", onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(P.SquaresFourFill, null, tint = Civic.colors.text, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Text("Widgets", style = Civic.type.label.copy(fontWeight = FontWeight.SemiBold), color = Civic.colors.text)
    }
}

/** "Listo": termina de editar. */
@Composable
internal fun DoneEditing(modifier: Modifier) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Row(
        modifier
            .height(52.dp)
            .clip(RoundedCornerShape(50))
            .background(if (pressed) Civic.colors.accentPressed else Civic.colors.accent)
            .clickable(source, indication = null, role = Role.Button, onClickLabel = "Terminar de editar") { SportLayout.stopEditing() }
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(P.CheckCircleFill, null, tint = Civic.colors.onAccent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Text("Listo", style = Civic.type.label.copy(fontWeight = FontWeight.SemiBold), color = Civic.colors.onAccent)
    }
}

// =================================================================== Widgets nuevos

/** Superficie común (la misma de las tarjetas de Inicio). */
@Composable
private fun WidgetSurface(modifier: Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(Civic.radii.xl)
    Box(
        modifier
            .clip(shape)
            .background(Civic.colors.surface)
            .bevelBorder(shape)
            .let { if (onClick != null) it.clickable(role = Role.Button, onClick = onClick) else it },
    ) { content() }
}

/** Datos del carro: batería, motor y consumo, uno debajo de otro, con su color. */
@Composable
internal fun StatsWidget(modifier: Modifier, onClick: () -> Unit) {
    val c = LocalContext.current.container
    val obdState by c.obd.state.collectAsStateWithLifecycle()
    val dataState = c.obd.data.collectAsStateWithLifecycle()
    val live by c.obd.live.collectAsStateWithLifecycle()
    val connected = obdState is EngineState.Connected
    val coolant by remember(connected) { derivedStateOf { if (connected) dataState.value[Pids.COOLANT_TEMP].fresh()?.value?.let { Math.round(it).toDouble() } else null } }
    val battery by remember { derivedStateOf { dataState.value.battery.fresh(30_000)?.value?.let { Math.round(it * 10) / 10.0 } } }
    WidgetSurface(modifier, onClick) {
        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.SpaceEvenly) {
            StatLine(P.CarBattery, "Batería", battery?.let { "%.1f V".format(it) } ?: "--", when {
                battery == null -> Civic.colors.textFaint
                battery!! < 11.9 -> Civic.colors.bad
                else -> Civic.colors.ok
            })
            StatLine(P.Thermometer, "Motor", coolant?.let { "%.0f °C".format(it) } ?: "--", when {
                coolant == null -> Civic.colors.textFaint
                coolant!! >= 105 -> Civic.colors.bad
                coolant!! >= 100 -> Civic.colors.warn
                coolant!! < 70 -> Civic.colors.cool
                else -> Civic.colors.ok
            })
            StatLine(
                P.GasPump, "Consumo",
                live.kmPerLiter?.let { if (it >= 99) "∞ km/L" else "%.1f km/L".format(it) } ?: live.litersPerHour?.let { "%.1f L/h".format(it) } ?: "--",
                if (live.kmPerLiter != null || live.litersPerHour != null) Civic.colors.text else Civic.colors.textFaint,
            )
        }
    }
}

@Composable
private fun StatLine(icon: ImageVector, label: String, value: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Civic.colors.textDim, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, style = Civic.type.label, color = Civic.colors.textDim, modifier = Modifier.weight(1f))
        Text(value, style = Civic.type.title.copy(fontSize = 24.sp, fontWeight = FontWeight.Light, fontFeatureSettings = "tnum"), color = color, maxLines = 1)
    }
}

/**
 * Música. En un lugar chico: portada, canción y controles. En uno grande: la portada grande arriba,
 * la canción y los controles abajo.
 */
@Composable
internal fun MusicWidget(modifier: Modifier, size: SlotSize, onOpen: () -> Unit) {
    val strip = size == SlotSize.STRIP
    val big = size != SlotSize.SMALL && !strip
    val c = LocalContext.current.container
    val state by c.media.state.collectAsStateWithLifecycle()
    val now = (state as? MediaState.Active)?.now
    val tint = now?.artColor
    val shape = RoundedCornerShape(Civic.radii.xl)
    Box(
        modifier
            .clip(shape)
            .background(
                if (tint != null) Brush.verticalGradient(listOf(tint.copy(alpha = 0.55f), Civic.colors.surface))
                else Brush.verticalGradient(listOf(Civic.colors.surface, Civic.colors.surface)),
            )
            .bevelBorder(shape)
            .clickable(role = Role.Button, onClickLabel = "Abrir la música", onClick = onOpen),
    ) {
        @Composable
        fun Cover(m: Modifier) {
            Box(m.clip(RoundedCornerShape(Civic.radii.m)).background(Civic.colors.surfaceRaised), contentAlignment = Alignment.Center) {
                val art = now?.art
                if (art != null) Image(art, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Icon(P.MusicNotes, null, tint = Civic.colors.textFaint, modifier = Modifier.fillMaxSize(0.4f))
            }
        }
        @Composable
        fun Controls(compact: Boolean = false) {
            val b = if (compact) 42.dp else 54.dp
            Row(verticalAlignment = Alignment.CenterVertically) {
                WidgetButton(P.SkipBackFill, "Anterior", now != null, b) { c.media.previous() }
                WidgetButton(if (now?.playing == true) P.PauseFill else P.PlayFill, if (now?.playing == true) "Pausar" else "Reproducir", true, b) {
                    if (now != null) c.media.playPause() else if (!c.media.playBluetooth()) onOpen()
                }
                WidgetButton(P.SkipForwardFill, "Siguiente", now != null, b) { c.media.next() }
            }
        }
        val title = now?.title ?: "Sin música"
        val subtitle = now?.let { it.artist ?: sourceLabel(it.source) } ?: "Toca para elegir"
        if (strip) {
            // En franja: la portada a un lado; la canción arriba y los controles grandes en medio.
            Row(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(Modifier.size(64.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (now != null && now.artist != null) "$title · ${now.artist}" else title,
                        style = Civic.type.label.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                        color = Civic.colors.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Controls()
                }
            }
        } else if (big) {
            Column(Modifier.fillMaxSize().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Cover(Modifier.fillMaxHeight().aspectRatio(1f))
                }
                Spacer(Modifier.height(14.dp))
                Text(title, style = Civic.type.title, color = Civic.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = Civic.type.label, color = Civic.colors.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                Controls()
            }
        } else {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Cover(Modifier.size(72.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(title, style = Civic.type.label.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold), color = Civic.colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(subtitle, style = Civic.type.caption, color = Civic.colors.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Controls() }
            }
        }
    }
}

@Composable
private fun WidgetButton(icon: ImageVector, label: String, enabled: Boolean, size: androidx.compose.ui.unit.Dp = 54.dp, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (pressed) Civic.colors.surfacePressed else Color.Transparent)
            .clickable(source, indication = null, enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = if (enabled) Civic.colors.text else Civic.colors.textFaint, modifier = Modifier.size(size * 0.5f)) }
}
