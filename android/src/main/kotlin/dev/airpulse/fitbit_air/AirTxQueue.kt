package dev.airpulse.fitbit_air

/**
 * Tracker-channel packets on their way to the Air, written one at a time.
 *
 * The outstanding write belongs to the connection it was started on:
 * - A replacement connection (a silent warm link falling back to a fresh
 *   one) never waits for a write on the old one, whose completion is ignored
 *   and would never arrive here.
 * - A new session on the same connection (a handshake retry) keeps waiting
 *   for the write Android is still processing, and its completion moves the
 *   queue on: [clear] drops packets only.
 * - A write whose completion never arrives stops blocking after [STALE_MILLIS];
 *   a write without response normally completes within milliseconds.
 */
internal class AirTxQueue(private val clock: () -> Long) {
    private val packets = ArrayDeque<ByteArray>()
    private var writingOn: Any? = null
    private var writingSince = 0L
    private var declinedInARow = 0

    @Synchronized fun add(packet: ByteArray) { packets.addLast(packet) }

    /** The next packet to write on [connection]; null while a write on it is outstanding or nothing waits. */
    @Synchronized fun next(connection: Any): ByteArray? {
        if (writingOn === connection && clock() - writingSince < STALE_MILLIS) return null
        val packet = packets.removeFirstOrNull() ?: return null
        writingOn = connection
        writingSince = clock()
        return packet
    }

    /** The write on [connection] completed. False: it was not the outstanding one. */
    @Synchronized fun written(connection: Any): Boolean {
        if (writingOn !== connection) return false
        writingOn = null
        declinedInARow = 0
        return true
    }

    /**
     * Android declined to start writing [packet] on [connection]: it goes back
     * to the front. Returns whether to try again shortly; false after
     * [MAX_DECLINED_IN_A_ROW], when only the next packet or completion moves the queue on.
     */
    @Synchronized fun declined(connection: Any, packet: ByteArray): Boolean {
        if (writingOn === connection) writingOn = null
        packets.addFirst(packet)
        return ++declinedInARow <= MAX_DECLINED_IN_A_ROW
    }

    /** A new session on the same connection: its packets go, a write Android is processing stays outstanding. */
    @Synchronized fun clear() { packets.clear() }

    /** The connection is gone. */
    @Synchronized fun reset() {
        packets.clear()
        writingOn = null
        declinedInARow = 0
    }

    @get:Synchronized val size: Int get() = packets.size

    companion object {
        const val STALE_MILLIS = 1_000L
        const val MAX_DECLINED_IN_A_ROW = 20
        /** Between attempts at a declined write: 20 × 25 ms bounds the retries to half a second. */
        const val DECLINED_RETRY_MILLIS = 25L
    }
}
