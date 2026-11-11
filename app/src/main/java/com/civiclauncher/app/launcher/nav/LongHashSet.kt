package com.civiclauncher.app.launcher.nav

/**
 * Conjunto de enteros de 64 bits sin objetos por elemento: ~16 bytes por valor contra ~60 de un
 * HashSet<Long>. [capacity] debe ser potencia de 2.
 */
internal class LongHashSet(capacity: Int = 1 shl 14) {
    private var table = LongArray(capacity)
    var size = 0
        private set

    /** Devuelve false si ya estaba. */
    fun add(value: Long): Boolean {
        // 0 marca un lugar vacío en la tabla.
        val v = if (value == 0L) 1L else value
        if ((size + 1) * 2 > table.size) grow()
        return insert(table, v).also { if (it) size++ }
    }

    private fun insert(into: LongArray, v: Long): Boolean {
        val mask = into.size - 1
        var i = (v xor (v ushr 32)).toInt() and mask
        while (into[i] != 0L) {
            if (into[i] == v) return false
            i = (i + 1) and mask
        }
        into[i] = v
        return true
    }

    private fun grow() {
        val bigger = LongArray(table.size * 2)
        for (v in table) if (v != 0L) insert(bigger, v)
        table = bigger
    }
}
