package dev.airpulse.fitbit_air

import com.fitbit.goldengate.bindings.coap.CoapEndpoint
import com.fitbit.goldengate.bindings.coap.data.Method
import com.fitbit.goldengate.bindings.coap.data.OutgoingRequestBuilder
import io.reactivex.Observable
import io.reactivex.Single
import java.util.concurrent.TimeUnit

/** Fixed, source-traced GET allowlist. No arbitrary resource/write interface. */
internal object AirReadOnlyResources {
    private val paths = listOf("/md/606", "liveactivity", "/alarms", "/sync/status")

    fun probe(endpoint: CoapEndpoint): Single<Map<String, Any>> =
        Observable.fromIterable(paths).concatMapSingle { path ->
            val request = OutgoingRequestBuilder(path, Method.GET)
                .expectSuccess(false).forceNonBlockwise(true).maxResendCount(0).build()
            endpoint.responseFor(request).flatMap { response ->
                response.body.asData().map { body ->
                    mapOf<String, Any>(
                        "path" to path,
                        "responseClass" to response.responseCode.responseClass.toInt(),
                        "detail" to response.responseCode.detail.toInt(),
                        "bodyLength" to body.size,
                    )
                }
            }.timeout(5, TimeUnit.SECONDS).onErrorReturn {
                // Do not expose native exception text, which can contain payloads.
                mapOf("path" to path, "errorCode" to "RESOURCE_READ_FAILED")
            }
        }.toList().map { results -> mapOf("resources" to results) }
}
