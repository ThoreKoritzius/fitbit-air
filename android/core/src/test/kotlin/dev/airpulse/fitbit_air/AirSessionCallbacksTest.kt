package dev.airpulse.fitbit_air

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AirSessionCallbacksTest {
    @Test fun `queued work is discarded after cleanup even when a replacement is active`() {
        val old = Any()
        var current: Any? = old
        val queue = ArrayDeque<() -> Unit>()
        val callbacks = AirSessionCallbacks({ current }) { queue.addLast(it) }
        val events = mutableListOf<String>()
        callbacks.dispatch(old) { events.add("stale write") }
        callbacks.dispatch(old) { events.add("stale completion") }
        current = null // cleanup invalidates ownership before disposing native resources
        callbacks.dispatch(old) { events.add("callback during teardown") }
        val replacement = Any()
        current = replacement
        callbacks.dispatch(replacement) { events.add("current write") }
        while (queue.isNotEmpty()) queue.removeFirst().invoke()
        assertEquals(listOf("current write"), events)
    }

    @Test fun `early connection callback waits until connection has been assigned`() {
        var current: Any? = null
        val queue = ArrayDeque<() -> Unit>()
        val callbacks = AirSessionCallbacks({ current }) { queue.addLast(it) }
        val connection = Any()
        var discovered = false
        callbacks.dispatch(connection) { discovered = true }
        assertFalse(discovered)
        current = connection
        queue.removeFirst().invoke()
        assertTrue(discovered)
    }

    @Test fun `closed session work is discarded without a replacement`() {
        val session = Any()
        var current: Any? = session
        val queue = ArrayDeque<() -> Unit>()
        val callbacks = AirSessionCallbacks({ current }) { queue.addLast(it) }
        callbacks.dispatch(session) { fail<Unit>("Closed native pointer must not be used") }
        current = null
        queue.removeFirst().invoke()
    }
}
