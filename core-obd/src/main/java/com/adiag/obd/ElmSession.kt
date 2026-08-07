package com.adiag.obd

import com.adiag.model.ConnectionState
import kotlinx.coroutines.delay

/**
 * Handshake y comandos de alto nivel sobre un ELM327.
 *
 * ATH1 es obligatorio: sin headers no se sabe que ECU respondio, y ese dato es
 * lo que permite ubicar la falla en el modelo 3D.
 */
class ElmSession(private val transport: ObdTransport) {

    private val initSequence = listOf(
        "ATZ" to 1_200L,   // reset; descarta el banner de version
        "ATE0" to 200L,    // eco off
        "ATL0" to 200L,    // sin linefeeds
        "ATS0" to 200L,    // sin espacios
        "ATH1" to 200L,    // headers ON
        "ATSP6" to 300L,   // ISO 15765-4 CAN 11 bit / 500k (Fiesta 2016)
    )

    suspend fun open(): ConnectionState {
        transport.connect().onFailure {
            return ConnectionState.Failed(it.message ?: "No se pudo abrir el enlace")
        }
        for ((cmd, wait) in initSequence) {
            val r = transport.send(cmd)
            if (r.isFailure) return ConnectionState.Failed("Fallo en '$cmd'")
            delay(wait)
        }
        // Verifica que el protocolo forzado responda; si no, pasa a auto.
        var ping = transport.send("0100").getOrNull().orEmpty()
        if (!ping.isValidObdReply()) {
            transport.send("ATSP0")
            delay(300)
            ping = transport.send("0100").getOrNull().orEmpty()
            if (!ping.isValidObdReply()) {
                return ConnectionState.Failed("El vehiculo no respondio a 0100")
            }
        }
        val proto = transport.send("ATDPN").getOrNull()?.trim().orEmpty()
        return ConnectionState.Connected(protocolName(proto), transport.name)
    }

    suspend fun readDtcs(mode: String): List<RawDtc> {
        val reply = transport.send(mode).getOrNull() ?: return emptyList()
        if (!reply.isValidObdReply()) return emptyList()
        return DtcParser.parse(reply, mode)
    }

    suspend fun clearDtcs(): Boolean =
        transport.send("04").getOrNull()?.isValidObdReply() == true

    suspend fun readVin(): String? {
        val reply = transport.send("0902").getOrNull() ?: return null
        return DtcParser.parseVin(reply)
    }

    /** UDS modo 22 hacia un modulo especifico (headers del signalset OBDb). */
    suspend fun readUds(header: String, respHeader: String, did: String): String? {
        transport.send("ATSH$header")
        transport.send("ATCRA$respHeader")
        val r = transport.send("22$did").getOrNull()
        transport.send("ATCRA")
        return r?.takeIf { it.isValidObdReply() }
    }

    fun close() = transport.close()

    private fun protocolName(dpn: String) = when (dpn.removePrefix("A").trim()) {
        "6" -> "ISO 15765-4 CAN 11/500"
        "7" -> "ISO 15765-4 CAN 29/500"
        "8" -> "ISO 15765-4 CAN 11/250"
        "9" -> "ISO 15765-4 CAN 29/250"
        "3" -> "ISO 9141-2"
        "5" -> "ISO 14230-4 KWP"
        else -> "Protocolo $dpn"
    }
}

private val ERROR_REPLIES = listOf(
    "NO DATA", "ERROR", "UNABLE TO CONNECT", "BUS INIT", "CAN ERROR",
    "STOPPED", "SEARCHING", "?"
)

fun String.isValidObdReply(): Boolean {
    val up = uppercase()
    return isNotBlank() && ERROR_REPLIES.none { up.contains(it) }
}
