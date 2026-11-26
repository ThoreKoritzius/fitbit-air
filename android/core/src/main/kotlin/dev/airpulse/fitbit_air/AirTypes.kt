package dev.airpulse.fitbit_air

/**
 * Typed results of [FitbitAir], the same as the Dart package's classes in
 * `lib/fitbit_air.dart`, parsed from the same native maps.
 */

/** What a [FitbitAirException] means for the user. */
enum class AirFailureKind {
    /** Worth retrying later: the Air was busy, out of range, or Google Health was using the shared channel. */
    TEMPORARY,

    /** The user needs to fix something: Bluetooth, a permission or pairing. */
    SETUP,

    /**
     * The Air or phone answered in a way this version does not expect, for
     * example after a firmware update. When it repeats, suggest an app update.
     */
    COMPATIBILITY,

    /** Not classified. */
    UNKNOWN;

    companion object {
        /** Classifies an error [code]; kept identical to Dart's `AirFailureKind.of` (tested). */
        @JvmStatic
        fun of(code: String): AirFailureKind = when (code) {
            "BUSY", "TIMEOUT", "CANCELLED", "HANDSHAKE_TIMEOUT", "AIR_NO_REPLY", "HAPTIC_UNCONFIRMED",
            "GATT_CONNECT", "GATT_DISCONNECTED", "CHANNEL_CONNECT", "CHANNEL_DISCONNECTED", "CHANNEL_TIMEOUT",
            "HEART_RATE_TIMEOUT", "SCAN_FAILED", "RSSI" -> TEMPORARY
            "AIR_NOT_FOUND", "BLUETOOTH_OFF", "BLUETOOTH_PERMISSION", "HEART_RATE_UNAVAILABLE" -> SETUP
            "NATIVE_UNAVAILABLE", "GOLDENGATE", "GOLDENGATE_TX", "DTLS", "TRACKER_CHANNEL", "CHANNEL_UNAVAILABLE",
            "HAPTIC_READ_FAILED", "HAPTIC_WRITE_FAILED", "HAPTIC_TAP_FAILED", "RING_FAILED", "HAPTIC_PULSE_FAILED",
            "HAPTIC_FAILED", "BATTERY_READ_FAILED", "HEART_RATE_FORMAT" -> COMPATIBILITY
            else -> UNKNOWN
        }
    }
}

/** A device or transport error, with a stable [code] and a [message] for people. */
class FitbitAirException(val code: String, message: String) : Exception(message) {
    /** What this error means for the user; see [AirFailureKind]. */
    val kind: AirFailureKind get() = AirFailureKind.of(code)

    override fun toString(): String = "FitbitAirException($code): $message"
}

/** Persistent haptic strength supported by the tested Air firmware. */
enum class HapticIntensity(val wireValue: Int) {
    LOW(2),
    HIGH(3);

    companion object {
        @JvmStatic
        fun fromWireValue(value: Int): HapticIntensity = entries.firstOrNull { it.wireValue == value }
            ?: throw FitbitAirException("UNSUPPORTED_INTENSITY", "Air returned unsupported haptic intensity $value.")
    }
}

/** Result of a persistent haptic change or a one-pulse toggle. */
data class HapticChangeResult(
    val previousIntensity: HapticIntensity,
    val currentIntensity: HapticIntensity,
    val changed: Boolean,
    val verified: Boolean,
)

/** Result of [FitbitAir.tap]: one verified pulse. */
data class TapResult(
    /** The strength the pulse used, which is now active on the Air. */
    val intensity: HapticIntensity,
    /** The strength before the tap. */
    val previousIntensity: HapticIntensity,
    /** True when a different strength was requested and is now kept. */
    val changed: Boolean,
    val verified: Boolean,
)

/** The outcome of [FitbitAir.ring]. */
data class AirRingResult(
    /** Pulses the Air accepted. Dropped pulses are skipped, not retried. */
    val pulses: Int,
    /** Whether a follow-up read confirmed the wearer's own strength is back. */
    val restored: Boolean,
)

