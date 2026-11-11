package com.civiclauncher.app.launcher.nav

/** Lleva la cuenta de los intentos fallidos de recalcular la ruta: cada vez se espera más (4 s, 15 s, 60 s). */
internal class RerouteBackoff {
    private var failures = 0
    private var nextTryAt = 0L

    fun canTry(nowMs: Long): Boolean = nowMs >= nextTryAt

    fun failed(nowMs: Long) {
        nextTryAt = nowMs + WAITS_MS[failures.coerceAtMost(WAITS_MS.lastIndex)]
        failures++
    }

    fun reset() {
        failures = 0
        nextTryAt = 0
    }

    private companion object {
        val WAITS_MS = longArrayOf(4_000, 15_000, 60_000)
    }
}
