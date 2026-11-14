// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.core.cot

/**
 * Splits a TAK protocol v0 byte stream (back-to-back CoT XML documents) into single events on
 * `</event>` boundaries. Partial reads are buffered. An event larger than [maxEventBytes] is
 * discarded in full, up to and including its closing tag, without disturbing later events.
 * Not thread-safe; use one per connection.
 */
class CotStreamSplitter(private val maxEventBytes: Int = 64 * 1024) {
    private var buf = ByteArray(8192)
    private var len = 0
    private var scanFrom = 0
    private var discarding = false

    /** Number of oversized events thrown away so far. */
    var droppedOversize = 0
        private set

    fun feed(data: ByteArray, offset: Int, length: Int, onEvent: (String) -> Unit) {
        var p = offset
        val end = offset + length
        while (p < end) {
            val n = minOf(end - p, maxEventBytes + END.size - len)
            if (len + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, len + n))
            System.arraycopy(data, p, buf, len, n)
            len += n
            p += n
            drain(onEvent)
        }
    }

    private fun drain(onEvent: (String) -> Unit) {
        while (true) {
            val idx = indexOf(END, scanFrom, len)
            if (idx < 0) {
                if (len >= maxEventBytes) {
                    // Too big. Keep only a tail long enough to catch a closing tag split across reads.
                    if (!discarding) droppedOversize++
                    discarding = true
                    consume(len - (END.size - 1))
                } else {
                    scanFrom = maxOf(0, len - (END.size - 1))
                }
                return
            }
            val eventEnd = idx + END.size
            if (discarding) {
                discarding = false
            } else {
                val start = indexOf(START, 0, idx)
                if (start >= 0) onEvent(String(buf, start, eventEnd - start, Charsets.UTF_8))
            }
            consume(eventEnd)
        }
    }

    private fun consume(n: Int) {
        System.arraycopy(buf, n, buf, 0, len - n)
        len -= n
        scanFrom = 0
    }

    private fun indexOf(pattern: ByteArray, from: Int, limit: Int): Int {
        var i = from
        val last = limit - pattern.size
        outer@ while (i <= last) {
            for (j in pattern.indices) {
                if (buf[i + j] != pattern[j]) {
                    i++
                    continue@outer
                }
            }
            return i
        }
        return -1
    }

    private companion object {
        val START = "<event".toByteArray(Charsets.US_ASCII)
        val END = "</event>".toByteArray(Charsets.US_ASCII)
    }
}