/** Result of [FitbitAir.vibrate], a direct haptic test. */
data class VibrationResult(
    /** The prior intensity: 2 (low) or 3 (high), as reported by the Air. */
    val originalIntensity: Int,
    /** Whether the Air accepted restoring that setting. */
    val restored: Boolean,
    /** Whether a follow-up read confirmed that the original setting was restored. */
    val verified: Boolean,
)

/** Battery state reported by the Air. */
data class AirBattery(
    /** 0–100; matches the percentage shown in Google Health. */
    val percent: Int,
    /** Whether the Air is on its charger, when it reports it. */
    val onCharger: Boolean? = null,
    val voltageMillivolts: Int? = null,
)

/** A heart-rate sensor found by [FitbitAir.scanHeartRateDevices]. */
data class AirHeartRateDevice(
    /** Device address returned by Android; treat it as private. */
    val deviceId: String,
    val name: String,
    val rssi: Int,
)

/** Standard Bluetooth heart-rate measurement. Optional fields depend on the device. */
data class AirHeartRate(
    val beatsPerMinute: Int,
    val receivedAtMillis: Long,
    /** Null when the sensor does not report contact status. */
    val contactDetected: Boolean?,
    val energyExpendedKj: Int?,
    val rrIntervalsMillis: List<Double>,
)

/** Information actually exposed by the connected Air's GATT services. */
data class AirDeviceInfo(
    val name: String,
    val batteryPercent: Int?,
    val model: String?,
    val firmware: String?,
    val hardware: String?,
    val manufacturer: String?,
    val connectionStatus: AirConnectionStatus?,
    val connectionConfiguration: AirConnectionConfiguration?,
    val services: List<AirGattService>,
    /** Reads that failed, by field (`<field>Error`). */
    val readErrors: Map<String, String>,
)

data class AirConnectionStatus(
    val bonded: Boolean,
    val encrypted: Boolean,
    val dataLengthExtension: Boolean,
    val maxTxPayload: Int,
    val maxRxPayload: Int,
)

data class AirConnectionConfiguration(
    val intervalMillis: Double,
    val peripheralLatency: Int,
    val supervisionTimeoutMillis: Int,
    val mtu: Int,
    val mode: String,
)

data class AirGattService(val uuid: String, val characteristics: List<AirGattCharacteristic>)

data class AirGattCharacteristic(val uuid: String, val properties: Int) {
    val canRead: Boolean get() = properties and 0x02 != 0
    val canNotify: Boolean get() = properties and 0x10 != 0
    val canIndicate: Boolean get() = properties and 0x20 != 0
}

/** A change in the Air's Bluetooth link state. */
data class AirConnectionEvent(
    val connected: Boolean,
    /** When the phone observed the change, not when the link changed. */
    val receivedAtMillis: Long,
)

/** A live finder update. Searching updates contain no old signal. */
data class AirFinderReading(
    val receivedAtMillis: Long,
    val rssi: Int?,
    val smoothedRssi: Double?,
) {
    val hasSignal: Boolean get() = rssi != null && smoothedRssi != null

    /** Normalized signal strength for a UI, not a probability or distance. */
    val strength: Double? get() = smoothedRssi?.let { ((it + 100) / 60).coerceIn(0.0, 1.0) }
}

/** Parses the client's native maps, exactly as the Dart `fromMap` constructors do. */
internal object AirParse {
    fun hapticChange(m: Map<*, *>) = HapticChangeResult(
        previousIntensity = HapticIntensity.fromWireValue(m.int("previousIntensity")),
        currentIntensity = HapticIntensity.fromWireValue(m.int("currentIntensity")),
        changed = m.bool("changed"),
        verified = m.bool("verified"),
    )

