package dev.airpulse.fitbit_air

/** One transport owner across Flutter engines in this app process.
 * Does not coordinate with Google Health or other Android processes.
 */
internal object AirTransportLease {
    private var owner: Any? = null

    @Synchronized
    fun tryAcquire(candidate: Any): Boolean {
        if (owner != null) return false
        owner = candidate
        return true
    }

    @Synchronized
    fun isHeld(): Boolean = owner != null

    @Synchronized
    fun release(candidate: Any) {
        if (owner === candidate) owner = null
    }
}
