// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.core.stats

import com.fearmikey.takwave.core.ax25.Ax25Address
import com.fearmikey.takwave.core.ax25.Ax25Frame
import com.fearmikey.takwave.core.codec.ChatMessage
import com.fearmikey.takwave.core.codec.DecodeResult
import com.fearmikey.takwave.core.codec.PacketCodec
import com.fearmikey.takwave.core.codec.PliMessage
import com.fearmikey.takwave.core.codec.Wire
import com.fearmikey.takwave.core.gateway.Gateway
import com.fearmikey.takwave.core.gateway.RxKind
import com.fearmikey.takwave.core.sched.FakeClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelHealthTest {
    private val clock = FakeClock()
    private val pli = PliMessage("ANDROID-589520ccfcd20f01", "NOVA", "a-f-G-U-C", 415_000_000, -713_000_000)

    @Test
    fun sequenceCounterRoundTripsAndOldReceiversIgnoreIt() {
        val codec = PacketCodec()
        val plain = codec.encode(pli)
        val counted = codec.encode(pli, seq = 200)
        assertEquals(plain.size + 2, counted.size)
        val d = codec.decode(counted) as DecodeResult.Ok
        assertEquals(200, d.seq)
        assertEquals(0, d.hops)
        assertEquals(0, d.ageS)
        assertEquals(pli, d.message)
        // What a receiver that predates the counter does: one extension byte with hop count 0 and
        // no age, then trailing data it ignores.
        val ext = counted[plain.size].toInt() and 0xFF
        assertEquals(Wire.EXT_SEQ, ext)
        assertEquals(0, ext and Wire.EXT_HOPS_MASK)
        assertEquals(0, ext and Wire.EXT_AGE)
        // A truncated counter is a malformed packet.
        assertTrue(codec.decode(counted.copyOf(counted.size - 1)) is DecodeResult.Error)
        assertNull((codec.decode(plain) as DecodeResult.Ok).seq)
    }

    @Test
    fun onlyPositionReportsCarryTheCounter() {
        val a = Gateway(clock, Ax25Address("N0AAA"))
        a.enqueue(pli)
        a.enqueue(ChatMessage("m1", "u", "NOVA", "All Chat Rooms", "All Chat Rooms", "hi"))
        val items = generateSequence { a.pollFrame() }.toList()
        val seqs = items.associate { it.message::class.simpleName to (PacketCodec().decode(Ax25Frame.decode(it.frame)!!.info) as DecodeResult.Ok).seq }
        assertEquals(0, seqs["PliMessage"])
        assertNull(seqs["ChatMessage"])
    }

    @Test
    fun missedReportsAreCountedFromCounterGaps() {
        val a = Gateway(clock, Ax25Address("N0AAA"))
        val b = Gateway(clock, Ax25Address("N0BBB"))
        val frames = (0 until 20).map {
            a.enqueue(pli.copy(latE7 = 415_000_000 + it))
            a.pollFrame()!!.frame
        }
        // B hears all but three.
        for ((i, f) in frames.withIndex()) {
            if (i == 4 || i == 9 || i == 10) continue
            clock.advance(6_000)
            assertEquals(RxKind.DELIVERED, b.onRfFrame(f).kind)
        }
        val h = b.heardStations().single()
        assertEquals(17, h.pliReceived)
        assertEquals(3, h.pliMissed)
        assertEquals(17f / 20f, h.pliReception!!, 1e-6f)
    }

    @Test
    fun aRestartIsNotCountedAsLoss() {
        val b = Gateway(clock, Ax25Address("N0BBB"))
        val codec = PacketCodec()
        fun frame(seq: Int, n: Int) =
            Ax25Frame(Gateway.DESTINATION, Ax25Address("N0AAA"), codec.encode(pli.copy(latE7 = n), seq = seq)).encode()
        b.onRfFrame(frame(120, 1))
        clock.advance(6_000)
        b.onRfFrame(frame(121, 2))
        clock.advance(6_000)
        // The other station restarted and counts from 0 again.
        b.onRfFrame(frame(0, 3))
        val h = b.heardStations().single()
        assertEquals(3, h.pliReceived)
        assertEquals(0, h.pliMissed)
        assertNull("too few reports for a figure", h.pliReception)
    }

    @Test
    fun receivedFramesAreClassifiedForTheStatistics() {
        val a = Gateway(clock, Ax25Address("N0AAA"))
        val b = Gateway(clock, Ax25Address("N0BBB"))
        a.enqueue(pli)
        val f = a.pollFrame()!!.frame
        assertEquals(RxKind.DELIVERED, b.onRfFrame(f).kind)
        assertEquals(RxKind.REPEAT, b.onRfFrame(f).kind)
        assertEquals(RxKind.REPEAT, a.onRfFrame(f).kind)
        val aprs = Ax25Frame(Ax25Address("APRS"), Ax25Address("N0XYZ"), "!4130.00N/07118.00W-".toByteArray()).encode()
        assertEquals(RxKind.OTHER, b.onRfFrame(aprs).kind)
        val garbage = Ax25Frame(Gateway.DESTINATION, Ax25Address("N0XYZ"), byteArrayOf(0x54, 9, 9, 9)).encode()
        assertEquals(RxKind.INVALID, b.onRfFrame(garbage).kind)
    }

    @Test
    fun statisticsRollOverTheWindow() {
        val stats = ChannelStats(clock, windowMs = 60_000, bucketMs = 10_000)
        repeat(30) {
            stats.sampleChannel(1_000, busy = it % 3 == 0)
            clock.advance(1_000)
        }
        stats.recordTx(1_500, repeat = false)
        stats.recordTx(500, repeat = true)
        stats.count(ChannelEvent.RX_DELIVERED, 4)
        stats.count(ChannelEvent.RX_REPAIRED)
        var h = stats.snapshot()
        assertEquals(30_000, h.windowMs)
        assertEquals(1f / 3f, h.busyFraction!!, 0.01f)
        assertEquals(2_000, h.ownAirtimeMs)
        assertEquals(2_000f / 30_000f, h.ownAirtimeFraction, 1e-4f)
        assertEquals(1, h[ChannelEvent.TX])
        assertEquals(1, h[ChannelEvent.TX_REPEAT])
        assertEquals(4, h[ChannelEvent.RX_DELIVERED])
        assertEquals(1, h[ChannelEvent.RX_REPAIRED])

        // A minute later everything above has left the window.
        clock.advance(61_000)
        h = stats.snapshot()
        assertEquals(60_000, h.windowMs)
        assertNull("nothing was listened to in the window", h.busyFraction)
        assertEquals(0, h.ownAirtimeMs)
        assertEquals(0, h[ChannelEvent.RX_DELIVERED])
    }
}
