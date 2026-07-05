package com.kmelect.freesafeauth.totp

object Base32 {
    private const val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private val valid = Regex("^[A-Z2-7= ]+$")

    fun normalize(value: String): String = value.uppercase().replace(" ", "")

    fun isValid(value: String): Boolean {
        val normalized = value.uppercase()
        return normalized.isNotBlank() && valid.matches(normalized)
    }

    fun decode(value: String): ByteArray {
        val clean = normalize(value).trimEnd('=')
        require(clean.isNotBlank()) { "Secret Key 不能为空" }
        var buffer = 0
        var bitsLeft = 0
        val out = ArrayList<Byte>()
        for (char in clean) {
            val index = alphabet.indexOf(char)
            require(index >= 0) { "Secret Key 格式错误" }
            buffer = (buffer shl 5) or index
            bitsLeft += 5
            if (bitsLeft >= 8) {
                out.add(((buffer shr (bitsLeft - 8)) and 0xff).toByte())
                bitsLeft -= 8
            }
        }
        return out.toByteArray()
    }
}
