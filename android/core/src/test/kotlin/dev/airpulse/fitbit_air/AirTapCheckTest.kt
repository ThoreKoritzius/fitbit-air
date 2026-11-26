package dev.airpulse.fitbit_air

import dev.airpulse.fitbit_air.AirTapCheck.Failed
import dev.airpulse.fitbit_air.AirTapCheck.Reason
import dev.airpulse.fitbit_air.AirTapCheck.Verified
import dev.airpulse.fitbit_air.AirTapCheck.Write
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AirTapCheckTest {
    @Test fun `an accepted tap at the wearer's strength is verified`() {
        assertEquals(Verified, AirTapCheck.after(original = 2, target = 2, write = Write.ACCEPTED, readBack = 2))
    }

    @Test fun `a rejected tap at the wearer's strength fails although the strength still matches`() {
        // Review: the unchanged strength read after a rejection matched the target and counted as verified.
        assertEquals(Failed(Reason.REJECTED, restore = false),
            AirTapCheck.after(original = 2, target = 2, write = Write.REJECTED, readBack = 2))
    }

    @Test fun `a rejection that left the alternate strength behind restores`() {
        assertEquals(Failed(Reason.REJECTED, restore = true),
            AirTapCheck.after(original = 2, target = 2, write = Write.REJECTED, readBack = 3))
    }

    @Test fun `a lost reply at the wearer's strength is unconfirmed, never verified`() {
        assertEquals(Failed(Reason.UNCONFIRMED, restore = false),
            AirTapCheck.after(original = 3, target = 3, write = Write.NO_REPLY, readBack = 3))
    }

    @Test fun `a lost reply to a strength change is verified by reading the new strength`() {
        assertEquals(Verified, AirTapCheck.after(original = 2, target = 3, write = Write.NO_REPLY, readBack = 3))
    }

    @Test fun `a lost reply to a strength change that did not arrive is unconfirmed`() {
        assertEquals(Failed(Reason.UNCONFIRMED, restore = false),
            AirTapCheck.after(original = 2, target = 3, write = Write.NO_REPLY, readBack = 2))
    }

    @Test fun `an accepted change that reads back otherwise is not verified`() {
        assertEquals(Failed(Reason.NOT_VERIFIED, restore = false),
            AirTapCheck.after(original = 2, target = 3, write = Write.ACCEPTED, readBack = 2))
    }

    @Test fun `an unreadable strength after a change restores, after a same-strength tap does not`() {
        assertEquals(Failed(Reason.UNCONFIRMED, restore = true),
            AirTapCheck.after(original = 2, target = 3, write = Write.ACCEPTED, readBack = null))
        assertEquals(Failed(Reason.UNCONFIRMED, restore = false),
            AirTapCheck.after(original = 2, target = 2, write = Write.NO_REPLY, readBack = null))
    }
}
