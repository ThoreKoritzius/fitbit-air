package dev.airpulse.fitbit_air

/**
 * `/settings` haptics bodies. Each entry is `Setting { haptics { intensity } }`;
 * intensities are 2 (low) and 3 (high).
 *
 * The Air pulses when the strength becomes different from the last non-off
 * strength. When a second change arrives before the first pulse plays, only
 * the second pulse is felt. [tap] therefore sends [alternate, original] in one
 * message: one pulse at the wearer's own strength, with that strength retained.
 */
internal object AirHapticSettings {
    fun write(intensity: Int): ByteArray = entries(intensity)

    fun tap(original: Int): ByteArray = entries(alternate(original), original)

    fun alternate(intensity: Int): Int {
        require(intensity == 2 || intensity == 3) { "Unsupported haptic intensity." }
        return if (intensity == 2) 3 else 2
    }

    private fun entries(vararg intensities: Int): ByteArray = intensities.flatMap { value ->
        require(value == 2 || value == 3) { "Unsupported haptic intensity." }
        listOf<Byte>(10, 4, 10, 2, 8, value.toByte())
    }.toByteArray()
}
