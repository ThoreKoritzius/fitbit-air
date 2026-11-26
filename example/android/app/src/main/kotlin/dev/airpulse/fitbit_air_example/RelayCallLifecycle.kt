package dev.airpulse.fitbit_air_example

/** Delayed work the call lifecycle schedules; returns a cancel function. Faked in tests. */
internal fun interface RelayTimers {
    fun post(delayMillis: Long, task: () -> Unit): () -> Unit
}

/**
 * When a call's ring starts and ends, from call notifications and, with the
 * Phone permission, Android's call state. Holds every timer involved, so the
 * sequences that decide whether the Air rings can be tested with fake time.
 *
 * - A removed call notification only ends its call if it is not re-posted
 *   within [REMOVAL_GRACE_MS] (Google's Phone app removes and re-posts while
 *   ringing).
 * - While the call state is available, the Phone app's notification waits up
 *   to [CALL_STATE_WAIT_MS] for it and only rings if the call state did not
 *   report the call ([RelayPhoneCallRouter]).
 * - Once the call state reports a call ringing, it owns that call's ring:
 *   notifications coming and going no longer end it.
 */
internal class RelayCallLifecycle(
    private val timers: RelayTimers,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        /** Ring for a call notification (the relay de-duplicates a call that already rings). */
        fun ringNotification(packageName: String, key: String, postedAt: Long)
        /** Ring for the phone call the call state reports. */
        fun ringPhoneCall()
        /** Stop ringing for [key]; the relay then calls [ended]. */
        fun end(key: String, reason: String)
        fun outcome(key: String, outcome: String)
        fun isRinging(key: String): Boolean
    }

    private val router = RelayPhoneCallRouter()
    private val pendingEnds = mutableMapOf<String, () -> Unit>()
    private val fallbacks = mutableMapOf<String, () -> Unit>()
    private val notifications = mutableMapOf<String, Pair<String, Long>>()

    var listening: Boolean
        get() = router.listening
        set(value) {
            router.listening = value
            if (!value) { fallbacks.values.forEach { it() }; fallbacks.clear() }
        }

    /** A ringing call's notification was posted or re-posted. [phoneApp]: it shows phone (SIM) calls. */
    fun incoming(packageName: String, key: String, postedAt: Long, phoneApp: Boolean) {
        // Re-posted after a removal: the same call goes on.
        pendingEnds.remove(key)?.invoke()
        if (!router.listening || !phoneApp || callbacks.isRinging(key)) {
            // A phone call ringing before the call state is available still belongs to that call,
            // so the call state, once allowed, takes it over instead of ringing again.
            if (phoneApp) router.notificationRang(key)
            callbacks.ringNotification(packageName, key, postedAt); return
        }
        notifications[key] = packageName to postedAt
        apply(router.notification(key))
    }

    /** The notification says its call no longer rings (answered or ended). */
    fun notRinging(key: String, reason: String) {
        if (router.ownedByCallState(key)) return
        callbacks.end(key, reason)
    }

    /** Whether a ring for [key] is pending: waiting for the call state, or removed and maybe coming back. */
    fun tracks(key: String): Boolean = key in notifications || key in pendingEnds

    /** A notification went away. Only one that rings or waits to ring can end a call. */
    fun removed(key: String) {
        // Gone before the call state answered for it: no ring yet. A re-post within the
        // grace period (even without its call category) waits afresh.
        val waiting = fallbacks.remove(key)?.let { it(); router.notificationGone(key); true } ?: false
        if (!waiting && !callbacks.isRinging(key)) return
        if (key in pendingEnds) return
        pendingEnds[key] = timers.post(REMOVAL_GRACE_MS) {
            pendingEnds.remove(key)
            notRinging(key, "notification removed")
        }
    }

    fun callState(state: PhoneCallState) = router.callState(state).forEach(::apply)

    /** The ring for [key] ended, whatever ended it. */
    fun ended(key: String) {
        pendingEnds.remove(key)?.invoke()
        forget(key)
    }

    fun clear() {
        listening = false
        pendingEnds.values.forEach { it() }
        pendingEnds.clear()
        notifications.clear()
    }

    private fun forget(key: String) {
        fallbacks.remove(key)?.invoke()
        notifications.remove(key)
        router.notificationGone(key)
    }

    private fun apply(action: RelayPhoneCallRouter.Action?) {
        when (action) {
            null -> Unit
            is RelayPhoneCallRouter.Ring ->
                if (action.key == RelayPhoneCalls.KEY) callbacks.ringPhoneCall()
                else notifications[action.key]?.let { (packageName, postedAt) ->
                    callbacks.ringNotification(packageName, action.key, postedAt)
                }
            is RelayPhoneCallRouter.Wait -> {
                callbacks.outcome(action.key, "waiting_for_call_state")
                fallbacks[action.key] = timers.post(CALL_STATE_WAIT_MS) {
                    fallbacks.remove(action.key)
                    apply(router.fallbackDue(action.key))
                }
            }
            is RelayPhoneCallRouter.Skip -> {
                // The call state speaks for this call: later updates of the notification are not
                // a call of their own, even once the call has ended.
                fallbacks.remove(action.key)?.invoke()
                notifications.remove(action.key)
                callbacks.outcome(action.key, action.reason)
            }
            is RelayPhoneCallRouter.End -> action.keys.forEach { callbacks.end(it, "stopped ringing (call state)") }
        }
    }

    companion object {
        const val REMOVAL_GRACE_MS = 1_500L
        const val CALL_STATE_WAIT_MS = 1_500L
    }
}
