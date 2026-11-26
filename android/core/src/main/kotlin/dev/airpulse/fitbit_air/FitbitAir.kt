package dev.airpulse.fitbit_air

import android.content.Context
import android.os.Handler
import android.os.Looper

/** A running stream from [FitbitAir]; [cancel] stops it. Cancelling twice, or after it ended, does nothing. */
fun interface AirSubscription {
    fun cancel()
}

/**
 * The Fitbit Air for Android apps, without Flutter: the same operations and
 * results as the Dart `FitbitAir` class.
 *
 * - The Air must be paired in Google Health on the same phone.
 * - Call on the main thread. Callbacks and stream events run there, always
 *   after the call has returned (never inside it), exactly once per call.
 * - One operation at a time; others fail with `BUSY`. For notifications, put
 *   an [AirHapticDispatcher] in front, which queues, dedupes and expires.
 * - The library never asks for permissions: request [requiredPermissions]
 *   first, or calls fail with `BLUETOOTH_PERMISSION`.
 * - A success means the Air accepted and the read-back matched. It is not
 *   proof that the wearer felt a vibration.
 *
 * Call [close] when done; it stops streams and the warm link.
 */
class FitbitAir(context: Context) {
    internal val client = FitbitAirClient(context)
    private val main = Handler(Looper.getMainLooper())

    // Commands

    /** Whether a Fitbit Air is bonded to this phone (paired in Google Health). */
    fun isAirPaired(callback: (Result<Boolean>) -> Unit) = client.isAirPaired(reply(callback) { it as Boolean })

    /** Whether the paired Air is connected to this phone right now. */
    fun isAirConnected(callback: (Result<Boolean>) -> Unit) = client.isAirConnected(reply(callback) { it as Boolean })

    fun readDeviceInfo(callback: (Result<AirDeviceInfo>) -> Unit) = client.readDeviceInfo(map(callback, AirParse::deviceInfo))

    fun readBattery(callback: (Result<AirBattery>) -> Unit) = client.readBattery(map(callback, AirParse::battery))

    /** The Air's signal strength (RSSI, dBm). */
    fun readSignalStrength(callback: (Result<Int>) -> Unit) =
        client.readSignalStrength(map(callback) { (it["rssi"] as Number).toInt() })

    /** One standard heart-rate measurement, from the Air or from the sensor [deviceId]. */
    fun readHeartRate(deviceId: String? = null, callback: (Result<AirHeartRate>) -> Unit) =
        client.readHeartRate(deviceId, map(callback, AirParse::heartRate))

    /** Heart-rate sensors nearby, scanned for 8 s. Needs [requiredPermissions] with `needsScan`. */
    fun scanHeartRateDevices(callback: (Result<List<AirHeartRateDevice>>) -> Unit) =
        client.scanHeartRateDevices(map(callback, AirParse::heartRateDevices))

    fun getHapticIntensity(callback: (Result<HapticIntensity>) -> Unit) =
        client.getHapticIntensity(map(callback) { HapticIntensity.fromWireValue((it["intensity"] as Number).toInt()) })

    /** Changes the Air's persistent strength, which also sets its alarm strength. */
    fun setHapticIntensity(intensity: HapticIntensity, callback: (Result<HapticChangeResult>) -> Unit) =
        client.setHapticIntensity(intensity.wireValue, map(callback, AirParse::hapticChange))

    /**
     * One pulse, verified by reading the strength back. With [intensity]
     * null the wearer's own strength is kept; otherwise the Air keeps the
     * new strength.
     */
    fun tap(intensity: HapticIntensity? = null, callback: (Result<TapResult>) -> Unit) =
        client.tap(intensity?.wireValue, map(callback, AirParse::tap))

    /**
     * Pulses about every [gapMillis] inside one secure session, until
     * [stopRing], [cancelOperation], [maxMillis], [pulses], or three
     * unanswered pulses in a row (`HAPTIC_UNCONFIRMED`). The wearer's strength
     * is restored wherever a link remains.
     */
    fun ring(
        intensity: HapticIntensity? = null,
        gapMillis: Long = 700,
        maxMillis: Long = 45_000,
        pulses: Int? = null,
        callback: (Result<AirRingResult>) -> Unit,
    ) = client.ring(intensity?.wireValue, gapMillis, maxMillis, pulses, map(callback, AirParse::ring))

    /** Stops the running ring; it then restores and its callback reports. */
    fun stopRing() = client.stopRing()

    /** One pulse by switching to the other strength, which is kept. */
    fun pulse(callback: (Result<HapticChangeResult>) -> Unit) = client.pulse(map(callback, AirParse::hapticChange))

    /** A haptic test: switches strength and back, then verifies the original is restored. */
    fun vibrate(callback: (Result<VibrationResult>) -> Unit) = client.vibrate(map(callback, AirParse::vibration))

