package com.adiag.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Empareja por Bluetooth Classic inyectando el PIN automaticamente.
 *
 * Muchos clones ELM327 (incluido el NexLink) no disparan el dialogo de PIN
 * cuando el emparejamiento se inicia desde Ajustes del sistema: Android arma
 * el bonding en silencio y lo deja caer. La forma confiable es que la propia
 * app llame a createBond() y responda al ACTION_PAIRING_REQUEST con el PIN
 * fijo del adaptador (1234), sin pasar por la UI de Ajustes.
 *
 * `BluetoothDevice.setPin()` no es parte del SDK publico (esta marcado
 * @hide desde API temprana), asi que no se puede llamar directamente: el
 * compilador no lo resuelve. Se invoca por reflexion, que es el mismo
 * mecanismo que usan Torque y otras apps OBD para este mismo problema.
 */
@SuppressLint("MissingPermission")
object BondManager {

    const val DEFAULT_PIN = "1234"

    private fun injectPin(device: BluetoothDevice, pin: String): Boolean = runCatching {
        val method = device.javaClass.getMethod("setPin", ByteArray::class.java)
        method.invoke(device, pin.toByteArray()) as Boolean
    }.getOrDefault(false)

    suspend fun ensureBonded(
        context: Context,
        device: BluetoothDevice,
        pin: String = DEFAULT_PIN,
        timeoutMs: Long = 20_000,
    ): Result<Unit> {
        if (device.bondState == BluetoothDevice.BOND_BONDED) return Result.success(Unit)

        val bonded = withTimeoutOrNull(timeoutMs) {
            callbackFlow {
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context, intent: Intent) {
                        val target: BluetoothDevice? =
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        if (target?.address != device.address) return

                        when (intent.action) {
                            BluetoothDevice.ACTION_PAIRING_REQUEST -> {
                                // Inyecta el PIN y corta el broadcast antes de
                                // que Android muestre su propio dialogo.
                                injectPin(device, pin)
                                abortBroadcast()
                            }
                            BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                                val state = intent.getIntExtra(
                                    BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR
                                )
                                when (state) {
                                    BluetoothDevice.BOND_BONDED -> trySend(true)
                                    BluetoothDevice.BOND_NONE -> trySend(false)
                                }
                            }
                        }
                    }
                }
                val filter = IntentFilter().apply {
                    addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
                    addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                    priority = IntentFilter.SYSTEM_HIGH_PRIORITY
                }
                context.registerReceiver(receiver, filter)

                if (!device.createBond()) {
                    trySend(false)
                }

                awaitClose { runCatching { context.unregisterReceiver(receiver) } }
            }.first()
        }

        return when (bonded) {
            true -> Result.success(Unit)
            false -> Result.failure(ObdException("El adaptador rechazo el PIN"))
            null -> Result.failure(ObdException("Timeout esperando el emparejamiento"))
        }
    }
}
