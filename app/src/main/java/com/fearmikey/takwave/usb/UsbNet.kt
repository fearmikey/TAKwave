// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.usb

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import com.fearmikey.takwave.core.io.KissLink
import com.fearmikey.takwave.core.ip.Ethernet
import com.fearmikey.takwave.core.ip.Rndis
import com.fearmikey.takwave.core.ip.RndisControl
import com.fearmikey.takwave.core.ip.UsbNetFraming
import com.fearmikey.takwave.ptt.SerialDevices
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** A radio (or other device) whose USB data link is a virtual network adapter. */
data class UsbNetOption(val key: String, val label: String, val framing: UsbNetFraming, val hasPermission: Boolean, val device: UsbDevice)

/**
 * An open USB network link: the byte-stream view the P25 transport needs (each read is one bulk
 * IN transfer, each write one bulk OUT transfer), plus what was learned while opening it.
 */
class UsbNetLink internal constructor(
    val framing: UsbNetFraming,
    val mac: ByteArray,
    val maxTransferSize: Int,
    private val connection: UsbDeviceConnection,
    private val claimed: List<UsbInterface>,
    private val bulkIn: UsbEndpoint,
    private val bulkOut: UsbEndpoint,
    private val control: UsbRndisControl?,
    private val stillAttached: () -> Boolean,
) : KissLink {
    @Volatile private var closed = false
    private val keepAlive: Thread? = control?.let { c ->
        // RNDIS devices may send keep-alives on the control channel and drop the link when unanswered.
        Thread({
            while (!closed) {
                try {
                    val msg = c.receive(0)
                    if (msg != null) Rndis.answer(msg)?.let { c.send(it) }
                    Thread.sleep(KEEPALIVE_POLL_MS)
                } catch (e: InterruptedException) {
                    return@Thread
                } catch (e: Exception) {
                    Log.d(TAG, "RNDIS control poll: ${e.message}")
                }
            }
        }, "takwave-rndis-control").apply { isDaemon = true; start() }
    }

    override val input: InputStream = object : InputStream() {
        override fun read(): Int = throw UnsupportedOperationException("whole transfers only")

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            while (true) {
                if (closed) return -1
                val n = connection.bulkTransfer(bulkIn, b, off, len, READ_TIMEOUT_MS)
                if (n >= 0) return n
                // -1 is a timeout or a lost device; only the device list can tell them apart.
                if (closed) return -1
                if (!stillAttached()) throw IOException("the radio was unplugged")
            }
        }
    }

    override val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (closed) throw IOException("USB link closed")
            // A transfer that is a whole number of packets would need a zero-length packet to end
            // it; a pad byte does the same and is ignored by RNDIS and by IP.
            val padded = if (len % bulkOut.maxPacketSize == 0) len + 1 else len
            val data = ByteArray(padded)
            b.copyInto(data, 0, off, off + len)
            val n = connection.bulkTransfer(bulkOut, data, padded, WRITE_TIMEOUT_MS)
            if (n < 0) throw IOException("USB write to the radio failed")
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        keepAlive?.interrupt()
        try { control?.send(Rndis.halt()) } catch (e: Exception) { /* device may be gone */ }
        for (i in claimed) try { connection.releaseInterface(i) } catch (e: Exception) { /* gone */ }
        connection.close()
    }

    private companion object {
        const val TAG = "TAKwave"
        const val READ_TIMEOUT_MS = 500
        const val WRITE_TIMEOUT_MS = 2_000
        const val KEEPALIVE_POLL_MS = 1_000L
    }
}

/** The RNDIS control channel on endpoint 0 (CDC encapsulated command and response). */
internal class UsbRndisControl(private val connection: UsbDeviceConnection, private val interfaceNumber: Int) : RndisControl {
    private val lock = Any()

    override fun send(message: ByteArray) {
        synchronized(lock) {
            val n = connection.controlTransfer(OUT_CLASS_INTERFACE, SEND_ENCAPSULATED_COMMAND, 0, interfaceNumber, message, message.size, 1_000)
            if (n < 0) throw IOException("the radio did not take an RNDIS control message")
        }
    }