    /**
     * Keeps a listen-only link to the Air between commands, so commands skip
     * connection setup (about 0.5 s instead of several). Google Health keeps
     * syncing. Off by default.
     */
    fun keepLinkWarm(enabled: Boolean, callback: (Result<Unit>) -> Unit = {}) =
        client.keepLinkWarm(enabled, reply(callback) { })

    /**
     * Cancels the running operation. A ring stops and restores; a haptic
     * write being verified cannot be cancelled (`CANNOT_CANCEL_WRITE`).
     */
    fun cancelOperation(callback: (Result<Unit>) -> Unit = {}) = client.cancelOperation(reply(callback) { })

    /** Recent transport cleanups and link recoveries, for bug reports. Contains no identifiers. */
    fun transportDiagnostics(): Map<String, Any> = client.transportDiagnostics()

    // Streams: one listener per stream; a new watch replaces the previous one.

    /** Heart-rate measurements until cancelled. The stream ends after [onError]. */
    fun watchHeartRate(
        deviceId: String? = null,
        onError: (FitbitAirException) -> Unit = {},
        onReading: (AirHeartRate) -> Unit,
    ): AirSubscription = watch(Stream.HEART_RATE, AirParse::heartRate, onReading, onError,
        start = { client.listenHeartRate(deviceId, it) }, stop = client::cancelHeartRate)

    /** The Air's connection state, now and on every change, until cancelled. */
    fun watchConnection(
        onError: (FitbitAirException) -> Unit = {},
        onEvent: (AirConnectionEvent) -> Unit,
    ): AirSubscription = watch(Stream.CONNECTION, AirParse::connection, onEvent, onError,
        start = client::listenConnection, stop = client::cancelConnection)

    /** Signal readings for finding a misplaced Air, until cancelled. */
    fun watchProximity(
        onError: (FitbitAirException) -> Unit = {},
        onReading: (AirFinderReading) -> Unit,
    ): AirSubscription = watch(Stream.FINDER, AirParse::finder, onReading, onError,
        start = client::listenFinder, stop = client::cancelFinder)

    /** Stops every stream and operation, the warm link and the transport. */
    fun close() {
        current.clear()
        client.close()
    }

    private enum class Stream { HEART_RATE, CONNECTION, FINDER }

    /** The live sink per stream, so cancelling an old subscription leaves a newer one running. */
    private val current = mutableMapOf<Stream, AirEventSink>()

    private fun <T> watch(
        stream: Stream,
        parse: (Map<*, *>) -> T,
        onValue: (T) -> Unit,
        onError: (FitbitAirException) -> Unit,
        start: (AirEventSink) -> Unit,
        stop: () -> Unit,
    ): AirSubscription {
        // Events are posted, so none arrives before this returns the subscription,
        // and none after it was cancelled.
        val sink = object : AirEventSink {
            var ended = false
            private fun live() = !ended && current[stream] === this
            override fun success(event: Any?) = main.post {
                if (!live()) return@post
                // Like Dart: an event that cannot be read is reported; the stream goes on.
                runCatching { parse(event as Map<*, *>) }.fold(onValue) {
                    onError(FitbitAirException("INVALID_RESPONSE", "The Air sent an unexpected event."))
                }
            }.let { }
            override fun error(code: String, message: String) = main.post {
                if (live()) onError(FitbitAirException(code, message))
            }.let { }
            override fun endOfStream() = main.post {
                if (current[stream] === this) current.remove(stream)
                ended = true
            }.let { }
        }
        current[stream] = sink
        start(sink)
        return AirSubscription {
            if (current[stream] === sink) {
                current.remove(stream)
                stop()
            }
            sink.ended = true
        }
    }

    companion object {
        /** The runtime permissions to hold before calling; [needsScan] for [scanHeartRateDevices]. */
        @JvmStatic
        @JvmOverloads
        fun requiredPermissions(needsScan: Boolean = false): Array<String> = FitbitAirClient.requiredPermissions(needsScan)

        @JvmStatic
        @JvmOverloads
        fun hasPermissions(context: Context, needsScan: Boolean = false): Boolean =
            FitbitAirClient.hasPermissions(context, needsScan)

        /** Firmware versions this library was tested with. */
        @JvmField
        val testedFirmware: Set<String> = setOf("67.20001.253.2")

    }

    private fun <T> reply(callback: (Result<T>) -> Unit, convert: (Any?) -> T) = object : AirReply {
        override fun success(value: Any?) {
            val result = runCatching { convert(value) }.recoverCatching { error ->
                throw error as? FitbitAirException ?: FitbitAirException("INVALID_RESPONSE", "The Air sent an unexpected result.")
            }
            main.post { callback(result) }
        }
        override fun error(code: String, message: String) {
            main.post { callback(Result.failure(FitbitAirException(code, message))) }
        }
    }

    private fun <T> map(callback: (Result<T>) -> Unit, convert: (Map<*, *>) -> T) = reply(callback) { value ->
        convert(value as? Map<*, *> ?: throw FitbitAirException("EMPTY_RESULT", "No result returned."))
    }
}
