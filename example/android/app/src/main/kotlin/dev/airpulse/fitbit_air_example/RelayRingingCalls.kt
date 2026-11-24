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

/** Which ringing calls may buzz. */
internal object RelayCallPolicy {
    const val INCOMING = 1 // Notification.CallStyle.CALL_TYPE_INCOMING

    /**
     * The call type of a call notification, or 0. Apps using CallStyle say so
     * in EXTRA_CALL_TYPE; Google's Phone app (seen on Android 17) posts a
     * ringing call without it. A ringing call opens the full-screen incoming
     * call screen, an ongoing one does not, so that marks it as incoming.
     */
    fun callType(isCall: Boolean, summary: Boolean, declaredType: Int, fullScreen: Boolean): Int = when {
        !isCall || summary -> 0
        declaredType != 0 -> declaredType
        fullScreen -> INCOMING
        else -> 0
    }

    /**
     * Phone calls arrive from the phone app, which people rarely turn on under
     * Apps, so it is accepted whenever incoming calls are on. Calls in other
     * apps (WhatsApp, Signal, ...) follow that app's selection.
     */
    fun accepts(packageName: String, selected: Set<String>, phoneApp: Boolean): Boolean =
        phoneApp || packageName in selected
}
