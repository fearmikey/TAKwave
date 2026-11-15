// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.core.modem

import com.fearmikey.takwave.core.ax25.Crc16
import com.fearmikey.takwave.core.ax25.Fx25Receiver
import com.fearmikey.takwave.core.ax25.HdlcDeframer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Bell 202 AFSK demodulator.
 *
 * Signal path: band-pass prefilter, then two quadrature correlators (the input is mixed with
 * local 1200 Hz and 2200 Hz oscillators and low-pass filtered, giving the envelope of each
 * tone). Several "slicers" compare mark against space using different space-tone gains, which
 * covers radios whose audio is de-emphasised, flat, or pre-emphasised. Each slicer has its own
 * clock-recovery PLL, HDLC deframer and FX.25 receiver; frames found by more than one slicer
 * (or by both the plain and the FX.25 path) are de-duplicated by CRC.
 *
 * The clock recovery is the classic software-TNC digital PLL: a phase accumulator that wraps
 * once per bit and is pulled towards zero at every data transition. The idea of scaling the
 * accumulator by an "inertia" factor that depends on lock state follows the approach described
 * in the Dire Wolf documentation (WB2OSZ); the code here is an independent implementation.
 */
class AfskDemodulator(
    private val sampleRate: Int = Bell202.SAMPLE_RATE,
    spaceGains: FloatArray = DEFAULT_SPACE_GAINS,
    private val onFrame: (ByteArray) -> Unit,
) {
    private val prefilter = FirFilter(Fir.bandPass(tapsFor(1.6), 900.0, 2600.0, sampleRate))
    private val lpfTaps = Fir.lowPass(tapsFor(1.4), 760.0, sampleRate)
    private val markI = FirFilter(lpfTaps)
    private val markQ = FirFilter(lpfTaps)
    private val spaceI = FirFilter(lpfTaps)
    private val spaceQ = FirFilter(lpfTaps)

    private val markOsc = Oscillator(Bell202.MARK_HZ, sampleRate)
    private val spaceOsc = Oscillator(Bell202.SPACE_HZ, sampleRate)

    private val slicers = Array(spaceGains.size) { Slicer(spaceGains[it]) }

    private var sampleIndex = 0L
    private val recentCrc = IntArray(8)
    private val recentAt = LongArray(8) { Long.MIN_VALUE / 2 }
    private var recentPos = 0
    private val dedupeWindow = sampleRate / 4L

    /** Data carrier detect: a slicer's PLL is locked to 1200 baud data or flags are arriving. */
    @Volatile
    var dcd: Boolean = false
        private set

    /** RMS level of the most recent block, 0.0-1.0 of full scale. */
    @Volatile
    var rmsLevel: Float = 0f
        private set

    /** Peak level of the most recent block, 0.0-1.0 of full scale. */
    @Volatile
    var peakLevel: Float = 0f
        private set

    /**
     * Frames delivered only thanks to FX.25: the plain decoders did not get them first. A frame
     * that arrives intact is decoded by the plain path at its closing flag, before the check
     * bytes have even been received, so this counts real repairs.
     */
    @Volatile
    var fecRecovered: Long = 0L
        private set

    private fun tapsFor(bits: Double): Int = ((sampleRate * bits / Bell202.BAUD).toInt()) or 1

    fun process(samples: ShortArray, offset: Int = 0, length: Int = samples.size - offset) {
        var sumSq = 0.0
        var peak = 0
        for (n in offset until offset + length) {
            val raw = samples[n].toInt()
            sumSq += raw.toDouble() * raw
            val a = abs(raw)
            if (a > peak) peak = a

            val x = prefilter.filter(raw / 32768f)
            val mi = markI.filter(x * markOsc.cos())
            val mq = markQ.filter(x * markOsc.sin())
            val si = spaceI.filter(x * spaceOsc.cos())
            val sq = spaceQ.filter(x * spaceOsc.sin())
            markOsc.advance()
            spaceOsc.advance()
            val mark = sqrt(mi * mi + mq * mq)
            val space = sqrt(si * si + sq * sq)
            for (s in slicers) s.step(mark, space)
            sampleIndex++
        }
        if (length > 0) {
            rmsLevel = (sqrt(sumSq / length) / 32768.0).toFloat()
            peakLevel = peak / 32768f
        }
        dcd = slicers.any { it.carrier }
    }

    /** Forgets all receiver state, for example after transmitting. */
    fun reset() {
        prefilter.reset(); markI.reset(); markQ.reset(); spaceI.reset(); spaceQ.reset()
        for (s in slicers) s.reset()
        dcd = false
    }

    private fun deliver(frame: ByteArray, viaFec: Boolean) {
        val crc = Crc16.compute(frame) or (frame.size shl 16)
        // An FX.25 block ends well after the frame inside it, so its window is longer.
        val window = if (viaFec) dedupeWindow * 8 else dedupeWindow
        for (i in recentCrc.indices) {
            if (recentCrc[i] == crc && sampleIndex - recentAt[i] < window) return
        }
        recentCrc[recentPos] = crc
        recentAt[recentPos] = sampleIndex
        recentPos = (recentPos + 1) % recentCrc.size
        if (viaFec) fecRecovered++
        onFrame(frame)
    }

    /** A sine/cosine table covering one full repeat period of [freq] at the sample rate. */
    private class Oscillator(freq: Double, sampleRate: Int) {
        private val period: Int
        private val cosTable: FloatArray
        private val sinTable: FloatArray
        private var idx = 0

        init {
            val f = freq.toInt()
            period = sampleRate / gcd(sampleRate, f)
            cosTable = FloatArray(period) { cos(2.0 * PI * freq * it / sampleRate).toFloat() }
            sinTable = FloatArray(period) { sin(2.0 * PI * freq * it / sampleRate).toFloat() }
        }

        fun cos(): Float = cosTable[idx]
        fun sin(): Float = sinTable[idx]
        fun advance() {
            if (++idx == period) idx = 0
        }

        private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
    }

    private inner class Slicer(private val spaceGain: Float) {
        private val deframer = HdlcDeframer { deliver(it, viaFec = false) }
        private val fx25 = Fx25Receiver { frame, _ -> deliver(frame, viaFec = true) }
        private val step = ((1L shl 32) * Bell202.BAUD / sampleRate).toInt()
        private var pll = 0
        private var level = false
        private var sampledLevel = false

        // Lock detection: one history bit per data transition, 1 = arrived when expected.
        private var history = 0
        private var locked = false
        private var bitsSinceTransition = 0
        private var flagHold = 0
        private var lastFlagCount = 0L

        val carrier: Boolean get() = locked || flagHold > 0

        fun reset() {
            deframer.reset()
            fx25.reset()
            history = 0
            locked = false
            flagHold = 0
            pll = 0
        }

        fun step(mark: Float, space: Float) {
            val newLevel = mark > space * spaceGain
            val prev = pll
            pll += step
            if (pll < 0 && prev > 0) {
                // The accumulator wrapped: this is the middle of a bit.
                val bit = newLevel == sampledLevel
                deframer.pushBit(bit)
                fx25.pushBit(bit)
                sampledLevel = newLevel
                if (flagHold > 0) flagHold--
                val flags = deframer.flagCount
                if (flags != lastFlagCount) {
                    lastFlagCount = flags
                    if (Integer.bitCount(history) >= FLAG_LOCK_MIN) flagHold = FLAG_HOLD_BITS
                }
                if (++bitsSinceTransition > MAX_IDLE_BITS) {
                    history = 0
                    locked = false
                }
            }
            if (newLevel != level) {
                level = newLevel
                bitsSinceTransition = 0
                val good = abs(pll.toLong()) < GOOD_WINDOW
                history = (history shl 1) or (if (good) 1 else 0)
                val score = Integer.bitCount(history)
                if (score >= LOCK_ON) locked = true else if (score <= LOCK_OFF) locked = false
                pll = (pll * (if (locked) INERTIA_LOCKED else INERTIA_SEARCHING)).toInt()
            }
        }
    }

    companion object {
        /** Space-tone gains of the parallel slicers; above 1 compensates de-emphasised audio. */
        val DEFAULT_SPACE_GAINS = floatArrayOf(0.6f, 1.0f, 1.6f, 2.5f, 4.0f)

        private const val INERTIA_LOCKED = 0.74f
        private const val INERTIA_SEARCHING = 0.50f

        /** A transition within +/-0.2 bit of the expected instant counts as "good". */
        private const val GOOD_WINDOW = (0.2 * 4294967296.0).toLong()
        private const val LOCK_ON = 27
        private const val LOCK_OFF = 20
        private const val MAX_IDLE_BITS = 16
        private const val FLAG_LOCK_MIN = 16
        private const val FLAG_HOLD_BITS = 32
    }
}
