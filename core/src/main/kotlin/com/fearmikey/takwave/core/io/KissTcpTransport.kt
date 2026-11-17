// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.core.io

import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Talks to an external TNC (hardware, or Dire Wolf) over KISS-on-TCP. See [KissStreamTransport]
 * for what the TNC does and how the connection is kept up.
 */
class KissTcpTransport(
    host: String,
    port: Int,
    reconnectDelayMs: Long = 5_000,
    onStatus: (String) -> Unit = {},
) : KissStreamTransport(
    name = "KISS TNC $host:$port",
    reconnectDelayMs = reconnectDelayMs,
    onStatus = onStatus,
    connect = { connectTcp(host, port) },
) {
    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000

        fun connectTcp(host: String, port: Int): KissLink {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                socket.tcpNoDelay = true
            } catch (e: Exception) {
                try { socket.close() } catch (ignored: Exception) { /* nothing was opened */ }
                throw e
            }
            return object : KissLink {
                override val input: InputStream = socket.getInputStream()
                override val output: OutputStream = socket.getOutputStream()
                override fun close() = socket.close()
            }
        }
    }
}
