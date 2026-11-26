package dev.airpulse.fitbit_air

import android.Manifest
import android.annotation.SuppressLint
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
import android.util.Log
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
import io.reactivex.Single
import io.reactivex.disposables.CompositeDisposable
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The Fitbit Air, without Flutter: haptics, device reads, the warm link and
 * the research streams. The Flutter plugin is a thin adapter over this class,
 * and Android apps can use it directly.
 *
 * Call everything on the main thread; replies and events arrive there too.
 * One operation runs at a time (others get `BUSY`). The client does not ask
 * for permissions: request [requiredPermissions] first, or calls fail with
 * `BLUETOOTH_PERMISSION`. Call [close] when done.
 */
internal class FitbitAirClient(context: Context) {
    private val appContext: Context = context.applicationContext
    private var secureEvents: AirEventSink? = null
    private var secureCapture: AirSecureEventCapture? = null
    private var secureCaptureSeconds = 30
    @Volatile private var ringStopRequested = false
    private var diagnosticEvents: AirEventSink? = null
    private var eventMonitor: AirEventMonitor? = null
    private var finder: AirFinder? = null
    private var connectionWatcher: AirConnectionWatcher? = null
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
    /** Set when a command on the warm link got no reply; the link is replaced at cleanup. */
    private var warmLinkUnresponsive = false
    private var heartRateEvents: AirEventSink? = null
    private var pendingResult: AirReply? = null
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
    private val txQueue = AirTxQueue()
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

    init {
        BundledNativeLoader.initializeEarly()
    }

    /** True while an operation or the device-event stream holds the transport. */
    val isBusy: Boolean get() = pendingResult != null || diagnosticEvents != null

    // Commands

    /** Recent transport cleanups and link recoveries, for bug reports. */
    fun transportDiagnostics(): Map<String, Any> = AirTransportDiagnostics.snapshot()

    /**
     * Cancels the running operation. A ring stops (and restores); a haptic
     * write that is being verified cannot be cancelled (`CANNOT_CANCEL_WRITE`).
     */
    fun cancelOperation(reply: AirReply) {
        if (diagnosticEvents != null) {
            eventMonitor?.close()
            eventMonitor = null
            diagnosticEvents?.endOfStream()
            diagnosticEvents = null
            AirTransportLease.release(transportOwner)
        }
        if (activeOperation is Operation.Ring) {
            // Cancelling a ring is stopping it; it then restores and finishes normally.
            ringStopRequested = true
            reply.success(null)
            return
        }
        if (activeOperation == Operation.PreviewHaptic ||
            activeOperation == Operation.SinglePulse || activeOperation is Operation.Tap ||
            activeOperation is Operation.SetHapticIntensity) {
            reply.error("CANNOT_CANCEL_WRITE", "Wait for the haptic write and verification to finish.")
            return
        }
        finishError("CANCELLED", "Fitbit Air operation cancelled.")
        reply.success(null)
    }

    /** Heart-rate sensors nearby, scanned for 8 s: `{devices: [{deviceId, name, rssi}]}`. */
    fun scanHeartRateDevices(reply: AirReply) = withBluetoothPermission(reply, needsScan = true) { startHeartRateScan(reply) }

    /** One standard heart-rate measurement, from the Air or from [deviceId]. */
    fun readHeartRate(deviceId: String?, reply: AirReply) = withBluetoothPermission(reply) {
        if (deviceId != null && !BluetoothAdapter.checkBluetoothAddress(deviceId)) {
            reply.error("INVALID_DEVICE", "Select a discovered heart-rate sensor first.")
        } else startOperation(reply, Operation.ReadHeartRate, deviceId)
    }

    fun readDeviceInfo(reply: AirReply) = withBluetoothPermission(reply) { startOperation(reply, Operation.ReadDeviceInfo) }

    /** Whether a Fitbit Air is bonded to this phone (paired in Google Health). */
    fun isAirPaired(reply: AirReply) = withBluetoothPermission(reply) { reply.success(findBondedAir() != null) }

    /** Whether the paired Air is connected to this phone right now. */
    fun isAirConnected(reply: AirReply) = withBluetoothPermission(reply) {
        val device = findBondedAir()
        reply.success(device != null && AirConnectionWatcher.isConnected(appContext, device))
    }

