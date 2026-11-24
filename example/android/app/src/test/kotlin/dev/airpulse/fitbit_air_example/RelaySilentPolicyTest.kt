package dev.airpulse.fitbit_air_example

import android.app.Notification
import android.app.NotificationManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelaySilentPolicyTest {
    private fun silent(importance: Int, groupChild: Boolean = false, alert: Int = Notification.GROUP_ALERT_ALL) =
        RelaySilentPolicy.silent(importance, groupChild, alert)

    @Test fun silentAndMinimizedChannelsNeverBuzz() {
        assertTrue(silent(NotificationManager.IMPORTANCE_LOW))
        assertTrue(silent(NotificationManager.IMPORTANCE_MIN))
        assertTrue(silent(NotificationManager.IMPORTANCE_NONE))
    }

    @Test fun alertingChannelsBuzz() {
        assertFalse(silent(NotificationManager.IMPORTANCE_DEFAULT))
        assertFalse(silent(NotificationManager.IMPORTANCE_HIGH))
    }

    @Test fun unknownImportanceStillBuzzes() {
        assertFalse(silent(NotificationManager.IMPORTANCE_UNSPECIFIED))
    }

    @Test fun setSilentGroupChildrenNeverBuzz() {
        // Notification.Builder.setSilent(true) puts a child in a group whose summary alerts.
        assertTrue(silent(NotificationManager.IMPORTANCE_HIGH, groupChild = true, alert = Notification.GROUP_ALERT_SUMMARY))
        assertFalse(silent(NotificationManager.IMPORTANCE_HIGH, groupChild = true, alert = Notification.GROUP_ALERT_CHILDREN))
        assertFalse(silent(NotificationManager.IMPORTANCE_HIGH, groupChild = false, alert = Notification.GROUP_ALERT_SUMMARY))
    }
}
