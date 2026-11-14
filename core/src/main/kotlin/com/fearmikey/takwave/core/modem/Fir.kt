// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.core.modem

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Windowed-sinc FIR design (Hamming window). Textbook method; see any DSP reference. */
object Fir {
    private fun sinc(x: Double): Double = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)

    private fun hamming(i: Int, n: Int): Double = 0.54 - 0.46 * cos(2.0 * PI * i / (n - 1))

    /** Low-pass with unity gain at DC. */
    fun lowPass(taps: Int, cutoffHz: Double, sampleRate: Int): FloatArray {
        val fc = cutoffHz / sampleRate
        val mid = (taps - 1) / 2.0
        val h = DoubleArray(taps) { 2.0 * fc * sinc(2.0 * fc * (it - mid)) * hamming(it, taps) }
        val sum = h.sum()
        return FloatArray(taps) { (h[it] / sum).toFloat() }
    }

    /** Band-pass with roughly unity gain at the centre of the passband. */
    fun bandPass(taps: Int, lowHz: Double, highHz: Double, sampleRate: Int): FloatArray {
        val f1 = lowHz / sampleRate
        val f2 = highHz / sampleRate
        val mid = (taps - 1) / 2.0
        val h = DoubleArray(taps) {
            val t = it - mid
            (2.0 * f2 * sinc(2.0 * f2 * t) - 2.0 * f1 * sinc(2.0 * f1 * t)) * hamming(it, taps)
        }
        val fCentre = (f1 + f2) / 2.0
        var re = 0.0
        var im = 0.0
        for (i in 0 until taps) {
            re += h[i] * cos(2.0 * PI * fCentre * (i - mid))
            im += h[i] * sin(2.0 * PI * fCentre * (i - mid))
        }
        val gain = kotlin.math.sqrt(re * re + im * im)
        return FloatArray(taps) { (h[it] / gain).toFloat() }
    }
}

/** Streaming FIR filter. The history is stored twice so the inner loop never wraps. */
class FirFilter(private val taps: FloatArray) {
    private val n = taps.size
    private val buf = FloatArray(n * 2)
    private var pos = 0

    fun filter(x: Float): Float {
        buf[pos] = x
        buf[pos + n] = x
        pos++
        if (pos == n) pos = 0
        var acc = 0f
        val base = pos
        for (i in 0 until n) acc += taps[i] * buf[base + i]
        return acc
    }

    fun reset() {
        buf.fill(0f)
        pos = 0
    }
}
