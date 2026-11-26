package dev.airpulse.fitbit_air_example

import android.app.Notification
import android.app.NotificationManager
import org.junit.Assert.*
import org.junit.Test

/** Group summaries with the production filter, silent policy and rules, in the order the relay applies them. */
class RelayGroupAlertsTest {
    private val app = "com.example.mail"

    private class Relay(val config: RelayRuleConfig, packages: Set<String>) {
        val filter = CalendarAlertFilter(startedAt = 0, selectedPackages = packages)
        val groups = RelayGroupAlerts()
        val buzzes = mutableListOf<RelayMatch>()
        val silent = mutableListOf<String>()

        /** CalendarNotificationListener.onNotificationPosted, then CalendarRelayService.onAlert. */
        fun post(packageName: String, key: String, at: Long, group: String?, summary: Boolean,
                 behavior: Int = Notification.GROUP_ALERT_ALL, chats: Set<String> = emptySet(),
                 importance: Int = NotificationManager.IMPORTANCE_HIGH, alertOnce: Boolean = false,
                 keyword: RelayKeywordRule? = null) {
            val child = group != null && !summary
            val isSilent = RelaySilentPolicy.silent(importance, child, behavior)
            val held = RelaySilentPolicy.heldForSummary(importance, child, behavior)
            val groupKey = group?.let { RelayGroupAlerts.key(packageName, it) }
            val alert = if (summary && groupKey != null) groups.summary(groupKey, key, behavior, at, alertOnce, isSilent) else null
            if (!filter.accept(packageName, key, at, at + 100, ongoing = false, summary = summary && alert == null,
                    alertOnce = alertOnce, freshInGroup = alert?.fresh == true)) return
            val pairs = held && groupKey != null && groups.pairsWithSummary(groupKey, at)
            if (isSilent && !pairs) {
                if (held && groupKey != null) groups.hold(groupKey, RelayGroupAlerts.Message(chats, keyword = keyword, postedAt = at))
                silent += key; return
            }
            val match = if (alert != null) config.matchSummary(packageName, alert)
                else config.match(packageName, chats, keyword = keyword?.takeIf { it in config.keywords })
            if (match != null && pairs) groups.claim(groupKey!!)
            match?.let(buzzes::add)
        }
    }

    private fun relay(config: RelayRuleConfig = RelayRuleConfig(apps = mapOf(app to RelayAppRule(true)))) =
        Relay(config, config.selectedPackages)

    private val summaryOnly = Notification.GROUP_ALERT_SUMMARY

    @Test fun `an app that alerts through its group summary buzzes once`() {
        // Review: the children were silenced and the summary dropped, so the Air never buzzed.
        val relay = relay()
        relay.post(app, "m1", 1_000, group = "inbox", summary = false, behavior = summaryOnly)
        relay.post(app, "sum", 1_010, group = "inbox", summary = true, behavior = summaryOnly)
        assertEquals(1, relay.buzzes.size)
        assertEquals(listOf("m1"), relay.silent)
    }

    @Test fun `Gmail's order, summary a few milliseconds before its message, buzzes once per email`() {
        // Seen on a Pixel 7 Pro: Gmail updated the summary (behavior ALL) 6 ms before posting the
        // email (behavior SUMMARY), so a summary waiting for held messages never buzzed.
        val relay = relay()
        relay.post(app, "sum", 1_000, group = "account", summary = true)
        relay.post(app, "mail1", 1_006, group = "account", summary = false, behavior = summaryOnly, chats = setOf("a"))
        assertEquals(1, relay.buzzes.size)
        relay.post(app, "sum", 60_000, group = "account", summary = true)
        relay.post(app, "mail2", 60_006, group = "account", summary = false, behavior = summaryOnly)
        assertEquals(2, relay.buzzes.size)
        // A summary update on its own (an email read) and a later unrelated email do not pair up.
        relay.post(app, "sum", 90_000, group = "account", summary = true)
        relay.post(app, "mail3", 95_000, group = "account", summary = false, behavior = summaryOnly)
        assertEquals(2, relay.buzzes.size)
    }

    @Test fun `the first post of an alert-once summary still lets its message buzz`() {
        // Review: FLAG_ONLY_ALERT_ONCE quiets updates only, but the first summary was not remembered,
        // so the message after it had nothing to pair with.
        val relay = relay()
        relay.post(app, "sum", 1_000, group = "account", summary = true, alertOnce = true)
        relay.post(app, "m1", 1_005, group = "account", summary = false, behavior = summaryOnly)
        assertEquals(1, relay.buzzes.size)
        // Its update does not alert.
        relay.post(app, "sum", 9_000, group = "account", summary = true, alertOnce = true)
        relay.post(app, "m2", 9_005, group = "account", summary = false, behavior = summaryOnly)
        assertEquals(1, relay.buzzes.size)
        // Removed and posted again: new, so it alerts.
        relay.groups.removed("sum"); relay.filter.onRemoved("sum")
        relay.post(app, "sum", 20_000, group = "account", summary = true, alertOnce = true)
        relay.post(app, "m3", 20_005, group = "account", summary = false, behavior = summaryOnly)
        assertEquals(2, relay.buzzes.size)
    }

