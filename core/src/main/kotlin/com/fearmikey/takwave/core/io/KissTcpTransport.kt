// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.core.io

import com.fearmikey.takwave.core.kiss.Kiss
import com.fearmikey.takwave.core.kiss.KissDecoder
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Talks to an external TNC (hardware, or Dire Wolf) over KISS-on-TCP. The TNC does the
 * modulation, channel access and PTT; TAKwave only exchanges AX.25 frames with it.
 * Reconnects automatically if the connection drops.
 */
class KissTcpTransport(
    private val host: String,
    private val port: Int,
    private val reconnectDelayMs: Long = 5_000,
) : FrameTransport {
    @Volatile private var running = false
    @Volatile private var socket: Socket? = null
    @Volatile private var output: OutputStream? = null
    private var thread: Thread? = null
    private val writeLock = Any()

    @Volatile
    override var lastError: String? = null
        private set

    val connected: Boolean get() = output != null

    override val state: RfState get() = RfState.IDLE
    override val inputLevel: Float get() = 0f

    override fun start(listener: FrameListener) {
        running = true
        thread = Thread({ run(listener) }, "takwave-kiss").apply {
            isDaemon = true
            start()
        }
    }

    private fun run(listener: FrameListener) {
        val buf = ByteArray(4096)
        while (running) {
            try {
                val s = Socket()
                socket = s
                s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                s.tcpNoDelay = true
                val decoder = KissDecoder { listener.onFrame(it) }
                output = s.getOutputStream()
                lastError = null
                val input = s.getInputStream()
                while (running) {
                    val n = input.read(buf)
                    if (n < 0) break
                    decoder.feed(buf, 0, n)
                }
            } catch (e: Exception) {
                if (running) lastError = "KISS TNC $host:$port: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                output = null
                try { socket?.close() } catch (e: Exception) { /* already closed */ }
                socket = null
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
            lastError = "KISS TNC $host:$port is not connected"
            return false
        }
        return try {
            synchronized(writeLock) {
                out.write(Kiss.encode(frame))
                out.flush()
            }
            true
        } catch (e: Exception) {
            lastError = "KISS write failed: ${e.message ?: e.javaClass.simpleName}"
            false
        }
    }

    override fun close() {
        running = false
        try { socket?.close() } catch (e: Exception) { /* already closed */ }
        thread?.interrupt()
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000
    }
}
