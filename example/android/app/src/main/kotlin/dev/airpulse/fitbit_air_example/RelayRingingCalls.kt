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

    /** Whether the call behind this notification key is still ringing. */
    fun isRinging(key: String): Boolean = key in active

    // A later call may reuse the notification key. It cannot revive a queued alert.
    fun isRinging(ring: Ring): Boolean = active[ring.key] === ring
}

/**
 * The shape of the last few call-like notifications and what the relay did
 * with them, for the diagnostic report. Never the caller: only the posting
 * app and notification metadata. In memory only.
 */
internal object RelayCallSignals {
    const val MAX = 6
    private val entries = ArrayDeque<MutableMap<String, Any?>>()
    private val keys = ArrayDeque<String>()

    @Synchronized
    fun seen(key: String, signal: Map<String, Any?>) {
        if (entries.size == MAX) { entries.removeFirst(); keys.removeFirst() }
        entries.addLast(signal.toMutableMap().apply { put("outcome", if (signal["recognized"] == true) "recognized" else "not_a_ringing_call") })
        keys.addLast(key)
    }

    /** What happened to the latest signal for [key]: "ringing", "app_not_selected", "relay_off", … */
    @Synchronized
    fun outcome(key: String, outcome: String) {
        val index = keys.lastIndexOf(key)
        if (index >= 0) entries[index]["outcome"] = outcome
    }

    @Synchronized
    fun snapshot(): List<Map<String, Any?>> = entries.map { it.toMap() }.asReversed()

    @Synchronized
    fun clear() { entries.clear(); keys.clear() }
}

/** Which ringing calls may buzz. */
internal object RelayCallPolicy {
    // Notification.CallStyle.CALL_TYPE_*, spelled out so older Android never needs CallStyle.
    const val INCOMING = 1
    const val ONGOING = 2
    const val SCREENING = 3

    /**
     * Notification.EXTRA_CALL_TYPE. Public from Android 14, but CallStyle
     * (Android 12+) already writes it, and the key reads as absent before that.
     */
    const val EXTRA_CALL_TYPE = "android.callType"

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
     * Whether a re-post of a call that is ringing still rings. Google's Phone
     * app (seen on a Pixel with Android 17, phone on silent) moves a ringing
     * call to a CallStyle notification without the call category about a
     * second after it appears, while the call keeps ringing. Only a post that
     * declares the call ongoing (answered) or screened ends the ring there;
     * anything that is not CallStyle at all means the call is over.
     */
    fun stillRinging(callStyle: Boolean, declaredType: Int): Boolean =
        callStyle && declaredType != ONGOING && declaredType != SCREENING

    enum class Route { INCOMING, NOT_RINGING, NONE }

    /**
     * What a posted notification means for calls. [tracked]: the relay is
     * ringing for its key, or still deciding (waiting for the call state or
     * for a removed notification to come back).
     */
    fun route(callType: Int, isCall: Boolean, tracked: Boolean, callStyle: Boolean, declaredType: Int): Route = when {
        callType == INCOMING -> Route.INCOMING
        // The Phone app may re-post a ringing call without its call category: the call goes on.
        tracked && stillRinging(callStyle, declaredType) -> Route.INCOMING
        // A call notification that no longer rings: answered or ended. Other notifications never end a call.
        isCall || tracked -> Route.NOT_RINGING
        else -> Route.NONE
    }

    /**
     * Phone calls arrive from the phone app, which people rarely turn on under
     * Apps, so it is accepted whenever incoming calls are on. Calls in other
     * apps (WhatsApp, Signal, ...) follow that app's selection.
     */
    fun accepts(packageName: String, selected: Set<String>, phoneApp: Boolean): Boolean =
        phoneApp || packageName in selected
}
