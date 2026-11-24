package dev.airpulse.fitbit_air_example

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarAlertFilterTest {
    private val calendar = "com.google.android.calendar"

    @Test fun ignoresOtherAppsSummariesOngoingAndOldAlerts() {
        val filter = CalendarAlertFilter(1_000)
        assertFalse(filter.accept("another.app", "other", 1_001, 1_010, false, false))
        assertFalse(filter.accept(calendar, "summary", 1_001, 1_010, false, true))
        assertFalse(filter.accept(calendar, "ongoing", 1_001, 1_010, true, false))
        assertFalse(filter.accept(calendar, "old", 999, 1_010, false, false))
        assertFalse(filter.accept(calendar, "expired", 1_001, 31_001, false, false))
        assertFalse(filter.accept(calendar, "future", 1_011, 1_010, false, false))
        assertTrue(filter.accept(calendar, "fresh", 1_001, 1_010, false, false))
    }

    @Test fun existingNotificationsRemainIgnoredWhenUpdatedAfterStart() {
        val filter = CalendarAlertFilter(1_000, mapOf("already-present" to null))
        assertFalse(filter.accept(calendar, "already-present", 2_000, 2_010, false, false))
        assertTrue(filter.accept(calendar, "new-reminder", 2_000, 2_010, false, false))
    }

    @Test fun updatesCannotBuzzAgainEvenIfTimestampChanges() {
        val filter = CalendarAlertFilter(1_000)
        assertTrue(filter.accept(calendar, "same-key", 1_001, 1_010, false, false))
        assertFalse(filter.accept(calendar, "same-key", 2_000, 2_010, false, false))
        assertTrue(filter.accept(calendar, "different-key", 2_000, 2_010, false, false))
    }

    @Test fun alertOnceUpdatesNeverBuzzEvenWithANewerMessage() {
        val filter = CalendarAlertFilter(1_000)
        assertTrue(filter.accept(calendar, "chat", 1_001, 1_010, false, false, 1_001, alertOnce = true))
        assertFalse(filter.accept(calendar, "chat", 2_000, 2_010, false, false, 2_000, alertOnce = true))
        assertTrue(filter.accept(calendar, "chat", 3_000, 3_010, false, false, 3_000))
    }

    @Test fun newAlertsStillWorkAfter256KeysWithoutReplayingActiveAlerts() {
        val filter = CalendarAlertFilter(1_000)
        repeat(512) { assertTrue(filter.accept(calendar, "key-$it", 1_001, 1_010, false, false)) }
        assertFalse(filter.accept(calendar, "key-0", 1_001, 1_010, false, false))
    }

    @Test fun onlyExplicitlySelectedAppsAreRelayed() {
        val filter = CalendarAlertFilter(1_000, selectedPackages = setOf("com.whatsapp", "com.whatsapp.w4b"))
        assertFalse(filter.accept(calendar, "calendar", 1_001, 1_010, false, false))
        assertFalse(filter.accept("unselected.app", "other", 1_001, 1_010, false, false))
        assertTrue(filter.accept("com.whatsapp", "chat", 1_001, 1_010, false, false, 1_001))
        assertTrue(filter.accept("com.whatsapp.w4b", "business", 1_001, 1_010, false, false, 1_001))
        assertFalse(CalendarAlertFilter(1_000, selectedPackages = emptySet())
            .accept("com.whatsapp", "empty", 1_001, 1_010, false, false))
    }

    @Test fun newerMessagesInTheSameChatAreAcceptedButRedeliveryIsNot() {
        val filter = CalendarAlertFilter(1_000, selectedPackages = setOf("com.whatsapp"))
        assertTrue(filter.accept("com.whatsapp", "chat", 1_001, 1_010, false, false, 1_001))
        assertFalse(filter.accept("com.whatsapp", "chat", 2_000, 2_010, false, false, 1_001))
        assertTrue(filter.accept("com.whatsapp", "chat", 3_000, 3_010, false, false, 3_000))
        assertFalse(filter.accept("com.whatsapp", "chat", 4_000, 4_010, false, false, 2_000))
        assertFalse(filter.accept("com.whatsapp", "chat", 4_000, 4_010, false, true, 4_000))
    }

    @Test fun existingChatCanReceiveNewMessagesWithoutReplayingHistory() {
        val filter = CalendarAlertFilter(1_000, mapOf("chat" to 900L), setOf("com.whatsapp"))
        assertFalse(filter.accept("com.whatsapp", "chat", 1_001, 1_010, false, false, 900))
        assertTrue(filter.accept("com.whatsapp", "chat", 2_000, 2_010, false, false, 2_000))
        assertFalse(filter.accept("com.whatsapp", "old-message", 2_000, 2_010, false, false, 900))
    }

    @Test fun knownChatUpdatesStillWorkAlongsideMoreThan256Keys() {
        val filter = CalendarAlertFilter(1_000, selectedPackages = setOf("com.whatsapp"))
        repeat(256) { assertTrue(filter.accept("com.whatsapp", "key-$it", 1_001, 1_010, false, false, 1_001)) }
        assertTrue(filter.accept("com.whatsapp", "overflow", 2_000, 2_010, false, false, 2_000))
        assertTrue(filter.accept("com.whatsapp", "key-0", 2_000, 2_010, false, false, 2_000))
    }

    @Test fun startupSnapshotSuppressesUpdatesBeyond256Keys() {
        val existing = (0 until 512).associate { "key-$it" to null }
        val filter = CalendarAlertFilter(1_000, existing)
        assertFalse(filter.accept(calendar, "key-511", 2_000, 2_010, false, false))
        assertTrue(filter.accept(calendar, "new", 2_000, 2_010, false, false))
    }

    @Test fun removedKeyCanBeReusedForANewNotification() {
        val filter = CalendarAlertFilter(1_000)
        assertTrue(filter.accept(calendar, "reused", 1_001, 1_010, false, false))
        filter.onRemoved("reused")
        assertFalse(filter.accept(calendar, "reused", 1_001, 31_001, false, false))
        assertTrue(filter.accept(calendar, "reused", 31_001, 31_010, false, false))
        assertFalse(filter.accept(calendar, "reused", 31_002, 31_010, false, false))
    }
}
