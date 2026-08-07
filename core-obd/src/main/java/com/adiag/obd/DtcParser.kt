package com.adiag.obd

import com.adiag.model.DtcStatus

data class RawDtc(val code: String, val status: DtcStatus, val ecu: String?)

/**
 * Parser de respuestas OBD con headers activos (ATH1).
 *
 * Trabaja en dos etapas porque con ATH1 el byte PCI queda entre el header y el
 * servicio, y las respuestas de mas de 2 DTCs llegan partidas en frames
 * ISO-TP que hay que reensamblar por ECU antes de decodificar nada.
 */
object DtcParser {

    private val HEADER = Regex("^([0-9A-F]{3}|[0-9A-F]{8})")

    /** Reensambla los frames ISO-TP. Devuelve el payload por ECU. */
    fun reassemble(reply: String): Map<String, String> {
        val done = mutableMapOf<String, String>()
        val pending = mutableMapOf<String, Pair<StringBuilder, Int>>()

        for (raw in reply.lines()) {
            val line = raw.trim().replace(" ", "").uppercase()
            val m = HEADER.find(line) ?: continue
            val ecu = m.value
            val rest = line.substring(m.range.last + 1)
            if (rest.isEmpty()) continue

            when (rest[0]) {
                '0' -> {                                   // single frame
                    val n = rest.getOrNull(1)?.digitToIntOrNull(16) ?: continue
                    if (rest.length >= 2 + n * 2) done[ecu] = rest.substring(2, 2 + n * 2)
                }
                '1' -> {                                   // first frame
                    val n = rest.substring(1, 4).toIntOrNull(16) ?: continue
                    pending[ecu] = StringBuilder(rest.substring(4)) to n
                }
                '2' -> {                                   // consecutive frame
                    val (sb, n) = pending[ecu] ?: continue
                    sb.append(rest.substring(2))
                    if (sb.length >= n * 2) {
                        done[ecu] = sb.substring(0, n * 2)
                        pending.remove(ecu)
                    }
                }
            }
        }
        return done
    }

    /**
     * Dos bytes -> codigo textual.
     * Bits 15-14 dan la letra (P/C/B/U), bits 13-12 el primer digito.
     */
    fun decode(hi: Int, lo: Int): String {
        val letter = "PCBU"[hi shr 6]
        val d1 = (hi shr 4) and 0x03
        return "%c%d%01X%02X".format(letter, d1, hi and 0x0F, lo)
    }

    fun parse(reply: String, mode: String): List<RawDtc> {
        val status = when (mode) {
            "07" -> DtcStatus.PENDING
            "0A" -> DtcStatus.PERMANENT
            else -> DtcStatus.CONFIRMED
        }
        val expected = "%02X".format(mode.toInt(16) + 0x40)
        val out = mutableListOf<RawDtc>()

        for ((ecu, payload) in reassemble(reply)) {
            if (!payload.startsWith(expected)) continue
            // Tras el byte de servicio viene el conteo de DTCs en modos 03/07/0A.
            var body = payload.drop(2)
            if (mode in setOf("03", "07", "0A")) body = body.drop(2)

            var i = 0
            while (i + 4 <= body.length) {
                val hi = body.substring(i, i + 2).toIntOrNull(16)
                val lo = body.substring(i + 2, i + 4).toIntOrNull(16)
                i += 4
                if (hi == null || lo == null) continue
                if (hi == 0 && lo == 0) continue          // relleno
                out += RawDtc(decode(hi, lo), status, ecu)
            }
        }
        return out.distinctBy { it.code }
    }

    fun parseVin(reply: String): String? {
        val hex = reassemble(reply).values
            .firstOrNull { it.startsWith("49") }
            ?.drop(6)                                      // 49 02 <indice>
            ?: return null
        val vin = hex.chunked(2)
            .mapNotNull { it.toIntOrNull(16) }
            .filter { it in 0x30..0x5A }
            .map { it.toChar() }
            .joinToString("")
        return vin.takeIf { it.length >= 17 }?.takeLast(17)
    }
}
