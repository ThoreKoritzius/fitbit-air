package dev.airpulse.fitbit_air_example

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Remembers that the user turned notifications on, so the relay comes back
 * after a restart, an app update, the system ending the process, or Android
 * re-binding the notification listener. Only Stop or revoking notification
 * access turns it off.
 */
internal object RelayAutoResume {
    private const val PREFS = "relay_state"
    private const val WANTED = "wanted"

    fun wanted(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(WANTED, false)

    fun setWanted(context: Context, wanted: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(WANTED, wanted).apply()
    }

    /**
     * Starts the relay if the user left it on. [reason] is a fixed label
     * (`restart`, `update`, `listener`, `watchdog`) kept in the history entry.
     * Returns whether a start was requested.
     */
    fun resume(context: Context, reason: String): Boolean {
        if (!wanted(context) || CalendarRelayService.isRunning) return false
        if (!CalendarRelayService.hasAccess(context)) {
            // Access was revoked while off: that is a decision to stop.
            setWanted(context, false)
            return false
        }
        if (RelayRuleStore.read(context).selectedPackages.isEmpty()) return false
        return try {
            CalendarRelayService.start(context, resumeReason = reason)
            Log.i(TAG, "relay resumed: $reason")
            true
        } catch (error: Exception) {
            // Android can refuse background starts; the next trigger tries again.
            Log.w(TAG, "relay resume refused ($reason): ${error.javaClass.simpleName}")
            false
        }
    }

    private const val TAG = "AirRelay"
}

/** Resumes the relay after a reboot or an app update. */
class RelayResumeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reason = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> "restart"
            Intent.ACTION_MY_PACKAGE_REPLACED -> "update"
            else -> return
        }
        RelayAutoResume.resume(context.applicationContext, reason)
    }
}
