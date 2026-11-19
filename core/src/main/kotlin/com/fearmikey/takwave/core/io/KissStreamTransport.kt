// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.core.io

import com.fearmikey.takwave.core.kiss.Kiss
import com.fearmikey.takwave.core.kiss.KissDecoder
import java.io.InputStream
import java.io.OutputStream

/** One open byte-stream connection to a KISS TNC: a TCP socket, a Bluetooth serial link, ... */
interface KissLink {
    val input: InputStream
    val output: OutputStream

    /** Closes the connection and unblocks a pending read. Safe from any thread and more than once. */
    fun close()
}

/**
 * Talks KISS to an external TNC over any byte stream. The TNC does the modulation, channel
 * access and PTT; TAKwave only exchanges AX.25 frames with it.
 *
 * [connect] opens the link and may block; it throws with a user-readable message when it
 * cannot. The link is reopened automatically after [reconnectDelayMs] when it fails or drops.
 * [name] describes the TNC in error messages. [onStatus] is told when the link comes up, goes
 * down or cannot be opened; the same message is not reported twice in a row. [onRaw], when set,
 * sees every chunk read from the link before KISS decoding, for other framings on the same stream.
 */
open class KissStreamTransport(
    private val name: String,
    private val reconnectDelayMs: Long = 5_000,
    private val onStatus: (String) -> Unit = {},
    private val onRaw: ((ByteArray, Int, Int) -> Unit)? = null,
    private val connect: () -> KissLink,
) : FrameTransport {
    @Volatile private var running = false
    @Volatile private var link: KissLink? = null
    @Volatile private var output: OutputStream? = null
    private var thread: Thread? = null
    private val writeLock = Any()
    private var lastStatus: String? = null

    @Volatile
    override var lastError: String? = null
        protected set

    val connected: Boolean get() = output != null

    override val state: RfState get() = RfState.IDLE
    override val inputLevel: Float get() = 0f

    @Volatile
    override var lastAirtimeMs: Long = 0L
        protected set

    override fun start(listener: FrameListener) {
        running = true
        thread = Thread({ run(listener) }, "takwave-kiss").apply {
            isDaemon = true
            start()
        }
    }

    private fun report(message: String) {
        if (message == lastStatus) return
        lastStatus = message
        try {
            onStatus(message)
        } catch (e: Exception) {
            // A failing observer must not take the link down.
        }
    }

    private fun run(listener: FrameListener) {
        val buf = ByteArray(4096)
        while (running) {
            var opened = false
            try {
                val l = connect()
                link = l
                // close() may have run while connect() was blocking and found no link to close.
                if (!running) return
                val decoder = KissDecoder { listener.onFrame(it) }
                val input = l.input
                output = l.output
                opened = true
                lastError = null
                report("$name: connected")
                while (running) {
                    val n = input.read(buf)
                    if (n < 0) break
                    onRaw?.invoke(buf, 0, n)
                    decoder.feed(buf, 0, n)
                }
                if (running) report("$name: connection closed by the TNC")
            } catch (e: Exception) {
                if (running) {
                    val reason = e.message ?: e.javaClass.simpleName
                    lastError = "$name: $reason"
                    report(if (opened) "$name: connection lost ($reason)" else "$name: $reason")
                }
            } finally {
                output = null
                try { link?.close() } catch (e: Exception) { /* already closed */ }
                link = null
            }
            if (running) {
                try {
                    Thread.sleep(reconnectDelayMs)
                } catch (e: InterruptedException) {
                    return
                }
            }
        }
    }

    override fun send(frame: ByteArray): Boolean {
        val out = output
        if (out == null) {
            lastError = "$name is not connected"
            return false
        }
        return try {
            synchronized(writeLock) {
                out.write(Kiss.encode(frame))
                out.flush()
            }
            // The TNC's own TXDELAY is unknown; count the frame with FCS, flags and typical bit stuffing at 1200 baud.
            lastAirtimeMs = (frame.size + 4) * 8L * 1000L * 21 / 20 / 1200
            true
        } catch (e: Exception) {
            lastError = "KISS write failed: ${e.message ?: e.javaClass.simpleName}"
            false
        }
    }

    /** Writes [bytes] to the link as they are, without KISS framing. False when not connected or the write fails. */
    fun sendRaw(bytes: ByteArray): Boolean {
        val out = output
        if (out == null) {
            lastError = "$name is not connected"
            return false
        }
        return try {
            synchronized(writeLock) {
                out.write(bytes)
                out.flush()
            }
            true
        } catch (e: Exception) {
            lastError = "$name: write failed: ${e.message ?: e.javaClass.simpleName}"
            false
        }
    }

    override fun close() {
        running = false
        try { link?.close() } catch (e: Exception) { /* already closed */ }
        thread?.interrupt()
    }
}
