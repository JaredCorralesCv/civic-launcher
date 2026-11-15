package com.civiclauncher.obd

/**
 * Las últimas preguntas al adaptador y lo que contestó, para diagnosticar. Ocupa poco (unas cuantas
 * líneas cortas) y se exporta desde Ajustes: con esto el error del acelerador se habría visto en un
 * minuto en lugar de adivinarse.
 */
class ObdLog(private val capacity: Int = 400, private val clock: () -> Long = System::currentTimeMillis) {
    data class Entry(val atMs: Long, val command: String, val reply: String, val tookMs: Long)

    private val entries = ArrayDeque<Entry>()

    @Synchronized
    fun add(command: String, reply: String, tookMs: Long) {
        if (entries.size >= capacity) entries.removeFirst()
        // Las respuestas largas se recortan: lo que importa es ver si vino completa o cortada.
        entries.addLast(Entry(clock(), command, reply.replace("\r", "\\r").replace("\n", "").take(MAX_REPLY), tookMs))
    }

    /** Una nota del motor (reconexión, cambio de modo…), para leer el registro con contexto. */
    fun note(text: String) = add("#", text, 0)

    @Synchronized
    fun snapshot(): List<Entry> = entries.toList()

    private companion object {
        const val MAX_REPLY = 160
    }
}
