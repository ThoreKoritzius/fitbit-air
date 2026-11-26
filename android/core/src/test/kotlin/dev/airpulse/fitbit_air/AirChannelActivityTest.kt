package dev.airpulse.fitbit_air

import dev.airpulse.fitbit_air.AirChannelActivity.Decision
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AirChannelActivityTest {
    private fun activity() = AirChannelActivity(
        observeMillis = 700, quietWindowMillis = 1_200, busyBytes = 400, maxHoldMillis = 10_000,
    )

    @Test fun `a silent channel starts after the quiet window`() {
        val a = activity()
        a.begin(0)
        assertEquals(Decision.WAIT, a.decide(200))
        assertEquals(Decision.START, a.decide(300))
        assertEquals("none", a.outcome(300))
    }

    @Test fun `light traffic still observes the full window`() {
        val a = activity()
        a.begin(0)
        a.record(100, 106)
        assertEquals(Decision.WAIT, a.decide(500))
        assertEquals(Decision.START, a.decide(700))
    }

    @Test fun `a warm link with known history starts at once unless busy`() {
        val warm = AirChannelActivity(observeMillis = 0)
        warm.begin(10_000)
        assertEquals(Decision.START, warm.decide(10_000))
        val busy = AirChannelActivity(observeMillis = 0)
        busy.begin(10_000)
        for (t in 9_000L..10_000L step 100) busy.record(t, 244)
        assertEquals(Decision.WAIT, busy.decide(10_000))
    }

    @Test fun `foreground polling alone never holds a command`() {
        val a = activity()
        a.begin(0)
        // Google Health polls about once a second: a 106-byte reply plus a 1-byte ack.
        a.record(100, 106); a.record(300, 1)
        a.record(1_100, 106); a.record(1_300, 1)
        assertEquals(Decision.START, a.decide(1_300))
    }

    @Test fun `a sync burst holds until the channel has been quiet`() {
        val a = activity()
        a.begin(0)
        for (t in 0L..2_000L step 100) a.record(t, 244)
        assertEquals(Decision.WAIT, a.decide(2_000))
        assertEquals(Decision.WAIT, a.decide(3_000))
        assertEquals(Decision.START, a.decide(3_300))
        assertEquals("waited", a.outcome(3_300))
    }

    @Test fun `a sync that never ends is bounded by the maximum hold`() {
        val a = activity()
        a.begin(0)
        for (t in 0L..12_000L step 100) a.record(t, 244)
        assertEquals(Decision.WAIT, a.decide(9_900))
        assertEquals(Decision.START, a.decide(10_000))
        assertEquals("max", a.outcome(10_000))
    }
}
