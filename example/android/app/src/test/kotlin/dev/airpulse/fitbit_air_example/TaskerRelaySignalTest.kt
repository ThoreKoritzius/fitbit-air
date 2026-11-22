package dev.airpulse.fitbit_air_example

import org.junit.Assert.*
import org.junit.Test

class TaskerRelaySignalTest {
    @Test fun recognizesOnlyTheReservedTitle() {
        assertTrue(TaskerRelaySignal.isTriggerTitle("Air Relay trigger"))
        assertFalse(TaskerRelaySignal.isTriggerTitle("Air Relay trigger 2"))
        assertFalse(TaskerRelaySignal.isTriggerTitle(null))
    }

    @Test fun onlyAcceptsFreshTimestamps() {
        val postedAt = 100_000L
        assertEquals(99_999L, TaskerRelaySignal.timestamp("99999", postedAt))
        for (text in listOf(null, "%TIMEMS", "hello", "0", "70000", "100001")) {
            assertNull(TaskerRelaySignal.timestamp(text, postedAt))
        }
    }

    @Test fun repeatedTaskerNotificationKeyAcceptsOnlyNewSignals() {
        val tasker = TaskerRelaySignal.PACKAGE
        val filter = CalendarAlertFilter(100_000, mapOf("tasker-key" to null), setOf(tasker))
        fun deliver(timestamp: Long, now: Long, ongoing: Boolean = false): Boolean {
            val parsed = TaskerRelaySignal.timestamp(timestamp.toString(), now) ?: return false
            return filter.accept(tasker, "tasker-key", now, now, ongoing, false, parsed)
        }
        assertFalse(deliver(99_999, 100_001))
        assertTrue(deliver(100_001, 100_002))
        assertFalse(deliver(100_001, 100_003))
        assertFalse(deliver(100_000, 100_004))
        assertFalse(deliver(100_004, 100_005, ongoing = true))
        assertTrue(deliver(100_005, 100_006))
        assertFalse(CalendarAlertFilter(100_000, selectedPackages = emptySet())
            .accept(tasker, "tasker-key", 100_005, 100_005, false, false, 100_005))
    }
}
