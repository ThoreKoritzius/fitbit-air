package dev.airpulse.fitbit_air

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build

/**
 * Reports whether the Air's Bluetooth link is up, from Android's system
 * connection broadcasts. Opens no connection of its own; Google Health keeps
 * the link while the Air is in range, so a disconnect usually means out of
 * range, Bluetooth off, or the Air shut down.
 *
 * Switching Bluetooth off does not send a per-device disconnect, so adapter
 * state changes are watched too. Only actual changes are reported.
 */
@SuppressLint("MissingPermission")
internal class AirConnectionWatcher(
    private val context: Context,
    private val device: BluetoothDevice,
    private val emit: (Map<String, Any>) -> Unit,
) {
    private var registered = false
    private var last: Boolean? = null
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> publish(false)
                    BluetoothAdapter.STATE_ON -> publish(isConnected(context, device))
                }
                return
            }
            val changed = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }
            if (changed?.address != device.address) return
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> publish(true)
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> publish(false)
            }
        }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        registered = true
        publish(isConnected(context, device))
    }

    fun close() {
        if (!registered) return
        registered = false
        runCatching { context.unregisterReceiver(receiver) }
    }

    private fun publish(connected: Boolean) {
        if (connected == last) return
        last = connected
        emit(mapOf("connected" to connected, "receivedAtMillis" to System.currentTimeMillis()))
    }

    companion object {
        fun isConnected(context: Context, device: BluetoothDevice): Boolean {
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            return manager.getConnectionState(device, BluetoothProfile.GATT) == BluetoothProfile.STATE_CONNECTED
        }
    }
}
