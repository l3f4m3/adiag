package com.adiag.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow

/**
 * Escanea dispositivos Bluetooth Classic cercanos.
 *
 * Se necesita ademas de `bondedDevices`: el NexLink puede no llegar a
 * enlazarse desde Ajustes del sistema, asi que el picker debe poder mostrarlo
 * y ofrecerlo aunque todavia no este emparejado.
 *
 * Importante: el discovery debe estar detenido antes de abrir un socket
 * RFCOMM, o la conexion falla. Por eso `stop()` es publico y el ViewModel lo
 * llama en cuanto la persona elige un adaptador, sin esperar a que termine la
 * ventana de busqueda.
 */
@SuppressLint("MissingPermission")
class DeviceScanner(private val context: Context) {

    private val adapter: BluetoothAdapter? get() = BluetoothAdapter.getDefaultAdapter()

    fun stop() {
        runCatching { adapter?.cancelDiscovery() }
    }

    fun discover() = callbackFlow<BluetoothDevice> {
        val bt = adapter
        if (bt == null) { close(); return@callbackFlow }

        bt.bondedDevices.forEach { trySend(it) }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != BluetoothDevice.ACTION_FOUND) return
                intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    ?.let { trySend(it) }
            }
        }
        context.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_FOUND))

        if (bt.isDiscovering) bt.cancelDiscovery()
        bt.startDiscovery()

        awaitClose {
            runCatching { bt.cancelDiscovery() }
            runCatching { context.unregisterReceiver(receiver) }
        }
    }
}
