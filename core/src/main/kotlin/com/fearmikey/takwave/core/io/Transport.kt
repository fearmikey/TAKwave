// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.core.io

/** Plays 16-bit mono PCM at 48 kHz. */
interface AudioSink {
    /** Blocks until the samples have been accepted for playback. */
    fun write(samples: ShortArray, offset: Int, length: Int)

    /** Blocks until everything written so far has actually been played. */
    fun drain()

    /** Stops playback immediately and unblocks a pending [write] or [drain]. Safe from any thread. */
    fun abort()
}

/** Captures 16-bit mono PCM at 48 kHz. */
interface AudioSource {
    /** Blocks until samples are available. Returns the number read, or -1 when the source has ended. */
    fun read(buffer: ShortArray, offset: Int, length: Int): Int
}

/** Push-to-talk control. Implementations must be safe to call from any thread and idempotent. */
interface Ptt {
    fun key()
    fun unkey()
}

/** For VOX-keyed radios and external TNCs: there is nothing to switch. */
object NoPtt : Ptt {
    override fun key() {}
    override fun unkey() {}
}

fun interface FrameListener {
    /** Called with a received AX.25 frame (FCS already checked and removed). */
    fun onFrame(frame: ByteArray)
}

enum class RfState { IDLE, RX, TX }

/** Sends and receives raw AX.25 frames (no flags, no FCS). */
interface FrameTransport {
    /** Starts receiving. Frames are delivered on a transport-owned thread. */
    fun start(listener: FrameListener)

    /**
     * Transmits one frame, blocking until it has gone out (including channel access).
     * Returns false when it could not be sent; the reason is in [lastError].
     */
    fun send(frame: ByteArray): Boolean

    val state: RfState

    /** Receive audio level 0.0-1.0, or 0 when the transport has no audio path. */
    val inputLevel: Float

    /** Explanation of the most recent failed [send], for the log. */
    val lastError: String?

    /** Stops everything and releases the transmitter. */
    fun close()
}
