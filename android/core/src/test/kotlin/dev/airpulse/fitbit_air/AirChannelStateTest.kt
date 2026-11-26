package dev.airpulse.fitbit_air

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AirChannelStateTest {
    @Test fun `no traffic is idle`() {
        assertEquals("idle", AirChannelState().state(10_000))
    }

    @Test fun `foreground polling is polling, then idle once it stops`() {
        val s = AirChannelState()
        s.record(0, 106); s.record(200, 1); s.record(1_000, 106); s.record(1_200, 1)
        assertEquals("polling", s.state(1_500))
        assertEquals("idle", s.state(4_000))
    }

    @Test fun `a transfer burst is syncing, decaying to polling then idle`() {
        val s = AirChannelState()
        for (t in 0L..1_000L step 100) s.record(t, 244)
        assertEquals("syncing", s.state(1_000))
        assertEquals("syncing", s.state(2_400))
        assertEquals("polling", s.state(3_100))
        assertEquals("idle", s.state(3_600))
    }

    @Test fun `short gaps between bursts stay syncing`() {
        val s = AirChannelState()
        for (t in 0L..400L step 100) s.record(t, 244)
        assertEquals("syncing", s.state(400))
        // A 1.5 s gap, then another burst: no flicker to polling in between.
        assertEquals("syncing", s.state(1_900))
        for (t in 1_900L..2_300L step 100) s.record(t, 244)
        assertEquals("syncing", s.state(2_300))
    }
}
