package dev.airpulse.fitbit_air

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AirProtocolEventsTest {
    @Test fun `decodes unpacked source-traced event identifiers`() {
        // Synthetic protobuf vectors for PING and WRS_ON, not captured packets.
        val bytes = byteArrayOf(8, 0xe7.toByte(), 0xdc.toByte(), 0xa5.toByte(), 0x83.toByte(), 7,
            8, 0xab.toByte(), 0xe6.toByte(), 0xc9.toByte(), 0xbb.toByte(), 7)
        val ids = AirProtocolEventDecoder.decode(bytes)
        assertEquals(listOf(1885957735L, 2003989291L), ids)
        assertEquals(listOf("PING", "WRS_ON"), ids.map(AirProtocolEventDecoder::name))
    }

    @Test fun `accepts packed uint32 and retains unsigned unknown identifiers`() {
        val bytes = byteArrayOf(10, 10, 0xe7.toByte(), 0xdc.toByte(), 0xa5.toByte(), 0x83.toByte(), 7,
            0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 15)
        val ids = AirProtocolEventDecoder.decode(bytes)
        assertEquals(listOf(1885957735L, 4294967295L), ids)
        assertEquals("UNKNOWN", AirProtocolEventDecoder.name(ids.last()))
    }

    @Test fun `skips unrelated fields without returning their contents`() {
        assertEquals(listOf(1L), AirProtocolEventDecoder.decode(byteArrayOf(18, 3, 65, 66, 67, 8, 1)))
        assertTrue(AirProtocolEventDecoder.decode(byteArrayOf()).isEmpty())
    }

    @Test fun `rejects truncated packed fields and invalid or excessive data`() {
        for (bytes in listOf(byteArrayOf(8), byteArrayOf(10, 2, 0x80.toByte()),
                byteArrayOf(10, 1, 0x80.toByte(), 8, 1), byteArrayOf(0),
                byteArrayOf(13, 0, 0, 0, 0), ByteArray(1025))) {
            assertThrows(IllegalArgumentException::class.java) { AirProtocolEventDecoder.decode(bytes) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            AirProtocolEventDecoder.decode(ByteArray(130) { if (it % 2 == 0) 8 else 1 })
        }
    }
}
