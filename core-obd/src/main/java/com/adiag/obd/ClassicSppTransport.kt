package com.adiag.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Transporte Bluetooth Classic (SPP) hacia el adaptador.
 *
 * Estrategia de conexion, en orden:
 *   1. RFCOMM inseguro con el UUID estandar de SPP. Es el que funciona con la
 *      mayoria de clones ELM327 y **no exige emparejamiento previo**, asi que
 *      evita por completo el problema del PIN.
 *   2. RFCOMM seguro con el mismo UUID, para adaptadores que si exigen enlace.
 *   3. createRfcommSocket(1) por reflexion, para clones que no publican el
 *      registro SDP de SPP.
 *
 * Antes de cualquier intento se cancela el discovery: un escaneo activo hace
 * fallar la conexion RFCOMM, y como el picker de la app escanea para encontrar
 * el adaptador, es muy probable que siga corriendo al llegar aqui.
 */
@SuppressLint("MissingPermission")
class ClassicSppTransport(private val device: BluetoothDevice) : ObdTransport {

    override val name: String get() = device.name ?: device.address

    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    override suspend fun connect(): Result<Unit> = withContext(Dispatchers.IO) {
        // Un discovery en curso rompe RFCOMM. Siempre cancelarlo primero.
        runCatching { BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery() }

        val attempts: List<Pair<String, () -> BluetoothSocket>> = listOf(
            "SPP inseguro" to {
                device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
            },
            "SPP seguro" to {
                device.createRfcommSocketToServiceRecord(SPP_UUID)
            },
            "canal 1 directo" to {
                device.javaClass
                    .getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    .invoke(device, 1) as BluetoothSocket
            },
        )

        val errors = mutableListOf<String>()
        for ((label, factory) in attempts) {
            val result = runCatching {
                val s = factory()
                s.connect()
                socket = s
                input = s.inputStream
                output = s.outputStream
            }
            if (result.isSuccess) return@withContext Result.success(Unit)
            runCatching { socket?.close() }
            socket = null
            errors += "$label: ${result.exceptionOrNull()?.message ?: "fallo"}"
        }
        Result.failure(ObdException(errors.joinToString(" | ")))
    }

    override suspend fun send(command: String, timeoutMs: Long): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val out = output ?: throw ObdException("Transporte no conectado")
                val inp = input ?: throw ObdException("Transporte no conectado")
                out.write((command + "\r").toByteArray())
                out.flush()
                withTimeoutOrNull(timeoutMs) { readUntilPrompt(inp) }
                    ?: throw ObdException("Timeout esperando respuesta a '$command'")
            }
        }

    private suspend fun readUntilPrompt(inp: InputStream): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        val buf = ByteArray(256)
        while (true) {
            val n = inp.read(buf)
            if (n <= 0) continue
            sb.append(String(buf, 0, n))
            if (sb.contains('>')) break
        }
        sb.toString().substringBefore('>').trim()
    }

    override fun close() {
        runCatching { socket?.close() }
        socket = null; input = null; output = null
    }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
