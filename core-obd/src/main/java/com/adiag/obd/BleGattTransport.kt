package com.adiag.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * BLE para clones ELM327. Los UUID de servicio varian por fabricante (FFF0,
 * FFE0, 18F0...), asi que no se hardcodean: se descubren en runtime tomando la
 * caracteristica con NOTIFY como RX y la escribible como TX.
 */
@SuppressLint("MissingPermission")
class BleGattTransport(
    private val context: Context,
    private val device: BluetoothDevice,
) : ObdTransport {

    override val name: String get() = device.name ?: device.address

    private var gatt: BluetoothGatt? = null
    private var tx: BluetoothGattCharacteristic? = null
    private var rx: BluetoothGattCharacteristic? = null
    private val buffer = StringBuilder()
    private var pending: CompletableDeferred<String>? = null
    private val ready = CompletableDeferred<Result<Unit>>()

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) g.discoverServices()
            else if (!ready.isCompleted) ready.complete(
                Result.failure(ObdException("Desconectado durante el enlace"))
            )
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            for (service in g.services) {
                for (ch in service.characteristics) {
                    val props = ch.properties
                    if (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) rx = ch
                    val writable = BluetoothGattCharacteristic.PROPERTY_WRITE or
                        BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
                    if (props and writable != 0) tx = ch
                }
            }
            val rxCh = rx
            if (rxCh == null || tx == null) {
                ready.complete(Result.failure(ObdException("No se hallo un par TX/RX compatible")))
                return
            }
            g.setCharacteristicNotification(rxCh, true)
            rxCh.getDescriptor(CCCD)?.let {
                it.value = BluetoothGattDescriptorEnableNotify
                g.writeDescriptor(it)
            }
            ready.complete(Result.success(Unit))
        }

        @Deprecated("API < 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            buffer.append(String(ch.value))
            if (buffer.contains('>')) {
                pending?.complete(buffer.toString().substringBefore('>').trim())
                buffer.clear()
            }
        }
    }

    override suspend fun connect(): Result<Unit> {
        gatt = device.connectGatt(context, false, callback)
        return withTimeoutOrNull(15_000) { ready.await() }
            ?: Result.failure(ObdException("Timeout conectando por BLE"))
    }

    override suspend fun send(command: String, timeoutMs: Long): Result<String> {
        val g = gatt ?: return Result.failure(ObdException("Transporte no conectado"))
        val txCh = tx ?: return Result.failure(ObdException("Sin caracteristica TX"))
        val deferred = CompletableDeferred<String>()
        pending = deferred
        buffer.clear()
        txCh.value = (command + "\r").toByteArray()
        txCh.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        g.writeCharacteristic(txCh)
        val res = withTimeoutOrNull(timeoutMs) { deferred.await() }
        pending = null
        return res?.let { Result.success(it) }
            ?: Result.failure(ObdException("Timeout esperando respuesta a '$command'"))
    }

    override fun close() {
        runCatching { gatt?.close() }
        gatt = null
    }

    companion object {
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        val BluetoothGattDescriptorEnableNotify = byteArrayOf(0x01, 0x00)
    }
}
