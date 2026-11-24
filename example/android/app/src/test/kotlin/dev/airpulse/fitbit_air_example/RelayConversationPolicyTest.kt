package dev.airpulse.fitbit_air_example

import org.junit.Assert.*
import org.junit.Test

class RelayConversationPolicyTest {
    @Test fun identitiesWorkForAnyChatAppAndStaySeparatePerApp() {
        val packages = listOf("com.whatsapp", "org.thoughtcrime.securesms", "org.telegram.messenger",
            "com.google.android.apps.messaging")
        val matches = packages.map { RelayConversationIdentity.options(it, "thread-7", "Alice", true) }
        assertTrue(matches.all { it.map { match -> match.kind } == listOf("shortcut", "name") })
        assertEquals(8, matches.flatten().map { it.id }.toSet().size)
    }

    @Test fun savedChatStillUsesFreshnessAndDuplicateFilter() {
        val filter = CalendarAlertFilter(1000, selectedPackages = setOf("com.whatsapp"))
        val config = RelayRuleConfig(apps = mapOf("com.whatsapp" to RelayAppRule(true)),
            chats = mapOf("alice" to SavedChat("com.whatsapp", "Alice")), onlySavedChats = setOf("com.whatsapp"))
        fun accept(key: String, time: Long, id: String?) =
            filter.accept("com.whatsapp", key, time, time, false, false, time) &&
                config.match("com.whatsapp", setOfNotNull(id)) != null
        assertTrue(accept("chat1", 1100, "alice"))
        assertFalse(accept("chat1", 1100, "alice"))
        assertTrue(accept("chat1", 1200, "alice"))
        assertFalse(accept("chat2", 1300, "bob"))
        assertFalse(accept("chat1", 900, "alice"))
        // Rejecting an unidentified update still consumes its timestamp.
        assertFalse(accept("chat3", 1400, null))
        assertFalse(accept("chat3", 1400, "alice"))
    }
    @Test fun missingShortcutCanOfferExplicitNameRuleWithoutChangingStableIds() {
        val stable = RelayConversationIdentity.options("com.whatsapp", "stable-chat", "Alice", true)
        val missing = RelayConversationIdentity.options("com.whatsapp", null, "Alice", true)
        assertEquals(listOf("shortcut", "name"), stable.map { it.kind })
        assertEquals("name", missing.single().kind)
        assertEquals(stable.last().id, missing.single().id)
        // A stable rule never turns into a name rule implicitly.
        assertNotEquals(stable.first().id, missing.single().id)
        assertNotEquals(missing.single().id,
            RelayConversationIdentity.options("com.whatsapp.w4b", null, "Alice", true).single().id)
        assertNotEquals(missing.single().id,
            RelayConversationIdentity.options("com.whatsapp", null, "alice", true).single().id)
    }

    @Test fun namesRequireMessageMetadataAndCannotUseGenericAppTitles() {
        assertTrue(RelayConversationIdentity.options("com.whatsapp", null, "Alice", false).isEmpty())
        assertTrue(RelayConversationIdentity.options("com.whatsapp", null, "WhatsApp", true).isEmpty())
        assertTrue(RelayConversationIdentity.options("com.whatsapp", null, null, true).isEmpty())
        // Any app's own name is generic too, using its label from the phone.
        assertTrue(RelayConversationIdentity.options("org.telegram.messenger", null, "Telegram", true, "Telegram").isEmpty())
        assertEquals("name", RelayConversationIdentity.options("org.telegram.messenger", null, "Alice", true, "Telegram").single().kind)
    }

    @Test fun captureExpiryKeepsResultsAvailableForSelectionButStopsListening() {
        val window = ConversationCaptureWindow()
        window.start(1000)
        assertTrue(window.listening(60_999))
        assertFalse(window.listening(61_000))
        assertTrue(window.selectable(61_000))
        assertFalse(window.selectable(361_000))
        // Even a callback delivered after deep sleep must not resurrect stale results.
        window.finish(900_000)
        assertFalse(window.selectable(900_000))
    }

    @Test fun explicitStopPreservesResultsAndClearRemovesThem() {
        val window = ConversationCaptureWindow()
        window.start(1000)
        window.finish(2000)
        assertFalse(window.listening(2000))
        assertEquals(0L, window.remaining(2000))
        assertTrue(window.selectable(301_999))
        assertFalse(window.selectable(302_000))
        window.clear()
        assertFalse(window.selectable(2000))
    }

}
