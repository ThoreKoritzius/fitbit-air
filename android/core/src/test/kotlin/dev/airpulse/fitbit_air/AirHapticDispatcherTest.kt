package dev.airpulse.fitbit_air

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The Dart dispatcher's scenarios (test/fitbit_air_commands_test.dart), with fake time. */
class AirHapticDispatcherTest {
    private class Fake : AirTimers {
        var now = 1_000_000L
        private val tasks = mutableListOf<Triple<Long, () -> Unit, BooleanArray>>()
        val pendingTimers get() = tasks.count { !it.third[0] }

        override fun post(delayMillis: Long, task: () -> Unit): () -> Unit {
            val cancelled = booleanArrayOf(false)
            tasks += Triple(now + delayMillis, task, cancelled)
            return { cancelled[0] = true }
        }

        fun elapse(millis: Long) {
            val until = now + millis
            while (true) {
                val next = tasks.filter { !it.third[0] && it.first <= until }.minByOrNull { it.first } ?: break
                tasks.remove(next)
                now = next.first
                next.second()
            }
            tasks.removeAll { it.third[0] }
            now = until
        }
    }

    private val time = Fake()
    private val calls = mutableListOf<AirHapticCommand>()
    /** What the next run reports; null holds the reply until [release]. */
    private var reply: (Int) -> Result<Unit>? = { Result.success(Unit) }
    private var held: ((Result<Unit>) -> Unit)? = null
    private val runner = AirHapticRunner { command, done ->
        calls += command
        val result = reply(calls.size)
        if (result == null) held = done else done(result)
    }

    private fun release(result: Result<Unit>) = held!!.also { held = null }(result)
    private fun failure(code: String) = Result.failure<Unit>(FitbitAirException(code, code))

    private fun dispatcher(
        minimumIntervalMillis: Long = 2_000,
        deduplicationWindowMillis: Long = 300_000,
        maxPending: Int = 4,
        maxRemembered: Int = 256,
        retryUnsent: Boolean = true,
    ) = AirHapticDispatcher(runner, time, { time.now }, minimumIntervalMillis, deduplicationWindowMillis,
        maxPending, maxRemembered, retryUnsent, 3_000)

    private fun command(id: String, lifetimeSeconds: Long = 60) = AirHapticCommand(id, time.now + lifetimeSeconds * 1000)

    private fun AirHapticDispatcher.send(command: AirHapticCommand): MutableList<AirHapticDispatchResult> {
        val results = mutableListOf<AirHapticDispatchResult>()
        submit(command) { results += it }
        assertTrue(results.isEmpty(), "answered inside submit")
        time.elapse(0)
        return results
    }

    @Test fun `an explicit strength is only for a tap`() {
        assertThrows<IllegalArgumentException> { AirHapticCommand("bad", time.now + 60_000, intensity = HapticIntensity.LOW) }
        val result = dispatcher().send(AirHapticCommand("low", time.now + 60_000, AirHapticKind.TAP, HapticIntensity.LOW))
        assertEquals(AirHapticDispatchStatus.COMPLETED, result.single().status)
        assertEquals(HapticIntensity.LOW, calls.single().intensity)
    }

    @Test fun `expired messages never invoke the device`() {
        val result = dispatcher().send(command("old", lifetimeSeconds = 0))
        assertEquals(AirHapticDispatchStatus.EXPIRED, result.single().status)
        assertTrue(calls.isEmpty())
    }

    @Test fun `expiry is checked again after waiting for the rate limit`() {
        val d = dispatcher()
        d.send(command("first"))
        val queued = d.send(command("expires-queued", lifetimeSeconds = 1))
        time.elapse(2_000)
        assertEquals(AirHapticDispatchStatus.EXPIRED, queued.single().status)
        assertEquals(1, calls.size)
    }

    @Test fun `the rate limit separates device operations`() {
        val d = dispatcher()
        d.send(command("first"))
        d.send(command("second"))
        assertEquals(1, calls.size)
        time.elapse(1_999)
        assertEquals(1, calls.size)
        time.elapse(1)
        assertEquals(2, calls.size)
    }

    @Test fun `outstanding work is bounded and close leaves an active write alone`() {
        reply = { null }
        val d = dispatcher(maxPending = 2)
        val active = d.send(command("active"))
        val queued = d.send(command("queued"))
        val overflow = d.send(command("overflow"))
        assertEquals(2, d.pendingCount)
        assertEquals(AirHapticDispatchStatus.QUEUE_FULL, overflow.single().status)
        d.close()
        assertTrue(queued.isEmpty(), "answered inside close")
        time.elapse(0)
        assertEquals(AirHapticDispatchStatus.CLOSED, queued.single().status)
        assertEquals(1, d.pendingCount)
        release(Result.success(Unit))
        time.elapse(0)
        assertEquals(AirHapticDispatchStatus.COMPLETED, active.single().status)
        assertEquals(listOf("active"), calls.map { it.id })
        assertEquals(0, d.pendingCount)
    }

