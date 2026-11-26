package dev.airpulse.fitbit_air

/**
 * Classifies Google Health's use of the shared tracker channel from the Air's
 * notifications alone (timing and sizes; payloads are encrypted).
 *
 * - `syncing`: a transfer burst, at least [syncBytes] within [syncWindowMillis],
 *   held for [syncHoldMillis] after the last burst so gaps between bursts
 *   do not flicker back to `polling`.
 * - `polling`: any traffic within [activeWindowMillis] (Google Health in the
 *   foreground exchanges about 107 bytes per second).
 * - `idle`: no traffic.
 */
internal class AirChannelState(
    private val syncWindowMillis: Long = 1_200,
    private val syncBytes: Int = 400,
    private val activeWindowMillis: Long = 2_500,
    private val syncHoldMillis: Long = 2_000,
) {
    private val packets = ArrayDeque<Pair<Long, Int>>()
    private var lastSyncAt: Long? = null

    fun record(nowMillis: Long, size: Int) {
        packets.addLast(nowMillis to size)
        trim(nowMillis)
    }

    fun state(nowMillis: Long): String {
        trim(nowMillis)
        if (packets.isEmpty()) return "idle"
        val recentBytes = packets.filter { nowMillis - it.first <= syncWindowMillis }.sumOf { it.second }
        if (recentBytes >= syncBytes) lastSyncAt = nowMillis
        val sinceSync = lastSyncAt?.let { nowMillis - it }
        return if (sinceSync != null && sinceSync < syncHoldMillis) "syncing" else "polling"
    }

    private fun trim(nowMillis: Long) {
        while (packets.isNotEmpty() && nowMillis - packets.first().first > activeWindowMillis) {
            packets.removeFirst()
        }
    }
}
