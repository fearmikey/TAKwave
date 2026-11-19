// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.ptt

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.fearmikey.takwave.core.io.KissLink
import com.fearmikey.takwave.core.ip.Ipv4
import com.fearmikey.takwave.core.ip.P25Probe
import com.fearmikey.takwave.core.ip.P25ProbeResult
import com.fearmikey.takwave.data.SerialLinkKind
import com.fearmikey.takwave.data.Settings
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * A USB serial port used as a plain byte stream, for the data port of a P25 radio: a USB-to-RS-232
 * adapter on the radio's data cable, or a radio that shows up as a USB serial (CDC ACM) device.
 *
 * RTS and DTR are held low, so an interface that is also wired for PTT (a Digirig, for example)
 * can never key a radio through this link.
 */
object SerialLinks {
    /** USB vendor id of Motorola's radio products (0x0CAD); ASTRO radios on their USB cable report it. */
    const val MOTOROLA_VENDOR_ID = 0x0CAD

    /** Serial line rates a radio data port is commonly set to, in the order they are tried. */
    val BAUD_RATES = listOf(9600, 19200, 38400, 57600, 115200)

    /** The address Motorola radios use for themselves on the data cable unless reprogrammed. */
    private const val DEFAULT_RADIO_IP = "192.168.128.1"

    fun isMotorolaRadio(device: UsbDevice): Boolean = device.vendorId == MOTOROLA_VENDOR_ID

    /**
     * Finds the radio's data port settings on the port chosen in [settings]: the stored baud rate
     * and framing first, then the other rates. Blocks for up to about 10 s; throws
     * [IllegalStateException] with a readable reason when nothing answers.
     *
     * Over a Bluetooth serial adapter only the framing is found: the adapter's own setting fixes
     * the rate on the radio side, and the phone cannot change it, so the stored rate is kept.
     */
    fun probe(context: Context, settings: Settings, onProgress: (String) -> Unit = {}): P25ProbeResult {
        val local = Ipv4.parse(settings.p25LocalIp)?.takeIf { it != 0 } ?: error("the phone's IP address is not valid")
        val radios = listOfNotNull(Ipv4.parse(settings.p25RadioIp), Ipv4.parse(DEFAULT_RADIO_IP)).distinct()
        if (settings.p25Link == SerialLinkKind.BLUETOOTH) {
            return P25Probe(listOf(settings.p25Baud), local, radios, onProgress = { onProgress("Asking the radio over Bluetooth (PPP and SLIP)") }) {
                BluetoothDevices.openLink(context, settings.p25BtDevice)
            }.run()
        }
        val bauds = (listOf(settings.p25Baud) + BAUD_RATES).distinct()
        return P25Probe(bauds, local, radios, onProgress = onProgress) { baud -> open(context, settings.p25Device, baud) }.run()
    }

    /**
     * Opens port [portIndex] of the device matching [deviceKey] (the first one found when empty) at
     * [baud], 8N1. Dual-port bridges such as the one in an Icom IC-705 have the data port second.
     * Throws [IllegalStateException] with a user-readable message when that is not possible.
     */
    fun open(context: Context, deviceKey: String, baud: Int, portIndex: Int = 0): KissLink {
        val manager = context.getSystemService(UsbManager::class.java) ?: error("this device has no USB host support")
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)
        val driver = drivers.firstOrNull { deviceKey.isEmpty() || SerialDevices.keyOf(it.device) == deviceKey }
            ?: error(if (deviceKey.isEmpty()) "no USB serial port found for the radio's data cable" else "the chosen USB serial port is not connected")
        check(manager.hasPermission(driver.device)) { "USB permission for the radio's serial port has not been granted (Settings > Radio & audio)" }
        val connection = manager.openDevice(driver.device) ?: error("could not open the USB serial port")
        val port = driver.ports.getOrNull(portIndex)
            ?: error(if (driver.ports.isEmpty()) "the USB serial device has no ports" else "the USB serial device has no port ${portIndex + 1} (it has ${driver.ports.size})")
        try {
            port.open(connection)
            port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            try {
                port.setDTR(false)
                port.setRTS(false)
            } catch (e: UnsupportedOperationException) {
                // Some drivers have no control lines; nothing to hold low then.
            }
        } catch (e: Exception) {
            try { port.close() } catch (ignored: Exception) { /* best effort */ }
            error("could not configure the USB serial port: ${e.message}")
        }
        return PortLink(port)
    }

    private class PortLink(private val port: UsbSerialPort) : KissLink {
        @Volatile private var closed = false

        override val input: InputStream = object : InputStream() {
            private val buf = ByteArray(maxOf(4096, port.readEndpoint?.maxPacketSize ?: 0))
            private var pos = 0
            private var len = 0

            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
            }

            override fun read(b: ByteArray, off: Int, length: Int): Int {
                if (length == 0) return 0
                while (pos >= len) {
                    if (closed) return -1
                    // Timeout 0 blocks until data arrives; close() cancels the request.
                    len = try {
                        port.read(buf, 0)
                    } catch (e: IOException) {
                        if (closed) return -1
                        throw e
                    }
                    pos = 0
                }
                val n = minOf(length, len - pos)
                buf.copyInto(b, off, pos, pos + n)
                pos += n
                return n
            }
        }

        override val output: OutputStream = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

            override fun write(b: ByteArray, off: Int, len: Int) {
                if (closed) throw IOException("serial port closed")
                port.write(b.copyOfRange(off, off + len), WRITE_TIMEOUT_MS)
            }
        }

        override fun close() {
            if (closed) return
            closed = true
            try { port.close() } catch (e: Exception) { /* already closed or unplugged */ }
        }
    }

    private const val WRITE_TIMEOUT_MS = 2_000
}
