// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.core.codec

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAKey
import java.security.spec.ECFieldFp
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Seals and opens the over-air payload. Implementations are selected by the operator; the
 * [suite] number travels in the packet header so a receiver can tell a mismatch from corruption.
 *
 * **Encryption is not permitted on amateur radio frequencies.** It exists for Part 90 use.
 */
interface PayloadCipher {
    /** Cipher suite id, 0-7, carried in bits 6-4 of payload byte 1. */
    val suite: Int

    /** Bytes added to the plaintext by [seal]. */
    val overhead: Int

    /** False for a send-only station (public key without the matching private key). */
    val canDecrypt: Boolean

    /** Human-readable summary for the UI and log, without any secret material. */
    val description: String

    /** Encrypts [plain]; [aad] is authenticated but not encrypted. */
    fun seal(plain: ByteArray, aad: ByteArray): ByteArray

    /** Decrypts and authenticates. Returns null when the data is corrupt or the key is wrong. Never throws. */
    fun open(data: ByteArray, offset: Int, length: Int, aad: ByteArray): ByteArray?
}

enum class AeadAlgorithm(val suite: Int, val label: String) {
    AES_GCM(0, "AES-GCM"),
    CHACHA20_POLY1305(1, "ChaCha20-Poly1305"),
}

/** Low-level AEAD helpers shared by the suites. Nonces are 96 bits, tags 128 bits. */
internal object Aead {
    const val NONCE_SIZE = 12
    const val TAG_SIZE = 16

    private fun chacha(): Cipher = try {
        Cipher.getInstance("ChaCha20-Poly1305") // JDK name
    } catch (e: Exception) {
        Cipher.getInstance("ChaCha20/Poly1305/NoPadding") // Android name (API 28+)
    }

    private fun cipher(algorithm: AeadAlgorithm, mode: Int, key: ByteArray, nonce: ByteArray, nonceOffset: Int): Cipher =
        when (algorithm) {
            AeadAlgorithm.AES_GCM -> Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_SIZE * 8, nonce, nonceOffset, NONCE_SIZE))
            }
            AeadAlgorithm.CHACHA20_POLY1305 -> chacha().apply {
                init(mode, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce, nonceOffset, NONCE_SIZE))
            }
        }

    fun encrypt(algorithm: AeadAlgorithm, key: ByteArray, nonce: ByteArray, plain: ByteArray, aad: ByteArray): ByteArray {
        val c = cipher(algorithm, Cipher.ENCRYPT_MODE, key, nonce, 0)
        c.updateAAD(aad)
        return c.doFinal(plain)
    }

    fun decrypt(
        algorithm: AeadAlgorithm, key: ByteArray, nonce: ByteArray, nonceOffset: Int,
        data: ByteArray, offset: Int, length: Int, aad: ByteArray,
    ): ByteArray {
        val c = cipher(algorithm, Cipher.DECRYPT_MODE, key, nonce, nonceOffset)
        c.updateAAD(aad)
        return c.doFinal(data, offset, length)
    }
}

/**
 * Symmetric suite (ids 0 and 1): a shared key, a random 96-bit nonce per packet.
 * Sealed form: nonce (12) | ciphertext | tag (16).
 * The key may come from a passphrase, a key file, or an ECDH agreement; see [KeyLoader].
 */
