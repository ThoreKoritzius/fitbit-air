package dev.airpulse.fitbit_air

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AirTxQueueTest {
    private val warm = Any()
    private val fresh = Any()
    private val queue = AirTxQueue()
    private fun packet(n: Int) = byteArrayOf(n.toByte())

    @Test fun `one write at a time on a connection`() {
        queue.add(packet(1)); queue.add(packet(2))
        assertArrayEquals(packet(1), queue.next(warm))
        assertNull(queue.next(warm))
        assertTrue(queue.written(warm))
        assertArrayEquals(packet(2), queue.next(warm))
    }

    @Test fun `a replacement connection sends its first packet although the old write never completed`() {
        // Review: the flag of a write whose callback never came stayed set after the silent-link
        // fallback, so the fresh connection's first handshake packet never left the queue.
        queue.add(packet(1))
        assertArrayEquals(packet(1), queue.next(warm)) // completion never arrives
        queue.clear()
        queue.add(packet(9))
        assertArrayEquals(packet(9), queue.next(fresh))
        // A late completion from the old connection does not touch the new write.
        assertFalse(queue.written(warm))
        assertNull(queue.next(fresh))
        assertTrue(queue.written(fresh))
    }

    @Test fun `a handshake retry on the same connection resumes when the earlier write completes`() {
        // Review: clearing the outstanding write on a session restart let the next packet be
        // written while Android was still busy; it was declined, and the late completion was then
        // ignored, so the packet stayed queued.
        queue.add(packet(1))
        queue.next(warm)
        queue.clear() // new session, same connection
        queue.add(packet(2))
        assertNull(queue.next(warm)) // still waiting for Android
        assertTrue(queue.written(warm)) // the late completion counts
        assertArrayEquals(packet(2), queue.next(warm))
    }

    @Test fun `a write without its completion keeps blocking, so a late completion is never another write's`() {
        // Review: a one-second cutoff let packet 2 start while packet 1 lacked its completion; packet
        // 1's late completion then cleared packet 2's and let packet 3 through.
        queue.add(packet(1)); queue.add(packet(2)); queue.add(packet(3))
        queue.next(warm)
        assertNull(queue.next(warm))
        assertTrue(queue.written(warm)) // packet 1, however late
        assertArrayEquals(packet(2), queue.next(warm))
        assertNull(queue.next(warm))
    }

    @Test fun `a declined write goes back to the front and is retried a bounded number of times`() {
        queue.add(packet(1)); queue.add(packet(2))
        repeat(AirTxQueue.MAX_DECLINED_IN_A_ROW) {
            val first = queue.next(fresh)!!
            assertArrayEquals(packet(1), first)
            assertTrue(queue.declined(fresh, first))
        }
        assertFalse(queue.declined(fresh, queue.next(fresh)!!))
        assertEquals(2, queue.size)
        // A completed write starts the count again.
        queue.next(fresh); queue.written(fresh)
        assertTrue(queue.declined(fresh, queue.next(fresh)!!))
    }

    @Test fun `reset forgets the outstanding write`() {
        queue.add(packet(1)); queue.add(packet(2))
        queue.next(warm)
        queue.reset()
        queue.add(packet(3))
        assertArrayEquals(packet(3), queue.next(warm))
    }
}
