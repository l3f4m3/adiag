package com.adiag.obd

import com.adiag.model.DtcStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class DtcParserTest {

    private fun codes(reply: String, mode: String = "03") =
        DtcParser.parse(reply, mode).map { it.code to it.ecu }

    @Test fun `single frame con dos codigos`() {
        assertEquals(
            listOf("P0171" to "7E8", "P0300" to "7E8"),
            codes("7E80643020171030000")
        )
    }

    @Test fun `codigo de chasis desde el modulo ABS`() {
        assertEquals(
            listOf("U0123" to "760"),
            codes("760064301C1230000\n7E8064300000000000")
        )
    }

    @Test fun `reensambla multiframe ISO-TP`() {
        assertEquals(
            listOf("P0171", "P0300", "P0420", "P0442", "U0100"),
            codes("7E8100C430501710300\n7E82104200442C10000").map { it.first }
        )
    }

    @Test fun `varios modulos responden`() {
        assertEquals(
            listOf("P0171" to "7E8", "U0123" to "760"),
            codes("7E80643010171000000\n760064301C1230000")
        )
    }

    @Test fun `sin codigos almacenados`() {
        assertEquals(emptyList<Pair<String, String>>(), codes("7E8064300000000000"))
    }

    @Test fun `modo 07 marca pendientes`() {
        val r = DtcParser.parse("7E80647010171000000", "07")
        assertEquals(DtcStatus.PENDING, r.single().status)
    }

    @Test fun `decodifica las cuatro familias de letra`() {
        assertEquals("P0171", DtcParser.decode(0x01, 0x71))
        assertEquals("C0123", DtcParser.decode(0x41, 0x23))
        assertEquals("B1318", DtcParser.decode(0x93, 0x18))
        assertEquals("U0100", DtcParser.decode(0xC1, 0x00))
    }

    @Test fun `respuestas de error no son validas`() {
        assertEquals(false, "NO DATA".isValidObdReply())
        assertEquals(false, "SEARCHING...".isValidObdReply())
        assertEquals(true, "7E80643020171030000".isValidObdReply())
    }
}
