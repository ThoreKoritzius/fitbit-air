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
import java.util.ArrayDeque
import java.util.UUID

/** Diagnostic subscriptions only. No tracker packets or control writes. */
@SuppressLint("MissingPermission")
internal class AirEventMonitor(
    private val context: Context,
    private val emit: (Map<String, Any>) -> Unit,
    private val fail: (String, String) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private val descriptors = ArrayDeque<Pair<BluetoothGattDescriptor, ByteArray>>()
    private var subscribed = 0
    private var closed = false
    private val deadline = Runnable { error("EVENT_TIMEOUT", "Timed out subscribing to Air diagnostic events.") }

    fun start(device: BluetoothDevice) {
        handler.postDelayed(deadline, 15_000)
        gatt = device.connectGatt(context, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, state: Int) {
                handler.post {
                    if (closed || g !== gatt) return@post
                    if (status != BluetoothGatt.GATT_SUCCESS || state == BluetoothProfile.STATE_DISCONNECTED) {
                        emit(mapOf("kind" to "disconnected", "status" to status,
                            "receivedAtMillis" to System.currentTimeMillis()))
                        error("EVENT_DISCONNECTED", "Air diagnostic connection ended (status $status).")
                    } else if (state == BluetoothProfile.STATE_CONNECTED && !g.discoverServices()) {
                        error("EVENT_DISCOVERY", "Android rejected service discovery.")
                    }
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                handler.post {
                    if (closed || g !== gatt) return@post
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        error("EVENT_DISCOVERY", "Service discovery failed (status $status).")
                        return@post
                    }
                    for (service in g.services.filter { it.uuid in MONITORED_SERVICES }) {
                        for (characteristic in service.characteristics) {
                            val notify = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
                            val indicate = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
                            if (!notify && !indicate) continue
                            val descriptor = characteristic.getDescriptor(CCCD) ?: continue
                            if (!g.setCharacteristicNotification(characteristic, true)) continue
                            descriptors.addLast(descriptor to if (notify)
                                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
                        }
                    }
                    if (descriptors.isEmpty()) error("NO_DEVICE_EVENTS", "No supported diagnostic notification characteristics found.")
                    else nextDescriptor(g)
                }
            }

            override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                handler.post {
                    if (closed || g !== gatt) return@post
                    if (status == BluetoothGatt.GATT_SUCCESS) subscribed++
                    emit(mapOf(
                        "kind" to "subscription",
                        "characteristicUuid" to descriptor.characteristic.uuid.toString(),
                        "status" to status,
                        "receivedAtMillis" to System.currentTimeMillis(),
                    ))
                    nextDescriptor(g)
                }
            }

            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
                notification(g, c, value.copyOf())
            }

            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
                if (Build.VERSION.SDK_INT < 33) notification(g, c, c.value?.copyOf() ?: byteArrayOf())
            }
        }, BluetoothDevice.TRANSPORT_LE)
        if (gatt == null) error("EVENT_CONNECT", "Android did not create a diagnostic connection.")
    }

    private fun notification(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, bytes: ByteArray) {
        val service = characteristic.service.uuid
        val uuid = characteristic.uuid
        handler.post {
            if (closed || g !== gatt || service !in MONITORED_SERVICES) return@post
            emit(mapOf(
                "kind" to "notification",
                "serviceUuid" to service.toString(),
                "characteristicUuid" to uuid.toString(),
                "payload" to bytes,
                "receivedAtMillis" to System.currentTimeMillis(),
            ))
        }
    }

    private fun nextDescriptor(g: BluetoothGatt) {
        val next = descriptors.pollFirst()
        if (next == null) {
            handler.removeCallbacks(deadline)
            if (subscribed == 0) error("EVENT_SUBSCRIBE", "Air rejected every diagnostic subscription.")
            else emit(mapOf("kind" to "ready", "subscribed" to subscribed, "receivedAtMillis" to System.currentTimeMillis()))
            return
        }
        val (descriptor, value) = next
        val accepted = if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(descriptor, value) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = value
            @Suppress("DEPRECATION")
            g.writeDescriptor(descriptor)
        }
        if (!accepted) {
            emit(mapOf("kind" to "subscription", "characteristicUuid" to descriptor.characteristic.uuid.toString(),
                "status" to -1, "receivedAtMillis" to System.currentTimeMillis()))
            nextDescriptor(g)
        }
    }

    private fun error(code: String, message: String) {
        if (closed) return
        close()
        fail(code, message)
    }

    fun close() {
        if (closed) return
        closed = true
        handler.removeCallbacks(deadline)
        descriptors.clear()
        val connection = gatt
        gatt = null
        runCatching { connection?.disconnect() }
        runCatching { connection?.close() }
    }

    companion object {
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        // Link status plus the Air's as-yet unclassified service. Deliberately
        // excludes ABBAFF (tracker/DTLS), standard HR, and service-change events.
        private val MONITORED_SERVICES = setOf(
            UUID.fromString("abbafd00-e56a-484c-b832-8b17cf6cbfe8"),
            UUID.fromString("4eee1c00-4133-479b-8663-02c84bdc14be"),
        )
    }
}
