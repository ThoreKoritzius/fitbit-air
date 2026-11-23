package dev.airpulse.fitbit_air_example

import org.junit.Assert.*
import org.junit.Test

class RelayConversationPolicyTest {
    @Test fun matchingTitlesAndShortcutsStaySeparateAcrossMessagingApps() {
        val packages = listOf("com.whatsapp", "com.google.android.apps.dynamite",
            "org.telegram.messenger", "com.google.android.apps.messaging")
        val matches = packages.map { RelayConversationIdentity.options(it, "thread-7", "Alice", true) }
        assertTrue(matches.all { it.map { match -> match.kind } == listOf("shortcut", "name") })
        assertEquals(8, matches.flatten().map { it.id }.toSet().size)
        assertTrue(RelayConversationIdentity.options("org.telegram.messenger", null, "Telegram", true).isEmpty())
        assertTrue(RelayConversationIdentity.options("com.google.android.gm", "thread-7", "Alice", true).isEmpty())
    }

    @Test fun unrestrictedModePreservesExistingAppsIncludingMissingMetadata() {
        for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b", "com.google.android.calendar")) {
            assertTrue(RelayConversationPolicy.accepts(pkg, false, emptySet(), null))
        }
    }

    @Test fun restrictedModeFailsClosedForEmptyOrUnknownConversation() {
        for (pkg in RelayConversationPolicy.packages) {
            assertFalse(RelayConversationPolicy.accepts(pkg, true, emptySet(), "chat"))
            assertFalse(RelayConversationPolicy.accepts(pkg, true, setOf("chat"), null))
            assertFalse(RelayConversationPolicy.accepts(pkg, true, setOf("chat"), "other"))
            assertTrue(RelayConversationPolicy.accepts(pkg, true, setOf("chat"), "chat"))
        }
    }

    @Test fun restrictionDoesNotChangeOtherApps() {
        assertTrue(RelayConversationPolicy.accepts("com.google.android.calendar", true, emptySet(), null))
    }

    @Test fun selectedChatStillUsesFreshnessAndDuplicateFilter() {
        val filter = CalendarAlertFilter(1000, selectedPackages = setOf("com.whatsapp"))
        fun accept(key: String, time: Long, id: String?) =
            filter.accept("com.whatsapp", key, time, time, false, false, time) &&
                RelayConversationPolicy.accepts("com.whatsapp", true, setOf("alice"), id)
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
        assertFalse(RelayConversationPolicy.accepts("com.whatsapp", true, setOf(stable.first().id), missing.single().id))
        assertNotEquals(missing.single().id,
            RelayConversationIdentity.options("com.whatsapp.w4b", null, "Alice", true).single().id)
        assertNotEquals(missing.single().id,
            RelayConversationIdentity.options("com.whatsapp", null, "alice", true).single().id)
    }

    @Test fun namesRequireMessageMetadataAndCannotUseGenericAppTitles() {
        assertTrue(RelayConversationIdentity.options("com.whatsapp", null, "Alice", false).isEmpty())
        assertTrue(RelayConversationIdentity.options("com.whatsapp", null, "WhatsApp", true).isEmpty())
        assertTrue(RelayConversationIdentity.options("com.whatsapp", null, null, true).isEmpty())
        assertTrue(RelayConversationIdentity.options("other.app", "id", "Alice", true).isEmpty())
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
