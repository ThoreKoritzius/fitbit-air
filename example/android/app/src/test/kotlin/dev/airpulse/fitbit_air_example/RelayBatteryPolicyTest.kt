package dev.airpulse.fitbit_air_example

import org.junit.Assert.*
import org.junit.Test

class RelayBatteryPolicyTest {
    @Test fun `phones that pause optimized apps are recognised by maker or brand`() {
        assertTrue(RelayBatteryPolicy.pausesOptimizedApps("OPPO", "OPPO"))
        assertTrue(RelayBatteryPolicy.pausesOptimizedApps("Xiaomi", "Redmi"))
        assertTrue(RelayBatteryPolicy.pausesOptimizedApps("OnePlus", null))
        assertTrue(RelayBatteryPolicy.pausesOptimizedApps("unknown", "realme"))
    }

    @Test fun `stock Android phones are left alone`() {
        assertFalse(RelayBatteryPolicy.pausesOptimizedApps("Google", "google"))
        assertFalse(RelayBatteryPolicy.pausesOptimizedApps("samsung", "samsung"))
        assertFalse(RelayBatteryPolicy.pausesOptimizedApps("motorola", "motorola"))
        assertFalse(RelayBatteryPolicy.pausesOptimizedApps(null, null))
    }
}