class AeadCipher(
    key: ByteArray,
    private val algorithm: AeadAlgorithm = AeadAlgorithm.AES_GCM,
    private val source: String = "shared key",
    private val random: SecureRandom = SecureRandom(),
) : PayloadCipher {
    private val key = key.copyOf()

    init {
        when (algorithm) {
            AeadAlgorithm.AES_GCM -> require(key.size == 16 || key.size == 24 || key.size == 32) { "AES keys are 16, 24 or 32 bytes (got ${key.size})" }
            AeadAlgorithm.CHACHA20_POLY1305 -> require(key.size == 32) { "ChaCha20-Poly1305 needs a 32-byte key (got ${key.size})" }
        }
        // Fail now, not at the first transmission, if the platform lacks the algorithm.
        Aead.encrypt(algorithm, this.key, ByteArray(Aead.NONCE_SIZE), ByteArray(1), ByteArray(0))
    }

    override val suite: Int get() = algorithm.suite
    override val overhead: Int get() = Aead.NONCE_SIZE + Aead.TAG_SIZE
    override val canDecrypt: Boolean get() = true
    override val description: String
        get() = "${if (algorithm == AeadAlgorithm.AES_GCM) "AES-${key.size * 8}-GCM" else algorithm.label}, $source, key id ${KeyLoader.fingerprint(key)}"

    override fun seal(plain: ByteArray, aad: ByteArray): ByteArray {
        val nonce = ByteArray(Aead.NONCE_SIZE)
        random.nextBytes(nonce)
        return nonce + Aead.encrypt(algorithm, key, nonce, plain, aad)
    }

    override fun open(data: ByteArray, offset: Int, length: Int, aad: ByteArray): ByteArray? {
        if (length < overhead) return null
        return try {
            Aead.decrypt(algorithm, key, data, offset, data, offset + Aead.NONCE_SIZE, length - Aead.NONCE_SIZE, aad)
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Public-key suite (ids 2 and 3): ECIES. Every packet is encrypted to the team's public key
 * with a fresh ephemeral key, so a station that holds only the public key can send but cannot
 * read anyone's traffic. Stations that should read traffic also load the team private key.
 *
 * Sealed form: ephemeral public key (32 bytes X25519, or 33 bytes compressed P-256) |
 * AES-256-GCM ciphertext | tag (16). The AES key is HKDF-SHA256(ECDH secret, salt
 * "TAKwave-ECIES-v0", info = ephemeral public | recipient public). Because that key is used
 * for exactly one packet, the GCM nonce is twelve zero bytes.
 */
class EciesCipher(
    private val recipientPublic: PublicKey,
    private val ownPrivate: PrivateKey? = null,
    private val random: SecureRandom = SecureRandom(),
) : PayloadCipher {
    private val isEc = recipientPublic is ECPublicKey
    private val ecParams: ECParameterSpec? = (recipientPublic as? ECPublicKey)?.params
    private val recipientBytes: ByteArray
    private val publicSize: Int

    init {
        if (isEc) {
            EcPoints.requireP256(ecParams!!)
            recipientBytes = EcPoints.compress((recipientPublic as ECPublicKey).w, ecParams)
            publicSize = 33
        } else {
            require(KeyLoader.isX25519(recipientPublic)) { "unsupported public key type ${recipientPublic.algorithm}; use EC P-256 or X25519" }
            recipientBytes = KeyLoader.x25519Raw(recipientPublic)
            publicSize = 32
        }
        // Prove the key pair generator and agreement exist on this platform, and that a private
        // key, if given, actually matches the public key.
        val probe = seal(byteArrayOf(1), ByteArray(0))
        if (ownPrivate != null) {
            require(open(probe, 0, probe.size, ByteArray(0)) != null) { "the private key does not match the public key" }
        }
    }

    override val suite: Int get() = if (isEc) SUITE_P256 else SUITE_X25519
    override val overhead: Int get() = publicSize + Aead.TAG_SIZE
    override val canDecrypt: Boolean get() = ownPrivate != null
    override val description: String
        get() = "ECIES ${if (isEc) "P-256" else "X25519"} + AES-256-GCM, team key id ${KeyLoader.fingerprint(recipientBytes)}" +
            if (canDecrypt) "" else " (send-only: no private key loaded)"

    private fun agreementName() = if (isEc) "ECDH" else "X25519"

    private fun derive(shared: ByteArray, ephemeral: ByteArray): ByteArray =
        KeyLoader.hkdf(shared, SALT.toByteArray(Charsets.US_ASCII), ephemeral + recipientBytes)

    override fun seal(plain: ByteArray, aad: ByteArray): ByteArray {
        val generator = if (isEc) {
            KeyPairGenerator.getInstance("EC").apply { initialize(ecParams, random) }
        } else {
            KeyLoader.x25519Generator()
        }
        val ephemeral = generator.generateKeyPair()
        val agreement = KeyAgreement.getInstance(agreementName())
        agreement.init(ephemeral.private)
        agreement.doPhase(recipientPublic, true)
        val ephemeralBytes = if (isEc) EcPoints.compress((ephemeral.public as ECPublicKey).w, ecParams!!) else KeyLoader.x25519Raw(ephemeral.public)
        val key = derive(agreement.generateSecret(), ephemeralBytes)
        return ephemeralBytes + Aead.encrypt(AeadAlgorithm.AES_GCM, key, ByteArray(Aead.NONCE_SIZE), plain, aad)
    }

    override fun open(data: ByteArray, offset: Int, length: Int, aad: ByteArray): ByteArray? {
        val priv = ownPrivate ?: return null
        if (length < overhead) return null
        return try {
            val ephemeralBytes = data.copyOfRange(offset, offset + publicSize)
            val ephemeralKey: PublicKey = if (isEc) {
                KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(EcPoints.decompress(ephemeralBytes, ecParams!!), ecParams))
            } else {
                KeyLoader.x25519FromRaw(ephemeralBytes)
            }
            val agreement = KeyAgreement.getInstance(agreementName())
            agreement.init(priv)
            agreement.doPhase(ephemeralKey, true)
            val key = derive(agreement.generateSecret(), ephemeralBytes)
            Aead.decrypt(
                AeadAlgorithm.AES_GCM, key, ByteArray(Aead.NONCE_SIZE), 0,
                data, offset + publicSize, length - publicSize, aad,
            )
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        const val SUITE_X25519 = 2
        const val SUITE_P256 = 3
        const val SALT = "TAKwave-ECIES-v0"
    }
}

/** SEC 1 point compression for prime curves with p = 3 (mod 4), which includes P-256. */
internal object EcPoints {
    fun requireP256(params: ECParameterSpec) {
        val field = params.curve.field
        require(field is ECFieldFp && field.fieldSize == 256 && field.p.testBit(1)) {
            "only 256-bit prime curves (NIST P-256 / secp256r1) are supported"
        }
    }

    fun compress(point: ECPoint, params: ECParameterSpec): ByteArray {
        val size = (params.curve.field.fieldSize + 7) / 8
        val out = ByteArray(1 + size)
        out[0] = if (point.affineY.testBit(0)) 3 else 2
        val x = point.affineX.toByteArray()
        val n = minOf(x.size, size)
        System.arraycopy(x, x.size - n, out, 1 + size - n, n)
        return out
    }

    fun decompress(bytes: ByteArray, params: ECParameterSpec): ECPoint {
        val p = (params.curve.field as ECFieldFp).p
        require(bytes[0].toInt() == 2 || bytes[0].toInt() == 3) { "not a compressed point" }
        val x = BigInteger(1, bytes.copyOfRange(1, bytes.size))
        require(x < p) { "x out of range" }
        val rhs = x.pow(3).add(params.curve.a.multiply(x)).add(params.curve.b).mod(p)
        var y = rhs.modPow(p.add(BigInteger.ONE).shiftRight(2), p)
        require(y.multiply(y).mod(p) == rhs) { "point is not on the curve" }
        if (y.testBit(0) != (bytes[0].toInt() == 3)) y = p.subtract(y)
        return ECPoint(x, y)
    }
}

/**
 * Turns operator-supplied key material into usable keys. Every function validates its input and
 * throws [IllegalArgumentException] with a message suitable for showing to the operator.
 */
object KeyLoader {
    const val PASSPHRASE_SALT = "TAKwave-PSK-v0"
    const val PASSPHRASE_ITERATIONS = 100_000
    const val ECDH_SALT = "TAKwave-ECDH-v0"
    const val MAX_KEY_FILE_BYTES = 16 * 1024

    private val X25519_SPKI_PREFIX = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x6e, 0x03, 0x21, 0x00)

    /**
     * PBKDF2-HMAC-SHA256 with a fixed, public salt, so every station derives the same 256-bit
     * key from the same passphrase. The passphrase has to carry all the entropy.
     */
    fun fromPassphrase(passphrase: CharArray): ByteArray {
        require(passphrase.isNotEmpty()) { "passphrase must not be empty" }
        val spec = PBEKeySpec(passphrase, PASSPHRASE_SALT.toByteArray(Charsets.US_ASCII), PASSPHRASE_ITERATIONS, 256)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /**
     * Reads a symmetric key file: 16, 24 or 32 raw bytes, or the same written as hex or Base64
     * text (whitespace and a trailing newline are ignored).
     */
    fun symmetricKey(file: ByteArray): ByteArray {
        require(file.isNotEmpty()) { "the key file is empty" }
        require(file.size <= MAX_KEY_FILE_BYTES) { "the key file is too large" }
        val text = String(file, Charsets.ISO_8859_1).filter { !it.isWhitespace() }
        require(!text.startsWith("-----BEGIN")) { "this is a PEM key pair file, not a symmetric key; load it as a public or private key" }
        if (text.matches(Regex("[0-9a-fA-F]+")) && text.length in setOf(32, 48, 64)) {
            return ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }
        if (text.matches(Regex("[A-Za-z0-9+/_-]+=*")) && text.length in 22..44) {
            val decoded = try {
                Base64.getDecoder().decode(text.replace('-', '+').replace('_', '/').trimEnd('=').let { it + "=".repeat((4 - it.length % 4) % 4) })
            } catch (e: IllegalArgumentException) {
                null
            }
            if (decoded != null && decoded.size in setOf(16, 24, 32)) return decoded
        }
        require(file.size in setOf(16, 24, 32)) {
            "not a symmetric key: expected 16, 24 or 32 bytes, raw or as hex or Base64 (the file is ${file.size} bytes)"
        }
        return file.copyOf()
    }

    private fun pemBody(file: ByteArray, wanted: String): ByteArray {
        require(file.size <= MAX_KEY_FILE_BYTES) { "the key file is too large" }
        val text = String(file, Charsets.ISO_8859_1)
        val header = Regex("-----BEGIN ([A-Z0-9 ]+)-----").find(text)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("not a PEM file (no -----BEGIN ...----- line)")
        when {
            header == wanted -> {}
            header == "ENCRYPTED PRIVATE KEY" -> throw IllegalArgumentException(
                "password-protected private keys are not supported; decrypt it first (openssl pkcs8 -topk8 -nocrypt)",
            )
            header == "EC PRIVATE KEY" || header == "RSA PRIVATE KEY" -> throw IllegalArgumentException(
                "legacy '$header' format; convert to PKCS#8 first (openssl pkcs8 -topk8 -nocrypt -in key.pem -out key8.pem)",
            )
            header.startsWith("OPENSSH") || header.startsWith("PGP") || header == "CERTIFICATE" -> throw IllegalArgumentException(
                "'$header' files are not supported; export a PKCS#8 private key or an X.509 SubjectPublicKeyInfo public key in PEM",
            )
            else -> throw IllegalArgumentException("expected a '$wanted' PEM file but found '$header'")
        }
        val body = text.substringAfter("-----BEGIN $wanted-----").substringBefore("-----END").filter { !it.isWhitespace() }
        return try {
            Base64.getDecoder().decode(body)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("the PEM file is damaged (invalid Base64)")
        }
    }

    private val ASYMMETRIC_ALGORITHMS = listOf("EC", "X25519", "XDH", "RSA")

    private fun <T> firstWorking(build: (KeyFactory) -> T): T {
        for (name in ASYMMETRIC_ALGORITHMS) {
            try {
                return build(KeyFactory.getInstance(name))
            } catch (e: Exception) {
                // Wrong algorithm for this key, or not available on this platform: try the next.
            }
        }
        throw IllegalArgumentException("unrecognised key type; supported: EC P-256 and X25519 (X25519 needs a recent Android version)")
    }

    private fun rejectUnusable(key: java.security.Key) {
        require(key !is RSAKey) {
            "RSA keys cannot be used: an RSA ciphertext is at least 256 bytes and does not fit in a radio packet. Use EC P-256 or X25519."
        }
        if (key is java.security.interfaces.ECKey) EcPoints.requireP256(key.params)
    }

    /** Reads an unencrypted PKCS#8 private key in PEM ("-----BEGIN PRIVATE KEY-----"). */
    fun privateKey(file: ByteArray): PrivateKey {
        val der = pemBody(file, "PRIVATE KEY")
        return firstWorking { it.generatePrivate(PKCS8EncodedKeySpec(der)) }.also { rejectUnusable(it) }
    }

    /** Reads an X.509 SubjectPublicKeyInfo public key in PEM ("-----BEGIN PUBLIC KEY-----"). */
    fun publicKey(file: ByteArray): PublicKey {
        val der = pemBody(file, "PUBLIC KEY")
        return firstWorking { it.generatePublic(X509EncodedKeySpec(der)) }.also { rejectUnusable(it) }
    }

    /**
     * Static Diffie-Hellman: both ends of a link combine their own private key with the other's
     * public key and arrive at the same 256-bit symmetric key.
     */
    fun ecdhSharedKey(ownPrivate: PrivateKey, peerPublic: PublicKey): ByteArray {
        val name = if (peerPublic is ECPublicKey) "ECDH" else "X25519"
        val secret = try {
            KeyAgreement.getInstance(name).run {
                init(ownPrivate)
                doPhase(peerPublic, true)
                generateSecret()
            }
        } catch (e: Exception) {
            throw IllegalArgumentException("the private and public keys are not of the same type (${ownPrivate.algorithm} vs ${peerPublic.algorithm})")
        }
        return hkdf(secret, ECDH_SALT.toByteArray(Charsets.US_ASCII), "pair".toByteArray(Charsets.US_ASCII))
    }

    /** HKDF-SHA256 (RFC 5869) producing one 32-byte block. */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update(info)
        mac.update(1)
        return mac.doFinal()
    }

    /** A short, non-secret identifier for comparing keys between stations: the first 4 bytes of SHA-256, in hex. */
    fun fingerprint(material: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(material).take(4).joinToString("") { "%02x".format(it) }

    fun fingerprint(key: PublicKey): String = fingerprint(
        if (key is ECPublicKey) EcPoints.compress(key.w, key.params) else x25519Raw(key),
    )

    fun describe(key: java.security.Key): String = when {
        key is java.security.interfaces.ECKey -> "EC P-256"
        key.algorithm == "X25519" || key.algorithm == "XDH" -> "X25519"
        else -> key.algorithm
    }

    internal fun isX25519(key: PublicKey): Boolean =
        (key.algorithm == "X25519" || key.algorithm == "XDH") && key.encoded?.size == X25519_SPKI_PREFIX.size + 32

    internal fun x25519Raw(key: PublicKey): ByteArray = key.encoded.copyOfRange(X25519_SPKI_PREFIX.size, X25519_SPKI_PREFIX.size + 32)

    internal fun x25519FromRaw(raw: ByteArray): PublicKey {
        val spec = X509EncodedKeySpec(X25519_SPKI_PREFIX + raw)
        return try {
            KeyFactory.getInstance("X25519").generatePublic(spec)
        } catch (e: java.security.NoSuchAlgorithmException) {
            KeyFactory.getInstance("XDH").generatePublic(spec)
        }
    }

    /** Throws NoSuchAlgorithmException on platforms without X25519 (older Android versions). */
    internal fun x25519Generator(): KeyPairGenerator = KeyPairGenerator.getInstance("X25519")
}

/** Convenience constructors for the common symmetric setups. */
object PayloadCrypto {
    fun fromPassphrase(passphrase: CharArray, algorithm: AeadAlgorithm = AeadAlgorithm.AES_GCM): PayloadCipher =
        AeadCipher(KeyLoader.fromPassphrase(passphrase), algorithm, "passphrase")
}