    override fun receive(timeoutMs: Long): ByteArray? {
        val deadline = System.currentTimeMillis() + timeoutMs
        val buf = ByteArray(RESPONSE_SIZE)
        while (true) {
            val n = synchronized(lock) {
                connection.controlTransfer(IN_CLASS_INTERFACE, GET_ENCAPSULATED_RESPONSE, 0, interfaceNumber, buf, buf.size, 200)
            }
            // Nothing pending is a stall or a single zero byte.
            if (n >= 12) return buf.copyOf(n)
            if (System.currentTimeMillis() >= deadline) return null
            Thread.sleep(20)
        }
    }

    private companion object {
        const val OUT_CLASS_INTERFACE = 0x21
        const val IN_CLASS_INTERFACE = 0xA1
        const val SEND_ENCAPSULATED_COMMAND = 0x00
        const val GET_ENCAPSULATED_RESPONSE = 0x01
        const val RESPONSE_SIZE = 1025
    }
}

/**
 * Finds and opens USB network devices: RNDIS (as on Motorola APX radios) and CDC-ECM. Opening an
 * RNDIS device runs its initialization (MAC address, packet filter); for ECM the MAC address
 * comes from the device's descriptor, or a random local one.
 */
object UsbNetDevices {
    private const val CDC_SET_ETHERNET_PACKET_FILTER = 0x43
    private const val CDC_DATA = 0x0A

    /** The framing a device's interfaces offer, or null when it is not a USB network device. */
    fun framingOf(device: UsbDevice): UsbNetFraming? {
        var ecm = false
        for (i in 0 until device.interfaceCount) {
            val itf = device.getInterface(i)
            val c = itf.interfaceClass
            val s = itf.interfaceSubclass
            val p = itf.interfaceProtocol
            when {
                // RNDIS appears as CDC "ACM" with a vendor protocol, as wireless-controller RNDIS, or as misc RNDIS.
                c == UsbConstants.USB_CLASS_COMM && s == 2 && p == 0xFF -> return UsbNetFraming.RNDIS
                c == UsbConstants.USB_CLASS_WIRELESS_CONTROLLER && s == 1 && p == 3 -> return UsbNetFraming.RNDIS
                c == UsbConstants.USB_CLASS_MISC && s == 4 && p == 1 -> return UsbNetFraming.RNDIS
                c == UsbConstants.USB_CLASS_COMM && s == 6 -> ecm = true
            }
        }
        return if (ecm) UsbNetFraming.ECM else null
    }

    fun list(context: Context): List<UsbNetOption> {
        val manager = context.getSystemService(UsbManager::class.java) ?: return emptyList()
        return manager.deviceList.values.mapNotNull { d ->
            val framing = framingOf(d) ?: return@mapNotNull null
            val name = d.productName?.takeIf { it.isNotBlank() } ?: d.manufacturerName ?: "USB network device"
            UsbNetOption(
                SerialDevices.keyOf(d),
                "$name (USB network, ${framing.label}, %04x:%04x)".format(d.vendorId, d.productId),
                framing,
                manager.hasPermission(d),
                d,
            )
        }
    }

    /**
     * The USB network device to use for [deviceKey]: that device if it is one, or with an empty
     * key the first one attached. Null when there is none, so the caller falls back to serial.
     */
    fun find(context: Context, deviceKey: String): UsbNetOption? =
        list(context).firstOrNull { deviceKey.isEmpty() || it.key == deviceKey }

