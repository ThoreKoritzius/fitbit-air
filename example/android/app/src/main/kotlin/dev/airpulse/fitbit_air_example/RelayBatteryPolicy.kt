package dev.airpulse.fitbit_air_example

import java.util.Locale

/**
 * Phone makers whose own battery management is known to pause or end
 * battery-optimized apps in the background even while they run a foreground
 * service. Stock Android (Pixel and similar) leaves the relay running, so the
 * battery suggestion is only shown on these.
 */
internal object RelayBatteryPolicy {
    private val aggressive = setOf(
        "oppo", "realme", "oneplus", "xiaomi", "redmi", "poco", "huawei", "honor",
        "vivo", "iqoo", "meizu", "tecno", "infinix", "itel",
    )

    fun pausesOptimizedApps(manufacturer: String?, brand: String?): Boolean =
        listOfNotNull(manufacturer, brand).any { it.trim().lowercase(Locale.ROOT) in aggressive }
}
