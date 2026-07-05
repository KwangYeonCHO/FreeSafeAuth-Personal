package com.kmelect.freesafeauth.totp

import java.nio.ByteBuffer
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.pow

object TotpGenerator {
    fun generateCode(
        secretBase32: String,
        timeMillis: Long = System.currentTimeMillis(),
        digits: Int = 6,
        period: Int = 30,
        algorithm: String = "SHA1"
    ): String {
        val counter = timeMillis / 1000L / period
        val key = Base32.decode(secretBase32)
        val algo = when (algorithm.uppercase(Locale.US)) {
            "SHA256" -> "HmacSHA256"
            "SHA512" -> "HmacSHA512"
            else -> "HmacSHA1"
        }
        val mac = Mac.getInstance(algo)
        mac.init(SecretKeySpec(key, algo))
        val hash = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array())
        val offset = hash.last().toInt() and 0x0f
        val binary = ((hash[offset].toInt() and 0x7f) shl 24) or
            ((hash[offset + 1].toInt() and 0xff) shl 16) or
            ((hash[offset + 2].toInt() and 0xff) shl 8) or
            (hash[offset + 3].toInt() and 0xff)
        val mod = 10.0.pow(digits).toInt()
        return (binary % mod).toString().padStart(digits, '0')
    }
}