    @Test fun `a muted message does not use up the summary's alert`() {
        // Review: the muted message consumed the pending alert before its mute applied, and the
        // allowed message after it was held for a summary that never came.
        val config = RelayRuleConfig(apps = mapOf(app to RelayAppRule(true)),
            chats = mapOf("muted" to SavedChat(app, "Muted", preset = RelayPreset.MUTE)))
        val relay = relay(config)
        relay.post(app, "sum", 1_000, group = "account", summary = true)
        relay.post(app, "m1", 1_005, group = "account", summary = false, behavior = summaryOnly, chats = setOf("muted"))
        relay.post(app, "m2", 1_010, group = "account", summary = false, behavior = summaryOnly, chats = setOf("friend"))
        assertEquals(1, relay.buzzes.size)
        // One alert, one buzz: a third message does not buzz again.
        relay.post(app, "m3", 1_015, group = "account", summary = false, behavior = summaryOnly, chats = setOf("friend"))
        assertEquals(1, relay.buzzes.size)
    }

    @Test fun `a quiet summary does not let the message after it buzz`() {
        // Review: a low-importance summary 5 ms before its message was taken as alerting, so the
        // message buzzed although Android kept both quiet.
        val relay = relay()
        relay.post(app, "sum", 1_000, group = "account", summary = true, importance = NotificationManager.IMPORTANCE_LOW)
        relay.post(app, "m1", 1_005, group = "account", summary = false, behavior = summaryOnly)
        assertTrue(relay.buzzes.isEmpty())
    }

    @Test fun `a quiet summary does not buzz for the messages held before it`() {
        val relay = relay()
        relay.post(app, "m1", 1_000, group = "account", summary = false, behavior = summaryOnly)
        relay.post(app, "sum", 1_005, group = "account", summary = true, importance = NotificationManager.IMPORTANCE_LOW)
        relay.post(app, "sum", 1_500, group = "account", summary = true)
        assertTrue(relay.buzzes.isEmpty())
    }

    @Test fun `a message held before its summary keeps its keyword mute`() {
        // Review: a held message kept its chat but lost its keyword, so a keyword mute was bypassed.
        val mute = RelayKeywordRule(listOf("newsletter"), RelayPreset.MUTE)
        val config = RelayRuleConfig(apps = mapOf(app to RelayAppRule(true)), keywords = listOf(mute))
        val relay = relay(config)
        relay.post(app, "m1", 1_000, group = "account", summary = false, behavior = summaryOnly, keyword = mute)
        relay.post(app, "sum", 1_005, group = "account", summary = true)
        assertTrue(relay.buzzes.isEmpty())
        // The same sequence without the keyword buzzes.
        relay.post(app, "m2", 2_000, group = "account", summary = false, behavior = summaryOnly)
        relay.post(app, "sum", 2_005, group = "account", summary = true)
        assertEquals(1, relay.buzzes.size)
    }

    @Test fun `a message following its summary keeps its keyword mute`() {
        val mute = RelayKeywordRule(listOf("newsletter"), RelayPreset.MUTE)
        val config = RelayRuleConfig(apps = mapOf(app to RelayAppRule(true)), keywords = listOf(mute))
        val relay = relay(config)
        relay.post(app, "sum", 1_000, group = "account", summary = true)
        relay.post(app, "m1", 1_005, group = "account", summary = false, behavior = summaryOnly, keyword = mute)
        assertTrue(relay.buzzes.isEmpty())
    }

    @Test fun `a message following its summary keeps its chat's mute`() {
        val config = RelayRuleConfig(apps = mapOf(app to RelayAppRule(true)),
            chats = mapOf("muted" to SavedChat(app, "Muted", preset = RelayPreset.MUTE)))
        val relay = relay(config)
        relay.post(app, "sum", 1_000, group = "account", summary = true)
        relay.post(app, "m1", 1_006, group = "account", summary = false, behavior = summaryOnly, chats = setOf("muted"))
        assertTrue(relay.buzzes.isEmpty())
    }

    @Test fun `the summary alerts again for a new message, not for a plain update`() {
        val relay = relay()
        relay.post(app, "m1", 1_000, group = "inbox", summary = false, behavior = summaryOnly)
        relay.post(app, "sum", 1_010, group = "inbox", summary = true)
        relay.post(app, "sum", 5_000, group = "inbox", summary = true) // e.g. a message was read
        assertEquals(1, relay.buzzes.size)
        relay.post(app, "m2", 9_000, group = "inbox", summary = false, behavior = summaryOnly)
        relay.post(app, "sum", 9_010, group = "inbox", summary = true)
        assertEquals(2, relay.buzzes.size)
    }

