package dev.airpulse.fitbit_air

import java.util.concurrent.TimeoutException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AirCommandFailureTest {
    @Test fun `an unanswered read before any change is a safe no-reply`() {
        assertEquals("AIR_NO_REPLY", AirCommandFailure.code(TimeoutException(), sent = false, answeredCode = "HAPTIC_TAP_FAILED"))
    }

    @Test fun `an unanswered change is unconfirmed, never a compatibility failure`() {
        assertEquals("HAPTIC_UNCONFIRMED", AirCommandFailure.code(TimeoutException(), sent = true, answeredCode = "HAPTIC_TAP_FAILED"))
    }

    @Test fun `a real rejection keeps the command's own code, also when wrapped`() {
        val rejected = AirCommandFailure.Answered("Air rejected haptic setting change.")
        assertEquals("HAPTIC_TAP_FAILED", AirCommandFailure.code(rejected, sent = true, answeredCode = "HAPTIC_TAP_FAILED"))
        val wrapped = IllegalStateException("Haptic failed; the prior setting was restored.", rejected)
        assertEquals("HAPTIC_FAILED", AirCommandFailure.code(wrapped, sent = true, answeredCode = "HAPTIC_FAILED"))
    }

    @Test fun `only links that did not answer are dropped`() {
        for (code in listOf("AIR_NO_REPLY", "HAPTIC_UNCONFIRMED", "HANDSHAKE_TIMEOUT", "DTLS", "TIMEOUT")) {
            assertTrue(AirCommandFailure.distrustsLink(code), code)
        }
        for (code in listOf("HAPTIC_TAP_FAILED", "BUSY", "CANCELLED", "GATT_DISCONNECTED", "AIR_NOT_FOUND")) {
            assertFalse(AirCommandFailure.distrustsLink(code), code)
        }
    }
}
