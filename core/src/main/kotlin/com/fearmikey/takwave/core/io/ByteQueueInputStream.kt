// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.core.io

import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * An [InputStream] fed with chunks from another thread. It turns a link that delivers data in
 * callbacks (Bluetooth LE notifications) into the byte stream that [KissStreamTransport] reads.
 *
 * [read] blocks until bytes arrive, the stream is ended ([end], [close]: -1 once the queue is
 * drained) or failed ([fail]: [IOException] once the queue is drained). At most [maxQueuedBytes]
 * wait unread; beyond that new chunks are dropped and counted in [dropped], so a stalled reader
 * cannot exhaust memory.
 */
class ByteQueueInputStream(private val maxQueuedBytes: Int = 64 * 1024) : InputStream() {
    private val lock = ReentrantLock()
    private val ready = lock.newCondition()
    private val chunks = ArrayDeque<ByteArray>()
    private var head = 0
    private var queued = 0
    private var ended = false
    private var failure: String? = null

    /** Bytes thrown away because the reader had fallen [maxQueuedBytes] behind. */
    @Volatile
    var dropped: Long = 0
        private set

    /** Queues a copy of [data] for the reader. Ignored after [end] or [fail]. */
    fun offer(data: ByteArray) {
        if (data.isEmpty()) return
        lock.withLock {
            if (ended || failure != null) return
            if (queued + data.size > maxQueuedBytes) {
                dropped += data.size
                return
            }
            chunks.addLast(data.copyOf())
            queued += data.size
            ready.signalAll()
        }
    }

    /** No more data will come: what is queued is still read, then [read] returns -1. */
    fun end() {
        lock.withLock {
            ended = true
            ready.signalAll()
        }
    }

    /** The link failed: what is queued is still read, then [read] throws an [IOException] with [reason]. */
    fun fail(reason: String) {
        lock.withLock {
            if (failure == null && !ended) failure = reason
            ready.signalAll()
        }
    }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (off < 0 || len < 0 || off + len > b.size) throw IndexOutOfBoundsException()
        if (len == 0) return 0
        lock.withLock {
            while (chunks.isEmpty()) {
                failure?.let { throw IOException(it) }
                if (ended) return -1
                try {
                    ready.await()
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException("read interrupted")
                }
            }
            var n = 0
            while (n < len && chunks.isNotEmpty()) {
                val chunk = chunks.first()
                val k = minOf(len - n, chunk.size - head)
                chunk.copyInto(b, off + n, head, head + k)
                n += k
                head += k
                if (head == chunk.size) {
                    chunks.removeFirst()
                    head = 0
                }
            }
            queued -= n
            return n
        }
    }

    override fun available(): Int = lock.withLock { queued }

    /** Ends the stream and discards whatever is still queued. */
    override fun close() {
        lock.withLock {
            ended = true
            chunks.clear()
            head = 0
            queued = 0
            ready.signalAll()
        }
    }
}
