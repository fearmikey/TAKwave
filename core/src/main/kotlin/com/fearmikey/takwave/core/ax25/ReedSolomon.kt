// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.core.ax25

/**
 * Reed-Solomon code over GF(2^8) with the parameters FX.25 uses: field polynomial 0x11D, first
 * consecutive root alpha^1, primitive element alpha, codewords of 255 bytes. These are the
 * settings Dire Wolf passes to Phil Karn's library, so the check bytes agree with it.
 *
 * Byte 0 of a codeword is the coefficient of the highest power; the [nroots] check bytes follow
 * the data. The code corrects up to [nroots] / 2 wrong bytes.
 */
class ReedSolomon(val nroots: Int) {
    /** Data bytes in a full codeword. */
    val k: Int = NN - nroots

    /** Generator polynomial, highest power first; monic, so gen[0] = 1. */
    private val gen: IntArray

    init {
        require(nroots in 2..NN - 1 && nroots % 2 == 0) { "nroots must be even and below 255" }
        var g = intArrayOf(1)
        for (i in 1..nroots) {
            // g(x) *= (x - alpha^i); subtraction is addition (xor) in GF(2^8).
            val next = IntArray(g.size + 1)
            val root = EXP[i]
            for (j in g.indices) {
                next[j] = next[j] xor g[j]
                next[j + 1] = next[j + 1] xor mul(g[j], root)
            }
            g = next
        }
        gen = g
    }

    /** Returns the [nroots] check bytes for [data], which must be exactly [k] bytes. */
    fun encode(data: ByteArray): ByteArray {
        require(data.size == k) { "data must be $k bytes" }
        val rem = IntArray(nroots)
        for (b in data) {
            val feedback = (b.toInt() and 0xFF) xor rem[0]
            for (j in 0 until nroots - 1) rem[j] = rem[j + 1] xor mul(feedback, gen[j + 1])
            rem[nroots - 1] = mul(feedback, gen[nroots])
        }
        return ByteArray(nroots) { rem[it].toByte() }
    }

    /**
     * Corrects [block] (255 bytes) in place. Returns the number of bytes corrected, or -1 when the
     * block cannot be corrected; the block is then unchanged. Positions in [mustBeZero] (the
     * unsent part of a shortened code) are known to be zero: a correction there means the
     * decoder was fooled, and the block is rejected.
     */
    fun decode(block: ByteArray, mustBeZero: IntRange = IntRange.EMPTY): Int {
        require(block.size == NN) { "block must be $NN bytes" }
        val syn = IntArray(nroots)
        var anyError = false
        for (j in 0 until nroots) {
            val root = EXP[j + 1]
            var acc = 0
            for (b in block) acc = mul(acc, root) xor (b.toInt() and 0xFF)
            syn[j] = acc
            if (acc != 0) anyError = true
        }
        if (!anyError) return 0

        // Berlekamp-Massey: error locator lambda(x), lowest power first, lambda[0] = 1.
        val lambda = IntArray(nroots + 1).also { it[0] = 1 }
        var prev = IntArray(nroots + 1).also { it[0] = 1 }
        var degree = 0
        var shift = 1
        var prevDiscrepancy = 1
        for (n in 0 until nroots) {
            var d = syn[n]
            for (i in 1..degree) d = d xor mul(lambda[i], syn[n - i])
            if (d == 0) {
                shift++
                continue
            }
            val scale = mul(d, inv(prevDiscrepancy))
            val before = lambda.copyOf()
            for (i in 0..nroots - shift) lambda[i + shift] = lambda[i + shift] xor mul(scale, prev[i])
            if (2 * degree <= n) {
                degree = n + 1 - degree
                prev = before
                prevDiscrepancy = d
                shift = 1
            } else {
                shift++
            }
        }
        if (degree == 0 || degree > nroots / 2) return -1

        // Chien search. Position p is the coefficient of x^(254 - p); its locator inverse is alpha^(p + 1).
        val positions = IntArray(degree)
        var found = 0
        for (p in 0 until NN) {
            val xInv = EXP[(p + 1) % NN]
            var acc = 0
            for (i in degree downTo 0) acc = mul(acc, xInv) xor lambda[i]
            if (acc == 0) {
                if (found == degree) return -1
                positions[found++] = p
            }
        }
        if (found != degree) return -1
        for (p in positions) if (p in mustBeZero) return -1

        // Forney: omega(x) = S(x) * lambda(x) mod x^nroots; with first root alpha^1 the error is omega / lambda'.
        val omega = IntArray(nroots)
        for (i in 0 until nroots) {
            var acc = 0
            for (j in 0..minOf(i, degree)) acc = acc xor mul(lambda[j], syn[i - j])
            omega[i] = acc
        }
        val values = IntArray(degree)
        for ((n, p) in positions.withIndex()) {
            val xInv = EXP[(p + 1) % NN]
            var num = 0
            for (i in nroots - 1 downTo 0) num = mul(num, xInv) xor omega[i]
            var den = 0
            var i = 1
            while (i <= degree) {
                den = den xor mul(lambda[i], pow(xInv, i - 1))
                i += 2
            }
            if (den == 0) return -1
            values[n] = mul(num, inv(den))
        }
        for ((n, p) in positions.withIndex()) block[p] = (block[p].toInt() xor values[n]).toByte()
        return degree
    }

    companion object {
        const val NN = 255
        private const val FIELD_POLY = 0x11D
        private val EXP = IntArray(2 * NN)
        private val LOG = IntArray(NN + 1)

        init {
            var x = 1
            for (i in 0 until NN) {
                EXP[i] = x
                LOG[x] = i
                x = x shl 1
                if (x and 0x100 != 0) x = x xor FIELD_POLY
            }
            for (i in NN until 2 * NN) EXP[i] = EXP[i - NN]
        }

        private fun mul(a: Int, b: Int): Int = if (a == 0 || b == 0) 0 else EXP[LOG[a] + LOG[b]]
        private fun inv(a: Int): Int = EXP[NN - LOG[a]]
        private fun pow(a: Int, n: Int): Int = when {
            n == 0 -> 1
            a == 0 -> 0
            else -> EXP[(LOG[a] * n) % NN]
        }
    }
}
