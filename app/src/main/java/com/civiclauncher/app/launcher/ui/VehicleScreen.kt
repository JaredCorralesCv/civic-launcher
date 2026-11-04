package com.civiclauncher.app.launcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.civiclauncher.app.ui.design.Civic
import com.civiclauncher.app.ui.design.CivicCard
import com.civiclauncher.app.ui.design.Gauge
import com.civiclauncher.app.ui.design.LinearMeter
import com.civiclauncher.app.ui.design.PhosphorIcons as P
import com.civiclauncher.app.ui.design.StatusPill
import com.civiclauncher.app.ui.design.Tone
import com.civiclauncher.app.ui.design.ValueCard
import com.civiclauncher.obd.ElmSession
import com.civiclauncher.obd.ObdResult
import com.civiclauncher.obd.Pids
import com.civiclauncher.obd.SimulatedElm
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Pantalla del vehículo. Por ahora muestra el simulador (modo demo); en la Fase 3 se conecta
 * al adaptador OBD real y en la Fase 4 agrega códigos de falla, consumo y viajes.
 */
@Composable
fun VehicleScreen() {
    val rpm = remember { mutableFloatStateOf(0f) }
    val speed = remember { mutableFloatStateOf(0f) }
    val coolant = remember { mutableFloatStateOf(0f) }
    val load = remember { mutableFloatStateOf(0f) }
    val intake = remember { mutableFloatStateOf(0f) }
    var volts by remember { mutableStateOf("—") }

    // El simulador solo corre mientras esta pantalla está visible.
    LaunchedEffect(Unit) {
        val session = ElmSession(SimulatedElm(latencyMs = 25))
        session.start()
        while (isActive) {
            (session.query(Pids.RPM) as? ObdResult.Value)?.let { rpm.floatValue = it.value.toFloat() }
            (session.query(Pids.SPEED) as? ObdResult.Value)?.let { speed.floatValue = it.value.toFloat() }
            (session.query(Pids.COOLANT_TEMP) as? ObdResult.Value)?.let { coolant.floatValue = it.value.toFloat() }
            (session.query(Pids.ENGINE_LOAD) as? ObdResult.Value)?.let { load.floatValue = it.value.toFloat() }
            (session.query(Pids.INTAKE_AIR_TEMP) as? ObdResult.Value)?.let { intake.floatValue = it.value.toFloat() }
            (session.batteryVoltage() as? ObdResult.Value)?.let { volts = "%.1f".format(it.value) }
            delay(150)
        }
    }

    Column(
        Modifier.fillMaxSize().padding(Civic.space.xl),
        verticalArrangement = Arrangement.spacedBy(Civic.space.l),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Vehículo", style = Civic.type.headline, color = Civic.colors.text, modifier = Modifier.weight(1f))
            StatusPill("Demo con simulador · el OBD real llega en la Fase 3", Tone.Warn)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Civic.space.l)) {
            CivicCard(Modifier.weight(1f), padding = androidx.compose.foundation.layout.PaddingValues(Civic.space.m)) {
                Gauge({ rpm.floatValue }, 0f, 7000f, "Motor", "RPM", Modifier.align(Alignment.CenterHorizontally), warnAt = 6000f, dangerAt = 6800f, majorTicks = 7)
            }
            CivicCard(Modifier.weight(1f), padding = androidx.compose.foundation.layout.PaddingValues(Civic.space.m)) {
                Gauge({ speed.floatValue }, 0f, 180f, "Velocidad", "km/h", Modifier.align(Alignment.CenterHorizontally))
            }
            CivicCard(Modifier.weight(1f), padding = androidx.compose.foundation.layout.PaddingValues(Civic.space.m)) {
                Gauge({ coolant.floatValue }, 40f, 120f, "Temperatura", "°C", Modifier.align(Alignment.CenterHorizontally), warnAt = 100f, dangerAt = 108f, majorTicks = 4)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Civic.space.l)) {
            ValueCard(P.CarBattery, "Batería", volts, "V", Modifier.weight(1f), tone = Tone.Ok)
            ValueCard(P.Thermometer, "Admisión", intake.floatValue.toInt().toString(), "°C", Modifier.weight(1f))
            CivicCard(Modifier.weight(1.4f)) {
                Text("Carga del motor", style = Civic.type.caption, color = Civic.colors.textDim)
                Spacer(Modifier.height(Civic.space.s))
                Text("${load.floatValue.toInt()} %", style = Civic.type.number, color = Civic.colors.text)
                Spacer(Modifier.height(Civic.space.s))
                LinearMeter(load.floatValue / 100f, Tone.Accent)
            }
        }
    }
}