    fun vibrate(reply: AirReply) = withBluetoothPermission(reply) { startOperation(reply, Operation.PreviewHaptic) }

    fun getHapticIntensity(reply: AirReply) = withBluetoothPermission(reply) { startOperation(reply, Operation.ReadHapticIntensity) }

    /** [intensity] 2 (low) or 3 (high). */
    fun setHapticIntensity(intensity: Int?, reply: AirReply) {
        if (intensity !in INTENSITIES) {
            reply.error("INVALID_INTENSITY", "Haptic intensity must be 2 (low) or 3 (high).")
        } else withBluetoothPermission(reply) { startOperation(reply, Operation.SetHapticIntensity(intensity!!)) }
    }

    /**
     * Keeps a listen-only link to the Air between commands, so commands skip
     * connection setup. Google Health keeps syncing.
     */
    fun keepLinkWarm(enabled: Boolean, reply: AirReply) {
        if (!enabled) {
            stopWarmLink()
            reply.success(null)
        } else withBluetoothPermission(reply) {
            val device = findBondedAir()
            if (device == null) {
                reply.error("AIR_NOT_FOUND", "Pair your Air in Google Health first.")
            } else {
                startWarmLink(device)
                reply.success(null)
            }
        }
    }

    fun readBattery(reply: AirReply) = withBluetoothPermission(reply) { startOperation(reply, Operation.ReadBattery) }

    fun readSignalStrength(reply: AirReply) = withBluetoothPermission(reply) { startOperation(reply, Operation.ReadSignalStrength) }

    fun probeBatteryAccess(reply: AirReply) = withBluetoothPermission(reply) { startOperation(reply, Operation.ProbeBatteryAccess) }

    fun probeReadOnlyResources(reply: AirReply) = withBluetoothPermission(reply) { startOperation(reply, Operation.ProbeReadOnlyResources) }

    fun pulse(reply: AirReply) = withBluetoothPermission(reply) { startOperation(reply, Operation.SinglePulse) }

    /** One tap at [intensity] (2 or 3), or at the wearer's own strength when null. */
    fun tap(intensity: Int?, reply: AirReply) {
        if (intensity != null && intensity !in INTENSITIES) {
            reply.error("INVALID_INTENSITY", "Haptic intensity must be 2 (low) or 3 (high).")
        } else withBluetoothPermission(reply) { startOperation(reply, Operation.Tap(intensity)) }
    }

    /**
     * Pulses inside one secure session every [gapMillis] until [stopRing],
     * [cancelOperation], [maxMillis] or [pulses].
     */
    fun ring(intensity: Int?, gapMillis: Long = 700, maxMillis: Long = 45_000, pulses: Int? = null, reply: AirReply) {
        when {
            pulses != null && pulses !in 1..100 ->
                reply.error("INVALID_PULSES", "A ring is 1-100 pulses, or unbounded.")
            intensity != null && intensity !in INTENSITIES ->
                reply.error("INVALID_INTENSITY", "Haptic intensity must be 2 (low) or 3 (high).")
            gapMillis !in MIN_RING_GAP_MS..5_000L ->
                reply.error("INVALID_GAP", "Pulses must be ${MIN_RING_GAP_MS}-5000 ms apart.")
            maxMillis !in 1_000L..300_000L ->
                reply.error("INVALID_DURATION", "A ring must last 1-300 seconds.")
            else -> withBluetoothPermission(reply) {
                ringStopRequested = false
                startOperation(reply, Operation.Ring(intensity, gapMillis, maxMillis, pulses))
            }
        }
    }

    /**
     * Stops the running ring; never refused, since the wearer is being
     * buzzed. A stray stop must not end a later, unrelated ring.
     */
    fun stopRing() {
        if (activeOperation is Operation.Ring) ringStopRequested = true
    }

    // Streams. Each listen replaces the previous listener of that stream.

