package dev.airpulse.fitbit_air

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry.RequestPermissionsResultListener

/**
 * Flutter adapter over [FitbitAirClient]: maps the `fitbit_air` channels to
 * the client and asks for Bluetooth permissions through the attached
 * Activity. Everything about the Air lives in the client.
 */
class FitbitAirPlugin : FlutterPlugin, ActivityAware, MethodChannel.MethodCallHandler {
    private lateinit var appContext: Context
    private lateinit var client: FitbitAirClient
    private lateinit var channel: MethodChannel
    private val eventChannels = mutableListOf<EventChannel>()
    private var activity: Activity? = null
    private var activityBinding: ActivityPluginBinding? = null
    private var pendingPermission: AirReply? = null
    private var pendingAction: (() -> Unit)? = null
    private val permissionListener = RequestPermissionsResultListener { requestCode, _, grantResults ->
        if (requestCode != PERMISSION_REQUEST) return@RequestPermissionsResultListener false
        val reply = pendingPermission
        pendingPermission = null
        val action = pendingAction
        pendingAction = null
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) action?.invoke()
        else reply?.error("BLUETOOTH_PERMISSION", "Nearby devices permission is required.")
        true
    }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        appContext = binding.applicationContext
        client = FitbitAirClient(appContext)
        channel = MethodChannel(binding.binaryMessenger, CHANNEL)
        channel.setMethodCallHandler(this)
        fun stream(name: String, listen: (Any?, AirEventSink) -> Unit, cancel: () -> Unit) {
            val events = EventChannel(binding.binaryMessenger, "fitbit_air/$name")
            events.setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, sink: EventChannel.EventSink) = listen(arguments, sink.asAirSink())
                override fun onCancel(arguments: Any?) = cancel()
            })
            eventChannels += events
        }
        // Streams that use a session report BUSY while a permission request is pending.
        stream("heart_rate", { args, sink ->
            val deviceId = (args as? Map<*, *>)?.get("deviceId") as? String
            if (pendingPermission != null) sink.busy()
            else withPermission(sink.asReply()) { client.listenHeartRate(deviceId, sink) }
        }, { client.cancelHeartRate() })
        stream("secure_events", { args, sink ->
            val seconds = (args as? Map<*, *>)?.get("durationSeconds")
            if (pendingPermission != null) sink.busy()
            else withPermission(sink.asReply()) { client.listenSecureEvents(seconds, sink) }
        }, { client.cancelSecureEvents() })
        stream("finder", { _, sink ->
            client.cancelFinder()
            withPermission(sink.asReply()) { client.listenFinder(sink) }
        }, { client.cancelFinder() })
        stream("connection", { _, sink -> withPermission(sink.asReply()) { client.listenConnection(sink) } },
            { client.cancelConnection() })
        stream("channel_activity", { _, sink -> withPermission(sink.asReply()) { client.listenChannelActivity(sink) } },
            { client.cancelChannelActivity() })
        stream("device_events", { _, sink ->
            if (pendingPermission != null) sink.busy()
            else withPermission(sink.asReply()) { client.listenDeviceEvents(sink) }
        }, { client.cancelDeviceEvents() })
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        val reply = result.asReply()
        when (call.method) {
            "transportDiagnostics" -> result.success(client.transportDiagnostics())
            "cancelOperation" -> client.cancelOperation(reply)
            "stopRing" -> { client.stopRing(); result.success(null) }
            "keepLinkWarm" -> {
                val enabled = call.argument<Boolean>("enabled") == true
                if (!enabled) client.keepLinkWarm(false, reply)
                else withPermission(reply) { client.keepLinkWarm(true, reply) }
            }
            "scanHeartRateDevices" -> withPermission(reply, needsScan = true) { client.scanHeartRateDevices(reply) }
            "readHeartRate" -> withPermission(reply) { client.readHeartRate(call.argument<String>("deviceId"), reply) }
            "readDeviceInfo" -> withPermission(reply) { client.readDeviceInfo(reply) }
            "isAirPaired" -> withPermission(reply) { client.isAirPaired(reply) }
            "isAirConnected" -> withPermission(reply) { client.isAirConnected(reply) }
            "vibrate" -> withPermission(reply) { client.vibrate(reply) }
            "getHapticIntensity" -> withPermission(reply) { client.getHapticIntensity(reply) }
            "readBattery" -> withPermission(reply) { client.readBattery(reply) }
            "readSignalStrength" -> withPermission(reply) { client.readSignalStrength(reply) }
            "probeBatteryAccess" -> withPermission(reply) { client.probeBatteryAccess(reply) }
            "probeReadOnlyResources" -> withPermission(reply) { client.probeReadOnlyResources(reply) }
            "pulse" -> withPermission(reply) { client.pulse(reply) }
            // Invalid arguments are reported before any permission request.
            "setHapticIntensity" -> {
                val intensity = call.argument<Int>("intensity")
                if (intensity !in setOf(2, 3)) client.setHapticIntensity(intensity, reply)
                else withPermission(reply) { client.setHapticIntensity(intensity, reply) }
            }
            "tap" -> {
                val intensity = call.argument<Int>("intensity")
                if (intensity != null && intensity !in setOf(2, 3)) client.tap(intensity, reply)
                else withPermission(reply) { client.tap(intensity, reply) }
            }
            "ring" -> {
                val intensity = call.argument<Int>("intensity")
                val gapMillis = (call.argument<Int>("gapMillis") ?: 700).toLong()
                val maxMillis = (call.argument<Int>("maxMillis") ?: 45_000).toLong()
                val pulses = call.argument<Int>("pulses")
                val valid = (pulses == null || pulses in 1..100) && (intensity == null || intensity in setOf(2, 3)) &&
                    gapMillis in FitbitAirClient.MIN_RING_GAP_MS..5_000L && maxMillis in 1_000L..300_000L
                if (!valid) client.ring(intensity, gapMillis, maxMillis, pulses, reply)
                else withPermission(reply) { client.ring(intensity, gapMillis, maxMillis, pulses, reply) }
            }
            else -> result.notImplemented()
        }
    }

    /** Runs [action] once the Bluetooth permissions are granted, asking through the Activity if needed. */
    private fun withPermission(reply: AirReply, needsScan: Boolean = false, action: () -> Unit) {
        if (FitbitAirClient.hasPermissions(appContext, needsScan)) {
            action()
            return
        }
        val host = activity
        if (host == null) {
            reply.error("NO_ACTIVITY", "Attach the Flutter view before calling Fitbit Air.")
            return
        }
        if (pendingPermission != null) {
            reply.error("BUSY", "A Bluetooth permission request is already pending.")
            return
        }
        pendingPermission = reply
        pendingAction = action
        host.requestPermissions(FitbitAirClient.requiredPermissions(needsScan), PERMISSION_REQUEST)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        eventChannels.forEach { it.setStreamHandler(null) }
        eventChannels.clear()
        client.close()
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
        activityBinding = binding
        binding.addRequestPermissionsResultListener(permissionListener)
    }

    override fun onDetachedFromActivityForConfigChanges() = detachActivity()
    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) = onAttachedToActivity(binding)
    override fun onDetachedFromActivity() = detachActivity()

    private fun detachActivity() {
        activityBinding?.removeRequestPermissionsResultListener(permissionListener)
        activityBinding = null
        activity = null
    }

    private companion object {
        const val CHANNEL = "fitbit_air"
        const val PERMISSION_REQUEST = 49217
    }
}

private fun MethodChannel.Result.asReply() = object : AirReply {
    override fun success(value: Any?) = this@asReply.success(value)
    override fun error(code: String, message: String) = this@asReply.error(code, message, null)
}

private fun EventChannel.EventSink.asAirSink() = object : AirEventSink {
    override fun success(event: Any?) = this@asAirSink.success(event)
    override fun error(code: String, message: String) = this@asAirSink.error(code, message, null)
    override fun endOfStream() = this@asAirSink.endOfStream()
}

private fun AirEventSink.asReply() = object : AirReply {
    override fun success(value: Any?) { }
    override fun error(code: String, message: String) {
        this@asReply.error(code, message)
        endOfStream()
    }
}

private fun AirEventSink.busy() {
    error("BUSY", "Another Bluetooth operation is running.")
    endOfStream()
}
