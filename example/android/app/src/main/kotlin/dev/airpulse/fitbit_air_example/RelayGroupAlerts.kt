package dev.airpulse.fitbit_air_example

import android.app.Notification

/**
 * Apps that group their notifications with GROUP_ALERT_SUMMARY let Android
 * alert only for the group summary: the messages in the group are posted
 * quietly ([RelaySilentPolicy]) and the summary makes the sound. The Air
 * follows Android and buzzes once per new message, matched against that
 * message's chat, since a summary does not say which chat it is for.
 *
 * The two arrive in either order: a summary posted just after its messages
 * buzzes for the messages held for it; a message posted just after its
 * summary alerted buzzes itself ([hold] returns true). Gmail posts the
 * summary first, a few milliseconds ahead of the message.
 *
 * Any other summary stays quiet: its messages alert themselves, or Android
 * keeps the summary quiet (GROUP_ALERT_CHILDREN).
 */
internal class RelayGroupAlerts(private val maxAgeMillis: Long = 30_000) {
    /**
     * A summary that alerts. [chats]: each held message's chat identities;
     * empty when no message was seen. [fresh]: messages arrived since the
     * last alert, so an update of a summary already shown alerts again.
     */
    data class SummaryAlert(val chats: List<Set<String>>, val fresh: Boolean)

    private val held = LinkedHashMap<String, MutableList<Pair<Long, Set<String>>>>()
    /** Summaries that alerted with no message held yet: the message may follow. */
    private val alerted = LinkedHashMap<String, Long>()

    /**
     * A message posted quietly because its group alerts through the summary.
     * True: its summary alerted just before, so this message buzzes now.
     */
    fun hold(group: String, identities: Set<String>, postedAt: Long): Boolean {
        // setSilent(true) puts an ungrouped notification in this group: it is just quiet.
        if (group.endsWith("|$SILENT_GROUP")) return false
        alerted.remove(group)?.let { if (postedAt - it in -PAIR_MILLIS..PAIR_MILLIS) return true }
        val messages = held.remove(group) ?: mutableListOf()
        messages.removeAll { postedAt - it.first > maxAgeMillis }
        if (messages.size < MAX_MESSAGES) messages += postedAt to identities
        held[group] = messages
        while (held.size > MAX_GROUPS) held.remove(held.keys.first())
        return false
    }

    /**
     * A group summary was posted: whether it alerts now, and for which chats.
     * [alertOnce]: FLAG_ONLY_ALERT_ONCE, so an update does not alert.
     */
    fun summary(group: String, groupAlertBehavior: Int, postedAt: Long, alertOnce: Boolean = false): SummaryAlert? {
        if (groupAlertBehavior == Notification.GROUP_ALERT_CHILDREN) { held.remove(group); alerted.remove(group); return null }
        val messages = held.remove(group).orEmpty().filter { postedAt - it.first in -maxAgeMillis..maxAgeMillis }
        return when {
            messages.isNotEmpty() -> SummaryAlert(messages.map { it.second }, fresh = true)
            // Declared on the summary itself: Android alerts for it even without a message seen here.
            groupAlertBehavior == Notification.GROUP_ALERT_SUMMARY -> SummaryAlert(emptyList(), fresh = false)
            else -> {
                // Its message may still be on the way: it buzzes when it arrives.
                if (!alertOnce) {
                    alerted.remove(group); alerted[group] = postedAt
                    while (alerted.size > MAX_GROUPS) alerted.remove(alerted.keys.first())
                }
                null
            }
        }
    }

    companion object {
        /** The group Notification.Builder.setSilent(true) uses when the app set none. */
        const val SILENT_GROUP = "silent"
        /** How far apart a summary and its message may be posted to count as one alert. */
        const val PAIR_MILLIS = 2_000L
        private const val MAX_GROUPS = 32
        private const val MAX_MESSAGES = 16

        /** "package|group": groups only ever match within their app. */
        fun key(packageName: String, group: String) = "$packageName|$group"
    }
}

/**
 * The vibration for an alerting summary: the strongest match among its held
 * messages' chats, so mutes and "only saved chats" still apply. Without any
 * held message the chat is unknown, and a muted chat in the app keeps it quiet.
 */
internal fun RelayRuleConfig.matchSummary(
    packageName: String, alert: RelayGroupAlerts.SummaryAlert, tone: RelayTone? = null, keyword: RelayKeywordRule? = null,
): RelayMatch? {
    if (alert.chats.isEmpty()) {
        if (chats.values.any { it.packageName == packageName && it.preset == RelayPreset.MUTE }) return null
        return match(packageName, emptySet(), tone, keyword)
    }
    return alert.chats.mapNotNull { match(packageName, it, tone, keyword) }.maxByOrNull { it.specificity }
}
