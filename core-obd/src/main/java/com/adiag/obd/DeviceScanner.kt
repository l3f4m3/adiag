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
 * enlazarse desde Ajustes del sistema (ver BondManager), asi que el picker
 * debe poder mostrarlo y ofrecerlo aunque todavia no este emparejado.
 */
@SuppressLint("MissingPermission")
class DeviceScanner(private val context: Context) {

    fun discover() = callbackFlow<BluetoothDevice> {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) { close(); return@callbackFlow }

        adapter.bondedDevices.forEach { trySend(it) }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != BluetoothDevice.ACTION_FOUND) return
                intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    ?.let { trySend(it) }
            }
        }
        context.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_FOUND))

        if (adapter.isDiscovering) adapter.cancelDiscovery()
        adapter.startDiscovery()

        awaitClose {
            runCatching { adapter.cancelDiscovery() }
            runCatching { context.unregisterReceiver(receiver) }
        }
    }
}
