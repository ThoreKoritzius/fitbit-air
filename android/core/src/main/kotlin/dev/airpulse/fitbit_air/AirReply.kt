package dev.airpulse.fitbit_air

/**
 * The answer to one [FitbitAirClient] call: exactly one of [success] or
 * [error], on the main thread. Errors carry a stable code (the same codes the
 * Dart `FitbitAirException` exposes) and a message for people.
 */
internal interface AirReply {
    fun success(value: Any?)
    fun error(code: String, message: String)
}

/**
 * Events from one [FitbitAirClient] stream, on the main thread. A stream ends
 * with [endOfStream], usually right after an [error].
 */
internal interface AirEventSink {
    fun success(event: Any?)
    fun error(code: String, message: String)
    fun endOfStream()
}
