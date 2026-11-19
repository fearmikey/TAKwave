// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.ptt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.fearmikey.takwave.core.io.KissLink
import com.fearmikey.takwave.core.io.Ptt
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * A Bluetooth device the operator can choose: paired in Android's settings, or found with the
 * Bluetooth LE TNC chooser ([BleCompanion]). [address] is what gets stored in settings; [name] is
 * what the device advertises, used to recognise supported radios.
 */
data class BluetoothDeviceOption(
    val address: String,
    val label: String,
    val name: String = "",
    val lowEnergyOnly: Boolean = false,
    val paired: Boolean = true,
)

object BluetoothDevices {
    /** Android 12+ needs the "Nearby devices" runtime permission; older versions grant Bluetooth at install. */
    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /**
     * Devices already paired in Android's Bluetooth settings, plus Bluetooth LE TNCs the operator
     * picked in the system chooser ([BleCompanion]). TAKwave never scans by itself, so it needs
     * neither the scan nor the location permission. Empty without permission or Bluetooth.
     */
    @SuppressLint("MissingPermission") // Checked by hasPermission().
    fun known(context: Context): List<BluetoothDeviceOption> {
        if (!hasPermission(context)) return emptyList()
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        return try {
            val bonded = adapter.bondedDevices.orEmpty().map { d ->
                val name = d.name.orEmpty()
                val le = d.type == BluetoothDevice.DEVICE_TYPE_LE
                BluetoothDeviceOption(
                    d.address,
                    "${name.ifEmpty { "Unnamed device" }} (${d.address})" + if (le) ", Bluetooth LE" else "",
                    name,
                    lowEnergyOnly = le,
                )
            }
            val found = BleCompanion.associated(context)
                .filter { a -> bonded.none { it.address.equals(a, ignoreCase = true) } }
                .mapNotNull { a ->
                    val d = try { adapter.getRemoteDevice(a.uppercase()) } catch (e: IllegalArgumentException) { null } ?: return@mapNotNull null
                    val name = d.name.orEmpty()
                    BluetoothDeviceOption(d.address, "${name.ifEmpty { "Bluetooth LE TNC" }} (${d.address}), found nearby", name, lowEnergyOnly = true, paired = false)
                }
            (bonded + found).sortedBy { it.label }
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    /** The standard Serial Port Profile service. */
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    /** Short (16-bit) UUIDs are the Bluetooth base UUID with the short value in the first word. */
    private const val BASE_UUID_SUFFIX = "-0000-1000-8000-00805f9b34fb"

    /** Vendor services tried after SPP, at most this many, for data links only. */
    private const val MAX_VENDOR_SERVICES = 3

    /**
     * The chosen device, if it may be used: paired, or (with [allowFound]) picked in the Bluetooth
     * LE chooser. Throws [IllegalStateException] with a user-readable message otherwise.
     */
    @SuppressLint("MissingPermission") // Checked by hasPermission() below.
    fun resolve(context: Context, address: String, allowFound: Boolean = false): BluetoothDevice {
        check(address.isNotEmpty()) { "no Bluetooth device has been chosen" }
        check(hasPermission(context)) { "the Nearby devices (Bluetooth) permission has not been granted" }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: error("this phone has no Bluetooth")
        check(adapter.isEnabled) { "Bluetooth is switched off" }
        adapter.bondedDevices.orEmpty().firstOrNull { it.address.equals(address, ignoreCase = true) }?.let { return it }
        if (allowFound && BleCompanion.associated(context).any { it.equals(address, ignoreCase = true) }) {
            return adapter.getRemoteDevice(address.uppercase())
        }
        error("the chosen Bluetooth device is no longer paired")
    }

    /**
     * Opens a serial (RFCOMM) link to the paired device with MAC [address]: for PTT commands, KISS
     * frames to a TNC, or a digital radio's data port. Blocks for several seconds, so call it off
     * the main thread. Throws [IllegalStateException] with a user-readable message when the link
     * cannot be set up.
     *
     * The standard serial service (SPP) is tried with an encrypted link, then without encryption,
     * for adapters whose firmware refuses the first. With [dataLink], vendor-specific serial
     * services the device advertises are tried after that, for radios that offer their data port
     * under their own service. PTT never uses them ([dataLink] false), so key and unkey commands
     * only ever go to a standard serial port. [onNote] is told which service was used when it was
     * not the first.
     */
    @SuppressLint("MissingPermission") // Checked by resolve().
    fun connectSerial(context: Context, address: String, dataLink: Boolean = false, onNote: (String) -> Unit = {}): BluetoothSocket {
        val device = resolve(context, address)
        val attempts = buildList<Pair<String, () -> BluetoothSocket>> {
            add("serial port (SPP)" to { device.createRfcommSocketToServiceRecord(SPP_UUID) })
            add("serial port (SPP) without encryption" to { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) })
            if (dataLink) {
                val advertised = device.uuids
                if (advertised == null) {
                    // Ask for the service list now so the next attempt, 5 s later, can use it.
                    try { device.fetchUuidsWithSdp() } catch (e: Exception) { /* best effort */ }
                }
                advertised.orEmpty()
                    .map { it.uuid }
                    .filter { !it.toString().lowercase().endsWith(BASE_UUID_SUFFIX) }
                    .distinct()
                    .take(MAX_VENDOR_SERVICES)
                    .forEach { uuid -> add("vendor serial service $uuid" to { device.createRfcommSocketToServiceRecord(uuid) }) }
            }
        }
        for ((index, attempt) in attempts.withIndex()) {
            val (what, create) = attempt
            val socket = try { create() } catch (e: IOException) { continue }
            try {
                socket.connect()
                if (index > 0) onNote("Bluetooth: connected to ${device.name ?: address} using its $what.")
                return socket
            } catch (e: IOException) {
                try { socket.close() } catch (ignored: Exception) { /* best effort */ }
            }
        }
        error("could not connect to ${device.name ?: address} (is it switched on, in range, and offering a serial port?)")
    }

    /** [connectSerial] for a data link (KISS TNC, digital radio data port), as a byte stream. */
    fun openLink(context: Context, address: String, onNote: (String) -> Unit = {}): KissLink {
        val socket = connectSerial(context, address, dataLink = true, onNote = onNote)
        return object : KissLink {
            override val input: InputStream = socket.inputStream
            override val output: OutputStream = socket.outputStream
            override fun close() = socket.close()
        }
    }

    /** Android's idea of the device's radio: [BluetoothDevice.DEVICE_TYPE_LE], CLASSIC, DUAL or UNKNOWN. */
    @SuppressLint("MissingPermission") // Only called after resolve() checked it.
    fun typeOf(device: BluetoothDevice): Int = try { device.type } catch (e: SecurityException) { BluetoothDevice.DEVICE_TYPE_UNKNOWN }
}

/**
 * PTT through a Bluetooth serial (SPP) link: a command is written to key the radio and another
 * to unkey it. This suits radios and adapters with Bluetooth CAT control and Bluetooth keyers.
 *
 * The unkey command is sent as soon as the link opens, after every transmission and on close.
 * If the link drops, [onLost] is called so the engine stops transmitting. A command cannot reach
 * the radio over a dead link, so the radio's own time-out timer is the last line of defense.
 */
class BluetoothPtt private constructor(
    private val socket: BluetoothSocket,
    private val keyCommand: ByteArray,
    private val unkeyCommand: ByteArray,
    private val onLost: () -> Unit,
) : Ptt {
    @Volatile private var closed = false
    private val output = socket.outputStream

    private fun send(command: ByteArray) {
        output.write(command)
        output.flush()
    }

    @Synchronized
    override fun key() {
        check(!closed) { "Bluetooth PTT link is closed" }
        send(keyCommand)
    }

    @Synchronized
    override fun unkey() {
        if (closed) return
        send(unkeyCommand)
    }

    /** Discards whatever the radio answers and notices when the link goes away. */
    private fun watchLink() {
        val buffer = ByteArray(256)
        try {
            val input = socket.inputStream
            while (input.read(buffer) >= 0) { /* replies are not interpreted */ }
        } catch (e: IOException) {
            // Link lost, or the socket was closed by close().
        }
        if (closed) return
        closed = true
        closeSocket()
        onLost()
    }

    @Synchronized
    fun close() {
        if (closed) return
        try {
            send(unkeyCommand)
        } catch (e: Exception) {
            // The link is already gone; nothing more can be sent.
        }
        closed = true
        closeSocket()
    }

    private fun closeSocket() {
        try {
            socket.close()
        } catch (e: Exception) {
            // Nothing left to release.
        }
    }

    companion object {
        /**
         * Connects to the paired device with MAC [address]. Blocks for several seconds, so call
         * it off the main thread. Throws [IllegalStateException] with a user-readable message
         * when the link cannot be set up.
         */
        @SuppressLint("MissingPermission") // Checked by hasPermission() below.
        fun open(
            context: Context,
            address: String,
            keyCommand: ByteArray,
            unkeyCommand: ByteArray,
            onLost: () -> Unit,
        ): BluetoothPtt {
            val socket = BluetoothDevices.connectSerial(context, address)
            val ptt = BluetoothPtt(socket, keyCommand, unkeyCommand, onLost)
            try {
                // Start from a known state: receive.
                ptt.send(unkeyCommand)
            } catch (e: IOException) {
                ptt.closed = true
                ptt.closeSocket()
                error("the Bluetooth link dropped while it was being set up")
            }
            Thread({ ptt.watchLink() }, "takwave-bt-ptt").apply { isDaemon = true }.start()
            return ptt
        }
    }
}
