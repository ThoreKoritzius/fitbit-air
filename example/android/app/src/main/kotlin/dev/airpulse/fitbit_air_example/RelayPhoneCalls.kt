package dev.airpulse.fitbit_air_example

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager

/**
 * Phone (SIM) calls straight from Android's call state, once the user allows
 * the Phone permission. Unlike the call notification, the call state does not
 * depend on how a phone maker's call app draws its call screen. App calls
 * (WhatsApp, Signal, …) are not reported here and keep coming from
 * notifications. Only the state is read, never the number.
 */
internal class RelayPhoneCalls(private val context: Context, private val onChange: (ringing: Boolean) -> Unit) {
    /** One registration per SIM, so a call on either SIM of a dual-SIM phone is seen. */
    private val registrations = mutableListOf<() -> Unit>()
    private val states = mutableMapOf<Int, Int>()
    private var ringing = false

    val listening: Boolean get() = registrations.isNotEmpty()

    /** Starts or stops listening to match the permission. Call from the main thread. */
    fun refresh() {
        if (!granted(context)) { stop(); return }
        if (listening) return
        runCatching { start() }.onFailure {
            android.util.Log.w("AirRelay", "phone call state unavailable: ${it.javaClass.simpleName}")
            stop()
        }
    }

    fun stop() {
        registrations.forEach { runCatching(it) }
        registrations.clear()
        states.clear()
        if (ringing) { ringing = false; onChange(false) }
    }

    private fun start() {
        val base = context.getSystemService(TelephonyManager::class.java) ?: return
        val subscriptions = runCatching {
            context.getSystemService(SubscriptionManager::class.java)?.activeSubscriptionInfoList?.map { it.subscriptionId }
        }.getOrNull().orEmpty().ifEmpty { listOf(SubscriptionManager.INVALID_SUBSCRIPTION_ID) }
        for (id in subscriptions) {
            val telephony = if (id == SubscriptionManager.INVALID_SUBSCRIPTION_ID) base else base.createForSubscriptionId(id)
            if (Build.VERSION.SDK_INT >= 31) {
                val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) = update(id, state)
                }
                telephony.registerTelephonyCallback(context.mainExecutor, callback)
                registrations += { telephony.unregisterTelephonyCallback(callback) }
            } else {
                @Suppress("DEPRECATION")
                val listener = object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) = update(id, state)
                }
                @Suppress("DEPRECATION")
                telephony.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
                @Suppress("DEPRECATION")
                registrations += { telephony.listen(listener, PhoneStateListener.LISTEN_NONE) }
            }
        }
    }

    private fun update(subscription: Int, state: Int) {
        states[subscription] = state
        val now = RelayPhoneCallState.ringing(states.values)
        if (now != ringing) { ringing = now; onChange(now) }
    }

    companion object {
        const val KEY = "phone_state"

        fun granted(context: Context): Boolean =
            context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
    }
}

internal object RelayPhoneCallState {
    /**
     * A call rings while any SIM rings and none is in a call: a second call
     * during a call (call waiting) is the phone's to signal, not the Air's.
     */
    fun ringing(states: Collection<Int>): Boolean =
        TelephonyManager.CALL_STATE_RINGING in states && TelephonyManager.CALL_STATE_OFFHOOK !in states
}
