package com.civiclauncher.app.launcher.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterVersionTest {
    @Test
    fun comparaPorNumerosNoComoTexto() {
        assertTrue(Updater.isNewer("0.2.10", "0.2.9"))
        assertTrue(Updater.isNewer("1.0", "0.9.9"))
        assertTrue(Updater.isNewer("0.3.0", "0.2.0"))
    }

    @Test
    fun igualOMasViejaNoEsActualizacion() {
        assertFalse(Updater.isNewer("0.2.0", "0.2.0"))
        assertFalse(Updater.isNewer("0.2", "0.2.0"))
        assertFalse(Updater.isNewer("0.1.9", "0.2.0"))
    }

    @Test
    fun ignoraSufijos() {
        assertFalse(Updater.isNewer("0.0.5-diag", "0.2.0"))
        assertTrue(Updater.isNewer("0.3.0-beta", "0.2.0"))
    }
}
