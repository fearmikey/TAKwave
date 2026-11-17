// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.ptt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.fearmikey.takwave.core.io.Ptt
import java.io.IOException
import java.util.UUID

/** A paired Bluetooth device that could carry PTT commands. [address] is what gets stored in settings. */
data class BluetoothDeviceOption(val address: String, val label: String)

object BluetoothDevices {
    /** Android 12+ needs the "Nearby devices" runtime permission; older versions grant Bluetooth at install. */
    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /**
     * Devices already paired in Android's Bluetooth settings. TAKwave never scans, so it needs
     * neither the scan nor the location permission. Empty without permission or Bluetooth.
     */
    @SuppressLint("MissingPermission") // Checked by hasPermission().
    fun paired(context: Context): List<BluetoothDeviceOption> {
        if (!hasPermission(context)) return emptyList()
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        return try {
            adapter.bondedDevices.orEmpty()
                .map { BluetoothDeviceOption(it.address, "${it.name ?: "Unnamed device"} (${it.address})") }
                .sortedBy { it.label }
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    /** The standard Serial Port Profile service. */
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    /**
     * Opens a serial (SPP) link to the paired device with MAC [address]: for PTT commands, or
     * for KISS frames to a radio with a built-in TNC. Blocks for several seconds, so call it off
     * the main thread. Throws [IllegalStateException] with a user-readable message when the link
     * cannot be set up.
     */
    @SuppressLint("MissingPermission") // Checked by hasPermission() below.
    fun connectSerial(context: Context, address: String): BluetoothSocket {
        check(address.isNotEmpty()) { "no Bluetooth device has been chosen" }
        check(hasPermission(context)) { "the Nearby devices (Bluetooth) permission has not been granted" }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: error("this phone has no Bluetooth")
        check(adapter.isEnabled) { "Bluetooth is switched off" }
        val device = adapter.bondedDevices.orEmpty().firstOrNull { it.address == address }
            ?: error("the chosen Bluetooth device is no longer paired")
        val socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
        try {
            socket.connect()
        } catch (e: IOException) {
            try { socket.close() } catch (ignored: Exception) { /* best effort */ }
            error("could not connect to ${device.name ?: address} (is it switched on, in range, and offering a serial port?)")
        }
        return socket
    }
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
