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
 * Empareja por Bluetooth Classic respondiendo automaticamente al tipo de
 * solicitud que pida el adaptador.
 *
 * Muchos clones ELM327 (incluido el NexLink) no completan el emparejamiento
 * iniciado desde Ajustes del sistema, asi que la propia app llama a
 * createBond() y responde al ACTION_PAIRING_REQUEST sin pasar por esa UI.
 *
 * El punto que fallaba en la primera version: no todos los adaptadores piden
 * PIN fijo (variante PIN). Algunos negocian por SSP y solo piden confirmar
 * "si/no" (variante CONSENT o PASSKEY_CONFIRMATION), y en ese caso inyectar
 * un PIN no hace nada — Android espera respuesta a la variante correcta y,
 * si no llega, deja caer el enlace. Ahora se inspecciona la variante real
 * (EXTRA_PAIRING_VARIANT, publica) y se responde con el metodo que le
 * corresponde.
 *
 * `setPin()` y `setPairingConfirmation()` no son parte del SDK publico
 * (marcados @hide), asi que se invocan por reflexion — el mismo mecanismo
 * que usan Torque y otras apps OBD para este problema.
 */
@SuppressLint("MissingPermission")
object BondManager {

    const val DEFAULT_PIN = "1234"

    private const val VARIANT_PIN = 0
    private const val VARIANT_PASSKEY_CONFIRMATION = 2
    private const val VARIANT_CONSENT = 3

    private fun injectPin(device: BluetoothDevice, pin: String): Boolean = runCatching {
        val m = device.javaClass.getMethod("setPin", ByteArray::class.java)
        m.invoke(device, pin.toByteArray()) as Boolean
    }.getOrDefault(false)

    private fun confirmPairing(device: BluetoothDevice, accept: Boolean): Boolean = runCatching {
        val m = device.javaClass.getMethod("setPairingConfirmation", Boolean::class.javaPrimitiveType)
        m.invoke(device, accept) as Boolean
    }.getOrDefault(false)

    /** Traduce el codigo de motivo del rechazo, para que un fallo futuro se pueda diagnosticar sin otra vuelta de logs. */
    private fun unbondReason(intent: Intent): String {
        val code = intent.getIntExtra("android.bluetooth.device.extra.REASON", -1)
        return when (code) {
            1 -> "fallo de autenticacion"
            2 -> "el adaptador rechazo el enlace"
            3 -> "cancelado"
            4 -> "el adaptador esta apagado o fuera de rango"
            5 -> "hay un escaneo en curso"
            6 -> "tiempo de espera agotado"
            7 -> "demasiados intentos seguidos"
            8 -> "cancelado por el adaptador"
            9 -> "enlace eliminado"
            else -> "motivo desconocido ($code)"
        }
    }

    suspend fun ensureBonded(
        context: Context,
        device: BluetoothDevice,
        pin: String = DEFAULT_PIN,
        timeoutMs: Long = 20_000,
    ): Result<Unit> {
        if (device.bondState == BluetoothDevice.BOND_BONDED) return Result.success(Unit)

        var failReason = "el adaptador rechazo el enlace"

        val bonded = withTimeoutOrNull(timeoutMs) {
            callbackFlow {
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context, intent: Intent) {
                        val target: BluetoothDevice? =
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        if (target?.address != device.address) return

                        when (intent.action) {
                            BluetoothDevice.ACTION_PAIRING_REQUEST -> {
                                val variant = intent.getIntExtra(
                                    BluetoothDevice.EXTRA_PAIRING_VARIANT, -1
                                )
                                when (variant) {
                                    VARIANT_PIN -> injectPin(device, pin)
                                    VARIANT_PASSKEY_CONFIRMATION, VARIANT_CONSENT ->
                                        confirmPairing(device, true)
                                    else -> {
                                        // Variante no manejada (display passkey, OOB...):
                                        // se intenta confirmar igual, es el mejor esfuerzo
                                        // posible sin pedirle nada a la persona.
                                        confirmPairing(device, true)
                                    }
                                }
                                abortBroadcast()
                            }
                            BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                                val state = intent.getIntExtra(
                                    BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR
                                )
                                when (state) {
                                    BluetoothDevice.BOND_BONDED -> trySend(true)
                                    BluetoothDevice.BOND_NONE -> {
                                        failReason = unbondReason(intent)
                                        trySend(false)
                                    }
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
                    failReason = "no se pudo iniciar el enlace"
                    trySend(false)
                }

                awaitClose { runCatching { context.unregisterReceiver(receiver) } }
            }.first()
        }

        return when (bonded) {
            true -> Result.success(Unit)
            false -> Result.failure(ObdException("El adaptador rechazo el enlace: $failReason"))
            null -> Result.failure(ObdException("Timeout esperando el emparejamiento"))
        }
    }
}
