package com.civiclauncher.app.launcher.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Avisos cortos para el usuario ("La radio no respondió") que nacen lejos de la pantalla (en los
 * repositorios). La raíz del launcher los muestra como mensajes breves.
 */
object Notices {
    private val _flow = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val flow: SharedFlow<String> = _flow.asSharedFlow()

    fun show(text: String) {
        _flow.tryEmit(text)
    }
}
