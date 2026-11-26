package dev.airpulse.fitbit_air

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AirTxQueueTest {
    private val warm = Any()
    private val fresh = Any()
    private fun packet(n: Int) = byteArrayOf(n.toByte())

    @Test fun `one write at a time on a connection`() {
        val queue = AirTxQueue()
        queue.add(packet(1)); queue.add(packet(2))
        assertArrayEquals(packet(1), queue.next(warm))
        assertNull(queue.next(warm))
        assertTrue(queue.written(warm))
        assertArrayEquals(packet(2), queue.next(warm))
    }

    @Test fun `a replacement connection sends its first packet although the old write never completed`() {
        // Review: the flag of a write whose callback never came stayed set after the silent-link
        // fallback, so the fresh connection's first handshake packet never left the queue.
        val queue = AirTxQueue()
        queue.add(packet(1))
        assertArrayEquals(packet(1), queue.next(warm)) // completion never arrives
        queue.clear() // the session is reset for the fresh connection
        queue.add(packet(9))
        assertArrayEquals(packet(9), queue.next(fresh))
        // A late completion from the old connection does not touch the new write.
        assertFalse(queue.written(warm))
        assertNull(queue.next(fresh))
        assertTrue(queue.written(fresh))
    }

    @Test fun `even without a reset the old connection's write does not block the new one`() {
        val queue = AirTxQueue()
        queue.add(packet(1)); queue.add(packet(2))
        queue.next(warm)
        assertArrayEquals(packet(2), queue.next(fresh))
    }

    @Test fun `a declined write goes back to the front and frees the connection`() {
        val queue = AirTxQueue()
        queue.add(packet(1)); queue.add(packet(2))
        val first = queue.next(fresh)!!
        queue.declined(fresh, first)
        assertEquals(2, queue.size)
        assertArrayEquals(packet(1), queue.next(fresh))
    }
}
