package dev.airpulse.fitbit_air_example

import org.junit.Assert.*
import org.junit.Test

class RelayRingingCallsTest {
    @Test fun sameRingingNotificationOnlyStartsOneAlert() {
        val calls = RelayRingingCalls()
        val ring = checkNotNull(calls.onIncoming("phone"))
        assertNull(calls.onIncoming("phone"))
        assertTrue(calls.isRinging(ring))
    }

    @Test fun endingOrReplacingACallInvalidatesTheQueuedRing() {
        val calls = RelayRingingCalls()
        val first = checkNotNull(calls.onIncoming("phone"))
        calls.onEnded("phone")
        assertFalse(calls.isRinging(first))
        val next = checkNotNull(calls.onIncoming("phone"))
        assertFalse(calls.isRinging(first))
        assertTrue(calls.isRinging(next))
    }

    @Test fun incomingCallsOutrankQueuedMessagesAndDefaultOff() {
        assertNull(RelayCallRule().match())
        val queuedCall = RelayBurstChoice(checkNotNull(RelayCallRule(enabled = true).match()), 1)
        val urgentMessage = RelayBurstChoice(RelayMatch(RelayPreset.HIGH, 2), 2)
        assertSame(queuedCall, urgentMessage.prefer(queuedCall))
        assertEquals(RelayPreset.DOUBLE, queuedCall.match.preset)
    }
}