    /** Heart-rate measurements until [cancelHeartRate]. */
    fun listenHeartRate(deviceId: String?, events: AirEventSink) {
        if (deviceId != null && !BluetoothAdapter.checkBluetoothAddress(deviceId)) {
            events.error("INVALID_DEVICE", "Select a discovered heart-rate sensor first.")
            events.endOfStream()
            return
        }
        if (isBusy) {
            events.error("BUSY", "Another Bluetooth operation is running.")
            events.endOfStream()
            return
        }
        heartRateEvents = events
        val streamResult = object : AirReply {
            override fun success(value: Any?) { }
            override fun error(code: String, message: String) {
                if (heartRateEvents === events) heartRateEvents = null
                events.error(code, message)
                events.endOfStream()
            }
        }
        withBluetoothPermission(streamResult) {
            if (heartRateEvents === events) startOperation(streamResult, Operation.ReadHeartRate, deviceId)
        }
    }

    fun cancelHeartRate() {
        heartRateEvents = null
        if (activeOperation == Operation.ReadHeartRate) {
            // Do not emit an error into a stream the consumer cancelled.
            pendingResult = null
            activeOperation = null
            closeTransport()
        }
    }

    /** Secure device events for [durationSeconds] (30-90), then a summary. */
    fun listenSecureEvents(durationSeconds: Any?, events: AirEventSink) {
        if (isBusy || secureEvents != null) {
            events.error("BUSY", "Another Bluetooth operation is running.")
            events.endOfStream()
            return
        }
        val requestedSeconds = durationSeconds ?: 30
        if (requestedSeconds !is Int || requestedSeconds !in 30..90) {
            events.error("INVALID_ARGUMENT", "durationSeconds must be an integer from 30 to 90.")
            events.endOfStream()
            return
        }
        secureCaptureSeconds = requestedSeconds
        secureEvents = events
        val reply = object : AirReply {
            override fun success(value: Any?) {
                if (secureEvents === events) {
                    events.success((value as Map<*, *>) + mapOf("kind" to "complete", "receivedAtMillis" to System.currentTimeMillis()))
                    secureEvents = null
                    events.endOfStream()
                }
            }
            override fun error(code: String, message: String) {
                if (secureEvents === events) {
                    secureEvents = null
                    events.error(code, message)
                    events.endOfStream()
                }
            }
        }
        withBluetoothPermission(reply) {
            if (secureEvents === events) startOperation(reply, Operation.CaptureSecureEvents)
        }
    }

    fun cancelSecureEvents() {
        secureEvents = null
        if (activeOperation == Operation.CaptureSecureEvents) {
            pendingResult = null
            activeOperation = null
            closeTransport()
        }
    }

    /** Proximity readings for finding the Air, until [cancelFinder]. */
    fun listenFinder(events: AirEventSink) {
        cancelFinder()
        withBluetoothPermission(streamReply(events)) {
            val device = findBondedAir()
            if (device == null) {
                events.error("AIR_NOT_FOUND", "Pair your Air in Google Health first.")
                events.endOfStream()
            } else {
                finder = AirFinder(appContext, device, events).also { it.start() }
            }
        }
    }

    fun cancelFinder() {
        finder?.close()
        finder = null
    }

    /** The Air's connection state, now and on every change, until [cancelConnection]. */
    fun listenConnection(events: AirEventSink) {
        withBluetoothPermission(streamReply(events)) {
            val device = findBondedAir() ?: run {
                events.error("AIR_NOT_FOUND", "Pair your Air in Google Health first.")
                events.endOfStream()
                return@withBluetoothPermission
            }
            connectionWatcher?.close()
            connectionWatcher = AirConnectionWatcher(appContext, device) { events.success(it) }
                .also { it.start() }
        }
    }

    fun cancelConnection() {
        connectionWatcher?.close()
        connectionWatcher = null
    }

    /** Google Health's traffic on the tracker channel, until [cancelChannelActivity]. */
    fun listenChannelActivity(events: AirEventSink) {
        withBluetoothPermission(streamReply(events)) {
            val device = findBondedAir() ?: run {
                events.error("AIR_NOT_FOUND", "Pair your Air in Google Health first.")
                events.endOfStream()
                return@withBluetoothPermission
            }
            channelMonitor?.close()
            val monitor = AirChannelMonitor(appContext, { events.success(it) }) { code, message ->
                events.error(code, message)
                events.endOfStream()
            }
            channelMonitor = monitor
            monitor.start(device)
        }
    }

