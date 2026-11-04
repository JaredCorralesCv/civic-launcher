package com.civiclauncher.app.launcher.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import com.civiclauncher.obd.ObdTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Conexión Bluetooth clásica (SPP) con un adaptador ELM327.
 *
 * En la Fase 0 comprobamos que tu pantalla abre conexiones SPP "sin emparejar". Igual probamos tres
 * formas, en orden, porque cada marca de adaptador se comporta distinto:
 *  1. sin emparejar (la que funcionó en tu pantalla),
 *  2. emparejado (algunos adaptadores lo exigen; PIN normal: 1234 o 0000),
 *  3. canal 1 directo (truco clásico para clones de ELM327 que no anuncian el servicio).
 */
class BluetoothSppTransport(private val context: Context, private val address: String) : ObdTransport {
    @Volatile private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    private fun adapter(): BluetoothAdapter =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: throw IOException("Esta pantalla no tiene Bluetooth accesible")

    @SuppressLint("MissingPermission")
    override suspend fun open() = withContext(Dispatchers.IO) {
        val adapter = adapter()
        if (!adapter.isEnabled) throw IOException("El Bluetooth está apagado")
        runCatching { adapter.cancelDiscovery() } // buscar dispositivos hace muy lenta la conexión
        val device = adapter.getRemoteDevice(address)
        val attempts = listOf<(BluetoothDevice) -> BluetoothSocket>(
            { it.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
            { it.createRfcommSocketToServiceRecord(SPP_UUID) },
            { d -> d.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType).invoke(d, 1) as BluetoothSocket },
        )
        var lastError: Exception? = null
        for (make in attempts) {
            val s = runCatching { make(device) }.getOrNull() ?: continue
            try {
                s.connect()
                socket = s
                input = s.inputStream
                output = s.outputStream
                return@withContext
            } catch (e: Exception) {
                lastError = e
                runCatching { s.close() }
            }
        }
        throw IOException("No se pudo conectar con el adaptador (${lastError?.message ?: "sin respuesta"})")
    }

    override suspend fun send(command: String, timeoutMs: Long): String = withContext(Dispatchers.IO) {
        val s = socket ?: throw IOException("El adaptador no está conectado")
        val inp = input!!
        val out = output!!
        // Descarta basura que haya quedado de una respuesta anterior.
        while (inp.available() > 0) inp.read()
        out.write("$command\r".toByteArray(Charsets.US_ASCII))
        out.flush()
        coroutineScope {
            var timedOut = false
            // Una lectura de Bluetooth no se puede interrumpir: si tarda demasiado, cerramos el socket.
            val watchdog = launch {
                delay(timeoutMs)
                timedOut = true
                runCatching { s.close() }
            }
            try {
                readUntilPrompt(inp)
            } catch (e: IOException) {
                throw if (timedOut) IOException("El adaptador no respondió en ${timeoutMs / 1000} s") else e
            } finally {
                watchdog.cancel()
            }
        }
    }

    private fun readUntilPrompt(inp: InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val b = inp.read()
            if (b < 0) throw IOException("Se cerró la conexión con el adaptador")
            val c = b.toChar()
            if (c == '>') return sb.toString()
            if (c != '\u0000') sb.append(c)
            if (sb.length > 4096) throw IOException("Respuesta demasiado larga")
        }
    }

    override suspend fun close() = withContext(Dispatchers.IO) {
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
    }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