    @Test fun `a summary that alerts only once stays quiet on updates`() {
        val relay = relay()
        relay.post(app, "m1", 1_000, group = "inbox", summary = false, behavior = summaryOnly)
        relay.post(app, "sum", 1_010, group = "inbox", summary = true, alertOnce = true)
        relay.post(app, "m2", 9_000, group = "inbox", summary = false, behavior = summaryOnly)
        relay.post(app, "sum", 9_010, group = "inbox", summary = true, alertOnce = true)
        assertEquals(1, relay.buzzes.size)
    }

    @Test fun `ordinary groups still buzz once per message, never for the summary`() {
        val relay = relay()
        relay.post(app, "m1", 1_000, group = "inbox", summary = false)
        relay.post(app, "sum", 1_010, group = "inbox", summary = true)
        relay.post(app, "sum2", 1_020, group = "other", summary = true, behavior = Notification.GROUP_ALERT_CHILDREN)
        assertEquals(1, relay.buzzes.size)
    }

    @Test fun `a muted chat's message held for the summary keeps the summary quiet`() {
        val config = RelayRuleConfig(apps = mapOf(app to RelayAppRule(true)),
            chats = mapOf("muted" to SavedChat(app, "Muted", preset = RelayPreset.MUTE)))
        val relay = relay(config)
        relay.post(app, "m1", 1_000, group = "inbox", summary = false, behavior = summaryOnly, chats = setOf("muted"))
        relay.post(app, "sum", 1_010, group = "inbox", summary = true)
        assertTrue(relay.buzzes.isEmpty())
        // A message from another chat in the same group still buzzes.
        relay.post(app, "m2", 2_000, group = "inbox", summary = false, behavior = summaryOnly, chats = setOf("friend"))
        relay.post(app, "sum", 2_010, group = "inbox", summary = true)
        assertEquals(1, relay.buzzes.size)
    }

    @Test fun `only saved chats applies to the messages behind a summary`() {
        val config = RelayRuleConfig(apps = mapOf(app to RelayAppRule(true)), onlySavedChats = setOf(app),
            chats = mapOf("saved" to SavedChat(app, "Saved")))
        val relay = relay(config)
        relay.post(app, "m1", 1_000, group = "inbox", summary = false, behavior = summaryOnly, chats = setOf("stranger"))
        relay.post(app, "sum", 1_010, group = "inbox", summary = true)
        assertTrue(relay.buzzes.isEmpty())
        relay.post(app, "m2", 2_000, group = "inbox", summary = false, behavior = summaryOnly, chats = setOf("saved"))
        relay.post(app, "sum", 2_010, group = "inbox", summary = true)
        assertEquals(1, relay.buzzes.size)
    }

    @Test fun `a summary declaring summary alerts without a message seen stays quiet if any chat is muted`() {
        val muted = RelayRuleConfig(apps = mapOf(app to RelayAppRule(true)),
            chats = mapOf("muted" to SavedChat(app, "Muted", preset = RelayPreset.MUTE)))
        relay(muted).apply {
            post(app, "sum", 1_000, group = "inbox", summary = true, behavior = summaryOnly)
            assertTrue(buzzes.isEmpty())
        }
        relay().apply {
            post(app, "sum", 1_000, group = "inbox", summary = true, behavior = summaryOnly)
            assertEquals(1, buzzes.size)
        }
    }

    @Test fun `quiet channels, setSilent and unselected apps never buzz through a summary`() {
        val relay = relay()
        relay.post(app, "m1", 1_000, group = "inbox", summary = false, behavior = summaryOnly,
            importance = NotificationManager.IMPORTANCE_LOW)
        relay.post(app, "sum", 1_010, group = "inbox", summary = true)
        relay.post(app, "s1", 2_000, group = RelayGroupAlerts.SILENT_GROUP, summary = false, behavior = summaryOnly)
        relay.post(app, "sum-s", 2_010, group = RelayGroupAlerts.SILENT_GROUP, summary = true)
        relay.post("com.other", "o1", 3_000, group = "inbox", summary = false, behavior = summaryOnly)
        relay.post("com.other", "osum", 3_010, group = "inbox", summary = true)
        assertTrue(relay.buzzes.isEmpty())
    }

    @Test fun `a message held long before the summary does not make it alert`() {
        val relay = relay()
        relay.post(app, "m1", 1_000, group = "inbox", summary = false, behavior = summaryOnly)
        relay.post(app, "sum", 40_000, group = "inbox", summary = true)
        assertTrue(relay.buzzes.isEmpty())
    }
}
