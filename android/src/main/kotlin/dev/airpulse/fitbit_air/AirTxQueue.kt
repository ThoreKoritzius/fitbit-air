package dev.airpulse.fitbit_air

/**
 * Tracker-channel packets on their way to the Air, written one at a time.
 *
 * The outstanding write belongs to the connection it was started on. When a
 * connection is replaced (a silent warm link falls back to a fresh one), a
 * write whose completion never arrived on the old connection must not keep
 * the new one from sending its first packet: the old completion is ignored,
 * so it would never clear.
 */
internal class AirTxQueue {
    private val packets = ArrayDeque<ByteArray>()
    private var writingOn: Any? = null

    @Synchronized fun add(packet: ByteArray) { packets.addLast(packet) }

    /** The next packet to write on [connection]; null while a write on it is outstanding or nothing waits. */
    @Synchronized fun next(connection: Any): ByteArray? {
        if (writingOn === connection) return null
        val packet = packets.removeFirstOrNull() ?: return null
        writingOn = connection
        return packet
    }

    /** The write on [connection] completed. False: it was not the outstanding one. */
    @Synchronized fun written(connection: Any): Boolean {
        if (writingOn !== connection) return false
        writingOn = null
        return true
    }

    /** Android declined to start writing [packet] on [connection]: it goes back to the front. */
    @Synchronized fun declined(connection: Any, packet: ByteArray) {
        if (writingOn === connection) writingOn = null
        packets.addFirst(packet)
    }

    @Synchronized fun clear() {
        packets.clear()
        writingOn = null
    }

    @get:Synchronized val size: Int get() = packets.size
}
