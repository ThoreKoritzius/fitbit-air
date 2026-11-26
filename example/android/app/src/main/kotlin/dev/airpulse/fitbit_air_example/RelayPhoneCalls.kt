package dev.airpulse.fitbit_air_example

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager

/** The phone's calls as the Air sees them. */
internal enum class PhoneCallState { IDLE, RINGING, IN_CALL }

/**
 * Phone (SIM) calls straight from Android's call state, once the user allows
 * the Phone permission. Unlike the call notification, the call state does not
 * depend on how a phone maker's call app draws its call screen. App calls
 * (WhatsApp, Signal, …) are not reported here and keep coming from
 * notifications. Only the state is read, never the number.
 */
internal class RelayPhoneCalls(private val context: Context, private val onChange: (PhoneCallState) -> Unit) {
    /** One registration per SIM, so a call on either SIM of a dual-SIM phone is seen. */
    private val registrations = mutableListOf<() -> Unit>()
    private var subscriptions = emptySet<Int>()
    private var subscriptionWatch: (() -> Unit)? = null
    private val tracker = RelayPhoneCallTracker()
    private var state = PhoneCallState.IDLE

    val listening: Boolean get() = registrations.isNotEmpty()

    /** Starts or stops listening to match the permission. Call from the main thread. */
    fun refresh() {
        if (!granted(context)) { stop(); return }
        if (subscriptionWatch == null) watchSubscriptions()
        register()
    }

    fun stop() {
        subscriptionWatch?.let { runCatching(it) }
        subscriptionWatch = null
        unregister()
        subscriptions = emptySet()
        publish(PhoneCallState.IDLE)
    }

    /** Re-registers when SIMs change, e.g. a second eSIM turned on while the relay runs. */
    private fun register() {
        val current = activeSubscriptions()
        if (listening && current == subscriptions) return
        unregister()
        subscriptions = current
        runCatching { current.forEach(::listen) }.onFailure {
            android.util.Log.w("AirRelay", "phone call state unavailable: ${it.javaClass.simpleName}")
            unregister()
        }
    }

    private fun unregister() {
        registrations.forEach { runCatching(it) }
        registrations.clear()
        tracker.clear()
    }

    private fun activeSubscriptions(): Set<Int> = runCatching {
        context.getSystemService(SubscriptionManager::class.java)?.activeSubscriptionInfoList?.map { it.subscriptionId }
    }.getOrNull().orEmpty().toSet().ifEmpty { setOf(SubscriptionManager.INVALID_SUBSCRIPTION_ID) }

    private fun watchSubscriptions() {
        val manager = context.getSystemService(SubscriptionManager::class.java) ?: return
        val listener = object : SubscriptionManager.OnSubscriptionsChangedListener() {
            override fun onSubscriptionsChanged() { if (granted(context)) register() }
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 30) manager.addOnSubscriptionsChangedListener(context.mainExecutor, listener)
            else @Suppress("DEPRECATION") manager.addOnSubscriptionsChangedListener(listener)
            subscriptionWatch = { manager.removeOnSubscriptionsChangedListener(listener) }
        }
    }

    private fun listen(id: Int) {
        val base = context.getSystemService(TelephonyManager::class.java) ?: return
        val telephony = if (id == SubscriptionManager.INVALID_SUBSCRIPTION_ID) base else base.createForSubscriptionId(id)
        if (Build.VERSION.SDK_INT >= 31) {
            val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) = publish(tracker.update(id, state))
            }
            telephony.registerTelephonyCallback(context.mainExecutor, callback)
            registrations += { telephony.unregisterTelephonyCallback(callback) }
        } else {
            @Suppress("DEPRECATION")
            val listener = object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) = publish(tracker.update(id, state))
            }
            @Suppress("DEPRECATION")
            telephony.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
            @Suppress("DEPRECATION")
            registrations += { telephony.listen(listener, PhoneStateListener.LISTEN_NONE) }
        }
    }

    private fun publish(next: PhoneCallState) {
        if (next == state) return
        state = next
        onChange(next)
    }

    companion object {
        const val KEY = "phone_state"

        fun granted(context: Context): Boolean =
            context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
    }
}

