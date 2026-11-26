package dev.airpulse.fitbit_air

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AirHapticSettingsTest {
    @Test fun `single write matches the verified settings body`() {
        assertArrayEquals(byteArrayOf(10, 4, 10, 2, 8, 3), AirHapticSettings.write(3))
    }

    @Test fun `tap sends the alternate strength first and the original last`() {
        assertArrayEquals(byteArrayOf(10, 4, 10, 2, 8, 2, 10, 4, 10, 2, 8, 3), AirHapticSettings.tap(3))
        assertArrayEquals(byteArrayOf(10, 4, 10, 2, 8, 3, 10, 4, 10, 2, 8, 2), AirHapticSettings.tap(2))
    }

    @Test fun `off and unknown strengths are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { AirHapticSettings.tap(1) }
        assertThrows(IllegalArgumentException::class.java) { AirHapticSettings.write(4) }
    }
}
