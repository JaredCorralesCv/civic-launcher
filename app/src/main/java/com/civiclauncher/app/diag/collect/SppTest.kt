package com.civiclauncher.app.diag.collect

import android.annotation.SuppressLint
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import com.civiclauncher.app.diag.DiagStore
import com.civiclauncher.app.diag.ReportSection
import com.civiclauncher.app.diag.Row
import com.civiclauncher.app.diag.Status
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Prueba de Bluetooth clásico (SPP), el mismo canal que usan los adaptadores OBD2 de Bluetooth 2.0/3.0.
 *
 * Idea: el celular finge ser un adaptador ELM327 (servidor) y la pantalla se conecta a él (cliente),
 * le manda "ATZ" y "ATRV" como lo hará el launcher. Si llega la respuesta, la pila Bluetooth del
 * fabricante sí deja a las apps abrir conexiones SPP, y un adaptador OBD clásico funcionará.
 */
object Spp {
    val UUID_SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    const val FAKE_ELM = "ELM327 v1.5 (prueba Civic)"
    const val RESULT_KEY = "spp"
}

data class SppState(
    val running: Boolean = false,
    val log: List<String> = emptyList(),
    val success: Boolean? = null,
)

private fun stamp() = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

/** Lee hasta encontrar [end] o hasta que se cierre el socket. */
private fun InputStream.readUntil(end: Char, max: Int = 512): String {
    val sb = StringBuilder()
    while (sb.length < max) {
        val b = read()
        if (b < 0) break
        val ch = b.toChar()
        sb.append(ch)
        if (ch == end) break
    }
    return sb.toString()
}

private fun String.visible() = replace("\r", "\\r").replace("\n", "\\n")

/** Se corre en el CELULAR: espera la conexión y responde como un ELM327. */
class SppServer(private val context: Context) {
    private val _state = MutableStateFlow(SppState())
    val state: StateFlow<SppState> = _state.asStateFlow()
    @Volatile private var server: BluetoothServerSocket? = null
    @Volatile private var socket: BluetoothSocket? = null

    private fun log(s: String) = _state.update { it.copy(log = it.log + "${stamp()}  $s") }

    @SuppressLint("MissingPermission")
    fun start(scope: CoroutineScope, timeoutMs: Int = 120_000) {
        if (_state.value.running) return
        val adapter = BluetoothCollector.adapter(context)
        if (adapter == null || !adapter.isEnabled) {
            _state.value = SppState(log = listOf("Enciende el Bluetooth del celular primero."), success = false)
            return
        }
        _state.value = SppState(running = true)
        scope.launch(Dispatchers.IO) {
            try {
                val name = runCatching { adapter.name }.getOrNull() ?: "este celular"
                log("Esperando conexión como \"$name\" (2 min)…")
                val ss = adapter.listenUsingInsecureRfcommWithServiceRecord("Civic SPP test", Spp.UUID_SPP)
                server = ss
                val s = ss.accept(timeoutMs)
                socket = s
                runCatching { ss.close() }
                log("Conectado: ${runCatching { s.remoteDevice.name }.getOrNull() ?: s.remoteDevice.address}")
                val input = s.inputStream
                val output = s.outputStream
                repeat(10) {
                    val cmd = input.readUntil('\r').trim()
                    if (cmd.isEmpty()) return@repeat
                    log("Recibido: $cmd")
                    val reply = when (cmd.uppercase()) {
                        "ATZ" -> Spp.FAKE_ELM
                        "ATRV" -> "12.6V"
                        else -> "OK"
                    }
                    output.write("$reply\r\r>".toByteArray())
                    output.flush()
                    log("Respondido: $reply")
                }
                _state.update { it.copy(success = true) }
            } catch (e: Exception) {
                log("Terminó: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                close()
                _state.update { it.copy(running = false) }
            }
        }
    }

    fun close() {
        runCatching { server?.close() }
        runCatching { socket?.close() }
        server = null
        socket = null
    }
}

/** Se corre en la PANTALLA: se conecta al celular como lo hará con el adaptador OBD. */
class SppClient(private val context: Context, private val store: DiagStore) {
    private val _state = MutableStateFlow(SppState())
    val state: StateFlow<SppState> = _state.asStateFlow()
    @Volatile private var socket: BluetoothSocket? = null

    private fun log(s: String) = _state.update { it.copy(log = it.log + "${stamp()}  $s") }

    @SuppressLint("MissingPermission")
    fun connect(scope: CoroutineScope, address: String, name: String?) {
        if (_state.value.running) return
        val adapter = BluetoothCollector.adapter(context)
        if (adapter == null || !adapter.isEnabled) {
            _state.value = SppState(log = listOf("El Bluetooth de la pantalla está apagado."), success = false)
            return
        }
        _state.value = SppState(running = true)
        scope.launch(Dispatchers.IO) {
            var ok = false
            var method = "—"
            var atz = ""
            var atrv = ""
            var error: String? = null
            try {
                runCatching { adapter.cancelDiscovery() }
                val device = adapter.getRemoteDevice(address)
                log("Conectando a ${name ?: address}…")
                val s = runCatching {
                    method = "sin emparejar (insecure)"
                    device.createInsecureRfcommSocketToServiceRecord(Spp.UUID_SPP).also { it.connect() }
                }.recoverCatching { first ->
                    log("Sin emparejar falló (${first.message}). Probando con emparejamiento…")
                    method = "con emparejamiento (secure)"
                    device.createRfcommSocketToServiceRecord(Spp.UUID_SPP).also { it.connect() }
                }.getOrThrow()
                socket = s
                log("Conectado ($method)")
                // Si el otro lado no contesta en 8 s, cerramos para no quedarnos colgados.
                val watchdog = launch {
                    delay(8_000)
                    log("Sin respuesta en 8 s")
                    runCatching { s.close() }
                }
                s.outputStream.write("ATZ\r".toByteArray())
                s.outputStream.flush()
                atz = s.inputStream.readUntil('>').trim()
                log("ATZ → ${atz.visible()}")
                s.outputStream.write("ATRV\r".toByteArray())
                s.outputStream.flush()
                atrv = s.inputStream.readUntil('>').trim()
                log("ATRV → ${atrv.visible()}")
                watchdog.cancel()
                ok = atz.contains("ELM327")
                log(if (ok) "ÉXITO: la pantalla puede hablar por SPP" else "Respuesta inesperada")
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
                log("Falló: $error")
            } finally {
                runCatching { socket?.close() }
                socket = null
                _state.update { it.copy(running = false, success = ok) }
                store.put(
                    Spp.RESULT_KEY,
                    ReportSection(
                        "Prueba SPP (Bluetooth clásico para OBD)",
                        listOfNotNull(
                            Row("Resultado", if (ok) "Funciona: la pantalla abrió una conexión SPP" else "No funcionó", if (ok) Status.OK else Status.BAD),
                            Row("Método", method),
                            Row("Respuesta a ATZ", atz.visible().ifEmpty { "—" }),
                            Row("Respuesta a ATRV", atrv.visible().ifEmpty { "—" }),
                            error?.let { Row("Error", it, Status.BAD) },
                        ),
                        listOf(
                            if (ok) "Un adaptador OBD de Bluetooth clásico (2.0/3.0, SPP) funcionará."
                            else "Si falla con el celular bien preparado, el OBD tendrá que ser por USB.",
                        ),
                    ),
                )
            }
        }
    }

    fun close() {
        runCatching { socket?.close() }
        socket = null
    }
}
