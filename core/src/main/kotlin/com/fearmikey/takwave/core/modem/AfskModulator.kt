// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.core.modem

import com.fearmikey.takwave.core.ax25.Hdlc
import com.fearmikey.takwave.core.ax25.Nrzi
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sin

/** Bell 202 parameters shared by the modulator and demodulator. */
object Bell202 {
    const val SAMPLE_RATE = 48_000
    const val BAUD = 1200
    const val MARK_HZ = 1200.0
    const val SPACE_HZ = 2200.0
}

/**
 * Bell 202 AFSK modulator: 1200 baud, mark 1200 Hz, space 2200 Hz, NRZI, phase continuous.
 * Produces 16-bit mono PCM.
 */
class AfskModulator(private val sampleRate: Int = Bell202.SAMPLE_RATE) {

    /** Number of HDLC flags that fill [ms] milliseconds at 1200 baud (at least one). */
    fun flagsFor(ms: Int): Int = maxOf(1, ceil(ms * Bell202.BAUD / 8000.0).toInt())

    /**
     * Modulates one AX.25 frame (without FCS; it is added here).
     *
     * @param txDelayMs duration of the flag preamble sent while the transmitter keys up
     * @param txTailMs duration of trailing flags
     * @param amplitude output level, 0.0-1.0 of full scale
     */
    fun modulate(frame: ByteArray, txDelayMs: Int = 300, txTailMs: Int = 30, amplitude: Double = 0.5): ShortArray {
        val bits = Hdlc.encodeFrame(frame, flagsFor(txDelayMs), flagsFor(txTailMs))
        return modulateLevels(Nrzi.encode(bits), amplitude)
    }

    /** Modulates raw line levels (true = mark tone, false = space tone). */
    fun modulateLevels(levels: BooleanArray, amplitude: Double): ShortArray {
        val samplesPerBit = sampleRate.toDouble() / Bell202.BAUD
        val total = ceil(levels.size * samplesPerBit).toInt()
        val out = ShortArray(total)
        val markStep = 2.0 * PI * Bell202.MARK_HZ / sampleRate
        val spaceStep = 2.0 * PI * Bell202.SPACE_HZ / sampleRate
        val peak = amplitude.coerceIn(0.0, 1.0) * 32767.0
        var phase = 0.0
        for (i in 0 until total) {
            val bit = min(levels.size - 1, (i / samplesPerBit).toInt())
            phase += if (levels[bit]) markStep else spaceStep
            if (phase > 2.0 * PI) phase -= 2.0 * PI
            out[i] = (sin(phase) * peak).toInt().toShort()
        }
        applyRamp(out)
        return out
    }

    /** A steady tone for transmitter level and deviation setup. */
    fun tone(frequencyHz: Double, durationMs: Int, amplitude: Double): ShortArray {
        val total = (sampleRate.toLong() * durationMs / 1000).toInt()
        val step = 2.0 * PI * frequencyHz / sampleRate
        val peak = amplitude.coerceIn(0.0, 1.0) * 32767.0
        val out = ShortArray(total) { (sin(step * it) * peak).toInt().toShort() }
        applyRamp(out)
        return out
    }

    /** Mark and space tones alternating every bit period (a 600 Hz square-wave data pattern). */
    fun alternatingTones(durationMs: Int, amplitude: Double): ShortArray {
        val bits = durationMs * Bell202.BAUD / 1000
        return modulateLevels(BooleanArray(bits) { it % 2 == 0 }, amplitude)
    }

    /** Short raised edges so keying the audio on and off does not click. */
    private fun applyRamp(out: ShortArray) {
        val ramp = min(sampleRate / 1000, out.size / 2)
        for (i in 0 until ramp) {
            val g = i.toDouble() / ramp
            out[i] = (out[i] * g).toInt().toShort()
            out[out.size - 1 - i] = (out[out.size - 1 - i] * g).toInt().toShort()
        }
    }
}
