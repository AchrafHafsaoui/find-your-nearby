package com.fyn.app.core

import android.util.Base64
import java.nio.ByteBuffer
import java.security.*
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object Crypto {

    data class ECDHKeys(val privateKey: PrivateKey, val publicKey: PublicKey)

    fun generateEphemeralEcdh(): ECDHKeys {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1")) // P-256
        val kp = kpg.generateKeyPair()
        return ECDHKeys(kp.private, kp.public)
    }

    fun ecdhSharedSecret(privateKey: PrivateKey, peerPublic: PublicKey): ByteArray {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(privateKey)
        ka.doPhase(peerPublic, true)
        return ka.generateSecret() // ~32 bytes
    }

    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        // HKDF-Extract
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        // HKDF-Expand
        var t = ByteArray(0)
        val okm = ByteArray(length)
        var pos = 0
        var counter = 1
        while (pos < length) {
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(t)
            mac.update(info)
            mac.update(counter.toByte())
            t = mac.doFinal()
            val toCopy = minOf(t.size, length - pos)
            System.arraycopy(t, 0, okm, pos, toCopy)
            pos += toCopy
            counter++
        }
        return okm
    }

    fun aesGcmEncrypt(key: ByteArray, plaintext: ByteArray, aad: ByteArray? = null): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val nonce = SecureRandom().generateSeed(12) // 96-bit nonce
        val sk: SecretKey = SecretKeySpec(key, "AES")
        cipher.init(Cipher.ENCRYPT_MODE, sk, GCMParameterSpec(128, nonce))
        aad?.let { cipher.updateAAD(it) }
        val ct = cipher.doFinal(plaintext)
        return nonce to ct
    }

    fun aesGcmDecrypt(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray? = null): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val sk: SecretKey = SecretKeySpec(key, "AES")
        cipher.init(Cipher.DECRYPT_MODE, sk, GCMParameterSpec(128, nonce))
        aad?.let { cipher.updateAAD(it) }
        return cipher.doFinal(ciphertext)
    }

    fun publicKeyToBytes(pub: PublicKey): ByteArray = pub.encoded
    fun bytesToPublicKey(x509: ByteArray): PublicKey {
        val kf = KeyFactory.getInstance("EC")
        return kf.generatePublic(java.security.spec.X509EncodedKeySpec(x509))
    }

    fun packEnvelope(type: String, rid: ByteArray, json: String): ByteArray {
        val typeB = type.toByteArray(Charsets.UTF_8)
        val ridLen = rid.size
        val jsonB = json.toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.allocate(1 + 1 + ridLen + 4 + typeB.size + jsonB.size)
        buf.put(1) // version
        buf.put(ridLen.toByte())
        buf.put(rid)
        buf.putInt(typeB.size)
        buf.put(typeB)
        buf.put(jsonB)
        return buf.array()
    }

    fun unpackEnvelope(data: ByteArray): Triple<String, ByteArray, String> {
        val buf = ByteBuffer.wrap(data)
        buf.get() // version
        val ridLen = buf.get().toInt()
        val rid = ByteArray(ridLen)
        buf.get(rid)
        val typeLen = buf.int
        val typeB = ByteArray(typeLen)
        buf.get(typeB)
        val remain = ByteArray(buf.remaining())
        buf.get(remain)
        val type = String(typeB, Charsets.UTF_8)
        val json = String(remain, Charsets.UTF_8)
        return Triple(type, rid, json)
    }

    fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
}
