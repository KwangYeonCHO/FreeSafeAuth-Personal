package com.kmelect.freesafeauth.totp

import android.net.Uri
import java.net.URLDecoder

data class OtpAuthData(
    val issuer: String,
    val accountName: String,
    val secret: String,
    val algorithm: String = "SHA1",
    val digits: Int = 6,
    val period: Int = 30
)

object OtpAuthParser {
    fun parse(raw: String): OtpAuthData {
        val uri = Uri.parse(raw)
        require(uri.scheme == "otpauth" && uri.host == "totp") { "不是有效的 TOTP 二维码" }

        val label = URLDecoder.decode(uri.path.orEmpty().removePrefix("/"), "UTF-8")
        val labelParts = label.split(":", limit = 2)
        val issuerFromLabel = labelParts.getOrNull(0).orEmpty()
        val account = labelParts.getOrNull(1) ?: issuerFromLabel
        val issuer = uri.getQueryParameter("issuer") ?: issuerFromLabel
        val secret = uri.getQueryParameter("secret").orEmpty()
        val algorithm = uri.getQueryParameter("algorithm") ?: "SHA1"
        val digits = uri.getQueryParameter("digits")?.toIntOrNull() ?: 6
        val period = uri.getQueryParameter("period")?.toIntOrNull() ?: 30

        require(secret.isNotBlank() && Base32.isValid(secret)) { "Secret Key 格式错误" }
        return OtpAuthData(
            issuer = issuer.ifBlank { "未命名服务" },
            accountName = account.ifBlank { "未命名账号" },
            secret = Base32.normalize(secret),
            algorithm = algorithm.uppercase(),
            digits = digits,
            period = period
        )
    }
}
