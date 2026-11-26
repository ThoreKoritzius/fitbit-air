package dev.airpulse.fitbit_air_example

import org.junit.Assert.*
import org.junit.Test

/** Call sequences with fake time: which signal rings the Air, and what stops it. */
class RelayCallLifecycleTest {
    private val phone = RelayPhoneCalls.KEY

    private class FakeTimers : RelayTimers {
        var now = 0L
        private val tasks = mutableListOf<Triple<Long, () -> Unit, BooleanArray>>()

        override fun post(delayMillis: Long, task: () -> Unit): () -> Unit {
            val cancelled = booleanArrayOf(false)
            tasks += Triple(now + delayMillis, task, cancelled)
            return { cancelled[0] = true }
        }

        fun advanceTo(time: Long) {
            while (true) {
                val next = tasks.filter { !it.third[0] && it.first <= time }.minByOrNull { it.first } ?: break
                tasks.remove(next)
                now = next.first
                next.second()
            }
            now = time
        }
    }

    /** Stands in for the relay: rings until ended, like RelayRingingCalls plus the wrist ring. */
    private class Relay(timers: FakeTimers) : RelayCallLifecycle.Callbacks {
        val ringing = mutableSetOf<String>()
        val rings = mutableListOf<String>()
        val ends = mutableListOf<String>()
        val outcomes = mutableMapOf<String, String>()
        val lifecycle = RelayCallLifecycle(timers, this)

        override fun ringNotification(packageName: String, key: String, postedAt: Long) {
            if (ringing.add(key)) rings += key
        }
        override fun ringPhoneCall() { if (ringing.add(RelayPhoneCalls.KEY)) rings += RelayPhoneCalls.KEY }
        override fun end(key: String, reason: String) {
            if (ringing.remove(key)) ends += key
            lifecycle.ended(key)
        }
        override fun outcome(key: String, outcome: String) { outcomes[key] = outcome }
        override fun isRinging(key: String) = key in ringing
    }

    private fun setup(listening: Boolean = true): Pair<FakeTimers, Relay> {
        val timers = FakeTimers()
        return timers to Relay(timers).apply { lifecycle.listening = listening }
    }

    @Test fun `call state first rings once and answering stops it`() {
        val (timers, relay) = setup()
        relay.lifecycle.callState(PhoneCallState.RINGING)
        relay.lifecycle.incoming("dialer", "n", 0, phoneApp = true)
        timers.advanceTo(5_000)
        assertEquals(listOf(phone), relay.rings)
        assertEquals("via_call_state", relay.outcomes["n"])
        relay.lifecycle.callState(PhoneCallState.IN_CALL)
        assertEquals(listOf(phone), relay.ends)
        assertTrue(relay.ringing.isEmpty())
    }

    @Test fun `a re-post during the wait still rings when the call state misses the call`() {
        // Review: the end timer of the removal used to fire later and cancel the re-post's fallback.
        val (timers, relay) = setup()
        relay.lifecycle.incoming("dialer", "n", 0, phoneApp = true)
        timers.advanceTo(500)
        relay.lifecycle.removed("n", call = true)
        timers.advanceTo(600)
        relay.lifecycle.incoming("dialer", "n", 600, phoneApp = true)
        timers.advanceTo(10_000)
        assertEquals(listOf("n"), relay.rings)
        assertTrue(relay.ends.isEmpty())
        assertTrue("n" in relay.ringing)
    }

    @Test fun `once the call state confirms the call, a removed notification cannot stop the ring`() {
        // Review: the fallback rang under the notification key; a late RINGING must own its lifetime.
        val (timers, relay) = setup()
        relay.lifecycle.incoming("dialer", "n", 0, phoneApp = true)
        timers.advanceTo(1_500)
        assertEquals(listOf("n"), relay.rings)
        relay.lifecycle.callState(PhoneCallState.RINGING)
        assertEquals(listOf("n"), relay.rings) // no second ring
        relay.lifecycle.removed("n", call = true)
        relay.lifecycle.notRinging("n", "no longer ringing")
        timers.advanceTo(10_000)
        assertTrue(relay.ends.isEmpty())
        relay.lifecycle.callState(PhoneCallState.IN_CALL)
        assertEquals(listOf("n"), relay.ends)
        assertTrue(relay.ringing.isEmpty())
    }

    @Test fun `answering stops a notification ring the call state never confirmed`() {
        val (timers, relay) = setup()
        relay.lifecycle.incoming("dialer", "n", 0, phoneApp = true)
        timers.advanceTo(2_000)
        relay.lifecycle.callState(PhoneCallState.IN_CALL)
        assertEquals(listOf("n"), relay.ends)
    }

    @Test fun `a notification removed for good ends its ring after the grace period`() {
        val (timers, relay) = setup()
        relay.lifecycle.incoming("dialer", "n", 0, phoneApp = true)
        timers.advanceTo(1_500)
        relay.lifecycle.removed("n", call = true)
        timers.advanceTo(2_900)
        assertTrue(relay.ends.isEmpty())
        timers.advanceTo(3_000)
        assertEquals(listOf("n"), relay.ends)
    }

    @Test fun `a notification removed during the wait never rings`() {
        val (timers, relay) = setup()
        relay.lifecycle.incoming("dialer", "n", 0, phoneApp = true)
        timers.advanceTo(500)
        relay.lifecycle.removed("n", call = true)
        timers.advanceTo(10_000)
        assertTrue(relay.rings.isEmpty())
    }

    @Test fun `a call waiting during a call never rings`() {
        val (timers, relay) = setup()
        relay.lifecycle.callState(PhoneCallState.IN_CALL)
        relay.lifecycle.incoming("dialer", "w", 0, phoneApp = true)
        timers.advanceTo(10_000)
        assertTrue(relay.rings.isEmpty())
        assertEquals("call_waiting", relay.outcomes["w"])
    }

    @Test fun `without the call state notifications ring at once and re-posts keep ringing`() {
        val (timers, relay) = setup(listening = false)
        relay.lifecycle.incoming("dialer", "n", 0, phoneApp = true)
        assertEquals(listOf("n"), relay.rings)
        relay.lifecycle.removed("n", call = true)
        timers.advanceTo(1_000)
        relay.lifecycle.incoming("dialer", "n", 1_000, phoneApp = true)
        timers.advanceTo(10_000)
        assertTrue(relay.ends.isEmpty())
        relay.lifecycle.notRinging("n", "no longer ringing")
        assertEquals(listOf("n"), relay.ends)
    }

    @Test fun `app calls never wait for the call state`() {
        val (_, relay) = setup()
        relay.lifecycle.incoming("com.whatsapp", "wa", 0, phoneApp = false)
        assertEquals(listOf("wa"), relay.rings)
    }
}
