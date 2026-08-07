package com.adiag.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

@SuppressLint("MissingPermission")
class ClassicSppTransport(private val device: BluetoothDevice) : ObdTransport {

    override val name: String get() = device.name ?: device.address

    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    override suspend fun connect(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
            s.connect()
            socket = s
            input = s.inputStream
            output = s.outputStream
        }.recoverCatching {
            // Fallback conocido para clones ELM327 que no publican el record SPP.
            val fallback = device.javaClass
                .getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                .invoke(device, 1) as BluetoothSocket
            fallback.connect()
            socket = fallback
            input = fallback.inputStream
            output = fallback.outputStream
        }
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
