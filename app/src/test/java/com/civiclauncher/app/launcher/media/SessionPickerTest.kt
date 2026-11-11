package com.civiclauncher.app.launcher.media

import com.civiclauncher.app.launcher.data.VendorApps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionPickerTest {
    private val bt = "com.android.bluetooth"

    @Test
    fun ganaLaQueSuena() {
        val list = listOf(SessionInfo(bt, playing = false, hasTitle = true), SessionInfo("spotify", playing = true, hasTitle = true))
        assertEquals(1, SessionPicker.pick(list, ""))
    }

    @Test
    fun sinNadaSonandoGanaLaFuenteDelFabricante() {
        val list = listOf(SessionInfo("spotify", false, true), SessionInfo(VendorApps.RADIO, false, false))
        assertEquals(1, SessionPicker.pick(list, VendorApps.RADIO))
    }

    @Test
    fun listaVacia() {
        assertEquals(-1, SessionPicker.pick(emptyList(), VendorApps.RADIO))
    }

    @Test
    fun radioPuestaConCancionEnPausaMuestraRadio() {
        val best = SessionInfo(bt, playing = false, hasTitle = true)
        assertEquals(MusicSource.RADIO, SessionPicker.idleOverride(best, VendorApps.RADIO))
    }

    @Test
    fun radioPuestaSinSesionesMuestraRadio() {
        assertEquals(MusicSource.RADIO, SessionPicker.idleOverride(null, VendorApps.RADIO))
    }

    @Test
    fun siAlgoSuenaSeMuestraEso() {
        assertNull(SessionPicker.idleOverride(SessionInfo(bt, playing = true, hasTitle = true), VendorApps.RADIO))
    }

    @Test
    fun fuenteBluetoothConCancionEnPausaMuestraLaCancion() {
        assertNull(SessionPicker.idleOverride(SessionInfo(bt, false, true), VendorApps.BLUETOOTH))
    }

    @Test
    fun androidAutoComoFuente() {
        assertEquals(MusicSource.ANDROID_AUTO, SessionPicker.idleOverride(SessionInfo(bt, false, true), VendorApps.ANDROID_AUTO))
    }
}
