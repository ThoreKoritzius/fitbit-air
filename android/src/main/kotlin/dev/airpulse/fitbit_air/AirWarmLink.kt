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
 * A long-lived, listen-only GATT client to the Air, kept while a host (such as
 * a notification relay) expects commands. It subscribes to the tracker channel
 * Google Health already uses and never writes to it on its own, so it does not
 * compete with Google Health. Commands reuse it, skipping connection setup,
 * and its traffic history lets the sync-aware hold decide without waiting.
 *
 * While a command runs, [delegate] receives every callback, so the command's
 * own logic drives the client exactly like a fresh connection.
 */
@SuppressLint("MissingPermission")
internal class AirWarmLink(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val recent = ArrayDeque<Pair<Long, Int>>()
    private var closed = false

    var gatt: BluetoothGatt? = null
        private set

    /** Connected and subscribed to the tracker channel. */
    @Volatile var ready = false
        private set
    @Volatile private var readyAt = 0L
    @Volatile private var notReadySince = SystemClock.elapsedRealtime()
    /** When the Air last sent anything on this link; 0 before the first packet. */
    @Volatile private var heardAt = 0L
    /** When [resubscribe] last ran; 0 if it has not. */
    private var resubscribedAt = 0L

    /** Not ready for [STUCK_MILLIS], although Android should have reconnected. */
    fun isStuck(nowMillis: Long): Boolean = !ready && nowMillis - notReadySince >= STUCK_MILLIS

    private fun markNotReady() {
        if (ready) notReadySince = SystemClock.elapsedRealtime()
        ready = false
    }

    /**
     * Ready long enough to reuse. A command started right after (re)connecting
     * once received no reply from the Air; commands in that window use a
     * fresh connection instead.
     */
    fun isSettled(nowMillis: Long): Boolean = ready && settled(readyAt, nowMillis)

    /** The active command's callback; null while idle. */
    @Volatile var delegate: BluetoothGattCallback? = null

    val address: String? get() = gatt?.device?.address

    fun start(device: BluetoothDevice) {
        // autoConnect: Android reconnects by itself when the Air returns to range.
        gatt = device.connectGatt(context, true, callback, BluetoothDevice.TRANSPORT_LE)
    }

    /**
     * Re-registers Android's local notification routing for the tracker
     * channel. Local only, no radio traffic; guards against a client that
     * stays connected but no longer receives notifications.
     */
    fun refreshSubscription(): Boolean {
        val g = gatt ?: return false
        val rx = g.getService(TRACKER_SERVICE)?.getCharacteristic(TRACKER_RX) ?: return false
        return g.setCharacteristicNotification(rx, true)
    }

    /** What the idle link needs, from how long it has heard nothing. See [idle]. */
    fun idleCheck(nowMillis: Long): Idle =
        if (!ready) Idle.OK else idle(nowMillis, heardAt, readyAt, resubscribedAt)

    /**
     * Subscribes to the tracker channel again, writing the subscription to
     * the Air rather than only re-registering it locally. Main thread, and
     * only while no command uses the link.
     */
    fun resubscribe(): Boolean {
        val g = gatt ?: return false
        if (closed || delegate != null) return false
        resubscribedAt = SystemClock.elapsedRealtime()
        return subscribe(g)
    }

    /** Tracker traffic within the last [windowMillis], oldest first. */
    fun recentTraffic(nowMillis: Long, windowMillis: Long = HISTORY_MILLIS): List<Pair<Long, Int>> =
        synchronized(recent) { recent.filter { nowMillis - it.first <= windowMillis } }

    fun close() {
        if (closed) return
        closed = true
        markNotReady()
        delegate = null
        val connection = gatt
        gatt = null
        runCatching { connection?.disconnect() }
        runCatching { connection?.close() }
    }

    /**
     * Subscribes to the tracker channel. A step Android declines to start is
     * logged by name: the link then stays not ready, and the watchdog replaces
     * it once [isStuck].
     */
    private fun subscribe(g: BluetoothGatt): Boolean {
        val rx = g.getService(TRACKER_SERVICE)?.getCharacteristic(TRACKER_RX)
            ?: return setupFailed("tracker channel not found")
        val cccd = rx.getDescriptor(CCCD) ?: return setupFailed("subscription descriptor not found")
        if (!g.setCharacteristicNotification(rx, true)) return setupFailed("notification routing declined")
        val started = if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            g.writeDescriptor(cccd)
        }
        return started || setupFailed("subscription write declined")
    }

    private fun setupFailed(step: String): Boolean {
        android.util.Log.w("FitbitAir", "warm link setup: $step")
        return false
    }

    private fun record(size: Int) {
        val now = SystemClock.elapsedRealtime()
        heardAt = now
        synchronized(recent) {
            recent.addLast(now to size)
            while (recent.isNotEmpty() && now - recent.first().first > HISTORY_MILLIS) recent.removeFirst()
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            delegate?.onConnectionStateChange(g, status, newState)
            handler.post {
                if (closed || g !== gatt) return@post
                if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                    if (!ready && !g.discoverServices()) setupFailed("service discovery declined")
                } else {
                    markNotReady()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            delegate?.onServicesDiscovered(g, status)
            handler.post {
                if (closed || g !== gatt || ready) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) setupFailed("service discovery failed (status $status)")
                else subscribe(g)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            delegate?.onDescriptorWrite(g, descriptor, status)
            handler.post {
                if (closed || g !== gatt) return@post
                if (descriptor.characteristic.uuid != TRACKER_RX) return@post
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    // Subscribing again keeps a ready link ready and settled.
                    if (!ready) readyAt = SystemClock.elapsedRealtime()
                    ready = true
                } else setupFailed("subscription failed (status $status)")
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c.uuid == TRACKER_RX) record(value.size)
            delegate?.onCharacteristicChanged(g, c, value)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33 && c.uuid == TRACKER_RX) record(c.value?.size ?: 0)
            delegate?.onCharacteristicChanged(g, c)
        }

        override fun onServiceChanged(g: BluetoothGatt) {
            delegate?.onServiceChanged(g)
            // Cached handles may be stale: rediscover and subscribe again.
            handler.post {
                if (closed || g !== gatt) return@post
                markNotReady()
                g.discoverServices()
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            delegate?.onCharacteristicWrite(g, c, status)
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            delegate?.onCharacteristicRead(g, c, value, status)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            delegate?.onCharacteristicRead(g, c, status)
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            delegate?.onReadRemoteRssi(g, rssi, status)
        }
    }

    enum class Idle { OK, RESUBSCRIBE, REBUILD }

    companion object {
        const val SETTLE_MILLIS = 3_000L
        const val STUCK_MILLIS = 120_000L
        /**
         * Google Health syncs with the Air every few minutes, and the link
         * hears that traffic. AirBuzz reports an open link that stayed
         * connected but heard nothing for over an hour while Google Health
         * kept syncing, until it subscribed again. Not reproduced here.
         */
        const val DEAF_MILLIS = 20 * 60_000L

        fun settled(readyAtMillis: Long, nowMillis: Long): Boolean = nowMillis - readyAtMillis >= SETTLE_MILLIS

        /**
         * A ready link that has heard nothing for [DEAF_MILLIS] (since its last
         * packet, or since it became ready) subscribes again. If it still
         * hears nothing for [DEAF_MILLIS] after that, it is replaced: a
         * connection of its own is the next thing to try. Hearing the Air
         * again ends the spell. Times are elapsed realtime; 0 means never.
         */
        fun idle(nowMillis: Long, heardAtMillis: Long, readyAtMillis: Long, resubscribedAtMillis: Long): Idle {
            val quietSince = maxOf(heardAtMillis, readyAtMillis)
            return when {
                resubscribedAtMillis > quietSince ->
                    if (nowMillis - resubscribedAtMillis >= DEAF_MILLIS) Idle.REBUILD else Idle.OK
                nowMillis - quietSince >= DEAF_MILLIS -> Idle.RESUBSCRIBE
                else -> Idle.OK
            }
        }

        private const val HISTORY_MILLIS = 2_500L
        private val TRACKER_SERVICE = UUID.fromString("abbaff00-e56a-484c-b832-8b17cf6cbfe8")
        private val TRACKER_RX = UUID.fromString("abbaff02-e56a-484c-b832-8b17cf6cbfe8")
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
