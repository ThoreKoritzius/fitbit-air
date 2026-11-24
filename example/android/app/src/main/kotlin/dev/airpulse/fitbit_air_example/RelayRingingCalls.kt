package dev.airpulse.fitbit_air_example

/** One alert per ringing episode, even when Android updates the same notification. */
internal class RelayRingingCalls {
    class Ring(val key: String)

    private val active = mutableMapOf<String, Ring>()

    fun onIncoming(key: String): Ring? {
        if (key in active) return null
        return Ring(key).also { active[key] = it }
    }

    fun onEnded(key: String) { active.remove(key) }

    // A later call may reuse the notification key. It cannot revive a queued alert.
    fun isRinging(ring: Ring): Boolean = active[ring.key] === ring
}
