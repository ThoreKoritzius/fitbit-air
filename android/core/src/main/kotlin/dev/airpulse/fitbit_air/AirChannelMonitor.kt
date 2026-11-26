package dev.airpulse.fitbit_air

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.UUID

/**
 * Passive listener on the tracker channel. Subscribes to the Air's tracker
 * notifications (already enabled by Google Health) and never writes to the
 * channel or opens a session, so it does not compete with Google Health.
 * Emits `idle` / `polling` / `syncing` whenever the state changes.
 */
@SuppressLint("MissingPermission")
internal class AirChannelMonitor(
    private val context: Context,
    private val emit: (Map<String, Any>) -> Unit,
    private val fail: (String, String) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val classifier = AirChannelState()
    private var gatt: BluetoothGatt? = null
    private var lastState: String? = null
    private var closed = false
    private val deadline = Runnable { error("CHANNEL_TIMEOUT", "Timed out subscribing to the Air's tracker channel.") }
    private val tick = object : Runnable {
        override fun run() {
            if (closed) return
            publish()
            handler.postDelayed(this, TICK_MS)
        }
    }

    fun start(device: BluetoothDevice) {
        handler.postDelayed(deadline, 15_000)
        gatt = device.connectGatt(context, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, state: Int) {
                handler.post { connectionChanged(g, status, state) }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                handler.post { subscribe(g, status) }
            }

            override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                handler.post { subscribed(g, status) }
            }

            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
                received(g, c, value.size)
            }

            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
                if (Build.VERSION.SDK_INT < 33) received(g, c, c.value?.size ?: 0)
            }
        }, BluetoothDevice.TRANSPORT_LE)
        if (gatt == null) error("CHANNEL_CONNECT", "Android did not create a Bluetooth connection.")
    }

    private fun connectionChanged(g: BluetoothGatt, status: Int, state: Int) {
        if (closed || g !== gatt) return
        if (status != BluetoothGatt.GATT_SUCCESS || state == BluetoothProfile.STATE_DISCONNECTED) {
            error("CHANNEL_DISCONNECTED", "The Air disconnected (status $status).")
        } else if (state == BluetoothProfile.STATE_CONNECTED && !g.discoverServices()) {
            error("CHANNEL_DISCOVERY", "Android rejected service discovery.")
        }
    }

    private fun subscribe(g: BluetoothGatt, status: Int) {
        if (closed || g !== gatt) return
        val rx = g.getService(TRACKER_SERVICE)?.getCharacteristic(TRACKER_RX)
        val cccd = rx?.getDescriptor(CCCD)
        if (status != BluetoothGatt.GATT_SUCCESS || rx == null || cccd == null || !g.setCharacteristicNotification(rx, true)) {
            error("CHANNEL_UNAVAILABLE", "The Air's tracker channel is unavailable.")
            return
        }
        // Already enabled by Google Health; re-enabling is a no-op for it.
        val accepted = if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            g.writeDescriptor(cccd)
        }
        if (!accepted) error("CHANNEL_SUBSCRIBE", "Could not subscribe to the tracker channel.")
    }

    private fun subscribed(g: BluetoothGatt, status: Int) {
        if (closed || g !== gatt) return
        if (status != BluetoothGatt.GATT_SUCCESS) {
            error("CHANNEL_SUBSCRIBE", "Tracker channel subscription failed (status $status).")
            return
        }
        handler.removeCallbacks(deadline)
        publish()
        handler.postDelayed(tick, TICK_MS)
    }

    private fun received(g: BluetoothGatt, c: BluetoothGattCharacteristic, size: Int) {
        if (c.uuid != TRACKER_RX) return
        val at = SystemClock.elapsedRealtime()
        handler.post {
            if (closed || g !== gatt) return@post
            classifier.record(at, size)
            publish()
        }
    }

    private fun publish() {
        val state = classifier.state(SystemClock.elapsedRealtime())
        if (state == lastState) return
        lastState = state
        emit(mapOf("state" to state, "receivedAtMillis" to System.currentTimeMillis()))
    }

    private fun error(code: String, message: String) {
        if (closed) return
        close()
        fail(code, message)
    }

    /** Closes our client only; the shared subscription stays enabled for Google Health. */
    fun close() {
        if (closed) return
        closed = true
        handler.removeCallbacks(deadline)
        handler.removeCallbacks(tick)
        val connection = gatt
        gatt = null
        runCatching { connection?.disconnect() }
        runCatching { connection?.close() }
    }

    companion object {
        private const val TICK_MS = 500L
        private val TRACKER_SERVICE = UUID.fromString("abbaff00-e56a-484c-b832-8b17cf6cbfe8")
        private val TRACKER_RX = UUID.fromString("abbaff02-e56a-484c-b832-8b17cf6cbfe8")
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