    /** Opens and brings up [option]. Throws [IllegalStateException] with a readable reason. */
    fun open(context: Context, option: UsbNetOption): UsbNetLink {
        val manager = context.getSystemService(UsbManager::class.java) ?: error("this device has no USB host support")
        val device = option.device
        check(manager.hasPermission(device)) { "USB permission for the radio has not been granted (Settings > Radio & audio)" }
        val connection = manager.openDevice(device) ?: error("could not open the radio's USB connection")
        val claimed = ArrayList<UsbInterface>()
        try {
            val interfaces = (0 until device.interfaceCount).map { device.getInterface(it) }
            val controlItf = interfaces.firstOrNull { isControl(it, option.framing) } ?: error("the radio's USB network control interface was not found")
            // The data interface with both bulk endpoints (for ECM, its alternate setting 1).
            val dataItf = interfaces.firstOrNull { it.interfaceClass == CDC_DATA && bulk(it, UsbConstants.USB_DIR_IN) != null && bulk(it, UsbConstants.USB_DIR_OUT) != null }
                ?: interfaces.firstOrNull { it.id != controlItf.id && bulk(it, UsbConstants.USB_DIR_IN) != null && bulk(it, UsbConstants.USB_DIR_OUT) != null }
                ?: error("the radio's USB network data interface was not found")
            for (itf in listOf(controlItf, dataItf)) {
                check(connection.claimInterface(itf, true)) { "could not claim the radio's USB interface ${itf.id}" }
                claimed.add(itf)
            }
            if (option.framing == UsbNetFraming.ECM) connection.setInterface(dataItf)
            val bulkIn = bulk(dataItf, UsbConstants.USB_DIR_IN)!!
            val bulkOut = bulk(dataItf, UsbConstants.USB_DIR_OUT)!!
            val attached = { manager.deviceList.values.any { it.deviceName == device.deviceName } }
            return when (option.framing) {
                UsbNetFraming.RNDIS -> {
                    val control = UsbRndisControl(connection, controlItf.id)
                    val rndis = Rndis.initialize(control)
                    UsbNetLink(UsbNetFraming.RNDIS, rndis.mac, minOf(rndis.maxTransferSize, Rndis.HOST_MAX_TRANSFER), connection, claimed, bulkIn, bulkOut, control, attached)
                }
                UsbNetFraming.ECM -> {
                    val mac = ecmMac(connection) ?: Ethernet.randomLocalMac()
                    // Directed, broadcast and all-multicast; some devices do not implement it.
                    connection.controlTransfer(0x21, CDC_SET_ETHERNET_PACKET_FILTER, 0x0E, controlItf.id, null, 0, 1_000)
                    UsbNetLink(UsbNetFraming.ECM, mac, 2048, connection, claimed, bulkIn, bulkOut, null, attached)
                }
            }
        } catch (e: Exception) {
            for (i in claimed) try { connection.releaseInterface(i) } catch (ignored: Exception) { /* best effort */ }
            connection.close()
            throw if (e is IllegalStateException) e else IllegalStateException("could not start the radio's USB network link: ${e.message}")
        }
    }

    private fun isControl(itf: UsbInterface, framing: UsbNetFraming): Boolean {
        val c = itf.interfaceClass
        val s = itf.interfaceSubclass
        val p = itf.interfaceProtocol
        return when (framing) {
            UsbNetFraming.RNDIS -> (c == UsbConstants.USB_CLASS_COMM && s == 2 && p == 0xFF) ||
                (c == UsbConstants.USB_CLASS_WIRELESS_CONTROLLER && s == 1 && p == 3) ||
                (c == UsbConstants.USB_CLASS_MISC && s == 4 && p == 1)
            UsbNetFraming.ECM -> c == UsbConstants.USB_CLASS_COMM && s == 6
        }
    }

    private fun bulk(itf: UsbInterface, direction: Int): UsbEndpoint? =
        (0 until itf.endpointCount).map { itf.getEndpoint(it) }
            .firstOrNull { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == direction }

    /** The MAC address from the ECM functional descriptor's iMACAddress string, or null. */
    private fun ecmMac(connection: UsbDeviceConnection): ByteArray? {
        val raw = connection.rawDescriptors ?: return null
        var i = 0
        while (i + 2 < raw.size) {
            val len = raw[i].toInt() and 0xFF
            if (len < 2) break
            // CS_INTERFACE, Ethernet Networking Functional Descriptor.
            if ((raw[i + 1].toInt() and 0xFF) == 0x24 && i + 3 < raw.size && (raw[i + 2].toInt() and 0xFF) == 0x0F) {
                val index = raw[i + 3].toInt() and 0xFF
                val buf = ByteArray(255)
                val n = connection.controlTransfer(0x80, 0x06, (0x03 shl 8) or index, 0x0409, buf, buf.size, 1_000)
                if (n < 4) return null
                val text = String(buf, 2, n - 2, Charsets.UTF_16LE)
                return Ethernet.parseMac(text)
            }
            i += len
        }
        return null
    }
}
