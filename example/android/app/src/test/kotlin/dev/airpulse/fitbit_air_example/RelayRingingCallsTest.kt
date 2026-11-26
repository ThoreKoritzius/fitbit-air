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


    @Test fun `a ringing call re-posted without its call category keeps ringing until answered`() {
        // Pixel Phone app, phone on silent: CallStyle stays, the category goes.
        assertTrue(RelayCallPolicy.stillRinging(callStyle = true, declaredType = 0))
        assertTrue(RelayCallPolicy.stillRinging(callStyle = true, declaredType = 1))
        assertFalse(RelayCallPolicy.stillRinging(callStyle = true, declaredType = 2))
        assertFalse(RelayCallPolicy.stillRinging(callStyle = true, declaredType = 3))
        assertFalse(RelayCallPolicy.stillRinging(callStyle = false, declaredType = 0))
        val calls = RelayRingingCalls()
        calls.onIncoming("k")
        assertTrue(calls.isRinging("k"))
        calls.onEnded("k")
        assertFalse(calls.isRinging("k"))
    }

    @Test fun `posts route to ringing, ending or nothing`() {
        val route = RelayCallPolicy::route
        assertEquals(RelayCallPolicy.Route.INCOMING, route(1, true, false, true, 1))
        // Category dropped while the relay still tracks the key: the call goes on.
        assertEquals(RelayCallPolicy.Route.INCOMING, route(0, false, true, true, 1))
        assertEquals(RelayCallPolicy.Route.NONE, route(0, false, false, true, 1))
        assertEquals(RelayCallPolicy.Route.NOT_RINGING, route(2, true, true, true, 2))
        assertEquals(RelayCallPolicy.Route.NOT_RINGING, route(0, false, true, false, 0))
        assertEquals(RelayCallPolicy.Route.NONE, route(0, false, false, false, 0))
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

class RelayCallSignalsTest {
    @org.junit.Before fun reset() = RelayCallSignals.clear()

    @org.junit.Test fun keepsTheLatestFewNewestFirstWithTheirOutcome() {
        repeat(RelayCallSignals.MAX + 2) { RelayCallSignals.seen("k$it", mapOf("package" to "p$it", "recognized" to (it % 2 == 0))) }
        RelayCallSignals.outcome("k7", "app_not_selected")
        RelayCallSignals.outcome("k0", "ignored") // Already dropped: no effect.
        val snapshot = RelayCallSignals.snapshot()
        org.junit.Assert.assertEquals(RelayCallSignals.MAX, snapshot.size)
        org.junit.Assert.assertEquals("p7", snapshot.first()["package"])
        org.junit.Assert.assertEquals("app_not_selected", snapshot.first()["outcome"])
        org.junit.Assert.assertEquals("recognized", snapshot[1]["outcome"])
        org.junit.Assert.assertEquals("not_a_ringing_call", snapshot[2]["outcome"])
    }

    @org.junit.Test fun aReRingUpdatesTheNewestEntryForThatKey() {
        RelayCallSignals.seen("call", mapOf("recognized" to true))
        RelayCallSignals.seen("call", mapOf("recognized" to true))
        RelayCallSignals.outcome("call", "air_verified")
        val snapshot = RelayCallSignals.snapshot()
        org.junit.Assert.assertEquals("air_verified", snapshot[0]["outcome"])
        org.junit.Assert.assertEquals("recognized", snapshot[1]["outcome"])
    }
}

class RelayPhoneCallTrackerTest {
    private val idle = android.telephony.TelephonyManager.CALL_STATE_IDLE
    private val ringing = android.telephony.TelephonyManager.CALL_STATE_RINGING
    private val offhook = android.telephony.TelephonyManager.CALL_STATE_OFFHOOK

    @Test fun `a call rings until answered or over`() {
        val tracker = RelayPhoneCallTracker()
        assertEquals(PhoneCallState.IDLE, tracker.update(1, idle))
        assertEquals(PhoneCallState.RINGING, tracker.update(1, ringing))
        assertEquals(PhoneCallState.IN_CALL, tracker.update(1, offhook))
        assertEquals(PhoneCallState.IDLE, tracker.update(1, idle))
    }

    @Test fun `a call waiting on the same SIM during a call does not ring`() {
        // Android reports RINGING for a waiting call, replacing OFFHOOK.
        val tracker = RelayPhoneCallTracker()
        tracker.update(1, offhook)
        assertEquals(PhoneCallState.IN_CALL, tracker.update(1, ringing))
        assertEquals(PhoneCallState.IDLE, tracker.update(1, idle))
    }

    @Test fun `a call on the other SIM during a call does not ring`() {
        val tracker = RelayPhoneCallTracker()
        tracker.update(1, offhook)
        assertEquals(PhoneCallState.IN_CALL, tracker.update(2, ringing))
        assertEquals(PhoneCallState.RINGING, tracker.update(1, idle))
    }
}

class RelayPhoneCallRouterTest {
    private val phone = RelayPhoneCalls.KEY
    private fun router() = RelayPhoneCallRouter().apply { listening = true }

    @Test fun `without the call state a phone app notification rings at once`() {
        val router = RelayPhoneCallRouter()
        assertEquals(RelayPhoneCallRouter.Ring("n"), router.notification("n"))
    }

    @Test fun `call state first - one ring, ended by answering`() {
        val router = router()
        assertEquals(listOf(RelayPhoneCallRouter.Ring(phone)), router.callState(PhoneCallState.RINGING))
        assertEquals(RelayPhoneCallRouter.Skip("n", "via_call_state"), router.notification("n"))
        assertEquals(listOf(RelayPhoneCallRouter.End(setOf(phone))), router.callState(PhoneCallState.IN_CALL))
    }

    @Test fun `notification first - the call state takes over within the wait`() {
        val router = router()
        assertEquals(RelayPhoneCallRouter.Wait("n"), router.notification("n"))
        assertNull(router.notification("n")) // re-posted while waiting
        assertEquals(
            listOf(RelayPhoneCallRouter.Skip("n", "via_call_state"), RelayPhoneCallRouter.Ring(phone)),
            router.callState(PhoneCallState.RINGING),
        )
        assertEquals(listOf(RelayPhoneCallRouter.End(setOf(phone))), router.callState(PhoneCallState.IDLE))
    }

    @Test fun `notification rings after the wait and answering still stops it`() {
        // Regression: the late call state must not start a second ring, and must end the notification's ring.
        val router = router()
        router.notification("n")
        assertEquals(RelayPhoneCallRouter.Ring("n"), router.fallbackDue("n"))
        assertEquals(emptyList<RelayPhoneCallRouter.Action>(), router.callState(PhoneCallState.RINGING))
        assertEquals(listOf(RelayPhoneCallRouter.End(setOf(phone, "n"))), router.callState(PhoneCallState.IN_CALL))
    }

    @Test fun `answering ends a notification ring even if the call state never reported ringing`() {
        val router = router()
        router.notification("n")
        router.fallbackDue("n")
        assertEquals(listOf(RelayPhoneCallRouter.End(setOf(phone, "n"))), router.callState(PhoneCallState.IN_CALL))
    }

    @Test fun `a waiting call's notification never rings during a call`() {
        val router = router()
        router.callState(PhoneCallState.IN_CALL)
        assertEquals(RelayPhoneCallRouter.Skip("n", "call_waiting"), router.notification("n"))
        // Posted just before the call was answered: answering drops it without a ring.
        val other = router()
        other.notification("m")
        assertEquals(
            listOf(RelayPhoneCallRouter.Skip("m", "call_waiting"), RelayPhoneCallRouter.End(setOf(phone))),
            other.callState(PhoneCallState.IN_CALL),
        )
    }

    @Test fun `a removed notification is forgotten`() {
        val router = router()
        router.notification("n")
        router.fallbackDue("n")
        router.notificationGone("n")
        assertEquals(listOf(RelayPhoneCallRouter.End(setOf(phone))), router.callState(PhoneCallState.IN_CALL))
    }
}

class RelayPhoneCallTrackerSimChangeTest {
    @Test fun `turning on another SIM keeps a call in progress on the first`() {
        // Review: re-registration used to forget OFFHOOK, so a waiting call then rang.
        val tracker = RelayPhoneCallTracker()
        tracker.update(1, android.telephony.TelephonyManager.CALL_STATE_OFFHOOK)
        tracker.retain(setOf(1, 2))
        assertEquals(PhoneCallState.IN_CALL, tracker.update(1, android.telephony.TelephonyManager.CALL_STATE_RINGING))
        tracker.retain(setOf(2))
        assertEquals(PhoneCallState.IDLE, tracker.current())
    }
}
