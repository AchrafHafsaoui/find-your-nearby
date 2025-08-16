package com.fyn.app.core

import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.floor

class RotatingId(private val seed: ByteArray) {

    fun currentRid(epochSec: Long, slotSec: Int): ByteArray {
        val slot = floor(epochSec.toDouble() / slotSec).toLong()
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(seed, "HmacSHA256"))
        val bb = ByteBuffer.allocate(8)
        bb.putLong(slot)
        val h = mac.doFinal(bb.array())
        // Truncate to 8 bytes for adv payload compactness
        return h.copyOf(8)
    }

    companion object {
        fun newSeed(): ByteArray {
            val s = ByteArray(16)
            java.security.SecureRandom().nextBytes(s)
            return s
        }
    }
}
