package com.civiclauncher.app.launcher.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.StateFlow

/**
 * Como collectAsStateWithLifecycle, pero además se detiene si la pantalla que lo contiene no se ve.
 * Inicio sigue armado detrás de las otras pantallas: sin esto, sus widgets se volvían a calcular al
 * ritmo del OBD aunque nadie los estuviera viendo. Al volver, retoma con el valor más reciente.
 */
@Composable
internal fun <T> StateFlow<T>.collectWhenVisible(): State<T> {
    val active = LocalScreenActive.current
    val owner = LocalLifecycleOwner.current
    val state = remember(this) { mutableStateOf(value) }
    LaunchedEffect(this, active, owner) {
        if (!active) return@LaunchedEffect
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) { collect { state.value = it } }
    }
    return state
}