/**
 * Combines each SIM's call state. Android reports RINGING both for a new call
 * and for a call waiting during another call, so a SIM that went off-hook
 * stays in a call until it is idle again: a waiting call is the phone's to
 * signal, not the Air's.
 */
internal class RelayPhoneCallTracker {
    private val states = mutableMapOf<Int, Int>()
    private val inCall = mutableSetOf<Int>()

    fun update(subscription: Int, state: Int): PhoneCallState {
        when (state) {
            TelephonyManager.CALL_STATE_OFFHOOK -> inCall += subscription
            TelephonyManager.CALL_STATE_IDLE -> inCall -= subscription
        }
        states[subscription] = state
        return current()
    }

    fun current(): PhoneCallState = when {
        inCall.isNotEmpty() -> PhoneCallState.IN_CALL
        TelephonyManager.CALL_STATE_RINGING in states.values -> PhoneCallState.RINGING
        else -> PhoneCallState.IDLE
    }

    fun clear() { states.clear(); inCall.clear() }
}

/**
 * Decides which signal rings the Air for a phone call while the call state is
 * available, so one call rings once and whatever started the ring, the call
 * state ends it. The Phone app's own notification waits briefly for the call
 * state and only rings if the call state has not reported the call.
 */
internal class RelayPhoneCallRouter {
    sealed interface Action
    /** Ring for this key: [RelayPhoneCalls.KEY] for the call state, else a notification key. */
    data class Ring(val key: String) : Action
    /** Hold this notification back and ask again with [fallbackDue] after a short wait. */
    data class Wait(val key: String) : Action
    /** This notification does not ring: [reason] goes to the diagnostic report. */
    data class Skip(val key: String, val reason: String) : Action
    /** Stop ringing for these keys. */
    data class End(val keys: Set<String>) : Action

    var listening = false
        set(value) { field = value; if (!value) { state = PhoneCallState.IDLE; waiting.clear() } }
    private var state = PhoneCallState.IDLE
    private val waiting = mutableSetOf<String>()
    /** Notification rings that belong to the current phone call. */
    private val notificationRings = mutableSetOf<String>()

    /** A ringing-call notification from the Phone app. Null: nothing new (a re-post while waiting). */
    fun notification(key: String): Action? = when {
        !listening -> Ring(key)
        state == PhoneCallState.IN_CALL -> Skip(key, "call_waiting")
        state == PhoneCallState.RINGING -> Skip(key, "via_call_state")
        key in waiting -> null
        else -> { waiting += key; Wait(key) }
    }

    fun fallbackDue(key: String): Action {
        waiting -= key
        return when (state) {
            PhoneCallState.RINGING -> Skip(key, "via_call_state")
            PhoneCallState.IN_CALL -> Skip(key, "call_waiting")
            PhoneCallState.IDLE -> { notificationRings += key; Ring(key) }
        }
    }

    /** The notification went away or its call ended some other way. */
    fun notificationGone(key: String) { waiting -= key; notificationRings -= key }

    fun callState(next: PhoneCallState): List<Action> {
        val previous = state
        state = next
        if (!listening || next == previous) return emptyList()
        if (next == PhoneCallState.RINGING) {
            val held = waiting.map { Skip(it, "via_call_state") }
            waiting.clear()
            // A notification that already rings is this call: the call state only takes over ending it.
            val ring = if (notificationRings.isEmpty()) listOf(Ring(RelayPhoneCalls.KEY)) else emptyList()
            return held + ring
        }
        // Answered, declined or missed: whatever rings for the phone call stops.
        val ended = setOf(RelayPhoneCalls.KEY) + notificationRings
        notificationRings.clear()
        val held = waiting.map { Skip(it, if (next == PhoneCallState.IN_CALL) "call_waiting" else "call_ended") }
        waiting.clear()
        return held + End(ended)
    }
}
