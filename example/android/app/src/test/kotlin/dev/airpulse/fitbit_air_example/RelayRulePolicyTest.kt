package dev.airpulse.fitbit_air_example

import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class RelayRulePolicyTest {
    private val whatsapp = "com.whatsapp"
    private val config = RelayRuleConfig(apps = mapOf(whatsapp to RelayAppRule(true), "calendar" to RelayAppRule(true, RelayPreset.DOUBLE)),
        chats = mapOf("alice" to SavedChat(whatsapp, "Alice", preset = RelayPreset.HIGH),
            "muted" to SavedChat(whatsapp, "Muted", preset = RelayPreset.MUTE), "bob" to SavedChat(whatsapp, "Bob")))

    @Test fun `only saved chats works per app for any chat app`() {
        val signal = "org.thoughtcrime.securesms"
        val telegram = "org.telegram.messenger"
        val rules = config.copy(
            apps = config.apps + listOf(signal, telegram).associateWith { RelayAppRule(true) },
            chats = config.chats + ("team" to SavedChat(signal, "Team", preset = RelayPreset.DOUBLE)),
            onlySavedChats = setOf(signal),
        )
        assertNull(rules.match(signal, emptySet()))
        assertEquals(RelayMatch(RelayPreset.DOUBLE, 1), rules.match(signal, setOf("team")))
        // Other apps, including WhatsApp, are unaffected by Signal's setting.
        assertEquals(RelayMatch(RelayPreset.CURRENT, 0), rules.match(telegram, emptySet()))
        assertEquals(RelayMatch(RelayPreset.CURRENT, 0), rules.match(whatsapp, setOf("carol")))
        // A chat saved in one app never matches in another.
        assertEquals(RelayMatch(RelayPreset.CURRENT, 0), rules.match(telegram, setOf("team")))
        assertNull(rules.copy(onlySavedChats = setOf(telegram)).match(telegram, setOf("team")))
    }
    @Test fun `migration preserves selection and style but backup restore does not reenable stale apps`() {
        val migrated = migrateRelayRules(setOf("com.whatsapp"), "double", false)
        assertEquals(setOf("com.whatsapp"), migrated.selectedPackages)
        assertEquals(RelayPreset.DOUBLE, migrated.defaultPreset)
        assertNull(migrated.copy(onlySavedChats = RelayConversationPolicy.whatsappPackages).match("com.whatsapp", emptySet()))
        assertTrue(migrateRelayRules(setOf("com.whatsapp"), "double", true).selectedPackages.isEmpty())
    }
    @Test fun `chat overrides app and mute wins across identity forms`() {
        assertEquals(RelayMatch(RelayPreset.HIGH, 1), config.match(whatsapp, setOf("alice")))
        assertNull(config.match(whatsapp, setOf("alice", "muted")))
        assertEquals(RelayMatch(RelayPreset.DOUBLE, 0), config.match("calendar", setOf("alice")))
        assertNull(config.match("unknown", emptySet()))
    }
    @Test fun `everyone else silent lets only saved chats through`() {
        val silent = config.copy(onlySavedChats = setOf(whatsapp))
        assertNull(silent.match(whatsapp, emptySet()))
        assertNull(silent.match(whatsapp, setOf("carol")))
        assertNull(silent.copy(chats = emptyMap()).match(whatsapp, setOf("alice")))
        assertEquals(RelayMatch(RelayPreset.HIGH, 1), silent.match(whatsapp, setOf("alice")))
        // A saved chat without its own vibration uses the app vibration.
        assertEquals(RelayMatch(RelayPreset.CURRENT, 0), silent.match(whatsapp, setOf("bob")))
        assertEquals(RelayMatch(RelayPreset.CURRENT, 0), config.match(whatsapp, setOf("carol")))
        assertNull(config.copy(apps = mapOf(whatsapp to RelayAppRule(false))).match(whatsapp, setOf("alice")))
    }
    @Test fun `message tone only applies when enabled and keeps mutes`() {
        assertEquals(RelayMatch(RelayPreset.CURRENT, 0), config.match(whatsapp, emptySet(), RelayTone.URGENT))
        val toned = config.copy(tone = RelayToneRules(true, urgent = RelayPreset.DOUBLE, neutral = RelayPreset.MUTE))
        assertEquals(RelayMatch(RelayPreset.DOUBLE, 2, RelayTone.URGENT), toned.match(whatsapp, setOf("alice"), RelayTone.URGENT))
        // The category is kept for history even when it keeps the chat's vibration.
        assertEquals(RelayMatch(RelayPreset.HIGH, 1, RelayTone.NEGATIVE), toned.match(whatsapp, setOf("alice"), RelayTone.NEGATIVE))
        assertEquals(RelayMatch(RelayPreset.DOUBLE, 0), toned.match("calendar", emptySet(), null))
        assertNull(toned.match(whatsapp, emptySet(), RelayTone.NEUTRAL))
        assertNull(toned.match(whatsapp, setOf("muted"), RelayTone.URGENT))
        val mutedApp = toned.copy(apps = mapOf(whatsapp to RelayAppRule(true, RelayPreset.MUTE)))
        assertNull(mutedApp.match(whatsapp, emptySet(), RelayTone.URGENT))
    }
    @Test fun `urgent message wins a burst over a person rule`() {
        val toned = config.copy(tone = RelayToneRules(true, urgent = RelayPreset.DOUBLE))
        val person = RelayBurstChoice(toned.match(whatsapp, setOf("alice"))!!, 2)
        val urgent = RelayBurstChoice(toned.match(whatsapp, emptySet(), RelayTone.URGENT)!!, 1)
        assertEquals(urgent, person.prefer(urgent))
    }
    private fun time(hour: Int, minute: Int = 0): Long = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        set(2026, Calendar.OCTOBER, 17, hour, minute, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    @Test fun `overnight quiet hours honor inclusive start exclusive end and timezone`() {
        val silence = RelaySilence(quietEnabled = true)
        val utc = TimeZone.getTimeZone("UTC")
        assertNull(silence.reason(time(21, 59), utc))
        assertEquals("quiet_hours", silence.reason(time(22), utc))
        assertEquals("quiet_hours", silence.reason(time(6, 59), utc))
        assertNull(silence.reason(time(7), utc))
        assertEquals("quiet_hours", silence.reason(time(20), TimeZone.getTimeZone("GMT+03:00")))
    }
    @Test fun `pause expires without replay and takes precedence over quiet hours`() {
        val now = time(23)
        assertEquals("paused", RelaySilence(-1).reason(now))
        assertEquals("paused", RelaySilence(now + 1).reason(now))
        assertNull(RelaySilence(now).reason(now))
        assertEquals("quiet_hours", RelaySilence(now, true).reason(now, TimeZone.getTimeZone("UTC")))
    }
    @Test fun `daytime quiet window and disabled equal times`() {
        val silence = RelaySilence(quietEnabled = true, startMinute = 540, endMinute = 1020)
        assertEquals("quiet_hours", silence.reason(time(12), TimeZone.getTimeZone("UTC")))
        assertNull(silence.reason(time(18), TimeZone.getTimeZone("UTC")))
        assertNull(RelaySilence(startMinute = 0, endMinute = 0).reason(time(12)))
    }
    @Test(expected = IllegalArgumentException::class) fun `equal enabled times rejected`() { RelaySilence(quietEnabled = true, startMinute = 0, endMinute = 0) }
    @Test fun `burst specificity wins then newest breaks ties`() {
        val app = RelayBurstChoice(RelayMatch(RelayPreset.DOUBLE, 0), 100)
        val chat = RelayBurstChoice(RelayMatch(RelayPreset.HIGH, 1), 90)
        val next = RelayBurstChoice(RelayMatch(RelayPreset.LOW, 1), 110)
        assertEquals(chat, app.prefer(chat))
        assertEquals(chat, chat.prefer(app))
        assertEquals(next, chat.prefer(next))
        assertEquals(next, next.prefer(chat))
    }
    @Test fun `silenced notifications advance duplicate baseline and are not replayed`() {
        val filter = CalendarAlertFilter(100, selectedPackages = setOf(whatsapp))
        assertTrue(filter.accept(whatsapp, "id", 101, 102, false, false, 101))
        // Caller suppresses the accepted alert during pause, but freshness is retained.
        assertFalse(filter.accept(whatsapp, "id", 101, 105, false, false, 101))
        assertTrue(filter.accept(whatsapp, "id", 106, 107, false, false, 106))
    }
}
