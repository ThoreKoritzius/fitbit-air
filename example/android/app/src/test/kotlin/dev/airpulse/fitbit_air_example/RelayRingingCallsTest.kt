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
        // By default a call rings at the Air's own strength.
        assertEquals(RelayPreset.CURRENT, queuedCall.match.preset)
    }

    @Test fun `phone calls buzz without selecting the phone app and app calls follow the selection`() {
        val selected = setOf("com.whatsapp")
        assertTrue(RelayCallPolicy.accepts("com.google.android.dialer", selected, phoneApp = true))
        assertTrue(RelayCallPolicy.accepts("com.whatsapp", selected, phoneApp = false))
        assertFalse(RelayCallPolicy.accepts("org.thoughtcrime.securesms", selected, phoneApp = false))
    }


    @Test fun `a ringing call is recognised with or without CallStyle's declared type`() {
        // CallStyle apps declare the type.
        assertEquals(1, RelayCallPolicy.callType(isCall = true, summary = false, declaredType = 1, fullScreen = false))
        assertEquals(2, RelayCallPolicy.callType(isCall = true, summary = false, declaredType = 2, fullScreen = true))
        // Google's Phone app rings without a type, but with the full-screen call screen.
        assertEquals(1, RelayCallPolicy.callType(isCall = true, summary = false, declaredType = 0, fullScreen = true))
        // Once answered it stays a call notification without the full-screen screen.
        assertEquals(0, RelayCallPolicy.callType(isCall = true, summary = false, declaredType = 0, fullScreen = false))
        assertEquals(0, RelayCallPolicy.callType(isCall = false, summary = false, declaredType = 0, fullScreen = true))
        assertEquals(0, RelayCallPolicy.callType(isCall = true, summary = true, declaredType = 1, fullScreen = true))
    }
}
