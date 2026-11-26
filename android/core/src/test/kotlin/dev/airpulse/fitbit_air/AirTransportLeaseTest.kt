package dev.airpulse.fitbit_air

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

class AirTransportLeaseTest {
    @Test fun `another engine cannot acquire or release an owned lease`() {
        val first = Any()
        val second = Any()
        try {
            assertTrue(AirTransportLease.tryAcquire(first))
            assertFalse(AirTransportLease.tryAcquire(second))
            AirTransportLease.release(second)
            assertFalse(AirTransportLease.tryAcquire(second))
            AirTransportLease.release(first)
            assertTrue(AirTransportLease.tryAcquire(second))
            AirTransportLease.release(first) // stale cleanup must not release second
            assertFalse(AirTransportLease.tryAcquire(first))
        } finally {
            AirTransportLease.release(first)
            AirTransportLease.release(second)
        }
    }

    @Test fun `concurrent acquisition has exactly one winner`() {
        val start = CountDownLatch(1)
        val candidates = List(16) { Any() }
        val winners = Collections.synchronizedList(mutableListOf<Any>())
        val workers = candidates.map { candidate -> thread {
            start.await()
            if (AirTransportLease.tryAcquire(candidate)) winners.add(candidate)
        } }
        start.countDown()
        workers.forEach { it.join() }
        try {
            assertEquals(1, winners.size)
        } finally {
            candidates.forEach { AirTransportLease.release(it) }
        }
    }
}
