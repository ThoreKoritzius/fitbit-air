package dev.airpulse.fitbit_air

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File

class AirTypesTest {
    @Test fun `failure kinds match the Dart package for every code`() {
        // The Dart table is the reference; parse it so the two cannot drift apart.
        val dart = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "lib/fitbit_air.dart") }.first { it.isFile }.readText()
        val table = Regex("""static AirFailureKind of\(String code\) => switch \(code\) \{(.*?)\n  \};""",
            RegexOption.DOT_MATCHES_ALL).find(dart)!!.groupValues[1]
        val arms = Regex("""((?:\s*'[A-Z_]+'\s*\|\|)*\s*'[A-Z_]+')\s*=>\s*(\w+)""").findAll(table).toList()
        assertEquals(3, arms.size)
        for (arm in arms) {
            val kind = AirFailureKind.valueOf(arm.groupValues[2].uppercase())
            val codes = Regex("'([A-Z_]+)'").findAll(arm.groupValues[1]).map { it.groupValues[1] }.toList()
            assertTrue(codes.isNotEmpty())
            for (code in codes) assertEquals(kind, AirFailureKind.of(code), code)
        }
        assertEquals(AirFailureKind.UNKNOWN, AirFailureKind.of("SOMETHING_NEW"))
    }

    @Test fun `tap results default like Dart when the native side omits fields`() {
        assertEquals(TapResult(HapticIntensity.HIGH, HapticIntensity.HIGH, changed = false, verified = true),
            AirParse.tap(mapOf("intensity" to 3, "verified" to true)))
        assertEquals(TapResult(HapticIntensity.LOW, HapticIntensity.HIGH, changed = true, verified = true),
            AirParse.tap(mapOf("intensity" to 2, "previousIntensity" to 3, "changed" to true, "verified" to true)))
        val error = assertThrows<FitbitAirException> { AirParse.tap(mapOf("intensity" to 5, "verified" to true)) }
        assertEquals("UNSUPPORTED_INTENSITY", error.code)
    }

    @Test fun `device info collects read errors and nested GATT services`() {
        val info = AirParse.deviceInfo(mapOf(
            "name" to "Fitbit Air", "firmwareError" to "status 5",
            "connectionConfiguration" to mapOf("intervalMillis" to 30, "peripheralLatency" to 4,
                "supervisionTimeoutMillis" to 5000, "mtu" to 247, "mode" to "fast"),
            "services" to listOf(mapOf("uuid" to "180d", "characteristics" to listOf(mapOf("uuid" to "2a37", "properties" to 0x10)))),
        ))
        assertNull(info.firmware)
        assertEquals(mapOf("firmwareError" to "status 5"), info.readErrors)
        assertEquals(30.0, info.connectionConfiguration?.intervalMillis)
        assertTrue(info.services.single().characteristics.single().canNotify)
        assertFalse(info.services.single().characteristics.single().canRead)
    }

    @Test fun `finder readings carry a signal only while one is seen`() {
        val searching = AirParse.finder(mapOf("state" to "searching", "receivedAtMillis" to 5L, "rssi" to -60))
        assertFalse(searching.hasSignal)
        assertNull(searching.strength)
        val signal = AirParse.finder(mapOf("state" to "signal", "receivedAtMillis" to 5L, "rssi" to -60, "smoothedRssi" to -70.0))
        assertEquals(0.5, signal.strength!!, 1e-9)
    }

    @Test fun `heart rate, battery, ring and change results parse the native maps`() {
        assertEquals(listOf(812.5, 800.0), AirParse.heartRate(mapOf("beatsPerMinute" to 72, "receivedAtMillis" to 1L,
            "rrIntervalsMillis" to listOf(812.5, 800))).rrIntervalsMillis)
        assertEquals(AirBattery(80, null, 3900), AirParse.battery(mapOf("percent" to 80, "voltageMillivolts" to 3900)))
        assertEquals(AirRingResult(5, true), AirParse.ring(mapOf("pulses" to 5, "restored" to true)))
        assertEquals(HapticChangeResult(HapticIntensity.LOW, HapticIntensity.HIGH, changed = true, verified = false),
            AirParse.hapticChange(mapOf("previousIntensity" to 2, "currentIntensity" to 3, "changed" to true, "verified" to false)))
    }
}
