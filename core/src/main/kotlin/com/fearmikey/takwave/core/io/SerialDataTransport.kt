// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.core.io

import com.fearmikey.takwave.core.ax25.Crc16
import java.util.Base64

/**
 * A digital radio whose data port carries bytes over the air unchanged: whatever goes in on one
 * radio comes out of the other radios' data ports. [throughputBps] and [keyUpMs] are the default
 * air time estimate that paces sending; both can be overridden.
 */
enum class SerialDataProfile(val throughputBps: Int, val keyUpMs: Int) {
    /**
     * Icom D-STAR DV data in the slow-data channel: 1200 bit/s raw alongside the voice frames,
     * about 900 bit/s of user data after the slow-data headers and sync frames.
     */
    DSTAR_SLOW(900, 400),

    /** Icom D-STAR DV Fast Data: about 3.5 kbit/s raw, sent in place of the voice frames. */
    DSTAR_FAST(2_800, 400),

    /** NXDN transparent data (Kenwood NX, Icom IDAS): 4800 bit/s channel, roughly 1.8 kbit/s of user data with FEC. */
    NXDN(1_800, 500),
}

/**
 * How frames are written to the radio's data port. Receivers always accept both, so stations
 * with different settings still hear each other (as long as the radios pass what is sent).
 */
enum class SerialEncoding {
    /** One KISS data frame per frame: compact, but uses every byte value. */
    BINARY,

    /**
     * One text line per frame: `TW1:` + Base64 + CR LF. A third longer, but only printable ASCII,
     * for radios whose data path is not 8-bit clean or that send data line by line.
     */
    TEXT,
}

/**
 * Carries TAKwave's AX.25 frames through a radio's transparent serial data port (D-STAR DV data,
 * NXDN transparent data), on a USB serial port or a Bluetooth serial link. The radio keys itself
 * when data arrives and does its own modulation, so there is no PTT here.
 *
 * Those channels are byte streams with no error detection of their own that TAKwave can rely on,
 * so each frame carries its 16-bit FCS (the AX.25 frame check sequence, low byte first); frames
 * that fail the check are dropped. In [SerialEncoding.BINARY] the frame and FCS go as one KISS
 * data frame; in [SerialEncoding.TEXT] as one Base64 text line. Both are always decoded.
 *
 * [connect] opens the link (see [KissStreamTransport] for reconnect and [onStatus]). [send] waits
 * for the estimated air time (unless [pace] is false) so TAKwave's priority queue, not the radio's
 * small buffer, decides what goes next. [onDiagnostic], when set, gets damaged frames, unknown text
 * lines and, at most [RAW_LOG_PER_MINUTE] times a minute, raw received bytes as hex.
 */
