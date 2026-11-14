// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.data

import android.content.Context
import android.net.Uri
import com.fearmikey.takwave.core.codec.AeadAlgorithm
import com.fearmikey.takwave.core.codec.AeadCipher
import com.fearmikey.takwave.core.codec.EciesCipher
import com.fearmikey.takwave.core.codec.KeyLoader
import com.fearmikey.takwave.core.codec.PayloadCipher
import java.io.File

/** The three kinds of key the operator can import. Each lives in one app-private file. */
enum class KeySlot(val fileName: String, val label: String) {
    SYMMETRIC("symmetric.key", "Symmetric key"),
    PRIVATE("private.pem", "Private key"),
    PUBLIC("public.pem", "Public key"),
}

/**
 * Imported key material. Files are kept in the app's private storage (`files/keys`), which other
 * apps cannot read and which is excluded from backups (`allowBackup="false"`). Keys are
 * validated on import, so a slot never holds something that cannot be used.
 */
class KeyFiles(context: Context) {
    private val appContext = context.applicationContext
    private val dir = File(appContext.filesDir, "keys")

    private fun file(slot: KeySlot) = File(dir, slot.fileName)

    private fun read(slot: KeySlot): ByteArray? = file(slot).takeIf { it.isFile }?.readBytes()

    /**
     * Reads the document at [uri], checks that it is a usable key for [slot] and stores it.
     * Returns a description of the key. Throws [IllegalArgumentException] with an operator-readable
     * reason when the file is not acceptable. Does file I/O: call off the main thread.
     */
    fun import(slot: KeySlot, uri: Uri): String {
        val bytes = appContext.contentResolver.openInputStream(uri)?.use { input ->
            val buf = ByteArray(KeyLoader.MAX_KEY_FILE_BYTES + 1)
            var n = 0
            while (n < buf.size) {
                val r = input.read(buf, n, buf.size - n)
                if (r < 0) break
                n += r
            }
            buf.copyOf(n)
        } ?: throw IllegalArgumentException("could not open the selected file")
        require(bytes.size <= KeyLoader.MAX_KEY_FILE_BYTES) { "the file is too large to be a key" }
        // Store the canonical form for symmetric keys so hex/Base64 text is only parsed once.
        val toStore = if (slot == KeySlot.SYMMETRIC) KeyLoader.symmetricKey(bytes) else bytes
        val description = describe(slot, toStore)
        dir.mkdirs()
        file(slot).writeBytes(toStore)
        return description
    }

    fun clear(slot: KeySlot) {
        file(slot).delete()
    }

    /** A non-secret description of the stored key, or null when the slot is empty. Does file I/O. */
    fun info(slot: KeySlot): String? {
        val bytes = read(slot) ?: return null
        return try {
            describe(slot, bytes)
        } catch (e: Exception) {
            "unreadable (${e.message})"
        }
    }

    private fun describe(slot: KeySlot, bytes: ByteArray): String = when (slot) {
        KeySlot.SYMMETRIC -> KeyLoader.symmetricKey(bytes).let { "${it.size * 8}-bit key, id ${KeyLoader.fingerprint(it)}" }
        // Never fingerprint a private key: the id would be derived from secret material.
        KeySlot.PRIVATE -> "${KeyLoader.describe(KeyLoader.privateKey(bytes))} private key"
        KeySlot.PUBLIC -> KeyLoader.publicKey(bytes).let { "${KeyLoader.describe(it)} public key, id ${KeyLoader.fingerprint(it)}" }
    }

    /**
     * Builds the payload cipher for the current settings, or null when encryption is off.
     * Throws [IllegalArgumentException] describing what is missing or wrong.
     */
    fun buildCipher(settings: Settings): PayloadCipher? {
        val algorithm = when (settings.cipher) {
            CipherChoice.AES_GCM -> AeadAlgorithm.AES_GCM
            CipherChoice.CHACHA20_POLY1305 -> AeadAlgorithm.CHACHA20_POLY1305
        }
        fun need(slot: KeySlot): ByteArray =
            read(slot) ?: throw IllegalArgumentException("no ${slot.label.lowercase()} has been imported")
        return try {
            when (settings.encryption) {
                EncryptionMode.OFF -> null
                EncryptionMode.PASSPHRASE -> {
                    require(settings.passphrase.isNotEmpty()) { "no passphrase is set" }
                    AeadCipher(KeyLoader.fromPassphrase(settings.passphrase.toCharArray()), algorithm, "passphrase")
                }
                EncryptionMode.KEY_FILE -> AeadCipher(KeyLoader.symmetricKey(need(KeySlot.SYMMETRIC)), algorithm, "key file")
                EncryptionMode.ECDH_PAIR -> {
                    val peer = KeyLoader.publicKey(need(KeySlot.PUBLIC))
                    val own = KeyLoader.privateKey(need(KeySlot.PRIVATE))
                    AeadCipher(KeyLoader.ecdhSharedKey(own, peer), algorithm, "ECDH with peer ${KeyLoader.fingerprint(peer)}")
                }
                EncryptionMode.ECIES_TEAM -> {
                    val team = KeyLoader.publicKey(need(KeySlot.PUBLIC))
                    val own = read(KeySlot.PRIVATE)?.let { KeyLoader.privateKey(it) }
                    EciesCipher(team, own)
                }
            }
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            // Typically NoSuchAlgorithmException: the platform lacks X25519 or ChaCha20.
            throw IllegalArgumentException("this Android version does not support the selected algorithm (${e.message})")
        }
    }
}
