package dev.airpulse.fitbit_air

import android.util.Log
import com.fitbit.goldengate.bindings.coap.CoapEndpoint
import com.fitbit.goldengate.bindings.coap.data.Method
import com.fitbit.goldengate.bindings.coap.data.OutgoingRequestBuilder
import io.reactivex.Observable
import io.reactivex.Single
import java.util.concurrent.TimeUnit

/**
 * Asks `/settings` which settings exist, one selector at a time.
 *
 * `/settings` refuses GET, so the only way to look at it is the request the
 * haptic read already uses: a PUT whose body is `0a 01 NN`, a selector and
 * nothing else. Reading haptic intensity sends exactly that with selector 1,
 * constantly and without changing anything, so varying only the selector byte
 * is the smallest possible departure from a known-safe call.
 *
 * It carries no value to assign, so there is nothing for the device to store
 * even if it treated the message as a write. That is a reasoned expectation
 * from the shape of a message, not a guarantee about undocumented firmware.
 *
 * Selector 1 is deliberately skipped: it is haptics, it is already understood,
 * and leaving it out keeps the probe away from the one setting known to have a
 * side effect on the device.
 */
internal object AirSettingsProbe {
    /**
     * Every selector that fits the one byte this message carries, minus
     * haptics. A protobuf field number is not capped at a handful, so a short
     * sweep would have proved only that the first few are empty.
     */
    private val selectors = (2..127).toList()

    /** Enough to see a setting's shape; a settings value is not an identifier. */
    private const val MAX_LOGGED_BYTES = 32

    fun probe(endpoint: CoapEndpoint): Single<Map<String, Any>> =
        Observable.fromIterable(selectors).concatMapSingle { selector ->
            val request = OutgoingRequestBuilder("/settings", Method.PUT)
                .expectSuccess(false).forceNonBlockwise(true).maxResendCount(0)
                .body(byteArrayOf(0x0a, 0x01, selector.toByte()))
                .build()
            endpoint.responseFor(request).flatMap { response ->
                response.body.asData().map { body ->
                    val code = "${response.responseCode.responseClass}.${response.responseCode.detail}"
                    val hex = body.take(MAX_LOGGED_BYTES)
                        .joinToString(" ") { "%02x".format(it.toInt() and 255) }
                    // A rejected selector is the expected answer; logging 126
                    // of them would bury the one that is not.
                    if (response.responseCode.responseClass.toInt() != 4) {
                        Log.i("AirBridge", "setting $selector -> $code bytes=${body.size} [$hex]")
                    }
                    mapOf<String, Any>(
                        "selector" to selector,
                        "responseClass" to response.responseCode.responseClass.toInt(),
                        "detail" to response.responseCode.detail.toInt(),
                        "bodyLength" to body.size,
                        "body" to hex,
                    )
                }
            }.timeout(5, TimeUnit.SECONDS).onErrorReturn {
                Log.i("AirBridge", "setting $selector -> no answer")
                mapOf("selector" to selector, "errorCode" to "SETTING_READ_FAILED")
            }
        }.toList().map { results ->
            val answered = results.count { (it["responseClass"] as? Int) == 2 }
            Log.i("AirBridge", "settings sweep: ${results.size} selectors, $answered answered")
            mapOf("settings" to results)
        }

    /** `Settings { <selector> { 1: value } }`, the shape haptics is written in. */
    private fun body(selector: Int, value: Int) = byteArrayOf(
        0x0a, 0x04, ((selector shl 3) or 2).toByte(), 0x02, 0x08, value.toByte(),
    )

    private fun read(endpoint: CoapEndpoint, selector: Int): Single<Int> {
        val request = OutgoingRequestBuilder("/settings", Method.PUT)
            .expectSuccess(false).forceNonBlockwise(true).maxResendCount(0)
            .body(byteArrayOf(0x0a, 0x01, selector.toByte()))
            .build()
        return endpoint.responseFor(request).timeout(5, TimeUnit.SECONDS)
            .flatMap { response ->
                response.body.asData().map { bytes ->
                    check(response.responseCode.responseClass.toInt() == 2 && bytes.size == 6) {
                        "Setting $selector did not read back."
                    }
                    bytes.last().toInt()
                }
            }
    }

    private fun write(endpoint: CoapEndpoint, selector: Int, value: Int): Single<Boolean> {
        val request = OutgoingRequestBuilder("/settings", Method.POST)
            .expectSuccess(false).forceNonBlockwise(true).maxResendCount(0)
            .body(body(selector, value)).build()
        return endpoint.responseFor(request).timeout(5, TimeUnit.SECONDS).map { response ->
            check(response.responseCode.responseClass.toInt() == 2) {
                "The Air refused setting $selector."
            }
            true
        }
    }

    /**
     * Turns setting 2 on, holds it for [HOLD_MILLIS] so any effect can be
     * seen, and puts it back.
     *
     * The setting's meaning is unknown, which is the whole risk: the window is
     * short and the restore runs even when a step fails, so whatever it
     * governs is changed for about two seconds. A lost link between the write
     * and the restore would leave it on; re-running restores it, and Google
     * Health keeps its own copy of these settings.
     */
    fun toggleSecond(endpoint: CoapEndpoint): Single<Map<String, Any>> =
        read(endpoint, SECOND).flatMap { original ->
            val next = if (original == 0) 1 else 0
            Log.i("AirBridge", "setting $SECOND is $original; writing $next")
            write(endpoint, SECOND, next)
                .flatMap { read(endpoint, SECOND) }
                .doOnSuccess { Log.i("AirBridge", "setting $SECOND now $it; holding") }
                .flatMap { applied -> Single.timer(HOLD_MILLIS, TimeUnit.MILLISECONDS).map { applied } }
                // Restore whatever happened above, then report.
                .flatMap { applied ->
                    write(endpoint, SECOND, original).retry(2)
                        .flatMap { read(endpoint, SECOND) }
                        .map { restored -> Triple(original, applied, restored) }
                }
                .onErrorResumeNext { failure: Throwable ->
                    Log.w("AirBridge", "probe failed: ${failure.message}; restoring")
                    write(endpoint, SECOND, original).retry(2)
                        .flatMap { read(endpoint, SECOND) }
                        .map { restored -> Triple(original, -1, restored) }
                }
        }.map { (original, applied, restored) ->
            Log.i("AirBridge", "setting $SECOND restored to $restored (was $original)")
            mapOf(
                "selector" to SECOND,
                "original" to original,
                "applied" to applied,
                "restored" to restored,
                "verified" to (restored == original),
            )
        }

    private const val SECOND = 2
    private const val HOLD_MILLIS = 2_000L
}
