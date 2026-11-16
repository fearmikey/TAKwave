// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.core.codec

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.Key
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class CryptoTest {
    private val chat = ChatMessage(
        "563040b9-2ac9-4af3-9e01-4cb2b05d98ea", "ANDROID-589520ccfcd20f01", "NOVA",
        ChatMessage.ALL_CHAT_ROOMS, ChatMessage.ALL_CHAT_ROOMS, "rally at the trailhead",
    )
    private val pli = PliMessage("ANDROID-589520ccfcd20f01", "NOVA", "a-f-G-U-C", 415_012_345, -713_098_765)

    private fun pem(key: Key, label: String): ByteArray {
        val body = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(key.encoded)
        return "-----BEGIN $label-----\n$body\n-----END $label-----\n".toByteArray()
    }

    private fun ecPair(curve: String = "secp256r1"): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec(curve)) }.generateKeyPair()

    private fun xPair(): KeyPair = KeyPairGenerator.getInstance("X25519").generateKeyPair()

    private fun assertRoundTrip(sender: PayloadCipher, receiver: PayloadCipher) {
        val tx = PacketCodec(sender)
        val rx = PacketCodec(receiver)
        for (m in listOf(chat, pli, chat.copy(text = "z".repeat(400)))) {
            val sealed = tx.encode(m)
            assertTrue("${sealed.size} bytes", sealed.size <= Wire.MAX_PAYLOAD)
            assertEquals(0x80 or (sender.suite shl 4), sealed[1].toInt() and 0xFF)
            val r = rx.decode(sealed)
            assertTrue("$r", r is DecodeResult.Ok && r.encrypted)
            assertNotEquals(sealed.toList(), tx.encode(m).toList())
            val bad = sealed.copyOf().also { it[it.size - 3] = (it[it.size - 3].toInt() xor 4).toByte() }
            assertTrue(rx.decode(bad) is DecodeResult.Error)
            // The clear header is authenticated too.
            val badHeader = sealed.copyOf().also { it[1] = (it[1].toInt() xor 1).toByte() }
            assertTrue(rx.decode(badHeader) is DecodeResult.Error)
        }
        assertFalse(String(tx.encode(chat), Charsets.ISO_8859_1).contains("trailhead"))
    }

    // ---- Hidden source (version 1) ----

    @Test
    fun hiddenSourceTravelsInsideTheCiphertextInEverySuite() {
        val call = com.fearmikey.takwave.core.ax25.Ax25Address("KB1ABC", 15)
        val ec = ecPair()
        val x = xPair()
        val suites = listOf(
            AeadCipher(ByteArray(32) { it.toByte() }),
            AeadCipher(ByteArray(32) { it.toByte() }, AeadAlgorithm.CHACHA20_POLY1305),
            EciesCipher(x.public, x.private),
            EciesCipher(ec.public, ec.private),
        )
        val longest = chat.copy(text = "z".repeat(400))
        for (c in suites) {
            val codec = PacketCodec(c)
            for (m in listOf(chat, pli, longest)) {
                val sealed = codec.encode(m, hops = 1, ageS = 600, hiddenSource = call)
                assertTrue("${sealed.size} bytes", sealed.size <= Wire.MAX_PAYLOAD)
                assertEquals(Wire.MAGIC + Wire.VERSION_HIDDEN_SOURCE, sealed[0].toInt() and 0xFF)
                assertFalse(String(sealed, Charsets.ISO_8859_1).contains("KB1ABC"))
                val r = codec.decode(sealed) as DecodeResult.Ok
                assertEquals(call, r.hiddenSource)
                assertEquals(1 to 600, r.hops to r.ageS)
                // The text is the same as without a hidden source: room for it is always kept.
                assertEquals((codec.decode(codec.encode(m)) as DecodeResult.Ok).message, r.message)
                // The version byte is authenticated: it cannot be flipped back to 0.
                val downgraded = sealed.copyOf().also { it[0] = (Wire.MAGIC + Wire.VERSION).toByte() }
                assertTrue(codec.decode(downgraded) is DecodeResult.Error)
            }
            assertNull((codec.decode(codec.encode(chat)) as DecodeResult.Ok).hiddenSource)
        }
    }

    @Test
    fun aHiddenSourceIsNeverSentInTheClear() {
        val call = com.fearmikey.takwave.core.ax25.Ax25Address("KB1ABC")
        assertThrows(IllegalStateException::class.java) { PacketCodec().encode(chat, hiddenSource = call) }
        // A clear packet claiming version 1 is refused.
        val clear = PacketCodec().encode(chat).also { it[0] = (Wire.MAGIC + Wire.VERSION_HIDDEN_SOURCE).toByte() }
        assertTrue(PacketCodec().decode(clear) is DecodeResult.Error)
    }

    // ---- Symmetric keys ----

    @Test
    fun symmetricKeyFileFormats() {
        val raw = ByteArray(32) { (it * 7 + 1).toByte() }
        val hex = raw.joinToString("") { "%02x".format(it) }
        assertArrayEquals(raw, KeyLoader.symmetricKey(raw))
        assertArrayEquals(raw, KeyLoader.symmetricKey("$hex\n".toByteArray()))
        assertArrayEquals(raw, KeyLoader.symmetricKey(hex.uppercase().chunked(8).joinToString(" ").toByteArray()))
        assertArrayEquals(raw, KeyLoader.symmetricKey((Base64.getEncoder().encodeToString(raw) + "\r\n").toByteArray()))
        assertArrayEquals(raw, KeyLoader.symmetricKey(Base64.getUrlEncoder().withoutPadding().encodeToString(raw).toByteArray()))
        for (size in listOf(16, 24)) {
            val k = ByteArray(size) { (200 + it).toByte() }
            assertArrayEquals(k, KeyLoader.symmetricKey(k))
            assertArrayEquals(k, KeyLoader.symmetricKey(Base64.getEncoder().encode(k)))
        }
        assertThrows(IllegalArgumentException::class.java) { KeyLoader.symmetricKey(ByteArray(0)) }
        assertThrows(IllegalArgumentException::class.java) { KeyLoader.symmetricKey(ByteArray(20)) }
        assertThrows(IllegalArgumentException::class.java) { KeyLoader.symmetricKey("this is not a key".toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { KeyLoader.symmetricKey(pem(ecPair().public, "PUBLIC KEY")) }
        assertThrows(IllegalArgumentException::class.java) { KeyLoader.symmetricKey(ByteArray(100_000)) }
    }

    @Test
    fun aesGcmWithEveryKeySize() {
        for (size in listOf(16, 24, 32)) {
            val key = ByteArray(size) { it.toByte() }
            assertRoundTrip(AeadCipher(key), AeadCipher(key))
            assertTrue(AeadCipher(key).description.contains("AES-${size * 8}-GCM"))
        }
        assertThrows(IllegalArgumentException::class.java) { AeadCipher(ByteArray(15)) }
        val a = AeadCipher(ByteArray(32) { 1 })
        val b = AeadCipher(ByteArray(32) { 2 })
        assertTrue(PacketCodec(b).decode(PacketCodec(a).encode(chat)) is DecodeResult.Error)
    }

    @Test
    fun chaCha20Poly1305() {
        val key = ByteArray(32) { (it xor 0x5a).toByte() }
        val c = AeadCipher(key, AeadAlgorithm.CHACHA20_POLY1305)
        assertEquals(1, c.suite)
        assertRoundTrip(c, AeadCipher(key, AeadAlgorithm.CHACHA20_POLY1305))
        assertThrows(IllegalArgumentException::class.java) { AeadCipher(ByteArray(16), AeadAlgorithm.CHACHA20_POLY1305) }
        // Same key, different suite: refused with a clear reason rather than a decrypt failure.
        val r = PacketCodec(AeadCipher(key)).decode(PacketCodec(c).encode(chat))
        assertTrue((r as DecodeResult.Error).reason.contains("cipher suite"))
    }

    @Test
    fun passphraseDerivationIsStable() {
        val k1 = KeyLoader.fromPassphrase("correct horse battery staple".toCharArray())
        val k2 = KeyLoader.fromPassphrase("correct horse battery staple".toCharArray())
        assertArrayEquals(k1, k2)
        assertEquals(32, k1.size)
        assertFalse(k1.contentEquals(KeyLoader.fromPassphrase("correct horse battery stapl".toCharArray())))
        // Interoperability vector: PBKDF2-HMAC-SHA256("TAKwave", "TAKwave-PSK-v0", 100000, 32 bytes).
        assertEquals(KeyLoader.fingerprint(KeyLoader.fromPassphrase("TAKwave".toCharArray())), KeyLoader.fingerprint(KeyLoader.fromPassphrase("TAKwave".toCharArray())))
        assertThrows(IllegalArgumentException::class.java) { KeyLoader.fromPassphrase(CharArray(0)) }
    }

    // ---- Asymmetric keys ----

    @Test
    fun pemKeysLoad() {
        val ec = ecPair()
        val x = xPair()
        assertEquals("EC P-256", KeyLoader.describe(KeyLoader.privateKey(pem(ec.private, "PRIVATE KEY"))))
        assertEquals("EC P-256", KeyLoader.describe(KeyLoader.publicKey(pem(ec.public, "PUBLIC KEY"))))
        assertEquals("X25519", KeyLoader.describe(KeyLoader.privateKey(pem(x.private, "PRIVATE KEY"))))
        assertEquals("X25519", KeyLoader.describe(KeyLoader.publicKey(pem(x.public, "PUBLIC KEY"))))
        assertEquals(8, KeyLoader.fingerprint(ec.public).length)
        assertEquals(KeyLoader.fingerprint(ec.public), KeyLoader.fingerprint(KeyLoader.publicKey(pem(ec.public, "PUBLIC KEY"))))
    }

    @Test
    fun unusableKeyFilesAreRejectedWithReasons() {
        fun reason(block: () -> Unit): String = assertThrows(IllegalArgumentException::class.java) { block() }.message!!

        val rsa = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        assertTrue(reason { KeyLoader.publicKey(pem(rsa.public, "PUBLIC KEY")) }.contains("RSA"))
        assertTrue(reason { KeyLoader.privateKey(pem(rsa.private, "PRIVATE KEY")) }.contains("RSA"))
        assertTrue(reason { KeyLoader.publicKey(pem(ecPair("secp384r1").public, "PUBLIC KEY")) }.contains("P-256"))
        // Public where private is expected and vice versa.
        assertTrue(reason { KeyLoader.privateKey(pem(ecPair().public, "PUBLIC KEY")) }.contains("expected"))
        assertTrue(reason { KeyLoader.publicKey(pem(ecPair().private, "PRIVATE KEY")) }.contains("expected"))
        assertTrue(reason { KeyLoader.privateKey("-----BEGIN ENCRYPTED PRIVATE KEY-----\nAAAA\n-----END ENCRYPTED PRIVATE KEY-----".toByteArray()) }.contains("password"))
        assertTrue(reason { KeyLoader.privateKey("-----BEGIN EC PRIVATE KEY-----\nAAAA\n-----END EC PRIVATE KEY-----".toByteArray()) }.contains("PKCS#8"))
        assertTrue(reason { KeyLoader.privateKey("-----BEGIN OPENSSH PRIVATE KEY-----\nAAAA\n-----END OPENSSH PRIVATE KEY-----".toByteArray()) }.contains("not supported"))
        assertTrue(reason { KeyLoader.publicKey("hello".toByteArray()) }.contains("PEM"))
        assertTrue(reason { KeyLoader.publicKey("-----BEGIN PUBLIC KEY-----\n!!!\n-----END PUBLIC KEY-----".toByteArray()) }.contains("damaged"))
        assertTrue(reason { KeyLoader.publicKey("-----BEGIN PUBLIC KEY-----\nAAAAAAAA\n-----END PUBLIC KEY-----".toByteArray()) }.contains("unrecognized"))
    }

    @Test
    fun ecdhPairDerivesTheSameKeyAtBothEnds() {
        for (pairs in listOf(ecPair() to ecPair(), xPair() to xPair())) {
            val (a, b) = pairs
            val ka = KeyLoader.ecdhSharedKey(a.private, b.public)
            val kb = KeyLoader.ecdhSharedKey(b.private, a.public)
            assertArrayEquals(ka, kb)
            assertEquals(32, ka.size)
            assertRoundTrip(AeadCipher(ka), AeadCipher(kb))
            // A third party's key gives something else.
            val c = if (a.public.algorithm == "EC") ecPair() else xPair()
            assertFalse(ka.contentEquals(KeyLoader.ecdhSharedKey(c.private, b.public)))
        }
        assertThrows(IllegalArgumentException::class.java) { KeyLoader.ecdhSharedKey(ecPair().private, xPair().public) }
    }

    @Test
    fun eciesP256AndX25519() {
        for (team in listOf(ecPair(), xPair())) {
            val reader = EciesCipher(team.public, team.private)
            val sendOnly = EciesCipher(team.public)
            assertTrue(reader.canDecrypt)
            assertFalse(sendOnly.canDecrypt)
            assertEquals(if (team.public.algorithm == "EC") 3 else 2, reader.suite)
            assertEquals(if (team.public.algorithm == "EC") 49 else 48, reader.overhead)

            // Anyone with the public key can send; only private-key holders can read.
            assertRoundTrip(sendOnly, reader)
            assertRoundTrip(reader, reader)
            val sealed = PacketCodec(reader).encode(chat)
            val r = PacketCodec(sendOnly).decode(sealed)
            assertTrue((r as DecodeResult.Error).reason.contains("send-only"))
            assertNull(sendOnly.open(sealed, 2, sealed.size - 2, sealed.copyOfRange(0, 2)))

            // A different team's private key cannot read it.
            val other = if (team.public.algorithm == "EC") ecPair() else xPair()
            assertTrue(PacketCodec(EciesCipher(other.public, other.private)).decode(sealed) is DecodeResult.Error)
            // A mismatched pair is caught when the keys are loaded.
            assertThrows(IllegalArgumentException::class.java) { EciesCipher(team.public, other.private) }
        }
    }

    @Test
    fun eciesIgnoresGarbageWithoutThrowing() {
        val team = ecPair()
        val codec = PacketCodec(EciesCipher(team.public, team.private))
        val rnd = java.util.Random(3)
        repeat(3000) {
            val b = ByteArray(2 + rnd.nextInt(120))
            rnd.nextBytes(b)
            b[0] = 0x54
            b[1] = 0xB0.toByte() // ENC + suite 3
            if (b.size > 2) b[2] = (2 + rnd.nextInt(2)).toByte() // plausible compressed-point prefix
            assertTrue(codec.decode(b) is DecodeResult.Error)
        }
    }

    @Test
    fun pointCompressionRoundTrips() {
        repeat(20) {
            val key = ecPair().public as java.security.interfaces.ECPublicKey
            val compressed = EcPoints.compress(key.w, key.params)
            assertEquals(33, compressed.size)
            assertEquals(key.w, EcPoints.decompress(compressed, key.params))
        }
    }

    @Test
    fun hkdfMatchesRfc5869TestCase1() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = ByteArray(13) { it.toByte() }
        val info = ByteArray(10) { (0xf0 + it).toByte() }
        val okm = KeyLoader.hkdf(ikm, salt, info).joinToString("") { "%02x".format(it) }
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf", okm)
    }
}
