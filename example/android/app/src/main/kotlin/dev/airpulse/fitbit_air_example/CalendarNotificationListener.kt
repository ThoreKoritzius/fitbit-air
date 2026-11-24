package dev.airpulse.fitbit_air_example

import android.os.Build
import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class CalendarNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        instance = this
        connected = true
        RelayAutoResume.resume(applicationContext, "listener")
    }

    override fun onListenerDisconnected() {
        RelayConversations.stopCapture()
        instance = null
        connected = false
        if (!CalendarRelayService.hasAccess(this)) {
            // Access revoked: the user turned notifications off.
            RelayAutoResume.setWanted(this, false)
            CalendarRelayService.active?.requestStop("Notification access was turned off. Allow it to start again.")
            return
        }
        // A temporary unbind: resume when Android reconnects the listener.
        CalendarRelayService.active?.requestStop("Notification access disconnected. Air Relay resumes when Android reconnects it.")
        runCatching { NotificationListenerService.requestRebind(android.content.ComponentName(this, CalendarNotificationListener::class.java)) }
    }

    override fun onDestroy() {
        RelayConversations.stopCapture()
        instance = null
        connected = false
        CalendarRelayService.active?.requestStop("Notification listener stopped. Start the relay again when access is available.")
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val relay = CalendarRelayService.active
        val callType = callType(sbn.notification)
        if (callType == RelayCallPolicy.INCOMING) {
            // Package only: never the caller. Lets a test call show whether it was recognized.
            android.util.Log.i("AirRelay", "incoming call from ${sbn.packageName}; relay ${if (relay == null) "off" else "on"}")
            relay?.onIncomingCall(sbn.packageName, sbn.key, sbn.postTime)
            return
        }
        if (sbn.notification.category == Notification.CATEGORY_CALL || sbn.notification.fullScreenIntent != null) {
            // Shape only, never the caller: which call-like notifications are not recognized as ringing.
            val n = sbn.notification
            android.util.Log.i("AirRelay", "call-like notification from ${sbn.packageName}: category=${n.category} type=$callType " +
                "declared=${n.extras.getInt(RelayCallPolicy.EXTRA_CALL_TYPE)} fullScreen=${n.fullScreenIntent != null} " +
                "ongoing=${n.flags and Notification.FLAG_ONGOING_EVENT != 0} " +
                "style=${n.extras.getString(Notification.EXTRA_TEMPLATE)?.substringAfterLast('$')}")
        }
        // A call notification that is no longer ringing means the call was answered or ended.
        // Other notifications never end a call.
        if (sbn.notification.category == Notification.CATEGORY_CALL) relay?.onCallEnded(sbn.key, "no longer ringing")
        if (callType == RelayCallPolicy.ONGOING ||
            callType == RelayCallPolicy.SCREENING) return
        runCatching { RelayConversations.observe(sbn.packageName, sbn.notification, sbn.isOngoing, appLabel(sbn.packageName)) }
            .onFailure { RelayConversations.captureError() }
        if (relay == null) return
        if (!relay.acceptsPackage(sbn.packageName)) return
        val ranking = runCatching {
            NotificationListenerService.Ranking().takeIf { currentRanking?.getRanking(sbn.key, it) == true }
        }.getOrNull()
        val n = sbn.notification
        val summary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0
        val silent = RelaySilentPolicy.silent(
            importance = ranking?.importance ?: android.app.NotificationManager.IMPORTANCE_UNSPECIFIED,
            groupChild = n.group != null && !summary,
            groupAlertBehavior = if (Build.VERSION.SDK_INT >= 26) n.groupAlertBehavior else Notification.GROUP_ALERT_ALL,
        )
        // Opt-in: the text stays local to this call; only the tone and matched rule reach the relay.
        // A silent notification never buzzes, so its text is not read at all.
        val keywordRules = relay.keywordRules
        val text = if (silent || (!relay.toneEnabled && keywordRules.isEmpty())) null
            else runCatching { latestMessageText(sbn.notification)?.toString() }.getOrNull()
        val keyword = runCatching { RelayKeywordMatcher.firstMatch(keywordRules, text)?.let(keywordRules::get) }.getOrNull()
        val tone = if (silent || !relay.toneEnabled) null else runCatching {
            RelayToneClassifier.classify(text,
                RelayNotificationSignals.from(sbn.notification, ranking, System.currentTimeMillis()),
                RelayToneLexicons.shared(this))
        }.getOrNull()
        relay.onAlert(
            sbn.packageName, sbn.key, sbn.postTime, sbn.isOngoing, summary,
            latestMessageTimestamp(sbn.notification),
            RelayConversations.identities(sbn.packageName, sbn.notification, appLabel(sbn.packageName)), tone, keyword,
            silent = silent, alertOnce = n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0,
        )
    }

    private val labels = mutableMapOf<String, String?>()

    /** The app's display name, so a notification titled just "Signal" is never taken for a chat. */
    fun appLabel(packageName: String): String? = synchronized(labels) {
        labels.getOrPut(packageName) {
            runCatching {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
            }.getOrNull()
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        CalendarRelayService.active?.onAlertRemoved(sbn.key, call = sbn.notification.category == Notification.CATEGORY_CALL)
    }

    companion object {
        private var instance: CalendarNotificationListener? = null

        @Suppress("DEPRECATION")
        fun latestMessageTimestamp(notification: Notification): Long? {
            // This public framework parser is available from Android 11.
            // Older devices retain conservative once-per-notification behavior.
            if (Build.VERSION.SDK_INT < 30) return null
            return runCatching {
                Notification.MessagingStyle.Message.getMessagesFromBundleArray(
                    notification.extras.getParcelableArray(Notification.EXTRA_MESSAGES)
                ).maxOfOrNull { it.timestamp }?.takeIf { it > 0 }
            }.getOrNull()
        }

        @Suppress("DEPRECATION")
        fun latestMessageText(notification: Notification): CharSequence? {
            if (Build.VERSION.SDK_INT >= 30) runCatching {
                Notification.MessagingStyle.Message.getMessagesFromBundleArray(
                    notification.extras.getParcelableArray(Notification.EXTRA_MESSAGES)
                ).maxByOrNull { it.timestamp }?.text
            }.getOrNull()?.let { return it }
            return notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: notification.extras.getCharSequence(Notification.EXTRA_TEXT)
        }

        fun captureExisting() {
            runCatching {
                val listener = instance ?: return
                listener.activeNotifications?.take(128)?.forEach { sbn ->
                        runCatching { RelayConversations.observe(sbn.packageName, sbn.notification, sbn.isOngoing,
                            listener.appLabel(sbn.packageName), existing = true) }
                            .onFailure { RelayConversations.captureError() }
                    }
            }.onFailure { RelayConversations.captureError() }
        }

        fun existingKeys(selectedPackages: Set<String>): Map<String, Long?> = instance?.activeNotifications
            ?.filter { it.packageName in selectedPackages }
            ?.associate { it.key to latestMessageTimestamp(it.notification) } ?: emptyMap()

        fun existingRingingKeys(): List<String> = instance?.activeNotifications.orEmpty()
            .filter { callType(it.notification) == RelayCallPolicy.INCOMING }
            .map { it.key }

        /** Works on every Android version: older ones just never declare a call type. */
        private fun callType(notification: Notification): Int = RelayCallPolicy.callType(
            isCall = notification.category == Notification.CATEGORY_CALL,
            summary = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0,
            declaredType = runCatching { notification.extras.getInt(RelayCallPolicy.EXTRA_CALL_TYPE) }.getOrDefault(0),
            fullScreen = notification.fullScreenIntent != null,
        )

        var connected = false
            private set
    }
}
