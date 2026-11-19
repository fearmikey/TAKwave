// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.ptt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import com.fearmikey.takwave.core.io.BleKiss
import com.fearmikey.takwave.core.io.ByteQueueInputStream
import com.fearmikey.takwave.core.io.KissLink
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A KISS byte stream to a TNC on the Bluetooth LE KISS service ([BleKiss]): writes go to the TX
 * characteristic in MTU-sized pieces, notifications from the RX characteristic feed [input].
 * [KissStreamTransport][com.fearmikey.takwave.core.io.KissStreamTransport] does the framing,
 * reconnecting and status reports on top, exactly as for a classic serial link.
 *
 * The TNC keys the radio itself, so a write cut off by a dropped link leaves nothing keyed: the
 * TNC discards an incomplete KISS frame.
 */
@SuppressLint("MissingPermission") // BluetoothDevices.resolve() checks the permission before open().
class BleKissLink private constructor() : KissLink {
    private val incoming = ByteQueueInputStream()
    override val input = incoming

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var closed = false
    private var tx: BluetoothGattCharacteristic? = null
    private var writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
    @Volatile private var payloadSize = BleKiss.payloadSize(BleKiss.DEFAULT_MTU)

    // The GATT stack handles one request at a time and drops one issued while another is pending,
    // so every request waits for its callback before the next starts.
    private val opLock = Any()
    @Volatile private var pending: CountDownLatch? = null
    @Volatile private var pendingStatus = BluetoothGatt.GATT_SUCCESS

    private fun complete(status: Int) {
        pendingStatus = status
        pending?.countDown()
    }

    /** Starts one GATT request and waits for its callback. Throws [IOException] when it fails or times out. */
    private fun step(what: String, timeoutMs: Long, start: () -> Boolean) {
        synchronized(opLock) {
            if (closed) throw IOException("link closed")
            val latch = CountDownLatch(1)
            pending = latch
            pendingStatus = BluetoothGatt.GATT_SUCCESS
            try {
                if (!start()) throw IOException("$what could not be started")
                if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) throw IOException("$what timed out")
                if (closed) throw IOException("link closed")
                if (pendingStatus != BluetoothGatt.GATT_SUCCESS) throw IOException("$what failed (status $pendingStatus)")
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("$what interrupted")
            } finally {
                pending = null
            }
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> complete(status)
                BluetoothProfile.STATE_DISCONNECTED -> {
                    // Fails a pending request at once instead of letting it time out.
                    complete(if (status == BluetoothGatt.GATT_SUCCESS) BluetoothGatt.GATT_FAILURE else status)
                    incoming.fail("Bluetooth LE link lost")
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = complete(status)

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) payloadSize = BleKiss.payloadSize(mtu)
            complete(status)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) = complete(status)

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) = complete(status)

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == RX) incoming.offer(value)
        }

        @Deprecated("Called instead of the three-argument version before Android 13")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            if (characteristic.uuid == RX) characteristic.value?.let { incoming.offer(it) }
        }
    }

    override val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            val g = gatt ?: throw IOException("Bluetooth LE link closed")
            val characteristic = tx ?: throw IOException("Bluetooth LE link closed")
            for (piece in BleKiss.split(b.copyOfRange(off, off + len), payloadSize)) {
                step("Bluetooth LE write", WRITE_TIMEOUT_MS) { writeCharacteristic(g, characteristic, piece) }
            }
        }
    }

    private fun writeCharacteristic(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(characteristic, value, writeType) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                characteristic.writeType = writeType
                characteristic.value = value
                g.writeCharacteristic(characteristic)
            }
        }

    private fun writeDescriptor(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, value: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                descriptor.value = value
                g.writeDescriptor(descriptor)
            }
        }

    /** Connects, finds the KISS service, raises the MTU and switches notifications on. */
    private fun setUp(context: Context, device: BluetoothDevice) {
        val name = device.name ?: device.address
        step("connecting to $name", CONNECT_TIMEOUT_MS) {
            @Suppress("DEPRECATION") // The replacement needs API 36; this form works on every supported version.
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            gatt != null
        }
        val g = gatt ?: throw IOException("connecting to $name failed")
        step("reading the services of $name", DISCOVERY_TIMEOUT_MS) { g.discoverServices() }
        val service = g.getService(SERVICE)
            ?: throw IOException("$name does not offer the Bluetooth LE KISS service; use classic Bluetooth serial instead")
        val txChar = service.getCharacteristic(TX) ?: throw IOException("$name has no KISS TX characteristic")
        val rxChar = service.getCharacteristic(RX) ?: throw IOException("$name has no KISS RX characteristic")
        // Acknowledged writes give flow control; without them the TNC's buffer could overflow.
        writeType = if (txChar.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        tx = txChar
        try {
            step("raising the MTU", MTU_TIMEOUT_MS) { g.requestMtu(BleKiss.REQUESTED_MTU) }
        } catch (e: IOException) {
            // Some TNCs refuse; 20-byte writes still work, only slower.
            if (closed) throw e
        }
        if (!g.setCharacteristicNotification(rxChar, true)) throw IOException("$name refused notifications")
        val cccd = rxChar.getDescriptor(CCCD) ?: throw IOException("$name has no notification descriptor")
        val enable = if (rxChar.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }
        step("switching on notifications", DESCRIPTOR_TIMEOUT_MS) { writeDescriptor(g, cccd, enable) }
    }

    override fun close() {
        if (closed) return
        closed = true
        incoming.end()
        pending?.countDown()
        val g = gatt ?: return
        try { g.disconnect() } catch (e: Exception) { /* already gone */ }
        try { g.close() } catch (e: Exception) { /* already gone */ }
    }

    companion object {
        private val SERVICE: UUID = UUID.fromString(BleKiss.SERVICE)
        private val TX: UUID = UUID.fromString(BleKiss.TX_CHARACTERISTIC)
        private val RX: UUID = UUID.fromString(BleKiss.RX_CHARACTERISTIC)
        private val CCCD: UUID = UUID.fromString(BleKiss.CCCD)

        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val DISCOVERY_TIMEOUT_MS = 10_000L
        private const val MTU_TIMEOUT_MS = 5_000L
        private const val DESCRIPTOR_TIMEOUT_MS = 5_000L
        private const val WRITE_TIMEOUT_MS = 5_000L

        /**
         * Opens the link to the TNC with MAC [address] (paired, or picked in the BLE chooser).
         * Blocks for several seconds, so call it off the main thread. Throws with a user-readable
         * message when the link cannot be set up.
         */
        fun open(context: Context, address: String): BleKissLink {
            val device = BluetoothDevices.resolve(context, address, allowFound = true)
            val link = BleKissLink()
            try {
                link.setUp(context.applicationContext, device)
            } catch (e: Exception) {
                link.close()
                error(e.message ?: "the Bluetooth LE link could not be set up")
            }
            return link
        }
    }
}
