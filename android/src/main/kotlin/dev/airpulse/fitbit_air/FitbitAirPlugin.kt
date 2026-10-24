package dev.airpulse.fitbit_air

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.ParcelUuid
import com.fitbit.goldengate.bindings.coap.CoapEndpoint
import com.fitbit.goldengate.bindings.coap.data.Method
import com.fitbit.goldengate.bindings.coap.data.OutgoingRequestBuilder
import com.fitbit.goldengate.bindings.dtls.DtlsProtocolStatus.TlsProtocolState.TLS_STATE_ERROR
import com.fitbit.goldengate.bindings.dtls.DtlsProtocolStatus.TlsProtocolState.TLS_STATE_SESSION
import com.fitbit.goldengate.bindings.dtls.TlsKeyResolver
import com.fitbit.goldengate.bindings.io.RxSource
import com.fitbit.goldengate.bindings.io.TxSink
import com.fitbit.goldengate.bindings.node.BluetoothAddressNodeKey
import com.fitbit.goldengate.bindings.node.NodeKey
import com.fitbit.goldengate.bindings.stack.DtlsSocketNetifGattlink
import com.fitbit.goldengate.bindings.stack.Stack
import com.fitbit.goldengate.bindings.stack.StackEvent
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.PluginRegistry.RequestPermissionsResultListener
import io.reactivex.Single
import io.reactivex.disposables.CompositeDisposable
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Flutter bridge for the experimentally verified Fitbit Air haptic path. */
class FitbitAirPlugin : FlutterPlugin, ActivityAware, MethodChannel.MethodCallHandler {
    private lateinit var appContext: Context
    private lateinit var channel: MethodChannel
    private lateinit var heartRateChannel: EventChannel
    private lateinit var deviceEventChannel: EventChannel
    private lateinit var secureEventChannel: EventChannel
    private var secureEvents: EventChannel.EventSink? = null
    private var secureCapture: AirSecureEventCapture? = null
    private var secureCaptureSeconds = 30
    private var diagnosticEvents: EventChannel.EventSink? = null
    private var eventMonitor: AirEventMonitor? = null
    private lateinit var connectionChannel: EventChannel
    private var connectionWatcher: AirConnectionWatcher? = null
    private lateinit var channelActivityChannel: EventChannel
    private var channelMonitor: AirChannelMonitor? = null
    private var warmLink: AirWarmLink? = null
    private var usingWarmLink = false
    /** The host asked to keep a warm link; rebuilt after it fails or Bluetooth restarts. */
    private var warmLinkWanted = false
    private var warmLinkWatchdog: Runnable? = null
    private var adapterReceiver: BroadcastReceiver? = null
    private var lastWarmLinkRebuild = 0L
    private var operationCallback: BluetoothGattCallback? = null
    private var sessionRxPackets = 0
    private var warmSilenceCheck: Runnable? = null
    private var warmLinkFallback: String? = null
    private var heartRateEvents: EventChannel.EventSink? = null
    private var heartRateStreamGeneration = 0
    private var activity: Activity? = null
    private var activityBinding: ActivityPluginBinding? = null
    private var pendingPermissionResult: MethodChannel.Result? = null
    private var pendingAction: (() -> Unit)? = null
    private val permissionListener = RequestPermissionsResultListener { requestCode, _, grantResults ->
        if (requestCode != PERMISSION_REQUEST) return@RequestPermissionsResultListener false
        val result = pendingPermissionResult
        pendingPermissionResult = null
        val action = pendingAction
        pendingAction = null
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) action?.invoke()
        else result?.error("BLUETOOTH_PERMISSION", "Nearby devices permission is required.", null)
        true
    }
    private var pendingResult: MethodChannel.Result? = null
    private val transportOwner = Any()
    private val handler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var txSink: TxSink? = null
    private var rxSource: RxSource? = null
    private var stack: Stack? = null
    private var endpoint: CoapEndpoint? = null
    private val gattCallbacks = AirSessionCallbacks({ gatt }) { action -> handler.post { action() } }
    private val sessionCallbacks = AirSessionCallbacks({ endpoint }) { action -> handler.post { action() } }
    private val disposables = CompositeDisposable()
    private val txQueue = ArrayDeque<ByteArray>()
    private var writeInFlight = false
    private var sessionStarted = false
    private var timeout: Runnable? = null
    private var activeOperation: Operation? = null
    private var cleanupOperation: String? = null
    private val infoReads = ArrayDeque<BluetoothGattCharacteristic>()
    private val deviceInfo = mutableMapOf<String, Any>()
    private var heartRateSubscribed = false
    private var scanCallback: ScanCallback? = null
    private var handoff: Handoff? = null
    private var handoffOutcome: String? = null
    private var linkBoosted = false
    private var handshakeAttempts = 0
    private var handshakeDeadline: Runnable? = null
    private var channelActivity: AirChannelActivity? = null
    private var holdTick: Runnable? = null
    private var channelHoldOutcome: String? = null

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        appContext = binding.applicationContext
        channel = MethodChannel(binding.binaryMessenger, CHANNEL)
        channel.setMethodCallHandler(this)
        heartRateChannel = EventChannel(binding.binaryMessenger, "fitbit_air/heart_rate")
        heartRateChannel.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
                val address = (arguments as? Map<*, *>)?.get("deviceId") as? String
                if (address != null && !BluetoothAdapter.checkBluetoothAddress(address)) {
                    events.error("INVALID_DEVICE", "Select a discovered heart-rate sensor first.", null)
                    events.endOfStream()
                    return
                }
                if (pendingResult != null || pendingPermissionResult != null || diagnosticEvents != null) {
                    events.error("BUSY", "Another Bluetooth operation is running.", null)
                    events.endOfStream()
                    return
                }
                heartRateEvents = events
                val generation = ++heartRateStreamGeneration
                val streamResult = object : MethodChannel.Result {
                    override fun success(result: Any?) { }
                    override fun error(code: String, message: String?, details: Any?) {
                        if (heartRateEvents === events) heartRateEvents = null
                        events.error(code, message, details)
                        events.endOfStream()
                    }
                    override fun notImplemented() = error("INTERNAL", "Heart-rate stream unavailable.", null)
                }
                withBluetoothPermission(streamResult) {
                    if (generation == heartRateStreamGeneration && heartRateEvents === events) {
                        startOperation(streamResult, Operation.ReadHeartRate, address)
                    }
                }
            }

            override fun onCancel(arguments: Any?) {
                heartRateStreamGeneration++
                heartRateEvents = null
                if (activeOperation == Operation.ReadHeartRate) {
                    // Do not emit an error into a stream the consumer cancelled.
                    pendingResult = null
                    activeOperation = null
                    closeTransport()
                }
            }
        })
        secureEventChannel = EventChannel(binding.binaryMessenger, "fitbit_air/secure_events")
        secureEventChannel.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
                if (pendingResult != null || pendingPermissionResult != null || diagnosticEvents != null || secureEvents != null) {
                    events.error("BUSY", "Another Bluetooth operation is running.", null)
                    events.endOfStream()
                    return
                }
                val requestedSeconds = (arguments as? Map<*, *>)?.get("durationSeconds") ?: 30
                if (requestedSeconds !is Int || requestedSeconds !in 30..90) {
                    events.error("INVALID_ARGUMENT", "durationSeconds must be an integer from 30 to 90.", null)
                    events.endOfStream()
                    return
                }
                secureCaptureSeconds = requestedSeconds
                secureEvents = events
                val reply = object : MethodChannel.Result {
                    override fun success(result: Any?) {
                        if (secureEvents === events) {
                            events.success((result as Map<*, *>) + mapOf("kind" to "complete", "receivedAtMillis" to System.currentTimeMillis()))
                            secureEvents = null
                            events.endOfStream()
                        }
                    }
                    override fun error(code: String, message: String?, details: Any?) {
                        if (secureEvents === events) {
                            secureEvents = null
                            events.error(code, message, details)
                            events.endOfStream()
                        }
                    }
                    override fun notImplemented() = error("INTERNAL", "Secure event capture unavailable.", null)
                }
                withBluetoothPermission(reply) {
                    if (secureEvents === events) startOperation(reply, Operation.CaptureSecureEvents)
                }
            }
            override fun onCancel(arguments: Any?) {
                secureEvents = null
                if (activeOperation == Operation.CaptureSecureEvents) {
                    pendingResult = null
                    activeOperation = null
                    closeTransport()
                }
            }
        })
        connectionChannel = EventChannel(binding.binaryMessenger, "fitbit_air/connection")
        connectionChannel.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
                withBluetoothPermission(streamPermissionResult(events)) {
                    val device = findBondedAir() ?: run {
                        events.error("AIR_NOT_FOUND", "Pair your Air in Google Health first.", null)
                        events.endOfStream()
                        return@withBluetoothPermission
                    }
                    connectionWatcher?.close()
                    connectionWatcher = AirConnectionWatcher(appContext, device) { events.success(it) }
                        .also { it.start() }
                }
            }

            override fun onCancel(arguments: Any?) {
                connectionWatcher?.close()
                connectionWatcher = null
            }
        })
        channelActivityChannel = EventChannel(binding.binaryMessenger, "fitbit_air/channel_activity")
        channelActivityChannel.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
                withBluetoothPermission(streamPermissionResult(events)) {
                    val device = findBondedAir() ?: run {
                        events.error("AIR_NOT_FOUND", "Pair your Air in Google Health first.", null)
                        events.endOfStream()
                        return@withBluetoothPermission
                    }
                    channelMonitor?.close()
                    val monitor = AirChannelMonitor(appContext, { events.success(it) }) { code, message ->
                        events.error(code, message, null)
                        events.endOfStream()
                    }
                    channelMonitor = monitor
                    monitor.start(device)
                }
            }

            override fun onCancel(arguments: Any?) {
                channelMonitor?.close()
                channelMonitor = null
            }
        })
        deviceEventChannel = EventChannel(binding.binaryMessenger, "fitbit_air/device_events")
        deviceEventChannel.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
                if (pendingResult != null || pendingPermissionResult != null || diagnosticEvents != null) {
                    events.error("BUSY", "Another Bluetooth operation is running.", null)
                    events.endOfStream()
                    return
                }
                diagnosticEvents = events
                val permissionResult = object : MethodChannel.Result {
                    override fun success(result: Any?) { }
                    override fun error(code: String, message: String?, details: Any?) {
                        if (diagnosticEvents === events) {
                            eventMonitor?.close()
                            eventMonitor = null
                            diagnosticEvents = null
                            AirTransportLease.release(transportOwner)
                        }
                        events.error(code, message, details)
                        events.endOfStream()
                    }
                    override fun notImplemented() = error("INTERNAL", "Diagnostic events unavailable.", null)
                }
                withBluetoothPermission(permissionResult) {
                    if (diagnosticEvents !== events) return@withBluetoothPermission
                    val device = findBondedAir()
                    if (device == null) {
                        permissionResult.error("AIR_NOT_FOUND", "Pair your Air in Google Health first.", null)
                    } else {
                        if (!AirTransportLease.tryAcquire(transportOwner)) {
                            permissionResult.error("BUSY", "Another Flutter engine owns the Bluetooth transport.", null)
                            return@withBluetoothPermission
                        }
                        val monitor = AirEventMonitor(appContext, { event ->
                            if (diagnosticEvents === events) events.success(event)
                        }, { code, message -> permissionResult.error(code, message, null) })
                        eventMonitor = monitor
                        try {
                            monitor.start(device)
                        } catch (error: Exception) {
                            permissionResult.error("EVENT_CONNECT", error.message ?: "Could not start diagnostic connection.", null)
                        }
                    }
                }
            }

            override fun onCancel(arguments: Any?) {
                val wasActive = diagnosticEvents != null
                diagnosticEvents = null
                eventMonitor?.close()
                eventMonitor = null
                if (wasActive) AirTransportLease.release(transportOwner)
            }
        })
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "transportDiagnostics" -> result.success(AirTransportDiagnostics.snapshot())
            "cancelOperation" -> {
                if (diagnosticEvents != null) {
                    eventMonitor?.close()
                    eventMonitor = null
                    diagnosticEvents?.endOfStream()
                    diagnosticEvents = null
                    AirTransportLease.release(transportOwner)
                }
                if (activeOperation == Operation.PreviewHaptic ||
                    activeOperation == Operation.SinglePulse || activeOperation is Operation.Tap ||
                    activeOperation is Operation.SetHapticIntensity) {
                    result.error("CANNOT_CANCEL_WRITE", "Wait for the haptic write and verification to finish.", null)
                    return
                }
                finishError("CANCELLED", "Fitbit Air operation cancelled.")
                result.success(null)
            }
            "scanHeartRateDevices" -> withBluetoothPermission(result, needsScan = true) { scanHeartRateDevices(result) }
            "readHeartRate" -> withBluetoothPermission(result) {
                val address = call.argument<String>("deviceId")
                if (address != null && !BluetoothAdapter.checkBluetoothAddress(address)) {
                    result.error("INVALID_DEVICE", "Select a discovered heart-rate sensor first.", null)
                } else startOperation(result, Operation.ReadHeartRate, address)
            }
            "readDeviceInfo" -> withBluetoothPermission(result) {
                startOperation(result, Operation.ReadDeviceInfo)
            }
            "isAirPaired" -> withBluetoothPermission(result) {
                result.success(findBondedAir() != null)
            }
            "vibrate" -> withBluetoothPermission(result) {
                startOperation(result, Operation.PreviewHaptic)
            }
            "getHapticIntensity" -> withBluetoothPermission(result) {
                startOperation(result, Operation.ReadHapticIntensity)
            }
            "keepLinkWarm" -> {
                val enabled = call.argument<Boolean>("enabled") == true
                if (!enabled) {
                    stopWarmLink()
                    result.success(null)
                } else withBluetoothPermission(result) {
                    val device = findBondedAir()
                    if (device == null) {
                        result.error("AIR_NOT_FOUND", "Pair your Air in Google Health first.", null)
                    } else {
                        startWarmLink(device)
                        result.success(null)
                    }
                }
            }
            "readBattery" -> withBluetoothPermission(result) {
                startOperation(result, Operation.ReadBattery)
            }
            "readSignalStrength" -> withBluetoothPermission(result) {
                startOperation(result, Operation.ReadSignalStrength)
            }
            "isAirConnected" -> withBluetoothPermission(result) {
                val device = findBondedAir()
                result.success(device != null && AirConnectionWatcher.isConnected(appContext, device))
            }
            "probeBatteryAccess" -> withBluetoothPermission(result) {
                startOperation(result, Operation.ProbeBatteryAccess)
            }
            "probeReadOnlyResources" -> withBluetoothPermission(result) {
                startOperation(result, Operation.ProbeReadOnlyResources)
            }
            "setHapticIntensity" -> {
                val intensity = call.argument<Int>("intensity")
                if (intensity !in setOf(2, 3)) {
                    result.error("INVALID_INTENSITY", "Haptic intensity must be 2 (low) or 3 (high).", null)
                } else {
                    withBluetoothPermission(result) {
                        startOperation(result, Operation.SetHapticIntensity(intensity!!))
                    }
                }
            }
            "pulse" -> withBluetoothPermission(result) {
                startOperation(result, Operation.SinglePulse)
            }
            "tap" -> {
                val intensity = call.argument<Int>("intensity")
                if (intensity != null && intensity !in setOf(2, 3)) {
                    result.error("INVALID_INTENSITY", "Haptic intensity must be 2 (low) or 3 (high).", null)
                } else {
                    withBluetoothPermission(result) { startOperation(result, Operation.Tap(intensity)) }
                }
            }
            else -> result.notImplemented()
        }
    }

    /** Reports permission failures of a passive stream as stream errors. */
    private fun streamPermissionResult(events: EventChannel.EventSink) = object : MethodChannel.Result {
        override fun success(result: Any?) { }
        override fun error(code: String, message: String?, details: Any?) {
            events.error(code, message, details)
            events.endOfStream()
        }
        override fun notImplemented() = error("INTERNAL", "Stream unavailable.", null)
    }

    private fun withBluetoothPermission(result: MethodChannel.Result, needsScan: Boolean = false, action: () -> Unit) {
        val permissions = if (Build.VERSION.SDK_INT >= 31) {
            if (needsScan) arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
            else arrayOf(Manifest.permission.BLUETOOTH_CONNECT)
        } else if (needsScan) arrayOf(Manifest.permission.ACCESS_FINE_LOCATION) else emptyArray()
        if (permissions.all { appContext.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            action()
            return
        }
        val host = activity
        if (host == null) {
            result.error("NO_ACTIVITY", "Attach the Flutter view before calling Fitbit Air.", null)
            return
        }
        if (pendingPermissionResult != null) {
            result.error("BUSY", "A Bluetooth permission request is already pending.", null)
            return
        }
        pendingPermissionResult = result
        pendingAction = action
        host.requestPermissions(permissions, PERMISSION_REQUEST)
    }

    @SuppressLint("MissingPermission")
    private fun scanHeartRateDevices(result: MethodChannel.Result) {
        if (pendingResult != null || diagnosticEvents != null) {
            result.error("BUSY", "A Fitbit Air operation is already running.", null)
            return
        }
        val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val scanner = manager.adapter?.bluetoothLeScanner
        if (scanner == null) {
            result.error("BLUETOOTH_OFF", "Enable Bluetooth before scanning.", null)
            return
        }
        if (!AirTransportLease.tryAcquire(transportOwner)) {
            result.error("BUSY", "Another Flutter engine owns the Bluetooth transport.", null)
            return
        }
        pendingResult = result
        val found = mutableMapOf<String, Map<String, Any>>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, scan: ScanResult) {
                handler.post {
                    if (scanCallback !== this) return@post
                    found[scan.device.address] = mapOf(
                        "deviceId" to scan.device.address,
                        "name" to (scan.scanRecord?.deviceName ?: scan.device.name ?: "Heart-rate sensor"),
                        "rssi" to scan.rssi,
                    )
                }
            }
            override fun onScanFailed(errorCode: Int) {
                finishError("SCAN_FAILED", "Bluetooth scan failed (status $errorCode).")
            }
        }
        scanCallback = callback
        val deadline = Runnable { finishSuccess(mapOf("devices" to found.values.toList())) }
        timeout = deadline
        handler.postDelayed(deadline, 8_000)
        try {
            scanner.startScan(
                listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(HEART_RATE_SERVICE)).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                callback,
            )
        } catch (error: Exception) {
            finishError("SCAN_FAILED", error.message ?: "Could not start Bluetooth scan.")
        }
    }

    @SuppressLint("MissingPermission")
    private fun findBondedAir(): BluetoothDevice? {
        val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        return manager.adapter?.bondedDevices?.firstOrNull {
            it.name?.contains("Fitbit Air", ignoreCase = true) == true
        }
    }

    @SuppressLint("MissingPermission")
    private fun startOperation(result: MethodChannel.Result, operation: Operation, deviceId: String? = null) {
        if (pendingResult != null || diagnosticEvents != null) {
            result.error("BUSY", "A Fitbit Air operation is already running.", null)
            return
        }
        val device = if (deviceId == null) findBondedAir() else {
            val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            manager.adapter?.getRemoteDevice(deviceId)
        }
        if (device == null) {
            result.error("AIR_NOT_FOUND", "No bonded Fitbit Air found. Pair it in Google Health first.", null)
            return
        }
        if (!AirTransportLease.tryAcquire(transportOwner)) {
            result.error("BUSY", "Another Flutter engine owns the Bluetooth transport.", null)
            return
        }
        pendingResult = result
        heartRateSubscribed = false
        activeOperation = operation
        cleanupOperation = when (operation) {
            Operation.ReadHeartRate -> "ReadHeartRate"
            Operation.ReadDeviceInfo -> "ReadDeviceInfo"
            Operation.PreviewHaptic -> "PreviewHaptic"
            Operation.ReadHapticIntensity -> "ReadHapticIntensity"
            Operation.ProbeBatteryAccess -> "ProbeBatteryAccess"
            Operation.ReadBattery -> "ReadBattery"
            Operation.ReadSignalStrength -> "ReadSignalStrength"
            Operation.ProbeReadOnlyResources -> "ProbeReadOnlyResources"
            Operation.CaptureSecureEvents -> "CaptureSecureEvents"
            is Operation.SetHapticIntensity -> "SetHapticIntensity"
            Operation.SinglePulse -> "SinglePulse"
            is Operation.Tap -> "Tap"
        }
        sessionStarted = false
        handshakeAttempts = 0
        val deadline = Runnable {
            if (activeOperation == Operation.ReadHeartRate) {
                finishError("HEART_RATE_TIMEOUT", if (heartRateSubscribed)
                    "Air accepted the heart-rate subscription but sent no measurements. Heart-rate sharing may be disabled or paused."
                else "Heart-rate subscription was not acknowledged before the deadline.")
            } else finishError("TIMEOUT", "Fitbit Air operation timed out.")
        }
        timeout = deadline
        handler.postDelayed(deadline, if (operation == Operation.CaptureSecureEvents)
            (secureCaptureSeconds + 15) * 1000L else OPERATION_TIMEOUT_MS)
        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) = gattCallbacks.dispatch(g) {
                if (g !== gatt) return@dispatch
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    finishError("GATT_CONNECT", "Bluetooth connection failed (status $status).")
                } else if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED &&
                    activeOperation == Operation.ReadSignalStrength) {
                    // Signal strength needs only the link, not services or the tracker channel.
                    if (!g.readRemoteRssi()) finishError("RSSI", "Android rejected the signal-strength read.")
                } else if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                    g.discoverServices()
                } else if (newState == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) {
                    finishError("GATT_DISCONNECTED", "Air disconnected before the operation finished.")
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = gattCallbacks.dispatch(g) {
                if (g !== gatt) return@dispatch
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    finishError("GATT_DISCOVERY", "Could not discover Air services (status $status).")
                    return@dispatch
                }
                if (activeOperation == Operation.ReadDeviceInfo) {
                    readDeviceInformation(g, device)
                    return@dispatch
                }
                if (activeOperation == Operation.ReadHeartRate) {
                    subscribeHeartRate(g)
                    return@dispatch
                }
                val rx = g.getService(TRACKER_SERVICE)?.getCharacteristic(TRACKER_RX)
                val cccd = rx?.getDescriptor(CCCD)
                if (rx == null || cccd == null || !g.setCharacteristicNotification(rx, true)) {
                    finishError("TRACKER_CHANNEL", "Air tracker channel is unavailable.")
                    return@dispatch
                }
                val accepted = if (Build.VERSION.SDK_INT >= 33) {
                    g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothGatt.GATT_SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(cccd)
                }
                if (!accepted) finishError("SUBSCRIBE", "Could not subscribe to the tracker channel.")
            }

            override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) = gattCallbacks.dispatch(g) {
                if (g !== gatt) return@dispatch
                android.util.Log.d("FitbitAir", "CCCD ${descriptor.characteristic.uuid} status=$status")
                if (descriptor.uuid != CCCD) return@dispatch
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    finishError("SUBSCRIBE", "Tracker channel subscription failed (status $status).")
                } else if (activeOperation == Operation.ReadHeartRate) {
                    heartRateSubscribed = true
                    // The next standard Heart Rate Measurement completes this read.
                } else {
                    holdWhileChannelBusy(g, device)
                }
            }

            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) = gattCallbacks.dispatch(g) {
                if (g !== gatt) return@dispatch
                if (c.uuid == HEART_RATE_MEASUREMENT && activeOperation == Operation.ReadHeartRate) {
                    android.util.Log.d("FitbitAir", "Heart-rate notification length=${value.size}")
                    handleHeartRate(value)
                    return@dispatch
                }
                if (c.uuid == TRACKER_RX) {
                    sessionRxPackets++
                    channelActivity?.record(SystemClock.elapsedRealtime(), value.size)
                    rxSource?.receiveData(value)?.subscribe({}, { finishError("RX", it.message ?: "Receive failed.") })
                }
            }

            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
                val value = c.value?.copyOf() ?: byteArrayOf()
                gattCallbacks.dispatch(g) {
                    if (g !== gatt) return@dispatch
                    if (Build.VERSION.SDK_INT < 33 && c.uuid == HEART_RATE_MEASUREMENT && activeOperation == Operation.ReadHeartRate) {
                        handleHeartRate(value)
                        return@dispatch
                    }
                    if (Build.VERSION.SDK_INT < 33 && c.uuid == TRACKER_RX) {
                        sessionRxPackets++
                        channelActivity?.record(SystemClock.elapsedRealtime(), value.size)
                        rxSource?.receiveData(value)?.subscribe({}, { finishError("RX", it.message ?: "Receive failed.") })
                    }
                }
            }

            override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) = gattCallbacks.dispatch(g) {
                if (g !== gatt) return@dispatch
                writeInFlight = false
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    finishError("TX", "Tracker channel write failed (status $status).")
                } else {
                    writeNextPacket(g)
                }
            }

            override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) = gattCallbacks.dispatch(g) {
                if (g !== gatt || activeOperation != Operation.ReadSignalStrength) return@dispatch
                if (status == BluetoothGatt.GATT_SUCCESS) finishSuccess(mapOf("rssi" to rssi))
                else finishError("RSSI", "Signal-strength read failed (status $status).")
            }

            override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) = gattCallbacks.dispatch(g) {
                if (g !== gatt) return@dispatch
                if (activeOperation == Operation.ReadDeviceInfo) handleInfoRead(g, c, value, status)
            }

            @Suppress("DEPRECATION")
            override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
                val value = c.value?.copyOf() ?: byteArrayOf()
                gattCallbacks.dispatch(g) {
                    if (Build.VERSION.SDK_INT < 33 && activeOperation == Operation.ReadDeviceInfo) {
                        handleInfoRead(g, c, value, status)
                    }
                }
            }
        }
        operationCallback = callback
        warmLinkFallback = null
        val warm = warmLink
        val usesSession = operation != Operation.ReadDeviceInfo && operation != Operation.ReadHeartRate &&
            operation != Operation.ReadSignalStrength
        if (usesSession && warm != null && warm.isSettled(SystemClock.elapsedRealtime()) &&
            warm.address == device.address && warm.refreshSubscription()) {
            // Already connected and subscribed: skip setup and decide from known traffic.
            val connection = warm.gatt!!
            warm.delegate = callback
            gatt = connection
            usingWarmLink = true
            // The idle link (~150 ms with peripheral latency) dominates the
            // handshake. Request the fast link now; the update needs several
            // slow radio events to take effect. closeTransport() releases it.
            linkBoosted = connection.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
            handler.post { if (gatt === connection) holdWhileChannelBusy(connection, device) }
            return
        }
        connectFresh(device, callback)
    }

    @SuppressLint("MissingPermission")
    private fun connectFresh(device: BluetoothDevice, callback: BluetoothGattCallback) {
        val connection = try {
            device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (error: Exception) {
            finishError("GATT_CONNECT", error.message ?: "Could not start Bluetooth connection.")
            return
        }
        if (connection == null) {
            finishError("GATT_CONNECT", "Android did not create a Bluetooth connection.")
        } else {
            gatt = connection
        }
    }

    /**
     * The warm link reported ready but delivered nothing since the handshake
     * started, although the Air answers a Gattlink reset within about a
     * second (and Google Health's traffic would also arrive). Android can keep
     * such a client connected without routing notifications to it; every
     * command on it then times out. No device command has been sent yet, so
     * the same operation continues on a fresh connection, and the warm link is
     * rebuilt afterwards.
     */
    private fun abandonSilentWarmLink(device: BluetoothDevice) {
        val callback = operationCallback ?: return
        android.util.Log.d("FitbitAir", "warm link silent; reconnecting")
        AirTransportDiagnostics.recordLinkRecovery("silent_fallback")
        warmLinkFallback = "silent"
        handshakeDeadline?.let(handler::removeCallbacks)
        handshakeDeadline = null
        try {
            disposeSession()
        } catch (error: Exception) {
            finishError("GOLDENGATE", "Could not reset the secure session.")
            return
        }
        val stale = warmLink
        warmLink = null
        stale?.close()
        gatt = null
        usingWarmLink = false
        linkBoosted = false
        handshakeAttempts = 0
        connectFresh(device, callback)
    }

    @SuppressLint("MissingPermission")
    private fun startWarmLink(device: BluetoothDevice) {
        warmLinkWanted = true
        if (warmLink?.address != device.address) {
            warmLink?.close()
            warmLink = AirWarmLink(appContext).also { it.start(device) }
            lastWarmLinkRebuild = SystemClock.elapsedRealtime()
        }
        if (adapterReceiver == null) {
            // Connections made before Bluetooth was switched off never come back.
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                        BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                            if (warmLink?.gatt !== gatt) { warmLink?.close(); warmLink = null }
                        }
                        BluetoothAdapter.STATE_ON -> handler.postDelayed({ rebuildWarmLink("bluetooth_on") }, 1_000L)
                    }
                }
            }
            appContext.registerReceiver(receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
            adapterReceiver = receiver
        }
        if (warmLinkWatchdog == null) {
            val watchdog = object : Runnable {
                override fun run() {
                    if (!warmLinkWanted) return
                    val now = SystemClock.elapsedRealtime()
                    val link = warmLink
                    if (link == null || (link.isStuck(now) && now - lastWarmLinkRebuild >= WARM_REBUILD_MIN_MS)) {
                        rebuildWarmLink(if (link == null) "missing" else "stuck")
                    }
                    handler.postDelayed(this, WARM_WATCHDOG_MS)
                }
            }
            warmLinkWatchdog = watchdog
            handler.postDelayed(watchdog, WARM_WATCHDOG_MS)
        }
    }

    /** Replaces the warm link unless a command is using it. */
    private fun rebuildWarmLink(reason: String) {
        if (!warmLinkWanted || pendingResult != null) return
        val device = findBondedAir() ?: return
        android.util.Log.d("FitbitAir", "warm link rebuilt: $reason")
        AirTransportDiagnostics.recordLinkRecovery("rebuilt_$reason")
        warmLink?.close()
        warmLink = null
        startWarmLink(device)
    }

    private fun stopWarmLink() {
        warmLinkWanted = false
        warmLinkWatchdog?.let(handler::removeCallbacks)
        warmLinkWatchdog = null
        adapterReceiver?.let { runCatching { appContext.unregisterReceiver(it) } }
        adapterReceiver = null
        warmLink?.close()
        warmLink = null
    }

    @SuppressLint("MissingPermission")
    private fun subscribeHeartRate(g: BluetoothGatt) {
        val characteristic = g.getService(HEART_RATE_SERVICE)?.getCharacteristic(HEART_RATE_MEASUREMENT)
        val descriptor = characteristic?.getDescriptor(CCCD)
        if (characteristic == null || descriptor == null || !g.setCharacteristicNotification(characteristic, true)) {
            finishError("HEART_RATE_UNAVAILABLE", "Air does not expose standard heart-rate notifications.")
            return
        }
        val accepted = if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            g.writeDescriptor(descriptor)
        }
        if (!accepted) finishError("SUBSCRIBE", "Could not subscribe to heart rate.")
    }

    private fun handleHeartRate(bytes: ByteArray) {
        try {
            var offset = 0
            fun u8(): Int {
                require(offset < bytes.size) { "Truncated heart-rate measurement." }
                return bytes[offset++].toInt() and 0xff
            }
            fun u16(): Int = u8() or (u8() shl 8)
            val flags = u8()
            val bpm = if (flags and 1 == 0) u8() else u16()
            val contactSupported = flags and 4 != 0
            val values = mutableMapOf<String, Any>(
                "beatsPerMinute" to bpm,
                "receivedAtMillis" to System.currentTimeMillis(),
                "contactSupported" to contactSupported,
            )
            if (contactSupported) values["contactDetected"] = flags and 2 != 0
            if (flags and 8 != 0) values["energyExpendedKj"] = u16()
            val rr = mutableListOf<Double>()
            if (flags and 16 != 0) {
                require(offset < bytes.size) { "Missing RR intervals." }
                while (offset < bytes.size) rr.add(u16() * 1000.0 / 1024.0)
            }
            values["rrIntervalsMillis"] = rr
            handler.post {
                val events = heartRateEvents
                if (events != null && activeOperation == Operation.ReadHeartRate) {
                    timeout?.let(handler::removeCallbacks)
                    timeout = null
                    events.success(values)
                } else if (activeOperation == Operation.ReadHeartRate) finishSuccess(values)
            }
        } catch (error: IllegalArgumentException) {
            finishError("HEART_RATE_FORMAT", error.message ?: "Malformed heart-rate measurement.")
        }
    }

    @SuppressLint("MissingPermission")
    private fun readDeviceInformation(g: BluetoothGatt, device: BluetoothDevice) {
        deviceInfo.clear()
        infoReads.clear()
        deviceInfo["name"] = device.name ?: "Fitbit Air"
        deviceInfo["services"] = g.services.map { service ->
            mapOf(
                "uuid" to service.uuid.toString(),
                "characteristics" to service.characteristics.map { characteristic ->
                    mapOf("uuid" to characteristic.uuid.toString(), "properties" to characteristic.properties)
                },
            )
        }
        for (service in g.services) {
            for (characteristic in service.characteristics) {
                if (INFO_CHARACTERISTICS.containsKey(characteristic.uuid) &&
                    characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0
                ) infoReads.addLast(characteristic)
            }
        }
        nextInfoRead(g)
    }

    @SuppressLint("MissingPermission")
    private fun nextInfoRead(g: BluetoothGatt) {
        val characteristic = infoReads.pollFirst()
        if (characteristic == null) {
            finishSuccess(deviceInfo.toMap())
        } else if (!g.readCharacteristic(characteristic)) {
            deviceInfo["${INFO_CHARACTERISTICS[characteristic.uuid]}Error"] = "Android rejected read"
            nextInfoRead(g)
        }
    }

    private fun handleInfoRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, bytes: ByteArray, status: Int) {
        val key = INFO_CHARACTERISTICS[c.uuid] ?: return
        if (status == BluetoothGatt.GATT_SUCCESS) {
            if (key == "connectionStatus" || key == "connectionConfiguration") {
                try {
                    val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    if (key == "connectionStatus") {
                        require(bytes.size >= 7) { "Truncated connection status" }
                        val flags = buffer.get().toInt() and 0xff
                        deviceInfo[key] = mapOf(
                            "bonded" to (flags and 1 != 0),
                            "encrypted" to (flags and 2 != 0),
                            "dataLengthExtension" to (flags and 4 != 0),
                            "dataLengthReboot" to (flags and 8 != 0),
                            "maxTxPayload" to (buffer.get().toInt() and 0xff),
                            "maxTxTimeMicros" to (buffer.short.toInt() and 0xffff),
                            "maxRxPayload" to (buffer.get().toInt() and 0xff),
                            "maxRxTimeMicros" to (buffer.short.toInt() and 0xffff),
                        )
                    } else {
                        require(bytes.size >= 9) { "Truncated connection configuration" }
                        deviceInfo[key] = mapOf(
                            "intervalMillis" to (buffer.short.toInt() and 0xffff) * 1.25,
                            "peripheralLatency" to (buffer.short.toInt() and 0xffff),
                            "supervisionTimeoutMillis" to (buffer.short.toInt() and 0xffff) * 10,
                            "mtu" to (buffer.short.toInt() and 0xffff),
                            "mode" to when (buffer.get().toInt()) {
                                0 -> "default"
                                1 -> "fast"
                                2 -> "slow"
                                else -> "unknown"
                            },
                        )
                    }
                } catch (error: IllegalArgumentException) {
                    deviceInfo["${key}Error"] = error.message ?: "Invalid link status"
                }
            } else if (key == "batteryPercent") {
                val battery = bytes.firstOrNull()?.toInt()?.and(0xff)
                if (battery != null && battery in 0..100) deviceInfo[key] = battery
            } else {
                deviceInfo[key] = bytes.toString(Charsets.UTF_8).trimEnd('\u0000')
            }
        } else {
            deviceInfo["${key}Error"] = "GATT status $status"
        }
        nextInfoRead(g)
    }

    /**
     * Waits while Google Health is syncing over the shared tracker channel,
     * observed passively from the notifications we already receive. Starting
     * our session mid-sync would make that sync fail. Bounded; see
     * [AirChannelActivity].
     */
    private fun holdWhileChannelBusy(activeGatt: BluetoothGatt, device: BluetoothDevice) {
        val now = SystemClock.elapsedRealtime()
        val history = if (usingWarmLink) warmLink?.recentTraffic(now) else null
        val activity = if (history != null) AirChannelActivity(observeMillis = 0) else AirChannelActivity()
        activity.begin(now)
        history?.forEach { (at, size) -> activity.record(at, size) }
        channelActivity = activity
        val tick = object : Runnable {
            override fun run() {
                if (channelActivity !== activity || activeGatt !== gatt) return
                val now = SystemClock.elapsedRealtime()
                if (activity.decide(now) == AirChannelActivity.Decision.WAIT) {
                    handler.postDelayed(this, HOLD_TICK_MS)
                    return
                }
                channelActivity = null
                holdTick = null
                channelHoldOutcome = activity.outcome(now)
                android.util.Log.d("FitbitAir", "channel hold $channelHoldOutcome: " +
                    "${activity.observedPackets} packets, ${activity.observedBytes} bytes")
                startGoldengate(activeGatt, device)
            }
        }
        holdTick = tick
        handler.postDelayed(tick, HOLD_TICK_MS)
    }

    private fun startGoldengate(activeGatt: BluetoothGatt, device: BluetoothDevice) {
        if (activeGatt !== gatt || txSink != null) return
        try {
            val service = activeGatt.getService(TRACKER_SERVICE)
            val tx = service?.getCharacteristic(TRACKER_TX)
            val rx = service?.getCharacteristic(TRACKER_RX)
            if (tx == null || rx == null) {
                finishError("TRACKER_CHANNEL", "Air tracker channel characteristics are missing.")
                return
            }
            txCharacteristic = tx
            BundledNativeLoader.initialize()
            val sink = TxSink()
            txSink = sink
            val source = RxSource()
            rxSource = source
            disposables.add(sink.dataObservable.subscribe({ data ->
                gattCallbacks.dispatch(activeGatt) {
                    if (txSink !== sink) return@dispatch
                    synchronized(txQueue) { txQueue.addLast(data) }
                    writeNextPacket(activeGatt)
                }
            }, { error -> gattCallbacks.dispatch(activeGatt) {
                finishError("GOLDENGATE_TX", error.message ?: "Transport send failed.")
            } }))
            val newStack = Stack(BluetoothAddressNodeKey(device.address), sink.thisPointer, source.thisPointer, DtlsSocketNetifGattlink(), false)
            stack = newStack
            val coap = CoapEndpoint()
            endpoint = coap
            coap.attach(newStack)
            disposables.add(newStack.stackEventObservable.subscribe({ event ->
                android.util.Log.d("FitbitAir", "session stack event ${event.javaClass.simpleName}")
            }, { }))
            disposables.add(newStack.dtlsEventObservable.subscribe({ state ->
                android.util.Log.d("FitbitAir", "session DTLS ${state.state} error=${state.lastError}")
                if (state.state == TLS_STATE_ERROR && state.lastError == LOST_HANDSHAKE_RACE) {
                    // Google Health answered the Air's handshake too; both stall.
                    // Retry soon instead of waiting for the full stall deadline.
                    sessionCallbacks.dispatch(coap) { expediteHandshakeRetry(activeGatt, device, coap) }
                }
                sessionCallbacks.dispatch(coap) {
                    if (state.state == TLS_STATE_SESSION && !sessionStarted) {
                        sessionStarted = true
                        handshakeDeadline?.let(handler::removeCallbacks)
                        handshakeDeadline = null
                        // The idle link runs at ~150 ms with peripheral latency, so each
                        // request waits several radio events. Boost only once our session
                        // exists: on a fast link Google Health tends to answer the Air's
                        // handshake first and both stall. closeTransport() restores low power.
                        if (!linkBoosted) {
                            linkBoosted = gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) == true
                        }
                        runOperation(coap)
                    }
                }
            }, { error -> finishSessionError(coap, "DTLS", error.message ?: "Secure transport failed.") }))
            handshakeAttempts++
            val deadline = Runnable {
                if (endpoint === coap && !sessionStarted) retryHandshake(activeGatt, device)
            }
            handshakeDeadline = deadline
            handler.postDelayed(deadline, HANDSHAKE_RETRY_MS)
            sessionRxPackets = 0
            warmSilenceCheck?.let(handler::removeCallbacks)
            warmSilenceCheck = if (!usingWarmLink) null else Runnable {
                warmSilenceCheck = null
                if (endpoint === coap && !sessionStarted && usingWarmLink && sessionRxPackets == 0) {
                    abandonSilentWarmLink(device)
                }
            }.also { handler.postDelayed(it, WARM_SILENCE_MS) }
            newStack.start()
        } catch (error: UnsatisfiedLinkError) {
            // No bundled library for this device's ABI, or it failed to load.
            finishError("NATIVE_UNAVAILABLE", error.message ?: error.toString())
        } catch (error: Throwable) {
            finishError("GOLDENGATE", error.message ?: error.toString())
        }
    }

    private fun expediteHandshakeRetry(activeGatt: BluetoothGatt, device: BluetoothDevice, coap: CoapEndpoint) {
        if (sessionStarted || endpoint !== coap) return
        handshakeDeadline?.let(handler::removeCallbacks)
        val retry = Runnable {
            if (endpoint === coap && !sessionStarted) retryHandshake(activeGatt, device)
        }
        handshakeDeadline = retry
        handler.postDelayed(retry, LOST_RACE_RETRY_MS)
    }

    /**
     * A handshake normally completes within ~2 s. When Google Health answers the
     * Air's handshake at the same time, neither may win and ours stalls. No
     * device command has been sent yet, so one fresh attempt is safe.
     */
    private fun retryHandshake(activeGatt: BluetoothGatt, device: BluetoothDevice) {
        handshakeDeadline = null
        if (handshakeAttempts >= MAX_HANDSHAKE_ATTEMPTS) {
            finishError("HANDSHAKE_TIMEOUT", "The Air did not complete a secure session; Google Health may be using it.")
            return
        }
        android.util.Log.d("FitbitAir", "handshake stalled; retrying")
        try {
            disposeSession()
        } catch (error: Exception) {
            finishError("GOLDENGATE", "Could not reset the secure session.")
            return
        }
        startGoldengate(activeGatt, device)
    }

    /** Releases the native session but keeps the GATT connection. */
    private fun disposeSession() {
        disposables.clear()
        secureCapture?.close(); secureCapture = null
        endpoint?.close(); endpoint = null
        stack?.close(); stack = null
        rxSource?.close(); rxSource = null
        txSink?.close(); txSink = null
        synchronized(txQueue) { txQueue.clear() }
    }

    @SuppressLint("MissingPermission")
    private fun runOperation(coap: CoapEndpoint) {
        when (val operation = activeOperation) {
            Operation.ReadDeviceInfo -> finishSessionError(coap, "INTERNAL", "Device information does not use DTLS.")
            Operation.ReadHeartRate -> finishSessionError(coap, "INTERNAL", "Standard heart rate does not use DTLS.")
            Operation.PreviewHaptic -> runBoundedHaptic(coap)
            Operation.ReadHapticIntensity -> runReadHapticIntensity(coap)
            Operation.ProbeBatteryAccess -> runBatteryAccessProbe(coap)
            Operation.ReadBattery -> runReadBattery(coap)
            Operation.ReadSignalStrength -> finishSessionError(coap, "INTERNAL", "Signal strength does not use DTLS.")
            Operation.ProbeReadOnlyResources -> disposables.add(AirReadOnlyResources.probe(coap)
                .subscribe({ finishSessionSuccess(coap, it) }, {
                    finishSessionError(coap, "RESOURCE_PROBE_FAILED", "Read-only resource probe failed.")
                }))
            Operation.CaptureSecureEvents -> {
                val events = secureEvents ?: run {
                    finishSessionError(coap, "CANCELLED", "Secure event listener was cancelled.")
                    return
                }
                val peer = gatt?.device ?: run {
                    finishSessionError(coap, "GATT_DISCONNECTED", "Secure event connection ended.")
                    return
                }
                try {
                    // Mirror the non-authenticated resource filter group, not GROUP_0.
                    coap.setFilterGroup(com.fitbit.goldengate.bindings.coap.CoapGroupRequestFilterMode.GROUP_1,
                        BluetoothAddressNodeKey(peer.address))
                    val capture = AirSecureEventCapture(coap, secureCaptureSeconds) { event ->
                        handler.post { if (secureEvents === events) events.success(event) }
                    }
                    secureCapture = capture
                    disposables.add(capture.start().subscribe({ finishSessionSuccess(coap, it) }, {
                        finishSessionError(coap, "EVENT_CAPTURE_FAILED", "Could not observe secure device events.")
                    }))
                } catch (_: LinkageError) {
                    finishSessionError(coap, "EVENT_CAPTURE_ABI", "Installed native transport lacks compatible event-server bindings.")
                } catch (_: Exception) {
                    finishSessionError(coap, "EVENT_CAPTURE_FAILED", "Could not initialize secure event capture.")
                }
            }
            is Operation.SetHapticIntensity -> runSetHapticIntensity(coap, operation.intensity)
            Operation.SinglePulse -> runSinglePulse(coap)
            is Operation.Tap -> runTap(coap, operation.intensity)
            null -> finishSessionError(coap, "INTERNAL", "No Fitbit Air operation was selected.")
        }
    }

    /**
     * Battery level from the device-information resource. The level matched
     * Google Health's displayed percentage; voltage is in millivolts.
     */
    private fun runReadBattery(coap: CoapEndpoint) {
        val request = OutgoingRequestBuilder("/md/606", Method.GET)
            .expectSuccess(false).forceNonBlockwise(true).maxResendCount(0).build()
        disposables.add(coap.responseFor(request).timeout(5, TimeUnit.SECONDS)
            .flatMap { response -> response.body.asData().map { bytes ->
                if (response.responseCode.responseClass.toInt() != 2) throw IllegalStateException("Air rejected the battery read.")
                val fields = AirBatteryDecoder.decode(bytes)
                val level = fields["rawBatteryLevel"]
                require(level != null && level in 0..100) { "Air returned no valid battery level." }
                buildMap<String, Any> {
                    put("percent", level)
                    fields["rawOnCharger"]?.let { put("onCharger", it != 0) }
                    fields["rawVoltage"]?.let { put("voltageMillivolts", it) }
                }
            } }.subscribe({ finishSessionSuccess(coap, it) }, { error ->
                finishSessionError(coap, "BATTERY_READ_FAILED", error.message ?: "Could not read the battery level.")
            }))
    }

    /** Read-only research probe. Never exposes device identifiers or raw body. */
    private fun runBatteryAccessProbe(coap: CoapEndpoint) {
        val request = OutgoingRequestBuilder("/md/606", Method.GET)
            .expectSuccess(false).forceNonBlockwise(true).maxResendCount(0).build()
        disposables.add(coap.responseFor(request).timeout(5, TimeUnit.SECONDS)
            .flatMap { response -> response.body.asData().map { bytes ->
                mutableMapOf<String, Any>(
                    "responseClass" to response.responseCode.responseClass.toInt(),
                    "detail" to response.responseCode.detail.toInt(),
                    "bodyLength" to bytes.size,
                ).apply {
                    if (response.responseCode.responseClass.toInt() == 2) {
                        try { putAll(AirBatteryDecoder.decode(bytes)) }
                        catch (_: Exception) { put("decodeError", "Could not decode battery fields.") }
                    }
                }
            } }.subscribe({ result -> finishSessionSuccess(coap, result) }, { error ->
                finishSessionError(coap, "BATTERY_PROBE_FAILED", error.message ?: "Battery access probe failed.")
            }))
    }

    private fun intensityProbe() = OutgoingRequestBuilder("/settings", Method.PUT)
        .expectSuccess(false).forceNonBlockwise(true)
        .body(byteArrayOf(0x0a, 0x01, 0x01)).build()

    private fun readIntensity(coap: CoapEndpoint): Single<Int> =
        coap.responseFor(intensityProbe()).timeout(3, TimeUnit.SECONDS).flatMap { response ->
            response.body.asData().map { body ->
                if (response.responseCode.responseClass.toInt() != 2 ||
                    !(body.contentEquals(byteArrayOf(10, 4, 10, 2, 8, 2)) ||
                        body.contentEquals(byteArrayOf(10, 4, 10, 2, 8, 3)))
                ) throw IllegalStateException("Air returned an unsupported haptic setting.")
                body.last().toInt()
            }
        }

    private fun runReadHapticIntensity(coap: CoapEndpoint) {
        disposables.add(readIntensity(coap).subscribe({ intensity ->
            finishSessionSuccess(coap, mapOf("intensity" to intensity))
        }, { error ->
            finishSessionError(coap, "HAPTIC_READ_FAILED", error.message ?: "Could not read haptic intensity.")
        }))
    }

    private fun runSetHapticIntensity(coap: CoapEndpoint, requested: Int) {
        disposables.add(readIntensity(coap).flatMap { previous ->
            if (previous == requested) {
                Single.just(previous to false)
            } else {
                writeIntensity(coap, requested)
                    .flatMap { readIntensity(coap) }
                    .map { verified ->
                        if (verified != requested) throw IllegalStateException("Haptic intensity verification failed.")
                        previous to true
                    }
            }
        }.timeout(8, TimeUnit.SECONDS).subscribe({ (previous, changed) ->
            finishSessionSuccess(coap,
                mapOf(
                    "previousIntensity" to previous,
                    "currentIntensity" to requested,
                    "changed" to changed,
                    "verified" to true,
                ),
            )
        }, { error ->
            finishSessionError(coap, "HAPTIC_WRITE_FAILED", error.message ?: "Could not set haptic intensity.")
        }))
    }

    /** One physical pulse: toggle to the other intensity and intentionally leave it there. */
    private fun runSinglePulse(coap: CoapEndpoint) {
        disposables.add(readIntensity(coap).flatMap { previous ->
            val current = if (previous == 2) 3 else 2
            writeIntensity(coap, current)
                .flatMap { readIntensity(coap) }
                .map { verified ->
                    if (verified != current) throw IllegalStateException("Single-pulse verification failed.")
                    previous to current
                }
        }.timeout(8, TimeUnit.SECONDS).subscribe({ (previous, current) ->
            finishSessionSuccess(coap,
                mapOf(
                    "previousIntensity" to previous,
                    "currentIntensity" to current,
                    "changed" to true,
                    "verified" to true,
                ),
            )
        }, { error ->
            finishSessionError(coap, "HAPTIC_PULSE_FAILED", error.message ?: "Could not send a haptic pulse.")
        }))
    }

    /**
     * One pulse. At the wearer's strength (or a requested equal one), sends
     * [alternate, original] in one message so the alternate's pulse is
     * superseded and the original is retained. A different requested strength
     * is a single change: the Air cannot pulse at one strength and keep another.
     */
    private fun runTap(coap: CoapEndpoint, requested: Int?) {
        disposables.add(readIntensity(coap).flatMap { original ->
            val target = requested ?: original
            val body = if (target == original) AirHapticSettings.tap(original) else AirHapticSettings.write(target)
            postSettings(coap, body)
                .flatMap { readIntensity(coap) }
                .onErrorResumeNext { error: Throwable ->
                    // The message may have been applied even without a reply.
                    readIntensity(coap).onErrorResumeNext { Single.error(error) }
                }
                .flatMap { verified ->
                    if (verified == target) Single.just(original to target)
                    else writeIntensity(coap, original).retry(2).flatMap {
                        Single.error<Pair<Int, Int>>(IllegalStateException("Tap was not verified; the original strength was restored."))
                    }
                }
        }.subscribe({ (original, target) ->
            finishSessionSuccess(coap, mapOf(
                "intensity" to target,
                "previousIntensity" to original,
                "changed" to (target != original),
                "verified" to true,
            ))
        }, { error ->
            finishSessionError(coap, "HAPTIC_TAP_FAILED", error.message ?: "Could not send a haptic tap; check vibration strength in Google Health.")
        }))
    }

    private fun postSettings(coap: CoapEndpoint, body: ByteArray): Single<Boolean> {
        val request = OutgoingRequestBuilder("/settings", Method.POST)
            .expectSuccess(false).forceNonBlockwise(true).maxResendCount(0)
            .body(body).build()
        return coap.responseFor(request).timeout(3, TimeUnit.SECONDS).map { response ->
            if (response.responseCode.responseClass.toInt() != 2) throw IllegalStateException("Air rejected haptic setting change.")
            true
        }
    }

    private fun runBoundedHaptic(coap: CoapEndpoint) {
        disposables.add(readIntensity(coap).flatMap { original ->
                val alternate = if (original == 2) 3 else 2
                writeIntensity(coap, alternate)
                    .flatMap { Single.timer(800, TimeUnit.MILLISECONDS).map { original } }
                    .flatMap { prior -> writeIntensity(coap, prior).retry(2).map { prior } }
                    .flatMap { prior -> readIntensity(coap).map { verified ->
                        if (verified != prior) throw IllegalStateException("Original haptic setting was not verified restored.")
                        prior
                    } }.onErrorResumeNext { hapticError: Throwable ->
                        // A timed-out write may still have reached the Air. Restore the
                        // previously-read value before reporting the operation failure.
                        writeIntensity(coap, original).retry(2).onErrorResumeNext { restoreError: Throwable ->
                            Single.error(IllegalStateException(
                                "Haptic and restoration failed. Check vibration intensity in Google Health.",
                                restoreError,
                            ))
                        }.flatMap<Int> {
                            Single.error(IllegalStateException(
                                "Haptic failed; the prior setting was restored but not verified.",
                                hapticError,
                            ))
                        }
                    }
        // Each request has its own deadline. An outer short timeout could
        // dispose the recovery write; the operation-wide 45s guard remains.
        }.subscribe({ original ->
            finishSessionSuccess(coap, mapOf("originalIntensity" to original, "restored" to true, "verified" to true))
        }, { error -> finishSessionError(coap, "HAPTIC_FAILED", error.message ?: "Haptic command failed; check Google Health settings." ) }))
    }

    private fun writeIntensity(coap: CoapEndpoint, intensity: Int): Single<Boolean> =
        postSettings(coap, AirHapticSettings.write(intensity))

    @SuppressLint("MissingPermission")
    private fun writeNextPacket(activeGatt: BluetoothGatt?) {
        if (activeGatt == null || writeInFlight) return
        val characteristic = txCharacteristic ?: return
        val packet = synchronized(txQueue) { if (txQueue.isEmpty()) null else txQueue.removeFirst() } ?: return
        writeInFlight = true
        val accepted = if (Build.VERSION.SDK_INT >= 33) {
            activeGatt.writeCharacteristic(characteristic, packet, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION") characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            @Suppress("DEPRECATION") characteristic.value = packet
            @Suppress("DEPRECATION") activeGatt.writeCharacteristic(characteristic)
        }
        if (!accepted) {
            writeInFlight = false
            synchronized(txQueue) { txQueue.addFirst(packet) }
        }
    }

    private fun finishSessionSuccess(coap: CoapEndpoint, value: Map<String, Any>) {
        sessionCallbacks.dispatch(coap) { finishSuccess(value) }
    }

    private fun finishSessionError(coap: CoapEndpoint, code: String, message: String) {
        sessionCallbacks.dispatch(coap) { finishError(code, message) }
    }

    @SuppressLint("MissingPermission")
    private fun finishSuccess(value: Map<String, Any>) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { finishSuccess(value) }
            return
        }
        val reply = pendingResult ?: return
        if (handoff != null) return
        activeOperation = null
        handOffTrackerChannel {
            pendingResult = null
            reply.success(value)
        }
    }

    @SuppressLint("MissingPermission")
    private fun finishError(code: String, message: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { finishError(code, message) }
            return
        }
        val reply = pendingResult ?: return
        handoff?.let { pending ->
            // The link failed or the handoff errored: release now.
            completeHandoff(pending, "link_lost")
            return
        }
        if (activeOperation == Operation.CaptureSecureEvents) {
            secureEvents?.success((secureCapture?.summary() ?: mapOf("observationMillis" to 0L)) + mapOf(
                "kind" to "interrupted", "errorCode" to code,
                "receivedAtMillis" to System.currentTimeMillis(),
            ))
        }
        activeOperation = null
        val reportError = {
            pendingResult = null
            reply.error(code, message, null)
        }
        if (code.startsWith("GATT_")) {
            closeTransport()
            reportError()
        } else {
            handOffTrackerChannel(reportError)
        }
    }

    /**
     * Returns the tracker channel to Google Health before releasing the link.
     *
     * The Air has one Gattlink/DTLS channel, shared by every client on the
     * same BLE link. Our session leaves Google Health's Gattlink out of sync,
     * and it otherwise only recovers through its ~60 s ack-timeout watchdog.
     * A fresh Gattlink reset makes the Air offer its normal identity, which
     * this SDK cannot resolve; the Air then resets the link for every client.
     * Google Health obeys that reset, so we go silent at that point and let it
     * complete the handshake alone.
     */
    @SuppressLint("MissingPermission")
    private fun handOffTrackerChannel(done: () -> Unit) {
        val activeGatt = gatt
        val device = activeGatt?.device
        if (activeGatt == null || device == null || stack == null || txCharacteristic == null) {
            closeTransport()
            done()
            return
        }
        val pending = Handoff(done)
        handoff = pending
        timeout?.let(handler::removeCallbacks)
        timeout = null
        val deadline = Runnable { completeHandoff(pending, "deadline") }
        pending.deadline = deadline
        handler.postDelayed(deadline, HANDOFF_TIMEOUT_MS)
        try {
            disposeSession()
            val sink = TxSink()
            txSink = sink
            val source = RxSource()
            rxSource = source
            disposables.add(sink.dataObservable.subscribe({ data ->
                // Checked on the native loop thread, so nothing queued after
                // the Air's reset can reach the tracker.
                if (pending.muted) return@subscribe
                gattCallbacks.dispatch(activeGatt) {
                    if (handoff !== pending || txSink !== sink || pending.muted) return@dispatch
                    synchronized(txQueue) { txQueue.addLast(data) }
                    writeNextPacket(activeGatt)
                }
            }, { handler.post { completeHandoff(pending, "failed") } }))
            val kick = Stack(BluetoothAddressNodeKey(device.address), sink.thisPointer, source.thisPointer, DtlsSocketNetifGattlink(), false, RefusingTlsKeyResolver)
            stack = kick
            disposables.add(kick.stackEventObservable.subscribe({ event ->
                android.util.Log.d("FitbitAir", "handoff stack event ${event.javaClass.simpleName}")
                // The Air reset the link after the refused handshake. Go silent
                // before replying so Google Health alone resynchronizes.
                if (event is StackEvent.GattlinkSessionResent) {
                    pending.muted = true
                    handler.post { completeHandoff(pending, "reset_observed") }
                }
            }, { }))
            disposables.add(kick.dtlsEventObservable.subscribe({ state ->
                android.util.Log.d("FitbitAir", "handoff DTLS ${state.state} error=${state.lastError}")
            }, { }))
            kick.start()
        } catch (error: Throwable) {
            completeHandoff(pending, "failed")
        }
    }

    /** [outcome] is a fixed label recorded in transport diagnostics. */
    private fun completeHandoff(pending: Handoff, outcome: String) {
        if (handoff !== pending) return
        android.util.Log.d("FitbitAir", "handoff $outcome")
        pending.muted = true
        pending.deadline?.let(handler::removeCallbacks)
        handoff = null
        handoffOutcome = outcome
        closeTransport()
        pending.done()
    }

    /** Refuses every identity, so the Air's next handshake fails and it resets the link. */
    private object RefusingTlsKeyResolver : TlsKeyResolver() {
        override fun resolveKey(nodeKey: NodeKey<*>, keyId: ByteArray): ByteArray? = null
    }

    private class Handoff(val done: () -> Unit) {
        @Volatile var muted = false
        var deadline: Runnable? = null
    }

    @SuppressLint("MissingPermission")
    private fun closeTransport() {
        handoff?.let { pending ->
            handoff = null
            pending.muted = true
            pending.deadline?.let(handler::removeCallbacks)
            handoffOutcome = "interrupted"
        }
        val trace = AirCleanupTrace()
        handoffOutcome?.let { trace.note("channelHandoff", it) }
        handoffOutcome = null
        val closingGatt = gatt
        gatt = null
        val closingEndpoint = endpoint
        endpoint = null
        val record = cleanupOperation != null || closingGatt != null || scanCallback != null
        trace.step("stopScan", scanCallback != null) {
            scanCallback?.let { callback ->
                val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                manager.adapter?.bluetoothLeScanner?.stopScan(callback)
            }
        }
        scanCallback = null
        infoReads.clear()
        deviceInfo.clear()
        timeout?.let(handler::removeCallbacks)
        timeout = null
        handshakeDeadline?.let(handler::removeCallbacks)
        handshakeDeadline = null
        warmSilenceCheck?.let(handler::removeCallbacks)
        warmSilenceCheck = null
        warmLinkFallback?.let { trace.note("warmLinkFallback", it) }
        warmLinkFallback = null
        operationCallback = null
        holdTick?.let(handler::removeCallbacks)
        holdTick = null
        channelActivity?.let { channelHoldOutcome = it.outcome(SystemClock.elapsedRealtime()) }
        channelActivity = null
        channelHoldOutcome?.let { trace.note("channelHold", it) }
        channelHoldOutcome = null
        trace.step("subscriptions") { disposables.clear() }
        trace.step("secureCapture", secureCapture != null) { secureCapture?.close() }; secureCapture = null
        trace.step("endpoint", closingEndpoint != null) { closingEndpoint?.close() }
        trace.step("stack", stack != null) { stack?.close() }; stack = null
        trace.step("rxSource", rxSource != null) { rxSource?.close() }; rxSource = null
        trace.step("txSink", txSink != null) { txSink?.close() }; txSink = null
        txCharacteristic = null
        synchronized(txQueue) { txQueue.clear() }
        writeInFlight = false
        trace.step("linkPriority", closingGatt != null && linkBoosted) {
            closingGatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER)
        }
        linkBoosted = false
        val keepWarm = closingGatt != null && closingGatt === warmLink?.gatt
        if (keepWarm) {
            // The warm link stays connected for the next command.
            warmLink?.delegate = null
            trace.note("warmLink", "kept")
        } else {
            trace.step("gattDisconnect", closingGatt != null) { closingGatt?.disconnect() }
            trace.step("gattClose", closingGatt != null) { closingGatt?.close() }
        }
        usingWarmLink = false
        AirTransportLease.release(transportOwner)
        if (warmLinkWanted && warmLink == null) {
            handler.postDelayed({ if (warmLink == null) rebuildWarmLink("after_fallback") }, 1_000L)
        }
        if (record) AirTransportDiagnostics.record(cleanupOperation ?: "Scan", trace.snapshot())
        cleanupOperation = null
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        secureEventChannel.setStreamHandler(null)
        secureEvents = null
        heartRateChannel.setStreamHandler(null)
        heartRateEvents = null
        deviceEventChannel.setStreamHandler(null)
        connectionChannel.setStreamHandler(null)
        connectionWatcher?.close()
        connectionWatcher = null
        channelActivityChannel.setStreamHandler(null)
        channelMonitor?.close()
        channelMonitor = null
        stopWarmLink()
        diagnosticEvents = null
        eventMonitor?.close()
        eventMonitor = null
        closeTransport()
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

    companion object {
        private const val CHANNEL = "fitbit_air"
        private const val PERMISSION_REQUEST = 49217
        private const val OPERATION_TIMEOUT_MS = 45_000L
        private const val HANDOFF_TIMEOUT_MS = 10_000L
        private const val HANDSHAKE_RETRY_MS = 6_000L
        /** A working link delivers the Air's Gattlink answer within about a second. */
        private const val WARM_SILENCE_MS = 2_000L
        private const val WARM_WATCHDOG_MS = 60_000L
        private const val WARM_REBUILD_MIN_MS = 300_000L
        private const val HOLD_TICK_MS = 100L
        private const val LOST_RACE_RETRY_MS = 1_500L
        /** DTLS error observed when Google Health answered the same handshake. */
        private const val LOST_HANDSHAKE_RACE = -1
        private const val MAX_HANDSHAKE_ATTEMPTS = 2
        private val TRACKER_SERVICE = UUID.fromString("abbaff00-e56a-484c-b832-8b17cf6cbfe8")
        private val TRACKER_TX = UUID.fromString("abbaff01-e56a-484c-b832-8b17cf6cbfe8")
        private val TRACKER_RX = UUID.fromString("abbaff02-e56a-484c-b832-8b17cf6cbfe8")
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val HEART_RATE_SERVICE = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        private val HEART_RATE_MEASUREMENT = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        private val INFO_CHARACTERISTICS = mapOf(
            UUID.fromString("abbafd01-e56a-484c-b832-8b17cf6cbfe8") to "connectionConfiguration",
            UUID.fromString("abbafd02-e56a-484c-b832-8b17cf6cbfe8") to "connectionStatus",
            UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb") to "batteryPercent",
            UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb") to "model",
            UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb") to "firmware",
            UUID.fromString("00002a27-0000-1000-8000-00805f9b34fb") to "hardware",
            UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb") to "manufacturer",
        )
    }

    private sealed interface Operation {
        data object ReadHeartRate : Operation
        data object ReadDeviceInfo : Operation
        data object PreviewHaptic : Operation
        data object ReadHapticIntensity : Operation
        data object ProbeBatteryAccess : Operation
        data object ReadBattery : Operation
        data object ReadSignalStrength : Operation
        data object ProbeReadOnlyResources : Operation
        data object CaptureSecureEvents : Operation
        data class SetHapticIntensity(val intensity: Int) : Operation
        data object SinglePulse : Operation
        data class Tap(val intensity: Int?) : Operation
    }
}
