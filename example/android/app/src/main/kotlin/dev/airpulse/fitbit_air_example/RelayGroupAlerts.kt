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
 * summary alerted buzzes itself ([pairsWithSummary], then [claim] once the
 * message matches the rules, so a muted message leaves the alert for the
 * next one). Gmail posts the summary first, a few milliseconds ahead of the
 * message.
 *
 * Any other summary stays quiet: its messages alert themselves, or Android
 * keeps the summary quiet (GROUP_ALERT_CHILDREN).
 */
internal class RelayGroupAlerts(private val maxAgeMillis: Long = 30_000) {
    /** A message held for its summary, with what it decides by itself: its chat, keyword rule and tone. */
    data class Message(
        val identities: Set<String>,
        val tone: RelayTone? = null,
        val keyword: RelayKeywordRule? = null,
        val postedAt: Long = 0,
    )

    /**
     * A summary that alerts. [messages]: the held messages it alerts for;
     * empty when none was seen. [fresh]: messages arrived since the last
     * alert, so an update of a summary already shown alerts again.
     */
    data class SummaryAlert(val messages: List<Message>, val fresh: Boolean)

    private val held = LinkedHashMap<String, MutableList<Message>>()
    /** Summaries that alerted with no message held yet: the message may follow. */
    private val alerted = LinkedHashMap<String, Long>()
    /** Summary notifications already posted, so FLAG_ONLY_ALERT_ONCE only quiets their updates. */
    private val summaryKeys = LinkedHashSet<String>()

    /** Whether a message quiet for its group summary follows a summary that just alerted: it buzzes itself. */
    fun pairsWithSummary(group: String, postedAt: Long): Boolean {
        // setSilent(true) puts an ungrouped notification in this group: it is just quiet.
        if (group.endsWith("|$SILENT_GROUP")) return false
        val at = alerted[group] ?: return false
        return postedAt - at in -PAIR_MILLIS..PAIR_MILLIS
    }

    /** A message that matched the rules buzzed for the summary that just alerted. */
    fun claim(group: String) { alerted.remove(group) }

    /** A message posted quietly because its group alerts through the summary that has not come yet. */
    fun hold(group: String, message: Message) {
        if (group.endsWith("|$SILENT_GROUP")) return
        val postedAt = message.postedAt
        val messages = held.remove(group) ?: mutableListOf()
        messages.removeAll { postedAt - it.postedAt > maxAgeMillis }
        if (messages.size < MAX_MESSAGES) messages += message
        held[group] = messages
        while (held.size > MAX_GROUPS) held.remove(held.keys.first())
    }

    /** A notification was removed: a summary posted again under [key] is new. */
    fun removed(key: String) { summaryKeys.remove(key) }

    /**
     * A group summary was posted: whether it alerts now, and for which messages.
     * [key]: the summary notification's own key. [alertOnce]: FLAG_ONLY_ALERT_ONCE,
     * so its first post alerts and its updates do not. [silent]: Android keeps
     * the summary itself quiet (a quiet channel), so neither it nor its messages alert.
     */
    fun summary(
        group: String, key: String, groupAlertBehavior: Int, postedAt: Long,
        alertOnce: Boolean = false, silent: Boolean = false,
    ): SummaryAlert? {
        val firstPost = summaryKeys.add(key)
        while (summaryKeys.size > MAX_SUMMARIES) summaryKeys.remove(summaryKeys.first())
        if (silent || groupAlertBehavior == Notification.GROUP_ALERT_CHILDREN) {
            held.remove(group); alerted.remove(group); return null
        }
        val messages = held.remove(group).orEmpty().filter { postedAt - it.postedAt in -maxAgeMillis..maxAgeMillis }
        return when {
            messages.isNotEmpty() -> SummaryAlert(messages, fresh = true)
            // Declared on the summary itself: Android alerts for it even without a message seen here.
            groupAlertBehavior == Notification.GROUP_ALERT_SUMMARY -> SummaryAlert(emptyList(), fresh = false)
            else -> {
                // Its message may still be on the way: it buzzes when it arrives.
                if (!alertOnce || firstPost) {
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
        private const val MAX_SUMMARIES = 128

        /** "package|group": groups only ever match within their app. */
        fun key(packageName: String, group: String) = "$packageName|$group"
    }
}

/**
 * The vibration for an alerting summary: the strongest match among its held
 * messages, each with its own chat, keyword rule and tone, so mutes, keyword
 * mutes and "only saved chats" still apply. Without any held message the chat
 * is unknown, and a muted chat in the app keeps it quiet; the summary's own
 * [tone] and [keyword] then decide.
 */
internal fun RelayRuleConfig.matchSummary(
    packageName: String, alert: RelayGroupAlerts.SummaryAlert, tone: RelayTone? = null, keyword: RelayKeywordRule? = null,
): RelayMatch? {
    if (alert.messages.isEmpty()) {
        if (chats.values.any { it.packageName == packageName && it.preset == RelayPreset.MUTE }) return null
        return match(packageName, emptySet(), tone, keyword)
    }
    return alert.messages.mapNotNull { message ->
        // Only a rule that still exists, as for any notification.
        match(packageName, message.identities, message.tone, message.keyword?.takeIf { it in keywords })
    }.maxByOrNull { it.specificity }
}
