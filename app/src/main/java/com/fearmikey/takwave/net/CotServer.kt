// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.net

import com.fearmikey.takwave.core.cot.CotStreamSplitter
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * The local TAK server: plain TCP, streaming CoT XML (TAK protocol v0).
 *
 * It never starts a protocol negotiation, so clients stay in XML. Each client gets a reader
 * thread, which splits the stream into events and hands them to [onEvent], and a writer thread
 * fed by a bounded queue, so one stalled client cannot hold up anyone else or the radio path.
 */
class CotServer(
    private val port: Int,
    private val allowLan: Boolean,
    private val onEvent: (clientId: Int, xml: String) -> Unit,
    private val onClientsChanged: (count: Int) -> Unit,
    private val onLog: (String) -> Unit,
) {
    private class Client(val id: Int, val socket: Socket) {
        val outbox = LinkedBlockingQueue<ByteArray>(OUTBOX_LIMIT)
    }

    private val clients = ConcurrentHashMap<Int, Client>()
    private val nextId = AtomicInteger(1)
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var running = false

    val clientCount: Int get() = clients.size

    /** Binds and starts accepting. Throws [IOException] when the port cannot be bound. */
    @Throws(IOException::class)
    fun start() {
        // Not InetAddress.getLoopbackAddress(): on Android that is ::1, and ATAK connects to 127.0.0.1.
        val bind = InetAddress.getByName(if (allowLan) "0.0.0.0" else "127.0.0.1")
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(bind, port), 8)
        serverSocket = ss
        running = true
        Thread({ acceptLoop(ss) }, "takwave-accept").apply { isDaemon = true }.start()
        onLog("TAK server listening on ${bind.hostAddress}:$port")
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running) {
            val socket = try {
                ss.accept()
            } catch (e: IOException) {
                break
            }
            if (clients.size >= MAX_CLIENTS) {
                onLog("TAK client ${socket.inetAddress.hostAddress} refused: too many clients")
                closeQuietly(socket)
                continue
            }
            val client = Client(nextId.getAndIncrement(), socket)
            try {
                socket.tcpNoDelay = true
                socket.keepAlive = true
            } catch (e: IOException) {
                closeQuietly(socket)
                continue
            }
            clients[client.id] = client
            onLog("TAK client ${client.id} connected from ${socket.inetAddress.hostAddress}")
            onClientsChanged(clients.size)
            Thread({ readLoop(client) }, "takwave-client-${client.id}-rx").apply { isDaemon = true }.start()
            Thread({ writeLoop(client) }, "takwave-client-${client.id}-tx").apply { isDaemon = true }.start()
        }
    }

    private fun readLoop(client: Client) {
        val splitter = CotStreamSplitter()
        val buf = ByteArray(8192)
        var reportedOversize = 0
        try {
            val input = client.socket.getInputStream()
            while (running) {
                val n = input.read(buf)
                if (n < 0) break
                splitter.feed(buf, 0, n) { xml ->
                    try {
                        onEvent(client.id, xml)
                    } catch (e: Exception) {
                        // A bad event must never cost the client its connection.
                        onLog("error handling event from client ${client.id}: ${e.message}")
                    }
                }
                if (splitter.droppedOversize != reportedOversize) {
                    reportedOversize = splitter.droppedOversize
                    onLog("oversized CoT event from client ${client.id} dropped")
                }
            }
        } catch (e: IOException) {
            // Connection reset or closed by stop(); fall through to cleanup.
        } finally {
            drop(client, "disconnected")
        }
    }

    private fun writeLoop(client: Client) {
        try {
            val out = client.socket.getOutputStream()
            while (running && clients.containsKey(client.id)) {
                val data = client.outbox.take()
                if (data.isEmpty()) break
                out.write(data)
                out.flush()
            }
        } catch (e: IOException) {
            drop(client, "write failed")
        } catch (e: InterruptedException) {
            // Shutting down.
        }
    }

    private fun drop(client: Client, why: String) {
        if (clients.remove(client.id) != null) {
            closeQuietly(client.socket)
            client.outbox.clear()
            client.outbox.offer(POISON)
            if (running) {
                onLog("TAK client ${client.id} $why")
                onClientsChanged(clients.size)
            }
        }
    }

    private fun enqueue(client: Client, data: ByteArray) {
        if (!client.outbox.offer(data)) drop(client, "dropped: not reading fast enough")
    }

    /** Sends [xml] to every client, optionally skipping the one it came from. Never blocks. */
    fun sendToAll(xml: String, exceptClientId: Int? = null) {
        if (clients.isEmpty()) return
        val data = xml.toByteArray(Charsets.UTF_8)
        for (c in clients.values) if (c.id != exceptClientId) enqueue(c, data)
    }

    fun sendTo(clientId: Int, xml: String) {
        clients[clientId]?.let { enqueue(it, xml.toByteArray(Charsets.UTF_8)) }
    }

    fun stop() {
        running = false
        serverSocket?.let { closeQuietly(it) }
        serverSocket = null
        for (c in clients.values.toList()) {
            clients.remove(c.id)
            closeQuietly(c.socket)
            c.outbox.clear()
            c.outbox.offer(POISON)
        }
        onClientsChanged(0)
    }

    private fun closeQuietly(c: java.io.Closeable) {
        try {
            c.close()
        } catch (e: IOException) {
            // Nothing useful to do.
        }
    }

    private companion object {
        const val MAX_CLIENTS = 8
        const val OUTBOX_LIMIT = 256
        val POISON = ByteArray(0)
    }
}
