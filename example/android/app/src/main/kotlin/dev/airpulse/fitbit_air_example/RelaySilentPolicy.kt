package dev.airpulse.fitbit_air_example

import android.app.Notification
import android.app.NotificationManager

/**
 * Mirrors when Android itself keeps a notification quiet, so the Air stays
 * quiet too: background syncs (WhatsApp's "Checking for new messages"),
 * in-app updates posted on a silent channel, and anything posted with
 * Notification.Builder.setSilent(true).
 */
internal object RelaySilentPolicy {
    /**
     * [importance] is the channel importance from the notification ranking,
     * including any change the user made in Android settings; unknown
     * (IMPORTANCE_UNSPECIFIED) counts as alerting.
     */
    fun silent(importance: Int, groupChild: Boolean, groupAlertBehavior: Int): Boolean =
        importance in NotificationManager.IMPORTANCE_NONE..NotificationManager.IMPORTANCE_LOW ||
            // setSilent(true) and apps that let only the group summary alert.
            (groupChild && groupAlertBehavior == Notification.GROUP_ALERT_SUMMARY)
}
