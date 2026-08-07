package com.adiag.obd

/**
 * Transporte crudo hacia el adaptador. Dos implementaciones: Bluetooth Classic
 * (SPP) y BLE (GATT). El NEXAS NX230731 es dual-mode, asi que se intenta SPP
 * primero por estabilidad en Android y se cae a BLE si el bonding falla.
 */
interface ObdTransport {
    val name: String
    suspend fun connect(): Result<Unit>
    /** Envia un comando y lee hasta el prompt '>' del ELM327. */
    suspend fun send(command: String, timeoutMs: Long = 5_000): Result<String>
    fun close()
}

class ObdException(message: String, cause: Throwable? = null) : Exception(message, cause)