    fun tap(m: Map<*, *>): TapResult {
        val intensity = HapticIntensity.fromWireValue(m.int("intensity"))
        return TapResult(
            intensity = intensity,
            previousIntensity = (m["previousIntensity"] as? Number)?.let { HapticIntensity.fromWireValue(it.toInt()) } ?: intensity,
            changed = m["changed"] as? Boolean ?: false,
            verified = m.bool("verified"),
        )
    }

    fun ring(m: Map<*, *>) = AirRingResult(pulses = m.int("pulses"), restored = m.bool("restored"))

    fun vibration(m: Map<*, *>) = VibrationResult(
        originalIntensity = m.int("originalIntensity"),
        restored = m.bool("restored"),
        verified = m.bool("verified"),
    )

    fun battery(m: Map<*, *>) = AirBattery(
        percent = m.int("percent"),
        onCharger = m["onCharger"] as Boolean?,
        voltageMillivolts = (m["voltageMillivolts"] as Number?)?.toInt(),
    )

    fun heartRateDevices(m: Map<*, *>): List<AirHeartRateDevice> = m.list("devices").map {
        val d = it as Map<*, *>
        AirHeartRateDevice(deviceId = d.string("deviceId"), name = d.string("name"), rssi = d.int("rssi"))
    }

    fun heartRate(m: Map<*, *>) = AirHeartRate(
        beatsPerMinute = m.int("beatsPerMinute"),
        receivedAtMillis = m.long("receivedAtMillis"),
        contactDetected = m["contactDetected"] as Boolean?,
        energyExpendedKj = (m["energyExpendedKj"] as Number?)?.toInt(),
        rrIntervalsMillis = m.list("rrIntervalsMillis").map { (it as Number).toDouble() },
    )

    fun deviceInfo(m: Map<*, *>) = AirDeviceInfo(
        name = m.string("name"),
        batteryPercent = (m["batteryPercent"] as Number?)?.toInt(),
        model = m["model"] as String?,
        firmware = m["firmware"] as String?,
        hardware = m["hardware"] as String?,
        manufacturer = m["manufacturer"] as String?,
        connectionStatus = (m["connectionStatus"] as Map<*, *>?)?.let {
            AirConnectionStatus(it.bool("bonded"), it.bool("encrypted"), it.bool("dataLengthExtension"),
                it.int("maxTxPayload"), it.int("maxRxPayload"))
        },
        connectionConfiguration = (m["connectionConfiguration"] as Map<*, *>?)?.let {
            AirConnectionConfiguration((it["intervalMillis"] as Number).toDouble(), it.int("peripheralLatency"),
                it.int("supervisionTimeoutMillis"), it.int("mtu"), it.string("mode"))
        },
        services = m.list("services").map { s ->
            val service = s as Map<*, *>
            AirGattService(service.string("uuid"), service.list("characteristics").map { c ->
                val characteristic = c as Map<*, *>
                AirGattCharacteristic(characteristic.string("uuid"), characteristic.int("properties"))
            })
        },
        readErrors = m.entries.filter { (it.key as String).endsWith("Error") }
            .associate { it.key as String to it.value.toString() },
    )

    fun connection(m: Map<*, *>) = AirConnectionEvent(connected = m.bool("connected"), receivedAtMillis = m.long("receivedAtMillis"))

    fun finder(m: Map<*, *>): AirFinderReading {
        val signal = m["state"] == "signal"
        return AirFinderReading(
            receivedAtMillis = m.long("receivedAtMillis"),
            rssi = if (signal) (m["rssi"] as Number?)?.toInt() else null,
            smoothedRssi = if (signal) (m["smoothedRssi"] as Number?)?.toDouble() else null,
        )
    }

    private fun Map<*, *>.int(key: String): Int = (this[key] as Number).toInt()
    private fun Map<*, *>.long(key: String): Long = (this[key] as Number).toLong()
    private fun Map<*, *>.bool(key: String): Boolean = this[key] as Boolean
    private fun Map<*, *>.string(key: String): String = this[key] as String
    private fun Map<*, *>.list(key: String): List<*> = this[key] as List<*>
}
