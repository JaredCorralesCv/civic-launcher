package com.civiclauncher.app.launcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.civiclauncher.app.container
import com.civiclauncher.app.launcher.obd.ObdMode
import com.civiclauncher.app.ui.design.AlertBanner
import com.civiclauncher.app.ui.design.ButtonKind
import com.civiclauncher.app.ui.design.Civic
import com.civiclauncher.app.ui.design.CivicButton
import com.civiclauncher.app.ui.design.CivicCard
import com.civiclauncher.app.ui.design.Gauge
import com.civiclauncher.app.ui.design.LinearMeter
import com.civiclauncher.app.ui.design.PhosphorIcons as P
import com.civiclauncher.app.ui.design.Tone
import com.civiclauncher.app.ui.design.ValueCard
import com.civiclauncher.obd.EngineState
import com.civiclauncher.obd.PidSpec
import com.civiclauncher.obd.Pids
import com.civiclauncher.obd.VehicleData

/**
 * Pantalla del vehículo con datos en vivo del OBD (o del simulador).
 * Rendimiento: el flujo de datos se pasa como State y cada medidor lee solo su valor,
 * así un dato nuevo redibuja ese medidor y no la pantalla completa.
 */
@Composable
fun VehicleScreen() {
    val repo = LocalContext.current.container.obd
    val config by repo.config.collectAsStateWithLifecycle()
    val state by repo.state.collectAsStateWithLifecycle()
    val data = repo.data.collectAsStateWithLifecycle()
    var picker by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(Civic.space.xl), verticalArrangement = Arrangement.spacedBy(Civic.space.l)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Vehículo", style = Civic.type.headline, color = Civic.colors.text, modifier = Modifier.weight(1f))
            ObdStatusPill(state, config)
            Spacer(Modifier.width(Civic.space.m))
            CivicButton("Adaptador", { picker = true }, kind = ButtonKind.Secondary, icon = P.Bluetooth)
        }

        if (config.mode == ObdMode.NONE) {
            SetupCard(onChoose = { picker = true }, onSimulator = { repo.useSimulator() })
        } else {
            StateBanner(state, data)
            Dashboard(data)
        }
    }

    if (picker) {
        AdapterPickerDialog(
            onDismiss = { picker = false },
            onPick = { repo.useBluetooth(it.address, it.name); picker = false },
            onSimulator = { repo.useSimulator(); picker = false },
        )
    }
}

@Composable
private fun SetupCard(onChoose: () -> Unit, onSimulator: () -> Unit) {
    CivicCard(Modifier.fillMaxWidth()) {
        Text("Conecta tu adaptador OBD", style = Civic.type.title, color = Civic.colors.text)
        Spacer(Modifier.height(Civic.space.s))
        Text(
            "Con un adaptador ELM327 de Bluetooth clásico (por ejemplo Vgate iCar Pro Bluetooth 3.0) verás aquí RPM, " +
                "velocidad, temperatura, voltaje y más. Mientras llega, puedes probar con el simulador.",
            style = Civic.type.body,
            color = Civic.colors.textDim,
        )
        Spacer(Modifier.height(Civic.space.l))
        Row(horizontalArrangement = Arrangement.spacedBy(Civic.space.m)) {
            CivicButton("Buscar adaptador", onChoose, icon = P.Bluetooth)
            CivicButton("Usar simulador (demo)", onSimulator, kind = ButtonKind.Secondary, icon = P.Cpu)
        }
    }
}

@Composable
private fun StateBanner(state: EngineState, data: State<VehicleData>) {
    when (state) {
        is EngineState.CarOff -> {
            val v = data.value.battery.fresh(60_000)?.value
            AlertBanner(
                P.Power,
                "Carro apagado",
                "El adaptador responde, pero el carro no. Pon la llave en ON." + (v?.let { " Batería: %.1f V".format(it) } ?: ""),
                Tone.Cool,
            )
        }
        is EngineState.Reconnecting -> AlertBanner(
            P.Warning,
            "Sin conexión con el adaptador",
            "${state.reason}. Reintento ${state.attempt} en ${state.retryInMs / 1000} s.",
            Tone.Bad,
        )
        is EngineState.Detecting -> AlertBanner(
            P.Engine,
            "Hablando con el carro…",
            "La primera vez el adaptador busca el protocolo; puede tardar hasta 10 segundos.",
            Tone.Warn,
        )
        else -> Unit
    }
}

@Composable
private fun Dashboard(data: State<VehicleData>) {
    fun value(spec: PidSpec, default: Float): () -> Float = { data.value[spec].fresh()?.value?.toFloat() ?: default }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Civic.space.l)) {
        GaugeCard { Gauge(value(Pids.RPM, 0f), 0f, 7000f, "Motor", "RPM", Modifier.align(Alignment.CenterHorizontally), warnAt = 6000f, dangerAt = 6800f, majorTicks = 7) }
        GaugeCard { Gauge(value(Pids.SPEED, 0f), 0f, 180f, "Velocidad", "km/h", Modifier.align(Alignment.CenterHorizontally)) }
        GaugeCard { Gauge(value(Pids.COOLANT_TEMP, 40f), 40f, 120f, "Temperatura", "°C", Modifier.align(Alignment.CenterHorizontally), warnAt = 100f, dangerAt = 108f, majorTicks = 4) }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Civic.space.l)) {
        BatteryCard(data, Modifier.weight(1f))
        LiveValue(data, Pids.INTAKE_AIR_TEMP, P.Thermometer, "Admisión", Modifier.weight(1f))
        LoadCard(data, Modifier.weight(1.4f))
    }
}

@Composable
private fun RowScope.GaugeCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    CivicCard(Modifier.weight(1f), padding = PaddingValues(Civic.space.m), content = content)
}

@Composable
private fun BatteryCard(data: State<VehicleData>, modifier: Modifier) {
    val v = data.value.battery.fresh(30_000)?.value
    val tone = when {
        v == null -> Tone.Neutral
        v < 11.8 -> Tone.Bad
        v < 12.3 -> Tone.Warn
        else -> Tone.Ok
    }
    ValueCard(P.CarBattery, "Batería", v?.let { "%.1f".format(it) } ?: "--", "V", modifier, tone = tone)
}

@Composable
private fun LiveValue(data: State<VehicleData>, spec: PidSpec, icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier) {
    val v = data.value[spec].fresh()?.value
    ValueCard(icon, label, v?.let { "%.0f".format(it) } ?: "--", spec.unit, modifier)
}

@Composable
private fun LoadCard(data: State<VehicleData>, modifier: Modifier) {
    val load = data.value[Pids.ENGINE_LOAD].fresh()?.value?.toFloat()
    CivicCard(modifier) {
        Text("Carga del motor", style = Civic.type.caption, color = Civic.colors.textDim)
        Spacer(Modifier.height(Civic.space.s))
        Text(load?.let { "${it.toInt()} %" } ?: "--", style = Civic.type.number, color = Civic.colors.text)
        Spacer(Modifier.height(Civic.space.s))
        LinearMeter((load ?: 0f) / 100f, Tone.Accent)
    }
}
