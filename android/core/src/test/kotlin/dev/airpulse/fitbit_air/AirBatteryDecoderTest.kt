package dev.airpulse.fitbit_air

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AirBatteryDecoderTest {
    @Test fun `decodes only battery fields while skipping unrelated identifiers`() {
        // Synthetic fixture, not a captured device-info payload.
        val bytes = byteArrayOf(0x0a, 3, 65, 66, 67, 0x50, 1,
            0x60, 0xa0.toByte(), 0x1f, 0x68, 77, 0x7a, 2, 88, 89)
        assertEquals(mapOf("rawOnCharger" to 1, "rawVoltage" to 4000,
            "rawBatteryLevel" to 77), AirBatteryDecoder.decode(bytes))
    }

    @Test fun `skips fixed-width unknown fields and honors last repeated value`() {
        val bytes = byteArrayOf(9, 0, 0, 0, 0, 0, 0, 0, 0,
            21, 0, 0, 0, 0, 0x68, 42, 0x68, 43)
        assertEquals(mapOf("rawBatteryLevel" to 43), AirBatteryDecoder.decode(bytes))
    }

    @Test fun `bounds response size before parsing`() {
        assertThrows(IllegalArgumentException::class.java) { AirBatteryDecoder.decode(ByteArray(8193)) }
    }

    @Test fun `missing fields do not become invented zeros`() {
        assertTrue(AirBatteryDecoder.decode(byteArrayOf()).isEmpty())
        assertEquals(mapOf("rawBatteryLevel" to 0), AirBatteryDecoder.decode(byteArrayOf(0x68, 0)))
    }

    @Test fun `rejects truncated and invalid wire data`() {
        for (bytes in listOf(byteArrayOf(0x68), byteArrayOf(0x0a, 6, 1),
                byteArrayOf(0), byteArrayOf(0x69, 0, 0, 0, 0, 0, 0, 0, 0),
                ByteArray(12) { 0xff.toByte() })) {
            assertThrows(IllegalArgumentException::class.java) { AirBatteryDecoder.decode(bytes) }
        }
    }
}
