package dev.airpulse.fitbit_air

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AirWarmLinkTest {
    @Test fun `a freshly subscribed link is not reused until it has settled`() {
        assertFalse(AirWarmLink.settled(readyAtMillis = 10_000, nowMillis = 10_500))
        assertFalse(AirWarmLink.settled(10_000, 10_000 + AirWarmLink.SETTLE_MILLIS - 1))
        assertTrue(AirWarmLink.settled(10_000, 10_000 + AirWarmLink.SETTLE_MILLIS))
    }

    private val deaf = AirWarmLink.DEAF_MILLIS
    private fun idle(now: Long, heard: Long = 0, ready: Long = 0, resubscribed: Long = 0) =
        AirWarmLink.idle(now, heard, ready, resubscribed)

    @Test fun `a link that keeps hearing the Air is left alone`() {
        assertEquals(AirWarmLink.Idle.OK, idle(now = 10 * deaf, heard = 10 * deaf - 1_000, ready = 0))
        assertEquals(AirWarmLink.Idle.OK, idle(now = deaf - 1, ready = 0))
    }

    @Test fun `a link that hears nothing subscribes again, counted from its last packet or from becoming ready`() {
        assertEquals(AirWarmLink.Idle.RESUBSCRIBE, idle(now = deaf, ready = 0))
        assertEquals(AirWarmLink.Idle.RESUBSCRIBE, idle(now = 5_000 + deaf, heard = 5_000, ready = 1_000))
        // Reconnected recently: the old silence does not count.
        assertEquals(AirWarmLink.Idle.OK, idle(now = 5_000 + deaf, heard = 5_000, ready = 6_000))
    }

    @Test fun `still deaf after subscribing again, the link is replaced`() {
        val resubscribed = deaf
        assertEquals(AirWarmLink.Idle.OK, idle(now = resubscribed + deaf - 1, resubscribed = resubscribed))
        assertEquals(AirWarmLink.Idle.REBUILD, idle(now = resubscribed + deaf, resubscribed = resubscribed))
    }

    @Test fun `hearing the Air after subscribing again ends the spell`() {
        val resubscribed = deaf
        val heard = resubscribed + 60_000
        assertEquals(AirWarmLink.Idle.OK, idle(now = resubscribed + deaf, heard = heard, resubscribed = resubscribed))
        // A new spell starts with a fresh subscription, not a rebuild.
        assertEquals(AirWarmLink.Idle.RESUBSCRIBE, idle(now = heard + deaf, heard = heard, resubscribed = resubscribed))
    }
}
