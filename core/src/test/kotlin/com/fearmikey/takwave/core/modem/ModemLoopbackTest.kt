// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.core.modem

import com.fearmikey.takwave.core.ax25.Ax25Address
import com.fearmikey.takwave.core.ax25.Ax25Frame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Random
import kotlin.math.pow
import kotlin.math.sqrt

class ModemLoopbackTest {
    private val fs = Bell202.SAMPLE_RATE
    private val amplitude = 0.5

    private fun randomFrames(count: Int, seed: Long): List<ByteArray> {
        val rnd = Random(seed)
        return List(count) { n ->
            val info = ByteArray(20 + rnd.nextInt(60))
            rnd.nextBytes(info)
            Ax25Frame(Ax25Address("TAKWV"), Ax25Address("N0CALL", n % 16), info).encode()
        }
    }

    /** Concatenates one transmission per frame, separated by short silences. */
    private fun transmit(frames: List<ByteArray>, amp: Double = amplitude): ShortArray {
        val mod = AfskModulator(fs)
        val gap = ShortArray(fs / 20)
        val parts = ArrayList<ShortArray>()
        parts.add(gap)
        for (f in frames) {
            parts.add(mod.modulate(f, txDelayMs = 150, txTailMs = 30, amplitude = amp))
            parts.add(gap)
        }
        val out = ShortArray(parts.sumOf { it.size })
        var p = 0
        for (part in parts) {
            part.copyInto(out, p)
            p += part.size
        }
        return out
    }

    /** Adds white Gaussian noise. SNR is signal power over noise power across the full band. */
    private fun addNoise(signal: ShortArray, snrDb: Double, seed: Long, signalAmp: Double = amplitude): ShortArray {
        val rnd = Random(seed)
        val signalRms = signalAmp * 32767.0 / sqrt(2.0)
        val sigma = signalRms / 10.0.pow(snrDb / 20.0)
        return ShortArray(signal.size) {
            (signal[it] + rnd.nextGaussian() * sigma).coerceIn(-32768.0, 32767.0).toInt().toShort()
        }
    }

    /** Resamples by [ratio] with linear interpolation, simulating a sound-card clock error. */
    private fun skew(signal: ShortArray, ratio: Double): ShortArray {
        val n = (signal.size / ratio).toInt() - 1
        return ShortArray(n) {
            val pos = it * ratio
            val i = pos.toInt()
            val frac = pos - i
            (signal[i] * (1 - frac) + signal[i + 1] * frac).toInt().toShort()
        }
    }

    /** Single-pole low-pass at 300 Hz: roughly the 6 dB/octave de-emphasis of an FM receiver. */
    private fun deEmphasize(signal: ShortArray): ShortArray {
        val a = 1.0 - Math.exp(-2.0 * Math.PI * 300.0 / fs)
        var y = 0.0
        return ShortArray(signal.size) {
            y += a * (signal[it] - y)
            (y * 3.0).coerceIn(-32768.0, 32767.0).toInt().toShort()
        }
    }

    private fun decode(audio: ShortArray): List<ByteArray> {
        val got = ArrayList<ByteArray>()
        val demod = AfskDemodulator(fs) { got.add(it) }
        // Feed in odd-sized blocks, as a real audio callback would.
        var p = 0
        while (p < audio.size) {
            val n = minOf(1777, audio.size - p)
            demod.process(audio, p, n)
            p += n
        }
        return got
    }

    private fun decodeRate(sent: List<ByteArray>, audio: ShortArray): Double {
        val got = decode(audio)
        val sentSet = sent.map { it.toList() }.toSet()
        val gotSet = got.map { it.toList() }.toSet()
        assertTrue("decoder produced a frame that was never sent", sentSet.containsAll(gotSet))
        assertEquals("duplicate frames delivered", gotSet.size, got.size)
        return gotSet.size.toDouble() / sentSet.size
    }

    @Test
    fun cleanLoopback() {
        val frames = randomFrames(100, 10)
        assertEquals(1.0, decodeRate(frames, transmit(frames)), 0.0)
    }