class SerialDataTransport(
    name: String,
    private val throughputBps: Int,
    private val keyUpMs: Int,
    private val encoding: SerialEncoding = SerialEncoding.BINARY,
    private val pace: Boolean = true,
    onStatus: (String) -> Unit = {},
    private val onDiagnostic: ((String) -> Unit)? = null,
    private val nowMs: () -> Long = System::currentTimeMillis,
    reconnectDelayMs: Long = 5_000,
    connect: () -> KissLink,
) : FrameTransport {
    constructor(
        name: String,
        profile: SerialDataProfile,
        encoding: SerialEncoding = SerialEncoding.BINARY,
        pace: Boolean = true,
        onStatus: (String) -> Unit = {},
        onDiagnostic: ((String) -> Unit)? = null,
        connect: () -> KissLink,
    ) : this(name, profile.throughputBps, profile.keyUpMs, encoding, pace, onStatus, onDiagnostic, connect = connect)

    init {
        require(throughputBps > 0 && keyUpMs >= 0)
    }

    private val label = name
    @Volatile private var listener: FrameListener? = null
    private val lines = LineDecoder { onLine(it) }
    private var rawWindowStart = 0L
    private var rawInWindow = 0

    private val inner = KissStreamTransport(name, reconnectDelayMs, onStatus, onRaw = { b, off, len -> onRawBytes(b, off, len) }, connect = connect)

    /** True while the serial or Bluetooth link to the radio is open. */
    val connected: Boolean get() = inner.connected

    @Volatile private var ownError: String? = null

    override val lastError: String? get() = ownError ?: inner.lastError

    @Volatile
    override var lastAirtimeMs: Long = 0L
        private set

    /** Frames dropped because their FCS did not match, since start. */
    @Volatile
    var badFrames: Int = 0
        private set

    override val state: RfState get() = RfState.IDLE
    override val inputLevel: Float get() = 0f

    override fun start(listener: FrameListener) {
        this.listener = listener
        inner.start { data -> deliver(data, "KISS") }
    }

    private fun deliver(data: ByteArray, how: String) {
        val frame = unwrap(data)
        if (frame == null) {
            badFrames++
            diagnostic { "$label: dropped a damaged $how frame (${data.size} bytes, check failed): ${P25DataTransport.hex(data)}" }
        } else {
            listener?.onFrame(frame)
        }
    }

    // Reader thread only.
    private fun onRawBytes(b: ByteArray, off: Int, len: Int) {
        lines.feed(b, off, len)
        if (onDiagnostic == null) return
        val now = nowMs()
        if (now - rawWindowStart >= 60_000) {
            rawWindowStart = now
            rawInWindow = 0
        }
        if (rawInWindow++ < RAW_LOG_PER_MINUTE) {
            diagnostic { "$label: received $len bytes: ${P25DataTransport.hex(b.copyOfRange(off, off + len))}" }
        }
    }

    private fun onLine(line: String) {
        if (!line.startsWith(TEXT_PREFIX)) {
            // Binary KISS frames contain no line breaks worth reporting; only show text-looking lines.
            if (line.isNotBlank() && line.all { it.code in 0x20..0x7E }) diagnostic { "$label: ignored text line: ${line.take(80)}" }
            return
        }
        val data = try {
            Base64.getDecoder().decode(line.substring(TEXT_PREFIX.length).trim())
        } catch (e: IllegalArgumentException) {
            badFrames++
            diagnostic { "$label: dropped a damaged text frame (not Base64): ${line.take(80)}" }
            return
        }
        deliver(data, "text")
    }

    private inline fun diagnostic(describe: () -> String) {
        val observer = onDiagnostic ?: return
        try {
            observer(describe())
        } catch (e: Exception) {
            // An observer failing must not take the link down.
        }
    }

    override fun send(frame: ByteArray): Boolean {
        ownError = null
        val sent = when (encoding) {
            SerialEncoding.BINARY -> inner.send(wrap(frame))
            SerialEncoding.TEXT -> inner.sendRaw(textLine(frame))
        }
        if (!sent) return false
        lastAirtimeMs = estimateAirtimeMs(frame.size)
        if (pace) {
            try {
                Thread.sleep(lastAirtimeMs)
            } catch (e: InterruptedException) {
                // Already handed to the radio; keep the interrupt for the caller.
                Thread.currentThread().interrupt()
            }
        }
        return true
    }

    /** Key-up plus the encoded frame at the configured throughput. */
    fun estimateAirtimeMs(frameSize: Int): Long {
        val bytes = when (encoding) {
            SerialEncoding.BINARY -> frameSize + OVERHEAD_BYTES
            SerialEncoding.TEXT -> TEXT_PREFIX.length + (frameSize + 2 + 2) / 3 * 4 + 2
        }
        return keyUpMs + bytes * 8L * 1000L / throughputBps
    }

    override fun close() = inner.close()

    /** Splits a byte stream into lines at LF (CR dropped), capped so binary data cannot grow it. */
    private class LineDecoder(private val onLine: (String) -> Unit) {
        private val buf = StringBuilder()
        private var overflow = false

        fun feed(b: ByteArray, off: Int, len: Int) {
            for (i in off until off + len) {
                val c = b[i].toInt() and 0xFF
                when {
                    c == '\n'.code -> {
                        if (!overflow) onLine(buf.toString())
                        buf.setLength(0)
                        overflow = false
                    }
                    c == '\r'.code -> {}
                    buf.length >= MAX_LINE -> overflow = true
                    else -> buf.append(c.toChar())
                }
            }
        }
    }

    companion object {
        /** FCS (2) plus FEND, command byte and FEND, plus an allowance for escaped bytes. */
        const val OVERHEAD_BYTES = 8

        /** Start of a text-encoded frame line; the digit is the text framing version. */
        const val TEXT_PREFIX = "TW1:"

        /** Raw-byte diagnostics are limited to this many chunks a minute. */
        const val RAW_LOG_PER_MINUTE = 20

        private const val MAX_LINE = 1024

        /** The AX.25 frame with its FCS appended, as carried inside the KISS frame or text line. */
        fun wrap(frame: ByteArray): ByteArray = Crc16.appendFcs(frame)

        /** One text line for [frame]: prefix, Base64 of frame and FCS, CR LF. */
        fun textLine(frame: ByteArray): ByteArray =
            (TEXT_PREFIX + Base64.getEncoder().encodeToString(wrap(frame)) + "\r\n").toByteArray(Charsets.US_ASCII)

        /** The AX.25 frame from a received payload, or null when it is too short or the FCS does not match. */
        fun unwrap(data: ByteArray): ByteArray? =
            if (data.size >= MIN_FRAME + 2 && Crc16.check(data)) data.copyOf(data.size - 2) else null

        /** Shortest possible AX.25 UI frame: two addresses, control and PID. */
        private const val MIN_FRAME = 16
    }
}