    @Test fun `ambiguous failures are neither retried nor replayed`() {
        reply = { failure("TIMEOUT") }
        val d = dispatcher()
        val item = command("unknown-outcome")
        val first = d.send(item)
        assertEquals(AirHapticDispatchStatus.FAILED, first.single().status)
        assertEquals("TIMEOUT", first.single().error?.code)
        time.elapse(10_000)
        assertEquals(AirHapticDispatchStatus.DUPLICATE, d.send(item).single().status)
        assertEquals(1, calls.size)
    }

    @Test fun `deduplication is time bounded and close rejects new work`() {
        val d = dispatcher(deduplicationWindowMillis = 3_000)
        d.send(command("reusable"))
        time.elapse(4_000)
        d.send(command("reusable"))
        assertEquals(2, calls.size)
        d.close()
        assertEquals(AirHapticDispatchStatus.CLOSED, d.send(command("new")).single().status)
        assertEquals(2, calls.size)
    }

    @Test fun `ids and limits are validated`() {
        assertThrows<IllegalArgumentException> { command(" ") }
        assertThrows<IllegalArgumentException> { command("a".repeat(129)) }
        assertThrows<IllegalArgumentException> { dispatcher(maxPending = 0) }
        assertThrows<IllegalArgumentException> { dispatcher(maxPending = 5, maxRemembered = 4) }
        assertThrows<IllegalArgumentException> { dispatcher(deduplicationWindowMillis = 0) }
    }

    @Test fun `closing during the rate-limit wait cancels it without writing`() {
        val d = dispatcher(minimumIntervalMillis = 3_600_000)
        d.send(command("first"))
        val waiting = d.send(command("waiting", lifetimeSeconds = 7_200))
        d.close()
        time.elapse(0)
        assertEquals(AirHapticDispatchStatus.CLOSED, waiting.single().status)
        assertEquals(0, d.pendingCount)
        assertEquals(0, time.pendingTimers)
        assertEquals(1, calls.size)
    }

    @Test fun `a failure that reached nothing is retried once after a pause`() {
        for (code in AirHapticDispatcher.unsentErrorCodes) {
            calls.clear()
            reply = { attempt -> if (attempt == 1) failure(code) else Result.success(Unit) }
            val result = dispatcher().send(command("raced-$code"))
            assertTrue(result.isEmpty())
            time.elapse(3_000)
            assertEquals(AirHapticDispatchResult(AirHapticDispatchStatus.COMPLETED, attempts = 2), result.single())
            assertEquals(2, calls.size)
        }
    }

    @Test fun `an unconfirmed change is never retried`() {
        reply = { failure("HAPTIC_UNCONFIRMED") }
        val result = dispatcher().send(command("maybe-buzzed"))
        time.elapse(10_000)
        assertEquals("HAPTIC_UNCONFIRMED", result.single().error?.code)
        assertEquals(1, result.single().attempts)
        assertEquals(1, calls.size)
    }

    @Test fun `a second unsent failure is reported, not retried again`() {
        reply = { failure("HANDSHAKE_TIMEOUT") }
        val result = dispatcher().send(command("raced-twice"))
        time.elapse(10_000)
        assertEquals(AirHapticDispatchStatus.FAILED, result.single().status)
        assertEquals(2, result.single().attempts)
        assertEquals(2, calls.size)
    }

    @Test fun `no retry once expired, when disabled, or after close`() {
        reply = { failure("HANDSHAKE_TIMEOUT") }
        val expired = dispatcher().send(command("short", lifetimeSeconds = 2))
        time.elapse(3_000)
        assertEquals(AirHapticDispatchStatus.EXPIRED, expired.single().status)
        assertEquals(1, calls.size)

        calls.clear()
        val off = dispatcher(retryUnsent = false).send(command("off"))
        time.elapse(5_000)
        assertEquals(AirHapticDispatchStatus.FAILED, off.single().status)
        assertEquals(1, calls.size)

        calls.clear()
        val closing = dispatcher()
        val closed = closing.send(command("closing"))
        closing.close()
        time.elapse(5_000)
        assertEquals("HANDSHAKE_TIMEOUT", closed.single().error?.code)
        assertEquals(1, calls.size)
    }

    @Test fun `event keys make the same ids as the Dart package`() {
        fun id(source: String, key: String) = AirHapticCommand.fromEventKey(source, key, time.now + 60_000).id
        // Printed by the Dart package's AirHapticCommand.fromEventKey for the same pairs.
        assertEquals("a7db82a6414176309f95123e530f1bc04fcf4c7cefbd702c4a5c97c5d38627af",
            id("com.whatsapp", "0|com.whatsapp|1|null|10123"))
        assertEquals("ce4b30cfcb2c9fa2eff7e692eaede6501263d71346fb82f674c877d4b83521b2",
            id("src", "quote \" back \\ tab \t nl \n ctl \u0001 slash /"))
        assertEquals("918130859cb7be2d03e8eb8b3e67bebc9f9a3a021760e45b30c35cb3bffe6332",
            id("ünï", "emoji 😀 lone \uD800 end"))
        assertThrows<IllegalArgumentException> { id(" ", "key") }
        assertThrows<IllegalArgumentException> { id("src", "") }
    }
}
