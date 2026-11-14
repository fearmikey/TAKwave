// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.ptt

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.fearmikey.takwave.core.io.Ptt
import com.fearmikey.takwave.data.PttLine
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber

/** A USB serial adapter that could key the radio. [key] is "vendorId:productId". */
data class SerialDeviceOption(val key: String, val label: String, val hasPermission: Boolean, val device: UsbDevice)

object SerialDevices {
    const val ACTION_USB_PERMISSION = "com.fearmikey.takwave.USB_PERMISSION"

    fun keyOf(d: UsbDevice): String = "${d.vendorId}:${d.productId}"

    fun list(context: Context): List<SerialDeviceOption> {
        val manager = context.getSystemService(UsbManager::class.java) ?: return emptyList()
        return UsbSerialProber.getDefaultProber().findAllDrivers(manager).map { driver ->
            val d = driver.device
            val name = d.productName ?: driver.javaClass.simpleName.removeSuffix("SerialDriver")
            SerialDeviceOption(
                keyOf(d),
                "$name (%04x:%04x)".format(d.vendorId, d.productId),
                manager.hasPermission(d),
                d,
            )
        }
    }

    /** Shows the system dialog asking the user to let TAKwave use [device]. */
    fun requestPermission(context: Context, device: UsbDevice) {
        val manager = context.getSystemService(UsbManager::class.java) ?: return
        // The system adds extras to the intent, so it must be mutable; it is also explicit.
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        val pending = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        manager.requestPermission(device, pending)
    }
}

/**
 * PTT through a control line of a USB serial adapter, for example RTS on a Digirig's CP2102.
 * With normal polarity the line is asserted to transmit. Both lines are driven to their idle
 * state as soon as the port opens, so the radio never keys by accident.
 */
class SerialPtt private constructor(
    private val port: UsbSerialPort,
    val deviceKey: String,
    private val line: PttLine,
    private val inverted: Boolean,
) : Ptt {
    @Volatile private var closed = false

    private fun set(transmit: Boolean) {
        val level = transmit != inverted
        when (line) {
            PttLine.RTS -> port.setRTS(level)
            PttLine.DTR -> port.setDTR(level)
        }
    }

    @Synchronized
    override fun key() {
        check(!closed) { "PTT serial port is closed" }
        set(true)
    }

    @Synchronized
    override fun unkey() {
        if (closed) return
        set(false)
    }

    @Synchronized
    fun close() {
        if (closed) return
        try {
            set(false)
        } catch (e: Exception) {
            // The adapter may already be unplugged, in which case the line has dropped anyway.
        }
        closed = true
        try {
            port.close()
        } catch (e: Exception) {
            // Same: nothing left to release.
        }
    }

    companion object {
        /**
         * Opens the adapter matching [deviceKey] (or the first one found when the key is empty).
         * Throws [IllegalStateException] with a user-readable message when that is not possible.
         */
        fun open(context: Context, deviceKey: String, line: PttLine, inverted: Boolean): SerialPtt {
            val manager = context.getSystemService(UsbManager::class.java) ?: error("this device has no USB host support")
            val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)
            val driver = drivers.firstOrNull { deviceKey.isEmpty() || SerialDevices.keyOf(it.device) == deviceKey }
                ?: error("no USB serial adapter found for PTT")
            check(manager.hasPermission(driver.device)) { "USB permission for the PTT adapter has not been granted" }
            val connection = manager.openDevice(driver.device) ?: error("could not open the USB serial adapter")
            val port = driver.ports.firstOrNull() ?: error("the USB serial adapter has no ports")
            try {
                port.open(connection)
                port.setParameters(9600, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                // Idle both lines: the unused one low, the PTT line at its "receive" level.
                port.setDTR(line == PttLine.DTR && inverted)
                port.setRTS(line == PttLine.RTS && inverted)
            } catch (e: Exception) {
                try { port.close() } catch (ignored: Exception) { /* best effort */ }
                error("could not configure the USB serial adapter: ${e.message}")
            }
            return SerialPtt(port, SerialDevices.keyOf(driver.device), line, inverted)
        }
    }
}