    fun cancelChannelActivity() {
        channelMonitor?.close()
        channelMonitor = null
    }

    /** Standard device events over a connection of its own, until [cancelDeviceEvents]. */
    fun listenDeviceEvents(events: AirEventSink) {
        if (isBusy) {
            events.error("BUSY", "Another Bluetooth operation is running.")
            events.endOfStream()
            return
        }
        diagnosticEvents = events
        val failed = object : AirReply {
            override fun success(value: Any?) { }
            override fun error(code: String, message: String) {
                if (diagnosticEvents === events) {
                    eventMonitor?.close()
                    eventMonitor = null
                    diagnosticEvents = null
                    AirTransportLease.release(transportOwner)
                }
                events.error(code, message)
                events.endOfStream()
            }
        }
        withBluetoothPermission(failed) {
            if (diagnosticEvents !== events) return@withBluetoothPermission
            val device = findBondedAir()
            if (device == null) {
                failed.error("AIR_NOT_FOUND", "Pair your Air in Google Health first.")
            } else {
                if (!AirTransportLease.tryAcquire(transportOwner)) {
                    failed.error("BUSY", "Another Fitbit Air client owns the Bluetooth transport.")
                    return@withBluetoothPermission
                }
                val monitor = AirEventMonitor(appContext, { event ->
                    if (diagnosticEvents === events) events.success(event)
                }, { code, message -> failed.error(code, message) })
                eventMonitor = monitor
                try {
                    monitor.start(device)
                } catch (error: Exception) {
                    failed.error("EVENT_CONNECT", error.message ?: "Could not start diagnostic connection.")
                }
            }
        }
    }

    fun cancelDeviceEvents() {
        val wasActive = diagnosticEvents != null
        diagnosticEvents = null
        eventMonitor?.close()
        eventMonitor = null
        if (wasActive) AirTransportLease.release(transportOwner)
    }

    /** Ends every stream and operation and releases the transport and the warm link. */
    fun close() {
        cancelFinder()
        secureEvents = null
        heartRateEvents = null
        cancelConnection()
        cancelChannelActivity()
        stopWarmLink()
        diagnosticEvents = null
        eventMonitor?.close()
        eventMonitor = null
        closeTransport()
    }

    /** Reports permission failures of a passive stream as stream errors. */
    private fun streamReply(events: AirEventSink) = object : AirReply {
        override fun success(value: Any?) { }
        override fun error(code: String, message: String) {
            events.error(code, message)
            events.endOfStream()
        }
    }

    private fun withBluetoothPermission(reply: AirReply, needsScan: Boolean = false, action: () -> Unit) {
        if (hasPermissions(appContext, needsScan)) action()
        else reply.error("BLUETOOTH_PERMISSION", "Nearby devices permission is required.")
    }

