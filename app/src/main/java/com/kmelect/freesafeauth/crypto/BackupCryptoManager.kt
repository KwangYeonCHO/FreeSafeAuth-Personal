package com.kmelect.freesafeauth.crypto

import android.util.Base64
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object BackupCryptoManager {
    private const val iterations = 120_000
    private const val keyBits = 256

    fun encryptBackup(json: String, password: String): String {
        require(password.length >= 6) { "备份密码至少需要 6 位" }
        val salt = randomBytes(16)
        val iv = randomBytes(12)
        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val cipherText = cipher.doFinal(json.toByteArray(Charsets.UTF_8))

        return JSONObject()
            .put("version", 1)
            .put("app", "FreeSafeAuth Personal")
            .put("encryption", "PBKDF2WithHmacSHA256+AES-GCM")
            .put("iterations", iterations)
            .put("salt", b64(salt))
            .put("iv", b64(iv))
            .put("cipherText", b64(cipherText))
            .toString(2)
    }

    fun decryptBackup(encryptedJson: String, password: String): String {
        val root = JSONObject(encryptedJson)
        require(root.optString("app") == "FreeSafeAuth Personal") { "备份文件格式错误" }
        val salt = Base64.decode(root.getString("salt"), Base64.NO_WRAP)
        val iv = Base64.decode(root.getString("iv"), Base64.NO_WRAP)
        val cipherText = Base64.decode(root.getString("cipherText"), Base64.NO_WRAP)
        val key = deriveKey(password, salt, root.optInt("iterations", iterations))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return cipher.doFinal(cipherText).toString(Charsets.UTF_8)
    }

    private fun deriveKey(password: String, salt: ByteArray, rounds: Int = iterations): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, rounds, keyBits)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(bytes, "AES")
    }

    private fun randomBytes(size: Int): ByteArray =
        ByteArray(size).also { SecureRandom().nextBytes(it) }

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
}
