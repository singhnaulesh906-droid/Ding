package com.example.security

import android.content.Context
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-End Cryptographic Engine for Ding.
 * Uses ECDH (secp256r1 / P-256) key exchange + SHA-256 key derivation + AES-256-GCM.
 */
class CryptoEngine(private val context: Context? = null) {

    data class EcKeyPair(
        val publicKeyBase64: String,
        val privateKeyBase64: String
    )

    data class EncryptedPayload(
        val ciphertextBase64: String,
        val ivBase64: String
    )

    private val prefs by lazy {
        context?.applicationContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getOrCreateKeyPair(userId: String): EcKeyPair {
        val existingPub = prefs?.getString("${KEY_PUB_PREFIX}$userId", null)
        val existingPriv = prefs?.getString("${KEY_PRIV_PREFIX}$userId", null)
        if (!existingPub.isNullOrBlank() && !existingPriv.isNullOrBlank()) {
            return EcKeyPair(existingPub, existingPriv)
        }
        val generated = generateEcKeyPair()
        prefs?.edit()
            ?.putString("${KEY_PUB_PREFIX}$userId", generated.publicKeyBase64)
            ?.putString("${KEY_PRIV_PREFIX}$userId", generated.privateKeyBase64)
            ?.apply()
        return generated
    }

    fun generateEcKeyPair(): EcKeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        val pair = kpg.generateKeyPair()
        return EcKeyPair(
            publicKeyBase64 = Base64.encodeToString(pair.public.encoded, Base64.NO_WRAP),
            privateKeyBase64 = Base64.encodeToString(pair.private.encoded, Base64.NO_WRAP)
        )
    }

    fun deriveSharedAesKey(myPrivateKeyBase64: String, peerPublicKeyBase64: String): SecretKeySpec {
        val keyFactory = KeyFactory.getInstance("EC")
        val privateKey: PrivateKey = keyFactory.generatePrivate(
            PKCS8EncodedKeySpec(Base64.decode(myPrivateKeyBase64, Base64.NO_WRAP))
        )
        val publicKey: PublicKey = keyFactory.generatePublic(
            X509EncodedKeySpec(Base64.decode(peerPublicKeyBase64, Base64.NO_WRAP))
        )
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(privateKey)
        agreement.doPhase(publicKey, true)
        val rawSecret = agreement.generateSecret()

        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(rawSecret)
        digest.update("ding-e2e-aes-gcm-v1".toByteArray(StandardCharsets.UTF_8))
        val derivedKey = digest.digest()
        return SecretKeySpec(derivedKey, "AES")
    }

    fun encrypt(plaintext: String, aesKey: SecretKeySpec): EncryptedPayload {
        val iv = ByteArray(GCM_IV_LENGTH)
        SecureRandom().nextBytes(iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        val cipherBytes = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        return EncryptedPayload(
            ciphertextBase64 = Base64.encodeToString(cipherBytes, Base64.NO_WRAP),
            ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP)
        )
    }

    fun decrypt(ciphertextBase64: String, ivBase64: String, aesKey: SecretKeySpec): String {
        if (ciphertextBase64.isBlank() || ivBase64.isBlank()) return ""
        return try {
            val iv = Base64.decode(ivBase64, Base64.NO_WRAP)
            val cipherBytes = Base64.decode(ciphertextBase64, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(cipherBytes), StandardCharsets.UTF_8)
        } catch (_: Exception) {
            "🔒 Encrypted with a different device key"
        }
    }

    /**
     * Generates a 60-digit WhatsApp-style Safety Number from both parties' ECDH public keys.
     */
    fun computeSafetyNumber(pubKeyA: String, pubKeyB: String): List<String> {
        val sorted = listOf(pubKeyA, pubKeyB).sorted()
        val digest = MessageDigest.getInstance("SHA-256")
        var state = (sorted[0] + "|" + sorted[1]).toByteArray(StandardCharsets.UTF_8)
        // Iterative stretching for safety code generation
        repeat(64) {
            digest.reset()
            state = digest.digest(state)
        }
        val digits = StringBuilder(60)
        var counter = 0
        while (digits.length < 60) {
            digest.reset()
            digest.update(state)
            digest.update(counter.toByte())
            val block = digest.digest()
            for (i in 0 until block.size - 1 step 2) {
                if (digits.length >= 60) break
                val v = ((block[i].toInt() and 0xFF) shl 8) or (block[i + 1].toInt() and 0xFF)
                digits.append((v % 100000).toString().padStart(5, '0'))
            }
            counter++
        }
        return digits.substring(0, 60).chunked(5)
    }

    fun shortFingerprint(publicKeyBase64: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(publicKeyBase64.toByteArray(StandardCharsets.UTF_8))
        return digest.take(8).joinToString(":") { "%02X".format(it) }
    }

    fun hashPin(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(("ding-pin-salt:" + pin).toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val PREFS_NAME = "ding_crypto_keys"
        const val KEY_PUB_PREFIX = "pub_"
        const val KEY_PRIV_PREFIX = "priv_"
        const val GCM_IV_LENGTH = 12
        const val GCM_TAG_BITS = 128
    }
}
