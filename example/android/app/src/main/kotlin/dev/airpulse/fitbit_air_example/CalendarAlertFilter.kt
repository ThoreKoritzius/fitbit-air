package dev.airpulse.fitbit_air_example

/** Metadata only. The legacy class name is internal to the original demo. */
internal class CalendarAlertFilter(
    private val startedAt: Long,
    existingKeys: Map<String, Long?> = emptyMap(),
    private val selectedPackages: Set<String> = setOf("com.google.android.calendar")
) {
    // Keep each key until Android reports that its notification was removed.
    private val seen = existingKeys.toMutableMap()

    fun onRemoved(key: String) {
        seen.remove(key)
    }

    fun accept(packageName: String, key: String, postedAt: Long, now: Long,
               ongoing: Boolean, summary: Boolean, messageTimestamp: Long? = null,
               alertOnce: Boolean = false): Boolean {
        if (packageName !in selectedPackages || ongoing || summary ||
            postedAt < startedAt || postedAt > now || now - postedAt >= 30_000) return false
        if (seen.containsKey(key)) {
            // FLAG_ONLY_ALERT_ONCE: Android never alerts for an update of a shown notification.
            if (alertOnce) return false
            // Messaging apps reuse chat IDs. Only a strictly newer message can buzz again.
            val previous = seen[key]
            if (messageTimestamp == null || messageTimestamp <= (previous ?: (startedAt - 1))) return false
        }
        if (messageTimestamp != null && messageTimestamp < startedAt) return false
        seen[key] = messageTimestamp
        return true
    }
}