    @SuppressLint("MissingPermission")
    private fun startHeartRateScan(result: AirReply) {
        if (pendingResult != null || diagnosticEvents != null) {
            result.error("BUSY", "A Fitbit Air operation is already running.")
            return
        }
        val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val scanner = manager.adapter?.bluetoothLeScanner
        if (scanner == null) {
            result.error("BLUETOOTH_OFF", "Enable Bluetooth before scanning.")
            return
        }
        if (!AirTransportLease.tryAcquire(transportOwner)) {
            result.error("BUSY", "Another Fitbit Air client owns the Bluetooth transport.")
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
    private fun startOperation(result: AirReply, operation: Operation, deviceId: String? = null) {
        if (pendingResult != null || diagnosticEvents != null) {
            result.error("BUSY", "A Fitbit Air operation is already running.")
            return
        }
        val device = if (deviceId == null) findBondedAir() else {
            val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            manager.adapter?.getRemoteDevice(deviceId)
        }
        if (device == null) {
            result.error("AIR_NOT_FOUND", "No bonded Fitbit Air found. Pair it in Google Health first.")
            return
        }
        if (!AirTransportLease.tryAcquire(transportOwner)) {
            result.error("BUSY", "Another Fitbit Air client owns the Bluetooth transport.")
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
            is Operation.Ring -> "Ring"
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
        handler.postDelayed(deadline, when {
            operation == Operation.CaptureSecureEvents -> (secureCaptureSeconds + 15) * 1000L
            // A ring holds the session for as long as it pulses, by design.
            operation is Operation.Ring -> operation.maxMillis + OPERATION_TIMEOUT_MS
            else -> OPERATION_TIMEOUT_MS
        })
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
                    // Otherwise the operation would wait for a discovery that never starts until it times out.
                    if (!g.discoverServices()) finishError("GATT_DISCOVERY", "Android did not start service discovery.")
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
                if (g !== gatt || !txQueue.written(g)) return@dispatch
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
                    } else if (pendingResult == null && link.delegate == null) {
                        checkIdleWarmLink(link, now)
                    }
                    handler.postDelayed(this, WARM_WATCHDOG_MS)
                }
            }
            warmLinkWatchdog = watchdog
            handler.postDelayed(watchdog, WARM_WATCHDOG_MS)
        }
    }

    /** A connected warm link that hears nothing subscribes again, then is replaced ([AirWarmLink.idle]). */
    private fun checkIdleWarmLink(link: AirWarmLink, now: Long) {
        when (link.idleCheck(now)) {
            AirWarmLink.Idle.OK -> Unit
            AirWarmLink.Idle.RESUBSCRIBE -> {
                android.util.Log.d("FitbitAir", "warm link heard nothing; subscribing again")
                AirTransportDiagnostics.recordLinkRecovery("resubscribed_deaf")
                if (!link.resubscribe()) rebuildWarmLink("resubscribe_failed")
            }
            AirWarmLink.Idle.REBUILD -> rebuildWarmLink("deaf")
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
                    txQueue.add(data)
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
        txQueue.clear()
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
            is Operation.Ring -> runRing(coap, operation)
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
                ) throw AirCommandFailure.Answered("Air returned an unsupported haptic setting.")
                body.last().toInt()
            }
        }

    private fun runReadHapticIntensity(coap: CoapEndpoint) {
        disposables.add(readIntensity(coap).subscribe({ intensity ->
            finishSessionSuccess(coap, mapOf("intensity" to intensity))
        }, { error ->
            finishSessionError(coap, AirCommandFailure.code(error, sent = false, answeredCode = "HAPTIC_READ_FAILED"),
                error.message ?: "Could not read haptic intensity.")
        }))
    }

    private fun runSetHapticIntensity(coap: CoapEndpoint, requested: Int) {
        var sent = false
        disposables.add(readIntensity(coap).flatMap { previous ->
            if (previous == requested) {
                Single.just(previous to false)
            } else {
                Single.defer { sent = true; writeIntensity(coap, requested) }
                    .flatMap { readIntensity(coap) }
                    .map { verified ->
                        if (verified != requested) throw AirCommandFailure.Answered("Haptic intensity verification failed.")
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
            finishSessionError(coap, AirCommandFailure.code(error, sent, answeredCode = "HAPTIC_WRITE_FAILED"),
                error.message ?: "Could not set haptic intensity.")
        }))
    }

    /** One physical pulse: toggle to the other intensity and intentionally leave it there. */
    private fun runSinglePulse(coap: CoapEndpoint) {
        var sent = false
        disposables.add(readIntensity(coap).flatMap { previous ->
            val current = if (previous == 2) 3 else 2
            Single.defer { sent = true; writeIntensity(coap, current) }
                .flatMap { readIntensity(coap) }
                .map { verified ->
                    if (verified != current) throw AirCommandFailure.Answered("Single-pulse verification failed.")
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
            finishSessionError(coap, AirCommandFailure.code(error, sent, answeredCode = "HAPTIC_PULSE_FAILED"),
                error.message ?: "Could not send a haptic pulse.")
        }))
    }

    /**
     * One pulse. At the wearer's strength (or a requested equal one), sends
     * [alternate, original] in one message so the alternate's pulse is
     * superseded and the original is retained. A different requested strength
     * is a single change: the Air cannot pulse at one strength and keep another.
     */
    private fun runTap(coap: CoapEndpoint, requested: Int?) {
        var sent = false
        disposables.add(readIntensity(coap).flatMap { original ->
            val target = requested ?: original
            val body = if (target == original) AirHapticSettings.tap(original) else AirHapticSettings.write(target)
            var writeError: Throwable? = null
            var readError: Throwable? = null
            Single.defer { sent = true; postSettings(coap, body) }
                .map { AirTapCheck.Write.ACCEPTED }
                .onErrorReturn { error ->
                    writeError = error
                    if (AirCommandFailure.answered(error)) AirTapCheck.Write.REJECTED else AirTapCheck.Write.NO_REPLY
                }
                .flatMap { write ->
                    // Read once more if the first read goes unanswered; -1: the strength stays unknown.
                    readIntensity(coap).onErrorResumeNext { readIntensity(coap) }
                        .onErrorReturn { error -> readError = error; -1 }
                        .map { write to it.takeIf { value -> value >= 0 } }
                }
                .flatMap { (write, readBack) ->
                    when (val outcome = AirTapCheck.after(original, target, write, readBack)) {
                        AirTapCheck.Verified -> Single.just(original to target)
                        is AirTapCheck.Failed -> {
                            val failure: Throwable = when (outcome.reason) {
                                AirTapCheck.Reason.REJECTED -> AirCommandFailure.Answered("Air rejected the tap.")
                                AirTapCheck.Reason.NOT_VERIFIED -> AirCommandFailure.Answered("Tap was not verified.")
                                // Not an answer from the Air: reported as HAPTIC_UNCONFIRMED, never resent.
                                AirTapCheck.Reason.UNCONFIRMED -> writeError ?: readError
                                    ?: IllegalStateException("Tap was not confirmed.")
                            }
                            val restore: Single<Boolean> = if (outcome.restore) writeIntensity(coap, original).retry(2)
                                else Single.just(true)
                            restore.flatMap { Single.error<Pair<Int, Int>>(failure) }
                        }
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
            // Only an unexpected answer from the Air is HAPTIC_TAP_FAILED; silence is temporary.
            finishSessionError(coap, AirCommandFailure.code(error, sent, answeredCode = "HAPTIC_TAP_FAILED"),
                error.message ?: "Could not send a haptic tap; check vibration strength in Google Health.")
        }))
    }

    /**
     * Repeated pulses at one strength, inside a single secure session.
     *
     * A one-shot [runTap] spends most of its time on the session: connect,
     * handshake, verify, hand the channel back. Repeating that per pulse puts
     * seconds between buzzes, which reads as a reminder rather than a ring.
     * Here the session is opened once and each pulse is a single `/settings`
     * POST, so pulses can be a few hundred milliseconds apart.
     *
     * Every pulse posts `[alternate, target]`: the Air supersedes the first
     * change and plays the second, so each pulse has the same strength and
     * leaves `target` in place rather than alternating low and high.
     *
     * The Air plays one pulse per *change it notices*, so a gap shorter than a
     * pulse merges them into fewer, longer buzzes; [MIN_RING_GAP_MS] is a floor,
     * not a guarantee that every gap above it is felt as a separate pulse.
     *
     * A dropped pulse does not end the ring: the wearer would rather feel the
     * next one than have the call go quiet. [MAX_RING_DROPS_IN_A_ROW] dropped
     * in a row do, as the session is then gone. [stopRing], the duration and
     * a lost link end it too. The wearer's strength is restored on every path
     * that still has a link; a link lost mid-ring can leave `target` in place.
     */
    private fun runRing(coap: CoapEndpoint, operation: Operation.Ring) {
        val until = SystemClock.elapsedRealtime() + operation.maxMillis
        var sent = 0
        var pulses = 0
        var dropsInARow = 0
        fun ringing() = !ringStopRequested && SystemClock.elapsedRealtime() < until &&
            (operation.pulses == null || sent < operation.pulses)
        disposables.add(readIntensity(coap).flatMap { original ->
            val target = operation.intensity ?: original
            val body = AirHapticSettings.tap(target)
            fun pulse(): Single<Int> = Single.defer {
                // Checked here, not only after the gap: a stop that lands while
                // a pulse is being prepared must not still buzz the wrist after
                // the call is over.
                if (!ringing()) return@defer Single.just(pulses)
                sent++
                val sentAt = SystemClock.elapsedRealtime()
                postSettings(coap, body).map {
                    pulses++
                    dropsInARow = 0
                    Log.d("FitbitAir", "ring pulse $pulses rtt=${SystemClock.elapsedRealtime() - sentAt}ms")
                    pulses
                }.onErrorResumeNext { error: Throwable ->
                    Log.w("FitbitAir", "ring pulse dropped after ${SystemClock.elapsedRealtime() - sentAt}ms: ${error.message}")
                    // One dropped pulse is skipped. Several in a row mean the session is
                    // gone; ringing on would hold the channel from Google Health for nothing.
                    if (++dropsInARow >= MAX_RING_DROPS_IN_A_ROW) Single.error(error) else Single.just(pulses)
                }
            }
                .flatMap { count ->
                    if (!ringing()) Single.just(count)
                    else Single.timer(operation.gapMillis, TimeUnit.MILLISECONDS)
                        .flatMap { if (ringing()) pulse() else Single.just(pulses) }
                }
            // The last pulse left `target` behind; put the wearer's own strength
            // back so the next notification feels as they set it.
            val restore: Single<Boolean> = if (target == original) Single.just(true)
                else writeIntensity(coap, original).retry(2)
                    .flatMap { readIntensity(coap) }
                    .map { it == original }
                    .onErrorReturnItem(false)
            pulse().flatMap { count -> restore.map { count to it } }
                // Ended by failure: still try to restore before reporting it.
                .onErrorResumeNext { error: Throwable -> restore.flatMap { Single.error<Pair<Int, Boolean>>(error) } }
        }.subscribe({ (pulses, restored) ->
            finishSessionSuccess(coap, mapOf("pulses" to pulses, "restored" to restored))
        }, { error ->
            // Silence from the Air is temporary (AIR_NO_REPLY before any pulse, else
            // HAPTIC_UNCONFIRMED); only an unexpected answer is RING_FAILED.
            finishSessionError(coap, AirCommandFailure.code(error, sent > 0, answeredCode = "RING_FAILED"),
                error.message ?: "Could not ring the Air.")
        }))
    }

    private fun postSettings(coap: CoapEndpoint, body: ByteArray): Single<Boolean> {
        val request = OutgoingRequestBuilder("/settings", Method.POST)
            .expectSuccess(false).forceNonBlockwise(true).maxResendCount(0)
            .body(body).build()
        return coap.responseFor(request).timeout(3, TimeUnit.SECONDS).map { response ->
            if (response.responseCode.responseClass.toInt() != 2) throw AirCommandFailure.Answered("Air rejected haptic setting change.")
            true
        }
    }

    private fun runBoundedHaptic(coap: CoapEndpoint) {
        var sent = false
        disposables.add(readIntensity(coap).flatMap { original ->
                val alternate = if (original == 2) 3 else 2
                Single.defer { sent = true; writeIntensity(coap, alternate) }
                    .flatMap { Single.timer(800, TimeUnit.MILLISECONDS).map { original } }
                    .flatMap { prior -> writeIntensity(coap, prior).retry(2).map { prior } }
                    .flatMap { prior -> readIntensity(coap).map { verified ->
                        if (verified != prior) throw AirCommandFailure.Answered("Original haptic setting was not verified restored.")
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
        }, { error ->
            finishSessionError(coap, AirCommandFailure.code(error, sent, answeredCode = "HAPTIC_FAILED"),
                error.message ?: "Haptic command failed; check Google Health settings.")
        }))
    }

    private fun writeIntensity(coap: CoapEndpoint, intensity: Int): Single<Boolean> =
        postSettings(coap, AirHapticSettings.write(intensity))

    @SuppressLint("MissingPermission")
    private fun writeNextPacket(activeGatt: BluetoothGatt?) {
        if (activeGatt == null) return
        val characteristic = txCharacteristic ?: return
        val packet = txQueue.next(activeGatt) ?: return
        // Android's immediate answer; below API 33 only accepted or not (-1).
        val status = if (Build.VERSION.SDK_INT >= 33) {
            activeGatt.writeCharacteristic(characteristic, packet, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        } else {
            @Suppress("DEPRECATION") characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            @Suppress("DEPRECATION") characteristic.value = packet
            @Suppress("DEPRECATION") if (activeGatt.writeCharacteristic(characteristic)) BluetoothGatt.GATT_SUCCESS else -1
        }
        if (status == BluetoothGatt.GATT_SUCCESS) return
        // Declined before anything reached the Air. Busy (or unknown below API 33): try again
        // shortly, as nothing else may move the queue on; bounded, so a lasting refusal ends in
        // the operation's own timeout. Any other refusal will not pass on retry: fail now.
        val retry = txQueue.declined(activeGatt, packet)
        android.util.Log.d("FitbitAir", "tx declined status=$status retry=$retry")
        if (status != -1 && status != WRITE_BUSY) {
            handler.post { if (gatt === activeGatt) finishError("TX", "Android rejected the tracker channel write (status $status).") }
            return
        }
        if (retry) handler.postDelayed({ if (gatt === activeGatt) writeNextPacket(activeGatt) }, AirTxQueue.DECLINED_RETRY_MILLIS)
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
        if (usingWarmLink && AirCommandFailure.distrustsLink(code)) warmLinkUnresponsive = true
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
            reply.error(code, message)
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
                    txQueue.add(data)
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
        txQueue.reset()
        trace.step("linkPriority", closingGatt != null && linkBoosted) {
            closingGatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER)
        }
        linkBoosted = false
        val onWarmLink = closingGatt != null && closingGatt === warmLink?.gatt
        val keepWarm = onWarmLink && !warmLinkUnresponsive
        if (onWarmLink && warmLinkUnresponsive) {
            // Connected and subscribed, yet the command got no reply. Reusing it
            // would fail every following command the same way, and the watchdog
            // only replaces links that are disconnected. Rebuild it instead.
            android.util.Log.d("FitbitAir", "warm link unresponsive; replacing")
            AirTransportDiagnostics.recordLinkRecovery("dropped_no_reply")
            trace.note("warmLink", "dropped")
            warmLink?.close()
            warmLink = null
        } else if (keepWarm) {
            // The warm link stays connected for the next command.
            warmLink?.delegate = null
            trace.note("warmLink", "kept")
        } else {
            trace.step("gattDisconnect", closingGatt != null) { closingGatt?.disconnect() }
            trace.step("gattClose", closingGatt != null) { closingGatt?.close() }
        }
        usingWarmLink = false
        warmLinkUnresponsive = false
        AirTransportLease.release(transportOwner)
        if (warmLinkWanted && warmLink == null) {
            handler.postDelayed({ if (warmLink == null) rebuildWarmLink("after_fallback") }, 1_000L)
        }
        if (record) AirTransportDiagnostics.record(cleanupOperation ?: "Scan", trace.snapshot())
        cleanupOperation = null
    }

    companion object {
        /**
         * The runtime permissions a host must hold before calling: Bluetooth
         * connect, plus scan (or location before Android 12) for [scanHeartRateDevices].
         */
        @JvmStatic
        fun requiredPermissions(needsScan: Boolean = false): Array<String> = if (Build.VERSION.SDK_INT >= 31) {
            if (needsScan) arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
            else arrayOf(Manifest.permission.BLUETOOTH_CONNECT)
        } else if (needsScan) arrayOf(Manifest.permission.ACCESS_FINE_LOCATION) else emptyArray()

        @JvmStatic
        fun hasPermissions(context: Context, needsScan: Boolean = false): Boolean =
            requiredPermissions(needsScan).all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

        private val INTENSITIES = setOf(2, 3)

        /** BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY (API 33). */
        private const val WRITE_BUSY = 201
        private const val OPERATION_TIMEOUT_MS = 45_000L
        /** Below this, pulses merge into one longer buzz rather than repeating. */
        internal const val MIN_RING_GAP_MS = 250L
        /** Dropped pulses in a row after which the session counts as gone. */
        private const val MAX_RING_DROPS_IN_A_ROW = 3
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
        /** Repeated pulses inside one secure session; see [runRing]. */
        data class Ring(
            val intensity: Int?,
            val gapMillis: Long,
            val maxMillis: Long,
            /** Stop after this many pulses; null keeps going until stopped. */
            val pulses: Int?,
        ) : Operation
    }
}
