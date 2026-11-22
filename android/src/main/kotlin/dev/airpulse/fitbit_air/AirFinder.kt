package dev.airpulse.fitbit_air

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.flutter.plugin.common.EventChannel

/** A separate, read-only GATT client. Never discovers services or writes to the Air. */
@SuppressLint("MissingPermission")
internal class AirFinder(
    private val context: Context,
    private val device: BluetoothDevice,
    private val events: EventChannel.EventSink,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var closed = false
    private var connected = false
    private var requestedAt = 0L
    private var connectingAt = 0L
    private var nextAttempt = 0L
    private var lastReading = 0L
    private var smoothed: Double? = null
    private val tick = object : Runnable {
        override fun run() {
            if (closed) return
            try {
                val now = SystemClock.elapsedRealtime()
                if (gatt == null && now >= nextAttempt) connect()
                else if (gatt != null && ((!connected && now - connectingAt > 20000) ||
                    (requestedAt != 0L && now - requestedAt > 5000))) {
                    reset()
                    emit("searching")
                } else if (connected && requestedAt == 0L) {
                    requestedAt = now
                    if (gatt?.readRemoteRssi() != true) {
                        reset()
                        emit("searching")
                    }
                }
                if (lastReading != 0L && now - lastReading > 5000) {
                    lastReading = 0L
                    smoothed = null
                    emit("searching")
                }
            } catch (_: SecurityException) {
                events.error("BLUETOOTH_PERMISSION", "Nearby devices permission was revoked.", null)
                close()
                events.endOfStream()
            } catch (_: IllegalStateException) {
                reset()
                emit("searching")
            }
            if (!closed) handler.postDelayed(this, 1000)
        }
    }
    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, state: Int) {
            handler.post {
                if (closed || g !== gatt) return@post
                if (status == BluetoothGatt.GATT_SUCCESS && state == BluetoothProfile.STATE_CONNECTED) {
                    connected = true
                } else if (status != BluetoothGatt.GATT_SUCCESS || state == BluetoothProfile.STATE_DISCONNECTED) {
                    reset()
                    emit("searching")
                }
            }
        }
        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            handler.post {
                if (closed || g !== gatt) return@post
                requestedAt = 0L
                if (status != BluetoothGatt.GATT_SUCCESS || rssi !in -127..-1) {
                    reset()
                    emit("searching")
                    return@post
                }
                lastReading = SystemClock.elapsedRealtime()
                smoothed = smoothed?.let { it + .3 * (rssi - it) } ?: rssi.toDouble()
                emit("signal", rssi)
            }
        }
    }
    fun start() {
        emit("searching")
        handler.post(tick)
    }
    private fun connect() {
        connectingAt = SystemClock.elapsedRealtime()
        // Direct connection gives quick discovery of the bonded device even if
        // it is not advertising. Retry is bounded to one client every 20 seconds.
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }
    private fun emit(state: String, rssi: Int? = null) {
        if (closed) return
        events.success(mapOf("state" to state, "rssi" to rssi,
            "smoothedRssi" to if (rssi != null) smoothed else null,
            "receivedAtMillis" to System.currentTimeMillis()))
    }
    private fun reset() {
        nextAttempt = SystemClock.elapsedRealtime() + 3000
        val old = gatt
        gatt = null
        connected = false
        requestedAt = 0L
        lastReading = 0L
        smoothed = null
        try { old?.disconnect() } catch (_: SecurityException) { }
        old?.close()
    }
    fun close() {
        closed = true
        handler.removeCallbacks(tick)
        reset()
    }
}
