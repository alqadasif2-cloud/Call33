package app.backup.core

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM + PBKDF2-HMAC-SHA256 (UTF-8 RFC 8018).
 * متوافق بايتًا ببايت بين backup-app و viewer-app وتطبيق الويب (WebCrypto).
 */
object Crypto {
    const val ITERATIONS = 310_000
    private val rnd = SecureRandom()

    class Box(val iv: ByteArray, val data: ByteArray)

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { rnd.nextBytes(it) }

    /**
     * اشتقاق مفتاح 256-بت عبر PBKDF2-HMAC-SHA256 على تمثيل UTF-8 القياسي (RFC 8018)،
     * مما يضمن تطابق المفاتيح 100% مع WebCrypto عبر جميع الأنظمة ولجميع اللغات (بما فيها العربية).
     */
    fun deriveKey(pass: CharArray, salt: ByteArray, iterations: Int = ITERATIONS): ByteArray {
        val pwBytes = String(pass).toByteArray(Charsets.UTF_8)
        try {
            return pbkdf2HmacSha256SingleBlock(pwBytes, salt, iterations)
        } finally {
            pwBytes.fill(0)
        }
    }

    /**
     * يشتق معرّف الحساب السحابي الحتمي (v_ + 48 محرف ست عشري = 192-bit capability)
     * من (usernameId, password) عبر 310,000 دورة PBKDF2-HMAC-SHA256 مع فصل نطاق (Domain Separation)
     * مستقل تمامًا عن مفتاح تشفير البيانات الطرفي.
     */
    fun deriveCapabilityUid(
        usernameId: String,
        password: String,
        iterations: Int = ITERATIONS,
    ): String {
        val pwBytes = password.toByteArray(Charsets.UTF_8)
        val saltBytes = "personal-backup-auth-v2:$usernameId".toByteArray(Charsets.UTF_8)
        val prefix = "uid:".toByteArray(Charsets.UTF_8)
        var seed: ByteArray? = null
        var combined: ByteArray? = null
        try {
            seed = pbkdf2HmacSha256SingleBlock(pwBytes, saltBytes, iterations)
            combined = ByteArray(prefix.size + seed.size)
            System.arraycopy(prefix, 0, combined, 0, prefix.size)
            System.arraycopy(seed, 0, combined, prefix.size, seed.size)
            val hex = sha256Hex(combined)
            return "v_${hex.take(48)}"
        } finally {
            pwBytes.fill(0)
            seed?.fill(0)
            combined?.fill(0)
        }
    }

    private fun pbkdf2HmacSha256SingleBlock(
        passwordUtf8: ByteArray,
        salt: ByteArray,
        iterations: Int,
    ): ByteArray {
        require(iterations >= 1) { "iterations must be >= 1" }
        val keyBytes = if (passwordUtf8.isEmpty()) ByteArray(1) else passwordUtf8
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(keyBytes, "HmacSHA256"))
        mac.update(salt)
        var u = mac.doFinal(byteArrayOf(0, 0, 0, 1))
        val out = u.copyOf()
        for (i in 2..iterations) {
            u = mac.doFinal(u)
            for (k in 0 until 32) {
                out[k] = (out[k].toInt() xor u[k].toInt()).toByte()
            }
        }
        u.fill(0)
        return out
    }

    fun encrypt(key: ByteArray, plain: ByteArray, aad: ByteArray): Box {
        val iv = randomBytes(12)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        c.updateAAD(aad)
        return Box(iv, c.doFinal(plain))
    }

    fun decrypt(key: ByteArray, iv: ByteArray, data: ByteArray, aad: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        c.updateAAD(aad)
        return c.doFinal(data)
    }

    fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
    fun unb64(s: String): ByteArray = Base64.getDecoder().decode(s)

    fun sha256Hex(s: String): String = sha256Hex(s.toByteArray(Charsets.UTF_8))
    fun sha256Hex(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(b)
            .joinToString("") { String.format(Locale.US, "%02x", it) }
}
