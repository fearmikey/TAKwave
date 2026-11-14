// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.core.codec

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM payload encryption with a pre-shared passphrase.
 *
 * **Not permitted on amateur radio frequencies.** This exists for Part 90 (business band) use.
 *
 * The key is PBKDF2-HMAC-SHA256(passphrase, [SALT], [ITERATIONS]) truncated to 256 bits. The salt
 * is fixed and public so that every station derives the same key from the same passphrase; the
 * passphrase therefore has to carry all of the entropy.
 *
 * Sealed form: 12-byte random nonce, ciphertext, 16-byte GCM tag.
 */
class PayloadCrypto(passphrase: CharArray, private val random: SecureRandom = SecureRandom()) {
    private val key: SecretKeySpec

    init {
        require(passphrase.isNotEmpty()) { "passphrase must not be empty" }
        val spec = PBEKeySpec(passphrase, SALT.toByteArray(Charsets.US_ASCII), ITERATIONS, 256)
        val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        key = SecretKeySpec(raw, "AES")
    }

    /** Encrypts [plain]; [aad] is authenticated but not encrypted. */
    fun seal(plain: ByteArray, aad: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_SIZE)
        random.nextBytes(nonce)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_SIZE * 8, nonce))
        cipher.updateAAD(aad)
        return nonce + cipher.doFinal(plain)
    }

    /** Decrypts and authenticates. Returns null when the data is too short, corrupt, or the key is wrong. */
    fun open(data: ByteArray, offset: Int, length: Int, aad: ByteArray): ByteArray? {
        if (length < OVERHEAD) return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_SIZE * 8, data, offset, NONCE_SIZE))
            cipher.updateAAD(aad)
            cipher.doFinal(data, offset + NONCE_SIZE, length - NONCE_SIZE)
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        const val SALT = "TAKwave-PSK-v0"
        const val ITERATIONS = 100_000
        const val NONCE_SIZE = 12
        const val TAG_SIZE = 16
        const val OVERHEAD = NONCE_SIZE + TAG_SIZE
    }
}