    @Test
    fun noisyLoopback() {
        val frames = randomFrames(200, 11)
        val clean = transmit(frames)
        val report = StringBuilder()
        for (snr in listOf(30.0, 20.0, 12.0, 8.0, 5.0, 3.0)) {
            val rate = decodeRate(frames, addNoise(clean, snr, 100 + snr.toLong()))
            report.append("SNR %4.1f dB: %.1f%% decoded%n".format(snr, rate * 100))
            if (snr >= 8.0) assertTrue("decode rate $rate at $snr dB", rate >= 0.99)
        }
        println(report)
    }

    @Test
    fun sampleRateSkew() {
        val frames = randomFrames(200, 12)
        val clean = transmit(frames)
        for (ratio in listOf(0.98, 1.02)) {
            val rate = decodeRate(frames, addNoise(skew(clean, ratio), 15.0, 7))
            println("skew %.2f at 15 dB: %.1f%% decoded".format(ratio, rate * 100))
            assertTrue("decode rate $rate at skew $ratio", rate >= 0.99)
        }
    }

    @Test
    fun deEmphasizedAndQuietAudio() {
        val frames = randomFrames(200, 13)
        val deEmph = deEmphasize(transmit(frames))
        val rate = decodeRate(frames, addNoise(deEmph, 25.0, 8))
        println("de-emphasised: %.1f%% decoded".format(rate * 100))
        assertTrue("decode rate $rate with de-emphasis", rate >= 0.99)

        val quiet = transmit(frames, amp = 0.01)
        val quietRate = decodeRate(frames, addNoise(quiet, 20.0, 9, signalAmp = 0.01))
        println("quiet (1%% of full scale): %.1f%% decoded".format(quietRate * 100))
        assertTrue("decode rate $quietRate at low level", quietRate >= 0.99)
    }

    @Test
    fun carrierDetectAndLevel() {
        val demod = AfskDemodulator(fs) { }
        demod.process(ShortArray(fs / 2))
        assertFalse(demod.dcd)
        assertEquals(0f, demod.rmsLevel, 0f)

        // Open-squelch noise must not look like a carrier most of the time.
        val rnd = Random(5)
        var busy = 0
        val blocks = 200
        repeat(blocks) {
            demod.process(ShortArray(960) { (rnd.nextGaussian() * 6000).toInt().toShort() })
            if (demod.dcd) busy++
        }
        assertTrue("DCD asserted on noise in $busy of $blocks blocks", busy < blocks / 10)

        // A long flag preamble followed by data must assert DCD before the frame ends.
        val frame = randomFrames(1, 14)[0]
        val audio = AfskModulator(fs).modulate(frame, txDelayMs = 300, txTailMs = 30, amplitude = amplitude)
        val at250ms = fs / 4
        demod.process(audio, 0, at250ms)
        assertTrue("DCD not asserted during preamble", demod.dcd)
        assertTrue(demod.rmsLevel > 0.3f && demod.rmsLevel < 0.4f)
        demod.process(audio, at250ms, audio.size - at250ms)
        demod.process(ShortArray(fs / 4))
        assertFalse("DCD stuck after the signal ended", demod.dcd)
    }

    @Test
    fun writesInspectionWav() {
        val frames = randomFrames(3, 15)
        val audio = addNoise(transmit(frames), 20.0, 3)
        val dir = File(System.getProperty("takwave.buildDir") ?: "build")
        dir.mkdirs()
        val file = File(dir, "modem-test.wav")
        writeWav(file, audio)
        assertEquals(44L + audio.size * 2, file.length())
        assertEquals(3, decode(audio).size)
    }

    private fun writeWav(file: File, pcm: ShortArray) {
        fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
        fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
        DataOutputStream(FileOutputStream(file).buffered()).use { o ->
            val dataLen = pcm.size * 2
            o.write("RIFF".toByteArray()); o.write(le32(36 + dataLen)); o.write("WAVE".toByteArray())
            o.write("fmt ".toByteArray()); o.write(le32(16)); o.write(le16(1)); o.write(le16(1))
            o.write(le32(fs)); o.write(le32(fs * 2)); o.write(le16(2)); o.write(le16(16))
            o.write("data".toByteArray()); o.write(le32(dataLen))
            for (s in pcm) o.write(le16(s.toInt()))
        }
    }
}
